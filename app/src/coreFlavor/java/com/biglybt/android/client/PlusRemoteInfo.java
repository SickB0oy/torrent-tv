/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package com.biglybt.android.client;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.fragment.app.FragmentActivity;

import com.biglybt.android.client.dialog.DialogFragmentRemoteAccessQR;
import com.biglybt.android.client.rpc.RPC;
import com.biglybt.android.util.NetworkState;

/**
 * BiglyBT Plus: what the remote access banner shows (address, user, password)
 * and the QR code it opens. Core flavor only; the remote-only flavor has a stub.
 */
public class PlusRemoteInfo
{
	public final String webUrl;

	public final String user;

	@Nullable
	public final String password;

	private PlusRemoteInfo(String webUrl, String user, @Nullable String password) {
		this.webUrl = webUrl;
		this.user = user;
		this.password = password;
	}

	/** @return null when remote access is off or there's no local network */
	@Nullable
	@WorkerThread
	public static PlusRemoteInfo get() {
		CoreRemoteAccessPreferences ra = CorePrefs.getInstance().getRemoteAccessPreferences();
		if (ra == null || !ra.allowLANAccess) {
			return null;
		}
		String ip = NetworkState.getLocalIpAddress();
		if (ip == null) {
			return null;
		}
		return new PlusRemoteInfo("http://" + ip + ":" + RPC.LOCAL_BIGLYBT_PORT,
				ra.user, ra.reqPW ? ra.pw : null);
	}

	/** A new random password, each time the app is opened */
	@WorkerThread
	public static void newPassword() {
		CorePrefs.getInstance().newRemotePassword();
	}

	public void showQr(FragmentActivity activity) {
		DialogFragmentRemoteAccessQR.openWebOnly(activity.getSupportFragmentManager(),
				webUrl, user, password);
	}
}
