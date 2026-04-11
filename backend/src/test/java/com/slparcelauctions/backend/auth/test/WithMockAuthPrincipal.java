package com.slparcelauctions.backend.auth.test;

import org.springframework.security.test.context.support.WithSecurityContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inserts an {@link com.slparcelauctions.backend.auth.AuthPrincipal} into the Spring
 * {@code SecurityContext} for the test method or class. Modelled on Spring Security's
 * {@code @WithMockUser}.
 *
 * <p>Defaults: {@code userId=1L}, {@code email="test@example.com"}, {@code tokenVersion=0L}.
 * Override any subset via annotation attributes:
 *
 * <pre>{@code
 * @Test
 * @WithMockAuthPrincipal(userId = 42, email = "alice@example.com")
 * void placesBid_whenAuthenticated() { ... }
 * }</pre>
 *
 * <p><strong>Wiring:</strong> the {@code @WithSecurityContext(factory = ...)} element below is the
 * modern Spring Security 6+ auto-discovery path. {@code META-INF/spring.factories} is the legacy
 * fallback — use it only if this annotation is silently ignored in tests (see FOOTGUNS §B.3).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockAuthPrincipalSecurityContextFactory.class)
public @interface WithMockAuthPrincipal {
    long userId() default 1L;
    String email() default "test@example.com";
    long tokenVersion() default 0L;
}
