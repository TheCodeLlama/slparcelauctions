package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.RefreshTokenReuseDetectedException;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Lifecycle manager for DB-backed refresh tokens.
 *
 * <p>The raw token is never stored — only its SHA-256 hex digest (see {@link TokenHasher}).
 * The security-critical path is {@link #rotate}: if a token that has already been revoked is
 * presented, the service detects reuse and cascades: all of the user's refresh tokens are revoked
 * and {@code users.token_version} is bumped, immediately invalidating every outstanding access
 * token. See FOOTGUNS §B.6.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final RefreshTokenRepository repository;
    private final UserService userService;
    private final JwtConfig jwtConfig;
    private final Clock clock;

    // -------------------------------------------------------------------------
    // Public record types
    // -------------------------------------------------------------------------

    /**
     * Return value of {@link #issueForUser}: carries the raw token (for the cookie) and the
     * expiry time (for the Set-Cookie max-age / Expires attributes).
     */
    public record IssuedRefreshToken(String rawToken, OffsetDateTime expiresAt) {}

    /**
     * Return value of {@link #rotate}: the caller uses {@code rawToken} for the new cookie and
     * {@code userId} to mint a new access token.
     */
    public record RotationResult(Long userId, String rawToken, OffsetDateTime expiresAt) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates a new refresh token for the given user, persists only its hash, and returns the
     * raw token so the caller can place it in an HttpOnly cookie.
     *
     * @param userId    the user this token belongs to
     * @param userAgent truncated to {@value #MAX_USER_AGENT_LENGTH} chars for storage
     * @param ipAddress stored as-is for audit (IPv4 or IPv6, up to 45 chars)
     * @return raw token + expiry
     */
    public IssuedRefreshToken issueForUser(Long userId, String userAgent, String ipAddress) {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String tokenHash = TokenHasher.sha256Hex(rawToken);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(jwtConfig.getRefreshTokenLifetime());

        String truncatedAgent = userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH
                ? userAgent.substring(0, MAX_USER_AGENT_LENGTH)
                : userAgent;

        RefreshToken row = RefreshToken.builder()
                .userId(userId)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .userAgent(truncatedAgent)
                .ipAddress(ipAddress)
                .build();

        repository.save(row);
        return new IssuedRefreshToken(rawToken, expiresAt);
    }

    /**
     * Rotates a refresh token: validates the presented raw token, revokes the old row, and issues
     * a new one — all in a single transaction.
     *
     * <p><strong>Reuse detection (FOOTGUNS §B.6):</strong> if the token is found but already has
     * {@code revokedAt != null}, an attacker is replaying a previously-rotated token. The service
     * cascades: all of the user's refresh tokens are revoked and {@code token_version} is bumped
     * (invalidating all outstanding access tokens), then {@link RefreshTokenReuseDetectedException}
     * is thrown.
     *
     * <p>Three writes in one transaction: old row's {@code lastUsedAt} + {@code revokedAt} (via
     * dirty checking), plus a new row insert.
     *
     * @param rawToken  the value from the client's HttpOnly cookie
     * @param userAgent recorded on the new row for audit
     * @param ipAddress recorded on the new row for audit
     * @return userId, new raw token, and new expiry
     * @throws TokenInvalidException              if the hash is not found
     * @throws RefreshTokenReuseDetectedException if the token was already revoked (cascade applied)
     * @throws TokenExpiredException              if the token has passed its expiry time
     */
    @Transactional
    public RotationResult rotate(String rawToken, String userAgent, String ipAddress) {
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken row = repository.findByTokenHash(hash)
                .orElseThrow(() -> new TokenInvalidException("Refresh token not found."));

        if (row.getRevokedAt() != null) {
            // REUSE DETECTED — cascade. FOOTGUNS §B.6.
            log.warn("Refresh token reuse detected: userId={} ip={} userAgent={}",
                    row.getUserId(), ipAddress, userAgent);
            repository.revokeAllByUserId(row.getUserId(), OffsetDateTime.now(clock));
            userService.bumpTokenVersion(row.getUserId());  // invalidate live access tokens
            throw new RefreshTokenReuseDetectedException(row.getUserId());
        }

        if (row.getExpiresAt().isBefore(OffsetDateTime.now(clock))) {
            throw new TokenExpiredException("Refresh token has expired.");
        }

        // Happy path: rotate
        OffsetDateTime now = OffsetDateTime.now(clock);
        row.setLastUsedAt(now);
        row.setRevokedAt(now);
        // Dirty checking flushes on transaction commit

        IssuedRefreshToken newToken = issueForUser(row.getUserId(), userAgent, ipAddress);
        return new RotationResult(row.getUserId(), newToken.rawToken(), newToken.expiresAt());
    }

    /**
     * Revokes a single token by its raw value. Idempotent — if the token is unknown or already
     * revoked, the method returns silently without throwing or logging details that could reveal
     * token validity. See FOOTGUNS §B.7.
     *
     * @param rawToken the value from the client's HttpOnly cookie; null/blank is ignored
     */
    @Transactional
    public void revokeByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;  // null-safe
        repository.findByTokenHash(TokenHasher.sha256Hex(rawToken)).ifPresent(row -> {
            if (row.getRevokedAt() == null) {
                row.setRevokedAt(OffsetDateTime.now(clock));  // dirty checking saves
            }
        });
        // NEVER throws. NEVER logs details that could leak validity (FOOTGUNS §B.7).
    }

    /**
     * Revokes all active refresh tokens for the given user in a single UPDATE. Used by
     * logout-all-devices and the reuse-detection cascade.
     *
     * @param userId the user whose tokens should be revoked
     * @return the number of rows updated
     */
    @Transactional
    public int revokeAllForUser(Long userId) {
        return repository.revokeAllByUserId(userId, OffsetDateTime.now(clock));
    }
}
