package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;

/**
 * Projection of IP activity for a user, derived from their refresh token rows.
 * Used by the admin user-detail "Recent IPs" modal and by {@code BanRepository}
 * for IP-history lookups.
 *
 * @param ipAddress    the resolved IP address seen on the token
 * @param firstSeenAt  earliest {@code created_at} across all tokens from this IP
 * @param lastSeenAt   latest {@code last_used_at} across all tokens from this IP
 * @param sessionCount count of distinct refresh token rows from this IP
 */
public record UserIpProjection(
    String ipAddress,
    OffsetDateTime firstSeenAt,
    OffsetDateTime lastSeenAt,
    long sessionCount
) {}
