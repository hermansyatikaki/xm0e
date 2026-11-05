package miku.moe.app;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lapisan kripto Animeloverz (AnimeVerse) v3.
 *
 * - X25519 (RFC 7748) murni BigInteger: XDH bawaan JDK baru ada di API 33, sedangkan minSdk project 23.
 * - KDF terverifikasi live: h1 = HMAC(key = clientPub + serverPub, data = shared); pass = b64(HMAC(h1, label + 0x01)).
 * - Body: base64("Salted__" + salt8 + AES-256-CBC/PKCS7), key/iv dari EVP_BytesToKey(MD5, 1 iterasi).
 *
 * Sengaja tanpa dependency Android supaya bisa diuji di JVM biasa.
 */
public final class AnimeLoverzCrypto {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final byte[] KDF_INFO = "anilovers-enc-session-v3".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private AnimeLoverzCrypto() { }

    // ---------------------------------------------------------------- X25519

    public static byte[] randomPrivateKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    public static byte[] publicKey(byte[] privateKey) {
        byte[] base = new byte[32];
        base[0] = 9;
        return x25519(privateKey, base);
    }

    public static byte[] x25519(byte[] scalar, byte[] uCoordinate) {
        if (scalar == null || scalar.length != 32 || uCoordinate == null || uCoordinate.length != 32) {
            throw new IllegalArgumentException("X25519 butuh 32 byte");
        }
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= (byte) 127;
        k[31] |= (byte) 64;
        BigInteger kInt = decodeLittleEndian(k);
        byte[] u = uCoordinate.clone();
        u[31] &= (byte) 127;
        BigInteger x1 = decodeLittleEndian(u).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int bit = kInt.testBit(t) ? 1 : 0;
            swap ^= bit;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = bit;
            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger sum = da.add(cb).mod(P);
            BigInteger diff = da.subtract(cb).mod(P);
            x3 = sum.multiply(sum).mod(P);
            z3 = x1.multiply(diff.multiply(diff).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap == 1) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        BigInteger result = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);
        return encodeLittleEndian(result);
    }

    private static BigInteger decodeLittleEndian(byte[] in) {
        byte[] be = new byte[in.length];
        for (int i = 0; i < in.length; i++) be[i] = in[in.length - 1 - i];
        return new BigInteger(1, be);
    }

    private static byte[] encodeLittleEndian(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int idx = raw.length - 1 - i;
            out[i] = idx >= 0 ? raw[idx] : 0;
        }
        return out;
    }

    // ------------------------------------------------------------------- KDF

    public static String derivePassphrase(byte[] sharedSecret, byte[] clientPublic, byte[] serverPublic) throws Exception {
        byte[] key = new byte[clientPublic.length + serverPublic.length];
        System.arraycopy(clientPublic, 0, key, 0, clientPublic.length);
        System.arraycopy(serverPublic, 0, key, clientPublic.length, serverPublic.length);
        byte[] h1 = hmacSha256(key, sharedSecret);
        byte[] info = new byte[KDF_INFO.length + 1];
        System.arraycopy(KDF_INFO, 0, info, 0, KDF_INFO.length);
        info[info.length - 1] = 0x01;
        return base64Encode(hmacSha256(h1, info));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // ------------------------------------------------- AES "Salted__" (OpenSSL)

    public static String encryptSalted(String plaintext, String passphrase) throws Exception {
        byte[] salt = new byte[8];
        RANDOM.nextBytes(salt);
        byte[][] keyIv = evpBytesToKey(passphrase.getBytes(StandardCharsets.UTF_8), salt);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyIv[0], "AES"), new IvParameterSpec(keyIv[1]));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[16 + ct.length];
        System.arraycopy("Salted__".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 8);
        System.arraycopy(salt, 0, out, 8, 8);
        System.arraycopy(ct, 0, out, 16, ct.length);
        return base64Encode(out);
    }

    /** Melempar exception bila bukan Salted__, kunci salah (padding rusak), atau hasil bukan UTF-8. */
    public static String decryptSalted(String base64Body, String passphrase) throws Exception {
        byte[] raw = base64Decode(base64Body);
        if (raw.length < 32 || !"Salted__".equals(new String(raw, 0, 8, StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Bukan format Salted__");
        }
        byte[] salt = new byte[8];
        System.arraycopy(raw, 8, salt, 0, 8);
        byte[][] keyIv = evpBytesToKey(passphrase.getBytes(StandardCharsets.UTF_8), salt);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyIv[0], "AES"), new IvParameterSpec(keyIv[1]));
        byte[] pt = cipher.doFinal(raw, 16, raw.length - 16);
        return new String(pt, StandardCharsets.UTF_8);
    }

    public static boolean looksSalted(String body) {
        return body != null && body.trim().startsWith("U2FsdGVkX1");
    }

    private static byte[][] evpBytesToKey(byte[] password, byte[] salt) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] derived = new byte[48];
        byte[] prev = new byte[0];
        int filled = 0;
        while (filled < 48) {
            md5.reset();
            md5.update(prev);
            md5.update(password);
            md5.update(salt);
            prev = md5.digest();
            int n = Math.min(prev.length, 48 - filled);
            System.arraycopy(prev, 0, derived, filled, n);
            filled += n;
        }
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        System.arraycopy(derived, 0, key, 0, 32);
        System.arraycopy(derived, 32, iv, 0, 16);
        return new byte[][]{key, iv};
    }

    // ---------------------------------------------------------------- Base64

    public static String base64Encode(byte[] in) {
        StringBuilder sb = new StringBuilder(((in.length + 2) / 3) * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            sb.append(B64.charAt(b0 >> 2));
            sb.append(B64.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < in.length ? B64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < in.length ? B64.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    public static byte[] base64Decode(String text) {
        int[] vals = new int[text.length()];
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '-') ch = '+';
            else if (ch == '_') ch = '/';
            int v = B64.indexOf(ch);
            if (v >= 0) vals[n++] = v;
        }
        int outLen = n * 6 / 8;
        byte[] out = new byte[outLen];
        int bits = 0, acc = 0, o = 0;
        for (int i = 0; i < n && o < outLen; i++) {
            acc = (acc << 6) | vals[i];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) ((acc >> bits) & 0xff);
            }
        }
        return out;
    }
}
