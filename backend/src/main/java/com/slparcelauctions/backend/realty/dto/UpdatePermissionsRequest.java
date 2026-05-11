package com.slparcelauctions.backend.realty.dto;

import java.util.Set;

import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/v1/realty-groups/{publicId}/members/{memberPublicId}/permissions}.
 *
 * <p>The full target permission set is replaced — not patched additively. An empty set is
 * legal (revokes all flags). Leader-only operation; cannot target the leader's own row.
 */
public record UpdatePermissionsRequest(
    @NotNull Set<RealtyGroupPermission> permissions
) {}
