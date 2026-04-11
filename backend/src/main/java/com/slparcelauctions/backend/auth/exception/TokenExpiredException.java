package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code JwtService.parseAccessToken} when the access token's {@code exp} claim is
 * in the past, or by {@code RefreshTokenService.rotate} when a refresh token row's
 * {@code expires_at} is in the past. Maps to 401 AUTH_TOKEN_EXPIRED.
 */
public class TokenExpiredException extends RuntimeException {
    public TokenExpiredException(String message) {
        super(message);
    }
}
