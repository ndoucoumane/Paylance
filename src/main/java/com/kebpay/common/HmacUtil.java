package com.kebpay.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Calcul et vérification de signatures HMAC-SHA256 sur les payloads webhook bruts.
 */
public final class HmacUtil {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacUtil() {
    }

    public static String signHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Impossible de calculer la signature HMAC", e);
        }
    }

    /**
     * Comparaison en temps constant pour éviter les attaques par timing sur la validation de signature.
     */
    public static boolean matches(String payload, String secret, String expectedSignatureHex) {
        if (expectedSignatureHex == null || expectedSignatureHex.isBlank()) {
            return false;
        }
        String computed = signHex(payload, secret);
        return constantTimeEquals(computed.toLowerCase(), expectedSignatureHex.trim().toLowerCase());
    }

    /** Comparaison en temps constant, sensible à la casse — pour comparer deux secrets/tokens bruts. */
    public static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }
}
