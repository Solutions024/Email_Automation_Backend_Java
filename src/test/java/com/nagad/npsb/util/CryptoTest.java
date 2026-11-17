package com.nagad.npsb.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoTest {
    private static final byte[] KEY = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    @Test
    void aesGcmRoundTripsAndRefusesChangedValues() throws Exception {
        byte[] secret = "smtp-pass-123".getBytes(StandardCharsets.UTF_8);
        String a = Crypto.aesGcmEncrypt(KEY, secret), b = Crypto.aesGcmEncrypt(KEY, secret);
        assertNotEquals(a, b, "a new random nonce every time");
        assertArrayEquals(secret, Crypto.aesGcmDecrypt(KEY, a));

        byte[] changed = Base64.getUrlDecoder().decode(a);
        changed[changed.length - 1] ^= 1;
        assertThrows(Crypto.InvalidToken.class, () -> Crypto.aesGcmDecrypt(KEY, Base64.getUrlEncoder().encodeToString(changed)));
        byte[] otherKey = KEY.clone();
        otherKey[0] ^= 1;
        assertThrows(Crypto.InvalidToken.class, () -> Crypto.aesGcmDecrypt(otherKey, a));
        assertThrows(Crypto.InvalidToken.class, () -> Crypto.aesGcmDecrypt(KEY, "not base64 !"));
        assertThrows(Crypto.InvalidToken.class, () -> Crypto.aesGcmDecrypt(KEY, "AAAA"));
    }

    /** A Fernet token made with openssl (AES-128-CBC + HMAC-SHA256), as older versions saved secrets: still readable. */
    @Test
    void oldFernetValuesCanStillBeRead() throws Exception {
        String token = "gAAAAABlAAAAoKGio6SlpqeoqaqrrK2ur-GLkKVcacs_c9uomJFIgQd_S7Ia7fMgUowgE0v5zOqeE7DJw_uWAWH9xAIK9H9QlA==";
        assertEquals("smtp-pass-123", new String(Crypto.fernetDecrypt(KEY, token), StandardCharsets.UTF_8));
        String changed = token.substring(0, 40) + (token.charAt(40) == 'A' ? 'B' : 'A') + token.substring(41);
        assertThrows(Crypto.InvalidToken.class, () -> Crypto.fernetDecrypt(KEY, changed));
    }
}
