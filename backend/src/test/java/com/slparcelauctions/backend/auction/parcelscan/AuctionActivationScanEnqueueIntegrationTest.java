package com.slparcelauctions.backend.auction.parcelscan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.AdminAuctionService;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration test asserting that a SCAN_PARCEL task is enqueued whenever an
 * auction transitions to ACTIVE, and that it is skipped when eligibility
 * conditions are not met.
 *
 * <p>Two activation paths are covered:
 * <ul>
 *   <li><b>Verification path</b> ({@link com.slparcelauctions.backend.auction.AuctionVerificationService}) -
 *       exercised by calling {@link ParcelScanService#enqueueIfEligible} directly
 *       after an ACTIVE flip, because {@code triggerVerification} requires a
 *       live SL World API round-trip that is not available in integration tests.
 *       The wiring at the call site in {@code AuctionVerificationService} is
 *       confirmed by code review of the diff; this test proves the service
 *       behavior end-to-end.</li>
 *   <li><b>Admin reinstate path</b> ({@link AdminAuctionService#reinstate}) -
 *       invoked directly because it requires only a SUSPENDED auction in the DB
 *       with no external API calls.</li>
 * </ul>
 *
 * <p>Uses the {@code @SpringBootTest + @Transactional} auto-rollback pattern
 * from {@link ParcelScanServiceTest}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class AuctionActivationScanEnqueueIntegrationTest {

    @Autowired ParcelScanService parcelScanService;
    @Autowired AdminAuctionService adminAuctionService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired AuctionParcelLayoutRepository layoutRepo;
    @Autowired BotTaskRepository botTaskRepo;
    @Autowired UserRepository userRepo;

    @PersistenceContext EntityManager em;

    // --- Verification-path tests (direct enqueueIfEligible call) ---

    @Test
    void activate_viaVerification_enqueuesScanTask() {
        Auction auction = savedAuction(savedUser("verify-enqueue"), AuctionStatus.ACTIVE, true);
        em.flush();

        parcelScanService.enqueueIfEligible(auction);
        em.flush();

        assertThat(botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)).isTrue();
    }

    @Test
    void activate_skipsScanWhenParcelScanIncludedFalse() {
        Auction auction = savedAuction(savedUser("verify-scan-off"), AuctionStatus.ACTIVE, false);
        em.flush();

        parcelScanService.enqueueIfEligible(auction);
        em.flush();

        assertThat(botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)).isFalse();
    }

    @Test
    void activate_skipsScanWhenLayoutAlreadyExists() {
        Auction auction = savedAuction(savedUser("verify-has-layout"), AuctionStatus.ACTIVE, true);

        layoutRepo.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[512])
                .scannedAt(OffsetDateTime.now())
                .build());
        em.flush();

        parcelScanService.enqueueIfEligible(auction);
        em.flush();

        assertThat(botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)).isFalse();
    }

    // --- Admin reinstate path test (invokes AdminAuctionService.reinstate directly) ---

    @Test
    void activate_viaAdmin_enqueuesScanTask() {
        Auction auction = savedAuction(savedUser("admin-reinstate"), AuctionStatus.SUSPENDED, true);
        auction.setSuspendedAt(OffsetDateTime.now().minusHours(1));
        auction.setStartsAt(OffsetDateTime.now().minusHours(3));
        auction.setEndsAt(OffsetDateTime.now().plusHours(1));
        auction.setOriginalEndsAt(OffsetDateTime.now().plusHours(1));
        auctionRepo.save(auction);
        em.flush();

        adminAuctionService.reinstate(auction.getId(), Optional.empty());
        em.flush();

        assertThat(botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)).isTrue();
    }

    // --- fixtures ---

    private User savedUser(String label) {
        return userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(label + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName(label + " " + UUID.randomUUID())
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());
    }

    private Auction savedAuction(User seller, AuctionStatus status, boolean parcelScanIncluded) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
                .title("Activation scan test listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(status)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1_000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .parcelScanIncluded(parcelScanIncluded)
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
}
