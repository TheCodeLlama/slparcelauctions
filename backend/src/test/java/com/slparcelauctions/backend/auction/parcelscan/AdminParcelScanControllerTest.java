package com.slparcelauctions.backend.auction.parcelscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Full-stack integration test for
 * {@code POST /api/v1/admin/parcel-scan/{publicId}/reenqueue}.
 *
 * <p>Uses {@code @SpringBootTest + @AutoConfigureMockMvc} so the full Spring
 * Security filter chain (including the {@code hasRole("ADMIN")} gate on
 * {@code /api/v1/admin/**}), JWT auth filter, and
 * {@link AdminParcelScanController} all run.
 *
 * <p>Class-level {@code @Transactional} provides automatic rollback after each
 * test so rows do not bleed across test cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        "slpa.review.scheduler.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class AdminParcelScanControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired AuctionParcelLayoutRepository layoutRepo;
    @Autowired AuctionParcelHeightMapRepository heightRepo;
    @Autowired BotTaskRepository botTaskRepo;

    // --- token helpers ---

    private String adminToken() {
        User admin = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("admin-parcel-scan-" + UUID.randomUUID() + "@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin")
                .role(Role.ADMIN)
                .verified(true)
                .build());
        return jwtService.issueAccessToken(
                new AuthPrincipal(admin.getId(), admin.getPublicId(),
                        admin.getEmail(), 1L, Role.ADMIN));
    }

    private String userToken() {
        User user = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("user-parcel-scan-" + UUID.randomUUID() + "@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User")
                .role(Role.USER)
                .verified(true)
                .build());
        return jwtService.issueAccessToken(
                new AuthPrincipal(user.getId(), user.getPublicId(),
                        user.getEmail(), 1L, Role.USER));
    }

    // --- fixture helpers ---

    private Auction newAuction(User seller) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
                .title("Parcel scan reenqueue test listing")
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
                .parcelScanIncluded(true)
                .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .parcelName("Reenqueue Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepo.save(a);
    }

    private User newSeller(String label) {
        return userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(label + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName(label)
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());
    }

    private AuctionParcelLayout seedLayout(Auction auction) {
        return layoutRepo.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[512])
                .scannedAt(OffsetDateTime.now())
                .build());
    }

    private AuctionParcelHeightMap seedHeightMap(Auction auction) {
        return heightRepo.save(AuctionParcelHeightMap.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[4096])
                .baseMeters(22.0f)
                .stepMeters(0.5f)
                .scannedAt(OffsetDateTime.now())
                .build());
    }

    private BotTask seedInProgressTask(Auction auction) {
        BotTask task = BotTask.builder()
                .taskType(BotTaskType.SCAN_PARCEL)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(auction)
                .parcelUuid(auction.getSlParcelUuid())
                .regionName("Test Region")
                .positionX(128.0)
                .positionY(64.0)
                .positionZ(22.0)
                .sentinelPrice(0L)
                .build();
        return botTaskRepo.save(task);
    }

    // --- test cases ---

    /**
     * Happy path: auction has an existing layout, height map, and IN_PROGRESS task.
     * POST reenqueue must:
     *   - delete the layout row
     *   - delete the height map row
     *   - delete the old IN_PROGRESS task
     *   - create exactly one new PENDING SCAN_PARCEL task
     */
    @Test
    void reenqueue_happyPath_returns204_clearsOldRastersAndTask_createsNewPending() throws Exception {
        User seller = newSeller("happy-seller");
        Auction auction = newAuction(seller);
        seedLayout(auction);
        seedHeightMap(auction);
        BotTask oldTask = seedInProgressTask(auction);
        Long oldTaskId = oldTask.getId();

        mvc.perform(post("/api/v1/admin/parcel-scan/{publicId}/reenqueue",
                        auction.getPublicId())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        // Layout and height map rows must be gone
        assertThat(layoutRepo.existsByAuctionId(auction.getId())).isFalse();
        assertThat(heightRepo.existsByAuctionId(auction.getId())).isFalse();

        // Old task must be gone
        assertThat(botTaskRepo.findById(oldTaskId)).isEmpty();

        // Exactly one new PENDING SCAN_PARCEL task must exist
        assertThat(botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)).isTrue();
    }

    /**
     * Unknown publicId must return 404.
     */
    @Test
    void reenqueue_unknownPublicId_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(post("/api/v1/admin/parcel-scan/{publicId}/reenqueue", unknown)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());
    }

    /**
     * Non-admin role must be rejected with 403.
     */
    @Test
    void reenqueue_nonAdmin_returns403() throws Exception {
        User seller = newSeller("non-admin-seller");
        Auction auction = newAuction(seller);

        mvc.perform(post("/api/v1/admin/parcel-scan/{publicId}/reenqueue",
                        auction.getPublicId())
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isForbidden());
    }

    /**
     * Anonymous (no token) must be rejected with 401.
     */
    @Test
    void reenqueue_anonymous_returns401() throws Exception {
        User seller = newSeller("anon-seller");
        Auction auction = newAuction(seller);

        mvc.perform(post("/api/v1/admin/parcel-scan/{publicId}/reenqueue",
                        auction.getPublicId()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * No existing raster rows and no pending task: endpoint must still return
     * 204 and create exactly one new PENDING task.
     */
    @Test
    void reenqueue_noExistingRastersOrTask_returns204_createsNewPending() throws Exception {
        User seller = newSeller("no-rasters-seller");
        Auction auction = newAuction(seller);

        // No layout, no height map, no task seeded

        mvc.perform(post("/api/v1/admin/parcel-scan/{publicId}/reenqueue",
                        auction.getPublicId())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        assertThat(botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)).isTrue();
    }
}
