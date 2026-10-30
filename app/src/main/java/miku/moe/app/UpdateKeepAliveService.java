package miku.moe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.HashMap;
import java.util.Map;

/**
 * Menjaga proses aplikasi tetap hidup selama pemeriksaan pembaruan manga berjalan,
 * supaya progres tidak terhenti saat pengguna keluar dari layar atau berpindah tab.
 * Beberapa pekerjaan bisa memakai service ini bersamaan (tiap pekerjaan punya key sendiri);
 * service berhenti otomatis saat semua pekerjaan selesai.
 */
public class UpdateKeepAliveService extends Service {
    private static final String CHANNEL_ID = "miku_update_check";
    private static final int NOTIFICATION_ID = 7301;
    private static final Object LOCK = new Object();
    private static final Map<String, String> JOBS = new HashMap<>();
    private static final long NOTIFY_MIN_INTERVAL_MS = 1000L;
    private static long lastNotifyAt = 0L;

    /** Tandai pekerjaan dimulai / perbarui teks progresnya. Aman dipanggil berulang dari main thread. */
    public static void update(Context context, String jobKey, String text) {
        if (context == null || jobKey == null) return;
        Context app = context.getApplicationContext();
        boolean first;
        synchronized (LOCK) {
            first = JOBS.isEmpty();
            JOBS.put(jobKey, text == null ? "" : text);
        }
        try {
            if (first) {
                Intent intent = new Intent(app, UpdateKeepAliveService.class);
                ContextCompat.startForegroundService(app, intent);
            } else {
                // Membangun + mengirim notifikasi itu mahal di main thread; cukup sekali per detik.
                long now = SystemClock.elapsedRealtime();
                if (now - lastNotifyAt >= NOTIFY_MIN_INTERVAL_MS) {
                    lastNotifyAt = now;
                    notifyNow(app);
                }
            }
        } catch (Throwable ignored) {
            // Gagal memulai service (mis. dibatasi sistem) tidak boleh mengganggu pemeriksaan itu sendiri.
        }
    }

    /** Tandai pekerjaan selesai. Service berhenti kalau tidak ada pekerjaan lain. */
    public static void finish(Context context, String jobKey) {
        if (context == null || jobKey == null) return;
        Context app = context.getApplicationContext();
        boolean empty;
        synchronized (LOCK) {
            JOBS.remove(jobKey);
            empty = JOBS.isEmpty();
        }
        try {
            if (empty) app.stopService(new Intent(app, UpdateKeepAliveService.class));
            else notifyNow(app);
        } catch (Throwable ignored) { }
    }

    private static String currentText() {
        synchronized (LOCK) {
            String text = "";
            for (String value : JOBS.values()) {
                if (value != null && !value.isEmpty()) text = value;
            }
            return text.isEmpty() ? "Memeriksa pembaruan manga" : text;
        }
    }

    private static Notification build(Context context) {
        ensureChannel(context);
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle("Miku Moe")
                .setContentText(currentText())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(android.app.PendingIntent.getActivity(
                        context, 0,
                        new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
    }

    private static void notifyNow(Context context) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, build(context));
        } catch (Throwable ignored) { }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Pemeriksaan pembaruan", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Tampil selama pembaruan manga sedang diperiksa");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = build(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (LOCK) {
            if (JOBS.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
