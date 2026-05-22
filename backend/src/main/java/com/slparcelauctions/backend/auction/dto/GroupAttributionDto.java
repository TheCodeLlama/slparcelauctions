package com.slparcelauctions.backend.auction.dto;

import java.util.UUID;

/**
 * Compact group attribution embedded into auction DTOs when the auction is
 * group-listed. Frontend uses {@link #dissolved} to hide the chip without
 * losing audit trail.
 *
 * <p>Logo URLs are dual light/dark (plan Task 2). Either may be {@code null}
 * when no upload exists for that variant; the frontend's {@code ThemedImage}
 * helper falls back to whichever sibling slot is populated.
 */
public record GroupAttributionDto(
        UUID publicId,
        String name,
        String slug,
        String logoLightUrl,
        String logoDarkUrl,
        boolean dissolved) {
}
