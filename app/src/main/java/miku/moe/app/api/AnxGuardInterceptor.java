package miku.moe.app.api;

import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

public final class AnxGuardInterceptor implements Interceptor {
    private static final String TAG = "AnxGuard";
    private static final String MASTER_HEX =
            "0f4be99c888e987925fad6650daae56e5fc002ee1286a6c24fead72f29365862";
    private static final byte[] SALT = "anx-guard-v1-salt".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GET_SIG_SALT = "anx-get".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GATE_SALT = "anx-gate".getBytes(StandardCharsets.UTF_8);
    private static final String CERT = "1a70a8a890d82093";
    public static final String VERSION_CODE = "26";
    public static final String APP_VERSION_NAME = "26.10.8";
    private static final long GET_TTL_SECONDS = 300L;
    private static final String DESC_RUNTIME = "d:native-file";
    private static final MediaType FORM =
            MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

    private static final Set<String> GET_ENDPOINTS = new HashSet<>(Arrays.asList(
            "get_config",
            "get_posts",
            "home_info",
            "user_count",
            "count_view",
            "get_category_ongoing",
            "get_category_not_ongoing",
            "get_category_posts_secure",
            "get_anime_by_genre",
            "get_anime_genre_list",
            "search_category_collection",
            "search_anime_movie"
    ));

    private static final Set<String> AUTH_ENDPOINTS = new HashSet<>(Arrays.asList(
            "get_post_description",
            "user_rating",
            "user_ads",
            "user_inbox",
            "user_points",
            "user_sync"
    ));

    private static final Set<String> POST_ENDPOINTS = new HashSet<>(Arrays.asList(
            "get_post_description",
            "get_current_next_previous",
            "user_rating",
            "update_view",
            "update_fhd",
            "register_user",
            "device_status",
            "integrity_verify",
            "access_challenge",
            "socket_ticket",
            "user_ads",
            "user_inbox",
            "user_points",
            "user_sync",
            "guest_inbox",
            "admin_inbox",
            "admin_bandwidth",
            "ad_audit",
            "skip_feedback"
    ));

    public static String userAgent() {
        String release = Build.VERSION.RELEASE;
        if (release == null || release.trim().isEmpty()) release = "14";
        String model = Build.MODEL;
        if (model == null || model.trim().isEmpty()) model = "Android";
        return "AnimeXNonton/" + APP_VERSION_NAME + " (" + VERSION_CODE + "; Android "
                + release + "; " + model + ")";
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String encodedPath = request.url().encodedPath();
        int i = encodedPath.indexOf("/new_api/");
        if (i < 0) return chain.proceed(request);
        String api2Path = encodedPath.substring(i);
        String endpoint = endpointName(api2Path);
        boolean isGet = GET_ENDPOINTS.contains(endpoint);
        boolean isPost = POST_ENDPOINTS.contains(endpoint);
        if (!isGet && !isPost) return chain.proceed(request);

        String method = request.method();
        if (isGet) {
            if (!"POST".equalsIgnoreCase(method) && !"GET".equalsIgnoreCase(method)) {
                Log.w(TAG, "Skipping new_api signing for unsupported method " + method + " " + api2Path);
                return chain.proceed(request);
            }
        } else if (!"POST".equalsIgnoreCase(method)) {
            Log.w(TAG, "Skipping new_api body signing for unsupported method " + method + " " + api2Path);
            return chain.proceed(request);
        }

        Request base = request;
        if (isGet) {
            try {
                base = toGetRequest(request, endpoint);
            } catch (Exception e) {
                Log.e(TAG, "GET conversion failed for " + api2Path, e);
                AnxDiagnosticInterceptor.logParserError("GET conversion failed for " + api2Path, e);
                if (e instanceof IOException) throw (IOException) e;
                return chain.proceed(request);
            }
        }

        Request signed;
        try {
            signed = sign(base, api2Path, endpoint, isGet, CERT);
        } catch (Exception e) {
            Log.e(TAG, "Guard signing failed for " + api2Path, e);
            AnxDiagnosticInterceptor.logParserError("Guard signing failed for " + api2Path, e);
            if (e instanceof IOException) throw (IOException) e;
            return chain.proceed(request);
        }

        return chain.proceed(signed);
    }

