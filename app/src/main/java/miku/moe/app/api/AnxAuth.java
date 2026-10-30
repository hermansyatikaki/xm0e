package miku.moe.app.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Penyimpan token login ANX untuk Model W.
 *
 * Pengganti AnxDebugAuth (yang menanam satu token testing kedaluwarsa di kode).
 * Di Model W, token TIDAK pernah ditulis di kode: aplikasi hanya menyimpan
 * ID token berumur 1 jam + app_session dari backend anx.mikumoe.my.id.
 * Kalau ID token kedaluwarsa, AnxAuth minta yang segar ke backend lewat
 * app_session; seed refresh token tetap tinggal di server, tidak di HP.
 *
 * Dipakai AnxGuardInterceptor lewat bearer() (tanpa argumen, sama seperti dulu).
 */
public final class AnxAuth {
    public static final String BACKEND_BASE = "https://anx.mikumoe.my.id";

    private static final String PREFS = "anx_auth";
    private static final String KEY_ID_TOKEN = "id_token";
    private static final String KEY_EXP_MS = "expires_at_ms";
    private static final String KEY_SESSION = "app_session";
    private static final long SKEW_MS = 30_000L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static Context appContext;
    private static String lastLoginMessage;
    private static boolean lastLoginConsumed = true;

    private AnxAuth() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Token Bearer untuk interceptor, atau null kalau belum login / gagal refresh. */
    public static String bearer() {
        if (appContext == null) return null;
        SharedPreferences p = prefs();
        String idToken = p.getString(KEY_ID_TOKEN, null);
        if (idToken == null || idToken.isEmpty()) return null;
        long expMs = p.getLong(KEY_EXP_MS, 0L);
        if (expMs - SKEW_MS > System.currentTimeMillis()) {
            return "Bearer " + idToken;
        }
        String session = p.getString(KEY_SESSION, null);
        if (session == null || session.isEmpty()) return null;
        String fresh = refreshViaBackend(session);
        if (fresh == null) return null;
        return "Bearer " + fresh;
    }

    /** True kalau aplikasi memegang app_session (login pernah berhasil). */
    public static boolean isLoggedIn() {
        if (appContext == null) return false;
        String session = prefs().getString(KEY_SESSION, null);
        return session != null && !session.isEmpty();
    }

    public static void saveLogin(String idToken, long expiresInSec, String appSession) {
        if (appContext == null) return;
        prefs().edit()
                .putString(KEY_ID_TOKEN, idToken)
                .putLong(KEY_EXP_MS, System.currentTimeMillis() + expiresInSec * 1000L)
                .putString(KEY_SESSION, appSession)
                .apply();
    }

    public static void logout() {
        if (appContext == null) return;
        prefs().edit().clear().apply();
    }

    /** Pesan hasil login terakhir untuk di-toast fragment (sekali ambil). */
    public static void setLastLoginResult(String message) {
        lastLoginMessage = message;
        lastLoginConsumed = false;
    }

    public static String takeLastLoginResult() {
        if (lastLoginConsumed) return null;
        lastLoginConsumed = true;
        return lastLoginMessage;
    }

    /**
     * Minta ID token segar ke backend. Jalan di thread pemanggil (interceptor
     * memanggil bearer() dari thread jaringan, jadi aman). Mengembalikan
     * ID token baru, atau null kalau gagal.
     */
    private static String refreshViaBackend(String session) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        try {
            JSONObject body = new JSONObject();
            body.put("app_session", session);
            Request request = new Request.Builder()
                    .url(BACKEND_BASE + "/anx/refresh")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                JSONObject json = new JSONObject(response.body().string());
                String idToken = json.optString("id_token", "");
                long expiresIn = json.optLong("expires_in", 0L);
                if (idToken.isEmpty() || expiresIn <= 0L) return null;
                prefs().edit()
                        .putString(KEY_ID_TOKEN, idToken)
                        .putLong(KEY_EXP_MS, System.currentTimeMillis() + expiresIn * 1000L)
                        .apply();
                return idToken;
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
