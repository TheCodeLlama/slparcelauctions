package com.slparcelauctions.backend.auction.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.dto.RegionResolution;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Full-stack coverage for {@code GET /api/v1/auctions/search} — verifies
 * the public security path, the {@code Cache-Control} response header,
 * filter validation surface, and pagination clamps. Uses
 * {@code @SpringBootTest} so the entire Spring Security filter chain,
 * the search {@code @RestControllerAdvice}, and the read-through Redis
 * cache are exercised end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class AuctionSearchControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired StringRedisTemplate redis;

    /**
     * Mocked so distance tests can stub region resolution without
     * hitting the real Grid Survey CAP service. Filter / pagination
     * tests don't transit through this bean since they pass no
     * {@code near_region} parameter.
     */
    @MockitoBean SlMapApiClient slMapApiClient;

    @BeforeEach
    void seed() {
        // Cache state from prior tests can shadow stubbed lookups; clear
        // both the resolver cache and the response cache up-front.
        clearRedisKeys("slpa:grid-coord:*");
        clearRedisKeys("slpa:search:*");
        Mockito.reset(slMapApiClient);

        User seller = userRepo.save(User.builder()
                .email("seller-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Seller").verified(true).build());
        seedActive(seller, "Tula", 1024, "GENERAL", 997.0, 1036.0);
        seedActive(seller, "Luna", 2048, "MODERATE", 1000.0, 1040.0);
        seedActive(seller, "Terra", 4096, "ADULT", 1100.0, 1100.0);
    }

    private void clearRedisKeys(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.meta.sortApplied").value("newest"));
    }

    @Test
    void includesCacheControlHeader() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=30")));
    }

    @Test
    void regionFilter_respects() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search").param("region", "tula"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].parcel.region").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalToIgnoringCase("Tula"))));
    }

    @Test
    void unknownMaturity_returns400InvalidFilterValue() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search").param("maturity", "Teen"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILTER_VALUE"))
                .andExpect(jsonPath("$.field").value("maturity"));
    }

    @Test
    void invalidRange_returns400InvalidRange() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search")
                        .param("min_area", "5000")
                        .param("max_area", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"))
                .andExpect(jsonPath("$.field").value("area"));
    }

    @Test
    void nearestWithoutNearRegion_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search").param("sort", "nearest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NEAREST_REQUIRES_NEAR_REGION"));
    }

    @Test
    void distanceWithoutNearRegion_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search").param("distance", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISTANCE_REQUIRES_NEAR_REGION"));
    }

    @Test
    void sortNewest_acceptedAndAppliedInMeta() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search").param("sort", "newest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.sortApplied").value("newest"));
    }

    @Test
    void pageAndSize_respected() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search")
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    void sizeOverMax_clampedTo100() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void nearRegion_unknown_returns400RegionNotFound() throws Exception {
        Mockito.when(slMapApiClient.resolve("Nowhere-x9z"))
                .thenReturn(new RegionResolution.NotFound());

        mockMvc.perform(get("/api/v1/auctions/search").param("near_region", "Nowhere-x9z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"))
                .andExpect(jsonPath("$.field").value("near_region"));
    }

    @Test
    void nearRegion_resolved_populatesMeta() throws Exception {
        Mockito.when(slMapApiClient.resolve("Tula"))
                .thenReturn(new RegionResolution.Found(997.0, 1036.0));

        mockMvc.perform(get("/api/v1/auctions/search").param("near_region", "Tula"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.nearRegionResolved.name").value("Tula"))
                .andExpect(jsonPath("$.meta.nearRegionResolved.gridX").value(997.0))
                .andExpect(jsonPath("$.meta.nearRegionResolved.gridY").value(1036.0));
    }

    private void seedActive(
            User seller, String region, int area, String maturity,
            Double gridX, Double gridY) {
        Parcel p = parcelRepo.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .regionName(region).areaSqm(area).maturityRating(maturity)
                .gridX(gridX).gridY(gridY)
                .verified(true).build());
        auctionRepo.save(Auction.builder()
                .parcel(p).seller(seller).title("Test")
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).currentBid(1000L)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .durationHours(168)
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
    }
}
