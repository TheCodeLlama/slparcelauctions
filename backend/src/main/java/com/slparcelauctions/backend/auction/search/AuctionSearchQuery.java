package com.slparcelauctions.backend.auction.search;

import java.util.Set;

import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parceltag.ParcelTag;

/**
 * Validated, normalized query input. The controller parses raw request
 * parameters, the validator checks semantics, then this record is passed
 * to the predicate builder.
 *
 * <p>Pagination / distance bounds (max page size, max + default distance,
 * default page size) are externalized to {@code slpa.auction.*} and read
 * via {@code AuctionConfigProperties} by the validator and search service.
 */
public record AuctionSearchQuery(
        String q,                              // nullable free-text query (header search overlay)
        String region,                         // nullable
        Integer minArea, Integer maxArea,      // nullable
        Long minPrice, Long maxPrice,          // nullable
        Set<String> maturity,                  // nullable/empty -> no filter
        Set<ParcelTag> tags,                   // nullable/empty -> no filter
        TagsMode tagsMode,
        ReserveStatusFilter reserveStatus,
        SnipeProtectionFilter snipeProtection,
        Set<VerificationTier> verificationTier,
        Integer endingWithinHours,             // nullable
        String nearRegion,                     // nullable
        Integer distance,                      // nullable, clamp applied
        Long sellerId,                         // nullable
        AuctionSearchSort sort,
        int page,
        int size) {
}
