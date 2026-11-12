package dev.pic.blueprint.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloading schematics from links, and finding schematic links inside a video page's description. */
public final class Links {
	private Links() {}

	public static final long MAX_BYTES = 64L * 1024 * 1024;
	private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public static final class Download {
		public final byte[] data;
		public final String fileName;
		public Download(byte[] data, String fileName) { this.data = data; this.fileName = fileName; }
	}

	/** Rewrites share links (Dropbox, Google Drive, GitHub) into direct-download links. */
	public static String directUrl(String url) {
		String u = url.trim();
		if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
		Matcher m;
		if ((m = Pattern.compile("drive\\.google\\.com/file/d/([A-Za-z0-9_-]+)").matcher(u)).find()
				|| (m = Pattern.compile("drive\\.google\\.com/open\\?id=([A-Za-z0-9_-]+)").matcher(u)).find()) {
			return "https://drive.usercontent.google.com/download?id=" + m.group(1) + "&export=download&confirm=t";
		}
		if (u.contains("dropbox.com/")) {
			u = u.replace("dl=0", "dl=1");
			if (!u.contains("dl=1")) u += (u.contains("?") ? "&" : "?") + "dl=1";
			return u;
		}
		if ((m = Pattern.compile("https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)").matcher(u)).find()) {
			return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3);
		}
		return u;
	}

	public static Download download(String url) throws IOException, InterruptedException {
		String direct = directUrl(url);
		HttpResponse<InputStream> res = get(direct);
		String ctype = res.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
		byte[] body = readLimited(res.body());

		// MediaFire / other HTML landing pages: look for the real file link inside the page.
		if (ctype.contains("text/html") || looksLikeHtml(body)) {
			String html = new String(body, StandardCharsets.UTF_8);
			String inner = findFileLinkInHtml(html);
			if (inner == null) {
				throw new IOException("That link is a web page, not a file. Open it, copy the direct download link of the .schem/.litematic/.nbt and try again.");
			}
			res = get(inner);
			body = readLimited(res.body());
			if (looksLikeHtml(body)) throw new IOException("Could not get the file from that page. Use the direct download link.");
			direct = inner;
		}
		return new Download(body, fileNameFrom(res, direct));
	}

	private static HttpResponse<InputStream> get(String url) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(60))
				.header("User-Agent", UA)
				.header("Accept-Language", "en-US,en;q=0.9")
				.GET().build();
		HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() >= 400) {
			res.body().close();
			throw new IOException("Server answered HTTP " + res.statusCode());
		}
		return res;
	}

	private static byte[] readLimited(InputStream in) throws IOException {
		try (in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[16384];
			long total = 0;
			int n;
			while ((n = in.read(buf)) > 0) {
				total += n;
				if (total > MAX_BYTES) throw new IOException("File is bigger than 64 MB");
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	private static boolean looksLikeHtml(byte[] b) {
		int n = Math.min(b.length, 512);
		String head = new String(b, 0, n, StandardCharsets.ISO_8859_1).trim().toLowerCase(Locale.ROOT);
		return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<head");
	}

	private static final Pattern FILE_EXT = Pattern.compile("(?i)\\.(schem|schematic|litematic|nbt)(\\?|$|#)");

	static String findFileLinkInHtml(String html) {
		Matcher m = Pattern.compile("href=\"(https?://download[0-9]*\\.mediafire\\.com/[^\"]+)\"").matcher(html);
		if (m.find()) return m.group(1);
		m = Pattern.compile("(https?://[^\"'\\s<>]+)").matcher(html);
		while (m.find()) {
			String u = m.group(1).replace("&amp;", "&");
			if (FILE_EXT.matcher(u).find()) return u;
		}
		return null;
	}

	private static String fileNameFrom(HttpResponse<?> res, String url) {
		String cd = res.headers().firstValue("content-disposition").orElse("");
		Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
		String name = null;
		if (m.find()) name = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
		if (name == null || name.isBlank()) {
			String path = URI.create(res.uri().toString()).getPath();
			if (path != null) name = path.substring(path.lastIndexOf('/') + 1);
		}
		return safeFileName(name == null || name.isBlank() ? "download" : name);
	}

	public static String safeFileName(String name) {
		String n = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		if (n.length() > 80) n = n.substring(n.length() - 80);
		return n.isEmpty() ? "download" : n;
	}

	// ---------------- video links ----------------

	/**
	 * Fetches a video page (YouTube, etc.) and returns links from its description that look like
	 * schematic downloads, best candidates first.
	 */
	public static List<String> scanVideo(String videoUrl) throws IOException, InterruptedException {
		String u = videoUrl.trim();
		if (!u.startsWith("http")) u = "https://" + u;
		HttpResponse<InputStream> res = get(u);
		String html = new String(readLimited(res.body()), StandardCharsets.UTF_8);
		return candidateLinks(extractDescription(html));
	}

	/** Pulls the description text out of a YouTube watch page (falls back to the whole page). */
	static String extractDescription(String html) {
		Matcher m = Pattern.compile("\"shortDescription\":\"((?:\\\\.|[^\"\\\\])*)\"").matcher(html);
		if (m.find()) return unescapeJson(m.group(1));
		m = Pattern.compile("<meta (?:name|property)=\"(?:og:)?description\" content=\"([^\"]*)\"").matcher(html);
		StringBuilder sb = new StringBuilder();
		while (m.find()) sb.append(m.group(1).replace("&amp;", "&")).append('\n');
		sb.append(html);
		return sb.toString();
	}

	static String unescapeJson(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				char n = s.charAt(++i);
				switch (n) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case 'u' -> {
						if (i + 4 < s.length()) {
							sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
							i += 4;
						}
					}
					default -> sb.append(n);
				}
			} else sb.append(c);
		}
		return sb.toString();
	}

	private static final String[] HOSTS = {
		"mediafire.com", "drive.google.com", "dropbox.com", "planetminecraft.com", "github.com",
		"raw.githubusercontent.com", "mega.nz", "curseforge.com", "modrinth.com", "patreon.com",
		"abfielder.com", "minecraft-schematics.com", "grabcraft.com", "cdn.discordapp.com"
	};

	static List<String> candidateLinks(String text) {
		Matcher m = Pattern.compile("(https?://[^\\s\"'<>\\\\]+)").matcher(text);
		Set<String> direct = new LinkedHashSet<>();
		Set<String> hosted = new LinkedHashSet<>();
		while (m.find()) {
			String link = m.group(1).replaceAll("[).,;!]+$", "").replace("&amp;", "&");
			String lower = link.toLowerCase(Locale.ROOT);
			if (lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("ytimg.com")
					|| lower.contains("google.com/s2") || lower.contains("gstatic.com") || lower.contains("googleapis.com")) continue;
			if (FILE_EXT.matcher(lower).find()) direct.add(link);
			else for (String h : HOSTS) if (lower.contains(h)) { hosted.add(link); break; }
		}
		List<String> out = new ArrayList<>(direct);
		for (String h : hosted) if (!out.contains(h)) out.add(h);
		return out.size() > 8 ? out.subList(0, 8) : out;
	}
}
