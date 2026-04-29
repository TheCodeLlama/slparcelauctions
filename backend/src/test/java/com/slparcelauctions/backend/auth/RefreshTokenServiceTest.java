package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.RefreshTokenReuseDetectedException;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    RefreshTokenRepository repository;

    @Mock
    UserService userService;

    RefreshTokenService service;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setRefreshTokenLifetime(Duration.ofDays(7));
        // Real-time clock so the existing assertions ("expires in ~7d", "now is
        // before expiry") still hold without rewriting them. Tests that need
        // deterministic timestamps can construct the service with
        // Clock.fixed(...) directly.
        service = new RefreshTokenService(repository, userService, config, Clock.systemUTC());
    }

    // -------------------------------------------------------------------------
    // 1. issueForUser — persists a row whose tokenHash is sha256Hex of the raw token
    // -------------------------------------------------------------------------

    @Test
    void issueForUser_createsRowWithHashedToken() {
        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenService.IssuedRefreshToken issued =
                service.issueForUser(1L, "Mozilla/5.0", "127.0.0.1");

        verify(repository).save(any(RefreshToken.class));
        // The raw token was returned and the hash of it was stored
        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(OffsetDateTime.now().plusDays(6));

        // Re-derive the hash from the raw token and confirm it was saved
        String expectedHash = TokenHasher.sha256Hex(issued.rawToken());
        verify(repository).save(argThat(rt -> expectedHash.equals(rt.getTokenHash())));
    }

    // -------------------------------------------------------------------------
    // 2. issueForUser — userAgent longer than 512 chars is truncated
    // -------------------------------------------------------------------------

    @Test
    void issueForUser_truncatesLongUserAgent() {
        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        String longAgent = "A".repeat(1000);
        service.issueForUser(1L, longAgent, "127.0.0.1");

        verify(repository).save(argThat(rt -> rt.getUserAgent().length() == 512));
    }

    // -------------------------------------------------------------------------
    // 3. rotate — happy path: old row revoked + lastUsedAt set, new token issued
    // -------------------------------------------------------------------------

    @Test
    void rotate_happyPath_revokesOldAndInsertsNew() {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);

        RefreshToken existing = RefreshToken.builder()
                .id(1L)
                .userId(42L)
                .tokenHash(hash)
                .expiresAt(OffsetDateTime.now().plusDays(3))
                .build();

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(existing));
        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenService.RotationResult result =
                service.rotate(rawToken, "Mozilla/5.0", "10.0.0.1");

        // Old row gets revokedAt + lastUsedAt
        assertThat(existing.getRevokedAt()).isNotNull();
        assertThat(existing.getLastUsedAt()).isNotNull();

        // Result carries a different raw token
        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.rawToken()).isNotEqualTo(rawToken);
        assertThat(result.expiresAt()).isAfter(OffsetDateTime.now().plusDays(6));
    }

    // -------------------------------------------------------------------------
    // 4. rotate — expired token → TokenExpiredException
    // -------------------------------------------------------------------------

    @Test
    void rotate_rejectsExpiredToken() {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);

        RefreshToken expired = RefreshToken.builder()
                .id(2L)
                .userId(42L)
                .tokenHash(hash)
                .expiresAt(OffsetDateTime.now().minusDays(1))
                .build();

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate(rawToken, "UA", "1.2.3.4"))
                .isInstanceOf(TokenExpiredException.class);
    }

    // -------------------------------------------------------------------------
    // 5. rotate — reused (already-revoked) token → cascade + exception
    // -------------------------------------------------------------------------

    @Test
    void rotate_onReusedToken_cascadeRevokesAndBumpsTokenVersion() {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);

        RefreshToken reused = RefreshToken.builder()
                .id(3L)
                .userId(99L)
                .tokenHash(hash)
                .expiresAt(OffsetDateTime.now().plusDays(3))
                .revokedAt(OffsetDateTime.now().minusHours(1))  // already revoked
                .build();

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(reused));

        assertThatThrownBy(() -> service.rotate(rawToken, "evil-UA", "5.6.7.8"))
                .isInstanceOf(RefreshTokenReuseDetectedException.class);

        // (a) cascade revoke
        verify(repository).revokeAllByUserId(eq(99L), any(OffsetDateTime.class));
        // (b) bump token version
        verify(userService).bumpTokenVersion(99L);
        // (d) no new token saved
        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 6. rotate — unknown token → TokenInvalidException
    // -------------------------------------------------------------------------

    @Test
    void rotate_onUnknownToken_throwsInvalid() {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);

        when(repository.findByTokenHash(hash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(rawToken, "UA", "1.2.3.4"))
                .isInstanceOf(TokenInvalidException.class);
    }

    // -------------------------------------------------------------------------
    // 7. revokeByRawToken — missing token (empty optional) → no throw, no save
    // -------------------------------------------------------------------------

    @Test
    void revokeByRawToken_isIdempotentOnMissingToken() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revokeByRawToken("unknown-token");

        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 8. revokeByRawToken — already-revoked row → revokedAt unchanged
    // -------------------------------------------------------------------------

    @Test
    void revokeByRawToken_doesNotDoubleRevoke() {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);
        OffsetDateTime originalRevocation = OffsetDateTime.now().minusHours(2);

        RefreshToken alreadyRevoked = RefreshToken.builder()
                .id(4L)
                .userId(10L)
                .tokenHash(hash)
                .expiresAt(OffsetDateTime.now().plusDays(1))
                .revokedAt(originalRevocation)
                .build();

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(alreadyRevoked));

        service.revokeByRawToken(rawToken);

        // revokedAt must be the original value — no overwrite
        assertThat(alreadyRevoked.getRevokedAt()).isEqualTo(originalRevocation);
        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 9. revokeByRawToken — active row → revokedAt set via dirty checking
    // -------------------------------------------------------------------------

    @Test
    void revokeByRawToken_setsRevokedAtOnActiveRow() {
        String rawToken = TokenHasher.secureRandomBase64Url(32);
        String hash = TokenHasher.sha256Hex(rawToken);

        RefreshToken active = RefreshToken.builder()
                .id(5L)
                .userId(20L)
                .tokenHash(hash)
                .expiresAt(OffsetDateTime.now().plusDays(5))
                .build();

        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(active));

        service.revokeByRawToken(rawToken);

        assertThat(active.getRevokedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // 10. revokeAllForUser — delegates to repository, returns count
    // -------------------------------------------------------------------------

    @Test
    void revokeAllForUser_delegatesToRepository() {
        when(repository.revokeAllByUserId(eq(7L), any(OffsetDateTime.class))).thenReturn(3);

        int count = service.revokeAllForUser(7L);

        assertThat(count).isEqualTo(3);
        verify(repository).revokeAllByUserId(eq(7L), any(OffsetDateTime.class));
    }

    // -------------------------------------------------------------------------
    // Mockito helper — argThat shorthand
    // -------------------------------------------------------------------------

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
