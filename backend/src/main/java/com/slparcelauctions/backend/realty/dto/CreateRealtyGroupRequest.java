package com.slparcelauctions.backend.realty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/realty-groups}.
 *
 * <p>The authenticated caller becomes the leader of the newly-created group. The
 * service derives the slug from {@code name}; {@code description} and {@code website} are
 * optional. URL validation on {@code website} happens in the service layer (Bean
 * Validation is only used here to bound length).
 */
public record CreateRealtyGroupRequest(
    @NotBlank @Size(max = 64) String name,
    @Size(max = 2000) String description,
    @Size(max = 500) String website
) {}
