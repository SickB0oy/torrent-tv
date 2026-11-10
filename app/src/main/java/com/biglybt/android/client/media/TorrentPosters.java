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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Torrent TV: TMDB posters for the download list. Looked up once per torrent
 * name (one at a time, in the background) and remembered.
 */
public final class TorrentPosters
{
	private static final String TAG = "TorrentPosters";

	private static final String PREFS = "torrent_posters";

	private static final String IMG = "https://image.tmdb.org/t/p/w185";

	/** Not found: ask TMDB again after this long */
	private static final long RETRY_MS = 3L * 24 * 60 * 60 * 1000;

	/** name → poster URL, or "" when there is none */
	private static final Map<String, String> known = new ConcurrentHashMap<>();

	/** name → time of the last failed lookup (offline, no key) */
	private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();

	private static final long FAIL_WAIT_MS = 60 * 1000;

	private static final Set<String> pending = Collections.synchronizedSet(
			new HashSet<>());

	private static final ExecutorService worker = Executors.newSingleThreadExecutor();

	private static final Handler main = new Handler(Looper.getMainLooper());

	public interface Callback
	{
		/** Main thread, only when a poster was found */
		void onPoster(@NonNull String url);
	}

	private TorrentPosters() {
	}

	/**
	 * @return the poster URL when already known; otherwise null, and a lookup
	 *         is started that calls back if it finds one
	 */
	@Nullable
	public static String get(@NonNull Context context, @Nullable String name,
			@Nullable Callback callback) {
		if (name == null || name.trim().isEmpty()) {
			return null;
		}
		final String key = MediaLibrary.tmdbLanguage() + "|" + name;
		String url = known.get(key);
		if (url != null) {
			return url.isEmpty() ? null : url;
		}
		final Context app = context.getApplicationContext();
		SharedPreferences prefs = app.getSharedPreferences(PREFS,
				Context.MODE_PRIVATE);
		String saved = prefs.getString(key, null);
		if (saved != null && saved.startsWith("/")) {
			url = IMG + saved;
			known.put(key, url);
			return url;
		}
		if (saved != null && saved.startsWith("-")) {
			long when = 0;
			try {
				when = Long.parseLong(saved.substring(1));
			} catch (NumberFormatException ignore) {
			}
			if (System.currentTimeMillis() - when < RETRY_MS) {
				known.put(key, "");
				return null;
			}
		}
		Long failed = failedAt.get(key);
		if (failed != null
				&& System.currentTimeMillis() - failed < FAIL_WAIT_MS) {
			return null;
		}
		if (!pending.add(key)) {
			return null;
		}
		worker.execute(() -> {
			try {
				MediaLibrary library = new MediaLibrary(app);
				if (library.getToken() == null) {
					// No TMDB key yet: try again once one is entered
					failedAt.put(key, System.currentTimeMillis());
					return;
				}
				String path = library.findPosterPath(name);
				if (path == null) {
					prefs.edit().putString(key,
							"-" + System.currentTimeMillis()).apply();
					known.put(key, "");
					return;
				}
				prefs.edit().putString(key, path).apply();
				String found = IMG + path;
				known.put(key, found);
				if (callback != null) {
					main.post(() -> callback.onPoster(found));
				}
			} catch (Throwable t) {
				// Offline: try again later
				Log.w(TAG, "Poster lookup failed for " + name, t);
				failedAt.put(key, System.currentTimeMillis());
			} finally {
				pending.remove(key);
			}
		});
		return null;
	}
}
