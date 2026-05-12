package com.slparcelauctions.backend.realty.dto;

import java.math.BigDecimal;
import java.util.Set;

import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/v1/realty-groups/{publicId}/members/{memberPublicId}/permissions}.
 *
 * <p>The full target permission set is replaced — not patched additively. An empty set is
 * legal (revokes all flags). Leader-only operation; cannot target the leader's own row.
 *
 * <p>{@code agentCommissionRate} is optional. When present it replaces the member's
 * current rate. When {@code null} the rate is left unchanged (so a leader can patch perms
 * without touching the rate, and vice versa via a separate call).
 */
public record UpdatePermissionsRequest(
    @NotNull Set<RealtyGroupPermission> permissions,
    @DecimalMin("0.0") BigDecimal agentCommissionRate
) {}
