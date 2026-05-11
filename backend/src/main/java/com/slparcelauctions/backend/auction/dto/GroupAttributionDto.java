package com.slparcelauctions.backend.auction.dto;

import java.util.UUID;

/**
 * Compact group attribution embedded into auction DTOs when the auction is group-listed.
 * Frontend uses {@link #dissolved} to hide the chip without losing audit trail.
 */
public record GroupAttributionDto(
        UUID publicId,
        String name,
        String slug,
        String logoUrl,
        boolean dissolved) {
}
