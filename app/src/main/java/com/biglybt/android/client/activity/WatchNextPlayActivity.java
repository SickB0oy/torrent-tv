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

package com.biglybt.android.client.activity;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;

/**
 * Torrent TV: opened from the "Continue watching" row of the Google TV home
 * screen. Only plays a video file that exists on this device, then gets out
 * of the way.
 */
public class WatchNextPlayActivity
	extends Activity
{
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Uri uri = getIntent().getData();
		if (uri != null && "file".equals(uri.getScheme()) && uri.getPath() != null
				&& new File(uri.getPath()).isFile()) {
			PlayerActivity.play(this, uri, getIntent().getStringExtra("title"));
		}
		finish();
	}
}
