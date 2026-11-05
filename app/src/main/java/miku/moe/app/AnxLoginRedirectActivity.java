package miku.moe.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import miku.moe.app.api.AnxAuth;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Penangkap deep link Model W: mikumoe://anx-login?code=...
 *
 * Website anx.mikumoe.my.id mengarahkan browser ke link ini sesudah pengguna
 * mengetuk "Kirim ke aplikasi". Activity ini menukar kode sekali pakai itu
 * ke backend (POST /anx/token) lalu menyimpan ID token + app_session lewat
 * AnxAuth. Tidak ada token yang tampil di UI atau log.
 */
public class AnxLoginRedirectActivity extends AppCompatActivity {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Memproses login Anx...");
        text.setTextSize(16f);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        setContentView(text);

        if (savedInstanceState != null) {
            // Dibuat ulang (mis. rotasi): jangan tukar kode dua kali.
            finish();
            return;
        }
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data == null) {
            finish();
            return;
        }
        String error = data.getQueryParameter("error");
        String code = data.getQueryParameter("code");
        if (error != null || code == null || code.isEmpty()) {
            AnxAuth.setLastLoginResult("Login Anx dibatalkan.");
            Toast.makeText(this, "Login Anx dibatalkan.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        new Thread(() -> exchangeCode(code)).start();
    }

    private void exchangeCode(String code) {
        String result;
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        try {
            JSONObject body = new JSONObject();
            body.put("code", code);
            Request request = new Request.Builder()
                    .url(AnxAuth.BACKEND_BASE + "/anx/token")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    result = "Login Anx gagal: backend tidak merespons.";
                } else {
                    JSONObject json = new JSONObject(response.body().string());
                    if (!response.isSuccessful()) {
                        result = mapError(json.optString("error", ""));
                    } else {
                        String idToken = json.optString("id_token", "");
                        long expiresIn = json.optLong("expires_in", 0L);
                        String session = json.optString("app_session", "");
                        if (idToken.isEmpty() || session.isEmpty() || expiresIn <= 0L) {
                            result = "Login Anx gagal: respons backend tidak lengkap.";
                        } else {
                            AnxAuth.saveLogin(idToken, expiresIn, session);
                            result = "Login Anx berhasil. HD/FHD aktif.";
                        }
                    }
                }
            }
        } catch (Exception e) {
            result = "Login Anx gagal: tidak ada koneksi ke backend.";
        }
        final String message = result;
        runOnUiThread(() -> {
            AnxAuth.setLastLoginResult(message);
            Toast.makeText(AnxLoginRedirectActivity.this, message, Toast.LENGTH_SHORT).show();
            // Kembali ke MainActivity supaya pengguna pulang ke aplikasi, bukan browser.
            Intent main = new Intent(AnxLoginRedirectActivity.this, MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(main);
            finish();
        });
    }

    private String mapError(String error) {
        switch (error) {
            case "bad_or_expired_code":
                return "Login Anx gagal: kode kedaluwarsa. Login ulang di website.";
            case "seed_missing":
                return "Login Anx gagal: backend belum ada seed (tahap testing).";
            case "mint_failed":
                return "Login Anx gagal: backend gagal membuat token.";
            default:
                return "Login Anx gagal. Coba lagi.";
        }
    }
}