    private static String endpointName(String api2Path) {
        String rest = api2Path.substring("/new_api/".length());
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    private static Request sign(Request request, String api2Path, String endpoint,
                                boolean isGet, String cert) throws Exception {
        return isGet
                ? buildSignedGet(request, api2Path, cert)
                : buildSignedPost(request, api2Path, endpoint, cert);
    }

    private static final java.util.Map<String, String[]> QUERY_ORDER = new java.util.HashMap<>();

    static {
        QUERY_ORDER.put("get_posts", new String[]{"page", "count", "isAPKvalid"});
        QUERY_ORDER.put("get_category_ongoing", new String[]{"page", "count", "lang", "isAPKvalid"});
        QUERY_ORDER.put("get_category_not_ongoing", new String[]{"page", "count", "lang", "isAPKvalid"});
        QUERY_ORDER.put("get_category_posts_secure", new String[]{"id", "isAPKvalid"});
        QUERY_ORDER.put("search_category_collection", new String[]{"search", "page", "count", "lang", "isAPKvalid"});
        QUERY_ORDER.put("search_anime_movie", new String[]{"search", "page", "count", "lang", "isAPKvalid", "films_only"});
        QUERY_ORDER.put("get_anime_by_genre", new String[]{"genre1", "genre2", "lang", "sort", "isAPKvalid"});
    }

    private static List<String[]> shapeQuery(String endpoint, List<String[]> pairs) {
        if ("get_anime_genre_list".equals(endpoint)) return new ArrayList<>();
        List<String[]> shaped = new ArrayList<>();
        for (String[] pair : pairs) {
            if ("get_anime_by_genre".equals(endpoint)
                    && ("page".equals(pair[0]) || "count".equals(pair[0]))) continue;
            shaped.add(pair);
        }
        final String[] order = QUERY_ORDER.get(endpoint);
        if (order != null) {
            final List<String> orderList = Arrays.asList(order);
            Collections.sort(shaped, (a, b) -> {
                int ia = orderList.indexOf(a[0]);
                int ib = orderList.indexOf(b[0]);
                if (ia < 0) ia = Integer.MAX_VALUE;
                if (ib < 0) ib = Integer.MAX_VALUE;
                return Integer.compare(ia, ib);
            });
        }
        return shaped;
    }

    private static Request toGetRequest(Request request, String endpoint) throws IOException {
        boolean post = !"GET".equalsIgnoreCase(request.method());
        HttpUrl url = request.url();
        String form = post ? extractFormFields(request.body()) : "";
        HttpUrl source = form.isEmpty() ? url : url.newBuilder().encodedQuery(form).build();
        List<String[]> pairs = new ArrayList<>();
        for (int k = 0; k < source.querySize(); k++) {
            String name = source.queryParameterName(k);
            if (name == null || name.isEmpty()) continue;
            String value = source.queryParameterValue(k);
            pairs.add(new String[]{name, value == null ? "" : value});
        }
        pairs = shapeQuery(endpoint, pairs);
        HttpUrl.Builder builder = url.newBuilder().query(null);
        for (String[] pair : pairs) builder.addQueryParameter(pair[0], pair[1]);
        Request.Builder rb = request.newBuilder().url(builder.build());
        if (post) {
            AnxDiagnosticInterceptor.recordOriginalFormPayload("GET", request.url().encodedPath(), form);
            rb.get().removeHeader("Content-Type").removeHeader("Content-Length");
        }
        return rb.build();
    }

    private static Request buildSignedGet(Request request, String api2Path, String cert)
            throws Exception {
        long ts = System.currentTimeMillis() / 1000L + GET_TTL_SECONDS;
        HttpUrl url = request.url();
        String canonicalQuery = canonicalQuery(url);

        byte[] session = h91b("master", hexToBytes(MASTER_HEX), SALT);
        byte[] sigKey = h91b("sig", session, GET_SIG_SALT);
        String signText = "GET\n" + api2Path + "\n" + canonicalQuery + "\n" + ts + "\n"
                + VERSION_CODE + "\n" + cert;
        String sig = hex(hmacSha256(sigKey, signText.getBytes(StandardCharsets.UTF_8)));

        String gate = hex(h91b("gate", session, GATE_SALT)).substring(0, 32);
        String encodedQuery = url.encodedQuery();
        String fullPath = url.encodedPath() + (encodedQuery == null ? "" : "?" + encodedQuery);
        String seed = ts + fullPath + " " + gate;
        byte[] md5 = MessageDigest.getInstance("MD5").digest(seed.getBytes(StandardCharsets.UTF_8));

        return request.newBuilder()
                .removeHeader("X-Anx-Ts")
                .removeHeader("X-Anx-N")
                .removeHeader("X-Anx-Rsp")
                .removeHeader("X-Anx-Sig")
                .removeHeader("X-Integrity")
                .removeHeader("Data-Agent")
                .removeHeader("Cache-Control")
                .removeHeader("Content-Type")
                .header("User-Agent", userAgent())
                .header("X-Anx-E", String.valueOf(ts))
                .header("X-Anx-Ver", VERSION_CODE)
                .header("X-Anx-Cert", cert)
                .header("X-Anx-S", sig)
                .header("X-Anx-L", urlSafeNoPad(md5))
                .build();
    }

    private static Request buildSignedPost(Request request, String api2Path, String endpoint,
                                           String cert) throws Exception {
        long ts = System.currentTimeMillis() / 1000L;
        byte[] nonce16 = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce16);

        byte[] session = h91b("master", hexToBytes(MASTER_HEX), SALT);
        byte[] reqKey = h91b("req", session, nonce16);
        byte[] sigKey = h91b("sig", session, nonce16);

        String plainBody = extractFormFields(request.body());
        AnxDiagnosticInterceptor.recordOriginalFormPayload(request.method(), api2Path, plainBody);
        if (request.body() == null || plainBody.isEmpty()) {
            MediaType originalType = request.body() == null ? null : request.body().contentType();
            throw new IOException("new_api POST form body unexpectedly empty for " + api2Path
                    + " (bodyType=" + (request.body() == null ? "<null>" : request.body().getClass().getName())
                    + ", contentType=" + (originalType == null ? "<null>" : originalType) + ")");
        }

        if ("get_post_description".equals(endpoint)) {
            String devId = AnxDeviceIdentity.peekDeviceId();
            if (!plainBody.contains("anx_rt=")) {
                plainBody += "&anx_rt=" + URLEncoder.encode(DESC_RUNTIME, "UTF-8");
            }
            if (devId != null && !devId.isEmpty() && !plainBody.contains("device_id=")) {
                plainBody += "&device_id=" + URLEncoder.encode(devId, "UTF-8");
            }
        }

        byte[] nonce12 = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(reqKey, "AES"),
                new GCMParameterSpec(128, nonce12));
        cipher.updateAAD(("anx-req|" + api2Path + "|" + ts).getBytes(StandardCharsets.UTF_8));
        byte[] ct = cipher.doFinal(plainBody.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[12 + ct.length];
        System.arraycopy(nonce12, 0, payload, 0, 12);
        System.arraycopy(ct, 0, payload, 12, ct.length);
        String encB64 = Base64.encodeToString(payload, Base64.NO_WRAP);
        String finalBody = "anx=" + URLEncoder.encode(encB64, "UTF-8");

        String params = sortedQueryParamValues(request.url());
        String bodyHash = sha256Hex(finalBody.getBytes(StandardCharsets.UTF_8));
        String canonical = request.method() + "\n"
                + api2Path + "\n"
                + params + "\n"
                + ts + "\n"
                + VERSION_CODE + "\n"
                + cert + "\n"
                + bodyHash;
        String sig = hex(hmacSha256(sigKey, canonical.getBytes(StandardCharsets.UTF_8)));

        RequestBody body = RequestBody.create(finalBody, FORM);
        Request.Builder builder = request.newBuilder()
                .method(request.method(), body)
                .removeHeader("X-Anx-E")
                .removeHeader("X-Anx-S")
                .removeHeader("X-Anx-L")
                .removeHeader("X-Integrity")
                .removeHeader("Data-Agent")
                .removeHeader("Cache-Control")
                .header("User-Agent", userAgent())
                .header("X-Anx-Ts", String.valueOf(ts))
                .header("X-Anx-N", urlSafeNoPad(nonce16))
                .header("X-Anx-Ver", VERSION_CODE)
                .header("X-Anx-Cert", cert)
                .header("X-Anx-Rsp", "1")
                .header("X-Anx-Sig", sig);
        if (AUTH_ENDPOINTS.contains(endpoint)) {
            String bearer = AnxAuth.bearer();
            if (bearer != null) builder.header("Authorization", bearer);
        }
        if (!"integrity_verify".equals(endpoint)) {
            String ticket = AnxDeviceIdentity.peekIntegrityTicket();
            if (ticket != null) builder.header("X-Integrity", ticket);
        }
        return builder.build();
    }

