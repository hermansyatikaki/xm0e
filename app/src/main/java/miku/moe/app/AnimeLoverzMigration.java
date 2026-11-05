package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Menyelaraskan Favorit + History Animeloverz lama dengan API baru, di latar belakang.
 *
 * Aturan keselamatan data pengguna:
 * - TIDAK PERNAH menghapus item. Yang gagal dicocokkan dibiarkan apa adanya.
 * - Pencocokan: (1) slug langsung, (2) kalau 404, cari lewat judul dan terima hanya judul IDENTIK.
 * - Gangguan jaringan/handshake/Cloudflare = berhenti diam-diam, ulangi di start berikutnya (tidak dihitung gagal).
 * - Hanya "tidak ditemukan" yang dihitung percobaan; setelah {@link #MAX_ATTEMPTS} kali dibiarkan (tetap bisa
 *   dibuka lewat fallback judul di layar detail).
 * - Idempotent: slug yang sudah terverifikasi dicatat dan dilewati; item hasil import backup lama ikut terproses
 *   di start berikutnya.
 */
public final class AnimeLoverzMigration {
    private static final String TAG = "AnimeLoverzMigration";
    private static final String PREF = "animeloverz_migration";
    private static final String KEY_VERIFIED = "verified";
    private static final int MAX_PER_RUN = 60;
    private static final int MAX_ATTEMPTS = 3;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private AnimeLoverzMigration() { }

    public static void runAsync(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    migrate(app);
                } catch (Throwable e) {
                    Log.w(TAG, "Migrasi ditunda: " + e);
                } finally {
                    RUNNING.set(false);
                }
            }
        }, "loverz-migration");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void migrate(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Set<String> verified = new HashSet<>(prefs.getStringSet(KEY_VERIFIED, new HashSet<String>()));

        // slug lama ter-normalisasi -> judul (untuk fallback pencarian)
        LinkedHashMap<String, String[]> targets = new LinkedHashMap<>();
        for (AnimePost favorite : FavoriteManager.getFavorites(context)) {
            if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(favorite.sourceId)) collect(targets, favorite.slug, favorite.categoryName);
        }
        for (HistoryItem history : HistoryManager.getHistory(context)) {
            if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(history.sourceId)) collect(targets, history.slug, history.categoryName);
        }
        for (String done : verified) targets.remove(done);
        if (targets.isEmpty()) return;

        Map<String, AnimePost> favoriteReplacements = new HashMap<>();
        Map<String, AnimeLoverzApi.Detail> historyReplacements = new HashMap<>();
        SharedPreferences.Editor editor = prefs.edit();
        int processed = 0;
        boolean interrupted = false;

        for (Map.Entry<String, String[]> entry : targets.entrySet()) {
            if (processed++ >= MAX_PER_RUN) break;
            String key = entry.getKey();
            int attempts = prefs.getInt("att:" + key, 0);
            if (attempts >= MAX_ATTEMPTS) {
                verified.add(key);
                continue;
            }
            try {
                AnimeLoverzApi.Detail detail = AnimeLoverzApi.detailOrByTitle(entry.getValue()[0], entry.getValue()[1]);
                favoriteReplacements.put(key, detail.post);
                historyReplacements.put(key, detail);
                verified.add(key);
                verified.add(AnimeLoverzApi.normalizeSlug(detail.post.slug).toLowerCase(Locale.ROOT));
            } catch (AnimeLoverzApi.ApiException e) {
                if (e.isNotFound()) {
                    editor.putInt("att:" + key, attempts + 1);
                } else {
                    Log.w(TAG, "Dihentikan (gangguan layanan): " + e.getMessage());
                    interrupted = true;
                    break;
                }
            } catch (Exception e) {
                Log.w(TAG, "Dihentikan (jaringan): " + e);
                interrupted = true;
                break;
            }
        }

        int favorites = FavoriteManager.migrateLoverz(context, favoriteReplacements);
        int history = HistoryManager.migrateLoverz(context, historyReplacements);
        editor.putStringSet(KEY_VERIFIED, verified).apply();
        Log.i(TAG, "Favorit diperbarui=" + favorites + ", history diperbarui=" + history + (interrupted ? " (belum selesai)" : ""));
    }

    private static void collect(Map<String, String[]> targets, String slug, String title) {
        String key = AnimeLoverzApi.normalizeSlug(slug).toLowerCase(Locale.ROOT);
        if (key.isEmpty() || targets.containsKey(key)) return;
        targets.put(key, new String[]{slug, title});
    }
}
