package com.slparcelauctions.backend.parceltag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/admin/parcel-tags request body.
 * <p>
 * {@code code} is the immutable admin-facing primary key for a tag. Restricted
 * to uppercase letters / digits / underscore so it stays stable on the wire
 * (no ambiguity from spaces, hyphens, or case-folding mismatches).
 */
public record CreateParcelTagRequest(
        @NotBlank
        @Size(min = 1, max = 50)
        @Pattern(regexp = "^[A-Z0-9_]+$",
                message = "Code must be uppercase letters, digits, and underscores only")
        String code,

        @NotBlank
        @Size(min = 1, max = 100)
        String label,

        @NotBlank
        @Size(min = 1, max = 50)
        String category,

        @Size(max = 2000)
        String description) {}
