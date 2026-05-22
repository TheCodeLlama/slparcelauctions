package com.slparcelauctions.backend.realty.dto;

import java.util.UUID;

import com.slparcelauctions.backend.realty.RealtyGroupRole;

/**
 * Wire shape for {@code GET /api/v1/users/{publicId}/realty-groups} — feeds the
 * "Groups" section on the public user profile. The role is computed (leader vs
 * agent); permissions are deliberately not exposed on a user-facing surface.
 *
 * <p>Logo URLs are dual light/dark (plan Task 2). Either may be {@code null}
 * when no upload exists for that variant.
 */
public record UserRealtyGroupAffiliationDto(
    UUID groupPublicId,
    String groupName,
    String groupSlug,
    String logoLightUrl,
    String logoDarkUrl,
    RealtyGroupRole role
) {}
