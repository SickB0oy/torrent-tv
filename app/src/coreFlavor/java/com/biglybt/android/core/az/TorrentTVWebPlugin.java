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

package com.biglybt.android.core.az;

import com.aelitis.azureus.plugins.xmwebui.XMWebUIPlugin;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadManager;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.tracker.web.TrackerWebPageRequest;
import com.biglybt.pif.tracker.web.TrackerWebPageResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Torrent TV: the remote access web server, plus tracker search.
 * <p/>
 * Adds three commands to the web server of remote access (same port, same
 * password), used by the "Search" tab of the remote page:
 * <ul>
 * <li>/ttv/trackers/get, /ttv/trackers/set: the trackers to search
 * (name, Torznab API address, API key). Keys stay on this device: they are
 * never sent back to the browser.</li>
 * <li>/ttv/search: searches every tracker that has a key, at the same time,
 * with the Torznab API (c411, TR4KER, Prowlarr, Jackett…).</li>
 * <li>/ttv/add: downloads the .torrent of a result here (its link holds the
 * passkey, which the browser never sees) and adds it.</li>
 * </ul>
 * Requests must carry the "X-TTV" header: a web page of another site cannot
 * send it without the browser asking this server first, which it refuses.
 */
public class TorrentTVWebPlugin
	extends XMWebUIPlugin
{
	private static final String PARAM_TRACKERS = "ttv.trackers";

	private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) "
			+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

	private static final int TIMEOUT_MS = 15000;

	private static final int MAX_BYTES = 8 * 1024 * 1024;

	private static final int MAX_REFS = 3000;

	private PluginInterface pi;

	private final SecureRandom random = new SecureRandom();

	/** Result id → [tracker id, download link]. Links hold the passkey. */
	private final Map<String, String[]> refs = new LinkedHashMap<String, String[]>(
			256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
			return size() > MAX_REFS;
		}
	};

	@Override
	public void initialize(PluginInterface _plugin_interface)
			throws PluginException {
		pi = _plugin_interface;
		super.initialize(_plugin_interface);
	}

	@Override
	public boolean generateSupport(TrackerWebPageRequest request,
			TrackerWebPageResponse response)
			throws IOException {
		String url = request.getURL();
		if (url == null || !url.startsWith("/ttv/")) {
			return super.generateSupport(request, response);
		}
		Map<?, ?> headers = request.getHeaders();
		if (headers == null || headers.get("x-ttv") == null) {
			response.setReplyStatus(403);
			return true;
		}
		int q = url.indexOf('?');
		String path = q >= 0 ? url.substring(0, q) : url;

		JSONObject out;
		try {
			JSONObject in = readJson(request.getInputStream());
			switch (path) {
				case "/ttv/trackers/get":
					out = trackersForBrowser();
					break;
				case "/ttv/trackers/set":
					out = setTrackers(in.optJSONArray("trackers"));
					break;
				case "/ttv/search":
					out = search(in.optString("q", "").trim(), in.optString("cat", "all"));
					break;
				case "/ttv/add":
					out = add(in.optString("ref", ""));
					break;
				case "/ttv/library/get":
					out = libraryForBrowser();
					break;
				case "/ttv/library/play":
					out = playOnTv(in.optString("path", ""));
					break;
				default:
					response.setReplyStatus(404);
					return true;
			}
		} catch (Throwable t) {
			out = new JSONObject();
			try {
				out.put("error", messageOf(t));
			} catch (JSONException ignore) {
			}
		}
		response.setContentType("application/json; charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.getOutputStream().write(out.toString().getBytes(StandardCharsets.UTF_8));
		return true;
	}

	/* ---------- Media library (films and series on this device) ---------- */

	private JSONObject libraryForBrowser() throws JSONException {
		android.content.Context ctx = com.biglybt.android.client.BiglyBTApp.getContext();
		com.biglybt.android.client.media.MediaLibrary lib = new com.biglybt.android.client.media.MediaLibrary(
				ctx);
		@SuppressWarnings("deprecation")
		android.content.SharedPreferences pos = ctx.getSharedPreferences(
				"player_positions", android.content.Context.MODE_MULTI_PROCESS);
		JSONArray a = new JSONArray();
		for (com.biglybt.android.client.media.MediaLibrary.Item it : lib.loadCache()) {
			if (it.path == null || !new java.io.File(it.path).isFile()) {
				continue;
			}
			String key = it.uri().toString();
			JSONObject o = new JSONObject();
			o.put("path", it.path);
			o.put("title", it.title == null ? "" : it.title);
			o.put("year", it.year == null ? "" : it.year);
			o.put("overview", it.overview == null ? "" : it.overview);
			o.put("season", it.season);
			o.put("episode", it.episode);
			o.put("episodeTitle", it.episodeTitle == null ? "" : it.episodeTitle);
			o.put("show", com.biglybt.android.client.media.MediaLibrary.showKey(it));
			o.put("poster", it.posterUrl() == null ? "" : it.posterUrl());
			o.put("size", it.size);
			o.put("modified", it.modified);
			o.put("watched", pos.getBoolean("watched:" + key, false));
			o.put("resume", pos.getLong(key, 0));
			a.put(o);
		}
		return new JSONObject().put("items", a);
	}

	/**
	 * Starts a film on the TV. Android lets the app open the player while
	 * Torrent TV is on screen; otherwise the browser says to open it first.
	 */
	private JSONObject playOnTv(String path) throws JSONException {
		android.content.Context ctx = com.biglybt.android.client.BiglyBTApp.getContext();
		com.biglybt.android.client.media.MediaLibrary lib = new com.biglybt.android.client.media.MediaLibrary(
				ctx);
		com.biglybt.android.client.media.MediaLibrary.Item it = path.isEmpty() ? null
				: lib.findByPath(path);
		if (it == null || !new java.io.File(path).isFile()) {
			return new JSONObject().put("error", "NOT_FOUND");
		}
		String title = it.title == null ? new java.io.File(path).getName()
				: it.isEpisode() ? it.title + " — " + it.episodeLabel() : it.title;
		android.content.Intent i = new android.content.Intent(ctx,
				com.biglybt.android.client.activity.PlayerActivity.class);
		i.setData(it.uri());
		i.putExtra(com.biglybt.android.client.activity.PlayerActivity.EXTRA_TITLE,
				title);
		i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
				| android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
		ctx.startActivity(i);
		return new JSONObject().put("ok", true);
	}

	/* ---------- Trackers ---------- */

	private JSONArray defaultTrackers() throws JSONException {
		JSONArray a = new JSONArray();
		a.put(tracker("c411", "C411", "https://c411.org/api/torznab", ""));
		a.put(tracker("tr4ker", "TR4KER", "https://tr4ker.net/api/torznab/all", ""));
		return a;
	}

	private static JSONObject tracker(String id, String name, String url,
			String key) throws JSONException {
		JSONObject o = new JSONObject();
		o.put("id", id);
		o.put("name", name);
		o.put("url", url);
		o.put("key", key);
		return o;
	}

	private synchronized JSONArray loadTrackers() throws JSONException {
		String s = pi.getPluginconfig().getPluginStringParameter(PARAM_TRACKERS, "");
		if (s == null || s.isEmpty()) {
			return defaultTrackers();
		}
		return new JSONArray(s);
	}

	private synchronized void saveTrackers(JSONArray a) throws PluginException {
		PluginConfig config = pi.getPluginconfig();
		config.setPluginParameter(PARAM_TRACKERS, a.toString());
		config.save();
	}

	/** Same list, keys replaced by "hasKey" */
	private JSONObject trackersForBrowser() throws JSONException {
		JSONArray all = loadTrackers();
		JSONArray safe = new JSONArray();
		for (int i = 0; i < all.length(); i++) {
			JSONObject t = all.getJSONObject(i);
			JSONObject o = new JSONObject();
			o.put("id", t.optString("id"));
			o.put("name", t.optString("name"));
			o.put("url", t.optString("url"));
			o.put("hasKey", !t.optString("key").isEmpty());
			safe.put(o);
		}
		return new JSONObject().put("trackers", safe);
	}

	private JSONObject setTrackers(JSONArray wanted)
			throws JSONException, PluginException {
		if (wanted == null) {
			throw new IllegalArgumentException("trackers");
		}
		JSONArray old = loadTrackers();
		JSONArray next = new JSONArray();
		for (int i = 0; i < wanted.length() && i < 20; i++) {
			JSONObject w = wanted.getJSONObject(i);
			String url = w.optString("url").trim();
			String name = w.optString("name").trim();
			if (url.isEmpty()) {
				continue;
			}
			if (!url.startsWith("https://") && !url.startsWith("http://")) {
				throw new IllegalArgumentException("URL: " + url);
			}
			String id = w.optString("id").trim();
			if (id.isEmpty()) {
				id = "t" + Long.toHexString(random.nextLong() & 0xffffffffL);
			}
			// Empty key = keep the one saved here (the browser never has it)
			String key = w.optString("key").trim();
			if (key.isEmpty() && !w.optBoolean("clearKey")) {
				for (int j = 0; j < old.length(); j++) {
					JSONObject o = old.getJSONObject(j);
					if (id.equals(o.optString("id"))) {
						key = o.optString("key");
					}
				}
			}
			next.put(tracker(id, name.isEmpty() ? hostOf(url) : name, url, key));
		}
		saveTrackers(next);
		return trackersForBrowser();
	}

	/* ---------- Search ---------- */

	private JSONObject search(final String query, String cat)
			throws JSONException, InterruptedException {
		final String cats = "movies".equals(cat) ? "2000"
				: "tv".equals(cat) ? "5000" : "";
		JSONArray trackers = loadTrackers();
		List<JSONObject> withKey = new ArrayList<>();
		for (int i = 0; i < trackers.length(); i++) {
			JSONObject t = trackers.getJSONObject(i);
			if (!t.optString("key").isEmpty()) {
				withKey.add(t);
			}
		}
		JSONObject out = new JSONObject();
		JSONArray results = new JSONArray();
		JSONArray errors = new JSONArray();
		out.put("results", results);
		out.put("errors", errors);
		out.put("searched", withKey.size());
		if (withKey.isEmpty()) {
			return out;
		}

		ExecutorService pool = Executors.newFixedThreadPool(withKey.size());
		try {
			List<Future<JSONArray>> futures = new ArrayList<>();
			for (final JSONObject t : withKey) {
				futures.add(pool.submit(() -> searchOne(t, query, cats)));
			}
			for (int i = 0; i < futures.size(); i++) {
				JSONObject t = withKey.get(i);
				try {
					JSONArray part = futures.get(i).get(TIMEOUT_MS * 2L,
							TimeUnit.MILLISECONDS);
					for (int j = 0; j < part.length(); j++) {
						results.put(part.get(j));
					}
				} catch (Throwable e) {
					Throwable cause = e.getCause() != null ? e.getCause() : e;
					errors.put(new JSONObject().put("tracker", t.optString("name")).put(
							"error", messageOf(cause)));
				}
			}
		} finally {
			pool.shutdownNow();
		}
		return out;
	}

	private JSONArray searchOne(JSONObject t, String query, String cats)
			throws Exception {
		String base = t.optString("url");
		StringBuilder u = new StringBuilder(base);
		u.append(base.contains("?") ? '&' : '?');
		u.append("t=search&limit=100&apikey=").append(enc(t.optString("key")));
		u.append("&q=").append(enc(query));
		if (!cats.isEmpty()) {
			u.append("&cat=").append(cats);
		}
		Fetched f = fetch(u.toString());
		if (f.status == 401 || f.status == 403) {
			throw new IOException("AUTH");
		}
		if (f.status != 200) {
			throw new IOException("HTTP " + f.status);
		}
		return parseTorznab(f.body, t);
	}

	private JSONArray parseTorznab(byte[] xml, JSONObject t) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(false);
		dbf.setExpandEntityReferences(false);
		try {
			dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		} catch (Throwable ignore) {
		}
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(new ByteArrayInputStream(xml));

		// Torznab error: <error code="100" description="Invalid API Key"/>
		Element root = doc.getDocumentElement();
		if (root != null && "error".equals(root.getTagName())) {
			throw new IOException(root.getAttribute("description") + " ("
					+ root.getAttribute("code") + ")");
		}

		String trackerKey = t.optString("key");
		JSONArray out = new JSONArray();
		NodeList items = doc.getElementsByTagName("item");
		SimpleDateFormat rfc822 = new SimpleDateFormat(
				"EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
		for (int i = 0; i < items.getLength(); i++) {
			Element item = (Element) items.item(i);
			String title = text(item, "title");
			if (title.isEmpty()) {
				continue;
			}
			String link = "";
			NodeList enc = item.getElementsByTagName("enclosure");
			if (enc.getLength() > 0) {
				link = ((Element) enc.item(0)).getAttribute("url");
			}
			if (link.isEmpty()) {
				link = text(item, "link");
			}
			if (link.isEmpty()) {
				continue;
			}
			long size = parseLong(text(item, "size"), -1);
			long seeders = -1, peers = -1, grabs = -1;
			double dlFactor = 1;
			boolean exclusive = EXCLU_TITLE.matcher(title).find();
			List<Integer> categories = new ArrayList<>();
			for (String tag : new String[] {
				"torznab:attr",
				"newznab:attr",
				"attr"
			}) {
				NodeList attrs = item.getElementsByTagName(tag);
				for (int j = 0; j < attrs.getLength(); j++) {
					Element a = (Element) attrs.item(j);
					String n = a.getAttribute("name"), v = a.getAttribute("value");
					switch (n) {
						case "seeders":
							seeders = parseLong(v, seeders);
							break;
						case "peers":
							peers = parseLong(v, peers);
							break;
						case "grabs":
							grabs = parseLong(v, grabs);
							break;
						case "size":
							if (size < 0) {
								size = parseLong(v, -1);
							}
							break;
						case "category":
							long c = parseLong(v, -1);
							if (c > 0) {
								categories.add((int) c);
							}
							break;
						case "downloadvolumefactor":
							try {
								dlFactor = Double.parseDouble(v.trim());
							} catch (Throwable ignore) {
							}
							break;
						default:
							// No standard Torznab field for "exclusive": look for the
							// usual ways trackers mark them (tag, internal, exclu…)
							String ln = n.toLowerCase(Locale.ROOT);
							String lv = v.toLowerCase(Locale.ROOT);
							if (ln.contains("exclu") || ln.equals("internal")) {
								exclusive |= !lv.isEmpty() && !lv.equals("0")
										&& !lv.equals("false") && !lv.equals("no");
							} else if (ln.equals("tag") || ln.equals("tags")) {
								exclusive |= lv.contains("exclu") || lv.contains("internal");
							}
							break;
					}
				}
			}
			if (size < 0 && enc.getLength() > 0) {
				size = parseLong(((Element) enc.item(0)).getAttribute("length"), -1);
			}
			long date = 0;
			try {
				Date d = rfc822.parse(text(item, "pubDate"));
				if (d != null) {
					date = d.getTime() / 1000;
				}
			} catch (Throwable ignore) {
			}
			// Page of the torrent on the tracker site, only if it holds no key
			String details = text(item, "comments");
			if (details.isEmpty()) {
				String guid = text(item, "guid");
				if (guid.startsWith("http")) {
					details = guid;
				}
			}
			if (!details.startsWith("http") || details.equals(link)
					|| (!trackerKey.isEmpty() && details.contains(trackerKey))
					|| details.contains("passkey") || details.contains("apikey")) {
				details = "";
			}

			String ref = newRef(t.optString("id"), link);
			JSONObject r = new JSONObject();
			r.put("ref", ref);
			r.put("title", title);
			r.put("size", size);
			r.put("seeders", seeders);
			// Torznab "peers" = seeders + leechers
			r.put("leechers", peers >= 0 && seeders >= 0 ? Math.max(0, peers - seeders)
					: -1);
			r.put("date", date);
			// Times downloaded to the end ("completed")
			r.put("grabs", grabs);
			r.put("tracker", t.optString("name"));
			r.put("kind", kindOf(categories));
			if (dlFactor == 0) {
				r.put("free", true);
			}
			if (exclusive) {
				r.put("exclu", true);
			}
			if (!details.isEmpty()) {
				r.put("details", details);
			}
			out.put(r);
		}
		return out;
	}

	/** "[EXCLU]", "EXCLUSIVITE", "Exclusive"… in the release name */
	private static final java.util.regex.Pattern EXCLU_TITLE = java.util.regex.Pattern.compile(
			"(?i)(^|[^a-z])exclu(sive|sivite|sivité)?([^a-z]|$)");

	private static String kindOf(List<Integer> cats) {
		for (int c : cats) {
			if (c >= 2000 && c < 3000) {
				return "movie";
			}
			if (c >= 5000 && c < 6000) {
				return "tv";
			}
		}
		return "other";
	}

	private synchronized String newRef(String trackerId, String link) {
		byte[] b = new byte[9];
		random.nextBytes(b);
		StringBuilder sb = new StringBuilder();
		for (byte x : b) {
			sb.append(String.format(Locale.US, "%02x", x));
		}
		String ref = sb.toString();
		refs.put(ref, new String[] {
			trackerId,
			link
		});
		return ref;
	}

	/* ---------- Add ---------- */

	private JSONObject add(String ref) throws Exception {
		String[] entry;
		synchronized (this) {
			entry = refs.get(ref);
		}
		if (entry == null) {
			throw new IOException("EXPIRED");
		}
		String link = entry[1];
		JSONObject out = new JSONObject();
		if (link.startsWith("magnet:")) {
			out.put("magnet", link);
			return out;
		}
		Fetched f = fetch(link);
		if (f.redirectMagnet != null) {
			out.put("magnet", f.redirectMagnet);
			return out;
		}
		if (f.status != 200) {
			throw new IOException("HTTP " + f.status);
		}
		if (f.body.length == 0 || f.body[0] != 'd') {
			throw new IOException("NOT_TORRENT");
		}
		Torrent torrent = pi.getTorrentManager().createFromBEncodedData(f.body);
		DownloadManager dm = pi.getDownloadManager();
		Download existing = dm.getDownload(torrent);
		if (existing != null) {
			out.put("duplicate", existing.getName());
			return out;
		}
		Download d = dm.addDownload(torrent);
		out.put("added", d != null ? d.getName() : torrent.getName());
		return out;
	}

	/* ---------- Helpers ---------- */

	private static final class Fetched
	{
		int status;

		byte[] body = new byte[0];

		String redirectMagnet;
	}

	/** GET, following redirects by hand (a .torrent link may lead to a magnet) */
	private static Fetched fetch(String url) throws IOException {
		Fetched f = new Fetched();
		String current = url;
		for (int hop = 0; hop < 6; hop++) {
			HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
			c.setInstanceFollowRedirects(false);
			if (c instanceof HttpsURLConnection) {
				((HttpsURLConnection) c).setSSLSocketFactory(systemSsl());
			}
			c.setConnectTimeout(TIMEOUT_MS);
			c.setReadTimeout(TIMEOUT_MS);
			c.setRequestProperty("User-Agent", USER_AGENT);
			c.setRequestProperty("Accept",
					"application/rss+xml, application/xml, application/x-bittorrent, */*");
			try {
				int code = c.getResponseCode();
				if (code >= 300 && code < 400) {
					String loc = c.getHeaderField("Location");
					if (loc == null) {
						f.status = code;
						return f;
					}
					if (loc.startsWith("magnet:")) {
						f.status = 200;
						f.redirectMagnet = loc;
						return f;
					}
					current = new URL(new URL(current), loc).toString();
					continue;
				}
				f.status = code;
				InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
				if (is != null) {
					f.body = readAll(is);
				}
				return f;
			} finally {
				c.disconnect();
			}
		}
		throw new IOException("too many redirects");
	}

	private static SSLSocketFactory systemSslFactory;

	/**
	 * TLS with Android's own trusted certificates. The torrent engine replaces
	 * the default ones with its own store, which does not know the
	 * certificates of most sites ("Trust anchor for certification path not
	 * found").
	 */
	private static synchronized SSLSocketFactory systemSsl() throws IOException {
		if (systemSslFactory == null) {
			try {
				KeyStore ks = KeyStore.getInstance("AndroidCAStore");
				ks.load(null, null);
				TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
				tmf.init(ks);
				SSLContext ctx = SSLContext.getInstance("TLS");
				ctx.init(null, tmf.getTrustManagers(), null);
				systemSslFactory = ctx.getSocketFactory();
			} catch (Exception e) {
				throw new IOException("TLS: " + e.getMessage(), e);
			}
		}
		return systemSslFactory;
	}

	private static byte[] readAll(InputStream is) throws IOException {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
			byte[] buf = new byte[16384];
			int n;
			while ((n = is.read(buf)) > 0) {
				bos.write(buf, 0, n);
				if (bos.size() > MAX_BYTES) {
					throw new IOException("response too large");
				}
			}
			return bos.toByteArray();
		} finally {
			is.close();
		}
	}

	private static JSONObject readJson(InputStream is) throws IOException,
			JSONException {
		if (is == null) {
			return new JSONObject();
		}
		String s = new String(readAll(is), StandardCharsets.UTF_8).trim();
		return s.isEmpty() ? new JSONObject() : new JSONObject(s);
	}

	private static String text(Element parent, String tag) {
		NodeList nl = parent.getElementsByTagName(tag);
		if (nl.getLength() == 0) {
			return "";
		}
		String s = nl.item(0).getTextContent();
		return s == null ? "" : s.trim();
	}

	private static long parseLong(String s, long def) {
		try {
			return Long.parseLong(s.trim());
		} catch (Throwable t) {
			return def;
		}
	}

	private static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (Exception e) {
			return s;
		}
	}

	private static String hostOf(String url) {
		try {
			return new URL(url).getHost();
		} catch (Exception e) {
			return url;
		}
	}

	private static String messageOf(Throwable t) {
		if (t instanceof java.net.SocketTimeoutException) {
			return "TIMEOUT";
		}
		if (t instanceof java.net.UnknownHostException) {
			return "DNS: " + t.getMessage();
		}
		String m = t.getMessage();
		return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
	}
}
