package com.slparcelauctions.backend.auction.search;

import org.springframework.data.domain.Sort;

/**
 * Maps {@link AuctionSearchSort} values to Spring Data {@link Sort}
 * specifications. Three sorts — LOWEST_PRICE, MOST_BIDS, NEAREST —
 * cannot be expressed as a declarative Sort (they need COALESCE,
 * subquery expressions, or a computed distance column). For those, the
 * service layer emits the ORDER BY clause via the
 * {@link org.springframework.data.jpa.domain.Specification} query
 * builder access and this method returns {@link Sort#unsorted()}.
 *
 * <p><b>Field-name notes (deviation from the spec):</b>
 * <ul>
 *   <li>{@code NEWEST} sorts by {@code startsAt} (DESC). The spec calls
 *       this column {@code activated_at}, but the existing
 *       {@link com.slparcelauctions.backend.auction.Auction} entity
 *       records the DRAFT to ACTIVE transition timestamp on
 *       {@code startsAt} (set by
 *       {@code AuctionVerificationService.activate}). No new column is
 *       being introduced in this sub-spec; if a dedicated
 *       {@code activatedAt} field is added later, swap the property
 *       name here.</li>
 *   <li>{@code LARGEST_AREA} sorts by {@code parcel.areaSqm} (DESC).
 *       The spec's {@code parcel.area} shorthand resolves to that JPA
 *       property path on
 *       {@link com.slparcelauctions.backend.parcel.Parcel}.</li>
 * </ul>
 */
public final class AuctionSearchSortSpec {

    private AuctionSearchSortSpec() {}

    public static Sort toSort(AuctionSearchSort sort) {
        return switch (sort) {
            case NEWEST         -> Sort.by(Sort.Order.desc("startsAt"), Sort.Order.desc("id"));
            case ENDING_SOONEST -> Sort.by(Sort.Order.asc("endsAt"), Sort.Order.asc("id"));
            case LARGEST_AREA   -> Sort.by(Sort.Order.desc("parcelSnapshot.areaSqm"), Sort.Order.desc("id"));
            case LOWEST_PRICE, MOST_BIDS, NEAREST -> Sort.unsorted();
        };
    }
}
