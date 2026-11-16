/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package com.biglybt.android.client;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Torrent TV: updates from the GitHub releases of the app, without going
 * through Downloader. Checked when the app opens (at most every few hours)
 * and from Settings, "Check for updates".
 */
public final class AppUpdater
{
	private static final String TAG = "AppUpdater";

	private static final String API = "https://api.github.com/repos/SickB0oy/torrent-tv/releases/latest";

	private static final String APK_NAME = "Torrent-TV.apk";

	private static final String PREFS = "plus_prefs";

	private static final String KEY_LAST_CHECK = "update_last_check";

	private static final String KEY_SKIPPED = "update_skipped";

	private static final long AUTO_EVERY_MS = 6L * 60 * 60 * 1000;

	private static boolean busy;

	private static final Handler main = new Handler(Looper.getMainLooper());

	private static final class Release
	{
		int build;

		String version;

		String apkUrl;
	}

	private AppUpdater() {
	}

	/** This APK's build number (set by the GitHub build), 0 for a local build */
	public static int currentBuild() {
		return BuildConfig.TTV_BUILD;
	}

	/** "1.3.4.4-plus47 (build 64)" */
	@NonNull
	public static String currentVersion() {
		return BuildConfig.VERSION_NAME
				+ (BuildConfig.TTV_BUILD > 0 ? " (build " + BuildConfig.TTV_BUILD + ")"
						: "");
	}

	/** When the app opens: quiet, unless a new version is there */
	public static void checkAuto(@NonNull Activity activity) {
		if (BuildConfig.TTV_BUILD <= 0) {
			// Local build: nothing to compare with
			return;
		}
		SharedPreferences p = prefs(activity);
		long now = System.currentTimeMillis();
		if (now - p.getLong(KEY_LAST_CHECK, 0) < AUTO_EVERY_MS) {
			return;
		}
		p.edit().putLong(KEY_LAST_CHECK, now).apply();
		check(activity, false);
	}

	/** From Settings: says so when the app is up to date */
	public static void checkNow(@NonNull Activity activity) {
		Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
		check(activity, true);
	}

	private static void check(@NonNull Activity activity, boolean manual) {
		if (busy) {
			return;
		}
		busy = true;
		new Thread(() -> {
			Release r = null;
			try {
				r = fetchLatest();
			} catch (Throwable t) {
				Log.w(TAG, "check", t);
			}
			final Release release = r;
			main.post(() -> {
				busy = false;
				if (activity.isFinishing()) {
					return;
				}
				if (release == null) {
					if (manual) {
						Toast.makeText(activity, R.string.update_failed,
								Toast.LENGTH_LONG).show();
					}
					return;
				}
				boolean newer = release.build > BuildConfig.TTV_BUILD;
				if (!newer) {
					if (manual) {
						Toast.makeText(activity, R.string.update_none,
								Toast.LENGTH_LONG).show();
					}
					return;
				}
				if (!manual && prefs(activity).getInt(KEY_SKIPPED, 0) == release.build) {
					return;
				}
				offer(activity, release);
			});
		}, "AppUpdater").start();
	}

	private static void offer(@NonNull Activity activity, @NonNull Release r) {
		new MaterialAlertDialogBuilder(activity).setTitle(
				R.string.update_title).setMessage(
						activity.getString(R.string.update_message, r.version)).setPositiveButton(
								R.string.update_install,
								(d, w) -> install(activity, r)).setNegativeButton(
										R.string.update_later,
										(d, w) -> prefs(activity).edit().putInt(KEY_SKIPPED,
												r.build).apply()).show();
	}

