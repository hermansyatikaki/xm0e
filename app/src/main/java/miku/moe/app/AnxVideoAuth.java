package miku.moe.app;

import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Per-episode video authorization for Anime X Nonton (new_api).
 *
 * The retired static Basic credential no longer works: get_post_description
 * now returns an encrypted {@code video_auth} field (same AES-GCM scheme and
 * {@code secretKey} as the channel URLs). Decrypted, it is a complete
 * Authorization header value (currently "Basic <base64 user:pass>").
 *
 * Mirrors the official app: stream URLs that carry a signature
 * ({@code md5=} + {@code expires=}) must be requested WITHOUT any
 * Authorization header; all other stream URLs use the decrypted
 * per-episode {@code video_auth}.
 */
public final class AnxVideoAuth {

    private AnxVideoAuth() {}

    /** Signed CDN URLs (whatbox md5/expires) play without Authorization. */
    public static boolean isSignedUrl(String url) {
        return url != null && url.contains("md5=") && url.contains("expires=");
    }

    /**
     * Full Authorization header value from a get_post_description response,
     * or null when the response carries no usable video_auth.
     */
    public static String fromDescription(JSONObject json) {
        if (json == null) return null;
        String secretKey = json.optString("secretKey", "");
        String decrypted = AnimeXnontonUrlDecoder.decryptText(json.optString("video_auth", ""), secretKey);
        return normalize(decrypted);
    }

    /**
     * Normalizes a raw credential into an Authorization header value:
     * "Basic ..." passes through, "user:pass" is Base64-wrapped, anything
     * else non-empty is returned as-is. Null/empty yields null.
     */
    public static String normalize(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return null;
        if (v.regionMatches(true, 0, "Basic ", 0, 6)) return v;
        if (v.contains(":")) {
            return "Basic " + Base64.encodeToString(v.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
        return v;
    }

    /** The Authorization value to actually send for {@code url}; null = send none. */
    public static String forUrl(String url, String auth) {
        if (auth == null || auth.trim().isEmpty()) return null;
        return isSignedUrl(url) ? null : auth;
    }

    /**
     * Decodes a "Basic base64(user:pass)" header value into
     * {@code [user, pass]} for embedding into a URI authority; null if the
     * value is not a decodable Basic credential.
     */
    public static String[] decodeBasicCredentials(String auth) {
        if (auth == null) return null;
        String v = auth.trim();
        if (!v.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        try {
            String decoded = new String(Base64.decode(v.substring(6).trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep <= 0) return null;
            return new String[]{decoded.substring(0, sep), decoded.substring(sep + 1)};
        } catch (Exception ignored) {
            return null;
        }
    }
}
