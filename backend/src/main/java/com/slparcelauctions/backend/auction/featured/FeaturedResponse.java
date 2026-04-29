package com.slparcelauctions.backend.auction.featured;

import java.util.List;

import com.slparcelauctions.backend.auction.search.AuctionSearchResultDto;

/**
 * Featured endpoint payload — a single named list of search-result cards.
 * Reuses {@link AuctionSearchResultDto} so the homepage rows render with
 * the same component the browse grid uses.
 *
 * <p>Cached in Redis via {@link FeaturedCache}; round-trips through the
 * Epic 07 GenericJackson2 serializer with {@code @class} type hints
 * (see {@code RedisCacheConfig}).
 */
public record FeaturedResponse(List<AuctionSearchResultDto> content) {

    public static FeaturedResponse of(List<AuctionSearchResultDto> content) {
        return new FeaturedResponse(content);
    }
}
