package com.slparcelauctions.backend.realty.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/realty-groups/{publicId}} and the admin twin.
 *
 * <p>Every field is optional; a {@code null} value means "leave this field unchanged".
 * Renames go through a 30-day cooldown on the non-admin path (admins bypass). Profile
 * fields are gated by {@code EDIT_GROUP_PROFILE}.
 */
public record UpdateRealtyGroupRequest(
    @Size(max = 64) String name,
    @Size(max = 2000) String description,
    @Size(max = 500) String website
) {}
