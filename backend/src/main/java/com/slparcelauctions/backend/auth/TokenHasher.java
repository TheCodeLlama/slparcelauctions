package com.slparcelauctions.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared hashing and secure-random helpers for refresh-token lifecycle.
 *
 * <p>Used by both {@code RefreshTokenService} (production path) and {@code RefreshTokenTestFixture}
 * (test path) so the hashing algorithm and random source cannot drift. Changing the hashing
 * algorithm here requires updating both call sites — see FOOTGUNS §B.8 for the "raw refresh token
 * never lives in the DB" rule that this helper enforces.
 */
public final class TokenHasher {

    private static final SecureRandom SECURE_RANDOM;

    static {
        try {
            SECURE_RANDOM = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SecureRandom strong instance unavailable", e);
        }
    }

    private TokenHasher() {}

    /**
     * Returns the SHA-256 hash of the input as lowercase hex (64 chars).
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Generates {@code byteCount} bytes of secure randomness and returns them as a base64url
     * string with no padding. For {@code byteCount = 32}, the output is 43 chars.
     */
    public static String secureRandomBase64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
