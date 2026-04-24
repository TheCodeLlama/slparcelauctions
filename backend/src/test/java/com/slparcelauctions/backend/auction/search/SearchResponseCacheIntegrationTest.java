package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the {@link SearchResponseCache} read-through is wired into the
 * service AND that the cached value can actually be deserialized on the
 * next call. Intentionally NOT {@code @Transactional} — Redis writes
 * happen synchronously and do not enroll in JPA transactions, so the
 * cache key inspection between calls needs to see real Redis state.
 *
 * <p>This test asserts cache HIT semantics by reading the value back
 * through the same {@link RedisTemplate} the cache uses. A successful
 * deserialization to {@link SearchPagedResponse} proves the typed mapper
 * is round-tripping the polymorphic envelope. We also assert that the
 * raw payload contains the {@code @class} type hint so a regression to
 * the previous "missing type id property '@class'" behaviour fails fast.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
class SearchResponseCacheIntegrationTest {

    @Autowired AuctionSearchService service;
    @Autowired StringRedisTemplate redis;
    @Autowired @Qualifier("epic07RedisTemplate") RedisTemplate<String, Object> typedRedis;

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
    void cachedValue_deserializesBackToSearchPagedResponse() {
        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder().build();
        var first = service.search(q);

        String key = SearchCacheKey.keyFor(q);

        // Raw bytes must carry the @class type hint - if they don't,
        // the typed RedisTemplate will fail to deserialize and the
        // cache will silently degrade to a 0s-effective TTL.
        byte[] raw = redis.getConnectionFactory()
                .getConnection()
                .stringCommands()
                .get(key.getBytes(StandardCharsets.UTF_8));
        assertThat(raw).as("cache value must be present").isNotNull();
        String json = new String(raw, StandardCharsets.UTF_8);
        assertThat(json)
                .as("serialized cache value must include @class type hints "
                        + "so the typed RedisTemplate can deserialize it")
                .contains("@class");

        // Round-trip through the same template the production cache
        // uses. This is the read path that was silently failing before.
        Object roundTripped = typedRedis.opsForValue().get(key);
        assertThat(roundTripped).isInstanceOf(SearchPagedResponse.class);
        SearchPagedResponse<?> hit = (SearchPagedResponse<?>) roundTripped;
        assertThat(hit.totalElements()).isEqualTo(first.totalElements());
        assertThat(hit.content()).hasSize(first.content().size());
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
