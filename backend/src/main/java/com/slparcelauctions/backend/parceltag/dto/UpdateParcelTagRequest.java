package com.slparcelauctions.backend.parceltag.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH /api/v1/admin/parcel-tags/{code} request body. All fields optional.
 * {@code code} is the path key and immutable.
 */
public record UpdateParcelTagRequest(
        @Size(min = 1, max = 100) String label,
        @Size(min = 1, max = 50) String categoryCode,
        @Size(max = 2000) String description) {}
