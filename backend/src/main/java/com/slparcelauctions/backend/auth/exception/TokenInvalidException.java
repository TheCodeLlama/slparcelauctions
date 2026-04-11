package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code JwtService.parseAccessToken} on any validation failure other than expiry
 * (bad signature, wrong {@code type} claim, malformed payload), and by {@code RefreshTokenService.rotate}
 * when a submitted refresh token hash isn't found in the DB. Maps to 401 AUTH_TOKEN_INVALID.
 *
 * <p>The filter also maps missing {@code Authorization} header to AUTH_TOKEN_MISSING via the
 * {@code JwtAuthenticationEntryPoint}, which is a different code path (entry point, not handler).
 */
public class TokenInvalidException extends RuntimeException {
    public TokenInvalidException(String message) {
        super(message);
    }
}
