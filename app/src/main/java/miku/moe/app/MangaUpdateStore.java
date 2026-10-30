package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.TreeSet;

/**
 * Satu-satunya tempat yang boleh menulis baseline dan daftar "chapter baru" hasil Check Update.
 *
 * Dipakai oleh MikuUpdate (tombol Check Update) DAN MangaFavoriteFragment (tombol refresh Favorite),
 * jadi kedua layar selalu membaca/menulis data yang sama dan tidak bisa berbeda.
 *
 * Prinsip:
 * - Yang disimpan adalah DAFTAR nomor chapter baru (bukan angka yang ditambah-tambah). Chapter yang sama
 *   tidak bisa terhitung dua kali walau ditemukan oleh dua tombol berbeda.
 * - Jumlah "belum dibaca" dihitung ulang saat layar dibuka dari daftar itu + riwayat baca,
 *   sehingga tidak perlu ketuk / reset apa pun.
 * - Baseline ditulis lewat satu jalur terkunci (synchronized), tidak ada lagi saling timpa.
 * - Baseline tidak mundur hanya karena sumber sesekali mengembalikan daftar chapter yang terpotong.
 */
public final class MangaUpdateStore {
    private MangaUpdateStore() {}

    static final String PREFS_RESULTS = "miku_manga_update_results";
    static final String KEY_ITEMS = "items";
    static final String KEY_LAST_CHECK_FOUND = "last_check_found";
    static final String KEY_LAST_CHECK_AT = "last_check_at";
    static final String PREFS_BASELINE = "miku_manga_update_baseline";
    static final String KEY_BASELINES = "baselines";

    /** Daftar chapter dianggap valid (tidak terpotong) kalau jumlahnya minimal segini dari total baseline. */
    private static final float MIN_VALID_LIST_RATIO = 0.8f;

    private static JSONObject baselineCache;
    private static LinkedHashMap<String, JSONObject> itemsCache;

    // ------------------------------------------------------------------ hasil & model

    public static final class CheckResult {
        /** Nomor chapter yang baru ditemukan pada pengecekan ini (urut naik, tanpa duplikat). */
        public final ArrayList<Float> newIndexes;
        /** Chapter dengan index lebih besar dari nilai ini adalah chapter baru (untuk ambil judul). */
        public final float threshold;

        CheckResult(ArrayList<Float> newIndexes, float threshold) {
            this.newIndexes = newIndexes == null ? new ArrayList<>() : newIndexes;
            this.threshold = threshold;
        }

        public int newCount() { return newIndexes.size(); }
    }

    public static final class StoredItem {
        public final String key;
        public final JSONObject post;
        public final String chapterLabel;
        public final long checkedAt;
        public final ArrayList<Float> newIndexes;

        StoredItem(String key, JSONObject post, String chapterLabel, long checkedAt, ArrayList<Float> newIndexes) {
            this.key = key;
            this.post = post;
            this.chapterLabel = chapterLabel;
            this.checkedAt = checkedAt;
            this.newIndexes = newIndexes;
        }
    }

    // ------------------------------------------------------------------ inti: catat hasil pengecekan

