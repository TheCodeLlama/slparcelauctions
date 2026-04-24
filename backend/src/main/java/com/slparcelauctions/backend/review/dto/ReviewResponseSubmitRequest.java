package com.slparcelauctions.backend.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/reviews/{id}/respond} (Epic 08
 * sub-spec 1 §4.3). The reviewee posts a one-time textual response to a
 * review written about them; responses are non-empty and capped at 500
 * chars. Lower-bound enforcement ({@code @NotBlank}) is deliberate — an
 * empty response is useless and likely a UI bug the caller will want to
 * see surfaced as 400.
 */
public record ReviewResponseSubmitRequest(
        @NotBlank
        @Size(max = 500)
        String text) {
}
