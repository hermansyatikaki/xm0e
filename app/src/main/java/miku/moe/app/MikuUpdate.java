package miku.moe.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MikuUpdate extends AppCompatActivity {
    private interface UpdateTypeCallback { void done(MangaPost post); }
    private interface UpdateTypeResultCallback { void done(String type); }
    private static final String PREFS = "miku_manga_update_results";
    private static final String KEY_LAST_CHECK_FOUND = "last_check_found";
    private static final String KEY_LAST_CHECK_AT = "last_check_at";
    private final ArrayList<UpdateItem> updateItems = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Semua kerja berat (baca DB favorit, parse/serialize JSON, tulis prefs) dipindah ke thread ini
    // supaya main thread tetap bebas dan progress bar bisa berjalan mulus 60fps.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "miku-update-io");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });
    private static final String KEEP_ALIVE_JOB = "miku_update_activity";
    private static final long ADAPTER_REFRESH_DELAY_MS = 220L;
    private static final long NEXT_ITEM_DELAY_MS = 40L;
    /** Batas waktu satu manga saat Check Update; lewat dari ini item dilewati agar tidak macet. */
    private static final long ITEM_CHECK_TIMEOUT_MS = 40000L;
    /** Token langkah aktif: hanya callback/timeout dengan token yang sama yang boleh lanjut ke item berikutnya. */
    private long checkToken = 0L;
    private boolean adapterRefreshQueued;
    private UpdateAdapter adapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView statusTextView;
    private TextView emptyTextView;
    private MaterialButton checkUpdateButton;
    private MaterialButton clearUpdateHistoryButton;
    private volatile ArrayList<MangaPost> workingFavorites = new ArrayList<>();
    private static MikuUpdate visibleActivity;
    private static boolean checking;
    private boolean skipNextResumeReload;
    private boolean itemsReloadQueued;
    private final LinkedHashMap<String, MangaPost> pendingFavoriteWrites = new LinkedHashMap<>();
    private static String runningStatusText = "";

    public static final class ExternalCheckItem {
        public final MangaPost post;
        public final int added;
        public final String chapterLabel;

        public ExternalCheckItem(MangaPost post, int added, String chapterLabel) {
            this.post = post;
            this.added = Math.max(1, added);
            this.chapterLabel = chapterLabel == null ? "" : chapterLabel;
        }
    }

    /**
     * Dipanggil Favorite setelah refresh selesai. Data chapter baru & baseline sudah ditulis per manga lewat
     * MangaUpdateStore, jadi di sini cukup mencatat waktu check terakhir dan menyegarkan layar Update bila terbuka.
     */
    public static void recordExternalCheck(Context context, ArrayList<MangaPost> checkedFavorites, ArrayList<ExternalCheckItem> updates) {
        if (context == null) return;
        int found = updates == null ? 0 : updates.size();
        MangaUpdateStore.markCheck(context, found);
        MikuUpdate target = visibleActivity;
        if (target != null) target.runOnUiThread(() -> target.refreshStoredHistory(false));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_miku_update);
        setupSystemBars();
        statusTextView = findViewById(R.id.statusTextView);
        emptyTextView = findViewById(R.id.emptyUpdateTextView);
        checkUpdateButton = findViewById(R.id.checkUpdateButton);
        clearUpdateHistoryButton = findViewById(R.id.clearUpdateHistoryButton);
        MaterialButton backButton = findViewById(R.id.backButton);
        RecyclerView recyclerView = findViewById(R.id.updateRecyclerView);
        swipeRefreshLayout = findViewById(R.id.updateSwipeRefreshLayout);
        updateItems.addAll(loadStoredUpdates());
        sortUpdateItems();
        adapter = new UpdateAdapter(updateItems, this::openDetail);
        adapter.setSkeletonCount(isAnyCheckRunning() ? (updateItems.isEmpty() ? 4 : 2) : 0);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        backButton.setOnClickListener(v -> finish());
        checkUpdateButton.setOnClickListener(v -> checkUpdates(true));
        clearUpdateHistoryButton.setOnClickListener(v -> showClearUpdateHistoryDialog());
        swipeRefreshLayout.setOnRefreshListener(this::refreshStoredHistory);
        visibleActivity = this;
        updateEmpty(false, false);
        setStatus(initialStatusText());
        updateActionButtons();
        setRefreshing(isAnyCheckRunning());
        // Check Update hanya berjalan kalau pengguna menekan tombolnya sendiri (tidak ada auto-check saat layar dibuka).
        skipNextResumeReload = true;
    }

    @Override protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
        visibleActivity = this;
        if (adapter != null) adapter.setSkeletonCount(isAnyCheckRunning() ? (updateItems.isEmpty() ? 4 : 2) : 0);
        // Data sudah dimuat di onCreate; jangan muat dua kali saat layar pertama kali muncul.
        if (skipNextResumeReload) skipNextResumeReload = false;
        else refreshStoredHistory(false);
        setStatus(initialStatusText());
        updateActionButtons();
    }

    @Override protected void onDestroy() {
        if (visibleActivity == this) visibleActivity = null;
        super.onDestroy();
    }

    private void showClearUpdateHistoryDialog() {
        if (updateItems.isEmpty()) {
            setStatus("History update masih kosong");
            updateActionButtons();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Hapus history update")
                .setMessage("Apakah kamu yakin ingin menghapus semua history hasil update manga?")
                .setNegativeButton("Tidak", null)
                .setPositiveButton("Ya", (dialog, which) -> clearUpdateHistory())
                .show();
    }

    private void clearUpdateHistory() {
        updateItems.clear();
        final Context appCtx = getApplicationContext();
        IO.execute(() -> MangaUpdateStore.clearItems(appCtx));
        if (adapter != null) adapter.refreshData();
        if (emptyTextView != null) {
            emptyTextView.setText("History update kosong");
            emptyTextView.setVisibility(View.VISIBLE);
        }
        setStatus("History update sudah dihapus");
        updateActionButtons();
    }

    /** True bila check sedang berjalan, baik dari tombol ini maupun dari tombol refresh di Favorite. */
    private static boolean isAnyCheckRunning() {
        return checking || !MangaUpdateStore.busyOwner().isEmpty();
    }

    /** True bila yang sedang memeriksa adalah tombol di Favorite (bukan tombol di layar ini). */
    private static boolean isExternalCheckRunning() {
        return !checking && MangaUpdateStore.OWNER_FAVORITE.equals(MangaUpdateStore.busyOwner());
    }

    /** Dipanggil Favorite tiap ada kemajuan / selesai, supaya layar Update ikut menampilkan progresnya. */
    public static void notifyExternalCheckState() {
        final MikuUpdate target = visibleActivity;
        if (target == null) return;
        target.runOnUiThread(target::syncExternalCheckUi);
    }

    /** Dipanggil Favorite saat menemukan chapter baru, supaya daftar di layar Update ikut bertambah. */
    public static void notifyItemsChanged() {
        final MikuUpdate target = visibleActivity;
        if (target == null) return;
        target.runOnUiThread(target::reloadItemsFromStore);
    }

    private void syncExternalCheckUi() {
        if (isFinishing() || isDestroyed() || checking) return;
        boolean external = isExternalCheckRunning();
        if (adapter != null) adapter.setSkeletonCount(external ? (updateItems.isEmpty() ? 4 : 2) : 0);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(external);
        if (statusTextView != null) statusTextView.setText(external ? MangaUpdateStore.progressText() : initialStatusText());
        updateEmpty(false, false);
        updateActionButtons();
    }

    private void updateActionButtons() {
        MikuUpdate target = visibleActivity == null ? this : visibleActivity;
        if (target.checkUpdateButton != null) target.checkUpdateButton.setEnabled(!isAnyCheckRunning());
        if (target.clearUpdateHistoryButton != null) {
            boolean enabled = !isAnyCheckRunning() && !target.updateItems.isEmpty();
            target.clearUpdateHistoryButton.setEnabled(enabled);
            target.clearUpdateHistoryButton.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void refreshStoredHistory() {
        refreshStoredHistory(true);
    }

    private void refreshStoredHistory(boolean showRefresh) {
        if (showRefresh) setRefreshing(true);
        updateItems.clear();
        updateItems.addAll(loadStoredUpdates());
        sortUpdateItems();
        // Skeleton hanya boleh tampil kalau check benar-benar sedang berjalan. Ini menyembuhkan kasus
        // activity dibuat ulang saat check jalan, lalu check selesai di instance lama.
        if (!isAnyCheckRunning() && adapter != null) adapter.setSkeletonCount(0);
        if (adapter != null) adapter.refreshData();
        updateEmpty(false, false);
        if (!checking) setStatus(initialStatusText());
        updateActionButtons();
        setRefreshing(isAnyCheckRunning());
    }

    /** Matikan skeleton di instance ini DAN di activity yang sedang tampil (bisa beda instance). */
    private void clearSkeletonEverywhere() {
        if (adapter != null) adapter.setSkeletonCount(0);
        MikuUpdate visible = visibleActivity;
        if (visible != null && visible != this && visible.adapter != null) visible.adapter.setSkeletonCount(0);
    }

    private void refreshVisibleStoredHistory() {
        MikuUpdate target = visibleActivity;
        if (target != null) target.refreshStoredHistory(false);
    }

    private static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    /** Gabungkan banyak perubahan list jadi satu refresh adapter (anti notifyDataSetChanged beruntun). */
    private void scheduleAdapterRefresh() {
        if (adapterRefreshQueued) return;
        adapterRefreshQueued = true;
        handler.postDelayed(() -> {
            adapterRefreshQueued = false;
            if (adapter != null) adapter.refreshData();
        }, ADAPTER_REFRESH_DELAY_MS);
    }

    /** Sinkronkan activity lain yang sedang tampil (kalau bukan instance ini) setelah data tersimpan. */
    private void syncOtherVisibleActivity() {
        MikuUpdate other = visibleActivity;
        if (other != null && other != this) other.refreshStoredHistory(false);
    }

    private void checkUpdates(boolean forceNetwork) {
        if (checking) {
            setRefreshing(true);
            updateActionButtons();
            return;
        }
        if (!MangaUpdateStore.begin(MangaUpdateStore.OWNER_UPDATE, 0)) {
            Toast.makeText(this, "Sedang memeriksa dari Favorite, tunggu sampai selesai", Toast.LENGTH_SHORT).show();
            syncExternalCheckUi();
            return;
        }
        checking = true;
        runningStatusText = "Menyiapkan data favorite";
        // Jaga proses tetap hidup meski pengguna keluar dari layar ini.
        UpdateKeepAliveService.update(getApplicationContext(), KEEP_ALIVE_JOB, "Menyiapkan data favorit");
        if (adapter != null) adapter.setSkeletonCount(updateItems.isEmpty() ? 4 : 2);
        updateEmpty(false, false);
        setStatus(runningStatusText);
        setRefreshing(true);
        updateActionButtons();
        final Context app = getApplicationContext();
        IO.execute(() -> {
            if (forceNetwork) MangaMemoryCache.clearRegistered();
            ArrayList<MangaPost> favorites = MangaFavoriteManager.getFavorites(app);
            if (favorites.isEmpty()) {
                runOnMain(() -> {
                    checking = false;
                    runningStatusText = "";
                    MangaUpdateStore.end(MangaUpdateStore.OWNER_UPDATE);
                    UpdateKeepAliveService.finish(app, KEEP_ALIVE_JOB);
                    clearSkeletonEverywhere();
                    updateEmpty(false, updateItems.isEmpty());
                    setStatus("Belum ada manga favorit");
                    setRefreshing(false);
                    updateActionButtons();
                });
                return;
            }
            final ArrayList<MangaPost> working = favoriteSnapshotCopy(favorites);
            final ArrayList<MangaPost> snapshot = favoriteSnapshotCopy(favorites);
            runOnMain(() -> {
                workingFavorites = working;
                runningStatusText = "Memeriksa pembaruan 0/" + snapshot.size();
                setStatus(runningStatusText);
                checkAt(snapshot, 0, false, 0);
            });
        });
    }

    private ArrayList<MangaPost> favoriteSnapshotCopy(ArrayList<MangaPost> source) {
        ArrayList<MangaPost> out = new ArrayList<>();
        if (source == null) return out;
        for (MangaPost post : source) out.add(copyFavoritePost(post));
        return out;
    }

    private MangaPost reloadFavoriteForCheck(MangaPost post) {
        return copyFavoritePost(post);
    }

    private MangaPost copyFavoritePost(MangaPost post) {
        if (post == null) return null;
        MangaPost copy = new MangaPost(post.slug, post.title, post.coverImage, post.author, post.status, post.synopsis, post.genre, post.typeLabel, post.latestChapter, post.latestChapterDate).withSource(post.getSourceId(), post.getSourceLabel());
        copy.info = post.info;
        copy.totalChapters = post.totalChapters;
        copy.favoriteChapterBase = post.favoriteChapterBase;
        copy.favoriteChapterAdded = post.favoriteChapterAdded;
        copy.historyChapterIndex = post.historyChapterIndex;
        copy.historyLastRead = post.historyLastRead;
        copy.historyPage = post.historyPage;
        copy.historyTotalPages = post.historyTotalPages;
        return copy;
    }

    private void checkAt(ArrayList<MangaPost> snapshot, int index, boolean changed, int found) {
        if (index >= snapshot.size()) {
            finishCheck(found);
            return;
        }
        MangaPost oldPost = snapshot.get(index);
        MangaUpdateStore.progress(MangaUpdateStore.OWNER_UPDATE, index + 1, snapshot.size());
        setStatus("Memeriksa pembaruan " + (index + 1) + "/" + snapshot.size());
        if (oldPost == null || oldPost.slug == null || oldPost.slug.trim().isEmpty()) {
            handler.post(() -> checkAt(snapshot, index + 1, changed, found));
            return;
        }
        final MangaPost checkPost = reloadFavoriteForCheck(oldPost);
        final long token = ++checkToken;
        // Sumber bermasalah (Cloudflare / website mati / tidak merespons): lewati setelah timeout.
        handler.postDelayed(() -> advanceCheck(token, snapshot, index, changed, found), ITEM_CHECK_TIMEOUT_MS);
        handler.postDelayed(() -> checkFavoriteAt(token, snapshot, index, changed, found, checkPost), NEXT_ITEM_DELAY_MS);
    }

    /** Lanjut ke manga berikutnya tepat satu kali per langkah; panggilan ganda/terlambat diabaikan. */
    private void advanceCheck(long token, ArrayList<MangaPost> snapshot, int index, boolean changed, int found) {
        handler.post(() -> {
            if (token != checkToken) return;
            checkToken++;
            handler.postDelayed(() -> checkAt(snapshot, index + 1, changed, found), NEXT_ITEM_DELAY_MS);
        });
    }

    private void finishCheck(int found) {
        checking = false;
        MangaUpdateStore.end(MangaUpdateStore.OWNER_UPDATE);
        UpdateKeepAliveService.finish(getApplicationContext(), KEEP_ALIVE_JOB);
        flushPendingFavoriteWrites();
        clearSkeletonEverywhere();
        persistLastCheckResult(found);
        // Muat ulang dari satu sumber data (store + riwayat baca) supaya angka selalu sinkron.
        refreshStoredHistory(false);
        syncOtherVisibleActivity();
        setRefreshing(false);
        updateActionButtons();
        updateEmpty(true, false);
        setStatus(updateStatusTextAfterCheck(found));
    }

    private void checkFavoriteAt(long token, ArrayList<MangaPost> snapshot, int index, boolean changed, int found, MangaPost oldPost) {
        try {
            KomikcastClient sourceApi = MangaSourceFactory.createBySourceId(oldPost.getSourceId());
            MangaRepository.INSTANCE.detailFuture(oldPost.getSourceId(), oldPost.slug).whenComplete((fresh, detailError) -> {
                if (token != checkToken) return;
                try {
                    if (detailError != null || fresh == null) {
                        // Detail gagal: jangan scan semua sumber lain (lambat), cukup coba cek chapter.
                        finishUpdateCheckWithChapters(token, snapshot, index, changed, found, oldPost, oldPost);
                        return;
                    }
                    MangaPost base = mergeFavorite(oldPost, fresh);
                    resolveUpdateType(oldPost, base, sourceApi, resolved -> finishUpdateCheckWithChapters(token, snapshot, index, changed, found, oldPost, resolved));
                } catch (Throwable t) {
                    advanceCheck(token, snapshot, index, changed, found);
                }
            });
        } catch (Throwable t) {
            advanceCheck(token, snapshot, index, changed, found);
        }
    }

    private void finishUpdateCheckWithChapters(long token, ArrayList<MangaPost> snapshot, int index, boolean changed, int found, MangaPost oldPost, MangaPost resolved) {
        if (token != checkToken) return;
        try {
        MangaRepository.INSTANCE.chaptersReliableFuture(oldPost.getSourceId(), oldPost.slug).whenComplete((chapters, chapterError) -> {
            if (token != checkToken) return;
            final Context app = getApplicationContext();
            IO.execute(() -> {
                try {
                if (chapterError != null || chapters == null) {
                    final boolean itemChanged = replaceFavorite(oldPost, resolved);
                    runOnMain(() -> advanceCheck(token, snapshot, index, changed || itemChanged, found));
                    return;
                }
                MangaUpdateCheckLogic.applyLatestChapter(resolved, chapters);
                // Satu jalur penyimpanan yang sama dengan tombol refresh di Favorite.
                final MangaUpdateStore.CheckResult result = MangaUpdateStore.applyCheck(app, oldPost, resolved, chapters);
                final boolean itemChanged = replaceFavorite(oldPost, resolved);
                runOnMain(() -> {
                    int nextFound = found;
                    if (result.newCount() > 0) {
                        nextFound++;
                        reloadItemsFromStore();
                    }
                    advanceCheck(token, snapshot, index, changed || itemChanged, nextFound);
                });
                } catch (Throwable ignored) {
                    // Satu manga gagal diproses tidak boleh menghentikan seluruh pengecekan.
                    runOnMain(() -> advanceCheck(token, snapshot, index, changed, found));
                }
            });
        });
        } catch (Throwable t) {
            advanceCheck(token, snapshot, index, changed, found);
        }
    }

    /**
     * Muat ulang daftar dari store (dihitung dengan riwayat baca). Dijalankan di thread latar, digabung
     * (maksimal sekali per ~0,7 detik) dan hanya jika layar Update sedang tampil, supaya check yang berjalan
     * di belakang tidak membebani main thread.
     */
    private void reloadItemsFromStore() {
        final MikuUpdate target = visibleActivity;
        if (target == null || target.itemsReloadQueued || target.isFinishing() || target.isDestroyed()) return;
        target.itemsReloadQueued = true;
        target.handler.postDelayed(() -> {
            target.itemsReloadQueued = false;
            if (target.isFinishing() || target.isDestroyed()) return;
            IO.execute(() -> {
                final ArrayList<UpdateItem> fresh = target.loadStoredUpdates();
                runOnMain(() -> {
                    if (target.isFinishing() || target.isDestroyed()) return;
                    target.updateItems.clear();
                    target.updateItems.addAll(fresh);
                    target.sortUpdateItems();
                    target.scheduleAdapterRefresh();
                    target.updateEmpty(false, false);
                });
            });
        }, 700L);
    }

    private MangaChapter newestChapter(ArrayList<MangaChapter> chapters) {
        return MangaUpdateCheckLogic.newestChapter(chapters);
    }

    private void resolveUpdateType(MangaPost oldPost, MangaPost target, KomikcastClient sourceApi, UpdateTypeCallback callback) {
        if (callback == null) return;
        if (target == null) {
            callback.done(oldPost);
            return;
        }
        String currentType = MangaLabelUtils.typeForFlag(target);
        if (!currentType.isEmpty()) {
            target.typeLabel = currentType;
            callback.done(target);
            return;
        }
        String query = firstNonEmpty(target.title, oldPost == null ? "" : oldPost.title, target.slug);
        if (query.isEmpty() || sourceApi == null) {
            applyUpdateLocalTypeFallback(target);
            callback.done(target);
            return;
        }
        sourceApi.list(1, "latest", query, "", new KomikcastClient.Result<ArrayList<MangaPost>>() {
            @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext) {
                String foundType = typeFromSearchResult(target, data);
                if (!foundType.isEmpty()) {
                    target.typeLabel = foundType;
                    callback.done(target);
                    return;
                }
                resolveUpdateTypeBySourceFilters(target, sourceApi, query, resolvedType -> {
                    if (!resolvedType.isEmpty()) target.typeLabel = resolvedType;
                    else applyUpdateLocalTypeFallback(target);
                    callback.done(target);
                });
            }
            @Override public void onError(String message) {
                resolveUpdateTypeBySourceFilters(target, sourceApi, query, resolvedType -> {
                    if (!resolvedType.isEmpty()) target.typeLabel = resolvedType;
                    else applyUpdateLocalTypeFallback(target);
                    callback.done(target);
                });
            }
        });
    }

    private void resolveUpdateTypeBySourceFilters(MangaPost target, KomikcastClient sourceApi, String query, UpdateTypeResultCallback callback) {
        if (callback == null) return;
        if (target == null || sourceApi == null || query.trim().isEmpty()) {
            callback.done("");
            return;
        }
        sourceApi.genres(new KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>>() {
            @Override public void onSuccess(ArrayList<KomikcastClient.GenreItem> genres, boolean hasNext) {
                ArrayList<TypeFilterCandidate> candidates = typeFilterCandidates(genres);
                if (candidates.isEmpty()) {
                    callback.done("");
                    return;
                }
                resolveUpdateTypeByCandidate(target, sourceApi, query, candidates, 0, callback);
            }
            @Override public void onError(String message) {
                callback.done("");
            }
        });
    }

    private void resolveUpdateTypeByCandidate(MangaPost target, KomikcastClient sourceApi, String query, ArrayList<TypeFilterCandidate> candidates, int index, UpdateTypeResultCallback callback) {
        if (callback == null) return;
        if (target == null || sourceApi == null || index >= candidates.size()) {
            callback.done("");
            return;
        }
        TypeFilterCandidate candidate = candidates.get(index);
        sourceApi.list(1, "latest", query, candidate.value, new KomikcastClient.Result<ArrayList<MangaPost>>() {
            @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext) {
                MangaPost matched = matchingSearchPost(target, data);
                if (matched != null) {
                    String found = MangaLabelUtils.normalizeStoredType(matched.typeLabel, safe(matched.genre) + " " + safe(matched.status) + " " + safe(matched.info));
                    callback.done(found.isEmpty() ? candidate.type : found);
                    return;
                }
                resolveUpdateTypeByCandidate(target, sourceApi, query, candidates, index + 1, callback);
            }
            @Override public void onError(String message) {
                resolveUpdateTypeByCandidate(target, sourceApi, query, candidates, index + 1, callback);
            }
        });
    }

    private ArrayList<TypeFilterCandidate> typeFilterCandidates(ArrayList<KomikcastClient.GenreItem> genres) {
        ArrayList<TypeFilterCandidate> out = new ArrayList<>();
        if (genres == null) return out;
        addTypeFilterCandidate(out, genres, "MANHWA");
        addTypeFilterCandidate(out, genres, "MANHUA");
        addTypeFilterCandidate(out, genres, "MANGA");
        return out;
    }

    private void addTypeFilterCandidate(ArrayList<TypeFilterCandidate> out, ArrayList<KomikcastClient.GenreItem> genres, String type) {
        for (KomikcastClient.GenreItem item : genres) {
            if (item == null) continue;
            String label = firstNonEmpty(item.title, item.value);
            String normalized = MangaLabelUtils.normalizeStoredType(label, item.value);
            if (type.equals(normalized)) out.add(new TypeFilterCandidate(type, item.value));
        }
    }

    private String typeFromSearchResult(MangaPost target, ArrayList<MangaPost> data) {
        MangaPost best = matchingSearchPost(target, data);
        if (best == null) return "";
        return MangaLabelUtils.normalizeStoredType(best.typeLabel, safe(best.genre) + " " + safe(best.status) + " " + safe(best.info));
    }

    private MangaPost matchingSearchPost(MangaPost target, ArrayList<MangaPost> data) {
        if (target == null || data == null || data.isEmpty()) return null;
        String targetSlug = safeLower(target.slug);
        String targetTitle = safeLower(target.title);
        for (MangaPost item : data) if (item != null && !targetSlug.isEmpty() && targetSlug.equals(safeLower(item.slug))) return item;
        for (MangaPost item : data) if (item != null && !targetTitle.isEmpty() && targetTitle.equals(safeLower(item.title))) return item;
        return null;
    }

    private void applyUpdateLocalTypeFallback(MangaPost post) {
        if (post == null) return;
        String resolved = MangaLabelUtils.normalizeStoredType(post.typeLabel, safe(post.genre) + " " + safe(post.status) + " " + safe(post.synopsis) + " " + safe(post.info) + " " + safe(post.title) + " " + safe(post.slug));
        if (!resolved.isEmpty()) post.typeLabel = resolved;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private MangaPost mergeFavorite(MangaPost oldPost, MangaPost fresh) {
        MangaPost result = fresh == null ? oldPost : fresh;
        if (result.title == null || result.title.trim().isEmpty()) result.title = oldPost.title;
        if (result.coverImage == null || result.coverImage.trim().isEmpty()) result.coverImage = oldPost.coverImage;
        if (result.author == null || result.author.trim().isEmpty()) result.author = oldPost.author;
        if (result.status == null || result.status.trim().isEmpty()) result.status = oldPost.status;
        if (result.synopsis == null || result.synopsis.trim().isEmpty()) result.synopsis = oldPost.synopsis;
        if (result.genre == null || result.genre.trim().isEmpty()) result.genre = oldPost.genre;
        if (result.info == null || result.info.trim().isEmpty()) result.info = oldPost.info;
        String oldType = MangaLabelUtils.typeLabel(oldPost);
        String resultType = MangaLabelUtils.typeLabel(result);
        if (MangaLabelUtils.isSpecificCountryType(resultType)) result.typeLabel = resultType;
        else if (MangaLabelUtils.isSpecificCountryType(oldType) && (resultType.isEmpty() || "MANGA".equals(resultType))) result.typeLabel = oldType;
        else if (!resultType.isEmpty()) result.typeLabel = resultType;
        else result.typeLabel = oldType;
        if (result.latestChapter == null || result.latestChapter.trim().isEmpty()) result.latestChapter = oldPost.latestChapter;
        if (result.latestChapterDate == null || result.latestChapterDate.trim().isEmpty()) result.latestChapterDate = oldPost.latestChapterDate;
        result.withSource(oldPost.getSourceId(), oldPost.getSourceLabel());
        result.totalChapters = Math.max(result.totalChapters, oldPost.totalChapters);
        result.favoriteChapterBase = 0;
        result.favoriteChapterAdded = 0;
        return result;
    }

    private void applyLatestChapter(MangaPost post, ArrayList<MangaChapter> chapters) {
        MangaUpdateCheckLogic.applyLatestChapter(post, chapters);
    }

    private int calculateAddedFromBaseline(float baseIndex, ArrayList<MangaChapter> chapters) {
        return MangaUpdateCheckLogic.calculateAddedFromBaseline(baseIndex, 0, chapters);
    }

    private int countNewChapters(ArrayList<MangaChapter> chapters, float threshold) {
        return MangaUpdateCheckLogic.countNewChapters(chapters, threshold);
    }

    private int favoriteChapterTotal(MangaPost post) {
        return MangaUpdateCheckLogic.favoriteChapterTotal(post);
    }

    private static float parseChapterIndex(String text) {
        return MangaUpdateCheckLogic.parseChapterIndex(text);
    }

    private boolean replaceFavorite(MangaPost oldPost, MangaPost fresh) {
        for (int i = 0; i < workingFavorites.size(); i++) {
            MangaPost current = workingFavorites.get(i);
            if (current != null && current.slug.equals(oldPost.slug) && current.getSourceId().equals(oldPost.getSourceId())) {
                String before = signature(current);
                workingFavorites.set(i, fresh);
                boolean changed = !before.equals(signature(fresh));
                if (changed && fresh != null) {
                    synchronized (pendingFavoriteWrites) { pendingFavoriteWrites.put(MangaUpdateStore.updateKey(fresh), fresh); }
                }
                return changed;
            }
        }
        return false;
    }

    /** Tulis semua perubahan data favorit dengan SATU kali simpan, di thread latar. */
    private void flushPendingFavoriteWrites() {
        final ArrayList<MangaPost> writes;
        synchronized (pendingFavoriteWrites) {
            writes = new ArrayList<>(pendingFavoriteWrites.values());
            pendingFavoriteWrites.clear();
        }
        if (writes.isEmpty()) return;
        final Context app = getApplicationContext();
        IO.execute(() -> MangaFavoriteManager.updateExistingFavorites(app, writes));
    }

    private String signature(MangaPost post) {
        if (post == null) return "";
        return safe(post.title) + "|" + safe(post.coverImage) + "|" + safe(post.author) + "|" + safe(post.status) + "|" + safe(post.synopsis) + "|" + safe(post.genre) + "|" + safe(post.info) + "|" + MangaLabelUtils.typeLabel(post) + "|" + safe(post.latestChapter) + "|" + safe(post.latestChapterDate) + "|" + post.totalChapters;
    }

    private void openDetail(UpdateItem item) {
        if (item == null || item.post == null) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_MANGA_DETAIL, item.post);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void updateEmpty(boolean done, boolean noFavorite) {
        if (emptyTextView == null) return;
        if (noFavorite && updateItems.isEmpty()) {
            emptyTextView.setText("Belum ada manga favorit");
            emptyTextView.setVisibility(View.VISIBLE);
            return;
        }
        if (done && updateItems.isEmpty()) {
            emptyTextView.setText("Tidak ada chapter terbaru");
            emptyTextView.setVisibility(View.VISIBLE);
            return;
        }
        emptyTextView.setVisibility(updateItems.isEmpty() && !isAnyCheckRunning() ? View.VISIBLE : View.GONE);
    }

    private void setRefreshing(boolean refreshing) {
        MikuUpdate target = visibleActivity == null ? this : visibleActivity;
        if (target.swipeRefreshLayout != null) target.swipeRefreshLayout.setRefreshing(refreshing);
    }

    private void setStatus(String text) {
        String value = text == null ? "" : text;
        if (checking) {
            runningStatusText = value;
            UpdateKeepAliveService.update(getApplicationContext(), KEEP_ALIVE_JOB, value);
        }
        MikuUpdate target = visibleActivity == null ? this : visibleActivity;
        if (target.statusTextView != null) target.statusTextView.setText(value);
    }

    private String initialStatusText() {
        if (checking && !runningStatusText.trim().isEmpty()) return runningStatusText;
        if (isExternalCheckRunning()) return MangaUpdateStore.progressText();
        long lastCheckAt = prefs().getLong(KEY_LAST_CHECK_AT, 0L);
        int lastFound = prefs().getInt(KEY_LAST_CHECK_FOUND, -1);
        if (!updateItems.isEmpty()) return updateItems.size() + " manga punya chapter belum dibaca";
        if (lastCheckAt > 0 && lastFound >= 0 && makeDayKey(lastCheckAt).equals(makeDayKey(System.currentTimeMillis()))) {
            return updateStatusTextAfterCheck(lastFound);
        }
        return "Tekan tombol update untuk memeriksa pembaruan favorit manga";
    }

    private String updateStatusTextAfterCheck(int found) {
        if (found > 0) return found + " manga punya chapter terbaru";
        return "Tidak ada chapter terbaru dari check terakhir";
    }

    private void persistLastCheckResult(int found) {
        prefs().edit().putInt(KEY_LAST_CHECK_FOUND, Math.max(0, found)).putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String updateKey(MangaPost post) {
        if (post == null) return "";
        return safe(post.getSourceId()) + "|" + safe(post.slug).trim();
    }

    private void sortUpdateItems() {
        Collections.sort(updateItems, new Comparator<UpdateItem>() {
            @Override public int compare(UpdateItem a, UpdateItem b) {
                long diff = b.checkedAt - a.checkedAt;
                if (diff > 0) return 1;
                if (diff < 0) return -1;
                String at = a.post == null ? "" : safe(a.post.title).toLowerCase(Locale.ROOT);
                String bt = b.post == null ? "" : safe(b.post.title).toLowerCase(Locale.ROOT);
                return at.compareTo(bt);
            }
        });
    }

    /**
     * Baca daftar update dari store. "added" di sini = jumlah chapter baru yang BELUM DIBACA,
     * dihitung langsung dari riwayat baca setiap kali dipanggil. Manga yang semua chapter barunya
     * sudah dibaca tidak ditampilkan lagi.
     */
    private ArrayList<UpdateItem> loadStoredUpdates() {
        ArrayList<UpdateItem> list = new ArrayList<>();
        try {
            Context app = getApplicationContext();
            for (MangaUpdateStore.StoredItem stored : MangaUpdateStore.loadItems(app)) {
                MangaPost post = postFromJson(stored.post);
                if (post == null || post.slug == null || post.slug.trim().isEmpty()) continue;
                float lastRead = MangaHistoryManager.getLastReadChapterIndex(app, post);
                int unread = MangaUpdateStore.unreadCount(stored.newIndexes, lastRead);
                if (unread <= 0) continue;
                String chapterLabel = stored.chapterLabel == null || stored.chapterLabel.trim().isEmpty() ? post.latestChapter : stored.chapterLabel;
                list.add(new UpdateItem(stored.key, post, chapterLabel, stored.checkedAt, Math.max(1, stored.newIndexes.size())));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private MangaPost postFromJson(JSONObject object) {
        if (object == null) return null;
        String sourceId = object.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
        MangaPost post = new MangaPost(object.optString("slug"), object.optString("title"), object.optString("cover"), object.optString("author"), object.optString("status"), object.optString("synopsis"), object.optString("genre"), object.optString("typeLabel"), object.optString("latestChapter"), object.optString("latestChapterDate")).withSource(sourceId, object.optString("sourceLabel", MangaSourceFactory.labelForSourceId(sourceId)));
        post.totalChapters = object.optInt("totalChapters", 0);
        return post;
    }

    private SharedPreferences prefs() {
        return getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void setupSystemBars() {
        ThemeManager.applySystemBars(this);
    }

    private static String makeDayKey(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp <= 0 ? System.currentTimeMillis() : timestamp);
        return calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private static String makeDayLabel(long timestamp) {
        long diffDays = dayDiff(timestamp <= 0 ? System.currentTimeMillis() : timestamp);
        if (diffDays <= 0) return "Hari ini";
        if (diffDays == 1) return "Kemarin";
        return diffDays + " hari yang lalu";
    }

    private static long dayDiff(long timestamp) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(timestamp);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        return Math.max(0L, (today.getTimeInMillis() - start.getTimeInMillis()) / 86400000L);
    }

    private static class TypeFilterCandidate {
        final String type;
        final String value;
        TypeFilterCandidate(String type, String value) { this.type = type == null ? "" : type; this.value = value == null ? "" : value; }
    }

    private static class UpdateItem {
        final String key;
        final MangaPost post;
        final String chapterLabel;
        final long checkedAt;
        final int added;

        UpdateItem(String key, MangaPost post, String chapterLabel, long checkedAt, int added) {
            this.key = key == null ? "" : key;
            this.post = post;
            this.chapterLabel = chapterLabel == null ? "" : chapterLabel;
            this.checkedAt = checkedAt <= 0 ? System.currentTimeMillis() : checkedAt;
            this.added = Math.max(1, added);
        }
    }

    private static class Row {
        static final int TYPE_HEADER = 0;
        static final int TYPE_ITEM = 1;
        static final int TYPE_SKELETON = 2;
        final int type;
        final String header;
        final UpdateItem item;

        Row(String header) {
            this.type = TYPE_HEADER;
            this.header = header;
            this.item = null;
        }

        Row(UpdateItem item) {
            this.type = TYPE_ITEM;
            this.header = "";
            this.item = item;
        }

        Row() {
            this.type = TYPE_SKELETON;
            this.header = "";
            this.item = null;
        }
    }

    private static class UpdateAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        interface Listener { void onClick(UpdateItem item); }
        private final ArrayList<UpdateItem> items;
        private final ArrayList<Row> rows = new ArrayList<>();
        private final Listener listener;
        private int skeletonCount;

        UpdateAdapter(ArrayList<UpdateItem> items, Listener listener) {
            this.items = items;
            this.listener = listener;
            setHasStableIds(true);
            rebuildRows();
        }

        void setSkeletonCount(int count) {
            int next = Math.max(0, count);
            if (skeletonCount == next) return;
            skeletonCount = next;
            rebuildRows();
            notifyDataSetChanged();
        }

        void refreshData() {
            rebuildRows();
            notifyDataSetChanged();
        }

        private void rebuildRows() {
            rows.clear();
            // Skeleton / fake FB shimmer SELALU ditaruh PALING ATAS: saat "Check update" berjalan,
            // placeholder hasil baru harus muncul di posisi konten baru akan tampil (di atas history),
            // bukan di bawah. Kalau belum ada history, skeleton tetap tampil sebagai satu-satunya isi list.
            for (int i = 0; i < skeletonCount; i++) rows.add(new Row());
            String lastKey = "";
            for (UpdateItem item : items) {
                if (item == null) continue;
                String key = makeDayKey(item.checkedAt);
                if (!key.equals(lastKey)) {
                    rows.add(new Row(makeDayLabel(item.checkedAt)));
                    lastKey = key;
                }
                rows.add(new Row(item));
            }
        }

        @Override public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override public long getItemId(int position) {
            Row row = rows.get(position);
            if (row.type == Row.TYPE_SKELETON) return Long.MIN_VALUE + position;
            if (row.type == Row.TYPE_HEADER) return ("header:" + row.header).hashCode();
            return row.item == null ? position : ("update:" + row.item.key).hashCode();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == Row.TYPE_HEADER) return new HeaderHolder(inflater.inflate(R.layout.history_day_header, parent, false));
            return new Holder(inflater.inflate(R.layout.manga_update_item, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).text.setText(row.header);
                return;
            }
            Holder itemHolder = (Holder) holder;
            hideShimmer(itemHolder);
            if (row.type == Row.TYPE_SKELETON) {
                bindSkeleton(itemHolder);
                return;
            }
            UpdateItem item = row.item;
            MangaPost post = item == null ? null : item.post;
            itemHolder.title.setText(post == null ? "" : post.title);
            itemHolder.chapter.setText(item == null || item.chapterLabel.trim().isEmpty() ? "Chapter" : item.chapterLabel);
            itemHolder.count.setText("Ditemukan " + (item == null || item.added <= 1 ? 1 : item.added) + " Chapter baru");
            itemHolder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(item);
            });
            if (post != null) {
                MangaImageLoader.loadShimmerCover(itemHolder.cover, post.coverImage, post.getSourceId());
                itemHolder.sourceBadge.setVisibility(View.VISIBLE);
                itemHolder.sourceBadge.setContentDescription(post.getSourceLabel());
                MangaImageLoader.loadForSource(itemHolder.sourceBadge, MangaSourceFactory.localIconForSourceId(post.getSourceId()), post.getSourceId(), false, null);
            } else {
                MangaImageLoader.loadShimmerCover(itemHolder.cover, "", "");
                itemHolder.sourceBadge.setImageDrawable(null);
                itemHolder.sourceBadge.setVisibility(View.GONE);
            }
        }

        private void bindSkeleton(Holder holder) {
            MangaImageLoader.clear(holder.cover);
            ShimmerUtil.showCover(holder.cover);
            ShimmerUtil.show(holder.title, 0, 20, 5f, 0.82f, 2, false);
            ShimmerUtil.show(holder.chapter, 0, 16, 5f, 0.68f);
            ShimmerUtil.show(holder.count, 0, 14, 5f, 0.48f);
            holder.sourceBadge.setImageDrawable(null);
            holder.sourceBadge.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(null);
            holder.itemView.setClickable(false);
        }

        private void hideShimmer(Holder holder) {
            ShimmerUtil.hide(holder.title);
            ShimmerUtil.hide(holder.chapter);
            ShimmerUtil.hide(holder.count);
        }

        @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof Holder) {
                Holder itemHolder = (Holder) holder;
                hideShimmer(itemHolder);
                MangaImageLoader.clear(itemHolder.cover);
                MangaImageLoader.clear(itemHolder.sourceBadge);
                itemHolder.itemView.setOnClickListener(null);
            }
            super.onViewRecycled(holder);
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        static class HeaderHolder extends RecyclerView.ViewHolder {
            final TextView text;

            HeaderHolder(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.dayHeaderTextView);
            }
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final ImageView sourceBadge;
            final TextView title;
            final TextView chapter;
            final TextView count;

            Holder(@NonNull View itemView) {
                super(itemView);
                cover = itemView.findViewById(R.id.coverImageView);
                sourceBadge = itemView.findViewById(R.id.sourceBadgeImageView);
                title = itemView.findViewById(R.id.mangaTitleTextView);
                chapter = itemView.findViewById(R.id.chapterLabelTextView);
                count = itemView.findViewById(R.id.newCountTextView);
            }
        }
    }
}
