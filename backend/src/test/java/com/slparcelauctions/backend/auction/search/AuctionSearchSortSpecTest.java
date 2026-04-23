package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class AuctionSearchSortSpecTest {

    /**
     * The spec calls this field {@code activated_at}; the existing Auction
     * entity stores the DRAFT to ACTIVE transition timestamp on
     * {@code startsAt} (set by {@code AuctionVerificationService} when the
     * status flips). No new column is being introduced in this task, so
     * "newest" sorts by {@code startsAt} for now. If a dedicated
     * {@code activatedAt} field is added later, swap the property name
     * here and update the test in the same change.
     */
    @Test
    void newest_descByStartsAtThenId() {
        Sort sort = AuctionSearchSortSpec.toSort(AuctionSearchSort.NEWEST);
        assertThat(sort.toString()).contains("startsAt: DESC").contains("id: DESC");
    }

    @Test
    void endingSoonest_ascEndsAtThenId() {
        Sort sort = AuctionSearchSortSpec.toSort(AuctionSearchSort.ENDING_SOONEST);
        assertThat(sort.toString()).contains("endsAt: ASC").contains("id: ASC");
    }

    /**
     * The {@link com.slparcelauctions.backend.parcel.Parcel} entity stores
     * area in {@code areaSqm} — the spec's {@code parcel.area} shorthand
     * resolves to that JPA path.
     */
    @Test
    void largestArea_descParcelAreaSqmThenId() {
        Sort sort = AuctionSearchSortSpec.toSort(AuctionSearchSort.LARGEST_AREA);
        assertThat(sort.toString()).contains("parcel.areaSqm: DESC").contains("id: DESC");
    }

    // LOWEST_PRICE, MOST_BIDS, NEAREST require raw SQL / computed order
    // — they're handled by the service layer via PredicateBuilder hooks
    // rather than Sort objects, so toSort() returns unsorted for them
    // and the service appends the raw ORDER BY via an orderBy hook.
    @Test
    void lowestPrice_returnsUnsortedForServiceHandling() {
        Sort sort = AuctionSearchSortSpec.toSort(AuctionSearchSort.LOWEST_PRICE);
        assertThat(sort.isUnsorted()).isTrue();
    }

    @Test
    void mostBids_returnsUnsortedForServiceHandling() {
        Sort sort = AuctionSearchSortSpec.toSort(AuctionSearchSort.MOST_BIDS);
        assertThat(sort.isUnsorted()).isTrue();
    }

    @Test
    void nearest_returnsUnsortedForServiceHandling() {
        Sort sort = AuctionSearchSortSpec.toSort(AuctionSearchSort.NEAREST);
        assertThat(sort.isUnsorted()).isTrue();
    }
}
