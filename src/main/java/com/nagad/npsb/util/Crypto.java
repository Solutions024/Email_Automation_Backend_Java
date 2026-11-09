package com.nagad.npsb.util;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hashing, random tokens, Fernet and JWT (HS256), compatible with what the Python backend stored. */
public final class Crypto {
    private Crypto() {}

    public static final SecureRandom RANDOM = new SecureRandom();

    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha1Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** hmac.compare_digest */
    public static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** secrets.token_hex(n) */
    public static String tokenHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** secrets.token_urlsafe(n) */
    public static String tokenUrlsafe(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** f"{secrets.randbelow(10**6):06d}" */
    public static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), iterations).hex() */
    public static String pbkdf2Hex(String password, String salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(key);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- Fernet (cryptography.fernet)

    public static final class InvalidToken extends Exception {
        public InvalidToken() { super("Invalid Fernet token"); }
    }

    /** Fernet(urlsafe_b64encode(key32)).encrypt(data) */
    public static String fernetEncrypt(byte[] key32, byte[] data) {
        try {
            byte[] signing = Arrays.copyOfRange(key32, 0, 16), encryption = Arrays.copyOfRange(key32, 16, 32);
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryption, "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(data);
            ByteBuffer basic = ByteBuffer.allocate(1 + 8 + 16 + ciphertext.length);
            basic.put((byte) 0x80).putLong(System.currentTimeMillis() / 1000).put(iv).put(ciphertext);
            byte[] hmac = hmacSha256(signing, basic.array());
            byte[] token = ByteBuffer.allocate(basic.capacity() + 32).put(basic.array()).put(hmac).array();
            return Base64.getUrlEncoder().encodeToString(token);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] fernetDecrypt(byte[] key32, String token) throws InvalidToken {
        byte[] data;
        try {
            data = Base64.getUrlDecoder().decode(token.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidToken();
        }
        if (data.length < 1 + 8 + 16 + 16 + 32 || (data[0] & 0xff) != 0x80) throw new InvalidToken();
        byte[] signing = Arrays.copyOfRange(key32, 0, 16), encryption = Arrays.copyOfRange(key32, 16, 32);
        byte[] basic = Arrays.copyOfRange(data, 0, data.length - 32);
        byte[] hmac = Arrays.copyOfRange(data, data.length - 32, data.length);
        if (!MessageDigest.isEqual(hmac, hmacSha256(signing, basic))) throw new InvalidToken();
        try {
            byte[] iv = Arrays.copyOfRange(basic, 9, 25);
            byte[] ciphertext = Arrays.copyOfRange(basic, 25, basic.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryption, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new InvalidToken();
        }
    }

    // ---------------------------------------------------------------- JWT (PyJWT, HS256)

    public static class JwtError extends Exception {
        public JwtError(String message) { super(message); }
    }

    public static final class JwtExpired extends JwtError {
        public JwtExpired() { super("Signature has expired"); }
    }

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    /** jwt.encode(payload, secret, algorithm="HS256") */
    public static String jwtEncode(Map<String, Object> payload, String secret) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String body;
        try {
            body = PyJson.MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String signingInput = B64URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + B64URL.encodeToString(body.getBytes(StandardCharsets.UTF_8));
        byte[] signature = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64URL.encodeToString(signature);
    }

    private static byte[] b64(String part) throws JwtError {
        try {
            return Base64.getUrlDecoder().decode(part.replace("=", ""));
        } catch (IllegalArgumentException e) {
            throw new JwtError("Invalid crypto padding");
        }
    }

    /**
     * jwt.decode(token, secret, algorithms=["HS256"], issuer=issuer, options={"require": required}).
     * Throws JwtExpired for an expired token, JwtError for any other problem.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> jwtDecode(String token, String secret, String issuer, String... required) throws JwtError {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw new JwtError("Not enough segments");
        Map<String, Object> header, payload;
        try {
            header = PyJson.MAPPER.readValue(b64(parts[0]), LinkedHashMap.class);
            payload = PyJson.MAPPER.readValue(b64(parts[1]), LinkedHashMap.class);
        } catch (JwtError e) {
            throw e;
        } catch (Exception e) {
            throw new JwtError("Invalid token");
        }
        if (header == null || payload == null || !"HS256".equals(header.get("alg"))) throw new JwtError("The specified alg value is not allowed");
        byte[] expected = hmacSha256(secret.getBytes(StandardCharsets.UTF_8),
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!MessageDigest.isEqual(expected, b64(parts[2]))) throw new JwtError("Signature verification failed");
        for (String claim : required) {
            if (!payload.containsKey(claim)) throw new JwtError("Token is missing the \"" + claim + "\" claim");
        }
        long now = System.currentTimeMillis() / 1000;
        if (payload.containsKey("iat")) {
            Long iat = asInt(payload.get("iat"));
            if (iat == null) throw new JwtError("Issued At claim (iat) must be an integer.");
            if (iat > now) throw new JwtError("The token is not yet valid (iat)");
        }
        if (payload.containsKey("nbf")) {
            Long nbf = asInt(payload.get("nbf"));
            if (nbf == null) throw new JwtError("Not Before claim (nbf) must be an integer.");
            if (nbf > now) throw new JwtError("The token is not yet valid (nbf)");
        }
        if (payload.containsKey("exp")) {
            Long exp = asInt(payload.get("exp"));
            if (exp == null) throw new JwtError("Expiration Time claim (exp) must be an integer.");
            if (exp <= now) throw new JwtExpired();
        }
        if (issuer != null && !issuer.equals(payload.get("iss"))) throw new JwtError("Invalid issuer");
        if (payload.containsKey("aud")) throw new JwtError("Invalid audience");
        if (payload.containsKey("sub") && !(payload.get("sub") instanceof String)) throw new JwtError("Subject must be a string");
        return payload;
    }

    private static Long asInt(Object v) {
        if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
        if (v instanceof Double d && d == Math.floor(d) && !d.isInfinite()) return d.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.strip()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
