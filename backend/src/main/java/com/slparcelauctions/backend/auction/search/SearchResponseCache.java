package com.slparcelauctions.backend.auction.search;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-through cache for /auctions/search responses. Get-or-compute: on
 * hit returns the cached SearchPagedResponse; on miss calls the
 * supplier, caches the result with a 30s TTL, and returns it.
 *
 * <p>Keyed on {@link SearchCacheKey#keyFor(AuctionSearchQuery)} so the
 * same filters + pagination produce the same key regardless of Set
 * iteration order on the input. No event-driven invalidation - the 30s
 * TTL is the invalidator.
 */
@Component
@Slf4j
public class SearchResponseCache {

    public static final Duration TTL = Duration.ofSeconds(30);

    private final RedisTemplate<String, Object> redis;

    public SearchResponseCache(@Qualifier("epic07RedisTemplate") RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    @SuppressWarnings("unchecked")
    public SearchPagedResponse<AuctionSearchResultDto> getOrCompute(
            AuctionSearchQuery query,
            Supplier<SearchPagedResponse<AuctionSearchResultDto>> compute) {

        String key = SearchCacheKey.keyFor(query);
        Object cached = null;
        try {
            cached = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Failed to read search cache for key {}: {}", key, e.toString());
        }
        if (cached instanceof SearchPagedResponse<?> typed) {
            log.debug("Search cache HIT: {}", key);
            return (SearchPagedResponse<AuctionSearchResultDto>) typed;
        }

        log.debug("Search cache MISS: {}", key);
        SearchPagedResponse<AuctionSearchResultDto> computed = compute.get();
        try {
            redis.opsForValue().set(key, computed, TTL);
        } catch (RuntimeException e) {
            log.warn("Failed to cache search response for key {}: {}",
                    key, e.toString());
        }
        return computed;
    }
}
