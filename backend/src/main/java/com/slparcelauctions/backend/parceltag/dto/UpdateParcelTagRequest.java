package com.slparcelauctions.backend.parceltag.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH /api/v1/admin/parcel-tags/{code} request body. All fields are
 * optional; only supplied fields are written.
 * <p>
 * {@code code} is intentionally absent — it's the path key and immutable.
 */
public record UpdateParcelTagRequest(
        @Size(min = 1, max = 100) String label,
        @Size(min = 1, max = 50) String category,
        @Size(max = 2000) String description) {}
