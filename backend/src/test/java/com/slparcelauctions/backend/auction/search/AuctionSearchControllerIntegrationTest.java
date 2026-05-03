package com.slparcelauctions.backend.auction.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.region.Region;
import com.slparcelauctions.backend.region.RegionRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Full-stack coverage for {@code GET /api/v1/auctions/search}. Distance /
 * near-region resolution reads the {@code regions} table directly (no SL
 * HTTP round-trip), so test setup seeds region rows alongside parcels.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class AuctionSearchControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired RegionRepository regionRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void seed() {
        clearRedisKeys("slpa:search:*");
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
    void response_includesEndOutcomeField_nullForActive() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].endOutcome")
                        .value(org.hamcrest.Matchers.nullValue()));
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
        mockMvc.perform(get("/api/v1/auctions/search").param("near_region", "Nowhere-x9z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"))
                .andExpect(jsonPath("$.field").value("near_region"));
    }

    @Test
    void nearRegion_resolved_populatesMeta() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/search").param("near_region", "Tula"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.nearRegionResolved.name").value("Tula"))
                .andExpect(jsonPath("$.meta.nearRegionResolved.gridX").value(997.0))
                .andExpect(jsonPath("$.meta.nearRegionResolved.gridY").value(1036.0));
    }

    private void seedActive(
            User seller, String regionName, int area, String maturity,
            Double gridX, Double gridY) {
        Region region = regionRepo.findByNameIgnoreCase(regionName)
                .orElseGet(() -> regionRepo.save(Region.builder()
                        .slUuid(UUID.randomUUID())
                        .name(regionName)
                        .gridX(gridX).gridY(gridY)
                        .maturityRating(maturity)
                        .build()));
        UUID parcelUuid = UUID.randomUUID();
        Auction a = auctionRepo.save(Auction.builder()
                .slParcelUuid(parcelUuid)
                .seller(seller).title("Test")
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).currentBid(1000L)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .durationHours(168)
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .region(region)
                .regionName(regionName)
                .regionMaturityRating(maturity)
                .areaSqm(area)
                .ownerType("agent")
                .ownerName("Owner")
                .parcelName(regionName + " Parcel")
                .build());
        auctionRepo.save(a);
    }
}
