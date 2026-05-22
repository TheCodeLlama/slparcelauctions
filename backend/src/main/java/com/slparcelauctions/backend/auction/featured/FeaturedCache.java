package com.slparcelauctions.backend.auction.featured;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.AuctionConfigProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-endpoint read-through cache for the featured rows. Each
 * {@link FeaturedCategory} maps to its own Redis key, so a stampede on
 * one row will not refresh the others.
 *
 * <p>Reads are wrapped in try/catch so a deserialization failure (e.g.
 * stale schema) degrades to a recompute rather than a 5xx — same posture
 * as {@code SearchResponseCache}. Writes are similarly defensive.
 *
 * <p>Constructor uses an explicit {@code @Qualifier} parameter rather
 * than {@code @RequiredArgsConstructor} because Lombok will not propagate
 * the field-level {@code @Qualifier} to the generated parameter.
 */
@Component
@Slf4j
public class FeaturedCache {

    private final RedisTemplate<String, Object> redis;
    private final Duration ttl;

    public FeaturedCache(
            @Qualifier("epic07RedisTemplate") RedisTemplate<String, Object> redis,
            AuctionConfigProperties config) {
        this.redis = redis;
        this.ttl = config.featuredCacheTtl();
    }

    public FeaturedResponse getOrCompute(FeaturedCategory category,
                                         Supplier<FeaturedResponse> compute) {
        Object cached = null;
        try {
            cached = redis.opsForValue().get(category.cacheKey());
        } catch (RuntimeException e) {
            log.warn("Failed to read featured cache for {}: {}",
                    category, e.toString());
        }
        if (cached instanceof FeaturedResponse resp) {
            log.debug("Featured cache HIT: {}", category);
            return resp;
        }
        log.debug("Featured cache MISS: {}", category);
        FeaturedResponse computed = compute.get();
        try {
            redis.opsForValue().set(category.cacheKey(), computed, ttl);
        } catch (RuntimeException e) {
            log.warn("Failed to cache featured response for {}: {}",
                    category, e.toString());
        }
        return computed;
    }

    /**
     * Drops the cached entry for {@code category}, forcing the next call
     * to recompute. Used by the admin Featured-toggle path so an admin
     * write surfaces immediately instead of waiting out the TTL.
     * Errors from the Redis client are swallowed and logged — the caller
     * does not need to handle a cache outage; the TTL self-heals.
     */
    public void invalidate(FeaturedCategory category) {
        try {
            redis.delete(category.cacheKey());
        } catch (RuntimeException e) {
            log.warn("Failed to invalidate featured cache for {}: {}",
                    category, e.toString());
        }
    }
}
