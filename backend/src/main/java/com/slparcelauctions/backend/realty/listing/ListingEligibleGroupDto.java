package com.slparcelauctions.backend.realty.listing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only row for the {@code GET /api/v1/realty/me/listing-eligible-groups} endpoint.
 * Drives the wizard's List-as picker; carries only what the picker needs to render +
 * compute its fee preview ({@code agentFeeRate}).
 */
public record ListingEligibleGroupDto(
        UUID publicId,
        String name,
        String slug,
        String logoUrl,
        BigDecimal agentFeeRate) {
}
