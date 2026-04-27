package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.user.Role;

/**
 * Lightweight authentication principal set into the Spring {@code SecurityContext} by
 * {@code JwtAuthenticationFilter} on a successful access-token parse. Consumed by controllers via
 * {@code @AuthenticationPrincipal AuthPrincipal principal}.
 *
 * <p><strong>Never use {@code @AuthenticationPrincipal UserDetails}</strong> in this codebase —
 * the filter sets this record, not a Spring {@code UserDetails}, and reaching for {@code UserDetails}
 * yields {@code null}. See FOOTGUNS §B.1.
 *
 * <p>The {@code tokenVersion} field is the freshness-mitigation claim: write-path services compare
 * it against the freshly-loaded {@code user.getTokenVersion()} at the integrity boundary to detect
 * stale sessions within the 15-minute access-token window. See spec §2 (data model) and §6
 * (service-layer freshness check).
 */
public record AuthPrincipal(Long userId, String email, Long tokenVersion, Role role) {}
