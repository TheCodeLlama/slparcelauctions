package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.parceltag.ParcelTag;

class SearchCacheKeyTest {

    @Test
    void sameQuery_sameKey() {
        AuctionSearchQuery q1 = AuctionSearchQueryBuilder.newBuilder()
                .region("Tula").sort(AuctionSearchSort.ENDING_SOONEST).build();
        AuctionSearchQuery q2 = AuctionSearchQueryBuilder.newBuilder()
                .region("Tula").sort(AuctionSearchSort.ENDING_SOONEST).build();
        assertThat(SearchCacheKey.keyFor(q1)).isEqualTo(SearchCacheKey.keyFor(q2));
    }

    @Test
    void differentQuery_differentKey() {
        AuctionSearchQuery q1 = AuctionSearchQueryBuilder.newBuilder()
                .region("Tula").build();
        AuctionSearchQuery q2 = AuctionSearchQueryBuilder.newBuilder()
                .region("Luna").build();
        assertThat(SearchCacheKey.keyFor(q1)).isNotEqualTo(SearchCacheKey.keyFor(q2));
    }

    @Test
    void tagSetOrder_doesNotAffectKey() {
        ParcelTag beach = tag("BEACHFRONT");
        ParcelTag road = tag("ROADSIDE");
        Set<ParcelTag> a = new LinkedHashSet<>();
        a.add(road); a.add(beach);
        Set<ParcelTag> b = new LinkedHashSet<>();
        b.add(beach); b.add(road);

        AuctionSearchQuery q1 = AuctionSearchQueryBuilder.newBuilder().tags(a).build();
        AuctionSearchQuery q2 = AuctionSearchQueryBuilder.newBuilder().tags(b).build();
        assertThat(SearchCacheKey.keyFor(q1)).isEqualTo(SearchCacheKey.keyFor(q2));
    }

    @Test
    void maturitySetOrder_doesNotAffectKey() {
        Set<String> a = new LinkedHashSet<>();
        a.add("ADULT"); a.add("GENERAL");
        Set<String> b = new LinkedHashSet<>();
        b.add("GENERAL"); b.add("ADULT");

        AuctionSearchQuery q1 = AuctionSearchQueryBuilder.newBuilder().maturity(a).build();
        AuctionSearchQuery q2 = AuctionSearchQueryBuilder.newBuilder().maturity(b).build();
        assertThat(SearchCacheKey.keyFor(q1)).isEqualTo(SearchCacheKey.keyFor(q2));
    }

    @Test
    void keyFormatHasNamespacePrefix() {
        String key = SearchCacheKey.keyFor(AuctionSearchQueryBuilder.newBuilder().build());
        assertThat(key).startsWith("slpa:search:");
        assertThat(key).hasSize("slpa:search:".length() + 64);  // SHA-256 hex
    }

    private static ParcelTag tag(String code) {
        ParcelTag t = new ParcelTag();
        t.setCode(code);
        return t;
    }
}
