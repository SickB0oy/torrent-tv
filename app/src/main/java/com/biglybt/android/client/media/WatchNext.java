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

package com.biglybt.android.client.media;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.tvprovider.media.tv.PreviewChannelHelper;
import androidx.tvprovider.media.tv.TvContractCompat;
import androidx.tvprovider.media.tv.WatchNextProgram;

import com.biglybt.android.client.AndroidUtils;

import java.io.File;

/**
 * Torrent TV: films and episodes started in the app appear in the
 * "Continue watching" row of the Google TV home screen; the next episode of
 * a series too. Selecting one opens the player right there.
 */
public final class WatchNext
{
	private static final String TAG = "WatchNext";

	private static final String PREFS = "watch_next";

	/** Opened by the home screen, forwards to the player */
	public static final String PLAY_ACTIVITY = "com.biglybt.android.client.activity.WatchNextPlayActivity";

	private WatchNext() {
	}

	private static boolean supported(@NonNull Context c) {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AndroidUtils.isTV(c);
	}

	/** Started, not finished: "Continue watching", at this position */
	public static void continueWatching(@NonNull Context context,
			@NonNull Uri uri, long positionMs, long durationMs) {
		publish(context, uri, positionMs, durationMs, false);
	}

	/** The episode after the one just finished: "Next" */
	public static void nextUp(@NonNull Context context, @NonNull Uri uri) {
		publish(context, uri, 0, 0, true);
	}

	/** Finished or deleted: off the home screen */
	public static void remove(@NonNull Context context, @NonNull Uri uri) {
		if (!supported(context)) {
			return;
		}
		final Context app = context.getApplicationContext();
		new Thread(() -> removeNow(app, uri.toString()), "WatchNext").start();
	}

	private static void publish(@NonNull Context context, @NonNull Uri uri,
			long positionMs, long durationMs, boolean next) {
		if (!supported(context) || !"file".equals(uri.getScheme())
				|| uri.getPath() == null) {
			return;
		}
		final Context app = context.getApplicationContext();
		new Thread(() -> {
			try {
				MediaLibrary.Item it = new MediaLibrary(app).findByPath(uri.getPath());
				WatchNextProgram p = build(app, uri, it, positionMs, durationMs, next);
				SharedPreferences prefs = app.getSharedPreferences(PREFS,
						Context.MODE_PRIVATE);
				String key = uri.toString();
				long id = prefs.getLong(key, -1);
				PreviewChannelHelper helper = new PreviewChannelHelper(app);
				if (id != -1) {
					try {
						helper.updateWatchNextProgram(p, id);
						return;
					} catch (Throwable t) {
						// Removed from the home screen by the user: add it again
						prefs.edit().remove(key).apply();
					}
				}
				long newId = helper.publishWatchNextProgram(p);
				if (newId > 0) {
					prefs.edit().putLong(key, newId).apply();
				}
			} catch (Throwable t) {
				Log.w(TAG, "publish", t);
			}
		}, "WatchNext").start();
	}

	@SuppressLint("RestrictedApi")
	private static WatchNextProgram build(@NonNull Context app, @NonNull Uri uri,
			@Nullable MediaLibrary.Item it, long pos, long dur, boolean next) {
		String title = it != null && it.title != null ? it.title
				: new File(uri.getPath()).getName();
		boolean episode = it != null && it.isEpisode();
		WatchNextProgram.Builder b = new WatchNextProgram.Builder();
		b.setType(episode ? TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
				: TvContractCompat.WatchNextPrograms.TYPE_MOVIE);
		b.setWatchNextType(next ? TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
				: TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
		b.setLastEngagementTimeUtcMillis(System.currentTimeMillis());
		b.setTitle(title);
		b.setInternalProviderId(uri.toString());
		if (!next && dur > 0) {
			b.setLastPlaybackPositionMillis((int) Math.min(Integer.MAX_VALUE, pos));
			b.setDurationMillis((int) Math.min(Integer.MAX_VALUE, dur));
		}
		if (episode) {
			b.setSeasonNumber(it.season);
			b.setEpisodeNumber(it.episode);
			if (it.episodeTitle != null) {
				b.setEpisodeTitle(it.episodeTitle);
			}
		}
		String poster = it == null ? null : it.posterUrl();
		if (poster != null) {
			b.setPosterArtUri(Uri.parse(poster));
			b.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3);
		}
		if (it != null && it.overview != null && !it.overview.isEmpty()) {
			b.setDescription(it.overview);
		}
		Intent open = new Intent(Intent.ACTION_VIEW);
		open.setComponent(new ComponentName(app.getPackageName(), PLAY_ACTIVITY));
		open.setData(uri);
		open.putExtra("title", episode ? title + " — " + it.episodeLabel() : title);
		b.setIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)));
		return b.build();
	}

	private static void removeNow(@NonNull Context app, @NonNull String key) {
		SharedPreferences prefs = app.getSharedPreferences(PREFS,
				Context.MODE_PRIVATE);
		long id = prefs.getLong(key, -1);
		if (id == -1) {
			return;
		}
		prefs.edit().remove(key).apply();
		try {
			app.getContentResolver().delete(
					TvContractCompat.buildWatchNextProgramUri(id), null, null);
		} catch (Throwable t) {
			Log.w(TAG, "remove", t);
		}
	}
}
