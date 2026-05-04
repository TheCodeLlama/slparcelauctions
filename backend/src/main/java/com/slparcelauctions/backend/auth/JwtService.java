package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Thin facade over JJWT 0.12 for access-token issuance and parsing.
 *
 * <p><strong>Access tokens only.</strong> Refresh tokens are not JWTs — they're opaque
 * {@code SecureRandom}-derived strings handled by {@code RefreshTokenService}.
 *
 * <p>Access token claims: {@code sub} (user ID as string), {@code email}, {@code tv} (token version),
 * {@code iat}, {@code exp}, {@code type} (literal {@code "access"}).
 *
 * <p>The {@code type} claim is enforced in {@link #parseAccessToken(String)} — any token whose
 * {@code type} is not {@code "access"} is rejected. This is not a redundant check: if the system
 * later issues other JWTs with the same signing key (e.g., email verification, API keys), the
 * {@code type} claim is the only gate preventing cross-type confusion. Do not remove it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final JwtConfig jwtConfig;
    private final SecretKey jwtSigningKey;

    public String issueAccessToken(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(principal.userPublicId().toString())
            .claim("email", principal.email())
            .claim("tv", principal.tokenVersion())
            .claim("type", "access")
            .claim("role", principal.role().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(jwtConfig.getAccessTokenLifetime())))
            .signWith(jwtSigningKey)
            .compact();
    }

    public AuthPrincipal parseAccessToken(String token) {
        try {
            Jws<Claims> parsed = Jwts.parser()
                .verifyWith(jwtSigningKey)
                .build()
                .parseSignedClaims(token);
            Claims claims = parsed.getPayload();

            if (!"access".equals(claims.get("type"))) {
                throw new TokenInvalidException("Token type claim is not 'access'.");
            }

            UUID userPublicId = UUID.fromString(claims.getSubject());
            String email = (String) claims.get("email");
            Long tokenVersion = ((Number) claims.get("tv")).longValue();
            String roleClaim = (String) claims.get("role");
            Role role = roleClaim == null ? Role.USER : Role.valueOf(roleClaim);

            // userId (Long) is resolved by JwtAuthenticationFilter via UserRepository.findByPublicId,
            // not encoded in the JWT itself. Place a sentinel here; the filter overwrites the principal
            // before it lands in the SecurityContext.
            return new AuthPrincipal(null, userPublicId, email, tokenVersion, role);
        } catch (ExpiredJwtException e) {
            log.debug("Access token expired");
            throw new TokenExpiredException("Access token has expired.");
        } catch (JwtException | IllegalArgumentException | ClassCastException | NullPointerException e) {
            log.debug("Access token invalid: {}", e.getClass().getSimpleName());
            throw new TokenInvalidException("Access token is invalid.");
        }
    }
}
