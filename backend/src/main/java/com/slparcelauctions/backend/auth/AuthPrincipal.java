package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.user.Role;

import java.util.UUID;

/**
 * Lightweight authentication principal set into the Spring {@code SecurityContext} by
 * {@code JwtAuthenticationFilter} on a successful access-token parse. Consumed by controllers via
 * {@code @AuthenticationPrincipal AuthPrincipal principal}.
 *
 * <p><strong>Never use {@code @AuthenticationPrincipal UserDetails}</strong> in this codebase —
 * the filter sets this record, not a Spring {@code UserDetails}, and reaching for {@code UserDetails}
 * yields {@code null}. See FOOTGUNS §B.1.
 *
 * <p>Carries both {@code userId} (internal {@code Long}, used for FK joins / internal lookups) and
 * {@code userPublicId} (UUID, used for outbound JSON, JWT subject claim, public URLs). Service
 * code reads {@code principal.userId()} for joins and {@code principal.userPublicId()} for any
 * value that crosses a public wire.
 *
 * <p>The {@code tokenVersion} field is the freshness-mitigation claim: write-path services compare
 * it against the freshly-loaded {@code user.getTokenVersion()} at the integrity boundary to detect
 * stale sessions within the 15-minute access-token window.
 */
public record AuthPrincipal(Long userId, UUID userPublicId, String email, Long tokenVersion, Role role) {}
