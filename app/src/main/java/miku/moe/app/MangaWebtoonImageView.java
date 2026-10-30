package miku.moe.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatImageView;
import java.util.HashSet;
import java.util.WeakHashMap;

public class MangaWebtoonImageView extends AppCompatImageView {
    private static final WeakHashMap<Bitmap, Rect> CROP_CACHE = new WeakHashMap<>();
    private static final HashSet<String> PENDING_CROP = new HashSet<>();
    private static final java.util.concurrent.ExecutorService CROP_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final android.os.Handler POST_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final float MAX_CROP_FRACTION = 0.07f;
    private static final float KEEP_GAP_FRACTION = 0.012f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect cropRect = new Rect();
    private final Rect fullRect = new Rect();
    private final Rect drawRect = new Rect();
    private boolean cropBorderEnabled;
    private String readerScaleType = MangaSettingsManager.IMAGE_SCALE_FIT_WIDTH;
    private int lastBitmapWidth = -1;
    private int lastBitmapHeight = -1;
    private boolean cropReady;
    private int expectedImageWidth = -1;
    private int expectedImageHeight = -1;

    public MangaWebtoonImageView(Context context) {
        super(context);
        init();
    }

    public MangaWebtoonImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MangaWebtoonImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setAdjustViewBounds(false);
        setScaleType(ScaleType.FIT_CENTER);
        setCropToPadding(false);
        setWillNotCacheDrawing(true);
    }

    public void setCropBorderEnabled(boolean enabled) {
        cropBorderEnabled = enabled;
        cropReady = false;
        requestLayout();
        invalidate();
    }

    public boolean isCropBorderEnabled() {
        return cropBorderEnabled;
    }

    public void setExpectedImageSize(int width, int height) {
        int safeWidth = width > 0 ? width : -1;
        int safeHeight = height > 0 ? height : -1;
        if (expectedImageWidth == safeWidth && expectedImageHeight == safeHeight) return;
        expectedImageWidth = safeWidth;
        expectedImageHeight = safeHeight;
        requestLayout();
    }

    public void setReaderScaleType(String type) {
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(type) || MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(type)) readerScaleType = type;
        else readerScaleType = MangaSettingsManager.IMAGE_SCALE_FIT_WIDTH;
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable drawable = getDrawable();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int imageWidth = drawable == null ? expectedImageWidth : drawable.getIntrinsicWidth();
        int imageHeight = drawable == null ? expectedImageHeight : drawable.getIntrinsicHeight();
        if (cropBorderEnabled && cropReady && cropRect.width() > 0 && cropRect.height() > 0) {
            imageWidth = cropRect.width();
            imageHeight = cropRect.height();
        }
        int height;
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)) {
            height = Math.max(dp(240), getResources().getDisplayMetrics().heightPixels);
        } else if (MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(readerScaleType) && imageWidth > 0 && imageHeight > 0) {
            float density = getResources().getDisplayMetrics().density;
            int targetWidth = Math.min(width, Math.max(1, Math.round(imageWidth / Math.max(1f, density))));
            height = Math.round(targetWidth * (imageHeight / (float) imageWidth));
        } else if (imageWidth > 0 && imageHeight > 0) {
            height = Math.round(width * (imageHeight / (float) imageWidth));
        } else {
            height = dp(520);
        }
        if (height <= 0) height = dp(520);
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!cropBorderEnabled && !MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)) {
            super.onDraw(canvas);
            return;
        }
        Bitmap bitmap = getBitmap();
        if (bitmap == null || bitmap.isRecycled()) {
            super.onDraw(canvas);
            return;
        }
        Rect src;
        if (cropBorderEnabled) {
            src = getCropRectSafely();
        } else {
            fullRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
            src = fullRect;
        }
        if (src.width() <= 0 || src.height() <= 0) {
            super.onDraw(canvas);
            return;
        }
        makeDrawRect(src);
        try {
            canvas.drawBitmap(bitmap, src, drawRect, paint);
        } catch (Exception e) {
            super.onDraw(canvas);
        }
    }

    @Override public void setImageDrawable(Drawable drawable) {
        int oldWidth = expectedImageWidth;
        int oldHeight = expectedImageHeight;
        int previousHeight = measuredHeightFor(oldWidth, oldHeight, Math.max(1, getWidth()));
        super.setImageDrawable(drawable);
        cropReady = false;
        lastBitmapWidth = -1;
        lastBitmapHeight = -1;
        if (drawable != null && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0) {
            expectedImageWidth = drawable.getIntrinsicWidth();
            expectedImageHeight = drawable.getIntrinsicHeight();
        }
        if (oldWidth == expectedImageWidth && oldHeight == expectedImageHeight) {
            invalidate();
            return;
        }
        int nextHeight = measuredHeightFor(expectedImageWidth, expectedImageHeight, Math.max(1, getWidth()));
        if (!cropBorderEnabled && previousHeight > 0 && nextHeight > 0 && Math.abs(previousHeight - nextHeight) <= 1) {
            invalidate();
            return;
        }
        requestLayout();
    }

    private int measuredHeightFor(int imageWidth, int imageHeight, int viewWidth) {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0) return -1;
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)) return Math.max(dp(240), getResources().getDisplayMetrics().heightPixels);
        if (MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(readerScaleType)) {
            float density = getResources().getDisplayMetrics().density;
            int targetWidth = Math.min(viewWidth, Math.max(1, Math.round(imageWidth / Math.max(1f, density))));
            return Math.max(1, Math.round(targetWidth * (imageHeight / (float) imageWidth)));
        }
        return Math.max(1, Math.round(viewWidth * (imageHeight / (float) imageWidth)));
    }

    private void makeDrawRect(Rect src) {
        int viewWidth = Math.max(1, getWidth());
        int viewHeight = Math.max(1, getHeight());
        float scale;
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)) {
            scale = Math.min(viewWidth / (float) src.width(), viewHeight / (float) src.height());
        } else if (MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(readerScaleType)) {
            float density = Math.max(1f, getResources().getDisplayMetrics().density);
            int targetWidth = Math.min(viewWidth, Math.max(1, Math.round(src.width() / density)));
            scale = targetWidth / (float) src.width();
        } else {
            scale = viewWidth / (float) src.width();
        }
        int width = Math.max(1, Math.round(src.width() * scale));
        int height = Math.max(1, Math.round(src.height() * scale));
        int left = (viewWidth - width) / 2;
        int top = MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType) ? (viewHeight - height) / 2 : 0;
        drawRect.set(left, top, left + width, top + height);
    }

    private Rect getCropRectSafely() {
        Bitmap bitmap = getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return new Rect(0, 0, 0, 0);
        Rect cached;
        synchronized (CROP_CACHE) {
            cached = CROP_CACHE.get(bitmap);
        }
        if (cached != null) {
            cropRect.set(cached);
            lastBitmapWidth = bitmap.getWidth();
            lastBitmapHeight = bitmap.getHeight();
            cropReady = true;
            return cropRect;
        }
        requestCropRect(bitmap);
        cropRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        return cropRect;
    }

    private void requestCropRect(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        String token = bitmapIdentity(bitmap);
        synchronized (PENDING_CROP) {
            if (PENDING_CROP.contains(token)) return;
            if (PENDING_CROP.size() > 64) PENDING_CROP.clear();
            PENDING_CROP.add(token);
        }
        CROP_EXECUTOR.execute(() -> {
            try {
                Rect computed = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                detectBorder(bitmap, computed);
                synchronized (CROP_CACHE) {
                    CROP_CACHE.put(bitmap, computed);
                }
            } catch (Exception ignored) { }
            synchronized (PENDING_CROP) {
                PENDING_CROP.remove(token);
            }
            POST_HANDLER.post(() -> {
                Bitmap current = getBitmap();
                if (current == null || current.isRecycled()) return;
                if (current != bitmap) return;
                cropReady = true;
                requestLayout();
                invalidate();
            });
        });
    }

    private String bitmapIdentity(Bitmap bitmap) {
        return System.identityHashCode(bitmap) + "x" + bitmap.getWidth() + "x" + bitmap.getHeight();
    }

    private Bitmap getBitmap() {
        Drawable drawable = getDrawable();
        if (drawable instanceof BitmapDrawable) return ((BitmapDrawable) drawable).getBitmap();
        return null;
    }

    /**
     * Potong Pinggiran: hanya membuang pinggiran polos (putih / hitam) di sisi KIRI dan KANAN gambar.
     * Tinggi gambar tidak disentuh, dan skala gambar tidak diubah.
     * Batas potong per sisi dibatasi, dan celah tipis sengaja disisakan supaya gambar tidak terasa mepet.
     */
    private void detectBorder(Bitmap bitmap, Rect out) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 32 || height < 32) {
            out.set(0, 0, width, height);
            return;
        }
        int stepY = Math.max(2, height / 160);
        int maxCrop = Math.max(2, Math.round(width * MAX_CROP_FRACTION));
        int keepGap = Math.max(2, Math.round(width * KEEP_GAP_FRACTION));

        int left = 0;
        while (left < maxCrop && isBorderColumn(bitmap, left, stepY)) left++;
        int right = width - 1;
        int rightLimit = width - 1 - maxCrop;
        while (right > rightLimit && isBorderColumn(bitmap, right, stepY)) right--;

        // Sisakan celah tipis di sisi yang benar-benar terpotong.
        if (left > 0) left = Math.max(0, left - keepGap);
        if (right < width - 1) right = Math.min(width - 1, right + keepGap);

        if (right - left < width * 0.7f) out.set(0, 0, width, height);
        else out.set(left, 0, right + 1, height);
    }

    private boolean isBorderColumn(Bitmap bitmap, int x, int step) {
        int height = bitmap.getHeight();
        int total = 0;
        int border = 0;
        int firstBorderKind = -1;
        for (int y = 0; y < height; y += step) {
            total++;
            int kind = borderKind(bitmap.getPixel(x, y));
            if (kind < 0) continue;
            // Satu kolom pinggiran harus seragam: putih semua atau hitam semua.
            if (firstBorderKind < 0) firstBorderKind = kind;
            if (kind == firstBorderKind) border++;
        }
        return total > 0 && border >= total * 0.985f;
    }

    /** 0 = putih, 1 = hitam, -1 = bukan pinggiran. */
    private int borderKind(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        if (r > 238 && g > 238 && b > 238) return 0;
        if (r < 20 && g < 20 && b < 20) return 1;
        return -1;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
