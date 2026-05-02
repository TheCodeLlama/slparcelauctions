package com.slparcelauctions.backend.auction.search;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.search.exception.DistanceRequiresNearRegionException;
import com.slparcelauctions.backend.auction.search.exception.InvalidFilterValueException;
import com.slparcelauctions.backend.auction.search.exception.InvalidRangeException;
import com.slparcelauctions.backend.auction.search.exception.NearestRequiresNearRegionException;
import com.slparcelauctions.backend.parcel.MaturityRating;

import lombok.extern.slf4j.Slf4j;

/**
 * Semantic validator for {@link AuctionSearchQuery}. Controller-level
 * {@code @Validated} + bean-validation handles primitive typing and
 * enum parsing (via the {@code fromWire} factories on each enum). This
 * class handles cross-field invariants + clamping.
 *
 * <p>Clamping vs erroring: scalar overshoots (size, distance, negative
 * page) clamp silently with DEBUG logging. Semantic errors — unknown
 * enum, range inversion, sort-without-anchor — throw 400s because the
 * caller's intent is unparsable.
 */
@Component
@Slf4j
public class AuctionSearchQueryValidator {

    public AuctionSearchQuery validate(AuctionSearchQuery q) {
        if (q.maturity() != null) {
            for (String m : q.maturity()) {
                if (!MaturityRating.CANONICAL_VALUES.contains(m)) {
                    throw new InvalidFilterValueException("maturity", m,
                            String.join(", ", MaturityRating.CANONICAL_VALUES));
                }
            }
        }

        if (q.minArea() != null && q.maxArea() != null && q.minArea() > q.maxArea()) {
            throw new InvalidRangeException("area");
        }
        if (q.minPrice() != null && q.maxPrice() != null && q.minPrice() > q.maxPrice()) {
            throw new InvalidRangeException("price");
        }

        if (q.sort() == AuctionSearchSort.NEAREST
                && (q.nearRegion() == null || q.nearRegion().isBlank())) {
            throw new NearestRequiresNearRegionException();
        }

        if (q.distance() != null
                && (q.nearRegion() == null || q.nearRegion().isBlank())) {
            throw new DistanceRequiresNearRegionException();
        }

        int size = q.size();
        if (size > AuctionSearchQuery.MAX_SIZE) {
            log.debug("Clamping size from {} to {}", size, AuctionSearchQuery.MAX_SIZE);
            size = AuctionSearchQuery.MAX_SIZE;
        }

        int page = q.page();
        if (page < 0) {
            log.debug("Clamping negative page {} to 0", page);
            page = 0;
        }

        Integer distance = q.distance();
        if (distance != null && distance > AuctionSearchQuery.MAX_DISTANCE) {
            log.debug("Clamping distance from {} to {}", distance, AuctionSearchQuery.MAX_DISTANCE);
            distance = AuctionSearchQuery.MAX_DISTANCE;
        }

        return new AuctionSearchQuery(
                q.region(), q.minArea(), q.maxArea(), q.minPrice(), q.maxPrice(),
                q.maturity(), q.tags(), q.tagsMode(), q.reserveStatus(),
                q.snipeProtection(), q.verificationTier(), q.endingWithinHours(),
                q.nearRegion(), distance, q.sellerId(), q.sort(), page, size);
    }
}
