package com.slparcelauctions.backend.stats;

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
 * End-to-end coverage for {@code GET /api/v1/stats/public}. Verifies the
 * public security path, the bundled four-count + asOf payload shape, the
 * {@code Cache-Control: max-age=60} response header, and the Redis cache
 * key is materialized after the first call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
class PublicStatsControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearCacheBefore() {
        redis.delete(PublicStatsCache.KEY);
    }

    @AfterEach
    void clearCacheAfter() {
        redis.delete(PublicStatsCache.KEY);
    }

    @Test
    void unauth_returns200_withFourCountsAndAsOf() throws Exception {
        mockMvc.perform(get("/api/v1/stats/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeAuctions").exists())
                .andExpect(jsonPath("$.activeBidTotalL").exists())
                .andExpect(jsonPath("$.completedSales").exists())
                .andExpect(jsonPath("$.registeredUsers").exists())
                .andExpect(jsonPath("$.asOf").exists());
    }

    @Test
    void countsAreNonNegativeLongs() throws Exception {
        mockMvc.perform(get("/api/v1/stats/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeAuctions").isNumber())
                .andExpect(jsonPath("$.activeBidTotalL").isNumber())
                .andExpect(jsonPath("$.completedSales").isNumber())
                .andExpect(jsonPath("$.registeredUsers").isNumber());
    }

    @Test
    void responseCarries60sPublicCacheControl() throws Exception {
        mockMvc.perform(get("/api/v1/stats/public"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=60")));
    }

    @Test
    void firstCall_populatesCacheKey() throws Exception {
        mockMvc.perform(get("/api/v1/stats/public"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(
                redis.hasKey(PublicStatsCache.KEY)).isTrue();
    }
}
