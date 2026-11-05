package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AnimeLoverzApi {
    private static final String TAG = "AnimeLoverzApi";
    public static final String BASE = "https://api.animeverse.id/api/v1";
    public static final int PAGE_SIZE = 20;

    private static final String HANDSHAKE_PASSPHRASE = "TIR9cVnmr1rxOqtGIztdGy2UX704PbZ2EHoJAhyeojolU3IZxZ4YEaPQcawSXvLq";
    private static final String USER_AGENT = "Dart/3.11 (dart:io)";
    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");
    private static final String PREF = "animeloverz_v3_session";
    private static final long EXPIRY_MARGIN_MS = 5 * 60 * 1000L;
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build();
    private static final Object LOCK = new Object();
    private static Context appContext;
    private static Session session;
    private static String base = BASE;

    private AnimeLoverzApi() { }

    public static final class ApiException extends IOException {
        public final int code;
        public ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
        public boolean isNotFound() { return code == 404; }
    }

    public static final class Page {
        public final ArrayList<AnimePost> posts = new ArrayList<>();
        public boolean hasNext;
        public int page = 1;
        public int totalPages = 1;
        public int totalItems;
    }

    public static final class Episode {
        public int id;
        public String number = "";
        public int index;
        public String url = "";
        public String thumbnail = "";
        public String title = "";

        public String label() {
            if (!isUseful(number)) return index > 0 ? "Episode " + index : "Episode";
            String n = number.trim();
            return n.toLowerCase(Locale.ROOT).contains("episode") || n.toLowerCase(Locale.ROOT).contains("movie") ? n : "Episode " + n;
        }
    }

    public static final class Detail {
        public AnimePost post;
        public final ArrayList<Episode> episodes = new ArrayList<>();
        public String type = "";
        public String released = "";
        public String studio = "";
    }

    public static final class StreamGroup {
        public String resolution = "";
        public final ArrayList<String> urls = new ArrayList<>();
        public String primary() { return urls.isEmpty() ? "" : urls.get(0); }
    }

    private static final class Session {
        String id;
        String passphrase;
        long expiresAt;
    }

    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (appContext == null) appContext = context.getApplicationContext();
            if (session == null) session = loadSession();
        }
    }

    static void setBaseUrlForTest(String url) {
        synchronized (LOCK) {
            base = url == null || url.isEmpty() ? BASE : url;
            session = null;
        }
    }

    public static void invalidateSession() {
        synchronized (LOCK) {
            session = null;
            if (appContext != null) appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    public static Page ongoing(int page, int limit, String filter) throws Exception {
        String f = isUseful(filter) ? filter : "all";
        JSONObject json = getJson(path("/series/ongoing", "filter", f, "page", String.valueOf(Math.max(1, page)), "limit", String.valueOf(limit)));
        return parsePage(json, page, limit, true);
    }

    public static Page movie(int page, int limit) throws Exception {
        JSONObject json = getJson(path("/series/movie", "page", String.valueOf(Math.max(1, page)), "limit", String.valueOf(limit)));
        return parsePage(json, page, limit, true);
    }

    public static Page series(int page, int limit, String search, String genre, String type, String status, String sortBy, String sortOrder) throws Exception {
        JSONObject json = getJson(path("/series",
                "page", String.valueOf(Math.max(1, page)),
                "limit", String.valueOf(limit),
                "search", search,
                "genre", genre,
                "type", type,
                "status", status,
                "sort_by", sortBy,
                "sort_order", sortOrder));
        return parsePage(json, page, limit, true);
    }

    public static Page trending(int limit) throws Exception {
        return parsePage(getJson(path("/series/trending", "limit", String.valueOf(limit))), 1, limit, false);
    }

    public static Page recommendation(int limit) throws Exception {
        try {
            return parsePage(getJson(path("/series/recommendation", "limit", String.valueOf(limit))), 1, limit, false);
        } catch (ApiException e) {
            if (!e.isNotFound()) throw e;
            Page top = series(1, limit, null, null, null, null, "score", "desc");
            top.hasNext = false;
            return top;
        }
    }

    public static Detail detail(String slug) throws Exception {
        String clean = normalizeSlug(slug);
        if (clean.isEmpty()) throw new ApiException(400, "Slug kosong");
        JSONObject data = getJson("/series/" + encode(clean)).optJSONObject("data");
        if (data == null) throw new ApiException(404, "Series tidak ditemukan");
        AnimePost post = parsePost(data);
        if (post == null) throw new ApiException(404, "Series tidak valid");
        Detail detail = new Detail();
        detail.post = post;
        detail.type = clean(data.optString("type", ""));
        detail.released = clean(data.optString("rilis", ""));
        detail.studio = clean(data.optString("studio", ""));
        JSONArray eps = data.optJSONArray("episodes");
        if (eps != null) {
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                int id = e.optInt("id", 0);
                if (id <= 0) continue;
                Episode ep = new Episode();
                ep.id = id;
                ep.number = clean(e.optString("episode", ""));
                ep.index = e.optInt("indexEps", i + 1);
                ep.url = clean(e.optString("url", ""));
                ep.thumbnail = clean(e.optString("thumbnail", ""));
                ep.title = clean(e.optString("title", ""));
                detail.episodes.add(ep);
            }
            Collections.sort(detail.episodes, new Comparator<Episode>() {
                @Override public int compare(Episode a, Episode b) { return Integer.compare(a.index, b.index); }
            });
        }
        if (detail.post.totalEpisodes <= 0) detail.post.totalEpisodes = detail.episodes.size();
        if (!detail.episodes.isEmpty() && !isUseful(detail.post.episodeCount)) detail.post.episodeCount = String.valueOf(detail.episodes.size());
        return detail;
    }

    public static Detail detailOrByTitle(String slug, String title) throws Exception {
        try {
            return detail(slug);
        } catch (ApiException e) {
            if (!e.isNotFound() || !isUseful(title)) throw e;
        }
        String wanted = titleKey(title);
        if (wanted.isEmpty()) throw new ApiException(404, "Anime tidak ditemukan");
        Page found = series(1, 10, title.trim(), null, null, null, null, null);
        for (AnimePost candidate : found.posts) {
            if (wanted.equals(titleKey(candidate.categoryName))) return detail(candidate.slug);
        }
        throw new ApiException(404, "Anime tidak ditemukan: " + title.trim());
    }

    static String titleKey(String title) {
        if (title == null) return "";
        String t = title.toLowerCase(Locale.ROOT).replace("sub indo", "").replace("subtitle indonesia", "");
        return t.replaceAll("[^a-z0-9]+", "");
    }

    public static ArrayList<StreamGroup> episodeStreams(int episodeId) throws Exception {
        if (episodeId <= 0) throw new ApiException(400, "ID episode tidak valid");
        JSONObject data = getJson("/episodes/" + episodeId + "?prefetch=1").optJSONObject("data");
        ArrayList<StreamGroup> result = new ArrayList<>();
        JSONArray groups = data == null ? null : data.optJSONArray("stream");
        if (groups == null) return result;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            StreamGroup group = new StreamGroup();
            group.resolution = clean(g.optString("reso", ""));
            JSONArray videos = g.optJSONArray("video_data");
            if (videos != null) {
                for (int v = 0; v < videos.length(); v++) {
                    JSONObject video = videos.optJSONObject(v);
                    if (video == null) continue;
                    String url = firstUseful(video.optString("data", ""), video.optString("url", ""), video.optString("link", ""));
                    if (isPlayable(url) && !group.urls.contains(url)) group.urls.add(url);
                }
            }
            if (!group.urls.isEmpty()) result.add(group);
        }
        return result;
    }

    public static String pickStream(ArrayList<StreamGroup> groups, String... preferredResolutions) {
        if (groups == null) return "";
        for (String wanted : preferredResolutions) {
            for (StreamGroup g : groups) if (wanted.equalsIgnoreCase(g.resolution)) return g.primary();
        }
        return "";
    }

    public static AnimePost parsePost(JSONObject item) {
        if (item == null) return null;
        String title = cleanTitle(firstUseful(item.optString("judul", ""), item.optString("title", ""), item.optString("name", "")));
        String slug = normalizeSlug(firstUseful(item.optString("url", ""), item.optString("slug", "")));
        if (title.isEmpty() || slug.isEmpty()) return null;
        AnimePost post = new AnimePost(firstUseful(item.optString("cover", ""), item.optString("image", "")), title, parseId(item.opt("id"), slug), -1);
        post.sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ;
        post.slug = slug;
        post.channelName = firstUseful(item.optString("last_ch", ""), item.optString("lastch", ""));
        post.created = firstUseful(item.optString("last_up", ""), item.optString("updated", ""));
        int total = item.optInt("total_episode", 0);
        if (total > 0) {
            post.totalEpisodes = total;
            post.episodeCount = String.valueOf(total);
        }
        post.genre = joinArray(item.optJSONArray("genre"));
        post.rating = firstUseful(item.optString("score", ""), item.optString("rating", ""));
        post.statusVideo = clean(item.optString("status", ""));
        post.description = firstUseful(item.optString("sinopsis", ""), item.optString("description", ""));
        post.ongoing = !post.statusVideo.toLowerCase(Locale.ROOT).contains("complete");
        Matcher m = YEAR.matcher(clean(item.optString("rilis", "")));
        if (m.find()) post.year = Integer.parseInt(m.group());
        return post;
    }

    public static String normalizeSlug(String value) {
        if (value == null) return "";
        String s = value.trim();
        try { s = URLDecoder.decode(s, "UTF-8"); } catch (Exception ignored) { }
        s = s.trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int cut = s.lastIndexOf('/');
        if (cut >= 0) s = s.substring(cut + 1);
        return s.trim();
    }

    public static boolean sameSlug(String a, String b) {
        String x = normalizeSlug(a), y = normalizeSlug(b);
        return !x.isEmpty() && x.equalsIgnoreCase(y);
    }

    public static boolean isPlayable(String url) {
        if (url == null) return false;
        String v = url.trim();
        return !v.isEmpty() && !"null".equalsIgnoreCase(v) && (v.startsWith("http://") || v.startsWith("https://"));
    }

    public static JSONObject getJson(String pathAndQuery) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Session s = ensureSession(attempt > 0);
            Request request = new Request.Builder()
                    .url(base + pathAndQuery)
                    .header("Accept", "text/plain")
                    .header("X-Enc-Ver", "3")
                    .header("X-Enc-Session", s.id)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();
            int code;
            String body;
            try (Response response = CLIENT.newCall(request).execute()) {
                code = response.code();
                body = response.body() == null ? "" : response.body().string();
            }
            String text = decodeBody(body, s.passphrase);
            if (text != null) {
                JSONObject json = new JSONObject(text);
                if (code >= 400 || !json.optBoolean("success", true)) {
                    ApiException error = new ApiException(code >= 400 ? code : 422, json.optString("message", "Request gagal"));
                    if (code == 401 || code == 403) {
                        last = error;
                        continue;
                    }
                    throw error;
                }
                return json;
            }
            last = new ApiException(code, "Respons tidak bisa dibaca (HTTP " + code + ")");
            Log.w(TAG, "decode gagal attempt=" + attempt + " http=" + code + " path=" + pathAndQuery);
        }
        throw last == null ? new ApiException(0, "Request gagal") : last;
    }

    private static String decodeBody(String body, String passphrase) {
        if (body == null) return null;
        String t = body.trim();
        if (t.isEmpty()) return null;
        if (AnimeLoverzCrypto.looksSalted(t)) {
            try {
                String plain = AnimeLoverzCrypto.decryptSalted(t, passphrase).trim();
                return plain.startsWith("{") ? plain : null;
            } catch (Exception e) {
                return null;
            }
        }
        return t.startsWith("{") ? t : null;
    }

    private static Session ensureSession(boolean force) throws Exception {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (!force && session != null && session.expiresAt > now) return session;
            Session fresh = handshake();
            session = fresh;
            saveSession(fresh);
            return fresh;
        }
    }

    private static Session handshake() throws Exception {
        byte[] priv = AnimeLoverzCrypto.randomPrivateKey();
        byte[] pub = AnimeLoverzCrypto.publicKey(priv);
        String payload = "{\"client_public_key\":\"" + AnimeLoverzCrypto.base64Encode(pub) + "\"}";
        String encrypted = AnimeLoverzCrypto.encryptSalted(payload, HANDSHAKE_PASSPHRASE);
        Request request = new Request.Builder()
                .url(base + "/auth/handshake")
                .header("Accept", "text/plain")
                .header("User-Agent", USER_AGENT)
                .post(RequestBody.create(encrypted, TEXT))
                .build();
        int code;
        String body;
        try (Response response = CLIENT.newCall(request).execute()) {
            code = response.code();
            body = response.body() == null ? "" : response.body().string();
        }
        if (!AnimeLoverzCrypto.looksSalted(body)) {
            throw new ApiException(code, "Handshake ditolak (HTTP " + code + "), kemungkinan diblokir Cloudflare");
        }
        JSONObject data = new JSONObject(AnimeLoverzCrypto.decryptSalted(body, HANDSHAKE_PASSPHRASE)).optJSONObject("data");
        if (data == null) throw new ApiException(code, "Handshake tidak valid");
        String sessionId = data.optString("session_id", "");
        byte[] serverPub = AnimeLoverzCrypto.base64Decode(data.optString("server_public_key", ""));
        if (sessionId.isEmpty() || serverPub.length != 32) throw new ApiException(code, "Handshake: kunci server tidak valid");
        byte[] shared = AnimeLoverzCrypto.x25519(priv, serverPub);
        Session s = new Session();
        s.id = sessionId;
        s.passphrase = AnimeLoverzCrypto.derivePassphrase(shared, pub, serverPub);
        long expiresInSeconds = data.optLong("expires_in", 3600);
        if (expiresInSeconds <= 0) expiresInSeconds = 3600;
        s.expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L - EXPIRY_MARGIN_MS;
        return s;
    }

    private static Session loadSession() {
        if (appContext == null) return null;
        SharedPreferences prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String id = prefs.getString("id", "");
        String pass = prefs.getString("pass", "");
        long exp = prefs.getLong("exp", 0L);
        if (id == null || id.isEmpty() || pass == null || pass.isEmpty() || exp <= System.currentTimeMillis()) return null;
        Session s = new Session();
        s.id = id;
        s.passphrase = pass;
        s.expiresAt = exp;
        return s;
    }

    private static void saveSession(Session s) {
        if (appContext == null || s == null) return;
        appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("id", s.id).putString("pass", s.passphrase).putLong("exp", s.expiresAt).apply();
    }

    private static Page parsePage(JSONObject json, int requestedPage, int limit, boolean paged) {
        Page result = new Page();
        JSONArray data = json == null ? null : json.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                AnimePost post = parsePost(data.optJSONObject(i));
                if (post != null) result.posts.add(post);
            }
        }
        JSONObject meta = json == null ? null : json.optJSONObject("meta");
        if (meta != null) {
            result.page = meta.optInt("page", requestedPage);
            result.totalPages = Math.max(1, meta.optInt("total_pages", 1));
            result.totalItems = meta.optInt("total_items", result.posts.size());
            result.hasNext = paged && result.page < result.totalPages;
        } else {
            result.page = requestedPage;
            result.hasNext = paged && data != null && limit > 0 && data.length() >= limit;
        }
        return result;
    }

    private static String path(String base, String... keyValues) {
        StringBuilder sb = new StringBuilder(base);
        char sep = '?';
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String value = keyValues[i + 1];
            if (!isUseful(value)) continue;
            sb.append(sep).append(keyValues[i]).append('=').append(encode(value.trim()));
            sep = '&';
        }
        return sb.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static int parseId(Object raw, String slug) {
        if (raw instanceof Number) {
            int v = ((Number) raw).intValue();
            if (v > 0) return v;
        } else if (raw != null) {
            try {
                int v = Integer.parseInt(String.valueOf(raw).trim());
                if (v > 0) return v;
            } catch (Exception ignored) { }
        }
        int h = slug == null ? 1 : slug.hashCode();
        return h == Integer.MIN_VALUE ? 1 : Math.abs(h);
    }

    private static String joinArray(JSONArray array) {
        if (array == null) return "";
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String v = clean(array.optString(i, ""));
            if (!v.isEmpty() && !values.contains(v)) values.add(v);
        }
        return android.text.TextUtils.join(", ", values);
    }

    private static String cleanTitle(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ")
                .replaceAll("(?i)\\bsub\\s*indo\\b", "")
                .replaceAll("(?i)\\bsubtitle\\s*indonesia\\b", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static String clean(String value) {
        return isUseful(value) ? value.trim() : "";
    }

    private static boolean isUseful(String value) {
        if (value == null) return false;
        String v = value.trim();
        return !v.isEmpty() && !"null".equalsIgnoreCase(v) && !"n/a".equalsIgnoreCase(v);
    }

    private static String firstUseful(String... values) {
        for (String v : values) if (isUseful(v)) return v.trim();
        return "";
    }
}
