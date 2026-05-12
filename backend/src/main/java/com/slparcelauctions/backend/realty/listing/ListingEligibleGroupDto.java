package com.slparcelauctions.backend.realty.listing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only row for the {@code GET /api/v1/realty/me/listing-eligible-groups} endpoint.
 * Drives the wizard's List-as picker; carries only what the picker needs to render +
 * compute its fee preview.
 *
 * <p>{@code agentFeeRate} is the group-level rate snapshotted into case-1 auctions
 * (agent == seller, agent's own parcel). For case-3 eligibility (sub-project E:
 * parcel owned by an SL group registered to the realty group), the per-listing rate
 * lives on the {@code RealtyGroupMember} row, not the group, so {@code agentFeeRate}
 * is {@code null} in case-3 results. The frontend reads the per-member rate from a
 * separate path when it needs to preview the fee.
 */
public record ListingEligibleGroupDto(
        UUID publicId,
        String name,
        String slug,
        String logoUrl,
        BigDecimal agentFeeRate) {
}
