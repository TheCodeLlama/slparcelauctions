package com.slparcelauctions.backend.auction.dto;

import java.util.UUID;

/**
 * Compact listing-agent attribution embedded into auction DTOs when the auction is
 * group-listed. In sub-project C case 1 this is the same user as the seller; sub-project E
 * is where the two diverge.
 */
public record ListingAgentDto(
        UUID publicId,
        String displayName,
        String avatarUrl) {
}
