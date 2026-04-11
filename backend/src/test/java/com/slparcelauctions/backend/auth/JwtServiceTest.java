package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.auth.test.JwtTestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;
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
        AuthPrincipal input = new AuthPrincipal(42L, "user@example.com", 3L);

        String token = jwtService.issueAccessToken(input);
        AuthPrincipal parsed = jwtService.parseAccessToken(token);

        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.email()).isEqualTo("user@example.com");
        assertThat(parsed.tokenVersion()).isEqualTo(3L);
    }

    @Test
    void parseAccessToken_returnsPrincipalOnValidToken() {
        AuthPrincipal expected = new AuthPrincipal(1L, "a@b.com", 0L);
        String token = testFactory.validAccessToken(expected);

        assertThat(jwtService.parseAccessToken(token)).isEqualTo(expected);
    }

    @Test
    void parseAccessToken_throwsExpiredOnExpiredToken() {
        AuthPrincipal p = new AuthPrincipal(1L, "a@b.com", 0L);
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
        AuthPrincipal p = new AuthPrincipal(1L, "a@b.com", 0L);
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
