package miku.moe.app.crash;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Pelapor crash SILENT: menangkap log crash lengkap tanpa dialog/toast/pesan
 * apa pun ke pengguna, menyimpannya sebagai berkas JSON, lalu mengirimkannya
 * ke URL relay web (relay yang meneruskan ke Telegram pemilik — token bot
 * TIDAK pernah ada di aplikasi; aplikasi hanya tahu RELAY_URL + APP_KEY).
 *
 * Alur:
 *  - install(): memasang UncaughtExceptionHandler yang membungkus handler
 *    lama. Saat crash: laporan dibangun, ditulis ke filesDir/crash_reports/,
 *    dicoba dikirim saat itu juga (thread terpisah, join maks ~3 detik);
 *    sesudahnya handler lama tetap dipanggil agar perilaku crash bawaan
 *    (dialog sistem / CrashHandler aplikasi) berjalan seperti biasa.
 *  - flushPending(): dipanggil saat aplikasi start; mengirim ulang laporan
 *    yang belum terkirim (maks 5 per start, tertua dulu) dan membuang yang
 *    berumur > 14 hari.
 *
 * Semua jalur dibungkus try/catch total: kelas ini tidak boleh pernah
 * melempar exception atau mengganggu aplikasi.
 */
public final class CrashReporter {

    private static final String RELAY_URL = "https://crash.mikumoe.my.id/report";
    private static final String APP_KEY = "de8ebced20f6043f";
    private static final String HEADER_KEY = "X-Crash-Key";

    private static final String DIR_NAME = "crash_reports";
    private static final long MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final int MAX_FLUSH_PER_START = 5;
    private static final long SEND_JOIN_MS = 3000L;

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private static volatile boolean installed = false;
    private static volatile Context appContext = null;

    private CrashReporter() {
    }

    // ---------------------------------------------------------------
    // Pemasangan handler
    // ---------------------------------------------------------------

    public static void install(Context context) {
        try {
            if (installed) return;
            synchronized (CrashReporter.class) {
                if (installed) return;
                if (context != null) {
                    appContext = context.getApplicationContext();
                }
                final Thread.UncaughtExceptionHandler previous =
                        Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread thread, Throwable throwable) {
                        try {
                            handleCrash(thread, throwable);
                        } catch (Throwable ignored) {
                            // Pelapor tidak boleh ikut crash.
                        }
                        try {
                            if (previous != null) {
                                previous.uncaughtException(thread, throwable);
                                return;
                            }
                        } catch (Throwable ignored) {
                        }
                        // Tidak ada handler lama: akhiri proses seperti crash normal.
                        try {
                            Process.killProcess(Process.myPid());
                        } catch (Throwable ignored) {
                        }
                        System.exit(10);
                    }
                });
                installed = true;
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------
    // Penanganan crash
    // ---------------------------------------------------------------

