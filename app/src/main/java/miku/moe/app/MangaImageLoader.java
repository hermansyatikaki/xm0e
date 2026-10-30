package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import coil.Coil;
import coil.ImageLoader;
import coil.disk.DiskCache;
import coil.memory.MemoryCache;
import coil.request.ErrorResult;
import coil.request.ImageRequest;
import coil.request.Disposable;
import coil.request.CachePolicy;
import coil.request.SuccessResult;
import okhttp3.Headers;
import java.util.LinkedList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MangaImageLoader {
    private static final Map<ImageView, Disposable> ACTIVE_REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ImageView, RequestState> REQUEST_STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final LinkedHashMap<String, Disposable> ACTIVE_PRELOADS = new LinkedHashMap<>();
    private static final LinkedList<PreloadItem> PENDING_PRELOADS = new LinkedList<>();
    private static final HashSet<String> PENDING_PRELOAD_KEYS = new HashSet<>();
    private static final int MAX_ACTIVE_PRELOADS = 2;
    private static final int MAX_PENDING_PRELOADS = 24;
    private static volatile Context APPLICATION_CONTEXT;
    private static final int MAX_DIMENSION_CACHE = 2400;
    private static final int MAX_PERSISTED_SIZES = 4000;
    private static final int MAX_IMAGE_REFERERS = 1500;
    private static final String SIZE_PREFS = "miku_manga_image_sizes";
    private static final long SIZE_FLUSH_INTERVAL_MS = 4000L;
    private static final LinkedHashMap<String, int[]> DIMENSION_CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static final LinkedHashMap<String, String> IMAGE_REFERERS = new LinkedHashMap<>(256, 0.75f, true);
    private static final HashMap<String, String> PENDING_SIZES = new HashMap<>();
    private static final android.os.Handler SIZE_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile boolean sizeFlushScheduled = false;
    private static volatile boolean dimensionCacheLoaded = false;

    private static final android.os.Handler RETRY_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Map<ImageView, Object> READER_TOKENS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final java.util.Set<String> RECOVERY_KEYS = Collections.synchronizedSet(new HashSet<>());
    private static final java.util.Set<String> COVER_BYPASS_KEYS = Collections.synchronizedSet(new HashSet<>());
    private static final AtomicLong NEXT_GENERATION = new AtomicLong(1L);

    private static final Map<String, String> LAST_ERRORS = Collections.synchronizedMap(new LinkedHashMap<String, String>(64, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 300;
        }
    });

    private MangaImageLoader() {}

    private enum RequestOutcome {
        IDLE,
        LOADING,
        SUCCESS,
        ERROR,
        CANCELLED
    }

    private static final class RequestState {
        final String requestKey;
        final long generation;
        final RequestOutcome outcome;

        RequestState(String requestKey, long generation, RequestOutcome outcome) {
            this.requestKey = requestKey;
            this.generation = generation;
            this.outcome = outcome;
        }

        RequestState withOutcome(RequestOutcome outcome) {
            return new RequestState(requestKey, generation, outcome);
        }
    }

    private static final class RequestHandle {
        final String requestKey;
        final long generation;

        RequestHandle(String requestKey, long generation) {
            this.requestKey = requestKey;
            this.generation = generation;
        }
    }

    private static RequestHandle beginRequest(ImageView target, String requestKey) {
        long generation = NEXT_GENERATION.getAndIncrement();
        REQUEST_STATES.put(target, new RequestState(requestKey, generation, RequestOutcome.LOADING));
        return new RequestHandle(requestKey, generation);
    }

    private static boolean isCurrent(ImageView target, RequestHandle handle) {
        if (target == null || handle == null) return false;
        RequestState state = REQUEST_STATES.get(target);
        return state != null && state.generation == handle.generation && handle.requestKey.equals(state.requestKey);
    }

    private static void markOutcome(ImageView target, RequestHandle handle, RequestOutcome outcome) {
        if (!isCurrent(target, handle)) return;
        RequestState state = REQUEST_STATES.get(target);
        if (state != null) REQUEST_STATES.put(target, state.withOutcome(outcome));
    }

    private static void invalidateTarget(ImageView target) {
        if (target == null) return;
        RequestState state = REQUEST_STATES.get(target);
        if (state != null) REQUEST_STATES.put(target, state.withOutcome(RequestOutcome.CANCELLED));
        try {
            Disposable disposable = ACTIVE_REQUESTS.remove(target);
            if (disposable != null) disposable.dispose();
        } catch (Exception ignored) { }
    }

    public static String getLastError(String url, String sourceId) {
        if (url == null) return "";
        String value = LAST_ERRORS.get(cacheKey(url.trim(), sourceId));
        return value == null ? "" : value;
    }

    // Laporkan error tak terduga saja: error jaringan/HTTP biasa dilewati supaya tidak spam.
    private static void reportCaught(String tag, String sourceId, String url, Throwable error) {
        try {
            if (error == null) return;
            if (error instanceof java.io.IOException && !"bitmap null".equals(error.getMessage())
                    && !String.valueOf(error.getMessage()).startsWith("decode bounds")) return;
            if (error.getClass().getName().endsWith("HttpException")) return;
            if (error instanceof java.util.concurrent.CancellationException) return;
            String host = "?";
            try {
                String h = android.net.Uri.parse(url == null ? "" : url).getHost();
                if (h != null) host = h;
            } catch (Throwable ignored) { }
            miku.moe.app.crash.CrashReporter.logCaught(tag, "source=" + sourceId + " host=" + host, error);
        } catch (Throwable ignored) { }
    }

    private static void rememberError(String requestKey, String safeUrl, Throwable error) {
        reportCaught("coil-load", null, safeUrl, error);
        rememberErrorText(requestKey, safeUrl, describeError(error));
        android.util.Log.w("MangaImage", "gagal: " + describeError(error) + " | " + safeUrl, error);
    }

    private static void rememberErrorText(String requestKey, String safeUrl, String reason) {
        LAST_ERRORS.put(requestKey, reason == null ? "" : reason);
    }

    private static String describeError(Throwable error) {
        if (error == null) return "penyebab tidak diketahui";
        String name = error.getClass().getName();
        String message = error.getMessage() == null ? "" : error.getMessage().trim();
        String text;
        if (name.endsWith("HttpException")) text = message.isEmpty() ? "HTTP error" : message;
        else if (error instanceof java.net.SocketTimeoutException) text = "timeout (server lambat)";
        else if (error instanceof java.net.UnknownHostException) text = "DNS/koneksi gagal";
        else if (error instanceof javax.net.ssl.SSLException) text = "SSL: " + message;
        else if (error instanceof java.io.IOException) text = "jaringan: " + error.getClass().getSimpleName() + (message.isEmpty() ? "" : " - " + message);
        else text = error.getClass().getSimpleName() + (message.isEmpty() ? "" : " - " + message);
        return text.length() > 90 ? text.substring(0, 90) : text;
    }

    public interface Callback { void onSuccess(); void onError(); }

    public static void load(ImageView target, String url) { load(target, url, false, null); }
    public static void load(ImageView target, String url, boolean crossfade) { load(target, url, crossfade, null); }

    public static void load(ImageView target, String url, boolean crossfade, Callback callback) {
        loadForSource(target, url, null, crossfade, callback);
    }

    public static void loadForSource(ImageView target, String url, String sourceId, boolean crossfade, Callback callback) {
        loadForSourceInternal(target, url, sourceId, crossfade, callback, false, false, false, false, false);
    }

    public static void loadShimmerCover(ImageView target, String url, String sourceId) {
        loadShimmerCover(target, url, sourceId, null);
    }

    public static void loadShimmerCover(ImageView target, String url, String sourceId, Callback callback) {
        if (target != null) target.setBackground(null);
        loadForSourceInternal(target, url, sourceId, true, callback, false, false, true, false, false);
    }

    public static void loadCoverForSource(ImageView target, String url, String sourceId) {
        loadCoverForSource(target, url, sourceId, null);
    }

    public static void loadCoverForSource(ImageView target, String url, String sourceId, Callback callback) {
        loadForSourceInternal(target, url, sourceId, true, callback, false, false, false, false, false);
    }

    public static void loadCoverForSourceCachedOnly(ImageView target, String url, String sourceId) {
        loadCoverForSourceCachedOnly(target, url, sourceId, null);
    }

    public static void loadCoverForSourceCachedOnly(ImageView target, String url, String sourceId, Callback callback) {
        loadCoverForSourceInternal(target, url, sourceId, callback);
    }

    private static void loadCoverForSourceInternal(ImageView target, String url, String sourceId, Callback callback) {
        if (target == null) return;
        if (url == null || url.trim().isEmpty()) {
            target.setImageDrawable(null);
            if (callback != null) callback.onError();
            return;
        }
        Context context = target.getContext();
        registerImageContext(context);
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        RequestState existing = REQUEST_STATES.get(target);
        if (existing != null && requestKey.equals(existing.requestKey) && existing.outcome == RequestOutcome.SUCCESS && hasRealImage(target)) {
            target.animate().cancel();
            target.setAlpha(1f);
            if (callback != null) callback.onSuccess();
            return;
        }
        String cachedUrl = COVER_BYPASS_KEYS.contains(requestKey) ? null : MangaCoverCache.cachedUri(context, safeUrl);
        boolean local = isLocalUri(safeUrl);
        boolean usingCached = cachedUrl != null && !cachedUrl.trim().isEmpty();
        String resolvedUrl = resolveImageUrl(safeUrl, sourceId);
        String requestData = usingCached ? cachedUrl : resolvedUrl;
        cancelPreload(requestKey);
        cancel(target);
        target.animate().cancel();
        target.setAlpha(1f);
        target.setTag(requestKey);
        RequestHandle handle = beginRequest(target, requestKey);
        ImageRequest request = new ImageRequest.Builder(context)
                .data(requestData)
                .headers(usingCached || local ? new Headers.Builder().build() : requestHeaders(resolvedUrl, sourceId, false, false))
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .crossfade(usingCached || local ? 0 : 500)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.DISABLED)
                .allowHardware(true)
                .target(target)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) {
                        if (!isCurrent(target, handle)) return;
                        recordImageSize(requestKey, result.getDrawable());
                        markOutcome(target, handle, RequestOutcome.SUCCESS);
                        if (callback != null) callback.onSuccess();
                    }
                    @Override public void onError(ImageRequest request, ErrorResult result) {
                        if (!isCurrent(target, handle)) return;
                        markOutcome(target, handle, RequestOutcome.ERROR);
                        if (usingCached) {
                            try { MangaCoverCache.delete(context.getApplicationContext(), safeUrl); } catch (Exception ignored) { }
                        }
                        target.setImageDrawable(null);
                        if (callback != null) callback.onError();
                    }
                })
                .build();
        Disposable disposable = Coil.imageLoader(context.getApplicationContext()).enqueue(request);
        ACTIVE_REQUESTS.put(target, disposable);
    }

    public static void registerImageContext(Context context) {
        if (context == null) return;
        APPLICATION_CONTEXT = context.getApplicationContext();
        loadPersistedImageSizes(APPLICATION_CONTEXT);
    }

    private static boolean hasRealImage(ImageView target) {
        android.graphics.drawable.Drawable d = target.getDrawable();
        return d != null && !(d instanceof ShimmerDrawable) && !(d instanceof BrokenImageDrawable);
    }

    private static boolean isRequestInFlight(ImageView target) {
        Disposable active = ACTIVE_REQUESTS.get(target);
        return active != null && !active.isDisposed();
    }

    private static void showBroken(ImageView target) {
        target.animate().cancel();
        target.setAlpha(1f);
        target.setImageDrawable(new BrokenImageDrawable(target.getContext()));
    }

    private static void loadForSourceInternal(ImageView target, String url, String sourceId, boolean crossfade, Callback callback, boolean retryFromNetwork, boolean directHeaderFallback, boolean shimmer, boolean recovery, boolean bypassCoverCache) {
        if (target == null) return;
        if (url == null || url.trim().isEmpty()) {
            cancel(target);
            target.setTag(null);
            if (shimmer) showBroken(target);
            else target.setImageDrawable(null);
            if (callback != null) callback.onError();
            return;
        }
        Context context = target.getContext();
        registerImageContext(context);
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        boolean recoveryRequest = recovery || RECOVERY_KEYS.contains(requestKey);
        RequestState existing = REQUEST_STATES.get(target);
        if (!recoveryRequest && !retryFromNetwork && existing != null && requestKey.equals(existing.requestKey)) {
            if (existing.outcome == RequestOutcome.SUCCESS && hasRealImage(target)) {
                target.animate().cancel();
                target.setAlpha(1f);
                if (callback != null) callback.onSuccess();
                return;
            }
            if (shimmer && existing.outcome == RequestOutcome.ERROR && target.getDrawable() instanceof BrokenImageDrawable) {
                if (callback != null) callback.onError();
                return;
            }
            if (shimmer && existing.outcome == RequestOutcome.LOADING && isRequestInFlight(target)) return;
        }
        boolean skipCover = bypassCoverCache || retryFromNetwork || recoveryRequest || COVER_BYPASS_KEYS.contains(requestKey);
        String cachedUrl = skipCover ? null : MangaCoverCache.cachedUri(context, safeUrl);
        boolean local = isLocalUri(safeUrl);
        boolean usingCached = cachedUrl != null && !cachedUrl.trim().isEmpty();
        String resolvedUrl = resolveImageUrl(safeUrl, sourceId);
        String requestData = usingCached ? cachedUrl : resolvedUrl;
        cancelPreload(requestKey);
        cancel(target);
        target.animate().cancel();
        target.setTag(requestKey);
        RequestHandle handle = beginRequest(target, requestKey);
        CachePolicy readPolicy = recoveryRequest ? CachePolicy.WRITE_ONLY : CachePolicy.ENABLED;
        ImageRequest request = new ImageRequest.Builder(context)
                .placeholder(shimmer ? new ShimmerDrawable(context, 0f) : null)
                .data(requestData)
                .headers(usingCached || local ? new Headers.Builder().build() : applyCacheBypass(requestHeaders(resolvedUrl, sourceId, retryFromNetwork, directHeaderFallback), requestKey, recoveryRequest))
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .crossfade(usingCached || local ? 0 : (crossfade ? 500 : 0))
                .memoryCachePolicy(readPolicy)
                .diskCachePolicy(readPolicy)
                .networkCachePolicy(readPolicy)
                .allowHardware(allowHardwareForTarget(target))
                .target(target)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) {
                        if (!isCurrent(target, handle)) return;
                        recordImageSize(requestKey, result.getDrawable());
                        RECOVERY_KEYS.remove(requestKey);
                        COVER_BYPASS_KEYS.remove(requestKey);
                        markOutcome(target, handle, RequestOutcome.SUCCESS);
                        if (callback != null) callback.onSuccess();
                    }
                    @Override public void onError(ImageRequest request, ErrorResult result) {
                        if (!isCurrent(target, handle)) return;
                        markOutcome(target, handle, RequestOutcome.ERROR);
                        rememberError(requestKey, safeUrl, result.getThrowable());
                        if (usingCached && !retryFromNetwork) {
                            try { MangaCoverCache.delete(context.getApplicationContext(), safeUrl); } catch (Exception ignored) { }
                            loadForSourceInternal(target, safeUrl, sourceId, crossfade, callback, true, false, shimmer, recoveryRequest, true);
                            return;
                        }
                        if (!retryFromNetwork && isDoujinImageRequest(safeUrl, sourceId)) {
                            loadForSourceInternal(target, safeUrl, sourceId, crossfade, callback, true, false, shimmer, recoveryRequest, bypassCoverCache);
                            return;
                        }
                        if (!directHeaderFallback && shouldRetryWithDirectHeaders(resolvedUrl, sourceId, local)) {
                            loadForSourceInternal(target, safeUrl, sourceId, crossfade, callback, true, true, shimmer, recoveryRequest, bypassCoverCache);
                            return;
                        }
                        if (shimmer) showBroken(target);
                        if (callback != null) callback.onError();
                    }
                })
                .build();
        Disposable disposable = Coil.imageLoader(context.getApplicationContext()).enqueue(request);
        ACTIVE_REQUESTS.put(target, disposable);
    }

    public static void preload(Context context, String url, String sourceId) {
        enqueuePreload(context, url, sourceId, false);
    }

    public static void preloadPriority(Context context, String url, String sourceId) {
        enqueuePreload(context, url, sourceId, true);
    }

    private static void enqueuePreload(Context context, String url, String sourceId, boolean priority) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        if (RECOVERY_KEYS.contains(requestKey)) return;
        String cachedUrl = MangaCoverCache.cachedUri(app, safeUrl);
        if (cachedUrl != null && !cachedUrl.trim().isEmpty()) return;
        boolean startNow = false;
        synchronized (ACTIVE_PRELOADS) {
            if (ACTIVE_PRELOADS.containsKey(requestKey) || PENDING_PRELOAD_KEYS.contains(requestKey)) return;
            if (ACTIVE_PRELOADS.size() < MAX_ACTIVE_PRELOADS) {
                ACTIVE_PRELOADS.put(requestKey, null);
                startNow = true;
            } else {
                if (PENDING_PRELOADS.size() >= MAX_PENDING_PRELOADS) {
                    PreloadItem removed = PENDING_PRELOADS.pollLast();
                    if (removed != null) PENDING_PRELOAD_KEYS.remove(removed.requestKey);
                }
                PreloadItem item = new PreloadItem(app, safeUrl, sourceId, requestKey, priority);
                addPendingPreload(item, priority);
                PENDING_PRELOAD_KEYS.add(requestKey);
            }
        }
        if (startNow) startPreload(app, safeUrl, sourceId, requestKey, false);
    }

    private static void addPendingPreload(PreloadItem item, boolean priority) {
        if (item == null) return;
        if (!priority || PENDING_PRELOADS.isEmpty()) {
            PENDING_PRELOADS.addLast(item);
            return;
        }
        int insertIndex = 0;
        for (PreloadItem pending : PENDING_PRELOADS) {
            if (!pending.priority) break;
            insertIndex++;
        }
        PENDING_PRELOADS.add(insertIndex, item);
    }

    private static void startPreload(Context context, String safeUrl, String sourceId, String requestKey, boolean directHeaderFallback) {
        try {
            String resolvedUrl = resolveImageUrl(safeUrl, sourceId);
            ImageRequest request = new ImageRequest.Builder(context.getApplicationContext())
                    .data(resolvedUrl)
                    .headers(directHeaderFallback ? new Headers.Builder().build() : headersFor(resolvedUrl, sourceId))
                    .memoryCacheKey(requestKey)
                    .diskCacheKey(requestKey)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(true)
                    .listener(new ImageRequest.Listener() {
                        @Override public void onSuccess(ImageRequest request, SuccessResult result) {
                            recordImageSize(requestKey, result.getDrawable());
                            finishPreload(context, requestKey);
                        }
                        @Override public void onError(ImageRequest request, ErrorResult result) {
                            if (!directHeaderFallback && shouldRetryWithDirectHeaders(resolvedUrl, sourceId, isLocalUri(safeUrl))) {
                                startPreload(context, safeUrl, sourceId, requestKey, true);
                                return;
                            }
                            finishPreload(context, requestKey);
                        }
                    })
                    .build();
            Disposable disposable = Coil.imageLoader(context.getApplicationContext()).enqueue(request);
            attachPreload(requestKey, disposable);
        } catch (Exception ignored) {
            finishPreload(context, requestKey);
        }
    }

    public static boolean isCached(Context context, String url, String sourceId) {
        if (context == null || url == null || url.trim().isEmpty()) return false;
        Context app = context.getApplicationContext();
        String safeUrl = url.trim();
        String cachedUrl = MangaCoverCache.cachedUri(app, safeUrl);
        if (cachedUrl != null && !cachedUrl.trim().isEmpty()) return true;
        return hasMemoryCache(app, cacheKey(safeUrl, sourceId));
    }

    private static boolean hasMemoryCache(Context context, String requestKey) {
        if (context == null || requestKey == null || requestKey.trim().isEmpty()) return false;
        try {
            MemoryCache cache = Coil.imageLoader(context.getApplicationContext()).getMemoryCache();
            if (cache == null) return false;
            return cache.get(new MemoryCache.Key(requestKey.trim(), Collections.emptyMap())) != null;
        } catch (Exception ignored) { }
        return false;
    }

    private static void attachPreload(String requestKey, Disposable disposable) {
        if (requestKey == null || requestKey.trim().isEmpty()) return;
        synchronized (ACTIVE_PRELOADS) {
            if (ACTIVE_PRELOADS.containsKey(requestKey)) ACTIVE_PRELOADS.put(requestKey, disposable);
            else if (disposable != null) disposable.dispose();
        }
    }

    private static void finishPreload(Context context, String requestKey) {
        if (requestKey == null || requestKey.trim().isEmpty()) return;
        PreloadItem next = null;
        synchronized (ACTIVE_PRELOADS) {
            ACTIVE_PRELOADS.remove(requestKey);
            while (!PENDING_PRELOADS.isEmpty()) {
                next = PENDING_PRELOADS.pollFirst();
                if (next == null) continue;
                PENDING_PRELOAD_KEYS.remove(next.requestKey);
                if (ACTIVE_PRELOADS.containsKey(next.requestKey)) {
                    next = null;
                    continue;
                }
                ACTIVE_PRELOADS.put(next.requestKey, null);
                break;
            }
        }
        if (next != null) startPreload(next.context, next.url, next.sourceId, next.requestKey, false);
    }

    public static void preloadRange(Context context, java.util.List<String> pages, String sourceId, int center, int distance) {
        if (context == null || pages == null || pages.isEmpty()) return;
        int start = Math.max(0, center);
        int end = Math.min(pages.size() - 1, center + Math.max(1, distance));
        for (int i = start; i <= end; i++) preload(context, pages.get(i), sourceId);
    }

    public static void preloadRangePriority(Context context, java.util.List<String> pages, String sourceId, int start, int end) {
        if (context == null || pages == null || pages.isEmpty()) return;
        int safeStart = Math.max(0, Math.min(start, pages.size() - 1));
        int safeEnd = Math.max(safeStart, Math.min(end, pages.size() - 1));
        for (int i = safeStart; i <= safeEnd; i++) preloadPriority(context, pages.get(i), sourceId);
    }

    public static void preloadReaderPage(Context context, String url, String sourceId) {
        if (context == null || url == null || url.trim().isEmpty() || isLocalUri(url)) return;
        Context app = context.getApplicationContext();
        String safeUrl = url.trim();
        if (MangaChapterPageStore.isReady(app, safeUrl, sourceId)) return;
        String requestKey = cacheKey(safeUrl, sourceId);
        synchronized (PAGE_WAITERS) {
            if (PAGE_DOWNLOADS_ACTIVE.contains(requestKey)) return;
            PAGE_DOWNLOADS_ACTIVE.add(requestKey);
        }
        startReaderPageDownload(app, safeUrl, sourceId, requestKey, PAGE_PRIORITY_PRELOAD);
    }

    public static void preloadReaderRange(Context context, java.util.List<String> pages, String sourceId, int center, int distance) {
        if (context == null || pages == null || pages.isEmpty()) return;
        int start = Math.max(0, center);
        int end = Math.min(pages.size() - 1, center + Math.max(1, distance));
        for (int i = start; i <= end; i++) preloadReaderPage(context, pages.get(i), sourceId);
    }

    private static boolean allowHardwareForTarget(ImageView target) {
        if (target instanceof MangaWebtoonImageView) return !((MangaWebtoonImageView) target).isCropBorderEnabled();
        return true;
    }

    private static boolean isLocalUri(String value) {
        if (value == null) return false;
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("android.resource://") || lower.startsWith("file://") || lower.startsWith("content://");
    }

    private static final class PreloadItem {
        final Context context;
        final String url;
        final String sourceId;
        final String requestKey;
        final boolean priority;

        PreloadItem(Context context, String url, String sourceId, String requestKey, boolean priority) {
            this.context = context;
            this.url = url;
            this.sourceId = sourceId;
            this.requestKey = requestKey;
            this.priority = priority;
        }
    }

    public static int[] getKnownSize(String url, String sourceId) {
        if (url == null || url.trim().isEmpty()) return null;
        String requestKey = cacheKey(url.trim(), sourceId);
        synchronized (DIMENSION_CACHE) {
            int[] size = DIMENSION_CACHE.get(requestKey);
            if (size == null || size.length < 2) return null;
            return new int[]{size[0], size[1]};
        }
    }

    private static void recordImageSize(String requestKey, Drawable drawable) {
        if (requestKey == null || requestKey.trim().isEmpty() || drawable == null) return;
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) return;
        boolean changed;
        synchronized (DIMENSION_CACHE) {
            int[] stored = DIMENSION_CACHE.get(requestKey);
            changed = stored == null || stored.length < 2 || stored[0] != width || stored[1] != height;
            DIMENSION_CACHE.put(requestKey, new int[]{width, height});
            while (DIMENSION_CACHE.size() > MAX_DIMENSION_CACHE) {
                String firstKey = DIMENSION_CACHE.keySet().iterator().next();
                DIMENSION_CACHE.remove(firstKey);
            }
        }
        if (changed) queueSizeForStorage(requestKey, width, height);
    }

    private static void queueSizeForStorage(String requestKey, int width, int height) {
    }

    private static void flushImageSizes() {
    }

    static void loadPersistedImageSizes(Context context) {
        dimensionCacheLoaded = true;
    }

    public static void trimImageCaches(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try {
            MemoryCache cache = Coil.imageLoader(app).getMemoryCache();
            if (cache != null) cache.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
        } catch (Throwable ignored) { }
    }

    private static void cancelPreload(String requestKey) {
        if (requestKey == null || requestKey.trim().isEmpty()) return;
        synchronized (ACTIVE_PRELOADS) {
            Disposable active = ACTIVE_PRELOADS.remove(requestKey);
            if (active != null) active.dispose();
            java.util.Iterator<PreloadItem> iterator = PENDING_PRELOADS.iterator();
            while (iterator.hasNext()) {
                PreloadItem item = iterator.next();
                if (requestKey.equals(item.requestKey)) {
                    iterator.remove();
                    PENDING_PRELOAD_KEYS.remove(requestKey);
                    break;
                }
            }
        }
        pumpPreloads();
    }

    private static void pumpPreloads() {
        PreloadItem next = null;
        synchronized (ACTIVE_PRELOADS) {
            if (ACTIVE_PRELOADS.size() >= MAX_ACTIVE_PRELOADS) return;
            while (!PENDING_PRELOADS.isEmpty()) {
                PreloadItem candidate = PENDING_PRELOADS.pollFirst();
                if (candidate == null) continue;
                PENDING_PRELOAD_KEYS.remove(candidate.requestKey);
                if (ACTIVE_PRELOADS.containsKey(candidate.requestKey)) continue;
                ACTIVE_PRELOADS.put(candidate.requestKey, null);
                next = candidate;
                break;
            }
        }
        if (next != null) startPreload(next.context, next.url, next.sourceId, next.requestKey, false);
    }

    public static void registerImageReferer(String imageUrl, String referer) {
        String image = imageUrl == null ? "" : imageUrl.trim();
        // Referer yang disimpan tidak boleh mengandung karakter non-ASCII
        // mentah (mis. slug "37-5℃-hidden-love"): nilai itu nanti dipasang
        // sebagai header OkHttp dan bikin crash IllegalArgumentException.
        // Encode sekali di pintu masuk supaya semua pemakai aman.
        String page = MangaSourceImageStrategy.encodeHeaderUrl(referer);
        if (image.isEmpty() || page.isEmpty()) return;
        synchronized (IMAGE_REFERERS) {
            IMAGE_REFERERS.put(image, page);
            while (IMAGE_REFERERS.size() > MAX_IMAGE_REFERERS) {
                String first = IMAGE_REFERERS.keySet().iterator().next();
                IMAGE_REFERERS.remove(first);
            }
        }
    }

    private static String registeredImageReferer(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return "";
        synchronized (IMAGE_REFERERS) {
            String value = IMAGE_REFERERS.get(imageUrl.trim());
            return value == null ? "" : MangaSourceImageStrategy.encodeHeaderUrl(value);
        }
    }

    private static String sourceIdKey(String sourceId) { return sourceId == null ? "" : sourceId.trim(); }

    public static String imageCacheKey(String url, String sourceId) { return cacheKey(url, sourceId); }

    public static String resolveImageUrl(String url, String sourceId) {
        return MangaSourceImageStrategy.resolveImageUrl(url, sourceId);
    }

    private static String cacheKey(String url, String sourceId) { return sourceIdKey(sourceId) + "|" + (url == null ? "" : url.trim()); }

    public static boolean isLoaded(ImageView target, String url, String sourceId) {
        if (target == null || url == null || url.trim().isEmpty()) return false;
        String requestKey = cacheKey(url.trim(), sourceId);
        RequestState state = REQUEST_STATES.get(target);
        if (state == null) return false;
        if (!requestKey.equals(state.requestKey)) return false;
        if (state.outcome != RequestOutcome.SUCCESS) return false;
        Drawable drawable = target.getDrawable();
        return drawable != null
                && !(drawable instanceof ShimmerDrawable)
                && !(drawable instanceof BrokenImageDrawable);
    }

    private static boolean shouldRetryWithDirectHeaders(String url, String sourceId, boolean local) {
        return MangaSourceImageStrategy.shouldRetryWithDirectHeaders(url, sourceId, local);
    }

    private static Headers requestHeaders(String url, String sourceId, boolean directRetry, boolean directHeaderFallback) {
        return MangaSourceImageStrategy.requestHeaders(url, sourceId, directRetry, directHeaderFallback, registeredImageReferer(url));
    }

    private static boolean isDoujinImageRequest(String url, String sourceId) {
        return MangaSourceImageStrategy.isDoujinImageRequest(url, sourceId);
    }

    public static Headers headersFor(String url, String sourceId) {
        return MangaSourceImageStrategy.headersFor(url, sourceId, registeredImageReferer(url));
    }

    public static void cancel(ImageView target) {
        if (target == null) return;
        invalidateTarget(target);
    }

    public static void cancelPreloads() {
        try {
            synchronized (ACTIVE_PRELOADS) {
                for (Disposable disposable : new java.util.ArrayList<>(ACTIVE_PRELOADS.values())) if (disposable != null) disposable.dispose();
                ACTIVE_PRELOADS.clear();
                PENDING_PRELOADS.clear();
                PENDING_PRELOAD_KEYS.clear();
            }
        } catch (Exception ignored) { }
    }

    public static void recycle(ImageView target) {
        if (target == null) return;
        READER_TOKENS.remove(target);
        invalidateTarget(target);
        REQUEST_STATES.remove(target);
        target.animate().cancel();
        target.setAlpha(1f);
        target.setTag(null);
    }

    public static void clear(ImageView target) {
        if (target == null) return;
        recycle(target);
        target.setImageDrawable(null);
    }

    public static void cancelReaderPage(ImageView target) {
        if (target == null) return;
        READER_TOKENS.remove(target);
        invalidateTarget(target);
        REQUEST_STATES.remove(target);
    }

    public static void clearImageCache(Context context, List<String> urls, String sourceId) {
        clearImageCache(context, urls, sourceId, true);
    }

    public static void releaseImageMemoryCache(Context context, List<String> urls, String sourceId) {
        clearImageCache(context, urls, sourceId, false);
    }

    private static void clearImageCache(Context context, List<String> urls, String sourceId, boolean includeDiskCache) {
        if (context == null || urls == null || urls.isEmpty()) return;
        Context app = context.getApplicationContext();
        registerImageContext(app);
        HashSet<String> keys = new HashSet<>();
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;
            String safeUrl = url.trim();
            String key = cacheKey(safeUrl, sourceId);
            keys.add(key);
            keys.add(safeUrl);
            if (includeDiskCache) {
                COVER_BYPASS_KEYS.add(key);
                try { MangaChapterPageStore.delete(app, safeUrl, sourceId); } catch (Exception ignored) { }
                String cachedUrl = MangaCoverCache.cachedUri(app, safeUrl);
                if (cachedUrl != null && !cachedUrl.trim().isEmpty()) keys.add(cachedUrl.trim());
                MangaCoverCache.delete(app, safeUrl);
            }
        }
        if (includeDiskCache) {
            try {
                DiskCache diskCache = Coil.imageLoader(app).getDiskCache();
                if (diskCache != null) {
                    for (String key : keys) {
                        if (key != null && !key.trim().isEmpty()) {
                            try { diskCache.remove(key.trim()); } catch (Exception ignored) { }
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        removeMemoryCacheKeys(app, keys);
    }

    private static void removeMemoryCacheKeys(Context context, java.util.Set<String> keys) {
        if (context == null || keys == null || keys.isEmpty()) return;
        try {
            MemoryCache cache = Coil.imageLoader(context.getApplicationContext()).getMemoryCache();
            if (cache == null) return;
            for (String key : keys) {
                if (key == null || key.trim().isEmpty()) continue;
                try { cache.remove(new MemoryCache.Key(key.trim(), Collections.emptyMap())); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private static Headers applyCacheBypass(Headers headers, String requestKey, boolean recoveryRequest) {
        if (headers == null || requestKey == null) return headers;
        if (!recoveryRequest && !RECOVERY_KEYS.contains(requestKey)) return headers;
        return headers.newBuilder().set("Cache-Control", "no-cache").set("Pragma", "no-cache").build();
    }

    private static void purgeImage(Context app, String safeUrl, String sourceId) {
        String key = cacheKey(safeUrl, sourceId);
        cancelPreload(key);
        if (RECOVERY_KEYS.size() > 600) RECOVERY_KEYS.clear();
        RECOVERY_KEYS.add(key);
        COVER_BYPASS_KEYS.add(key);
        try { MangaChapterPageStore.delete(app, safeUrl, sourceId); } catch (Exception ignored) { }
        clearImageCache(app, java.util.Collections.singletonList(safeUrl), sourceId, true);
    }

    private static final int PAGE_PRIORITY_VISIBLE = 10;
    private static final int PAGE_PRIORITY_PRELOAD = 0;

    private static final class ReaderDownloadTask implements Runnable, Comparable<ReaderDownloadTask> {
        final int priority;
        final long sequence;
        final Runnable body;

        ReaderDownloadTask(int priority, long sequence, Runnable body) {
            this.priority = priority;
            this.sequence = sequence;
            this.body = body;
        }

        @Override public void run() {
            body.run();
        }

        @Override public int compareTo(ReaderDownloadTask other) {
            if (priority != other.priority) return Integer.compare(other.priority, priority);
            return Long.compare(sequence, other.sequence);
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong PAGE_TASK_SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor PAGE_DOWNLOAD_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            3, 3, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.PriorityBlockingQueue<>());
    private static final Map<String, java.util.ArrayList<PageWaiter>> PAGE_WAITERS = new HashMap<>();
    private static final HashSet<String> PAGE_DOWNLOADS_ACTIVE = new HashSet<>();

    private static final class PageWaiter {
        final ImageView target;
        final String url;
        final String sourceId;
        final Callback callback;
        final int attempt;
        final Object token;
        final RequestHandle handle;

        PageWaiter(ImageView target, String url, String sourceId, Callback callback, int attempt, Object token, RequestHandle handle) {
            this.target = target;
            this.url = url;
            this.sourceId = sourceId;
            this.callback = callback;
            this.attempt = attempt;
            this.token = token;
            this.handle = handle;
        }
    }

    public static void loadReaderPage(ImageView target, String url, String sourceId, Callback callback) {
        if (target == null) return;
        if (url == null || url.trim().isEmpty() || isLocalUri(url)) {
            loadForSource(target, url, sourceId, false, callback);
            return;
        }
        Object token = new Object();
        READER_TOKENS.put(target, token);
        loadReaderPageAttempt(target, url.trim(), sourceId, callback, 0, token);
    }

    private static void loadReaderPageAttempt(ImageView target, String safeUrl, String sourceId, Callback callback, int attempt, Object token) {
        Context context = target.getContext();
        if (context == null) return;
        Context app = context.getApplicationContext();
        String requestKey = cacheKey(safeUrl, sourceId);
        target.setTag(requestKey);
        RequestHandle handle = beginRequest(target, requestKey);
        if (MangaChapterPageStore.isReady(app, safeUrl, sourceId)) {
            displayReaderPageFile(target, safeUrl, sourceId, callback, attempt, token, handle);
            return;
        }
        PageWaiter waiter = new PageWaiter(target, safeUrl, sourceId, callback, attempt, token, handle);
        boolean startDownload = false;
        synchronized (PAGE_WAITERS) {
            java.util.ArrayList<PageWaiter> waiters = PAGE_WAITERS.get(requestKey);
            if (waiters == null) {
                waiters = new java.util.ArrayList<>();
                PAGE_WAITERS.put(requestKey, waiters);
            }
            waiters.add(waiter);
            if (!PAGE_DOWNLOADS_ACTIVE.contains(requestKey)) {
                PAGE_DOWNLOADS_ACTIVE.add(requestKey);
                startDownload = true;
            }
        }
        if (startDownload) startReaderPageDownload(app, safeUrl, sourceId, requestKey);
    }

    private static void startReaderPageDownload(Context app, String safeUrl, String sourceId, String requestKey) {
        startReaderPageDownload(app, safeUrl, sourceId, requestKey, PAGE_PRIORITY_VISIBLE);
    }

    private static void startReaderPageDownload(Context app, String safeUrl, String sourceId, String requestKey, int priority) {
        PAGE_DOWNLOAD_EXECUTOR.execute(new ReaderDownloadTask(priority, PAGE_TASK_SEQUENCE.getAndIncrement(), () -> {
            MangaChapterPageStore.Result result = null;
            String errorReason = null;
            try {
                result = MangaChapterPageStore.download(app, safeUrl, sourceId);
            } catch (MangaChapterPageStore.DownloadException e) {
                errorReason = e.getMessage();
            } catch (Throwable e) {
                errorReason = describeError(e);
                reportCaught("reader-download", sourceId, safeUrl, e);
            }
            final MangaChapterPageStore.Result finalResult = result;
            final String finalError = errorReason;
            RETRY_HANDLER.post(() -> drainReaderPageWaiters(app, safeUrl, sourceId, requestKey, finalResult, finalError));
        }));
    }

    private static void drainReaderPageWaiters(Context app, String safeUrl, String sourceId, String requestKey, MangaChapterPageStore.Result result, String errorReason) {
        java.util.ArrayList<PageWaiter> waiters;
        synchronized (PAGE_WAITERS) {
            waiters = PAGE_WAITERS.remove(requestKey);
            PAGE_DOWNLOADS_ACTIVE.remove(requestKey);
        }
        if (waiters == null) return;
        for (PageWaiter waiter : waiters) {
            if (READER_TOKENS.get(waiter.target) != waiter.token) continue;
            if (!isCurrent(waiter.target, waiter.handle)) continue;
            if (result != null) {
                displayReaderPageFile(waiter.target, safeUrl, sourceId, waiter.callback, waiter.attempt, waiter.token, waiter.handle);
            } else {
                rememberErrorText(requestKey, safeUrl, errorReason == null ? "penyebab tidak diketahui" : errorReason);
                handleReaderPageFailure(waiter.target, safeUrl, sourceId, waiter.callback, waiter.attempt, waiter.token);
            }
        }
    }

    private static final java.util.concurrent.ThreadPoolExecutor PAGE_DISPLAY_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.PriorityBlockingQueue<>());

    // Batas decode halaman reader. Tanpa batas, halaman webtoon yang sangat tinggi jadi
    // bitmap raksasa (hardware bitmap ada di luar heap Java -> tidak ada OOM yang bisa
    // ditangkap, proses langsung dibunuh sistem / crash native di RenderThread).
    private static final long READER_MAX_PIXELS = 16L * 1024L * 1024L; // ~64MB ARGB_8888
    private static final int READER_MAX_HW_EDGE = 4096; // aman untuk batas tekstur GPU

    private static int[] readerTargetSize(java.io.File file) {
        int w = -1, h = -1;
        try {
            android.graphics.BitmapFactory.Options b = new android.graphics.BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), b);
            w = b.outWidth;
            h = b.outHeight;
        } catch (Throwable ignored) { }
        if (w <= 0 || h <= 0) return new int[]{2048, 8192};
        long px = (long) w * (long) h;
        if (px > READER_MAX_PIXELS) {
            double scale = Math.sqrt(READER_MAX_PIXELS / (double) px);
            w = Math.max(1, (int) Math.floor(w * scale));
            h = Math.max(1, (int) Math.floor(h * scale));
        }
        return new int[]{w, h};
    }

    private static void displayReaderPageFile(ImageView target, String safeUrl, String sourceId, Callback callback, int attempt, Object token, RequestHandle handle) {
        Context context = target.getContext();
        if (context == null) return;
        Context app = context.getApplicationContext();
        java.io.File file = MangaChapterPageStore.fileFor(app, safeUrl, sourceId);
        if (file == null) {
            handleReaderPageFailure(target, safeUrl, sourceId, callback, attempt, token);
            return;
        }
        String requestKey = cacheKey(safeUrl, sourceId);
        int[] reqSize = readerTargetSize(file);
        boolean hardwareOk = allowHardwareForTarget(target) && reqSize[0] <= READER_MAX_HW_EDGE && reqSize[1] <= READER_MAX_HW_EDGE;
        ImageRequest request = new ImageRequest.Builder(context)
                .data(file)
                .size(reqSize[0], reqSize[1])
                .scale(coil.size.Scale.FIT)
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .crossfade(0)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .networkCachePolicy(CachePolicy.DISABLED)
                .allowHardware(hardwareOk)
                .target(target)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) {
                        if (!isCurrent(target, handle)) return;
                        recordImageSize(requestKey, result.getDrawable());
                        markOutcome(target, handle, RequestOutcome.SUCCESS);
                        if (READER_TOKENS.get(target) == token && callback != null) callback.onSuccess();
                    }
                    @Override public void onError(ImageRequest request, ErrorResult result) {
                        if (!isCurrent(target, handle)) return;
                        displayReaderPageFileManual(target, safeUrl, sourceId, callback, attempt, token, handle);
                    }
                })
                .build();
        Disposable disposable = Coil.imageLoader(app).enqueue(request);
        ACTIVE_REQUESTS.put(target, disposable);
    }

    private static void displayReaderPageFileManual(ImageView target, String safeUrl, String sourceId, Callback callback, int attempt, Object token, RequestHandle handle) {
        Context context = target.getContext();
        if (context == null) return;
        Context app = context.getApplicationContext();
        java.io.File file = MangaChapterPageStore.fileFor(app, safeUrl, sourceId);
        if (file == null) {
            handleReaderPageFailure(target, safeUrl, sourceId, callback, attempt, token);
            return;
        }
        String requestKey = cacheKey(safeUrl, sourceId);
        PAGE_DISPLAY_EXECUTOR.execute(new ReaderDownloadTask(15, PAGE_TASK_SEQUENCE.getAndIncrement(), () -> {
            android.graphics.Bitmap bitmap = null;
            String decodeError = null;
            try {
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("decode bounds kosong");
                int sample = 1;
                while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 16384 || ((long) (bounds.outWidth / sample) * (long) (bounds.outHeight / sample)) > READER_MAX_PIXELS) sample *= 2;
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inSampleSize = sample;
                options.inScaled = false;
                options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
                bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (bitmap == null) throw new java.io.IOException("bitmap null");
            } catch (Throwable e) {
                decodeError = describeError(e);
                reportCaught("reader-decode", sourceId, safeUrl, e);
            }
            final android.graphics.Bitmap finalBitmap = bitmap;
            final String finalError = decodeError;
            RETRY_HANDLER.post(() -> {
                if (!isCurrent(target, handle) || READER_TOKENS.get(target) != token) {
                    if (finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
                    return;
                }
                if (finalBitmap == null) {
                    rememberErrorText(requestKey, safeUrl, "Gambar rusak: " + (finalError == null ? "decode gagal" : finalError));
                    MangaChapterPageStore.delete(app, safeUrl, sourceId);
                    markOutcome(target, handle, RequestOutcome.ERROR);
                    handleReaderPageFailure(target, safeUrl, sourceId, callback, attempt, token);
                    return;
                }
                target.setImageBitmap(finalBitmap);
                recordImageSize(requestKey, target.getDrawable());
                markOutcome(target, handle, RequestOutcome.SUCCESS);
                if (callback != null) callback.onSuccess();
            });
        }));
    }

    private static void handleReaderPageFailure(ImageView target, String safeUrl, String sourceId, Callback callback, int attempt, Object token) {
        if (READER_TOKENS.get(target) != token) return;
        if (callback != null) callback.onError();
    }

    public static void forceReloadReaderPage(ImageView target, String url, String sourceId, Callback callback) {
        if (target == null || url == null || url.trim().isEmpty()) return;
        final Context app = target.getContext().getApplicationContext();
        final String safeUrl = url.trim();
        cancelReaderPage(target);
        final Object token = new Object();
        READER_TOKENS.put(target, token);
        target.setTag(null);
        MangaCoroutines.io(() -> {
            MangaChapterPageStore.delete(app, safeUrl, sourceId);
            purgeImage(app, safeUrl, sourceId);
            MangaCoroutines.main(() -> {
                if (READER_TOKENS.get(target) != token) return;
                loadReaderPageAttempt(target, safeUrl, sourceId, callback, 0, token);
            });
        });
    }

    public static void hardRefreshImages(Context context, List<String> urls, String sourceId) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        cancelPreloads();
        if (urls != null) {
            if (RECOVERY_KEYS.size() + urls.size() > 600) RECOVERY_KEYS.clear();
            for (String url : urls) {
                if (url == null || url.trim().isEmpty()) continue;
                String key = cacheKey(url.trim(), sourceId);
                RECOVERY_KEYS.add(key);
                COVER_BYPASS_KEYS.add(key);
                try { MangaChapterPageStore.delete(app, url.trim(), sourceId); } catch (Exception ignored) { }
            }
            clearImageCache(app, urls, sourceId, true);
        }
        try { MikuMoeApplication.evictImageConnections(); } catch (Throwable ignored) { }
    }

    public static void clearMemoryCache(Context context) {
        if (context == null) return;
        try {
            MemoryCache cache = Coil.imageLoader(context.getApplicationContext()).getMemoryCache();
            if (cache != null) cache.clear();
        } catch (Exception ignored) { }
    }
}
