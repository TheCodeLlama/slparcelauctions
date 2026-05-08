package com.slparcelauctions.backend.parceltag.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH /api/v1/admin/parcel-tags/{code} request body. All fields are
 * optional; only supplied fields are written. Blank strings are treated
 * as no-op rather than "set empty" — clearing a field requires explicit
 * empty-string content (description) or an out-of-band action (label and
 * category have NotBlank constraints in the schema and can't be cleared
 * without recreating the row).
 * <p>
 * {@code code} is intentionally absent — it's the path key and immutable.
 */
public record UpdateParcelTagRequest(
        @Size(min = 1, max = 100) String label,
        @Size(min = 1, max = 50) String category,
        @Size(max = 2000) String description,
        Integer sortOrder) {}