    private static String extractFormFields(RequestBody body) throws IOException {
        if (body == null) return "";
        MediaType type = body.contentType();
        boolean urlEncoded = type != null
                && "application".equalsIgnoreCase(type.type())
                && "x-www-form-urlencoded".equalsIgnoreCase(type.subtype());
        if (urlEncoded) {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            return buffer.readUtf8();
        }
        if (body instanceof FormBody) {
            FormBody form = (FormBody) body;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < form.size(); i++) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(form.name(i), "UTF-8"))
                  .append('=')
                  .append(URLEncoder.encode(form.value(i), "UTF-8"));
            }
            return sb.toString();
        }
        return "";
    }

    static String canonicalQuery(HttpUrl url) {
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < url.querySize(); i++) {
            String name = url.queryParameterName(i);
            String value = url.queryParameterValue(i);
            pairs.add(new String[]{name, value == null ? "" : value});
        }
        Collections.sort(pairs, (a, b) -> {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(p[0]).append('=').append(p[1]);
        }
        return sb.toString();
    }

    private static String sortedQueryParamValues(HttpUrl url) {
        List<String> values = new ArrayList<>();
        for (String name : url.queryParameterNames()) {
            for (String v : url.queryParameterValues(name)) values.add(v == null ? "" : v);
        }
        Collections.sort(values);
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append('&');
            sb.append(v);
        }
        return sb.toString();
    }

    private static byte[] hmacSha256(byte[] key, byte[] msg) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg);
    }

    static byte[] h91b(String label, byte[] key, byte[] nonce) throws GeneralSecurityException {
        byte[] effectiveNonce = nonce.length == 0 ? new byte[32] : nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(effectiveNonce, "HmacSHA256"));
        byte[] first = mac.doFinal(key);
        mac.init(new SecretKeySpec(first, "HmacSHA256"));
        return mac.doFinal((label + "\u0001").getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) throws GeneralSecurityException {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String urlSafeNoPad(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE)
                .replace("\n", "").replace("=", "");
    }
}
