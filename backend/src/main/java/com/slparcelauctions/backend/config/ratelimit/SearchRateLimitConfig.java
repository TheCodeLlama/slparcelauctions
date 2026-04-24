package com.slparcelauctions.backend.config.ratelimit;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

/**
 * Beans for the bucket4j Redis-backed rate limiter that protects
 * {@code GET /api/v1/auctions/search}.
 *
 * <p>Bucket state is shared across backend instances via Lettuce CAS so the
 * 60-rpm-per-IP cap is enforced cluster-wide, not per replica. The
 * {@code spring-boot-starter-data-redis} autoconfig already provides a
 * {@code LettuceConnectionFactory} for the cache layer; we provision a
 * dedicated {@link RedisClient}/{@link StatefulRedisConnection} pair here
 * because bucket4j's {@link LettuceBasedProxyManager} requires a connection
 * with a {@code (String, byte[])} codec — the cache template's connection
 * uses a different codec and cannot be reused.
 *
 * <p>The community {@code com.giffing.*} starter's auto-configurations are
 * excluded in {@code application.yml} (they target Spring Boot 3 and break
 * under Spring Boot 4); only the programmatic bucket4j API is wired here.
 */
@Configuration
public class SearchRateLimitConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient() {
        return RedisClient.create(RedisURI.create(redisHost, redisPort));
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return client.connect(codec);
    }

    @Bean
    public ProxyManager<String> searchRateLimitProxyManager(
            StatefulRedisConnection<String, byte[]> connection) {
        // bucket4j 8.14: ExpirationAfterWriteStrategy.fixedTimeToLive(Duration)
        // replaces the older AbstractRedisProxyManagerBuilder.ExpirationStrategy
        // .fixedTimeStrategy(...) helper. Ten minutes is comfortably longer than
        // the 60-second refill window, so idle keys evict promptly without
        // truncating an in-flight refill.
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.fixedTimeToLive(Duration.ofMinutes(10)))
                .build();
    }

    @Bean
    public BucketConfiguration searchBucketConfiguration() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(60)
                        .refillGreedy(60, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
