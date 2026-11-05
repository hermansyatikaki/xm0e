package miku.moe.app.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.TelephonyManager;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class AnxDeviceIdentity {
    private static final String PREFS = "anx_device_identity";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_ANDROID_ID = "android_id";
    private static final String KEY_ANX_RT = "anx_rt";
    private static final String KEY_LAST_ROTATE_MS = "anx_last_rotate_ms";
    private static final String KEY_TICKET = "anx_integrity_ticket";
    private static final String KEY_TICKET_EXP = "anx_integrity_exp";
    private static final long ROTATE_COOLDOWN_MS = 10L * 60L * 1000L;
    private static final long TICKET_MIN_REMAINING_SEC = 2L * 60L * 60L;
    private static final long TICKET_DEFAULT_TTL_SEC = 6L * 60L * 60L;
    private static final String ANX_RT_VALUE = "d:native-file";

    private static volatile String cachedDeviceId;
    private static volatile String cachedTicket;
    private static volatile long cachedTicketExpSec;
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_REGISTERED = "anx_device_registered";
    private static final String KEY_BLOCKED = "anx_device_blocked";

    private static final String[] DEVICE_NAMES = {
            "Samsung Galaxy S23", "Samsung Galaxy S22", "Samsung Galaxy A54",
            "Samsung Galaxy A34", "Xiaomi 13", "Xiaomi 12", "Redmi Note 12",
            "Redmi Note 11", "POCO X5", "Google Pixel 8", "Google Pixel 7",
            "Google Pixel 6a", "OPPO Reno10", "OPPO A78", "vivo V29",
            "vivo Y36", "Realme 11 Pro", "Realme C55", "Infinix Note 30",
            "Infinix Hot 30", "ASUS Zenfone 10", "OnePlus 11", "Nokia G42"
    };

    private static OkHttpClient client;

    private AnxDeviceIdentity() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized String getDeviceId(Context context) {
        SharedPreferences p = prefs(context);
        String value = p.getString(KEY_DEVICE_ID, null);
        if (value == null || value.isEmpty() || value.contains("-")) {
            value = p.getString(KEY_ANDROID_ID, null);
            if (value == null || value.isEmpty()) value = randomHex(16);
            p.edit().putString(KEY_DEVICE_ID, value)
                    .putString(KEY_ANDROID_ID, value).apply();
        }
        cachedDeviceId = value;
        if (cachedTicket == null) {
            cachedTicket = p.getString(KEY_TICKET, null);
            cachedTicketExpSec = p.getLong(KEY_TICKET_EXP, 0L);
        }
        return value;
    }

    public static synchronized String getAndroidId(Context context) {
        return getDeviceId(context);
    }

    public static synchronized String getAnxRt(Context context) {
        return ANX_RT_VALUE;
    }

    public static String peekDeviceId() {
        return cachedDeviceId;
    }

    public static String peekAnxRt() {
        return ANX_RT_VALUE;
    }

    public static String peekIntegrityTicket() {
        String ticket = cachedTicket;
        if (ticket == null || ticket.isEmpty()) return null;
        long exp = cachedTicketExpSec;
        if (exp > 0L && System.currentTimeMillis() / 1000L >= exp) return null;
        return ticket;
    }

    public static synchronized String getDeviceName(Context context) {
        SharedPreferences p = prefs(context);
        String value = p.getString(KEY_DEVICE_NAME, null);
        if (value == null || value.isEmpty()) {
            value = DEVICE_NAMES[new SecureRandom().nextInt(DEVICE_NAMES.length)];
            p.edit().putString(KEY_DEVICE_NAME, value).apply();
        }
        return value;
    }

    public static String getDeviceToken() {
        return "";
    }

    public static boolean isDeviceBlocked(Context context) {
        return prefs(context).getBoolean(KEY_BLOCKED, false);
    }

    public static void ensureRegistered(final Context context) {
        try {
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    runRegistration(app);
                } catch (Throwable ignored) {
                }
            }, "anx-device-identity").start();
        } catch (Throwable ignored) {
        }
    }

    private static void runRegistration(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        String deviceId = getDeviceId(context);

        ensureIntegrityTicket(context, deviceId);
        registerIfNeeded(context, deviceId);
        boolean blocked = checkBlocked(context, deviceId);
        p.edit().putBoolean(KEY_BLOCKED, blocked).apply();

        if (blocked) {
            long last = p.getLong(KEY_LAST_ROTATE_MS, 0L);
            if (System.currentTimeMillis() - last >= ROTATE_COOLDOWN_MS) {
                rotateIdentity(context);
                String freshId = getDeviceId(context);
                ensureIntegrityTicket(context, freshId);
                registerIfNeeded(context, freshId);
                boolean stillBlocked = checkBlocked(context, freshId);
                p.edit().putBoolean(KEY_BLOCKED, stillBlocked)
                        .putLong(KEY_LAST_ROTATE_MS, System.currentTimeMillis()).apply();
            }
        }
    }

    private static void ensureIntegrityTicket(Context context, String deviceId) {
        try {
            long now = System.currentTimeMillis() / 1000L;
            if (peekIntegrityTicket() != null && cachedTicketExpSec - now > TICKET_MIN_REMAINING_SEC) return;

            FormBody form = new FormBody.Builder()
                    .add("token", "")
                    .add("ts", String.valueOf(System.currentTimeMillis()))
                    .add("device_id", deviceId)
                    .add("stage", "start")
                    .add("model", safe(Build.MODEL))
                    .add("sdk", String.valueOf(Build.VERSION.SDK_INT))
                    .add("installer", installerPackage(context))
                    .add("sim_country", countryIso(context, true))
                    .add("net_country", countryIso(context, false))
                    .add("error", "-1")
                    .build();
            Request request = new Request.Builder()
                    .url(AnxBaseUrl.apiBase() + "integrity_verify/")
                    .header("User-Agent", AnxGuardInterceptor.userAgent())
                    .post(form)
                    .build();
            try (Response response = client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                JSONObject json = new JSONObject(response.body().string());
                String ticket = json.optString("ticket", "");
                if (ticket.isEmpty() || "null".equalsIgnoreCase(ticket)) return;
                long exp = json.optLong("expires", 0L);
                if (exp > 100000000000L) exp = exp / 1000L;
                if (exp <= 0L) {
                    String[] parts = ticket.split("\\|");
                    if (parts.length > 1) {
                        try {
                            exp = Long.parseLong(parts[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (exp <= 0L) exp = now + TICKET_DEFAULT_TTL_SEC;
                prefs(context).edit().putString(KEY_TICKET, ticket).putLong(KEY_TICKET_EXP, exp).apply();
                cachedTicket = ticket;
                cachedTicketExpSec = exp;
            }
        } catch (Exception ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String installerPackage(Context context) {
        try {
            String installer = context.getPackageManager().getInstallerPackageName(context.getPackageName());
            return installer == null ? "" : installer;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String countryIso(Context context, boolean sim) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String iso = sim ? tm.getSimCountryIso() : tm.getNetworkCountryIso();
            return iso == null ? "" : iso.toUpperCase(Locale.US);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void registerIfNeeded(Context context, String deviceId) throws Exception {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_REGISTERED, false)) return;
        FormBody form = new FormBody.Builder()
                .add("device_id", deviceId)
                .add("device_name", getDeviceName(context))
                .add("device_token", deviceId)
                .add("fcm_token", "")
                .build();
        Request request = new Request.Builder()
                .url(AnxBaseUrl.apiBase() + "register_user/")
                .header("User-Agent", AnxGuardInterceptor.userAgent())
                .post(form)
                .build();
        try (Response response = client().newCall(request).execute()) {
            if (response.isSuccessful()) {
                p.edit().putBoolean(KEY_REGISTERED, true).apply();
            }
        }
    }

    private static boolean checkBlocked(Context context, String deviceId) {
        try {
            FormBody statusForm = new FormBody.Builder()
                    .add("device_id", deviceId)
                    .build();
            Request statusRequest = new Request.Builder()
                    .url(AnxBaseUrl.apiBase() + "device_status/")
                    .header("User-Agent", AnxGuardInterceptor.userAgent())
                    .post(statusForm)
                    .build();
            try (Response response = client().newCall(statusRequest).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject json = new JSONObject(response.body().string());
                    return json.optBoolean("device_blocked", json.optBoolean("blocked", false));
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void rotateIdentity(Context context) {
        SharedPreferences p = prefs(context);
        p.edit().remove(KEY_DEVICE_ID).remove(KEY_ANDROID_ID).remove(KEY_ANX_RT)
                .remove(KEY_DEVICE_NAME).remove(KEY_REGISTERED).remove(KEY_BLOCKED)
                .remove(KEY_TICKET).remove(KEY_TICKET_EXP).apply();
        cachedDeviceId = null;
        cachedTicket = null;
        cachedTicketExpSec = 0L;
    }

    private static synchronized OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .addInterceptor(new AnxGuardInterceptor())
                    .addInterceptor(new AnxResponseDecryptInterceptor())
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    private static String randomHex(int length) {
        char[] hex = "0123456789abcdef".toCharArray();
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(hex[random.nextInt(hex.length)]);
        return sb.toString();
    }
}
