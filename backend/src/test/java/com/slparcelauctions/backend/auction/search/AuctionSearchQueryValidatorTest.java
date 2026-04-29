package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.search.exception.DistanceRequiresNearRegionException;
import com.slparcelauctions.backend.auction.search.exception.InvalidFilterValueException;
import com.slparcelauctions.backend.auction.search.exception.InvalidRangeException;
import com.slparcelauctions.backend.auction.search.exception.NearestRequiresNearRegionException;

class AuctionSearchQueryValidatorTest {

    private final AuctionSearchQueryValidator validator = new AuctionSearchQueryValidator();

    @Test
    void validates_and_passesThroughValidQuery() {
        AuctionSearchQuery q = defaults().build();
        AuctionSearchQuery out = validator.validate(q);
        assertThat(out).isEqualTo(q);
    }

    @Test
    void rejects_unknown_maturity() {
        AuctionSearchQuery q = defaults()
                .maturity(Set.of("TEEN"))
                .build();
        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(InvalidFilterValueException.class)
                .matches(ex -> "maturity".equals(
                        ((InvalidFilterValueException) ex).getField()));
    }

    @Test
    void accepts_canonical_maturity() {
        AuctionSearchQuery q = defaults()
                .maturity(Set.of("GENERAL", "MODERATE", "ADULT"))
                .build();
        validator.validate(q);
    }

    @Test
    void rejects_minArea_greater_than_maxArea() {
        AuctionSearchQuery q = defaults().minArea(5000).maxArea(1000).build();
        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(InvalidRangeException.class);
    }

    @Test
    void rejects_minPrice_greater_than_maxPrice() {
        AuctionSearchQuery q = defaults().minPrice(10000L).maxPrice(5000L).build();
        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(InvalidRangeException.class);
    }

    @Test
    void rejects_sort_nearest_without_near_region() {
        AuctionSearchQuery q = defaults().sort(AuctionSearchSort.NEAREST).build();
        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(NearestRequiresNearRegionException.class);
    }

    @Test
    void rejects_distance_without_near_region() {
        AuctionSearchQuery q = defaults().distance(20).build();
        assertThatThrownBy(() -> validator.validate(q))
                .isInstanceOf(DistanceRequiresNearRegionException.class);
    }

    @Test
    void clamps_size_over_100() {
        AuctionSearchQuery q = defaults().size(500).build();
        assertThat(validator.validate(q).size()).isEqualTo(100);
    }

    @Test
    void clamps_distance_over_50() {
        AuctionSearchQuery q = defaults()
                .nearRegion("Tula")
                .distance(9999)
                .build();
        assertThat(validator.validate(q).distance()).isEqualTo(50);
    }

    @Test
    void clamps_negative_page_to_0() {
        AuctionSearchQuery q = defaults().page(-1).build();
        assertThat(validator.validate(q).page()).isEqualTo(0);
    }

    @Test
    void accepts_nearest_with_near_region() {
        AuctionSearchQuery q = defaults()
                .sort(AuctionSearchSort.NEAREST)
                .nearRegion("Tula")
                .build();
        validator.validate(q);
    }

    private static AuctionSearchQueryBuilder defaults() {
        return AuctionSearchQueryBuilder.newBuilder();
    }
}
