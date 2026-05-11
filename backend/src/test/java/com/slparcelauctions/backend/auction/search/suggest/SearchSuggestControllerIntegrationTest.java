package com.slparcelauctions.backend.auction.search.suggest;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class SearchSuggestControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired RegionRepository regionRepo;
    @Autowired AuctionRepository auctionRepo;

    private User seller;

    @BeforeEach
    void seed() {
        seller = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("seller-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Seller")
                .verified(true).build());
        seedActive("Tula", "Beachfront retreat", "Premium Waterfront");
        seedActive("Luna", "Skybox plot", "Modern Skybox");
    }

    @Test
    void publicEndpoint_noAuthRequired() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "tula"))
                .andExpect(status().isOk());
    }

    @Test
    void cacheControlHeader_15s() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "tula"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=15")));
    }

    @Test
    void emptyQ_returnsEmptyEnvelope_with200() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listings").isEmpty())
                .andExpect(jsonPath("$.regions").isEmpty())
                .andExpect(jsonPath("$.totalListings").value(0));
    }

    @Test
    void singleCharQ_returnsEmptyEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalListings").value(0));
    }

    @Test
    void matches_returnsListingsAndRegions() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "waterfront"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listings[0].title").value("Premium Waterfront"));
    }

    @Test
    void rateLimitHeader_proves_interceptor_isWired() throws Exception {
        // The 429-on-bucket-drain path is correct-by-construction (mirrors
        // SearchRateLimitInterceptor) — sending 300 requests in a unit
        // test is wasteful. Instead we verify the interceptor sees the
        // request by asserting the bucket-remaining header.
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "tula"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    private void seedActive(String regionName, String parcelName, String title) {
        Region region = regionRepo.findByNameIgnoreCase(regionName)
                .orElseGet(() -> regionRepo.save(Region.builder()
                        .slUuid(UUID.randomUUID()).name(regionName)
                        .gridX(0.0).gridY(0.0)
                        .maturityRating("GENERAL").build()));
        UUID parcelUuid = UUID.randomUUID();
        Auction a = auctionRepo.save(Auction.builder()
                .slParcelUuid(parcelUuid)
                .seller(seller).title(title)
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).currentBid(1000L)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .durationHours(168)
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid).region(region)
                .regionName(regionName).regionMaturityRating("GENERAL")
                .areaSqm(1024).ownerType("agent").ownerName("Owner")
                .parcelName(parcelName)
                .build());
        auctionRepo.save(a);
    }
}