    private static void handleCrash(Thread thread, Throwable throwable) {
        Context context = appContext;
        if (context == null) return;
        CrashLogManager.recordCrash(context, thread, throwable);
        long now = System.currentTimeMillis();
        String json = buildReportJson(context, thread, throwable, now);
        if (json == null) return;
        final File file = saveReport(context, json, now);
        // Coba kirim sekarang juga: thread terpisah, tunggu maks ~3 detik —
        // proses akan mati sesudah handler lama dipanggil.
        if (file != null) {
            try {
                Thread sender = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (postReport(json)) {
                                // Terkirim: laporan tidak perlu disimpan lagi.
                                try {
                                    file.delete();
                                } catch (Throwable ignored) {
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }, "crash-reporter-send");
                sender.setDaemon(true);
                sender.start();
                try {
                    sender.join(SEND_JOIN_MS);
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String buildReportJson(Context context, Thread thread, Throwable throwable, long now) {
        try {
            JSONObject json = new JSONObject();
            json.put("time", now);
            json.put("time_str", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date(now)));
            json.put("thread", thread != null ? String.valueOf(thread.getName()) : "?");
            String manufacturer = "";
            String model = "";
            try {
                manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
            } catch (Throwable ignored) {
            }
            try {
                model = Build.MODEL != null ? Build.MODEL : "";
            } catch (Throwable ignored) {
            }
            json.put("device", (manufacturer + " " + model).trim());
            String release = "?";
            try {
                release = Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "?";
            } catch (Throwable ignored) {
            }
            json.put("android", release + " (SDK " + Build.VERSION.SDK_INT + ")");
            json.put("app", appVersion(context));
            json.put("stack", stackTraceOf(throwable));
            return json.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long versionCode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = info.getLongVersionCode();
            } else {
                versionCode = info.versionCode;
            }
            String versionName = info.versionName != null ? info.versionName : "?";
            return versionName + " (" + versionCode + ")";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        try {
            if (throwable == null) return "";
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            // printStackTrace menulis seluruh rantai "Caused by:" juga.
            throwable.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static File saveReport(Context context, String json, long now) {
        try {
            File dir = reportsDir(context);
            if (dir == null) return null;
            File file = new File(dir, "crash-" + now + ".json");
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(json.getBytes("UTF-8"));
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            return file;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File reportsDir(Context context) {
        try {
            File base = context.getFilesDir();
            if (base == null) return null;
            File dir = new File(base, DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Pengiriman ulang laporan tertunda (dipanggil saat start)
    // ---------------------------------------------------------------

    public static void flushPending(final Context context) {
        try {
            if (context == null) return;
            if (appContext == null) {
                appContext = context.getApplicationContext();
            }
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        flushPendingInternal(context.getApplicationContext());
                    } catch (Throwable ignored) {
                    }
                }
            }, "crash-reporter-flush");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable ignored) {
        }
    }

    private static void flushPendingInternal(Context context) {
        try {
            File dir = reportsDir(context);
            if (dir == null) return;
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return;
            // Tertua dulu.
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    long diff = a.lastModified() - b.lastModified();
                    if (diff < 0) return -1;
                    if (diff > 0) return 1;
                    return 0;
                }
            });
            long now = System.currentTimeMillis();
            int sent = 0;
            for (File file : files) {
                try {
                    if (file == null || !file.isFile()) continue;
                    if (now - file.lastModified() > MAX_AGE_MS) {
                        // Terlalu tua: buang tanpa dikirim.
                        try {
                            file.delete();
                        } catch (Throwable ignored) {
                        }
                        continue;
                    }
                    if (sent >= MAX_FLUSH_PER_START) break;
                    String json = readFile(file);
                    if (json == null || json.length() == 0) continue;
                    sent++;
                    if (postReport(json)) {
                        try {
                            file.delete();
                        } catch (Throwable ignored) {
                        }
                    }
                    // Gagal kirim: berkas dibiarkan untuk start berikutnya.
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------
    // Error yang ditangkap try/catch (dilaporkan manual)
    // ---------------------------------------------------------------

    private static final int MAX_CAUGHT_PER_PROCESS = 15;
    private static final long CAUGHT_DEDUPE_MS = 10L * 60L * 1000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> caughtSeen =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static final java.util.concurrent.atomic.AtomicInteger caughtCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Laporkan error yang sudah ditelan try/catch tapi mencurigakan (bukan error jaringan biasa).
     * Aman dipanggil dari thread mana pun: tidak pernah melempar exception, kirim di thread
     * terpisah, error yang sama (tag + kelas + frame teratas) cuma dikirim sekali per 10 menit,
     * maksimal 15 laporan per sesi proses. Tidak disimpan ke disk bila gagal terkirim.
     *
     * @param tag   penanda lokasi, mis. "reader-decode"
     * @param extra info tambahan singkat (jangan isi URL lengkap / data pribadi), boleh null
     */
    public static void logCaught(final String tag, final String extra, final Throwable error) {
        try {
            final Context context = appContext;
            if (context == null || error == null) return;
            if (caughtCount.get() >= MAX_CAUGHT_PER_PROCESS) return;
            StackTraceElement[] frames = error.getStackTrace();
            String top = frames != null && frames.length > 0 ? String.valueOf(frames[0]) : "";
            String key = tag + "|" + error.getClass().getName() + "|" + top;
            long now = System.currentTimeMillis();
            Long last = caughtSeen.get(key);
            if (last != null && now - last.longValue() < CAUGHT_DEDUPE_MS) return;
            if (caughtSeen.size() > 100) caughtSeen.clear();
            caughtSeen.put(key, Long.valueOf(now));
            caughtCount.incrementAndGet();
            final long time = now;
            Thread sender = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String base = buildReportJson(context, Thread.currentThread(), error, time);
                        if (base == null) return;
                        JSONObject json = new JSONObject(base);
                        json.put("thread", "caught");
                        json.put("stack", "CAUGHT [" + tag + "] " + (extra == null ? "" : extra)
                                + "\n" + json.optString("stack", ""));
                        postReport(json.toString());
                    } catch (Throwable ignored) {
                    }
                }
            }, "crash-reporter-caught");
            sender.setDaemon(true);
            sender.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Varian untuk kelas source: laporkan kegagalan parsing (HTML/JSON berubah, null, dsb.).
     * Error jaringan (IOException) dan pembatalan dilewati supaya tidak spam.
     */
    public static void logParse(String source, String stage, Throwable error) {
        try {
            if (error == null) return;
            if (error instanceof java.io.IOException) return;
            if (error instanceof java.util.concurrent.CancellationException) return;
            if (isHtmlInsteadOfJson(error)) return;
            logCaught("source-parse", source + (stage == null || stage.isEmpty() ? "" : " | " + stage), error);
        } catch (Throwable ignored) {
        }
    }

    // Situs mengirim HTML (Cloudflare / halaman error) padahal app minta JSON: masalahnya di
    // situs, bukan bug app, jadi tidak dilaporkan. Gson menulis "line 1 column 1" bila isi
    // respons bukan JSON sejak karakter pertama.
    private static boolean isHtmlInsteadOfJson(Throwable error) {
        try {
            Throwable t = error;
            for (int depth = 0; t != null && depth < 6; depth++, t = t.getCause()) {
                String name = t.getClass().getName();
                boolean jsonError = name.endsWith("JsonSyntaxException")
                        || name.endsWith("JsonParseException")
                        || name.endsWith("MalformedJsonException")
                        || name.endsWith("JSONException");
                if (!jsonError) continue;
                String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
                if (msg.contains("<html") || msg.contains("<!doctype") || msg.contains("line 1 column 1")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---------------------------------------------------------------
    // Alasan proses mati di sesi sebelumnya (API 30+)
    // ---------------------------------------------------------------

    private static final String PREFS = "crash_reporter";
    private static final String KEY_LAST_EXIT_TS = "last_exit_ts";
    private static final int MAX_EXIT_REPORTS_PER_START = 3;

    /**
     * Membaca ApplicationExitInfo: menangkap crash NATIVE, low-memory kill, ANR, dsb.
     * yang tidak pernah lewat UncaughtExceptionHandler. Dikirim ke relay seperti crash biasa
     * (thread = "exit-info"). Crash Java (REASON_CRASH) dan keluar normal dilewati.
     */
    public static void reportPreviousExit(final Context context) {
        try {
            if (context == null || Build.VERSION.SDK_INT < 30) return;
            final Context app = context.getApplicationContext();
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        reportPreviousExitInternal(app);
                    } catch (Throwable ignored) {
                    }
                }
            }, "crash-reporter-exit");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable ignored) {
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "OTHER(" + reason + ")";
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private static void reportPreviousExitInternal(Context context) throws Exception {
        android.app.ActivityManager am =
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        java.util.List<android.app.ApplicationExitInfo> list =
                am.getHistoricalProcessExitReasons(null, 0, 8);
        if (list == null || list.isEmpty()) return;
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L);
        long newest = lastTs;
        int sentCount = 0;
        for (android.app.ApplicationExitInfo info : list) {
            try {
                long ts = info.getTimestamp();
                if (ts <= lastTs) continue;
                if (ts > newest) newest = ts;
                String processName = info.getProcessName();
                if (processName != null && processName.endsWith(":crash_report")) continue;
                int reason = info.getReason();
                // Whitelist: hanya alasan yang bisa jadi penyebab force close.
                // Sisanya (EXIT_SELF, CRASH Java yang sudah dilaporkan handler, USER_REQUESTED,
                // OTHER seperti swipe-clean HyperOS/MIUI, PACKAGE_UPDATED, FREEZER, dll) diabaikan.
                if (reason != 2 && reason != 3 && reason != 4 && reason != 5 && reason != 6
                        && reason != 7 && reason != 9) continue;
                // Crash Java yang handler-nya sudah mencatat (proses lalu di-kill sendiri, tampil sebagai
                // SIGNALED/CRASH beberapa detik sesudahnya) tidak perlu dicatat dobel.
                if ((reason == 2 || reason == 4) && CrashLogManager.hasEntryNear(context, ts, 20000L)) continue;
                if (sentCount >= MAX_EXIT_REPORTS_PER_START) break;
                sentCount++;
                StringBuilder sb = new StringBuilder();
                sb.append("EXIT-INFO ").append(exitReasonName(reason)).append('\n');
                sb.append("process: ").append(info.getProcessName()).append('\n');
                sb.append("status: ").append(info.getStatus()).append('\n');
                sb.append("importance: ").append(info.getImportance()).append('\n');
                sb.append("pss_kb: ").append(info.getPss()).append(" rss_kb: ").append(info.getRss()).append('\n');
                sb.append("description: ").append(info.getDescription()).append('\n');
                try {
                    java.io.InputStream trace = info.getTraceInputStream();
                    if (trace != null) {
                        try {
                            byte[] buf = new byte[4096];
                            int n = trace.read(buf);
                            if (n > 0) {
                                sb.append("trace (").append(reason == 5 ? "tombstone, proto" : "text").append("):\n");
                                sb.append(new String(buf, 0, n, "UTF-8").replaceAll("[^\\x09\\x0A\\x20-\\x7E]", "?"));
                            }
                        } finally {
                            try { trace.close(); } catch (Throwable ignored) { }
                        }
                    }
                } catch (Throwable ignored) {
                }
                // Catat juga ke riwayat lokal (Pengaturan > Catatan Log Crash).
                CrashLogManager.recordExit(context, ts, exitReasonName(reason),
                        String.valueOf(info.getDescription()), sb.toString());
                JSONObject json = new JSONObject();
                json.put("time", ts);
                json.put("time_str", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date(ts)));
                json.put("thread", "exit-info");
                json.put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim());
                json.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
                json.put("app", appVersion(context));
                json.put("stack", sb.toString());
                postReport(json.toString());
            } catch (Throwable ignored) {
            }
        }
        if (newest > lastTs) prefs.edit().putLong(KEY_LAST_EXIT_TS, newest).apply();
    }

    private static String readFile(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buffer = new byte[(int) Math.max(1L, file.length())];
                int offset = 0;
                int read;
                while (offset < buffer.length
                        && (read = in.read(buffer, offset, buffer.length - offset)) != -1) {
                    offset += read;
                }
                return new String(buffer, 0, offset, "UTF-8");
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------

    /** true hanya bila relay menjawab HTTP 200. */
    private static boolean postReport(String json) {
        Response response = null;
        try {
            Request request = new Request.Builder()
                    .url(RELAY_URL)
                    .header("Content-Type", "application/json")
                    .header(HEADER_KEY, APP_KEY)
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();
            response = client().newCall(request).execute();
            return response.code() == 200;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static volatile OkHttpClient clientInstance = null;

    private static OkHttpClient client() {
        OkHttpClient client = clientInstance;
        if (client == null) {
            synchronized (CrashReporter.class) {
                client = clientInstance;
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .writeTimeout(10, TimeUnit.SECONDS)
                            .build();
                    clientInstance = client;
                }
            }
        }
        return client;
    }
}
