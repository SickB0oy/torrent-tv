/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package com.biglybt.android.client;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

/** Remote-only flavor: there is no local core, so no remote access banner. */
public class PlusRemoteInfo
{
	public final String webUrl = "";

	public final String user = "";

	@Nullable
	public final String password = null;

	@Nullable
	public static PlusRemoteInfo get() {
		return null;
	}

	public static void newPassword() {
	}

	@Nullable
	public static String currentPassword() {
		return null;
	}

	public void showQr(FragmentActivity activity) {
	}
}
