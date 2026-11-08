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

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a release name into something a beginner can read:
 * "Les.Carnets.De.L.Apothicaire.S03E06.VOSTFR.1080p.WEB.x264-Raws"
 * becomes "Les Carnets De L Apothicaire — S03E06" with tags "1080p", "VOSTFR".
 * Same rules as the remote web page.
 */
public final class ReleaseName
{
	private static final Pattern RX_EP = Pattern.compile(
			"(?i)^(S\\d{1,2}(E\\d{1,3})?(-?E\\d{1,3})?|\\d{1,2}x\\d{2})$");

	private static final Pattern RX_YEAR = Pattern.compile(
			"^(19[2-9]\\d|20[0-4]\\d)$");

	private static final Pattern RX_RES = Pattern.compile(
			"(?i)^(480p|540p|576p|720p|1080p|1080i|2160p|4k|uhd)$");

	private static final Pattern RX_JUNK = Pattern.compile(
			"(?i)^(web|web-?dl|webrip|bluray|blu-ray|bdrip|brrip|remux|hdtv|hdrip|dvdrip|dvd|hdlight|4klight|light|mhd|hdr|hdr10|dv|dolby|atmos|x264|x265|h264|h265|hevc|avc|aac|ac3|eac3|dts|ddp|dd|truehd|flac|opus|10bit|8bit|proper|repack|complete|integrale|intégrale|internal|custom|extended|unrated|imax|repost)$");

	private static final Map<String, String> LANGS = new HashMap<>();

	static {
		LANGS.put("MULTI", "MULTi");
		LANGS.put("VOSTFR", "VOSTFR");
		LANGS.put("VOST", "VOST");
		LANGS.put("FRENCH", "VF");
		LANGS.put("TRUEFRENCH", "VFF");
		LANGS.put("VFF", "VFF");
		LANGS.put("VFQ", "VFQ");
		LANGS.put("VF", "VF");
		LANGS.put("VFI", "VF");
		LANGS.put("VOF", "VOF");
		LANGS.put("SUBFRENCH", "VOSTFR");
		LANGS.put("VO", "VO");
	}

	@NonNull
	public final String title;

	@NonNull
	public final String episode;

	@NonNull
	public final String year;

	@NonNull
	public final List<String> tags;

	private ReleaseName(@NonNull String title, @NonNull String episode,
			@NonNull String year, @NonNull List<String> tags) {
		this.title = title;
		this.episode = episode;
		this.year = year;
		this.tags = tags;
	}

	/** "Title — S03E06" or "Title (2017)" */
	@NonNull
	public String display() {
		StringBuilder sb = new StringBuilder(title);
		if (!episode.isEmpty()) {
			sb.append(" — ").append(episode);
		}
		if (!year.isEmpty()) {
			sb.append(" (").append(year).append(')');
		}
		return sb.toString();
	}

	/** "1080p · VOSTFR", or "" */
	@NonNull
	public String tagLine() {
		StringBuilder sb = new StringBuilder();
		for (String t : tags) {
			if (sb.length() > 0) {
				sb.append(" · ");
			}
			sb.append(t);
		}
		return sb.toString();
	}

	@NonNull
	public static ReleaseName parse(String raw) {
		String name = raw == null ? "" : raw.replaceAll("(?i)\\.(mkv|mp4|avi|ts|m4v)$",
				"");
		// Keep "5.1" or "2.0" (audio), split everything else on dots
		name = name.replaceAll("(^|\\D)(\\d)\\.(\\d)(?=\\D|$)", "$1$2\u0001$3").replaceAll(
				"[._]+", " ").replace('\u0001', '.').replaceAll("\\s+", " ").trim();
		String[] words = name.isEmpty() ? new String[0] : name.split(" ");

		List<String> title = new ArrayList<>();
		List<String> tags = new ArrayList<>();
		String ep = "", year = "";
		boolean stop = false;
		for (int i = 0; i < words.length; i++) {
			String w = words[i].replaceAll("^[\\[(]+|[\\])]+$", "");
			if (w.isEmpty()) {
				continue;
			}
			String up = w.toUpperCase(Locale.ROOT).replaceAll("-.*$", "");
			// "Blade Runner 2049 2017": the first of two years belongs to the title
			boolean nextYear = i + 1 < words.length
					&& RX_YEAR.matcher(words[i + 1]).matches();
			boolean isEp = RX_EP.matcher(w).matches();
			boolean isYear = RX_YEAR.matcher(w).matches() && !title.isEmpty()
					&& !(nextYear && !stop);
			boolean isRes = RX_RES.matcher(w).matches();
			String lang = LANGS.get(up);
			boolean junk = RX_JUNK.matcher(w.replaceAll("-.*$", "")).matches();
			if (!stop && (isEp || isYear || isRes || lang != null || junk)) {
				stop = true;
			}
			if (!stop) {
				title.add(w);
				continue;
			}
			if (isEp && ep.isEmpty()) {
				ep = w.toUpperCase(Locale.ROOT);
			} else if (isYear && year.isEmpty()) {
				year = w;
			} else if (isRes) {
				String r = w.matches("(?i)2160p|4k|uhd") ? "4K" : w.toLowerCase(Locale.ROOT);
				if (!tags.contains(r)) {
					tags.add(r);
				}
			} else if (lang != null && !tags.contains(lang)) {
				tags.add(lang);
			} else if (w.matches("(?i)^hdr.*") && !tags.contains("HDR")) {
				tags.add("HDR");
			}
		}
		StringBuilder joined = new StringBuilder();
		for (String w : title) {
			if (joined.length() > 0) {
				joined.append(' ');
			}
			joined.append(w);
		}
		String t = joined.toString().replaceAll("\\s*-\\s*$", "");
		if (t.isEmpty()) {
			t = raw == null ? "" : raw;
		}
		return new ReleaseName(t, ep, year, tags);
	}
}
