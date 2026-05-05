package com.slparcelauctions.backend.auth.test;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtKeyFactory;
import com.slparcelauctions.backend.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTestFactoryTest {

    private static final String DEV_SECRET = base64Of32RandomBytes();

    private static String base64Of32RandomBytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void forKey_producesFactoryThatIssuesValidAccessTokens() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(1L, publicId, "test@example.com", 0L, Role.USER);

        String token = factory.validAccessToken(principal);

        // Parse with the same key and assert the claims.
        SecretKey key = JwtKeyFactory.buildKey(DEV_SECRET);
        Jws<Claims> parsed = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        Claims claims = parsed.getPayload();

        assertThat(claims.getSubject()).isEqualTo(publicId.toString());
        assertThat(claims.get("username")).isEqualTo("test@example.com");
        assertThat(((Number) claims.get("tv")).longValue()).isEqualTo(0L);
        assertThat(claims.get("type")).isEqualTo("access");
    }

    @Test
    void expiredAccessToken_producesTokenWithPastExpiry() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);
        AuthPrincipal principal = new AuthPrincipal(1L, UUID.randomUUID(), "test@example.com", 0L, Role.USER);

        String token = factory.expiredAccessToken(principal);
        SecretKey key = JwtKeyFactory.buildKey(DEV_SECRET);

        assertThatThrownBy(() ->
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token))
            .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    @Test
    void tokenWithWrongType_hasTypeClaimSetToRefresh() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);
        AuthPrincipal principal = new AuthPrincipal(1L, UUID.randomUUID(), "test@example.com", 0L, Role.USER);

        String token = factory.tokenWithWrongType(principal);
        SecretKey key = JwtKeyFactory.buildKey(DEV_SECRET);
        Claims claims = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).getPayload();

        assertThat(claims.get("type")).isEqualTo("refresh");
    }

    @Test
    void tokenWithBadSignature_doesNotVerifyWithRealKey() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);

        String token = factory.tokenWithBadSignature();
        SecretKey key = JwtKeyFactory.buildKey(DEV_SECRET);

        assertThatThrownBy(() ->
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token))
            .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }

    @Test
    void malformedToken_returnsNonJwtString() {
        JwtTestFactory factory = JwtTestFactory.forKey(DEV_SECRET);

        assertThat(factory.malformedToken()).isEqualTo("not.a.jwt");
    }
}