	private static void install(@NonNull Activity activity, @NonNull Release r) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				&& !activity.getPackageManager().canRequestPackageInstalls()) {
			// Once: allow Torrent TV to install its own updates
			new MaterialAlertDialogBuilder(activity).setTitle(
					R.string.update_title).setMessage(
							R.string.update_permission).setPositiveButton(
									R.string.all_files_access_open, (d, w) -> {
										// Asked again when back in the app
										prefs(activity).edit().remove(KEY_LAST_CHECK).remove(
												KEY_SKIPPED).apply();
										try {
											activity.startActivity(new Intent(
													Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
													Uri.parse("package:" + activity.getPackageName())));
										} catch (Throwable t) {
											Log.w(TAG, "unknown sources", t);
										}
									}).setNegativeButton(R.string.update_later, null).show();
			return;
		}
		download(activity, r);
	}

	private static void download(@NonNull Activity activity, @NonNull Release r) {
		final LinearProgressIndicator bar = new LinearProgressIndicator(activity);
		bar.setMax(100);
		bar.setIndeterminate(true);
		final TextView text = new TextView(activity);
		text.setText(R.string.update_downloading);
		LinearLayout box = new LinearLayout(activity);
		box.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		box.addView(text);
		box.addView(bar);
		final AlertDialog dlg = new MaterialAlertDialogBuilder(activity).setTitle(
				R.string.update_title).setView(box).setCancelable(false).show();

		final File dir = new File(activity.getFilesDir(), "updates");
		final File apk = new File(dir, APK_NAME);
		final Context app = activity.getApplicationContext();
		new Thread(() -> {
			boolean ok = false;
			try {
				//noinspection ResultOfMethodCallIgnored
				dir.mkdirs();
				okhttp3.Response resp = open(r.apkUrl);
				okhttp3.ResponseBody body = resp.body();
				long total = body == null ? -1 : body.contentLength();
				try (InputStream in = body.byteStream();
						OutputStream out = new FileOutputStream(apk)) {
					byte[] buf = new byte[64 * 1024];
					long done = 0;
					int lastPct = -1, n;
					while ((n = in.read(buf)) > 0) {
						out.write(buf, 0, n);
						done += n;
						if (total > 0) {
							final int pct = (int) (done * 100 / total);
							if (pct != lastPct) {
								lastPct = pct;
								main.post(() -> {
									bar.setIndeterminate(false);
									bar.setProgressCompat(pct, true);
								});
							}
						}
					}
				}
				ok = total <= 0 || apk.length() == total;
			} catch (Throwable t) {
				Log.w(TAG, "download", t);
			}
			final boolean success = ok;
			main.post(() -> {
				try {
					dlg.dismiss();
				} catch (Throwable ignore) {
				}
				if (!success) {
					Toast.makeText(app, R.string.update_failed, Toast.LENGTH_LONG).show();
					return;
				}
				try {
					Uri uri = FileProvider.getUriForFile(app,
							app.getPackageName() + ".files", apk);
					Intent i = new Intent(Intent.ACTION_VIEW);
					i.setDataAndType(uri, "application/vnd.android.package-archive");
					i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
							| Intent.FLAG_ACTIVITY_NEW_TASK);
					activity.startActivity(i);
				} catch (Throwable t) {
					Log.w(TAG, "install", t);
					Toast.makeText(app, R.string.update_failed, Toast.LENGTH_LONG).show();
				}
			});
		}, "AppUpdater.download").start();
	}

	@Nullable
	private static Release fetchLatest() throws Exception {
		String json;
		try (okhttp3.Response resp = open(API)) {
			okhttp3.ResponseBody body = resp.body();
			json = body == null ? "" : body.string();
		}
		JSONObject o = new JSONObject(json);
		Matcher m = Pattern.compile("(\\d+)").matcher(o.optString("tag_name"));
		if (!m.find()) {
			return null;
		}
		Release r = new Release();
		r.build = Integer.parseInt(m.group(1));
		// "Torrent TV 1.3.4.4-plus47 (build 64)" → "1.3.4.4-plus47"
		String name = o.optString("name");
		Matcher v = Pattern.compile("Torrent TV (\\S+)").matcher(name);
		r.version = v.find() ? v.group(1) : o.optString("tag_name");
		JSONArray assets = o.optJSONArray("assets");
		for (int i = 0; assets != null && i < assets.length(); i++) {
			JSONObject a = assets.getJSONObject(i);
			if (APK_NAME.equals(a.optString("name"))) {
				r.apkUrl = a.optString("browser_download_url");
			}
		}
		return r.apkUrl == null || r.apkUrl.isEmpty() ? null : r;
	}

	private static final okhttp3.OkHttpClient http = new okhttp3.OkHttpClient.Builder().connectTimeout(
			15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60,
					java.util.concurrent.TimeUnit.SECONDS).followRedirects(
							true).followSslRedirects(true).build();

	/** GET, following GitHub's redirects to its download servers */
	@NonNull
	private static okhttp3.Response open(@NonNull String url) throws Exception {
		okhttp3.Request req = new okhttp3.Request.Builder().url(url).header(
				"User-Agent", "TorrentTV-Updater").header("Accept",
						url.startsWith(API) ? "application/vnd.github+json"
								: "application/octet-stream").build();
		okhttp3.Response resp = http.newCall(req).execute();
		if (!resp.isSuccessful()) {
			int code = resp.code();
			resp.close();
			throw new java.io.IOException("HTTP " + code + " for " + url);
		}
		return resp;
	}

	private static SharedPreferences prefs(@NonNull Context c) {
		return c.getApplicationContext().getSharedPreferences(PREFS,
				Context.MODE_PRIVATE);
	}
}
