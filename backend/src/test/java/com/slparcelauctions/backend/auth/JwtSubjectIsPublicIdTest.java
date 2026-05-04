package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.SecretKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link JwtService#issueAccessToken(AuthPrincipal)} encodes
 * {@code publicId.toString()} as the JWT {@code sub} claim and does NOT embed
 * the internal {@code Long userId} in any claim.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class JwtSubjectIsPublicIdTest {

    @Autowired JwtService jwtService;
    @Autowired SecretKey jwtSigningKey;

    @Test
    void issuedAccessTokenSubjectIsPublicIdString() {
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(42L, publicId, "x@example.com", 1L, Role.USER);

        String token = jwtService.issueAccessToken(principal);

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(jwtSigningKey)
                .build()
                .parseSignedClaims(token);

        assertThat(parsed.getPayload().getSubject())
                .as("sub claim must be publicId.toString()")
                .isEqualTo(publicId.toString());

        // Internal Long id must NOT appear in any claim.
        assertThat(parsed.getPayload()).doesNotContainKey("userId");
    }
}
