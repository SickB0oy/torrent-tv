/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package com.biglybt.android.client.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Torrent TV media library: finds downloaded videos, recognises them from
 * their file names and fetches posters and synopses from TMDB.
 * <p>
 * Metadata is kept in a small JSON cache, refreshed after 6 months at most
 * (TMDB terms of use).
 */
public class MediaLibrary
{
	private static final String TAG = "MediaLibrary";

	public static final String PREFS = "media_library";

	private static final String KEY_TOKEN = "tmdb_token";

	private static final String CACHE_FILE = "media_library.json";

	private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(180);

	private static final long NOT_FOUND_RETRY_MS = TimeUnit.DAYS.toMillis(7);

	private static final long MIN_VIDEO_BYTES = 40L * 1024 * 1024;

	private static final String IMG = "https://image.tmdb.org/t/p/";

	static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList("mkv",
			"mp4", "m4v", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "mts",
			"mpg", "mpeg", "vob", "ogv", "divx", "rmvb"));

	/** One video in the library */
	public static class Item
	{
		public String path;

		public long size;

		public long modified;

		/** "movie", "tv" or "" when not found */
		public String type = "";

		public int tmdbId;

		public String title;

		public String year = "";

		public String overview = "";

		public double rating;

		public String posterPath;

		public String backdropPath;

		public int season = -1;

		public int episode = -1;

		public String episodeTitle;

		/** The title read from the file name, used for the search */
		public String guessedTitle;

		public long fetchedAt;

		/** TMDB language used for title and synopsis */
		public String lang;

		public boolean hasMetadata() {
			return tmdbId > 0;
		}

		@Nullable
		public String posterUrl() {
			return TextUtils.isEmpty(posterPath) ? null : IMG + "w342" + posterPath;
		}

		@Nullable
		public String backdropUrl() {
			return TextUtils.isEmpty(backdropPath) ? null : IMG + "w780" + backdropPath;
		}

		public Uri uri() {
			return Uri.fromFile(new File(path));
		}

		public boolean isEpisode() {
			return season >= 0 && episode >= 0;
		}

		public String episodeLabel() {
			return String.format(Locale.US, "S%02dE%02d", season, episode);
		}

		JSONObject toJson() throws org.json.JSONException {
			JSONObject o = new JSONObject();
			o.put("path", path).put("size", size).put("modified", modified);
			o.put("type", type).put("tmdbId", tmdbId).put("title", title);
			o.put("year", year).put("overview", overview).put("rating", rating);
			o.put("poster", posterPath == null ? "" : posterPath);
			o.put("backdrop", backdropPath == null ? "" : backdropPath);
			o.put("season", season).put("episode", episode);
			o.put("episodeTitle", episodeTitle == null ? "" : episodeTitle);
			o.put("guessed", guessedTitle == null ? "" : guessedTitle);
			o.put("fetchedAt", fetchedAt);
			o.put("lang", lang == null ? "" : lang);
			return o;
		}

		static Item fromJson(JSONObject o) {
			Item i = new Item();
			i.path = o.optString("path");
			i.size = o.optLong("size");
			i.modified = o.optLong("modified");
			i.type = o.optString("type");
			i.tmdbId = o.optInt("tmdbId");
			i.title = o.optString("title");
			i.year = o.optString("year");
			i.overview = o.optString("overview");
			i.rating = o.optDouble("rating", 0);
			i.posterPath = emptyToNull(o.optString("poster"));
			i.backdropPath = emptyToNull(o.optString("backdrop"));
			i.season = o.optInt("season", -1);
			i.episode = o.optInt("episode", -1);
			i.episodeTitle = emptyToNull(o.optString("episodeTitle"));
			i.guessedTitle = o.optString("guessed");
			i.fetchedAt = o.optLong("fetchedAt");
			i.lang = o.optString("lang");
			return i;
		}
	}

	private final Context context;

	private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(
			15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

	public MediaLibrary(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	/* ---------- TMDB key ---------- */

	private SharedPreferences prefs() {
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	@Nullable
	public String getToken() {
		String t = prefs().getString(KEY_TOKEN, null);
		return TextUtils.isEmpty(t) ? null : t;
	}

	public void setToken(@Nullable String token) {
		prefs().edit().putString(KEY_TOKEN, token == null ? null : token.trim()).apply();
	}

	/** @return null if the key works, otherwise a short reason */
	@WorkerThread
	@Nullable
	public String checkToken(@NonNull String token) {
		try {
			JSONObject o = get(token.trim(), "configuration", null);
			return o != null ? null : "?";
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	/* ---------- Cache ---------- */

	@NonNull
	public synchronized List<Item> loadCache() {
		ArrayList<Item> list = new ArrayList<>();
		File f = new File(context.getFilesDir(), CACHE_FILE);
		if (!f.exists()) {
			return list;
		}
		try (FileInputStream in = new FileInputStream(f)) {
			byte[] data = new byte[(int) f.length()];
			int read = 0;
			while (read < data.length) {
				int n = in.read(data, read, data.length - read);
				if (n < 0) {
					break;
				}
				read += n;
			}
			JSONArray arr = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
			for (int i = 0; i < arr.length(); i++) {
				list.add(Item.fromJson(arr.getJSONObject(i)));
			}
		} catch (Throwable t) {
			Log.w(TAG, "Can't read cache", t);
		}
		return list;
	}

	private synchronized void saveCache(List<Item> items) {
		try {
			JSONArray arr = new JSONArray();
			for (Item i : items) {
				arr.put(i.toJson());
			}
			File tmp = new File(context.getFilesDir(), CACHE_FILE + ".tmp");
			try (FileOutputStream out = new FileOutputStream(tmp)) {
				out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
			}
			//noinspection ResultOfMethodCallIgnored
			tmp.renameTo(new File(context.getFilesDir(), CACHE_FILE));
		} catch (Throwable t) {
			Log.w(TAG, "Can't save cache", t);
		}
	}

	/* ---------- Scan ---------- */

	public interface Progress
	{
		/** Called on the worker thread each time the list changes */
		void onUpdate(List<Item> items);
	}

	/**
	 * Finds videos in the given folders (plus the usual download folders and
	 * removable drives), then fetches missing metadata.
	 */
	@WorkerThread
	public List<Item> refresh(@Nullable List<String> extraDirs,
			@Nullable Progress progress) {
		List<Item> cached = loadCache();
		java.util.HashMap<String, Item> byPath = new java.util.HashMap<>();
		for (Item i : cached) {
			byPath.put(i.path, i);
		}

		ArrayList<Item> items = new ArrayList<>();
		for (File f : findVideos(extraDirs)) {
			Item it = byPath.get(f.getAbsolutePath());
			if (it == null || it.size != f.length()) {
				it = new Item();
				it.path = f.getAbsolutePath();
				it.size = f.length();
				parseName(f, it);
			}
			it.modified = f.lastModified();
			items.add(it);
		}
		sort(items);
		if (progress != null) {
			progress.onUpdate(new ArrayList<>(items));
		}

		String token = getToken();
		if (token == null) {
			saveCache(items);
			return items;
		}

		long now = System.currentTimeMillis();
		boolean changed = false;
		for (Item it : items) {
			long age = now - it.fetchedAt;
			boolean stale = it.hasMetadata() ? age > MAX_AGE_MS
					: age > NOT_FOUND_RETRY_MS;
			boolean otherLang = it.hasMetadata()
					&& !tmdbLanguage().equals(it.lang);
			if (it.fetchedAt > 0 && !stale && !otherLang) {
				continue;
			}
			try {
				if (otherLang && !stale) {
					// Language changed: reload the same title, keep the match
					relocalize(token, it);
				} else {
					lookup(token, it, it.guessedTitle, it.year);
				}
				changed = true;
			} catch (IOException e) {
				Log.w(TAG, "TMDB lookup failed for " + it.guessedTitle, e);
				break; // offline or bad key: stop, keep what we have
			}
			if (progress != null) {
				progress.onUpdate(new ArrayList<>(items));
			}
		}
		saveCache(items);
		if (changed && progress != null) {
			progress.onUpdate(new ArrayList<>(items));
		}
		return items;
	}

	/** Search again with a title typed by the user */
	@WorkerThread
	public boolean research(@NonNull Item item, @NonNull String title,
			@Nullable String year) throws IOException {
		String token = getToken();
		if (token == null) {
			return false;
		}
		item.guessedTitle = title.trim();
		item.tmdbId = 0;
		lookup(token, item, item.guessedTitle, year == null ? "" : year.trim());
		List<Item> all = loadCache();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).path.equals(item.path)) {
				all.set(i, item);
			}
		}
		saveCache(all);
		return item.hasMetadata();
	}

	/**
	 * Torrent TV: poster of a torrent, for the download list. Uses what the
	 * library already matched (or the user corrected), else asks TMDB.
	 *
	 * @return TMDB poster path ("/abc.jpg"), or null when not found
	 */
	@WorkerThread
	@Nullable
	public String findPosterPath(@NonNull String torrentName) throws IOException {
		for (Item it : loadCache()) {
			if (it.posterPath != null && it.path != null
					&& it.path.contains("/" + torrentName)) {
				return it.posterPath;
			}
		}
		String token = getToken();
		if (token == null) {
			return null;
		}
		Item it = new Item();
		it.path = torrentName;
		File f = new File(torrentName);
		parseName(isVideo(f) ? f : new File(torrentName + ".mkv"), it);
		lookup(token, it, it.guessedTitle, it.year);
		return it.posterPath;
	}

	private static void sort(List<Item> items) {
		Collections.sort(items, (a, b) -> Long.compare(b.modified, a.modified));
	}

	@NonNull
	private List<File> findVideos(@Nullable List<String> extraDirs) {
		LinkedHashSet<File> roots = new LinkedHashSet<>();
		if (extraDirs != null) {
			for (String d : extraDirs) {
				if (!TextUtils.isEmpty(d) && !d.startsWith("content:")) {
					roots.add(new File(d));
				}
			}
		}
		roots.add(Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_DOWNLOADS));
		roots.add(Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_MOVIES));
		// USB drives and SD cards
		if (Build.VERSION.SDK_INT >= 30) {
			try {
				StorageManager sm = (StorageManager) context.getSystemService(
						Context.STORAGE_SERVICE);
				if (sm != null) {
					for (StorageVolume v : sm.getStorageVolumes()) {
						File dir = v.getDirectory();
						if (dir != null && v.isRemovable()) {
							roots.add(dir);
						}
					}
				}
			} catch (Throwable t) {
				Log.w(TAG, "Can't list volumes", t);
			}
		}

		LinkedHashSet<File> found = new LinkedHashSet<>();
		HashSet<String> visited = new HashSet<>();
		for (File root : roots) {
			walk(root, 0, found, visited);
		}
		return new ArrayList<>(found);
	}

	private static void walk(File dir, int depth, Set<File> out,
			Set<String> visited) {
		if (dir == null || depth > 5 || !dir.isDirectory()) {
			return;
		}
		String canon;
		try {
			canon = dir.getCanonicalPath();
		} catch (IOException e) {
			canon = dir.getAbsolutePath();
		}
		if (!visited.add(canon)) {
			return;
		}
		String n = dir.getName().toLowerCase(Locale.US);
		if (n.startsWith(".") || n.equals("android") || n.equals("sample")
				|| n.equals("samples") || n.equals("extras")) {
			return;
		}
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				walk(f, depth + 1, out, visited);
			} else if (isVideo(f) && f.length() >= MIN_VIDEO_BYTES) {
				out.add(f);
			}
		}
	}

	static boolean isVideo(File f) {
		String name = f.getName().toLowerCase(Locale.US);
		int dot = name.lastIndexOf('.');
		return dot > 0 && VIDEO_EXT.contains(name.substring(dot + 1))
				&& !name.contains("sample");
	}

	/* ---------- File name parsing ---------- */

	private static final Pattern EPISODE = Pattern.compile(
			"(?i)(?:^|[ ._\\-\\[(])s(\\d{1,2})[ ._\\-]?e(\\d{1,3})|(?:^|[ ._\\-])(\\d{1,2})x(\\d{2})(?=[ ._\\-]|$)");

	private static final Pattern YEAR = Pattern.compile(
			"(?:^|[ ._\\-\\[(])((?:19|20)\\d{2})(?=[ ._\\-\\])]|$)");

	private static final Pattern JUNK = Pattern.compile("(?i)[ ._\\-\\[(]("
			+ "2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr|hdr10|dv|dolby|multi|truefrench|"
			+ "french|vff|vfi|vf2|vfq|vostfr|subfrench|vo|en|eng|bluray|blu-ray|bdrip|brrip|"
			+ "webrip|web-dl|webdl|web|hdtv|dvdrip|remux|x264|x265|h264|h265|hevc|av1|"
			+ "aac|ac3|eac3|dts|ddp5|ddp|atmos|10bit|proper|repack|extended|unrated|"
			+ "remastered|imax|internal|limited|complete|integrale|saison|season)"
			+ "(?=[ ._\\-\\])]|$)");

	static void parseName(File f, Item item) {
		String base = stripExt(f.getName());
		// Generic file names: use the folder name instead
		if (base.length() < 4 || base.matches("(?i)(movie|film|video|vid|\\d+)")) {
			File parent = f.getParentFile();
			if (parent != null) {
				base = parent.getName();
			}
		}

		Matcher ep = EPISODE.matcher(base);
		int cut = base.length();
		if (ep.find()) {
			String s = ep.group(1) != null ? ep.group(1) : ep.group(3);
			String e = ep.group(2) != null ? ep.group(2) : ep.group(4);
			item.season = Integer.parseInt(s);
			item.episode = Integer.parseInt(e);
			cut = Math.min(cut, ep.start());
		}

		Matcher j = JUNK.matcher(base);
		int junkStart = base.length();
		if (j.find() && j.start() > 0) {
			junkStart = j.start();
			cut = Math.min(cut, junkStart);
		}

		// The release year is the last year before the quality tags: titles can
		// contain years too ("Blade Runner 2049 2017", "2012 2009")
		Matcher y = YEAR.matcher(base);
		String year = "";
		int yearStart = -1;
		while (y.find()) {
			if (y.start() > 0 && y.start() < junkStart) {
				year = y.group(1);
				yearStart = y.start();
			}
		}
		if (yearStart > 0 && item.season < 0) {
			cut = Math.min(cut, yearStart);
		}

		String title = base.substring(0, cut);
		title = title.replaceAll("\\[[^\\]]*\\]", " ") // [group]
				.replaceAll("[._]+", " ")
				.replaceAll("\\s*-\\s*$", "")
				.replaceAll("\\s+", " ").trim();
		if (title.isEmpty()) {
			title = base.replaceAll("[._]+", " ").trim();
		}
		item.guessedTitle = title;
		item.title = title;
		item.year = year;
	}

	private static String stripExt(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/* ---------- TMDB ---------- */

	@WorkerThread
	private void lookup(String token, Item item, String title, String year)
			throws IOException {
		item.fetchedAt = System.currentTimeMillis();
		item.lang = tmdbLanguage();
		if (TextUtils.isEmpty(title)) {
			return;
		}
		boolean tv = item.isEpisode();
		JSONObject best = search(token, tv ? "tv" : "movie", title, year);
		if (best == null && !TextUtils.isEmpty(year)) {
			best = search(token, tv ? "tv" : "movie", title, null);
		}
		if (best == null && !tv) {
			// Some series packs have no SxxEyy: try TV as a fallback
			best = search(token, "tv", title, null);
			if (best != null) {
				tv = true;
			}
		}
		if (best == null) {
			item.tmdbId = 0;
			item.type = "";
			return;
		}
		item.tmdbId = best.optInt("id");
		item.type = tv ? "tv" : "movie";
		item.title = best.optString(tv ? "name" : "title", title);
		String date = best.optString(tv ? "first_air_date" : "release_date", "");
		item.year = date.length() >= 4 ? date.substring(0, 4) : item.year;
		item.overview = best.optString("overview", "");
		item.rating = best.optDouble("vote_average", 0);
		item.posterPath = emptyToNull(best.optString("poster_path", ""));
		item.backdropPath = emptyToNull(best.optString("backdrop_path", ""));
		loadEpisode(token, item);
	}

	/** Reload title and synopsis of an already matched item in the current language */
	@WorkerThread
	private void relocalize(String token, Item item) throws IOException {
		boolean tv = "tv".equals(item.type);
		JSONObject o = get(token, (tv ? "tv/" : "movie/") + item.tmdbId, null);
		item.fetchedAt = System.currentTimeMillis();
		item.lang = tmdbLanguage();
		if (o == null) {
			return;
		}
		item.title = o.optString(tv ? "name" : "title", item.title);
		item.overview = o.optString("overview", "");
		String poster = emptyToNull(o.optString("poster_path", ""));
		if (poster != null) {
			item.posterPath = poster;
		}
		item.episodeTitle = null;
		loadEpisode(token, item);
	}

	@WorkerThread
	private void loadEpisode(String token, Item item) {
		if ("tv".equals(item.type) && item.isEpisode()) {
			try {
				JSONObject e = get(token, "tv/" + item.tmdbId + "/season/" + item.season
						+ "/episode/" + item.episode, null);
				if (e != null) {
					item.episodeTitle = emptyToNull(e.optString("name", ""));
					String eo = e.optString("overview", "");
					if (!eo.isEmpty()) {
						item.overview = eo;
					}
				}
			} catch (IOException ignore) {
				// Keep the series info
			}
		}
	}

	/** TMDB language matching the app language: fr-FR, de-DE or en-US */
	@NonNull
	public static String tmdbLanguage() {
		String l = com.biglybt.android.client.AppLanguage.current();
		switch (l) {
			case "fr":
				return "fr-FR";
			case "de":
				return "de-DE";
			default:
				return "en-US";
		}
	}

	@Nullable
	private JSONObject search(String token, String kind, String query,
			@Nullable String year) throws IOException {
		java.util.HashMap<String, String> q = new java.util.HashMap<>();
		q.put("query", query);
		q.put("include_adult", "false");
		if (!TextUtils.isEmpty(year)) {
			q.put(kind.equals("tv") ? "first_air_date_year" : "year", year);
		}
		JSONObject o = get(token, "search/" + kind, q);
		if (o == null) {
			return null;
		}
		JSONArray results = o.optJSONArray("results");
		return results == null || results.length() == 0 ? null
				: results.optJSONObject(0);
	}

	@Nullable
	private JSONObject get(String token, String path,
			@Nullable java.util.Map<String, String> query) throws IOException {
		HttpUrl.Builder url = HttpUrl.parse("https://api.themoviedb.org/3/" + path).newBuilder();
		url.addQueryParameter("language", tmdbLanguage());
		if (query != null) {
			for (java.util.Map.Entry<String, String> e : query.entrySet()) {
				url.addQueryParameter(e.getKey(), e.getValue());
			}
		}
		Request.Builder req = new Request.Builder().header("Accept", "application/json");
		// v3 API key (32 hex chars) or v4 read access token
		if (token.matches("[0-9a-fA-F]{32}")) {
			url.addQueryParameter("api_key", token);
		} else {
			req.header("Authorization", "Bearer " + token);
		}
		try (Response r = http.newCall(req.url(url.build()).build()).execute()) {
			if (r.code() == 401) {
				throw new IOException("401");
			}
			if (r.code() == 404) {
				return null;
			}
			if (!r.isSuccessful() || r.body() == null) {
				throw new IOException("HTTP " + r.code());
			}
			return new JSONObject(r.body().string());
		} catch (org.json.JSONException e) {
			throw new IOException(e);
		}
	}

	@Nullable
	static String emptyToNull(@Nullable String s) {
		return TextUtils.isEmpty(s) ? null : s;
	}
}
