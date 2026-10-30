package miku.moe.app;

import android.app.Application;
import coil.Coil;
import coil.ImageLoader;
import coil.ComponentRegistry;
import coil.ImageLoaderFactory;
import coil.decode.GifDecoder;
import coil.decode.ImageDecoderDecoder;
import coil.disk.DiskCache;
import coil.memory.MemoryCache;
import android.os.Build;
import com.google.android.material.color.DynamicColors;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import java.io.File;
import java.util.concurrent.TimeUnit;

public class MikuMoeApplication extends Application implements ImageLoaderFactory {
    private static volatile OkHttpClient imageHttpClient;
    private static volatile Cache imageHttpCache;
    private static volatile ImageLoader installedImageLoader;

    static void evictImageConnections() {
        OkHttpClient client = imageHttpClient;
        if (client != null) client.connectionPool().evictAll();
    }

    static void trimImageCaches(android.content.Context context) {
        ImageLoader loader = installedImageLoader;
        if (loader == null) return;
        try {
            MemoryCache memoryCache = loader.getMemoryCache();
            if (memoryCache != null) memoryCache.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
        } catch (Throwable ignored) { }
    }

    static void clearImageCaches(android.content.Context context) {
        ImageLoader loader = installedImageLoader;
        if (loader != null) {
            try {
                MemoryCache memoryCache = loader.getMemoryCache();
                if (memoryCache != null) memoryCache.clear();
            } catch (Throwable ignored) { }
            try {
                DiskCache diskCache = loader.getDiskCache();
                if (diskCache != null) diskCache.clear();
            } catch (Throwable ignored) { }
        }
        Cache httpCache = imageHttpCache;
        if (httpCache != null) {
            try { httpCache.evictAll(); } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Proses :crash_report cuma menampilkan dialog log. Jangan pasang handler/inisialisasi
        // lagi di sana, supaya log crash asli tidak tertimpa dan prosesnya ringan.
        if (isCrashReportProcess()) return;
        CrashHandler.install(this);
        // Pelapor crash SILENT: membungkus CrashHandler di atas (reporter memproses &
        // mengirim log dulu, handler lama tetap dipanggil sesudahnya), terpasang sebelum
        // semua inisialisasi lain; flushPending mengirim ulang laporan yang belum terkirim.
        miku.moe.app.crash.CrashReporter.install(this);
        miku.moe.app.crash.CrashReporter.flushPending(this);
        // Crash native / dibunuh sistem (OOM-kill) tidak lewat UncaughtExceptionHandler;
        // alasannya baru bisa dibaca di start berikutnya lewat ApplicationExitInfo (API 30+).
        miku.moe.app.crash.CrashReporter.reportPreviousExit(this);
        // Base URL dinamis ANX gen-2: muat nilai tersimpan, lalu segarkan dari get_config (latar, aman gagal).
        miku.moe.app.api.AnxBaseUrl.init(this);
        miku.moe.app.api.AnxBaseUrl.refreshAsync(this);
        // Identitas perangkat sintetis ANX gen-2: daftarkan sekali per install (latar, aman gagal).
        miku.moe.app.api.AnxDeviceIdentity.getDeviceId(this);
        miku.moe.app.api.AnxDeviceIdentity.getAnxRt(this);
        miku.moe.app.api.AnxDeviceIdentity.ensureRegistered(this);
        // Token login ANX Model W: init context untuk AnxAuth (bearer + refresh).
        miku.moe.app.api.AnxAuth.init(this);
        MikuLifecycleKeepAlive.install(this);
        MangaSettingsManager.init(this);
        NetworkDohManager.init(this);
        ThemeManager.applyNightMode(this);
        CloudflareHelper.init(this);
        MangaHttpClient.init(this);
        Coil.setImageLoader(newImageLoader());
        MangaImageLoader.registerImageContext(this);
        MangaCacheController.cleanOnAppStart(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

    private boolean isCrashReportProcess() {
        try {
            String name = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : null;
            return name != null && name.endsWith(":crash_report");
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_UI_HIDDEN && MangaCacheController.hasActiveReaderSession()) return;
        if (level >= TRIM_MEMORY_UI_HIDDEN) MangaCacheController.trimMangaCache(this, level >= TRIM_MEMORY_MODERATE);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        MangaCacheController.trimMangaCache(this, true);
    }

    @Override
    public ImageLoader newImageLoader() {
        File imageCache = new File(getCacheDir(), "manga_image_cache");
        File httpCacheDir = new File(getCacheDir(), "manga_http_cache");
        Cache httpCache = new Cache(httpCacheDir, 24L * 1024L * 1024L);
        imageHttpCache = httpCache;
        OkHttpClient client = CloudflareHelper.apply(new OkHttpClient.Builder())
                .cache(httpCache)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        imageHttpClient = client;
        ComponentRegistry.Builder components = new ComponentRegistry.Builder();
        components.add(new AnimatedAvifDecoder.Factory());
        if (Build.VERSION.SDK_INT >= 28) components.add(new ImageDecoderDecoder.Factory());
        else components.add(new GifDecoder.Factory());
        ImageLoader loader = new ImageLoader.Builder(this)
                .components(components.build())
                .okHttpClient(client)
                .memoryCache(new MemoryCache.Builder(this).maxSizePercent(0.14).build())
                .diskCache(new DiskCache.Builder().directory(imageCache).maxSizeBytes(128L * 1024L * 1024L).build())
                .crossfade(false)
                .respectCacheHeaders(false)
                .allowHardware(false)
                .build();
        installedImageLoader = loader;
        return loader;
    }
}
