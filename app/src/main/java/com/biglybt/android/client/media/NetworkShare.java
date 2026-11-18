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
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Torrent TV: a shared folder of a computer (Windows "Partager", or Samba),
 * read over the network. Its videos join the media library; the player
 * streams them (smb://) without copying them to the TV.
 */
public final class NetworkShare
{
	private static final String PREFS = "network_share";

	private static final long MIN_VIDEO_BYTES = 40L * 1024 * 1024;

	private static final int MAX_DEPTH = 6;

	/** Where the share is, and how to sign in */
	public static final class Config
	{
		@NonNull
		public final String host;

		@NonNull
		public final String share;

		/** Folder inside the share, "" for its top, with "/" separators */
		@NonNull
		public final String dir;

		@NonNull
		public final String user;

		@NonNull
		public final String password;

		Config(@NonNull String host, @NonNull String share, @NonNull String dir,
				@NonNull String user, @NonNull String password) {
			this.host = host;
			this.share = share;
			this.dir = dir;
			this.user = user;
			this.password = password;
		}

		/** "192.168.1.20/Films/Nouveaux" */
		@NonNull
		public String address() {
			return host + "/" + share + (dir.isEmpty() ? "" : "/" + dir);
		}
	}

	/** One video found on the share */
	public static final class Video
	{
		/** "smb://host/share/dir/file.mkv" (no password in it) */
		public String path;

		public long size;

		public long modified;
	}

	private NetworkShare() {
	}

	public static boolean isRemote(@Nullable String path) {
		return path != null && path.startsWith("smb://");
	}

	/**
	 * Accepts "\\PC\Films", "//192.168.1.20/Films/Séries", "smb://…" or
	 * "192.168.1.20/Films".
	 */
	@Nullable
	public static Config parse(@Nullable String address, @Nullable String user,
			@Nullable String password) {
		if (address == null) {
			return null;
		}
		String a = address.trim().replace('\\', '/');
		if (a.toLowerCase(Locale.ROOT).startsWith("smb:")) {
			a = a.substring(4);
		}
		while (a.startsWith("/")) {
			a = a.substring(1);
		}
		while (a.endsWith("/")) {
			a = a.substring(0, a.length() - 1);
		}
		String[] parts = a.split("/", 3);
		if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
			return null;
		}
		return new Config(parts[0], parts[1], parts.length > 2 ? parts[2] : "",
				user == null ? "" : user.trim(), password == null ? "" : password);
	}

	@Nullable
	public static Config get(@NonNull Context context) {
		SharedPreferences p = prefs(context);
		return parse(p.getString("address", null), p.getString("user", ""),
				p.getString("password", ""));
	}

	public static void save(@NonNull Context context, @Nullable Config c) {
		SharedPreferences.Editor e = prefs(context).edit();
		if (c == null) {
			e.clear();
		} else {
			e.putString("address", c.address()).putString("user", c.user).putString(
					"password", c.password);
		}
		e.apply();
	}

	private static SharedPreferences prefs(@NonNull Context c) {
		return c.getApplicationContext().getSharedPreferences(PREFS,
				Context.MODE_PRIVATE);
	}

	/* ---------- Network ---------- */

	private static SMBClient client() {
		return new SMBClient(SmbConfig.builder().withTimeout(15,
				TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build());
	}

	private static AuthenticationContext auth(@NonNull Config c) {
		if (c.user.isEmpty()) {
			return AuthenticationContext.guest();
		}
		String user = c.user, domain = "";
		int slash = user.indexOf('\\');
		if (slash > 0) {
			domain = user.substring(0, slash);
			user = user.substring(slash + 1);
		}
		return new AuthenticationContext(user, c.password.toCharArray(), domain);
	}

	private interface ShareWork<T>
	{
		T run(DiskShare share) throws IOException;
	}

	private static <T> T withShare(@NonNull Config c, @NonNull ShareWork<T> work)
			throws IOException {
		try (SMBClient client = client();
				Connection connection = client.connect(c.host)) {
			Session session = connection.authenticate(auth(c));
			try (DiskShare share = (DiskShare) session.connectShare(c.share)) {
				return work.run(share);
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/** Checks the address and the account: throws with the reason */
	@WorkerThread
	public static void test(@NonNull Config c) throws IOException {
		withShare(c, share -> {
			share.list(c.dir.replace('/', '\\'));
			return null;
		});
	}

	/** Every video of the share (subfolders included) */
	@WorkerThread
	@NonNull
	public static List<Video> listVideos(@NonNull Config c) throws IOException {
		return withShare(c, share -> {
			List<Video> out = new ArrayList<>();
			walk(c, share, c.dir, 0, out);
			return out;
		});
	}

	private static void walk(Config c, DiskShare share, String dir, int depth,
			List<Video> out) {
		if (depth > MAX_DEPTH) {
			return;
		}
		List<FileIdBothDirectoryInformation> list;
		try {
			list = share.list(dir.replace('/', '\\'));
		} catch (Exception e) {
			return;
		}
		for (FileIdBothDirectoryInformation f : list) {
			String name = f.getFileName();
			if (name == null || name.equals(".") || name.equals("..")
					|| name.startsWith(".") || name.startsWith("$")) {
				continue;
			}
			String rel = dir.isEmpty() ? name : dir + "/" + name;
			boolean folder = (f.getFileAttributes()
					& FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
			if (folder) {
				walk(c, share, rel, depth + 1, out);
				continue;
			}
			String ext = name.contains(".")
					? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
					: "";
			if (!MediaLibrary.VIDEO_EXT.contains(ext)
					|| name.toLowerCase(Locale.ROOT).contains("sample")
					|| f.getEndOfFile() < MIN_VIDEO_BYTES) {
				continue;
			}
			Video v = new Video();
			v.path = "smb://" + c.host + "/" + c.share + "/" + rel;
			v.size = f.getEndOfFile();
			v.modified = f.getLastWriteTime().toEpochMillis();
			out.add(v);
		}
	}

	/** Deletes a video of the share */
	@WorkerThread
	public static boolean delete(@NonNull Context context, @NonNull String path) {
		Config c = get(context);
		String prefix = c == null ? null : "smb://" + c.host + "/" + c.share + "/";
		if (prefix == null || !path.startsWith(prefix)) {
			return false;
		}
		final String rel = path.substring(prefix.length()).replace('/', '\\');
		try {
			return withShare(c, share -> {
				share.rm(rel);
				return true;
			});
		} catch (IOException e) {
			return false;
		}
	}

	/** Sign-in options for the player (libVLC smb module) */
	@NonNull
	public static List<String> playerOptions(@NonNull Context context) {
		List<String> o = new ArrayList<>();
		Config c = get(context);
		if (c == null || c.user.isEmpty()) {
			return o;
		}
		String user = c.user, domain = "";
		int slash = user.indexOf('\\');
		if (slash > 0) {
			domain = user.substring(0, slash);
			user = user.substring(slash + 1);
		}
		o.add(":smb-user=" + user);
		o.add(":smb-pwd=" + c.password);
		if (!TextUtils.isEmpty(domain)) {
			o.add(":smb-domain=" + domain);
		}
		return o;
	}

	/** The player's address for a video path */
	@NonNull
	public static Uri uri(@NonNull String path) {
		return Uri.parse(Uri.encode(path, ":/"));
	}
}
