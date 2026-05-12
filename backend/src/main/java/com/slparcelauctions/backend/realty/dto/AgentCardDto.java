package com.slparcelauctions.backend.realty.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.slparcelauctions.backend.realty.RealtyGroupRole;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

/**
 * Roster entry for a single member of a group.
 *
 * <p>The role is computed at mapping time ({@code userId == leaderId} → {@link
 * RealtyGroupRole#LEADER}, else {@link RealtyGroupRole#AGENT}).
 *
 * <p>{@code permissions}, {@code joinedAt}, and {@code agentCommissionRate} are
 * {@code null} for anonymous viewers (and for authenticated callers who are not members
 * of this group and not admins). They carry their real values when the requester is a
 * current member of this group or an admin — spec §5.2 ("permissions+joined_at only if
 * requester is a member or admin"). The commission rate follows the same privacy gate
 * because it is part of the same per-member terms the spec scopes to in-group readers.
 */
public record AgentCardDto(
    UUID memberPublicId,
    UUID userPublicId,
    String displayName,
    String avatarUrl,
    RealtyGroupRole role,
    Set<RealtyGroupPermission> permissions,
    OffsetDateTime joinedAt,
    BigDecimal agentCommissionRate
) {}