    /**
     * Dipanggil sekali per manga setelah daftar chapter berhasil diambil.
     * Menghitung chapter baru dari baseline, memajukan baseline (dengan penjaga), dan menyimpan
     * chapter baru ke daftar update. Aman dipanggil dari thread mana pun.
     */
    public static synchronized CheckResult applyCheck(Context context, MangaPost oldPost, MangaPost resolved, ArrayList<MangaChapter> chapters) {
        CheckResult none = new CheckResult(new ArrayList<>(), -1f);
        if (context == null || resolved == null || chapters == null || chapters.isEmpty()) return none;
        MangaPost keyPost = oldPost != null ? oldPost : resolved;
        String key = updateKey(keyPost);
        if (key.trim().isEmpty() || keyPost.slug == null || keyPost.slug.trim().isEmpty()) return none;
        MangaChapter newest = MangaUpdateCheckLogic.newestChapter(chapters);
        if (newest == null) return none;

        Context app = context.getApplicationContext();
        JSONObject baselines = baselines(app);
        JSONObject stored = baselines.optJSONObject(key);

        float baseIndex = stored != null ? (float) stored.optDouble("chapterIndex", -1d) : -1f;
        int baseTotal = stored != null ? stored.optInt("totalChapters", 0) : 0;
        if (baseIndex <= 0f && oldPost != null) baseIndex = MangaUpdateCheckLogic.parseChapterIndex(oldPost.latestChapter);
        if (baseTotal <= 0 && oldPost != null) baseTotal = MangaUpdateCheckLogic.favoriteChapterTotal(oldPost);

        // 1) Hitung chapter baru (tanpa duplikat).
        TreeSet<Float> fresh = new TreeSet<>();
        float threshold = -1f;
        if (baseIndex > 0f) {
            for (MangaChapter chapter : chapters) {
                if (chapter != null && chapter.index > baseIndex) fresh.add(chapter.index);
            }
            threshold = baseIndex;
        } else if (baseTotal > 0 && chapters.size() > baseTotal) {
            ArrayList<Float> all = new ArrayList<>();
            for (MangaChapter chapter : chapters) if (chapter != null) all.add(chapter.index);
            Collections.sort(all, Collections.reverseOrder());
            int take = Math.min(all.size(), chapters.size() - baseTotal);
            for (int i = 0; i < take; i++) fresh.add(all.get(i));
            if (!fresh.isEmpty()) threshold = fresh.first() - 0.0001f;
        }

        // 2) Majukan baseline. Baseline TIDAK boleh turun hanya karena satu kali sumber mengembalikan daftar
        //    chapter yang terpotong; turun hanya jika nilai rendah yang sama terlihat dua kali berturut-turut
        //    (misalnya sumber memang menghapus / menomori ulang chapter).
        boolean listLooksComplete = baseTotal <= 0 || chapters.size() >= Math.round(baseTotal * MIN_VALID_LIST_RATIO);
        boolean lowering = stored != null && baseIndex > 0f && newest.index < baseIndex;
        boolean advance = true;
        try {
            if (lowering) {
                float seenBefore = (float) stored.optDouble("lowerSeen", -1d);
                if (Math.abs(seenBefore - newest.index) > 0.0001f) {
                    advance = false;
                    stored.put("lowerSeen", newest.index);
                    app.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).edit().putString(KEY_BASELINES, baselines.toString()).apply();
                }
            }
            if (advance) {
                JSONObject object = new JSONObject();
                object.put("chapterIndex", newest.index);
                object.put("totalChapters", Math.max(0, listLooksComplete ? chapters.size() : Math.max(baseTotal, chapters.size())));
                object.put("chapterLabel", MangaUpdateCheckLogic.chapterLabel(newest.index));
                object.put("fingerprint", fingerprint(chapters, newest));
                object.put("checkedAt", System.currentTimeMillis());
                baselines.put(key, object);
                app.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).edit().putString(KEY_BASELINES, baselines.toString()).apply();
            }
        } catch (Exception ignored) {}

        // 3) Simpan chapter baru / segarkan data manga di daftar update.
        ArrayList<Float> freshList = new ArrayList<>(fresh);
        try {
            LinkedHashMap<String, JSONObject> items = items(app);
            String itemKey = key + "@" + dayStamp();
            JSONObject existing = items.get(itemKey);
            JSONObject postJson = postToJson(resolved);
            if (!freshList.isEmpty()) {
                TreeSet<Float> merged = new TreeSet<>(indexesOf(existing));
                merged.addAll(freshList);
                JSONObject object = new JSONObject();
                object.put("key", itemKey);
                object.put("post", postJson);
                object.put("chapterLabel", MangaUpdateCheckLogic.chapterLabel(newest.index));
                object.put("checkedAt", System.currentTimeMillis());
                JSONArray array = new JSONArray();
                for (Float value : merged) array.put((double) value);
                object.put("newIndexes", array);
                object.put("added", merged.size());
                items.put(itemKey, object);
                saveItems(app, items);
            } else if (existing != null) {
                // Tidak ada chapter baru, tapi manga ini ada di daftar: segarkan judul/cover/label bila berubah.
                String before = existing.optJSONObject("post") == null ? "" : existing.optJSONObject("post").toString();
                String after = postJson.toString();
                float existingLabelIndex = MangaUpdateCheckLogic.parseChapterIndex(existing.optString("chapterLabel", ""));
                boolean newerLabel = newest.index > existingLabelIndex;
                if (!before.equals(after) || newerLabel) {
                    existing.put("post", postJson);
                    if (newerLabel) existing.put("chapterLabel", MangaUpdateCheckLogic.chapterLabel(newest.index));
                    saveItems(app, items);
                }
            }
        } catch (Exception ignored) {}
        return new CheckResult(freshList, threshold);
    }

    // ------------------------------------------------------------------ baca daftar update

    /** Semua item tersimpan. Item lama (format sebelum perbaikan) otomatis dikonversi. */
    public static synchronized ArrayList<StoredItem> loadItems(Context context) {
        ArrayList<StoredItem> out = new ArrayList<>();
        if (context == null) return out;
        LinkedHashMap<String, JSONObject> items = items(context.getApplicationContext());
        for (JSONObject object : items.values()) {
            if (object == null) continue;
            JSONObject post = object.optJSONObject("post");
            if (post == null) continue;
            String slug = post.optString("slug", "");
            if (slug.trim().isEmpty()) continue;
            String key = object.optString("key", "");
            if (key.trim().isEmpty()) continue;
            out.add(new StoredItem(key, post, object.optString("chapterLabel", ""), object.optLong("checkedAt", System.currentTimeMillis()), indexesOf(object)));
        }
        return out;
    }

    /** Jumlah chapter baru yang belum dibaca. lastReadIndex &lt;= 0 berarti belum ada riwayat baca. */
    public static int unreadCount(ArrayList<Float> newIndexes, float lastReadIndex) {
        if (newIndexes == null) return 0;
        int count = 0;
        for (Float index : newIndexes) {
            if (index != null && index > lastReadIndex) count++;
        }
        return count;
    }

    public static synchronized void clearItems(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        itemsCache = new LinkedHashMap<>();
        app.getSharedPreferences(PREFS_RESULTS, Context.MODE_PRIVATE).edit()
                .remove(KEY_ITEMS).remove(KEY_LAST_CHECK_FOUND).remove(KEY_LAST_CHECK_AT).apply();
    }

    public static synchronized void markCheck(Context context, int found) {
        if (context == null) return;
        context.getApplicationContext().getSharedPreferences(PREFS_RESULTS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LAST_CHECK_FOUND, Math.max(0, found))
                .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
                .apply();
    }

    // ------------------------------------------------------------------ status check bersama

    public static final String OWNER_UPDATE = "update";
    public static final String OWNER_FAVORITE = "favorite";
    /** Kalau tidak ada progres selama ini, status dianggap basi (mis. proses mati) dan tidak lagi memblokir. */
    private static final long ACTIVE_STALE_MS = 3L * 60L * 1000L;

    private static String activeOwner = "";
    private static int activeDone;
    private static int activeTotal;
    private static long activeTouchedAt;

    /** Mulai check. Gagal (false) kalau tombol lain sedang memeriksa. */
    public static synchronized boolean begin(String owner, int total) {
        String busy = busyOwner();
        if (!busy.isEmpty() && !busy.equals(owner)) return false;
        activeOwner = owner == null ? "" : owner;
        activeDone = 0;
        activeTotal = Math.max(0, total);
        activeTouchedAt = System.currentTimeMillis();
        return true;
    }

    public static synchronized void progress(String owner, int done, int total) {
        if (owner == null || !owner.equals(activeOwner)) return;
        activeDone = Math.max(0, done);
        activeTotal = Math.max(0, total);
        activeTouchedAt = System.currentTimeMillis();
    }

    public static synchronized void end(String owner) {
        if (owner != null && owner.equals(activeOwner)) activeOwner = "";
    }

    /** "update", "favorite", atau "" bila tidak ada yang sedang memeriksa. */
    public static synchronized String busyOwner() {
        if (activeOwner.isEmpty()) return "";
        if (System.currentTimeMillis() - activeTouchedAt > ACTIVE_STALE_MS) {
            activeOwner = "";
            return "";
        }
        return activeOwner;
    }

    public static synchronized String progressText() {
        if (activeTotal > 0) return "Memeriksa pembaruan " + activeDone + "/" + activeTotal;
        return "Menyiapkan data favorite";
    }

    // ------------------------------------------------------------------ internal

    private static JSONObject baselines(Context app) {
        if (baselineCache == null) {
            try {
                baselineCache = new JSONObject(app.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getString(KEY_BASELINES, "{}"));
            } catch (Exception ignored) {
                baselineCache = new JSONObject();
            }
        }
        return baselineCache;
    }

    private static LinkedHashMap<String, JSONObject> items(Context app) {
        if (itemsCache == null) {
            itemsCache = new LinkedHashMap<>();
            try {
                JSONArray array = new JSONArray(app.getSharedPreferences(PREFS_RESULTS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object == null) continue;
                    String key = object.optString("key", "");
                    if (key.trim().isEmpty()) continue;
                    itemsCache.put(key, object);
                }
            } catch (Exception ignored) {}
        }
        return itemsCache;
    }

    private static void saveItems(Context app, LinkedHashMap<String, JSONObject> items) {
        JSONArray array = new JSONArray();
        for (JSONObject object : items.values()) array.put(object);
        app.getSharedPreferences(PREFS_RESULTS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    /** Baca daftar index chapter baru. Format lama (hanya "added") diperkirakan berurutan dari chapter terakhir. */
    private static ArrayList<Float> indexesOf(JSONObject object) {
        ArrayList<Float> out = new ArrayList<>();
        if (object == null) return out;
        JSONArray array = object.optJSONArray("newIndexes");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) out.add((float) array.optDouble(i, -1d));
            return out;
        }
        float latest = MangaUpdateCheckLogic.parseChapterIndex(object.optString("chapterLabel", ""));
        int added = Math.max(0, object.optInt("added", 0));
        if (latest > 0f) {
            for (int i = 0; i < added; i++) {
                float value = latest - i;
                if (value > 0f) out.add(value);
            }
            Collections.sort(out);
        }
        return out;
    }

    private static String fingerprint(ArrayList<MangaChapter> chapters, MangaChapter newest) {
        StringBuilder builder = new StringBuilder();
        builder.append(chapters == null ? 0 : chapters.size()).append('|');
        if (newest != null) {
            builder.append(newest.index).append('|');
            builder.append(safe(newest.title)).append('|');
            builder.append(safe(newest.slug)).append('|');
            builder.append(safe(newest.date));
        }
        return builder.toString();
    }

    private static JSONObject postToJson(MangaPost post) throws Exception {
        JSONObject object = new JSONObject();
        object.put("slug", post.slug);
        object.put("sourceId", post.getSourceId());
        object.put("sourceLabel", post.getSourceLabel());
        object.put("title", post.title);
        object.put("cover", post.coverImage);
        object.put("author", post.author);
        object.put("status", post.status);
        object.put("synopsis", post.synopsis);
        object.put("genre", post.genre);
        object.put("typeLabel", post.getTypeLabel());
        object.put("latestChapter", post.latestChapter);
        object.put("latestChapterDate", post.latestChapterDate);
        object.put("totalChapters", Math.max(0, post.totalChapters));
        return object;
    }

    private static String dayStamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    static String updateKey(MangaPost post) {
        if (post == null) return "";
        return safe(post.getSourceId()) + "|" + safe(post.slug).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
