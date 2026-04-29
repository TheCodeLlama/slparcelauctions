package com.slparcelauctions.backend.config.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Redis cache infrastructure for Epic 07 endpoints: /auctions/search,
 * /auctions/featured/*, /stats/public. Configures a single
 * RedisTemplate&lt;String, Object&gt; used by SearchResponseCache,
 * FeaturedCache, PublicStatsCache, and CachedRegionResolver.
 *
 * <p>Serialization uses GenericJackson2JsonRedisSerializer's builder with
 * defaultTyping enabled, so the serializer installs its own
 * TypeResolverBuilder that emits an {@code @class} hint at every node
 * (including record/final roots). We then layer JavaTimeModule onto the
 * mapper so java.time values round-trip as ISO-8601 strings rather than
 * timestamps. Configuring default typing on the mapper directly does
 * <strong>not</strong> work here — when the builder also applies its own
 * type resolver, but more importantly the previous setup using
 * {@code activateDefaultTyping(NON_FINAL, As.PROPERTY)} omitted
 * {@code @class} on final types (records like SearchPagedResponse), so
 * reads failed with {@code missing type id property '@class'} and the
 * cache fell through to recompute on every call.
 *
 * <p>This config is distinct from spring-session-data-redis's session
 * serializer - sessions use their own template. Do not consolidate.
 */
@Configuration
public class RedisCacheConfig {

    @Bean
    public RedisTemplate<String, Object> epic07RedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        GenericJackson2JsonRedisSerializer valueSerializer =
                GenericJackson2JsonRedisSerializer.builder()
                        .objectMapper(mapper)
                        .defaultTyping(true)
                        .build();
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
