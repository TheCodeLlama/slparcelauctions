package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the {@link SearchResponseCache} read-through is wired into the
 * service. Intentionally NOT {@code @Transactional} — Redis writes happen
 * synchronously and do not enroll in JPA transactions, so the cache key
 * inspection between calls needs to see real Redis state.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
class SearchResponseCacheIntegrationTest {

    @Autowired AuctionSearchService service;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearCache() {
        var keys = redis.keys("slpa:search:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void secondCall_servedFromCache() {
        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder().build();

        service.search(q);
        var keys = redis.keys("slpa:search:*");
        assertThat(keys).hasSize(1);

        var first = service.search(q);
        var second = service.search(q);
        assertThat(first.totalElements()).isEqualTo(second.totalElements());
        assertThat(redis.keys("slpa:search:*")).hasSize(1);
    }

    @Test
    void differentQueries_differentKeys() {
        AuctionSearchQuery q1 = AuctionSearchQueryBuilder.newBuilder()
                .region("Tula").build();
        AuctionSearchQuery q2 = AuctionSearchQueryBuilder.newBuilder()
                .region("Luna").build();

        service.search(q1);
        service.search(q2);

        assertThat(redis.keys("slpa:search:*")).hasSize(2);
    }
}
