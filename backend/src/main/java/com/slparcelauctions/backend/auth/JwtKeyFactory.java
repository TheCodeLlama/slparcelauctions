package com.slparcelauctions.backend.auth;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;

/**
 * Shared JWT signing-key derivation used by both production ({@link com.slparcelauctions.backend.auth.config.JwtConfig})
 * and tests ({@code JwtTestFactory}). Exists as a separate class so the key shape cannot drift
 * between prod and test code paths. If the production key derivation changes, this helper changes
 * once and both sides follow.
 */
public final class JwtKeyFactory {

    // HS256 requires keys ≥ 256 bits per RFC 7518 §3.2.
    private static final int MIN_KEY_BYTES = 32;

    private JwtKeyFactory() {}

    public static SecretKey buildKey(String secretBase64) {
        byte[] decoded = Decoders.BASE64.decode(secretBase64);
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                "jwt.secret must decode to at least 32 bytes (256 bits). "
                + "Got " + decoded.length + " bytes.");
        }
        return Keys.hmacShaKeyFor(decoded);
    }
}
