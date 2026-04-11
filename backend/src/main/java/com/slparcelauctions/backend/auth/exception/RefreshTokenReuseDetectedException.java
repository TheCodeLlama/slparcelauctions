package com.slparcelauctions.backend.auth.exception;

import lombok.Getter;

/**
 * Thrown by {@code RefreshTokenService.rotate} when a refresh token with {@code revoked_at != null}
 * is presented — a signal of theft (the legitimate user already rotated this token, so whoever is
 * submitting it now is replaying an old copy). The service has already cascaded the revocation
 * (revokes all of the user's refresh tokens, bumps {@code users.token_version}) before this
 * exception is thrown. The handler maps to 401 AUTH_REFRESH_TOKEN_REUSED and logs a WARN line
 * with the user ID, request IP, and User-Agent for audit.
 *
 * <p>See FOOTGUNS §B.6 — this cascade is the entire reason DB-backed refresh tokens are worth
 * the cost over JWT-based refresh tokens.
 */
@Getter
public class RefreshTokenReuseDetectedException extends RuntimeException {
    private final Long userId;

    public RefreshTokenReuseDetectedException(Long userId) {
        super("Refresh token reuse detected for user " + userId + "; all sessions revoked.");
        this.userId = userId;
    }
}
