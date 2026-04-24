package com.slparcelauctions.backend.auction.search;

import java.util.Set;

import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parceltag.ParcelTag;

/**
 * Validated, normalized query input. The controller parses raw request
 * parameters, the validator checks semantics, then this record is passed
 * to the predicate builder.
 */
public record AuctionSearchQuery(
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

    public static final int MAX_SIZE = 100;
    public static final int MAX_DISTANCE = 50;
    public static final int DEFAULT_DISTANCE = 10;
    public static final int DEFAULT_SIZE = 24;
}
