package miku.moe.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;

public final class SokujaSource {
    public static final String BASE = "https://x6.sokuja.uk";
    private static final int PAGE_SIZE = 24;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private static final SimpleCookieJar COOKIE_JAR = new SimpleCookieJar();

    private static final String SOURCE_LABEL = "Sokuja";
    private static final long CLOUDFLARE_WAIT_MS = 180_000L;

    private static final class SokujaCloudflareException extends IOException {
        SokujaCloudflareException(String message) {
            super(message);
        }
    }

    private static final OkHttpClient CLIENT = CloudflareHelper.apply(new OkHttpClient.Builder())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public static void setWebViewCookies(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) return;
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(BASE);
        if (url == null) return;
        List<okhttp3.Cookie> cookies = new ArrayList<>();
        for (String part : cookieHeader.split(";")) {
            String pair = part.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                cookies.add(new okhttp3.Cookie.Builder()
                        .name(pair.substring(0, eq).trim())
                        .value(pair.substring(eq + 1).trim())
                        .domain("x6.sokuja.uk")
                        .path("/")
                        .build());
            } catch (Exception ignored) {
            }
        }
        if (!cookies.isEmpty()) COOKIE_JAR.saveFromResponse(url, cookies);
    }

    private static final class SimpleCookieJar implements okhttp3.CookieJar {
        private final Map<String, List<okhttp3.Cookie>> store = new LinkedHashMap<>();

        @Override
        public synchronized void saveFromResponse(okhttp3.HttpUrl url, List<okhttp3.Cookie> cookies) {
            List<okhttp3.Cookie> kept = store.get(url.host());
            if (kept == null) {
                kept = new ArrayList<>();
                store.put(url.host(), kept);
            }
            for (okhttp3.Cookie cookie : cookies) {
                for (int i = kept.size() - 1; i >= 0; i--) {
                    if (kept.get(i).name().equals(cookie.name())) kept.remove(i);
                }
                kept.add(cookie);
            }
        }

        @Override
        public synchronized List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
            List<okhttp3.Cookie> kept = store.get(url.host());
            if (kept == null) return new ArrayList<>();
            List<okhttp3.Cookie> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (okhttp3.Cookie cookie : kept) {
                if (cookie.expiresAt() < now) continue;
                if (cookie.matches(url)) out.add(cookie);
            }
            return out;
        }
    }

    private static final Map<String, String> GENRE_SLUG_CACHE = new LinkedHashMap<>();
    private static final Map<String, DetailPage> DETAIL_CACHE = new LinkedHashMap<>();
    private static final Map<String, Long> DETAIL_CACHE_AT = new LinkedHashMap<>();
    private static final long DETAIL_CACHE_TTL_MS = 10 * 60 * 1000L;

    private SokujaSource() {}

    public static final class PageResult {
        public final List<AnimePost> items;
        public final boolean hasMore;
        public final int rawCount;

        public PageResult(List<AnimePost> items, boolean hasMore, int rawCount) {
            this.items = items;
            this.hasMore = hasMore;
            this.rawCount = rawCount;
        }
    }

    public static final class DetailResult {
        public final AnimePost post;
        public final String description;
        public final List<String> genres;
        public final Map<String, String> rows;
        public final int episodeCount;
        public final int episodeTotalHeading;
        public final double latestEpisodeNumber;
        public final String latestEpisodeLabel;

        public DetailResult(AnimePost post, String description, List<String> genres,
                            Map<String, String> rows, int episodeCount,
                            int episodeTotalHeading, double latestEpisodeNumber,
                            String latestEpisodeLabel) {
            this.post = post;
            this.description = description;
            this.genres = genres;
            this.rows = rows;
            this.episodeCount = episodeCount;
            this.episodeTotalHeading = episodeTotalHeading;
            this.latestEpisodeNumber = latestEpisodeNumber;
            this.latestEpisodeLabel = latestEpisodeLabel;
        }
    }

    public static final class EpisodeResult {
        public final int id;
        public final String title;
        public final String subtitle;
        public final String episodeSlug;
        public final double number;
        public final String releaseDateIso;

        public EpisodeResult(int id, String title, String subtitle, String episodeSlug,
                             double number, String releaseDateIso) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.episodeSlug = episodeSlug;
            this.number = number;
            this.releaseDateIso = releaseDateIso;
        }
    }

    public static final class StreamResult {
        public final String quality;
        public final String label;
        public final String url;

        public StreamResult(String quality, String label, String url) {
            this.quality = quality;
            this.label = label;
            this.url = url;
        }
    }

    public static final class VideoResult {
        public final List<StreamResult> options;
        public final String streamUrl;

        public VideoResult(List<StreamResult> options, String streamUrl) {
            this.options = options;
            this.streamUrl = streamUrl;
        }
    }


    public static PageResult listing(String order, int page) throws IOException {
        String safeOrder = safeOrder(order);
        String path = "/anime/?order=" + safeOrder + (page > 1 ? "&page=" + page : "");
        return parseListPage(fetchPage(path, BASE + "/anime/"), page);
    }

    public static PageResult search(String query, int page) throws IOException {
        String path = "/?s=" + encode(query) + (page > 1 ? "&page=" + page : "");
        return parseListPage(fetchPage(path, BASE + "/"), page);
    }

    public static PageResult genre(String genreName, int page) throws IOException {
        String slug = genreSlug(genreName);
        if (slug.isEmpty()) return new PageResult(new ArrayList<>(), false, 0);
        String genrePath = "/genre/" + slug + "/" + (page > 1 ? "?page=" + page : "");
        PageResult primary = parseListPage(fetchPage(genrePath, BASE + "/genre/" + slug + "/"), page);
        if (!primary.items.isEmpty()) return primary;
        String fallbackPath = "/anime/?genre=" + encode(slug) + (page > 1 ? "&page=" + page : "");
        return parseListPage(fetchPage(fallbackPath, BASE + "/anime/"), page);
    }

    public static List<String> genreNames() {
        try {
            Map<String, String> map = loadGenreMap();
            return new ArrayList<>(map.keySet());
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }


    public static DetailResult detail(AnimePost initial) throws IOException {
        String slug = initial != null && initial.slug != null ? initial.slug.trim() : "";
        if (slug.isEmpty()) throw new IOException("Sokuja: slug anime kosong");
        DetailPage page = detailPage(slug);
        String title = firstUseful(page.title, initial != null ? initial.categoryName : "");
        String cover = firstUseful(page.cover, initial != null ? initial.imgUrl : "");
        AnimePost post = new AnimePost(cover, title, positiveId(slug), -1);
        post.sourceId = AnimeSettingsManager.SOURCE_SOKUJA;
        post.slug = slug;
        post.channelName = "";
        if (page.score > 0) post.rating = stripTrailingZeros(page.score);
        post.description = page.synopsis != null ? page.synopsis : "";
        post.statusVideo = firstUseful(page.info.get("Status"), initial != null ? initial.statusVideo : "");
        post.year = page.year > 0 ? page.year : (initial != null ? initial.year : 0);
        post.ongoing = !post.statusVideo.toLowerCase(Locale.ROOT).contains("complete")
                && !post.statusVideo.toLowerCase(Locale.ROOT).contains("finished")
                && !post.statusVideo.equalsIgnoreCase("selesai");
        Map<String, String> rows = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : page.info.entrySet()) {
            String key = entry.getKey();
            if (key.equals("Status") || key.equals("Tahun")) continue;
            if (key.equals("Episode")) {
                putRow(rows, "Total Episode (rencana)", entry.getValue());
            } else {
                putRow(rows, key, entry.getValue());
            }
        }
        List<String> genres = new ArrayList<>(page.genres);
        post.genre = String.join(", ", genres);
        if (initial != null && post.genre.isEmpty()) post.genre = initial.genre;
        int latestInt = (int) page.latestEpisodeNumber;
        post.totalEpisodes = latestInt;
        post.episodeCount = page.episodes.size() > 0 ? String.valueOf(page.episodes.size()) : post.episodeCount;
        return new DetailResult(post, post.description, genres, rows,
                page.episodes.size(), page.episodeTotalHeading,
                page.latestEpisodeNumber, page.latestEpisodeLabel);
    }

    public static List<EpisodeResult> episodes(String seriesSlug) throws IOException {
        String slug = seriesSlug != null ? seriesSlug.trim() : "";
        if (slug.isEmpty()) throw new IOException("Sokuja: slug anime kosong");
        DetailPage page = detailPage(slug);
        List<EpisodeResult> result = new ArrayList<>();
        for (EpisodeEntry entry : page.episodes) {
            result.add(new EpisodeResult(positiveId(entry.slug), entry.label,
                    entry.relativeText, entry.slug, entry.number, entry.createdAtIso));
        }
        return result;
    }


    public static VideoResult video(String episodeSlug) throws IOException {
        String slug = cleanSlug(episodeSlug);
        if (slug.isEmpty()) throw new IOException("Sokuja: slug episode kosong");
        String pageUrl = BASE + "/" + slug + "/";
        String html = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                html = fetchPage("/" + slug + "/", pageUrl);
                break;
            } catch (IOException ignored) {
                if (attempt < 2) {
                    try {
                        Thread.sleep(1000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        Integer episodeId = null;
        String referer = pageUrl;
        if (html != null) {
            episodeId = episodeIdFromPlayerProps(html);
            if (episodeId == null) {
                episodeId = episodeIdFromDetailMap(html, slug);
            }
        }
        if (episodeId == null) {
            String seriesSlug = deriveSeriesSlug(slug);
            if (seriesSlug == null) seriesSlug = slug;
            DetailPage series = detailPage(seriesSlug);
            for (EpisodeEntry entry : series.episodes) {
                if (entry.id > 0 && entry.slug.equals(slug)) {
                    episodeId = entry.id;
                    break;
                }
            }
            if (episodeId == null && seriesSlug.equals(slug)) {
                for (EpisodeEntry entry : series.episodes) {
                    if (entry.id > 0 && entry.number == 1) {
                        episodeId = entry.id;
                        break;
                    }
                }
            }
            if (episodeId == null) {
                throw new IOException("Sokuja: episodeId tidak ditemukan untuk " + slug);
            }
            referer = BASE + "/anime/" + seriesSlug + "/";
        }
        String json = null;
        IOException mirrorsError = null;
        String[] referers = new String[]{referer, BASE + "/", BASE + "/anime/"};
        for (String ref : referers) {
            if (ref == null || ref.isEmpty()) continue;
            try {
                json = fetchJsonWithRetry(BASE + "/api/video-mirrors/?e=" + episodeId, ref);
                break;
            } catch (IOException e) {
                mirrorsError = e;
            }
        }
        if (json == null) {
            throw new IOException("Sokuja: mirrors API tidak dapat dijangkau", mirrorsError);
        }
        return parseVideoJson(json);
    }

    public static VideoResult parseVideoJson(String json) throws IOException {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            throw new IOException("Sokuja: respons mirrors tidak valid", e);
        }
        Map<String, StreamResult> byQuality = new LinkedHashMap<>();
        JSONArray mirrors = root.optJSONArray("mirrors");
        if (mirrors != null) {
            for (int i = 0; i < mirrors.length(); i++) {
                JSONObject item = mirrors.optJSONObject(i);
                if (item == null) continue;
                String quality = item.optString("quality", "").trim();
                String url = item.optString("embedUrl", "").trim();
                String type = item.optString("embedType", "mp4").trim();
                if (quality.isEmpty() || url.isEmpty() || url.equals("null")) continue;
                if (!quality.matches("\\d+p")) continue;
                if (!type.isEmpty() && !type.equalsIgnoreCase("mp4")) continue;
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
                if (url.contains("sokuja.id/x.php")) continue;
                if (!byQuality.containsKey(quality)) {
                    byQuality.put(quality, new StreamResult(quality, quality, url));
                }
            }
        }
        List<StreamResult> options = new ArrayList<>(byQuality.values());
        options.sort((a, b) -> Integer.compare(qualityRank(b.quality), qualityRank(a.quality)));
        if (options.isEmpty()) {
            throw new IOException("Sokuja: tidak ada mirror video untuk episode ini");
        }
        String streamUrl = "";
        for (StreamResult option : options) {
            if (option.quality.equals("720p")) {
                streamUrl = option.url;
                break;
            }
        }
        if (streamUrl.isEmpty()) streamUrl = options.get(0).url;
        return new VideoResult(options, streamUrl);
    }


    private static final class EpisodeEntry {
        final int id;
        final String slug;
        final String title;
        final String label;
        final double number;
        final String createdAtIso;
        String relativeText;

        EpisodeEntry(int id, String slug, String title, double number, String createdAtIso) {
            this.id = id;
            this.slug = slug;
            this.title = title;
            this.number = number;
            this.createdAtIso = createdAtIso;
            this.label = episodeLabel(title, number);
        }
    }

    private static final class DetailPage {
        String title = "";
        String cover = "";
        double score = 0;
        String synopsis = "";
        Map<String, String> info = new LinkedHashMap<>();
        List<String> genres = new ArrayList<>();
        List<EpisodeEntry> episodes = new ArrayList<>();
        int episodeTotalHeading = 0;
        double latestEpisodeNumber = 0;
        String latestEpisodeLabel = "";
        int year = 0;
    }

    private static DetailPage detailPage(String slug) throws IOException {
        synchronized (DETAIL_CACHE) {
            DetailPage cached = DETAIL_CACHE.get(slug);
            Long at = DETAIL_CACHE_AT.get(slug);
            if (cached != null && at != null
                    && System.currentTimeMillis() - at < DETAIL_CACHE_TTL_MS) {
                return cached;
            }
        }
        String html = fetchPage("/anime/" + slug + "/", BASE + "/anime/" + slug + "/");
        DetailPage page = parseDetailPage(slug, html);
        synchronized (DETAIL_CACHE) {
            if (DETAIL_CACHE.size() >= 30) {
                String eldest = DETAIL_CACHE.keySet().iterator().next();
                DETAIL_CACHE.remove(eldest);
                DETAIL_CACHE_AT.remove(eldest);
            }
            DETAIL_CACHE.put(slug, page);
            DETAIL_CACHE_AT.put(slug, System.currentTimeMillis());
        }
        return page;
    }

    private static DetailPage parseDetailPage(String slug, String html) {
        DetailPage page = new DetailPage();
        String flat = unescapeRsc(html);
        Matcher h1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL).matcher(html);
        if (h1.find()) page.title = stripTags(htmlUnescape(h1.group(1)));
        if (page.title.endsWith("Subtitle Indonesia")) {
            page.title = page.title.substring(0, page.title.length() - "Subtitle Indonesia".length()).trim();
        }
        Matcher img = Pattern.compile("<img[^>]*alt=\"[^\"]*\"[^>]*src=\"([^\"]*)\"").matcher(html);
        if (img.find()) page.cover = normalizeCover(img.group(1));
        JSONObject ld = null;
        Matcher ldMatcher = Pattern.compile(
                "<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        if (ldMatcher.find()) {
            try {
                ld = new JSONObject(ldMatcher.group(1));
            } catch (Exception ignored) {
                ld = null;
            }
        }
        if (ld != null) {
            if (page.title.isEmpty()) page.title = ld.optString("name", "");
            if (page.cover.isEmpty()) page.cover = normalizeCover(ld.optString("image", ""));
            Object genreObj = ld.opt("genre");
            if (genreObj instanceof JSONArray) {
                JSONArray array = (JSONArray) genreObj;
                for (int i = 0; i < array.length(); i++) {
                    String name = array.optString(i, "").trim();
                    if (!name.isEmpty() && !name.equals("null") && !page.genres.contains(name)) {
                        page.genres.add(name);
                    }
                }
            } else if (genreObj instanceof String) {
                String name = ((String) genreObj).trim();
                if (!name.isEmpty() && !name.equals("null")) page.genres.add(name);
            }
            JSONObject rating = ld.optJSONObject("aggregateRating");
            if (rating != null) page.score = rating.optDouble("ratingValue", 0);
        }
        Matcher dl = Pattern.compile("Informasi Anime</h2>\\s*<dl[^>]*>(.*?)</dl>",
                Pattern.DOTALL).matcher(html);
        if (dl.find()) {
            Matcher row = Pattern.compile("<dt[^>]*>([^<]*)</dt>\\s*<dd[^>]*>(.*?)</dd>",
                    Pattern.DOTALL).matcher(dl.group(1));
            while (row.find()) {
                String label = stripTags(htmlUnescape(row.group(1)));
                String value = stripTags(htmlUnescape(row.group(2)));
                if (label != null && !label.isEmpty() && value != null && !value.isEmpty()) {
                    page.info.put(label, value);
                }
            }
        }
        if (page.info.containsKey("Tahun")) {
            try {
                page.year = Integer.parseInt(page.info.get("Tahun").replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
                page.year = 0;
            }
        }
        page.synopsis = cleanSynopsis(html);
        String noComment = html.replaceAll("<!--.*?-->", "");
        Matcher heading = Pattern.compile("Daftar Episode.*?\\(\\s*(\\d+)(?:\\s*dari\\s*(\\d+))?\\s*\\)",
                Pattern.DOTALL).matcher(noComment);
        if (heading.find()) {
            try {
                page.episodeTotalHeading = Integer.parseInt(heading.group(1));
            } catch (Exception ignored) {
                page.episodeTotalHeading = 0;
            }
        }
        Map<String, EpisodeEntry> byNumber = new LinkedHashMap<>();
        for (EpisodeEntry entry : parseFlightEpisodes(flat)) {
            byNumber.put(numberKey(entry.number), entry);
        }
        Map<String, String> visibleText = new LinkedHashMap<>();
        Map<String, EpisodeEntry> visibleOnly = new LinkedHashMap<>();
        for (VisibleRow row : parseVisibleRows(html, slug)) {
            visibleText.put(numberKey(row.number), row.dateText);
            if (!byNumber.containsKey(numberKey(row.number))) {
                EpisodeEntry entry = new EpisodeEntry(0, row.slug, row.title, row.number, "");
                visibleOnly.put(numberKey(row.number), entry);
            }
        }
        byNumber.putAll(visibleOnly);
        List<EpisodeEntry> episodes = new ArrayList<>(byNumber.values());
        episodes.sort((a, b) -> Double.compare(a.number, b.number));
        for (EpisodeEntry entry : episodes) {
            String siteText = visibleText.get(numberKey(entry.number));
            if (siteText != null && !siteText.isEmpty() && !siteText.equals("null")) {
                entry.relativeText = siteText;
            } else if (entry.createdAtIso != null && !entry.createdAtIso.isEmpty()) {
                entry.relativeText = relativeTextFromIso(entry.createdAtIso);
            } else {
                entry.relativeText = "";
            }
        }
        page.episodes = episodes;
        if (!episodes.isEmpty()) {
            EpisodeEntry latest = episodes.get(episodes.size() - 1);
            page.latestEpisodeNumber = latest.number;
            page.latestEpisodeLabel = latest.label;
        }
        if (page.episodeTotalHeading == 0) page.episodeTotalHeading = episodes.size();
        return page;
    }

    private static List<EpisodeEntry> parseFlightEpisodes(String flat) {
        List<EpisodeEntry> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int idAt = flat.indexOf("\"id\":", from);
            if (idAt < 0) break;
            int numStart = idAt + 5;
            int numEnd = numStart;
            while (numEnd < flat.length() && Character.isDigit(flat.charAt(numEnd))) numEnd++;
            if (numEnd == numStart) {
                from = idAt + 5;
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(flat.substring(numStart, numEnd));
            } catch (Exception ignored) {
                from = numEnd;
                continue;
            }
            if (!flat.startsWith(",\"slug\":\"", numEnd)) {
                from = numEnd;
                continue;
            }
            int slugStart = numEnd + 9;
            int slugEnd = flat.indexOf('"', slugStart);
            if (slugEnd < 0 || slugEnd - slugStart > 300) {
                from = numEnd;
                continue;
            }
            String slug = cleanSlug(flat.substring(slugStart, slugEnd));
            if (!flat.startsWith(",\"title\":\"", slugEnd + 1)) {
                from = numEnd;
                continue;
            }
            int titleStart = slugEnd + 1 + 10;
            int epNumAt = flat.indexOf("\",\"episodeNumber\":", titleStart);
            if (epNumAt < 0 || epNumAt - titleStart > 600) {
                from = numEnd;
                continue;
            }
            String title = flat.substring(titleStart, epNumAt);
            int epNumStart = epNumAt + 18;
            int epNumEnd = epNumStart;
            while (epNumEnd < flat.length()
                    && (Character.isDigit(flat.charAt(epNumEnd)) || flat.charAt(epNumEnd) == '.')) {
                epNumEnd++;
            }
            double number;
            try {
                number = Double.parseDouble(flat.substring(epNumStart, epNumEnd));
            } catch (Exception ignored) {
                from = epNumEnd;
                continue;
            }
            int createdAt = flat.indexOf(",\"createdAt\":\"$D", epNumEnd);
            String created = "";
            if (createdAt >= 0 && createdAt - epNumEnd < 200) {
                int createdStart = createdAt + 16;
                int createdEnd = flat.indexOf("\"", createdStart);
                if (createdEnd > createdStart) created = flat.substring(createdStart, createdEnd);
            }
            from = Math.max(epNumEnd, titleStart);
            if (number == 0 || slug.contains("batch-download")) continue;
            if (title.contains("Batch Download")) continue;
            out.add(new EpisodeEntry(id, slug, title, number, created));
        }
        return out;
    }

    private static final class VisibleRow {
        final String slug;
        final String title;
        final double number;
        final String dateText;

        VisibleRow(String slug, String title, double number, String dateText) {
            this.slug = slug;
            this.title = title;
            this.number = number;
            this.dateText = dateText;
        }
    }

    private static List<VisibleRow> parseVisibleRows(String html, String seriesSlug) {
        List<VisibleRow> out = new ArrayList<>();
        Matcher section = Pattern.compile("Daftar Episode.*?Cari episode", Pattern.DOTALL).matcher(html);
        String seg = section.find() ? html.substring(section.start()) : html;
        Matcher row = Pattern.compile(
                "<a[^>]*class=\"flex items-center justify-between[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>"
                        + "<span[^>]*>([^<]*)</span><span[^>]*>([^<]*)</span></a>").matcher(seg);
        while (row.find()) {
            String href = htmlUnescape(row.group(1)).trim();
            String title = stripTags(htmlUnescape(row.group(2)));
            String dateText = stripTags(htmlUnescape(row.group(3)));
            String path = href.replaceAll("^/+|/+$", "");
            String slug = path.startsWith("anime/") ? path.substring(6) : path;
            double number = -1;
            Matcher num = Pattern.compile("-episode-(\\d+(?:\\.\\d+)?)-subtitle-indonesia").matcher(href);
            if (num.find()) {
                try {
                    number = Double.parseDouble(num.group(1));
                } catch (Exception ignored) {
                    number = -1;
                }
            } else if (slug.equals(seriesSlug)) {
                number = 1;
            }
            if (number < 0 || slug.isEmpty()) continue;
            out.add(new VisibleRow(slug, title != null ? title : "", number,
                    dateText != null ? dateText : ""));
        }
        return out;
    }

    private static String episodeLabel(String title, double number) {
        String clean = title != null ? title.trim() : "";
        Matcher m = Pattern.compile("Episode\\s+(\\d+(?:\\.\\d+)?)\\s*(.*)$").matcher(clean);
        if (m.find()) {
            String rest = m.group(2) != null ? m.group(2).trim() : "";
            return "Episode " + m.group(1) + (rest.isEmpty() ? "" : " " + rest);
        }
        return "Episode " + formatNumber(number);
    }

    private static Integer episodeIdFromPlayerProps(String html) {
        String flat = unescapeRsc(html);
        int qAt = flat.indexOf("\"qualityOptions\"");
        if (qAt < 0) return null;
        int idAt = flat.lastIndexOf("\"episodeId\":", qAt);
        if (idAt < 0) return null;
        int start = idAt + 12;
        int end = start;
        while (end < flat.length() && Character.isDigit(flat.charAt(end))) end++;
        if (end == start) return null;
        try {
            return Integer.parseInt(flat.substring(start, end));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer episodeIdFromDetailMap(String html, String slug) {
        String flat = unescapeRsc(html);
        for (EpisodeEntry entry : parseFlightEpisodes(flat)) {
            if (entry.slug.equals(slug)) return entry.id;
        }
        return null;
    }

    static String cleanSlug(String raw) {
        if (raw == null) return "";
        String v = raw.trim().replace("\\\"", "").replace("\"", "").replace("'", "");
        v = v.replaceAll("^/+|/+$", "");
        if (v.startsWith("anime/")) v = v.substring(6);
        return v.trim();
    }

    private static String deriveSeriesSlug(String episodeSlug) {
        Matcher m = Pattern.compile("^(.*)-episode-\\d+(?:\\.\\d+)?-subtitle-indonesia$").matcher(episodeSlug);
        if (m.find()) return m.group(1) + "-subtitle-indonesia";
        return null;
    }

    private static PageResult parseListPage(String html, int page) {
        List<AnimePost> items = new ArrayList<>();
        Map<String, AnimePost> bySlug = new LinkedHashMap<>();
        Matcher anchor = Pattern.compile(
                "<a[^>]*class=\"group block\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL).matcher(html);
        while (anchor.find()) {
            String href = htmlUnescape(anchor.group(1));
            if (!href.contains("/anime/")) continue;
            String slug = href.replaceAll("^/+|/+$", "");
            if (slug.startsWith("anime/")) slug = slug.substring(6);
            if (slug.isEmpty() || bySlug.containsKey(slug)) continue;
            String body = anchor.group(2);
            String title = "";
            String cover = "";
            Matcher img = Pattern.compile("<img[^>]*alt=\"([^\"]*)\"[^>]*src=\"([^\"]*)\"").matcher(body);
            if (img.find()) {
                title = htmlUnescape(img.group(1));
                cover = normalizeCover(img.group(2));
            }
            if (title.isEmpty()) {
                Matcher h3 = Pattern.compile("<h3[^>]*>(.*?)</h3>", Pattern.DOTALL).matcher(body);
                if (h3.find()) title = stripTags(htmlUnescape(h3.group(1)));
            }
            title = title != null ? title.trim() : "";
            if (title.isEmpty() || title.equals("null")) continue;
            AnimePost post = new AnimePost(cover, title, positiveId(slug), -1);
            post.sourceId = AnimeSettingsManager.SOURCE_SOKUJA;
            post.slug = slug;
            Matcher score = Pattern.compile("★[^0-9]*([0-9]+(?:\\.[0-9]+)?)").matcher(body);
            if (score.find()) {
                try {
                    post.rating = stripTrailingZeros(Double.parseDouble(score.group(1)));
                } catch (Exception ignored) {
                }
            }
            Matcher status = Pattern.compile(
                    "<span[^>]*absolute bottom-0[^>]*>([^<]*)</span>").matcher(body);
            if (status.find()) post.statusVideo = stripTags(htmlUnescape(status.group(1)));
            Matcher year = Pattern.compile("<p[^>]*>(\\d{4})</p>").matcher(body);
            if (year.find()) {
                try {
                    post.year = Integer.parseInt(year.group(1));
                } catch (Exception ignored) {
                    post.year = 0;
                }
            }
            String statusLower = post.statusVideo != null ? post.statusVideo.toLowerCase(Locale.ROOT) : "";
            post.ongoing = !statusLower.contains("complete")
                    && !statusLower.contains("finished")
                    && !statusLower.equals("selesai");
            post.genre = "";
            post.totalEpisodes = 0;
            bySlug.put(slug, post);
            items.add(post);
        }
        boolean hasMore;
        String text = stripTags(htmlUnescape(html)).replaceAll("\\s+", " ");
        Matcher total = Pattern.compile("(\\d[\\d,.]*)\\s+anime ditemukan").matcher(text);
        if (total.find()) {
            try {
                int totalCount = Integer.parseInt(total.group(1).replace(".", "").replace(",", ""));
                hasMore = page < (totalCount + PAGE_SIZE - 1) / PAGE_SIZE;
            } catch (Exception ignored) {
                hasMore = items.size() >= PAGE_SIZE;
            }
        } else {
            int maxPage = 0;
            Matcher pageLink = Pattern.compile("[?&]page=(\\d+)").matcher(htmlUnescape(html));
            while (pageLink.find()) {
                try {
                    maxPage = Math.max(maxPage, Integer.parseInt(pageLink.group(1)));
                } catch (Exception ignored) {
                }
            }
            hasMore = maxPage > 0 ? page < maxPage : items.size() >= PAGE_SIZE;
        }
        return new PageResult(items, hasMore, items.size());
    }

    private static Map<String, String> loadGenreMap() throws IOException {
        synchronized (GENRE_SLUG_CACHE) {
            if (!GENRE_SLUG_CACHE.isEmpty()) return GENRE_SLUG_CACHE;
        }
        String html = fetchPage("/anime/", BASE + "/anime/");
        Matcher select = Pattern.compile("<select[^>]*>(.*?)</select>", Pattern.DOTALL).matcher(html);
        while (select.find()) {
            Map<String, String> found = new LinkedHashMap<>();
            Matcher option = Pattern.compile(
                    "<option[^>]*value=\"([^\"]*)\"[^>]*>([^<]*)</option>").matcher(select.group(1));
            while (option.find()) {
                String value = htmlUnescape(option.group(1)).trim();
                String name = htmlUnescape(option.group(2)).trim();
                if (!value.isEmpty() && !name.isEmpty()) found.put(name, value);
            }
            if (found.containsValue("comedy") && found.containsValue("adventure")) {
                synchronized (GENRE_SLUG_CACHE) {
                    GENRE_SLUG_CACHE.putAll(found);
                }
                return GENRE_SLUG_CACHE;
            }
        }
        throw new IOException("Sokuja: daftar genre tidak ditemukan di /anime/");
    }

    private static String genreSlug(String genreName) {
        String name = genreName != null ? genreName.trim() : "";
        if (name.isEmpty()) return "";
        try {
            Map<String, String> map = loadGenreMap();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
            }
        } catch (Exception ignored) {
        }
        return slugify(name);
    }

    private static String cleanSynopsis(String html) {
        Matcher block = Pattern.compile("Sinopsis [^<]*</h2>.*?<div[^>]*prose[^>]*>(.*?)</div>",
                Pattern.DOTALL).matcher(html);
        if (!block.find()) return "";
        List<String> kept = new ArrayList<>();
        Matcher para = Pattern.compile("<p>(.*?)</p>", Pattern.DOTALL).matcher(block.group(1));
        while (para.find()) {
            String raw = para.group(1);
            String txt = stripTags(htmlUnescape(raw));
            if (txt == null || txt.isEmpty()) continue;
            if (txt.startsWith("PENGUMUMAN")) continue;
            if (txt.startsWith("Silahkan klik tombol Trailer")) continue;
            if (txt.contains("Urutan Arc")) continue;
            if (txt.matches("(?s).*\\d+\\.\\s*Episode \\d+-\\d+\\s+Arc.*")) continue;
            if (raw.contains("<a") && txt.length() < 120
                    && !txt.endsWith(".") && !txt.endsWith("!") && !txt.endsWith("?")) continue;
            kept.add(txt);
        }
        return String.join("\n\n", kept);
    }

    private static String relativeTextFromIso(String iso) {
        long createdMs = parseIsoMs(iso);
        if (createdMs <= 0) return "";
        long diffMs = System.currentTimeMillis() - createdMs;
        if (diffMs < 0) diffMs = 0;
        long seconds = diffMs / 1000;
        if (seconds < 60) return "baru saja";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " menit lalu";
        long hours = minutes / 60;
        if (hours < 24) return hours + " jam lalu";
        long days = hours / 24;
        if (days < 7) return days + " hari lalu";
        if (days < 30) return Math.max(1, days / 7) + " minggu lalu";
        if (days < 365) return Math.max(1, days / 30) + " bulan lalu";
        return Math.max(1, days / 365) + " tahun lalu";
    }

    private static long parseIsoMs(String iso) {
        try {
            Matcher m = Pattern.compile(
                    "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})").matcher(iso);
            if (!m.find()) return 0;
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.set(Calendar.YEAR, Integer.parseInt(m.group(1)));
            cal.set(Calendar.MONTH, Integer.parseInt(m.group(2)) - 1);
            cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(3)));
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(4)));
            cal.set(Calendar.MINUTE, Integer.parseInt(m.group(5)));
            cal.set(Calendar.SECOND, Integer.parseInt(m.group(6)));
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String numberKey(double number) {
        if (number == Math.floor(number) && !Double.isInfinite(number)) {
            return String.valueOf((long) number);
        }
        return String.valueOf(number);
    }

    private static String formatNumber(double number) {
        if (number == Math.floor(number) && !Double.isInfinite(number)) {
            return String.valueOf((long) number);
        }
        return String.valueOf(number);
    }

    private static String normalizeCover(String src) {
        if (src == null) return "";
        String value = htmlUnescape(src).trim();
        if (value.isEmpty() || value.equals("null")) return "";
        if (value.contains("/_next/image/")) {
            int qAt = value.indexOf('?');
            if (qAt >= 0) {
                String query = value.substring(qAt + 1);
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && pair.substring(0, eq).equals("url")) {
                        try {
                            String inner = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
                            if (inner.startsWith("http")) return inner;
                            if (inner.startsWith("/")) return BASE + inner;
                            return inner;
                        } catch (Exception ignored) {
                            return value;
                        }
                    }
                }
            }
        }
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("/")) return BASE + value;
        return value;
    }

    private static String unescapeRsc(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(i + 1);
            switch (next) {
                case '"': out.append('"'); i++; break;
                case '\\': out.append('\\'); i++; break;
                case '/': out.append('/'); i++; break;
                case 'n': out.append('\n'); i++; break;
                case 't': out.append('\t'); i++; break;
                case 'r': out.append('\r'); i++; break;
                case 'b': out.append('\b'); i++; break;
                case 'f': out.append('\f'); i++; break;
                case 'u':
                    if (i + 5 < text.length()) {
                        try {
                            out.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                            i += 5;
                        } catch (Exception ignored) {
                            out.append(next);
                            i++;
                        }
                    } else {
                        out.append(next);
                        i++;
                    }
                    break;
                default: out.append(next); i++; break;
            }
        }
        return out.toString();
    }

    private static String htmlUnescape(String value) {
        if (value == null) return "";
        String out = value.replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        Matcher numeric = Pattern.compile("&#(\\d+);|&#x([0-9a-fA-F]+);").matcher(out);
        StringBuffer buffer = new StringBuffer();
        while (numeric.find()) {
            try {
                int code = numeric.group(1) != null
                        ? Integer.parseInt(numeric.group(1))
                        : Integer.parseInt(numeric.group(2), 16);
                numeric.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf((char) code)));
            } catch (Exception ignored) {
                numeric.appendReplacement(buffer, "");
            }
        }
        numeric.appendTail(buffer);
        out = buffer.toString().replace("&amp;", "&");
        return out;
    }

    private static String stripTags(String value) {
        if (value == null) return "";
        String noComment = value.replaceAll("<!--.*?-->", "");
        String spaced = noComment.replaceAll(">\\s*<", "> <");
        String stripped = spaced.replaceAll("<[^>]+>", "");
        String cleaned = htmlUnescape(stripped).replaceAll("\\s+", " ").trim();
        return cleaned.equals("null") ? "" : cleaned;
    }

    private static String firstUseful(String first, String second) {
        String a = first != null ? first.trim() : "";
        if (!a.isEmpty() && !a.equals("null")) return a;
        String b = second != null ? second.trim() : "";
        return b.equals("null") ? "" : b;
    }

    private static void putRow(Map<String, String> rows, String key, String value) {
        String v = value != null ? value.trim() : "";
        if (!v.isEmpty() && !v.equals("null")) rows.put(key, v);
    }

    private static String safeOrder(String order) {
        if (order == null) return "update";
        switch (order) {
            case "update":
            case "popular":
            case "newest":
            case "oldest":
            case "title_az":
            case "title_za":
            case "score":
                return order;
            default:
                return "update";
        }
    }

    private static int qualityRank(String quality) {
        switch (quality) {
            case "1080p": return 4;
            case "720p": return 3;
            case "480p": return 2;
            case "360p": return 1;
            default: return 0;
        }
    }

    private static String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT).trim()
                .replace('_', '-').replace(' ', '-');
        slug = slug.replaceAll("[^a-z0-9-]", "");
        slug = slug.replaceAll("-+", "-");
        return slug;
    }

    private static String stripTrailingZeros(double value) {
        String text = Double.toString(value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    private static int positiveId(String value) {
        String raw = value != null ? value.trim() : "";
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
            if (parsed > 0) return parsed;
        } catch (Exception ignored) {
        }
        int hash = raw.hashCode();
        return hash == Integer.MIN_VALUE ? 1 : Math.abs(hash);
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8.name());
    }

    private static String fetchJsonWithRetry(String url, String referer) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return fetchJson(url, referer);
            } catch (SokujaCloudflareException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(1000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last;
    }

    private static String fetchPage(String path, String referer) throws IOException {
        String url = path.startsWith("http") ? path : BASE + path;
        Request request = new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Referer", referer)
                .build();
        return executeWithCloudflare(request, url);
    }

    private static String executeWithCloudflare(Request request, String url) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean challenge;
            int code;
            try (okhttp3.Response response = CLIENT.newCall(request).execute()) {
                code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) return body;
                challenge = CloudflareHelper.isChallengeResponse(response, body);
            }
            if (!challenge) throw new IOException("Sokuja HTTP " + code + " untuk " + url);
            boolean solved = attempt == 0 && CloudflareHelper.resolveBlocking(BASE + "/", SOURCE_LABEL, CLOUDFLARE_WAIT_MS);
            if (!solved) throw new SokujaCloudflareException("\uD83C\uDD98 Harap selesaikan Cloudflare pada Source Anime Sokuja \uD83C\uDD98");
        }
        throw new SokujaCloudflareException("\uD83C\uDD98 Harap selesaikan Cloudflare pada Source Anime Sokuja \uD83C\uDD98");
    }

    private static String fetchJson(String url, String referer) throws IOException {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Referer", referer)
                .build();
        return executeWithCloudflare(request, url);
    }
}
