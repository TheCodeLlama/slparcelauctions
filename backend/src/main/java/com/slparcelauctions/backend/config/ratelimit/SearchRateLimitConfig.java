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

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean redisSslEnabled;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${slpa.ratelimit.cache-ttl}")
    private Duration cacheTtl;

    @Value("${slpa.ratelimit.search.capacity}")
    private long searchCapacity;

    @Value("${slpa.ratelimit.search.refill}")
    private Duration searchRefill;

    @Value("${slpa.ratelimit.suggest.capacity}")
    private long suggestCapacity;

    @Value("${slpa.ratelimit.suggest.refill}")
    private Duration suggestRefill;

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient() {
        // Build the URI explicitly rather than using RedisURI.create(host, port)
        // because that overload skips TLS + auth. ElastiCache requires both
        // (transit_encryption_enabled = true + auth_token on the replication
        // group); without them the connection fails at handshake with
        // "Unable to connect to <host>/<unresolved>:6379" — the <unresolved>
        // is Lettuce's signal that the TCP connect attempt was rejected before
        // DNS resolution finished.
        //
        // Local dev (docker-compose Redis without TLS or auth) leaves both
        // properties at their defaults and ends up with a plain TCP URI —
        // unchanged from the pre-prod-deploy behaviour.
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort);
        if (redisSslEnabled) {
            builder.withSsl(true);
        }
        if (!redisPassword.isEmpty()) {
            builder.withPassword(redisPassword.toCharArray());
        }
        return RedisClient.create(builder.build());
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
        // .fixedTimeStrategy(...) helper. The TTL (slpa.ratelimit.cache-ttl) is
        // comfortably longer than the refill window, so idle keys evict
        // promptly without truncating an in-flight refill.
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.fixedTimeToLive(cacheTtl))
                .build();
    }

    @Bean
    public BucketConfiguration searchBucketConfiguration() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(searchCapacity)
                        .refillGreedy(searchCapacity, searchRefill)
                        .build())
                .build();
    }

    /**
     * 300 rpm/IP — typeahead amplifies request count ~10x compared to
     * the structured /search path, so its bucket is sized higher. 5
     * requests/sec sustained handles fast typing without throttling
     * a real user.
     */
    @Bean
    public BucketConfiguration suggestBucketConfiguration() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(suggestCapacity)
                        .refillGreedy(suggestCapacity, suggestRefill)
                        .build())
                .build();
    }
}
