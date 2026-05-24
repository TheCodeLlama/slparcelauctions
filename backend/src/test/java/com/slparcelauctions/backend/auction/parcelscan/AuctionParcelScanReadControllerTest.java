package com.slparcelauctions.backend.auction.parcelscan;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

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
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Full-stack integration tests for
 * {@code GET /api/v1/auctions/{publicId}/parcel-scan}.
 *
 * <p>Uses {@code @SpringBootTest} so the full Spring Security filter chain,
 * JWT auth filter, {@code AuctionExceptionHandler}, and
 * {@code GlobalExceptionHandler} all run. Class-level {@code @Transactional}
 * provides automatic rollback after each test so rows don't bleed across cases.
 *
 * <p>Fixture helpers ({@link #newUser} / {@link #newAuction}) are copied
 * verbatim from {@link ParcelScanServiceTest}.
 */
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
class AuctionParcelScanReadControllerTest {

    @Autowired MockMvc mvc;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionParcelLayoutRepository layoutRepo;
    @Autowired AuctionParcelHeightMapRepository heightRepo;

    // --- test cases ---

    @Test
    void happyPath_returns200WithAllSevenFieldsAndCacheControl() throws Exception {
        Auction auction = newAuction(newUser("happy"));
        seedLayout(auction);
        seedHeightMap(auction);

        String expectedLayoutB64 = Base64.getEncoder().encodeToString(new byte[512]);
        String expectedHeightB64 = Base64.getEncoder().encodeToString(new byte[4096]);

        mvc.perform(get("/api/v1/auctions/{id}/parcel-scan", auction.getPublicId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(jsonPath("$.gridSize").value(64))
                .andExpect(jsonPath("$.cellSizeMeters").value(4))
                .andExpect(jsonPath("$.layoutCellsBase64").value(expectedLayoutB64))
                .andExpect(jsonPath("$.heightCellsBase64").value(expectedHeightB64))
                .andExpect(jsonPath("$.baseMeters").value(22.0))
                .andExpect(jsonPath("$.stepMeters").value(0.5))
                .andExpect(jsonPath("$.scannedAt").exists());
    }

    @Test
    void unknownAuction_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(get("/api/v1/auctions/{id}/parcel-scan", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARCEL_SCAN_NOT_FOUND"));
    }

    @Test
    void missingLayout_returns404() throws Exception {
        Auction auction = newAuction(newUser("no-layout"));
        seedHeightMap(auction);

        mvc.perform(get("/api/v1/auctions/{id}/parcel-scan", auction.getPublicId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARCEL_SCAN_NOT_FOUND"));
    }

    @Test
    void missingHeightMap_returns404() throws Exception {
        Auction auction = newAuction(newUser("no-height"));
        seedLayout(auction);

        mvc.perform(get("/api/v1/auctions/{id}/parcel-scan", auction.getPublicId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARCEL_SCAN_NOT_FOUND"));
    }

    @Test
    void publicAccess_noAuth_returns200() throws Exception {
        Auction auction = newAuction(newUser("public-access"));
        seedLayout(auction);
        seedHeightMap(auction);

        // No Authorization header -- anonymous request
        mvc.perform(get("/api/v1/auctions/{id}/parcel-scan", auction.getPublicId()))
                .andExpect(status().isOk());
    }

    // --- fixtures (copied verbatim from ParcelScanServiceTest) ---

    private User newUser(String label) {
        return userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(label + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName(label + " " + UUID.randomUUID())
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());
    }

    private Auction newAuction(User seller) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
                .title("Scan test listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1_000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .parcelName("Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepo.save(a);
    }

    private void seedLayout(Auction auction) {
        layoutRepo.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[512])
                .scannedAt(OffsetDateTime.now())
                .build());
    }

    private void seedHeightMap(Auction auction) {
        heightRepo.save(AuctionParcelHeightMap.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[4096])
                .baseMeters(22.0f)
                .stepMeters(0.5f)
                .scannedAt(OffsetDateTime.now())
                .build());
    }
}
