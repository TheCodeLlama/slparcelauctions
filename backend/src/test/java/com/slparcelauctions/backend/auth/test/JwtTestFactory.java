package com.slparcelauctions.backend.auth.test;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtKeyFactory;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Test-only factory for issuing real signed JWTs that validate against the same key as production.
 *
 * <p>Two construction paths are intentionally exposed:
 * <ol>
 *   <li><strong>{@code @Component} with {@code @Value}</strong> — for slice and integration tests
 *       that bring up a Spring context. The factory is autowired and reads {@code jwt.secret}
 *       from the active profile's application.yml, so tokens it issues validate against the same
 *       key the running {@code JwtAuthenticationFilter} uses.</li>
 *   <li><strong>{@link #forKey(String)} static factory</strong> — for pure-unit tests that don't
 *       bring up a Spring context (e.g., {@code JwtServiceTest}, {@code JwtAuthenticationFilterTest}).
 *       The test hands in a secret directly.</li>
 * </ol>
 *
 * <p>Both paths derive the key via {@link JwtKeyFactory#buildKey(String)} so prod and test share
 * the same derivation — if the production key shape changes, this factory follows automatically.
 */
@Component
public class JwtTestFactory {

    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(15);

    private final SecretKey key;

    @Autowired
    public JwtTestFactory(@Value("${jwt.secret}") String secret) {
        this.key = JwtKeyFactory.buildKey(secret);
    }

    private JwtTestFactory(SecretKey key) {
        this.key = key;
    }

    /**
     * Pure-unit construction path. Use from tests that don't bring up a Spring context.
     */
    public static JwtTestFactory forKey(String secretBase64) {
        return new JwtTestFactory(JwtKeyFactory.buildKey(secretBase64));
    }

    public String validAccessToken(AuthPrincipal principal) {
        return validAccessTokenWithLifetime(principal, DEFAULT_LIFETIME);
    }

    public String validAccessTokenWithLifetime(AuthPrincipal principal, Duration lifetime) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(principal.userPublicId().toString())
            .claim("username", principal.username())
            .claim("tv", principal.tokenVersion())
            .claim("type", "access")
            .claim("role", principal.role().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(lifetime)))
            .signWith(key)
            .compact();
    }

    public String expiredAccessToken(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(principal.userPublicId().toString())
            .claim("username", principal.username())
            .claim("tv", principal.tokenVersion())
            .claim("type", "access")
            .claim("role", principal.role().name())
            .issuedAt(Date.from(now.minus(Duration.ofHours(1))))
            .expiration(Date.from(now.minus(Duration.ofMinutes(1))))
            .signWith(key)
            .compact();
    }

    public String tokenWithWrongType(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(principal.userPublicId().toString())
            .claim("username", principal.username())
            .claim("tv", principal.tokenVersion())
            .claim("type", "refresh") // wrong — access token parser should reject
            .claim("role", principal.role().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(DEFAULT_LIFETIME)))
            .signWith(key)
            .compact();
    }

    public String tokenWithBadSignature() {
        // Sign with a completely different key.
        byte[] otherBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(otherBytes);
        SecretKey otherKey = Keys.hmacShaKeyFor(otherBytes);
        Instant now = Instant.now();
        return Jwts.builder()
            .subject("1")
            .claim("username", "unused")
            .claim("tv", 0L)
            .claim("type", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(DEFAULT_LIFETIME)))
            .signWith(otherKey)
            .compact();
    }

    public String malformedToken() {
        return "not.a.jwt";
    }
}
