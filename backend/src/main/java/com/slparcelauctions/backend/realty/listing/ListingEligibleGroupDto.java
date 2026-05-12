package com.slparcelauctions.backend.realty.listing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only row for the {@code GET /api/v1/realty/me/listing-eligible-groups} endpoint.
 * Drives the wizard's List-as picker; carries only what the picker needs to render +
 * compute its fee preview.
 *
 * <p>{@code agentCommissionRate} is the calling user's per-member commission rate
 * within this group, projected from {@code realty_group_members.agent_commission_rate}
 * (sub-project G section 6.2). The wizard reads it directly off the eligible-list row,
 * removing the prior per-parcel {@code useRealtyGroup} round-trip that previously
 * sourced the rate from the public group DTO. If the caller has no member row for
 * the group (defensive edge case) the rate is {@link BigDecimal#ZERO} so the preview
 * still renders.
 */
public record ListingEligibleGroupDto(
        UUID publicId,
        String name,
        String slug,
        String logoUrl,
        BigDecimal agentCommissionRate) {
}
