package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.auction.search.exception.RegionLookupUnavailableException;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.RegionResolution;

/**
 * End-to-end coverage of {@link CachedRegionResolver} against the real
 * Redis used by the {@code dev} profile. {@link SlMapApiClient} is the
 * single mocked collaborator so we can assert on call counts; everything
 * else (Redis, key prefixing, TTLs, sealed-type switch dispatch) is the
 * real production code path.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
class CachedRegionResolverTest {

    @Autowired CachedRegionResolver resolver;
    @Autowired StringRedisTemplate redis;
    @MockitoBean SlMapApiClient slMapApiClient;

    @BeforeEach
    @AfterEach
    void clearCache() {
        var keys = redis.keys("slpa:grid-coord:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void found_cached_andReturned() {
        when(slMapApiClient.resolve("Tula"))
                .thenReturn(new RegionResolution.Found(997.0, 1036.0));

        Optional<GridCoordinates> first = resolver.resolve("Tula");
        assertThat(first).isPresent();
        assertThat(first.get().gridX()).isEqualTo(997.0);
        assertThat(first.get().gridY()).isEqualTo(1036.0);

        // Second call hits cache — upstream not re-invoked.
        resolver.resolve("Tula");
        verify(slMapApiClient, org.mockito.Mockito.times(1)).resolve(eq("Tula"));
    }

    @Test
    void caseInsensitiveCacheKey() {
        when(slMapApiClient.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new RegionResolution.Found(100.0, 200.0));

        resolver.resolve("Tula");
        resolver.resolve("tula");
        resolver.resolve("TULA");

        // 3 calls, 1 upstream hit.
        verify(slMapApiClient, org.mockito.Mockito.times(1))
                .resolve(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void notFound_cached_asNegativeSentinel() {
        when(slMapApiClient.resolve("Nowhere"))
                .thenReturn(new RegionResolution.NotFound());

        assertThat(resolver.resolve("Nowhere")).isEmpty();
        assertThat(resolver.resolve("Nowhere")).isEmpty();

        // Second call served from negative cache.
        verify(slMapApiClient, org.mockito.Mockito.times(1)).resolve(eq("Nowhere"));
    }

    @Test
    void upstreamError_notCached_andThrowsRegionLookupUnavailable() {
        when(slMapApiClient.resolve("Anywhere"))
                .thenReturn(new RegionResolution.UpstreamError("Grid Survey 500"));

        assertThatThrownBy(() -> resolver.resolve("Anywhere"))
                .isInstanceOf(RegionLookupUnavailableException.class);

        // Not cached — second call re-invokes upstream.
        when(slMapApiClient.resolve("Anywhere"))
                .thenReturn(new RegionResolution.Found(50.0, 60.0));
        assertThat(resolver.resolve("Anywhere")).isPresent();
        verify(slMapApiClient, org.mockito.Mockito.times(2)).resolve(eq("Anywhere"));
    }
}
