package miku.moe.app.api;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ThreadLocalRandom;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Mencoba ulang request yang ditolak server dengan HTTP/2 REFUSED_STREAM.
 *
 * REFUSED_STREAM berarti server menolak sebelum memproses apa pun (server lagi sibuk, membatasi
 * request paralel, atau koneksi sedang ditutup), jadi aman dicoba ulang, termasuk untuk POST.
 * OkHttp hanya mengulang sekali dan seketika, biasanya masih ditolak. Di sini ada jeda bertahap
 * (+ sedikit acak supaya dua request paralel tidak mengulang bersamaan).
 *
 * Pasang sebagai interceptor PALING LUAR (sebelum AnxGuardInterceptor) supaya tiap percobaan
 * ditandatangani ulang dengan nonce/timestamp baru.
 */
public final class AnxRetryInterceptor implements Interceptor {

    /** Jeda sebelum percobaan ke-2 sampai ke-5 (total maksimal 5 percobaan, sekitar 5 detik). */
    private static final long[] BACKOFF_MS = {300L, 800L, 1500L, 2500L};

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        IOException last = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                if (chain.call().isCanceled()) throw new IOException("Canceled");
                long delay = BACKOFF_MS[attempt - 1] + ThreadLocalRandom.current().nextLong(150L);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Retry dibatalkan");
                }
            }
            try {
                return chain.proceed(request);
            } catch (IOException e) {
                if (!isRefusedStream(e) || attempt == BACKOFF_MS.length) throw e;
                last = e;
            }
        }
        throw last != null ? last : new IOException("Retry gagal");
    }

    static boolean isRefusedStream(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 6; depth++) {
            String message = t.getMessage();
            if (message != null && message.contains("REFUSED_STREAM")) return true;
            t = t.getCause();
        }
        return false;
    }
}
