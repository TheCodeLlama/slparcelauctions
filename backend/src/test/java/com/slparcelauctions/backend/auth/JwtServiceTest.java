package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.auth.test.JwtTestFactory;
import com.slparcelauctions.backend.user.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = base64Of32RandomBytes();

    private JwtService jwtService;
    private JwtTestFactory testFactory;

    private static String base64Of32RandomBytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setSecret(SECRET);
        config.setAccessTokenLifetime(Duration.ofMinutes(15));
        config.setRefreshTokenLifetime(Duration.ofDays(7));
        SecretKey key = JwtKeyFactory.buildKey(SECRET);
        jwtService = new JwtService(config, key);
        testFactory = JwtTestFactory.forKey(SECRET);
    }

    @Test
    void issueAccessToken_producesTokenWithCorrectClaims() {
        UUID publicId = UUID.randomUUID();
        AuthPrincipal input = new AuthPrincipal(42L, publicId, "user@example.com", 3L, Role.USER);

        String token = jwtService.issueAccessToken(input);
        AuthPrincipal parsed = jwtService.parseAccessToken(token);

        // userId is a sentinel null after parse — resolved by JwtAuthenticationFilter
        assertThat(parsed.userId()).isNull();
        assertThat(parsed.userPublicId()).isEqualTo(publicId);
        assertThat(parsed.email()).isEqualTo("user@example.com");
        assertThat(parsed.tokenVersion()).isEqualTo(3L);
        assertThat(parsed.role()).isEqualTo(Role.USER);
    }

    @Test
    void roleClaim_roundTrips_forAdminRole() {
        AuthPrincipal adminPrincipal = new AuthPrincipal(7L, UUID.randomUUID(), "admin@example.com", 0L, Role.ADMIN);

        String token = jwtService.issueAccessToken(adminPrincipal);
        AuthPrincipal parsed = jwtService.parseAccessToken(token);

        assertThat(parsed.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void roleClaim_roundTrips_forUserRole() {
        AuthPrincipal userPrincipal = new AuthPrincipal(8L, UUID.randomUUID(), "user2@example.com", 0L, Role.USER);

        String token = jwtService.issueAccessToken(userPrincipal);
        AuthPrincipal parsed = jwtService.parseAccessToken(token);

        assertThat(parsed.role()).isEqualTo(Role.USER);
    }

    @Test
    void parseAccessToken_missingRoleClaim_defaultsToUser() {
        SecretKey key = JwtKeyFactory.buildKey(SECRET);
        Instant now = Instant.now();
        // Use a valid UUID as subject to match new token shape
        String legacyToken = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("email", "legacy@example.com")
            .claim("tv", 0L)
            .claim("type", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
            .signWith(key)
            .compact();

        AuthPrincipal parsed = jwtService.parseAccessToken(legacyToken);

        assertThat(parsed.role()).isEqualTo(Role.USER);
    }

    @Test
    void parseAccessToken_returnsPrincipalOnValidToken() {
        UUID publicId = UUID.randomUUID();
        // After parse, userId is null (filter resolves it); publicId round-trips via sub claim
        AuthPrincipal input = new AuthPrincipal(1L, publicId, "a@b.com", 0L, Role.USER);
        String token = testFactory.validAccessToken(input);
        AuthPrincipal parsed = jwtService.parseAccessToken(token);

        assertThat(parsed.userPublicId()).isEqualTo(publicId);
        assertThat(parsed.email()).isEqualTo("a@b.com");
        assertThat(parsed.tokenVersion()).isEqualTo(0L);
        assertThat(parsed.role()).isEqualTo(Role.USER);
        assertThat(parsed.userId()).isNull();
    }

    @Test
    void parseAccessToken_throwsExpiredOnExpiredToken() {
        AuthPrincipal p = new AuthPrincipal(1L, UUID.randomUUID(), "a@b.com", 0L, Role.USER);
        String token = testFactory.expiredAccessToken(p);

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
            .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void parseAccessToken_throwsInvalidOnBadSignature() {
        String token = testFactory.tokenWithBadSignature();

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
            .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void parseAccessToken_throwsInvalidOnWrongType() {
        AuthPrincipal p = new AuthPrincipal(1L, UUID.randomUUID(), "a@b.com", 0L, Role.USER);
        String token = testFactory.tokenWithWrongType(p);

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
            .isInstanceOf(TokenInvalidException.class)
            .hasMessageContaining("type");
    }

    @Test
    void parseAccessToken_throwsInvalidOnMalformedToken() {
        String token = testFactory.malformedToken();

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
            .isInstanceOf(TokenInvalidException.class);
    }
}
