package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;

public class Westmanga extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://v1.westmanga.my";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_WESTMANGA); }
    // API sekarang dipanggil SAME-ORIGIN lewat domain frontend (v1.westmanga.my).
    // Host lama data.mantweh.online sudah TIDAK bisa dipakai sama sekali: zona
    // mantweh.online menolak SEMUA akses langsung dengan halaman block permanen
    // Cloudflare ("Sorry, you have been blocked", terverifikasi live 2026-10-23
    // dari curl bertanda tangan maupun browser nyata — tidak ada tantangan/
    // clearance yang ditawarkan di sana). Sebaliknya jalur /api/* di
    // v1.westmanga.my membalas fetch bertanda tangan dengan tantangan
    // "Just a moment" (Cf-Mitigated: challenge) yang BISA diselesaikan di
    // WebView, dan cf_clearance untuk host frontend berlaku juga untuk /api/*.
    private static final String FALLBACK_API = "https://data.mantweh.online";
    private static String api() {
        String b = base();
        if (b == null || b.trim().isEmpty()) b = DEFAULT_BASE;
        return b.replaceAll("/+$", "");
    }
    private static String apiHost() {
        HttpUrl u = HttpUrl.parse(api());
        return u == null ? "v1.westmanga.my" : u.host();
    }
    // Cover/gambar dari API kadang masih ber-host mantweh.online yang kini
    // hard-block untuk semua request gambar; host frontend menyajikan aset
    // yang sama di path yang sama (/img/...) dan hanya dilindungi tantangan
    // biasa, jadi arahkan semua URL aset ke host frontend.
    private static String fixAssetUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (u.startsWith("//")) u = "https:" + u;
        String host = apiHost();
        u = u.replace("://data.mantweh.online", "://" + host).replace("://mantweh.online", "://" + host);
        if (u.startsWith("/")) u = api() + u;
        return u;
    }
    // Konstanta HMAC lama dihapus 2026-10-25 (lihat apiRequest): web tidak
    // memakainya lagi dan origin menolak request yang masih membawanya.

    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().addInterceptor(new VisitTokenInterceptor()).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();

    // Visit-token situs (2026-10-24, dari bundle web components-CJfqb9NT.js):
    // frontend web TIDAK LAGI memakai skema HMAC lama (x-wm-request-signature /
    // x-wm-accses-key / x-wm-request-time) — di traffic browser jumlahnya NOL.
    // Sebagai gantinya tiap request API dipasangi header "X-WM-Visit" berisi
    // token yang diambil dari GET {base}/api/visit-token (JSON: token +
    // expires_at). Token disimpan di memori saja, dipakai ulang selama masih
    // berlaku > 300 detik, dan di-refresh bila origin menolaknya (respons 403
    // + header "X-WM-Visit-Error"). App selama ini tidak mengirim header ini
    // sama sekali — itulah tersangka utama 403 JSON dari origin, karena
    // request yang lolos Cloudflare tapi tanpa token valid ditolak origin.
    // Header HMAC lama tetap dikirim (tidak dihapus) supaya perubahan ini
    // aditif; yang ditambahkan hanyalah header visit-token.
    private static final String VISIT_TOKEN_HEADER = "X-WM-Visit";
    private static final String VISIT_TOKEN_ERROR_HEADER = "X-WM-Visit-Error";
    private static volatile String visitToken = null;
    private static volatile long visitTokenExpiresAtSec = 0;
    private static final Object VISIT_TOKEN_LOCK = new Object();
    // Klien khusus ambil token: cookie jar bersama (cf_clearance ikut
    // terkirim) tapi TANPA VisitTokenInterceptor supaya tidak rekursi.
    private static final OkHttpClient VISIT_TOKEN_CLIENT = MangaHttpClient.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();

    // Interceptor: pasang X-WM-Visit di tiap request API Westmanga, PADA SAAT
    // KIRIM (bukan saat request dibangun). Ini penting karena request yang
    // di-retry CloudflareHelper setelah WebView solve adalah objek request
    // lama — header yang ditempel di sini selalu token terbaru dari cache.
    private static final class VisitTokenInterceptor implements Interceptor {
        @Override public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            String path = request.url().encodedPath();
            if (request.url().host().equals(apiHost()) && path.startsWith("/api/") && !path.endsWith("/api/visit-token")) {
                String token = ensureVisitToken();
                if (token != null && !token.isEmpty()) {
                    request = request.newBuilder().header(VISIT_TOKEN_HEADER, token).build();
                }
            }
            return chain.proceed(request);
        }
    }

    // Cermin perilaku web: pakai ulang token selama expires_at - sekarang >
    // 300 detik; bila tidak, fetch ulang sekali (single-flight via lock).
    // Bila fetch gagal, kembalikan token lama (bisa null) — request tetap
    // jalan tanpa header, dan penolakan origin ditangani jalur 403 di bawah.
    private static String ensureVisitToken() {
        long nowSec = System.currentTimeMillis() / 1000L;
        String cached = visitToken;
        if (cached != null && !cached.isEmpty() && visitTokenExpiresAtSec - nowSec > 300) return cached;
        // Jangan lakukan network di main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) return cached;
        synchronized (VISIT_TOKEN_LOCK) {
            nowSec = System.currentTimeMillis() / 1000L;
            cached = visitToken;
            if (cached != null && !cached.isEmpty() && visitTokenExpiresAtSec - nowSec > 300) return cached;
            try {
                HttpUrl tokenUrl = HttpUrl.parse(api() + "/api/visit-token");
                if (tokenUrl == null) return cached;
                Request req = new Request.Builder().url(tokenUrl)
                        .header("Accept", "application/json")
                        // Cermin fetch web: cache no-store (jangan sampai token
                        // basi dari cache disk OkHttp dipakai ulang) + Referer
                        // same-origin seperti fetch browser.
                        .header("Cache-Control", "no-store")
                        .header("Referer", base() + "/")
                        .header("User-Agent", CloudflareHelper.userAgentForHost(tokenUrl.host()))
                        .build();
                Response resp = VISIT_TOKEN_CLIENT.newCall(req).execute();
                try {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                        if (root.has("token") && root.get("token").isJsonPrimitive()) {
                            String t = root.get("token").getAsString();
                            long exp = 0;
                            if (root.has("expires_at") && root.get("expires_at").isJsonPrimitive()) {
                                try { exp = root.get("expires_at").getAsLong(); } catch (Exception ignored) {}
                            }
                            if (t != null && !t.isEmpty()) {
                                visitToken = t;
                                visitTokenExpiresAtSec = exp;
                                return t;
                            }
                        }
                    }
                } finally {
                    resp.close();
                }
            } catch (Exception ignored) {
            }
            return cached;
        }
    }

    private static void clearVisitToken() {
        synchronized (VISIT_TOKEN_LOCK) {
            visitToken = null;
            visitTokenExpiresAtSec = 0;
        }
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final ArrayList<GenreItem> GENRE_CACHE = new ArrayList<>();

    public static void clearSessionCaches() {
        KomikcastClient.clearSessionCaches();
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); }
    }
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    @Override protected String sourceLabel() { return "Westmanga"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            HttpUrl.Builder b = HttpUrl.parse(api() + "/api/contents").newBuilder();
            if (query != null && !query.trim().isEmpty()) b.addQueryParameter("q", query.trim());
            b.addQueryParameter("page", String.valueOf(Math.max(1, page)));
            b.addQueryParameter("per_page", "20");
            b.addQueryParameter("type", "Comic");
            String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
            boolean hasQuery = query != null && !query.trim().isEmpty();
            if (hasQuery) {
                b.addQueryParameter("orderBy", "Default");
            } else if ("popular".equals(s) || "popularity".equals(s)) b.addQueryParameter("orderBy", "Popular");
            else if ("latest".equals(s) || "update".equals(s)) b.addQueryParameter("orderBy", "Update");
            else if ("az".equals(s) || "a-z".equals(s)) b.addQueryParameter("orderBy", "Az");
            else if ("za".equals(s) || "z-a".equals(s)) b.addQueryParameter("orderBy", "Za");
            else if ("added".equals(s) || "new".equals(s)) b.addQueryParameter("orderBy", "Added");
            String genreId = normalizeGenreValue(genre);
            if (!genreId.isEmpty()) b.addQueryParameter("genre[]", genreId);
            getJson(b.build(), new Result<JsonObject>() {
                @Override public void onSuccess(JsonObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseBrowse(root);
                            JsonObject paginator = getObject(root, "paginator");
                            int current = getInt(paginator, "current_page", page);
                            int last = getInt(paginator, "last_page", page);
                            final boolean next = current < last;
                            final ArrayList<MangaPost> result = out;
                            MangaCoroutines.main(() -> cb.onSuccess(result, next));
                        } catch(Exception e) { miku.moe.app.crash.CrashReporter.logParse("Westmanga", "Daftar Westmanga gagal dibaca", e); MangaCoroutines.main(() -> cb.onError("Daftar Westmanga gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) {
                    if (hasQuery && message != null && message.contains("HTTP 500")) listByApiFallback(page, sort, query, genre, cb);
                    else cb.onError(message);
                }
            });
        } catch(Exception e) { miku.moe.app.crash.CrashReporter.logParse("Westmanga", "request", e); cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    private void listByApiFallback(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            HttpUrl.Builder b = HttpUrl.parse(api() + "/api/contents").newBuilder();
            b.addQueryParameter("page", String.valueOf(Math.max(1, page)));
            b.addQueryParameter("per_page", "40");
            b.addQueryParameter("type", "Comic");
            b.addQueryParameter("orderBy", "Default");
            String genreId = normalizeGenreValue(genre);
            if (!genreId.isEmpty()) b.addQueryParameter("genre[]", genreId);
            final String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            getJson(b.build(), new Result<JsonObject>() {
                @Override public void onSuccess(JsonObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> parsed = parseBrowse(root);
                            ArrayList<MangaPost> filtered = new ArrayList<>();
                            for (MangaPost post : parsed) {
                                String title = post == null || post.title == null ? "" : post.title.toLowerCase(Locale.ROOT);
                                if (needle.isEmpty() || title.contains(needle)) filtered.add(post);
                            }
                            JsonObject paginator = getObject(root, "paginator");
                            int current = getInt(paginator, "current_page", page);
                            int last = getInt(paginator, "last_page", page);
                            final boolean next = current < last;
                            final ArrayList<MangaPost> result = filtered;
                            MangaCoroutines.main(() -> cb.onSuccess(result, next));
                        } catch(Exception e) { miku.moe.app.crash.CrashReporter.logParse("Westmanga", "Pencarian Westmanga gagal dibaca", e); MangaCoroutines.main(() -> cb.onError("Pencarian Westmanga gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { miku.moe.app.crash.CrashReporter.logParse("Westmanga", "request", e); cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        synchronized (GENRE_CACHE) {
            if (!GENRE_CACHE.isEmpty()) {
                cb.onSuccess(new ArrayList<>(GENRE_CACHE), false);
                return;
            }
        }
        HttpUrl url = HttpUrl.parse(api() + "/api/contents/genres");
        if (url == null) { finishGenres(fallbackGenres(), cb); return; }
        getJson(url, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    ArrayList<GenreItem> out = new ArrayList<>();
                    LinkedHashSet<String> seen = new LinkedHashSet<>();
                    JsonArray data = getArray(root, "data");
                    for (JsonElement el : data) {
                        if (el == null || !el.isJsonObject()) continue;
                        JsonObject item = el.getAsJsonObject();
                        String name = getString(item, "name");
                        String id = firstNonEmpty(getString(item, "id"), getString(item, "slug"));
                        if (!name.isEmpty() && !id.isEmpty() && seen.add(id)) out.add(new GenreItem(name, id));
                    }
                    if (out.isEmpty()) out = fallbackGenres();
                    final ArrayList<GenreItem> result = out;
                    MangaCoroutines.main(() -> finishGenres(result, cb));
                });
            }
            @Override public void onError(String message) { finishGenres(fallbackGenres(), cb); }
        });
    }

    private static void finishGenres(ArrayList<GenreItem> values, Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> out = values == null ? new ArrayList<>() : values;
        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(out); }
        cb.onSuccess(new ArrayList<>(out), false);
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        final boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        final boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (p != null && (loadType || (p.latestChapter == null || p.latestChapter.trim().isEmpty())) && p.slug != null && !p.slug.isEmpty()) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || p.slug == null || p.slug.isEmpty()) continue;
            if (!loadType && p.latestChapter != null && !p.latestChapter.trim().isEmpty()) continue;
            if (!loadType) {
                chapters(p.slug, new Result<ArrayList<MangaChapter>>() {
                    @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                        if (chapters != null && !chapters.isEmpty()) {
                            MangaChapter newest = chapters.get(0);
                            for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                            p.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
                            p.latestChapterDate = newest.date == null ? "" : newest.date;
                        }
                        if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                    }
                    @Override public void onError(String msg) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
                });
                continue;
            }
            detail(p.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    if (detail != null) {
                        if (loadType) p.typeLabel = detail.getTypeLabel();
                        if (detail.genre != null && !detail.genre.trim().isEmpty()) p.genre = detail.genre;
                        if (detail.status != null && !detail.status.trim().isEmpty()) p.status = detail.status;
                        if (loadChapter && detail.latestChapter != null && !detail.latestChapter.trim().isEmpty()) p.latestChapter = detail.latestChapter;
                        if (loadChapter && detail.latestChapterDate != null) p.latestChapterDate = detail.latestChapterDate;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) {
                    if (!loadChapter) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); return; }
                    chapters(p.slug, new Result<ArrayList<MangaChapter>>() {
                        @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                            if (chapters != null && !chapters.isEmpty()) {
                                MangaChapter newest = chapters.get(0);
                                for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                                p.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
                                p.latestChapterDate = newest.date == null ? "" : newest.date;
                            }
                            if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                        }
                        @Override public void onError(String msg) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
                    });
                }
            });
        }
    }
    @Override public void detail(String slug, Result<MangaPost> cb) {
        MangaPost cached = DETAIL_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        HttpUrl url = HttpUrl.parse(api() + "/api/comic/" + slug).newBuilder().build();
        getJson(url, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        if (data == null) { MangaCoroutines.main(() -> cb.onError("Detail Westmanga kosong")); return; }
                        MangaPost post = parseDetail(data);
                        ArrayList<MangaChapter> parsedChapters = parseChapters(data);
                        CHAPTER_CACHE.put(slug, new ArrayList<>(parsedChapters));
                        DETAIL_CACHE.put(slug, post);
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { miku.moe.app.crash.CrashReporter.logParse("Westmanga", "Detail Westmanga gagal dibaca", e); MangaCoroutines.main(() -> cb.onError("Detail Westmanga gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        HttpUrl url = HttpUrl.parse(api() + "/api/comic/" + slug).newBuilder().build();
        getJson(url, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        ArrayList<MangaChapter> out = parseChapters(data == null ? new JsonObject() : data);
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                        final ArrayList<MangaChapter> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) { miku.moe.app.crash.CrashReporter.logParse("Westmanga", "Chapter Westmanga gagal dibaca", e); MangaCoroutines.main(() -> cb.onError("Chapter Westmanga gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String chapterSlug = findChapterSlug(slug, index);
        if (chapterSlug == null || chapterSlug.trim().isEmpty()) {
            chapters(slug, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                    String found = findChapterSlug(slug, index);
                    if (found == null || found.trim().isEmpty()) cb.onError("Slug chapter Westmanga tidak ditemukan");
                    else pagesByChapterSlug(slug, index, found, cb);
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        pagesByChapterSlug(slug, index, chapterSlug, cb);
    }

    private void pagesByChapterSlug(String mangaSlug, float index, String chapterSlug, Result<ArrayList<String>> cb) {
        String key = mangaSlug + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        HttpUrl url = HttpUrl.parse(api() + "/api/v/" + chapterSlug).newBuilder().build();
        getJson(url, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        JsonArray images = getArray(data, "images");
                        ArrayList<String> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        for (JsonElement el : images) {
                            String img = el == null || el.isJsonNull() ? "" : el.getAsString();
                            img = fixAssetUrl(img);
                            if (img.startsWith("http") && seen.add(img)) out.add(img);
                        }
                        PAGE_CACHE.put(key, new ArrayList<>(out));
                        final ArrayList<String> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) { miku.moe.app.crash.CrashReporter.logParse("Westmanga", "Gambar Westmanga gagal dibaca", e); MangaCoroutines.main(() -> cb.onError("Gambar Westmanga gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private Request apiRequest(HttpUrl url) {
        // 2026-10-25: header HMAC lama (x-wm-request-time / x-wm-accses-key /
        // x-wm-request-signature) DIHAPUS TOTAL. Frontend web tidak mengirim
        // header itu sama sekali (0 kemunculan di traffic browser), dan sejak
        // 25 Okt request app yang masih membawanya ditolak origin dengan 403
        // JSON padahal visit-token sudah 200 — diduga kuat origin kini
        // menolak request ber-header signature lama (kunci lama sudah tidak
        // berlaku di sisi server). Satu-satunya header auth sekarang adalah
        // X-WM-Visit yang dipasang VisitTokenInterceptor. Header Origin juga
        // dihapus (fetch GET same-origin di browser tidak mengirim Origin),
        // dan Accept disamakan dengan browser (*/*) supaya request app
        // semirip mungkin dengan request web yang terbukti lolos.
        // UA harus PERSIS sama dengan UA WebView yang dipakai menyelesaikan
        // Cloudflare: cf_clearance terikat ke UA, jadi pakai UA tersimpan untuk
        // host ini (UA bawaan WebView asli), bukan string Chrome 122 karangan
        // yang fingerprint-nya tidak cocok dengan mesin WebView beneran.
        String ua = CloudflareHelper.userAgentForHost(url.host());
        boolean sameOrigin = url.host().equals(apiHost());
        return new Request.Builder().url(url)
                .header("Referer", base() + "/")
                .header("Accept", "*/*")
                .header("Accept-Language", "id,en-US;q=0.9")
                .header("User-Agent", ua)
                .header("Sec-Fetch-Site", sameOrigin ? "same-origin" : "cross-site")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build();
    }

    private void getJson(HttpUrl url, Result<JsonObject> cb) { getJson(url, cb, false); }

    private void getJson(HttpUrl url, Result<JsonObject> cb, boolean isFallback) { getJson(url, cb, isFallback, false); }

    private void getJson(HttpUrl url, Result<JsonObject> cb, boolean isFallback, boolean visitRefreshed) {
        // Pola sama seperti Komiku: kalau WebView sudah mengambil body JSON yang
        // lolos Cloudflare untuk URL ini, pakai itu langsung tanpa network.
        String solvedBody = CloudflareHelper.cachedSolvedBody(url.toString());
        if (solvedBody != null && !solvedBody.trim().isEmpty()) {
            try {
                JsonObject cachedRoot = JsonParser.parseString(solvedBody).getAsJsonObject();
                MangaCoroutines.main(() -> cb.onSuccess(cachedRoot, false));
                return;
            } catch(Exception ignored) { }
        }
        CloudflareHelper.enqueue(client, apiRequest(url), sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    // Jalur /api same-origin tidak dikenal di host frontend
                    // (404 dari origin) -> coba sekali host API lama dengan
                    // path/query yang sama (signature dihitung ulang per URL).
                    if (response.code() == 404 && !isFallback) {
                        HttpUrl alt = url.newBuilder().scheme("https").host(HttpUrl.parse(FALLBACK_API).host()).build();
                        MangaCoroutines.main(() -> getJson(alt, cb, true));
                        return;
                    }
                    // Visit-token ditolak origin (403 + header X-WM-Visit-Error):
                    // cermin perilaku web — buang token lama, ambil yang baru,
                    // ulang request sekali. Dibatasi sekali supaya tidak loop.
                    if (response.code() == 403 && response.header(VISIT_TOKEN_ERROR_HEADER) != null && !visitRefreshed) {
                        response.close();
                        clearVisitToken();
                        MangaCoroutines.main(() -> getJson(url, cb, isFallback, true));
                        return;
                    }
                    // Sertakan cuplikan body respons supaya log error
                    // berikutnya menunjukkan ALASAN penolakan origin
                    // (mis. pesan rate-limit / token tidak valid), bukan
                    // sekadar kode 403 yang tidak bisa didiagnosis.
                    String detail = body == null ? "" : body.trim();
                    if (detail.length() > 160) detail = detail.substring(0, 160);
                    String errMsg = "HTTP " + response.code() + (detail.isEmpty() ? "" : ": " + detail);
                    MangaCoroutines.main(() -> cb.onError(errMsg));
                    return;
                }
                try { JsonObject root = JsonParser.parseString(body).getAsJsonObject(); MangaCoroutines.main(() -> cb.onSuccess(root, false)); }
                catch(Exception e) { miku.moe.app.crash.CrashReporter.logParse("Westmanga", "Data Westmanga gagal dibaca", e); MangaCoroutines.main(() -> cb.onError("Data Westmanga gagal dibaca")); }
            }
        });
    }

    private ArrayList<MangaPost> parseBrowse(JsonObject root) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonArray data = getArray(root, "data");
        for (JsonElement el : data) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject item = el.getAsJsonObject();
            String slug = getString(item, "slug");
            String title = getString(item, "title");
            String cover = fixAssetUrl(getString(item, "cover"));
            if (slug.isEmpty() || title.isEmpty() || !seen.add(slug)) continue;
            String status = getString(item, "status");
            String type = typeFromBrowse(item);
            ArrayList<String> gs = new ArrayList<>();
            if (!type.isEmpty()) gs.add(type);
            JsonArray genres = getArray(item, "genres");
            for (JsonElement ge : genres) if (ge != null && ge.isJsonObject()) {
                String name = getString(ge.getAsJsonObject(), "name");
                if (!name.isEmpty() && !gs.contains(name)) gs.add(name);
            }
            out.add(new MangaPost(slug, title, cover, "", status, "", join(gs), type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_WESTMANGA, "Westmanga"));
        }
        return out;
    }

    private MangaPost parseDetail(JsonObject data) {
        String slug = getString(data, "slug");
        String title = getString(data, "title");
        String cover = fixAssetUrl(getString(data, "cover"));
        String author = getString(data, "author");
        String status = getString(data, "status");
        String synopsis = Jsoup.parseBodyFragment(getString(data, "sinopsis")).wholeText().trim();
        String alt = getString(data, "alternative_name");
        if (!alt.isEmpty()) synopsis = synopsis + (synopsis.isEmpty() ? "" : "\n\n") + "Alternative Name: " + alt;
        ArrayList<String> gs = new ArrayList<>();
        String country = getString(data, "country_id");
        String type = typeFromCountry(country);
        if (!type.isEmpty()) gs.add(type);
        if (getBoolean(data, "color")) gs.add("Colored");
        JsonArray genres = getArray(data, "genres");
        for (JsonElement el : genres) if (el != null && el.isJsonObject()) {
            String name = getString(el.getAsJsonObject(), "name");
            if (!name.isEmpty() && !gs.contains(name)) gs.add(name);
        }
        String genre = join(gs);
        String latest = "";
        String latestDate = "";
        ArrayList<MangaChapter> chapters = parseChapters(data);
        if (!chapters.isEmpty()) {
            MangaChapter newest = chapters.get(0);
            for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
            latest = "Chapter " + MangaChapter.formatIndex(newest.index);
            latestDate = newest.date;
        }
        return new MangaPost(slug, title, cover, author, status, synopsis, genre, type, latest, latestDate).withSource(MangaSettingsManager.MANGA_SOURCE_WESTMANGA, "Westmanga");
    }

    private ArrayList<MangaChapter> parseChapters(JsonObject data) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        JsonArray arr = getArray(data, "chapters");
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject ch = el.getAsJsonObject();
            String number = cleanChapterNumber(getString(ch, "number"));
            float idx = parseFloat(number.replaceAll("(?i)\\s*end\\b", "").trim(), out.size() + 1);
            String slug = getString(ch, "slug");
            String date = prettyUnixTime(getLong(getObject(ch, "updated_at"), "time", 0L));
            MangaChapter chapter = new MangaChapter(slug, idx, chapterSuffix(number, idx), date);
            out.add(chapter);
        }
        return out;
    }

    private String findChapterSlug(String slug, float index) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached == null) return null;
        String want = MangaChapter.formatIndex(index);
        for (MangaChapter ch : cached) if (MangaChapter.formatIndex(ch.index).equals(want)) return ch.slug;
        return null;
    }

    private static String cleanChapterNumber(String raw) {
        if (raw == null) return "";
        String value = raw.replaceAll("(?i)\\bchapter\\b", "").trim();
        value = value.replaceAll("\\s+", " ");
        boolean hasEnd = value.toLowerCase(java.util.Locale.US).contains("end");
        value = value.replaceAll("(?i)\\bend\\b", "").trim();

        if (value.contains(":")) {
            String[] parts = value.split(":", 2);
            String left = parts[0].trim();
            String right = parts.length > 1 ? parts[1].trim() : "";
            if (sameChapterNumber(left, right) || right.matches("[0-9]+([\\.,][0-9]+)?")) {
                return (left + (hasEnd ? " End" : "")).trim();
            }
            return (left + " " + right + (hasEnd ? " End" : "")).trim();
        }

        java.util.regex.Matcher duplicate = java.util.regex.Pattern
                .compile("^([0-9]+(?:[\\.,][0-9]+)?)\\s+([0-9]+(?:[\\.,][0-9]+)?)$")
                .matcher(value);
        if (duplicate.find() && sameChapterNumber(duplicate.group(1), duplicate.group(2))) {
            return (duplicate.group(1) + (hasEnd ? " End" : "")).trim();
        }

        return (value + (hasEnd ? " End" : "")).trim();
    }

    private static String chapterSuffix(String cleanedNumber, float idx) {
        if (cleanedNumber == null) return "";
        String base = MangaChapter.formatIndex(idx);
        String rest = cleanedNumber.replaceFirst("^" + java.util.regex.Pattern.quote(base) + "\\b", "").trim();
        if (sameChapterNumber(base, rest)) return "";
        if (rest.equalsIgnoreCase("end")) return "End";
        return rest;
    }

    private static boolean sameChapterNumber(String a, String b) {
        if (a == null || b == null) return false;
        try {
            float fa = Float.parseFloat(a.replace(",", ".").trim());
            float fb = Float.parseFloat(b.replace(",", ".").trim());
            return Math.abs(fa - fb) < 0.0001f;
        } catch(Exception ignored) {
            return a.trim().equalsIgnoreCase(b.trim());
        }
    }

    private static String typeFromBrowse(JsonObject item) {
        String country = getString(item, "country_id");
        if (country.isEmpty()) country = getString(item, "country");
        if (!country.isEmpty()) {
            String fromCountry = typeFromCountry(country);
            if (!fromCountry.isEmpty()) return fromCountry;
        }
        String rawType = getString(item, "type");
        if (rawType.isEmpty()) rawType = getString(item, "type_label");
        if (rawType.isEmpty()) rawType = getString(item, "category");
        if (rawType.isEmpty()) rawType = getString(item, "comic_type");
        if (!rawType.isEmpty()) return MangaPost.normalizeType(rawType, "", "");
        JsonArray genres = getArray(item, "genres");
        for (JsonElement ge : genres) if (ge != null && ge.isJsonObject()) {
            String name = getString(ge.getAsJsonObject(), "name");
            String normalized = MangaPost.normalizeType(name, name, "");
            if (!"MANGA".equals(normalized) || name.toLowerCase(Locale.ROOT).contains("manga")) return normalized;
        }
        return "";
    }

    private static String typeFromCountry(String country) {
        if ("JP".equalsIgnoreCase(country)) return "Manga";
        if ("CN".equalsIgnoreCase(country)) return "Manhua";
        if ("KR".equalsIgnoreCase(country)) return "Manhwa";
        return "Manga";
    }

    private static ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        String[][] data = westGenreData();
        for (String[] pair : data) out.add(new GenreItem(pair[0], pair[1]));
        return out;
    }

    private static String normalizeGenreValue(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        String[] parts = value.split("\\|");
        value = "";
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                value = part.trim();
                break;
            }
        }
        if (value.toLowerCase(Locale.ROOT).startsWith("type:")) value = value.substring(5).trim();
        if (value.isEmpty()) return "";
        if (value.matches("[0-9]+")) return value;
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        for (String[] pair : westGenreData()) {
            String title = pair[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
            if (title.equals(normalized)) return pair[1];
        }
        return value;
    }

    private static String[][] westGenreData() {
        return new String[][]{
                {"4-Koma","344"},{"Action","13"},{"Adult","2279"},{"Adventure","4"},{"Anthology","1494"},
                {"Comedy","5"},{"Comedy. Ecchi","2028"},{"Cooking","54"},{"Crime","856"},{"Crossdressing","1306"},
                {"Demon","1318"},{"Demons","64"},{"Drama","6"},{"Ecchi","14"},{"Ecchi. Comedy","1837"},
                {"Fantasy","7"},{"Game","36"},{"Gender Bender","149"},{"Genderswap","157"},{"Ghosts","1579"},
                {"Gore","56"},{"Gyaru","812"},{"Harem","17"},{"Historical","44"},{"Horror","211"},
                {"Isekai","20"},{"Isekai Action","742"},{"Josei","164"},{"Long Strip","5917"},{"Magic","65"},
                {"Magical Girls","1527"},{"Manga","268"},{"Manhua","32"},{"Martial Art","754"},{"Martial Arts","8"},
                {"Mature","46"},{"Mecha","22"},{"Medical","704"},{"Military","1576"},{"Monster","1744"},
                {"Monster Girls","1714"},{"Monsters","91"},{"Music","457"},{"Mystery","30"},{"Ninja","2956"},
                {"Novel","5002"},{"Office Workers","1501"},{"Oneshot","405"},{"Philosophical","2894"},{"Police","2148"},
                {"Project","313"},{"Psychological","23"},{"Regression","5476"},{"Reincarnation","57"},{"Reverse Harem","1532"},
                {"Romance","15"},{"School","102"},{"School Life","9"},{"Sci-Fi","33"},{"Seinen","18"},
                {"Seinen Action","1525"},{"Shotacon","1070"},{"Shoujo","110"},{"Shoujo Ai","113"},{"Shounen","10"},
                {"Slice of Life","11"},{"Smut","586"},{"Sports","103"},{"Super Power","274"},{"Supernatural","34"},
                {"Survival","2794"},{"Suspense","181"},{"System","3088"},{"Thriller","170"},{"Time Travel","1592"},
                {"Tragedy","92"},{"Urban","1050"},{"Vampire","160"},{"Video Games","1093"},{"Villainess","2831"},
                {"Virtual Reality","2490"},{"Webtoons","486"},{"Yuri","357"},{"Zombies","377"}
        };
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static JsonObject getObject(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null; } catch(Exception e){return null;} }
    private static JsonArray getArray(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); } catch(Exception e){return new JsonArray();} }
    private static String getString(JsonObject o, String k) { try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; } catch(Exception e){return "";} }
    private static int getInt(JsonObject o, String k, int def) { try { return o != null && o.has(k) ? o.get(k).getAsInt() : def; } catch(Exception e){return def;} }
    private static long getLong(JsonObject o, String k, long def) { try { return o != null && o.has(k) ? o.get(k).getAsLong() : def; } catch(Exception e){return def;} }
    private static boolean getBoolean(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).getAsBoolean(); } catch(Exception e){return false;} }
    private static float parseFloat(String raw, float def) { try { return Float.parseFloat(raw.replace(",", ".").trim()); } catch(Exception e){ return def; } }
    private static String join(ArrayList<String> list) { StringBuilder sb = new StringBuilder(); for (String s : list) { if (s == null || s.trim().isEmpty()) continue; if (sb.length() > 0) sb.append(", "); sb.append(s.trim()); } return sb.toString(); }
    private static String prettyUnixTime(long seconds) { if (seconds <= 0) return ""; try { SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")); fmt.setTimeZone(TimeZone.getDefault()); return fmt.format(new java.util.Date(seconds * 1000L)); } catch(Exception e){ return ""; } }
}
