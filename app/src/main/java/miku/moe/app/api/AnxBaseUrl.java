package miku.moe.app.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class AnxBaseUrl {
    private static final String FALLBACK = "https://api.wincamp.web.id/animexnonton";
    private static final String PREFS = "anx_config";
    private static final String KEY_BASE_URL = "anx_base_url";
    private static final String USER_AGENT = AnxGuardInterceptor.userAgent();

    private static volatile String cached;
    private static OkHttpClient client;

    private AnxBaseUrl() {}

    public static void init(Context context) {
        try {
            SharedPreferences p = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String stored = p.getString(KEY_BASE_URL, null);
            if (isValid(stored)) {
                cached = normalize(stored);
            }
        } catch (Throwable ignored) {
        }
    }

    public static String base() {
        String value = cached;
        return value != null ? value : FALLBACK;
    }

    public static String apiBase() {
        return base() + "/new_api/";
    }

    public static void refreshAsync(final Context context) {
        try {
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    runRefresh(app);
                } catch (Throwable ignored) {
                }
            }, "anx-base-url").start();
        } catch (Throwable ignored) {
        }
    }

    private static void runRefresh(Context context) throws Exception {
        FormBody form = new FormBody.Builder()
                .add("version_code", AnxGuardInterceptor.VERSION_CODE)
                .build();
        Request request = new Request.Builder()
                .url(apiBase() + "get_config/")
                .header("User-Agent", USER_AGENT)
                .post(form)
                .build();
        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return;
            JSONObject json = new JSONObject(response.body().string());
            JSONObject config = json.optJSONObject("config");
            if (config == null) return;
            String serverUrl = config.optString("server_url", "");
            if (!isValid(serverUrl)) return;
            String normalized = normalize(serverUrl);
            if (!normalized.equals(base())) {
                context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_BASE_URL, normalized).apply();
                cached = normalized;
            }
        }
    }

    private static boolean isValid(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() < 10 || v.length() > 200) return false;
        if (!v.startsWith("http://") && !v.startsWith("https://")) return false;
        if (!v.contains("://")) return false;
        for (int i = 0; i < v.length(); i++) {
            if (Character.isWhitespace(v.charAt(i))) return false;
        }
        return true;
    }

    private static String normalize(String value) {
        String v = value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
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
}
