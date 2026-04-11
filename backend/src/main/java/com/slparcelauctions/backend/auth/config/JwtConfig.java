package com.slparcelauctions.backend.auth.config;

import com.slparcelauctions.backend.auth.JwtKeyFactory;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.crypto.SecretKey;
import java.time.Duration;

/**
 * JWT configuration properties bound from {@code jwt.*} keys in application.yml.
 *
 * <p>Fails fast on startup via {@link PostConstruct} if {@code jwt.secret} is missing, blank,
 * or shorter than 256 bits — preventing a misconfigured JWT secret from ever reaching runtime.
 * Production reads {@code jwt.secret} from the {@code JWT_SECRET} environment variable (no default);
 * dev uses a committed placeholder with a loud "DEV ONLY" comment in {@code application-dev.yml}.
 */
@Configuration
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtConfig {

    private String secret;
    private Duration accessTokenLifetime;
    private Duration refreshTokenLifetime;

    private SecretKey signingKey;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "jwt.secret is required. Set JWT_SECRET environment variable in production "
                + "or check application-dev.yml for the dev default.");
        }
        // Derive once — the length check is enforced inside buildKey, and the result is
        // cached for jwtSigningKey() to return so we don't decode + HMAC-derive twice.
        this.signingKey = JwtKeyFactory.buildKey(secret);
    }

    @Bean
    public SecretKey jwtSigningKey() {
        return signingKey;
    }
}
