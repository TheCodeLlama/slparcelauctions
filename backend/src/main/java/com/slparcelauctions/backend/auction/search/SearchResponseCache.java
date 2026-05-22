package com.slparcelauctions.backend.auction.search;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.AuctionConfigProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-through cache for /auctions/search responses. Get-or-compute: on
 * hit returns the cached SearchPagedResponse; on miss calls the
 * supplier, caches the result with the {@code slpa.auction.search-cache-ttl}
 * TTL, and returns it.
 *
 * <p>Keyed on {@link SearchCacheKey#keyFor(AuctionSearchQuery)} so the
 * same filters + pagination produce the same key regardless of Set
 * iteration order on the input. No event-driven invalidation - the TTL
 * is the invalidator. The TTL is kept aligned with the {@code Cache-Control}
 * max-age the search controller emits.
 */
@Component
@Slf4j
public class SearchResponseCache {

    private final RedisTemplate<String, Object> redis;
    private final Duration ttl;

    public SearchResponseCache(
            @Qualifier("epic07RedisTemplate") RedisTemplate<String, Object> redis,
            AuctionConfigProperties config) {
        this.redis = redis;
        this.ttl = config.searchCacheTtl();
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
            redis.opsForValue().set(key, computed, ttl);
        } catch (RuntimeException e) {
            log.warn("Failed to cache search response for key {}: {}",
                    key, e.toString());
        }
        return computed;
    }
}
