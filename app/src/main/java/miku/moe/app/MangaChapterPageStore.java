package miku.moe.app;

import android.content.Context;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class MangaChapterPageStore {
    private static final String DIR_NAME = "manga_reader_pages";
    private static final long MAX_STORE_BYTES = 256L * 1024L * 1024L;
    private static volatile OkHttpClient downloadClient;

    private MangaChapterPageStore() {}

    static final class Result {
        final File file;
        final int width;
        final int height;

        Result(File file, int width, int height) {
            this.file = file;
            this.width = width;
            this.height = height;
        }
    }

    static final class DownloadException extends Exception {
        DownloadException(String message) {
            super(message);
        }
    }

    private static OkHttpClient client() {
        OkHttpClient current = downloadClient;
        if (current == null) {
            synchronized (MangaChapterPageStore.class) {
                if (downloadClient == null) downloadClient = MangaHttpClient.newBuilder().cache(null).build();
                current = downloadClient;
            }
        }
        return current;
    }

    static File fileFor(Context context, String url, String sourceId) {
        if (context == null || url == null || url.trim().isEmpty()) return null;
        File dir = new File(context.getApplicationContext().getCacheDir(), DIR_NAME);
        return new File(dir, sha256(MangaImageLoader.imageCacheKey(url.trim(), sourceId)) + ".img");
    }

    static boolean isReady(Context context, String url, String sourceId) {
        File file = fileFor(context, url, sourceId);
        return file != null && file.isFile() && file.length() > 0;
    }

    static void delete(Context context, String url, String sourceId) {
        File file = fileFor(context, url, sourceId);
        if (file == null) return;
        try { if (file.exists()) file.delete(); } catch (Exception ignored) { }
        try {
            File tmp = tmpFile(file);
            if (tmp.exists()) tmp.delete();
        } catch (Exception ignored) { }
    }

    private static File tmpFile(File file) {
        return new File(file.getParentFile(), file.getName() + ".tmp");
    }

    static Result download(Context context, String url, String sourceId) throws DownloadException {
        Context app = context.getApplicationContext();
        File file = fileFor(app, url, sourceId);
        if (file == null) throw new DownloadException("URL gambar tidak valid");
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = tmpFile(file);
        String resolvedUrl = MangaImageLoader.resolveImageUrl(url.trim(), sourceId);
        Request request = new Request.Builder().url(resolvedUrl).headers(MangaImageLoader.headersFor(resolvedUrl, sourceId)).build();
        long total = 0L;
        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful()) throw new DownloadException("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new DownloadException("Body gambar kosong");
            try (InputStream input = body.byteStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) {
                        out.write(buffer, 0, read);
                        total += read;
                    }
                }
                out.flush();
            }
        } catch (DownloadException e) {
            try { tmp.delete(); } catch (Exception ignored) { }
            throw e;
        } catch (Exception e) {
            try { tmp.delete(); } catch (Exception ignored) { }
            throw new DownloadException(describeDownloadError(e));
        }
        if (total <= 0L) {
            try { tmp.delete(); } catch (Exception ignored) { }
            throw new DownloadException("Body gambar kosong");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            try { tmp.delete(); } catch (Exception ignored) { }
            throw new DownloadException("Data bukan gambar valid");
        }
        try { if (file.exists()) file.delete(); } catch (Exception ignored) { }
        if (!tmp.renameTo(file)) {
            try { tmp.delete(); } catch (Exception ignored) { }
            throw new DownloadException("Gagal menyimpan gambar");
        }
        trimToLimit(dir);
        return new Result(file, options.outWidth, options.outHeight);
    }

    private static String describeDownloadError(Throwable error) {
        if (error == null) return "penyebab tidak diketahui";
        if (error instanceof java.net.SocketTimeoutException) return "timeout (server lambat)";
        if (error instanceof java.net.UnknownHostException) return "DNS/koneksi gagal";
        if (error instanceof javax.net.ssl.SSLException) return "SSL: " + safeMessage(error);
        if (error instanceof java.io.IOException) return "jaringan: " + error.getClass().getSimpleName() + (safeMessage(error).isEmpty() ? "" : " - " + safeMessage(error));
        return error.getClass().getSimpleName() + (safeMessage(error).isEmpty() ? "" : " - " + safeMessage(error));
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage().trim();
        return message.length() > 60 ? message.substring(0, 60) : message;
    }

    private static void trimToLimit(File dir) {
        try {
            if (dir == null) return;
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return;
            long total = 0L;
            java.util.ArrayList<File> regular = new java.util.ArrayList<>();
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                if (file.getName().endsWith(".tmp")) continue;
                regular.add(file);
                total += Math.max(0L, file.length());
            }
            if (total <= MAX_STORE_BYTES) return;
            java.util.Collections.sort(regular, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File file : regular) {
                if (total <= MAX_STORE_BYTES) break;
                long length = Math.max(0L, file.length());
                if (file.delete()) total -= length;
            }
        } catch (Exception ignored) { }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            char[] hexChars = "0123456789abcdef".toCharArray();
            char[] hex = new char[digest.length * 2];
            int index = 0;
            for (byte b : digest) {
                hex[index++] = hexChars[(b >> 4) & 0x0F];
                hex[index++] = hexChars[b & 0x0F];
            }
            return new String(hex);
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
