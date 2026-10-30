package miku.moe.app.crash;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLogManager {
    private static final String HISTORY_FILE_NAME = "crash_history_log.json";
    private static final int MAX_SAVED_CRASHES = 30;
    // CrashReporter dan CrashHandler sama-sama memanggil recordCrash untuk crash yang sama.
    private static volatile Throwable lastRecorded = null;

    private CrashLogManager() {}

    public static void recordCrash(Context context, Thread thread, Throwable throwable) {
        if (context == null || throwable == null) return;
        if (throwable == lastRecorded) return;
        lastRecorded = throwable;
        try {
            long now = System.currentTimeMillis();
            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(now));
            String threadName = thread != null ? thread.getName() : "unknown";

            StackTraceElement rootElement = findRootAppElement(throwable);
            String crashClass = rootElement != null ? rootElement.getClassName() : "UnknownClass";
            String crashMethod = rootElement != null ? rootElement.getMethodName() : "unknownMethod";
            String crashFile = rootElement != null && rootElement.getFileName() != null ? rootElement.getFileName() : "UnknownFile.java";
            int crashLine = rootElement != null ? rootElement.getLineNumber() : -1;

            String stackTrace = stackTraceToString(throwable);
            String exType = throwable.getClass().getName();
            String message = throwable.getMessage() != null ? throwable.getMessage() : "(tanpa pesan error)";

            JSONObject crashEntry = new JSONObject();
            crashEntry.put("timestamp", now);
            crashEntry.put("time", timeStr);
            crashEntry.put("thread", threadName);
            crashEntry.put("exception_type", exType);
            crashEntry.put("message", message);

            JSONObject location = new JSONObject();
            location.put("class", crashClass);
            location.put("method", crashMethod);
            location.put("file", crashFile);
            location.put("line", crashLine);
            crashEntry.put("location", location);

            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
            device.put("model", Build.MODEL != null ? Build.MODEL : "");
            device.put("android_version", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "");
            device.put("sdk_int", Build.VERSION.SDK_INT);
            crashEntry.put("device", device);

            crashEntry.put("app_version", getAppVersion(context));
            crashEntry.put("stack_trace", stackTrace);

            saveCrashEntry(context, crashEntry);
        } catch (Throwable ignored) {}
    }

    private static StackTraceElement findRootAppElement(Throwable throwable) {
        if (throwable == null) return null;
        Throwable current = throwable;
        while (current != null) {
            StackTraceElement[] elements = current.getStackTrace();
            if (elements != null) {
                for (StackTraceElement elem : elements) {
                    if (elem != null && elem.getClassName() != null && elem.getClassName().startsWith("miku.moe.app")) {
                        return elem;
                    }
                }
            }
            current = current.getCause();
        }
        StackTraceElement[] top = throwable.getStackTrace();
        return (top != null && top.length > 0) ? top[0] : null;
    }

    private static String stackTraceToString(Throwable throwable) {
        if (throwable == null) return "";
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Throwable e) {
            return throwable.toString();
        }
    }

    private static synchronized void saveCrashEntry(Context context, JSONObject newEntry) {
        try {
            JSONArray list = loadAllCrashEntries(context);
            java.util.ArrayList<JSONObject> items = new java.util.ArrayList<JSONObject>();
            items.add(newEntry);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null) items.add(item);
            }
            // Terbaru di atas (urut timestamp), entri exit-info bisa datang terlambat saat start berikutnya.
            java.util.Collections.sort(items, new java.util.Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("timestamp", 0L), a.optLong("timestamp", 0L));
                }
            });
            JSONArray updated = new JSONArray();
            for (int i = 0; i < items.size() && i < MAX_SAVED_CRASHES; i++) updated.put(items.get(i));
            writeAtomically(new File(context.getFilesDir(), HISTORY_FILE_NAME),
                    updated.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    /**
     * Tulis ke berkas sementara lalu rename. Bila proses mati / disk penuh di tengah jalan,
     * riwayat lama tetap utuh (sebelumnya berkas dipotong dulu lalu ditulis, sehingga riwayat
     * bisa hilang atau rusak dan panel menampilkan "Belum ada error").
     */
    private static void writeAtomically(File target, byte[] data) throws java.io.IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            FileOutputStream fos = new FileOutputStream(tmp, false);
            try {
                fos.write(data);
                fos.flush();
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            } finally {
                fos.close();
            }
            if (!tmp.renameTo(target)) {
                target.delete();
                if (!tmp.renameTo(target)) throw new java.io.IOException("rename gagal");
            }
        } catch (java.io.IOException e) {
            try { tmp.delete(); } catch (Throwable ignored) {}
            throw e;
        }
    }

    /** true bila sudah ada entri riwayat dengan waktu dalam rentang windowMs dari timestamp. */
    public static synchronized boolean hasEntryNear(Context context, long timestamp, long windowMs) {
        try {
            JSONArray list = loadAllCrashEntries(context);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                if (Math.abs(item.optLong("timestamp", 0L) - timestamp) <= windowMs) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Catat proses yang mati TANPA melewati UncaughtExceptionHandler (crash native, dibunuh sistem
     * karena memori, ANR, dll) dari ApplicationExitInfo, supaya muncul di Riwayat Error.
     */
    public static void recordExit(Context context, long timestamp, String reasonName, String description, String details) {
        if (context == null) return;
        try {
            JSONObject entry = new JSONObject();
            entry.put("timestamp", timestamp);
            entry.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(timestamp)));
            entry.put("thread", "exit-info");
            entry.put("exception_type", "ExitInfo/" + reasonName);
            boolean noDescription = description == null || description.trim().isEmpty() || "null".equals(description);
            entry.put("message", noDescription ? "(tanpa keterangan dari sistem)" : description);
            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
            device.put("model", Build.MODEL != null ? Build.MODEL : "");
            device.put("android_version", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "");
            device.put("sdk_int", Build.VERSION.SDK_INT);
            entry.put("device", device);
            entry.put("app_version", getAppVersion(context));
            entry.put("stack_trace", details == null ? "" : details);
            saveCrashEntry(context, entry);
        } catch (Throwable ignored) {}
    }

    public static synchronized JSONArray loadAllCrashEntries(Context context) {
        try {
            File file = new File(context.getFilesDir(), HISTORY_FILE_NAME);
            if (!file.exists()) return new JSONArray();
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int r = fis.read(buf, read, buf.length - read);
                if (r <= 0) break;
                read += r;
            }
            fis.close();
            if (read <= 0) return new JSONArray();
            String content = new String(buf, 0, read, StandardCharsets.UTF_8);
            try {
                return new JSONArray(content);
            } catch (Throwable parseError) {
                // Berkas rusak: simpan salinannya, jangan langsung ditimpa riwayat baru.
                try {
                    File backup = new File(context.getFilesDir(), "crash_history_log.corrupt.json");
                    backup.delete();
                    file.renameTo(backup);
                } catch (Throwable ignored) {}
                return new JSONArray();
            }
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    public static synchronized void clearCrashLogs(Context context) {
        try {
            File file = new File(context.getFilesDir(), HISTORY_FILE_NAME);
            if (file.exists()) file.delete();
        } catch (Throwable ignored) {}
    }

    public static int getCrashCount(Context context) {
        return loadAllCrashEntries(context).length();
    }

    /** Teks ringkas yang mudah dibaca untuk panel riwayat error di Pengaturan. */
    public static String getFormattedTerminalLog(Context context) {
        JSONArray crashes = loadAllCrashEntries(context);
        String deviceLine = Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
        if (crashes.length() == 0) {
            return "Belum ada error yang tercatat.\n\n"
                    + "Versi aplikasi : " + getAppVersion(context) + "\n"
                    + "Perangkat      : " + deviceLine;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < crashes.length(); i++) {
            try {
                JSONObject item = crashes.getJSONObject(i);
                JSONObject loc = item.optJSONObject("location");
                JSONObject dev = item.optJSONObject("device");
                String type = item.optString("exception_type", "-");
                int dot = type.lastIndexOf('.');
                if (dot >= 0 && dot < type.length() - 1) type = type.substring(dot + 1);

                sb.append('#').append(i + 1).append("  ").append(item.optString("time", "-")).append('\n');
                sb.append(type).append('\n');
                sb.append("Pesan   : ").append(item.optString("message", "-")).append('\n');
                if (loc != null) {
                    sb.append("Lokasi  : ").append(loc.optString("class", "-")).append('.')
                            .append(loc.optString("method", "-")).append(" (")
                            .append(loc.optString("file", "-")).append(':').append(loc.optInt("line", -1)).append(")\n");
                }
                sb.append("Thread  : ").append(item.optString("thread", "-")).append('\n');
                if (dev != null) {
                    sb.append("Perangkat: ").append(dev.optString("manufacturer", "")).append(' ')
                            .append(dev.optString("model", "")).append(" · Android ")
                            .append(dev.optString("android_version", "")).append('\n');
                }
                sb.append("Versi   : ").append(item.optString("app_version", "-")).append("\n\n");

                String[] lines = item.optString("stack_trace", "").split("\n");
                int shown = 0;
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    sb.append("  ").append(line.trim()).append('\n');
                    shown++;
                    if (shown >= 10 && lines.length > 12) {
                        sb.append("  … ").append(lines.length - shown).append(" baris lainnya\n");
                        break;
                    }
                }
                sb.append("\n————————————————\n\n");
            } catch (Throwable ignored) {}
        }
        return sb.toString().trim();
    }

    public static String buildStructuredJsonExport(Context context) {
        try {
            JSONArray list = loadAllCrashEntries(context);
            JSONObject root = new JSONObject();

            JSONObject exportInfo = new JSONObject();
            exportInfo.put("title", "Crash Logging");
            exportInfo.put("log_type", "Crash Logging");
            exportInfo.put("app_name", "Miku Moe");
            exportInfo.put("app_version", getAppVersion(context));
            exportInfo.put("exported_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            exportInfo.put("device_model", Build.MANUFACTURER + " " + Build.MODEL);
            exportInfo.put("android_release", Build.VERSION.RELEASE);
            exportInfo.put("android_sdk", Build.VERSION.SDK_INT);
            exportInfo.put("total_crashes", list.length());

            root.put("log_title", "Crash Logging");
            root.put("export_info", exportInfo);
            root.put("crashes", list);
            return root.toString(2);
        } catch (Throwable e) {
            return "{\n  \"error\": \"Gagal menyusun JSON log crash: " + e.getMessage() + "\"\n}";
        }
    }

    public static boolean shareCrashLogFile(Context context) {
        if (context == null) return false;
        try {
            String jsonContent = buildStructuredJsonExport(context);
            File cacheDir = context.getCacheDir();
            File shareFile = new File(cacheDir, "Crash Logging.json");
            FileOutputStream fos = new FileOutputStream(shareFile, false);
            fos.write(jsonContent.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    shareFile
            );

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("application/json");
            sendIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Laporan Crash Logging - Miku Moe");
            sendIntent.putExtra(Intent.EXTRA_TEXT, "Berikut terlampir berkas Crash Logging.json dari aplikasi Miku Moe untuk diperiksa oleh developer.");
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(sendIntent, "Bagikan Crash Logging.json");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String getAppVersion(Context context) {
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return p.versionName + " (" + (Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode) + ")";
        } catch (Throwable ignored) {
            return "Miku Moe 3.7";
        }
    }
}
