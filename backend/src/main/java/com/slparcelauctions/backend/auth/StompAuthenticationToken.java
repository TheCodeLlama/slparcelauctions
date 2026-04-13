package com.slparcelauctions.backend.auth;

import java.security.Principal;

/**
 * Minimal {@link Principal} wrapper attached to a STOMP session by
 * {@link JwtChannelInterceptor} after a successful CONNECT-frame JWT
 * validation. Spring's {@code SimpMessagingTemplate.convertAndSendToUser}
 * resolves user-scoped destinations via {@link #getName()}.
 *
 * <p>This wrapper exists so Epic 04 can convert and send to specific users
 * for per-auction notifications. For Task 01-09, only {@link #principal()} is
 * read (via {@code @AuthenticationPrincipal} on the test controller, which
 * reaches in through the Spring MVC resolver chain anyway).
 */
public record StompAuthenticationToken(AuthPrincipal principal) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(principal.userId());
    }
}
