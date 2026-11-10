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
import android.util.Log;

import androidx.annotation.NonNull;

import org.videolan.libvlc.LibVLC;

import java.util.ArrayList;

/**
 * One libVLC instance for the whole app. Creating it loads the native
 * libraries and scans the codecs, which takes a noticeable moment: it is done
 * once, in the background, as soon as a screen that can play videos opens,
 * so pressing "Play" starts the video right away.
 */
public final class PlayerEngine
{
	private static final String TAG = "PlayerEngine";

	private static LibVLC libVLC;

	/** Subtitle size changed: next get() builds a new instance */
	private static boolean stale;

	private static final String PREFS = "player_prefs";

	private static final String KEY_SUB_SIZE = "sub_size";

	/** Small, normal, large, very large: libVLC "relative font size" (lower = bigger) */
	private static final int[] SUB_REL_SIZES = {
		20,
		16,
		12,
		9
	};

	/** 0 small, 1 normal, 2 large, 3 very large */
	public static int getSubtitleSize(@NonNull Context context) {
		int v = context.getApplicationContext().getSharedPreferences(PREFS,
				Context.MODE_PRIVATE).getInt(KEY_SUB_SIZE, 1);
		return Math.max(0, Math.min(SUB_REL_SIZES.length - 1, v));
	}

	/** Saved; applies from the next video start (see {@link #get}) */
	public static synchronized void setSubtitleSize(@NonNull Context context,
			int size) {
		context.getApplicationContext().getSharedPreferences(PREFS,
				Context.MODE_PRIVATE).edit().putInt(KEY_SUB_SIZE, size).apply();
		stale = true;
	}

	private PlayerEngine() {
	}

	@NonNull
	public static synchronized LibVLC get(@NonNull Context context) {
		if (stale && libVLC != null) {
			// Options are fixed at creation: rebuild with the new subtitle size
			try {
				libVLC.release();
			} catch (Throwable t) {
				Log.w(TAG, "release", t);
			}
			libVLC = null;
		}
		stale = false;
		if (libVLC == null) {
			ArrayList<String> options = new ArrayList<>();
			// Local files: short read-ahead, the disk is fast
			options.add("--file-caching=300");
			// Streams from this device (partial downloads): still short
			options.add("--network-caching=1000");
			options.add("--audio-time-stretch");
			// Most French subtitle files that aren't UTF-8 are Windows-1252
			options.add("--subsdec-encoding=Windows-1252");
			options.add("--freetype-rel-fontsize="
					+ SUB_REL_SIZES[getSubtitleSize(context)]);
			libVLC = new LibVLC(context.getApplicationContext(), options);
		}
		return libVLC;
	}

	/** Prepares libVLC in the background, ahead of the first "Play" */
	public static void warmUp(@NonNull Context context) {
		final Context app = context.getApplicationContext();
		new Thread(() -> {
			try {
				get(app);
			} catch (Throwable t) {
				Log.w(TAG, "warmUp", t);
			}
		}, "PlayerEngine.warmUp").start();
	}
}
