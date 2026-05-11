package com.slparcelauctions.backend.realty.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/realty-groups/{publicId}} and the admin twin.
 *
 * <p>Every field is optional; a {@code null} value means "leave this field unchanged".
 * Renames go through a 30-day cooldown on the non-admin path (admins bypass). Fee fields
 * are gated by {@code CONFIGURE_FEES}; profile fields by {@code EDIT_GROUP_PROFILE}.
 */
public record UpdateRealtyGroupRequest(
    @Size(max = 64) String name,
    @Size(max = 2000) String description,
    @Size(max = 500) String website,
    @DecimalMin("0.0000") @DecimalMax("0.5000") BigDecimal agentFeeRate,
    @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal agentFeeSplit
) {}
