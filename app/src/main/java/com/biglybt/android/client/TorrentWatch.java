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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.biglybt.android.client.activity.PlayerActivity;
import com.biglybt.android.client.media.ReleaseName;
import com.biglybt.android.client.session.Session;
import com.biglybt.android.util.MapUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Torrent TV: "Regarder" on a torrent card. Finds the video(s) of the
 * torrent, asks which one when there are several (season pack), then plays
 * it: from the file when it is complete, otherwise streamed while it
 * downloads (the torrent switches to downloading in order).
 */
public final class TorrentWatch
{
	private static final long MIN_VIDEO = 40L * 1024 * 1024;

	private static final String[] VIDEO_EXT = {
		".mkv",
		".mp4",
		".m4v",
		".avi",
		".mov",
		".ts",
		".m2ts",
		".webm",
		".wmv",
		".mpg",
		".mpeg"
	};

	private TorrentWatch() {
	}

	public static boolean isVideoName(@Nullable String name) {
		if (name == null) {
			return false;
		}
		String n = name.toLowerCase(Locale.ROOT);
		for (String e : VIDEO_EXT) {
			if (n.endsWith(e)) {
				return true;
			}
		}
		return false;
	}

	public static void watch(@NonNull Activity activity, @NonNull Session session,
			long torrentID) {
		Handler main = new Handler(Looper.getMainLooper());
		Toast.makeText(activity, R.string.watch_preparing, Toast.LENGTH_SHORT).show();
		session.torrent.getFileInfo("TorrentWatch", torrentID, null,
				(callID, added, fields, fileIndexes, removed) -> main.post(
						() -> filesReceived(activity, session, torrentID)));
	}

	private static void filesReceived(@NonNull Activity activity,
			@NonNull Session session, long torrentID) {
		if (activity.isFinishing()) {
			return;
		}
		Map<String, Object> torrent = session.torrent.getCachedTorrent(torrentID);
		List<?> files = torrent == null ? null
				: MapUtils.getMapList(torrent, TransmissionVars.FIELD_TORRENT_FILES,
						null);
		final List<Map<?, ?>> videos = new ArrayList<>();
		if (files != null) {
			for (Object o : files) {
				if (!(o instanceof Map)) {
					continue;
				}
				Map<?, ?> f = (Map<?, ?>) o;
				String name = MapUtils.getMapString(f, TransmissionVars.FIELD_FILES_NAME,
						null);
				long len = MapUtils.getMapLong(f, TransmissionVars.FIELD_FILES_LENGTH, 0);
				boolean wanted = MapUtils.getMapBoolean(f,
						TransmissionVars.FIELD_FILESTATS_WANTED, true);
				if (wanted && len >= MIN_VIDEO && isVideoName(name)
						&& !name.toLowerCase(Locale.ROOT).contains("sample")) {
					videos.add(f);
				}
			}
		}
		if (videos.isEmpty()) {
			Toast.makeText(activity, R.string.watch_no_video, Toast.LENGTH_LONG).show();
			return;
		}
		Collections.sort(videos,
				(a, b) -> MapUtils.getMapString(a, TransmissionVars.FIELD_FILES_NAME,
						"").compareToIgnoreCase(MapUtils.getMapString(b,
								TransmissionVars.FIELD_FILES_NAME, "")));
		if (videos.size() == 1) {
			play(activity, session, torrent, videos.get(0));
			return;
		}
		String[] names = new String[videos.size()];
		for (int i = 0; i < names.length; i++) {
			String n = MapUtils.getMapString(videos.get(i),
					TransmissionVars.FIELD_FILES_NAME, "");
			names[i] = ReleaseName.parse(new File(n).getName()).display()
					+ (complete(videos.get(i)) ? "" : "  ⏳");
		}
		new MaterialAlertDialogBuilder(activity).setTitle(
				R.string.watch_choose).setItems(names,
						(d, which) -> play(activity, session, torrent,
								videos.get(which))).show();
	}

	private static boolean complete(@NonNull Map<?, ?> f) {
		long len = MapUtils.getMapLong(f, TransmissionVars.FIELD_FILES_LENGTH, 0);
		long done = MapUtils.getMapLong(f,
				TransmissionVars.FIELD_FILESTATS_BYTES_COMPLETED, 0);
		return len > 0 && done >= len;
	}

	private static void play(@NonNull Activity activity, @NonNull Session session,
			@NonNull Map<?, ?> torrent, @NonNull Map<?, ?> file) {
		String name = MapUtils.getMapString(file, TransmissionVars.FIELD_FILES_NAME,
				"");
		String title = ReleaseName.parse(new File(name).getName()).display();
		File local = localFile(torrent, file);
		if (complete(file) && local != null && local.isFile()) {
			PlayerActivity.play(activity, Uri.fromFile(local), title);
			return;
		}
		// Still downloading: in order from the start, and streamed by the
		// torrent engine (it waits for the parts that are not there yet)
		long id = MapUtils.getMapLong(torrent, TransmissionVars.FIELD_TORRENT_ID, -1);
		if (id >= 0) {
			session.torrent.setSequential("TorrentWatch", new long[] {
				id
			}, true);
		}
		String url = streamUrl(session, torrent, file);
		Uri target = url != null ? Uri.parse(url)
				// No streaming address: the file itself, being filled in order from
				// the start (fine as long as playback stays behind the download)
				: local != null && local.isFile() ? Uri.fromFile(local) : null;
		if (target == null) {
			Toast.makeText(activity, R.string.watch_not_ready, Toast.LENGTH_LONG).show();
			return;
		}
		Toast.makeText(activity, R.string.watch_streaming, Toast.LENGTH_LONG).show();
		PlayerActivity.play(activity, target, title);
	}

	@Nullable
	private static File localFile(@NonNull Map<?, ?> torrent,
			@NonNull Map<?, ?> file) {
		String full = MapUtils.getMapString(file,
				TransmissionVars.FIELD_FILES_FULL_PATH, null);
		if (full != null && !full.isEmpty()) {
			return new File(full);
		}
		String dir = MapUtils.getMapString(torrent,
				TransmissionVars.FIELD_TORRENT_DOWNLOAD_DIR, null);
		String name = MapUtils.getMapString(file, TransmissionVars.FIELD_FILES_NAME,
				null);
		return dir == null || name == null ? null : new File(dir, name);
	}

	/** The torrent engine's own address for this file (same device) */
	@Nullable
	private static String streamUrl(@NonNull Session session,
			@NonNull Map<?, ?> torrent, @NonNull Map<?, ?> file) {
		String content = MapUtils.getMapString(file,
				TransmissionVars.FIELD_FILES_CONTENT_URL, null);
		long contentPort = session.getContentPort();
		if (contentPort > 0) {
			String hash = MapUtils.getMapString(torrent,
					TransmissionVars.FIELD_TORRENT_HASH_STRING, null);
			String name = MapUtils.getMapString(file, TransmissionVars.FIELD_FILES_NAME,
					null);
			if (hash == null || name == null) {
				return null;
			}
			int index = MapUtils.getMapInt(file, TransmissionVars.FIELD_FILES_INDEX, 0);
			return "http://127.0.0.1:" + contentPort + "/Content/" + hash + "-"
					+ index + AndroidUtils.getFileExtension(name);
		}
		if (content == null || content.isEmpty()) {
			return null;
		}
		if (content.charAt(0) == ':' || content.charAt(0) == '/') {
			content = "http://127.0.0.1" + content;
		}
		return content.replace("/localhost:", "/127.0.0.1:");
	}
}
