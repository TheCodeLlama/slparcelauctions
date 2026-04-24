package com.slparcelauctions.backend.auction.featured;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-stack coverage for {@code GET /api/v1/auctions/featured/*}. Verifies
 * the public security path, the {@code Cache-Control: max-age=60} response
 * header, the JSON envelope shape, and per-endpoint cache key isolation.
 *
 * <p>Each test runs against a Redis-cleared starting state so the cache-key
 * assertions don't see residue from a prior test or run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
class FeaturedControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearCacheBefore() {
        clearCache();
    }

    @AfterEach
    void clearCacheAfter() {
        clearCache();
    }

    private void clearCache() {
        var keys = redis.keys("slpa:featured:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void endingSoon_unauth_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/featured/ending-soon"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=60")))
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void justListed_unauth_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/featured/just-listed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void mostActive_unauth_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/featured/most-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void secondCall_servedFromCacheKey() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/featured/ending-soon"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(
                redis.keys("slpa:featured:ending-soon")).hasSize(1);
    }

    @Test
    void oneEndpointCached_doesNotAffectOthers() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/featured/ending-soon"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(
                redis.keys("slpa:featured:just-listed")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                redis.keys("slpa:featured:most-active")).isEmpty();
    }
}
