package com.slparcelauctions.backend.auth.test;

import com.slparcelauctions.backend.auth.RefreshToken;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.auth.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;

/**
 * Test helper for inserting {@link RefreshToken} rows in integration tests. Uses the same
 * {@link TokenHasher#sha256Hex(String)} helper as production so the hashing path cannot drift.
 *
 * <p>Each insert method returns an {@link InsertedToken} containing the raw token string and the
 * row id — tests replay the raw token through {@code /api/v1/auth/refresh} and assert DB state via
 * the id.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenTestFixture {

    private final RefreshTokenRepository refreshTokenRepository;

    public InsertedToken insertValid(Long userId) {
        return insertWithExpiry(userId, OffsetDateTime.now().plusDays(7));
    }

    public InsertedToken insertRevoked(Long userId) {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken row = RefreshToken.builder()
            .userId(userId)
            .tokenHash(hash)
            .expiresAt(OffsetDateTime.now().plusDays(7))
            .revokedAt(OffsetDateTime.now())
            .build();
        RefreshToken saved = refreshTokenRepository.save(row);
        return new InsertedToken(saved.getId(), rawToken, hash);
    }

    public InsertedToken insertExpired(Long userId) {
        return insertWithExpiry(userId, OffsetDateTime.now().minusDays(1));
    }

    public InsertedToken insertWithExpiry(Long userId, OffsetDateTime expiresAt) {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken row = RefreshToken.builder()
            .userId(userId)
            .tokenHash(hash)
            .expiresAt(expiresAt)
            .build();
        RefreshToken saved = refreshTokenRepository.save(row);
        return new InsertedToken(saved.getId(), rawToken, hash);
    }

    public record InsertedToken(Long id, String rawToken, String tokenHash) {}
}
