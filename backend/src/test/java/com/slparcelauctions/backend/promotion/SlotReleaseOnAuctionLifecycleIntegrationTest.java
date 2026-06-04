package com.slparcelauctions.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.auctionend.AuctionEndTask;
import com.slparcelauctions.backend.auction.broadcast.CapturingAuctionBroadcastPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Verifies that auction lifecycle transitions (END, CANCEL, ADMIN_CANCEL) release
 * any active PROMO-01 featured-board slot via
 * {@link FeaturedBoardSlotService#releaseForAuction} registered on afterCommit.
 *
 * <p>The test is NOT {@code @Transactional}: the production paths commit their own
 * transactions and the afterCommit hooks fire post-commit. Fixtures are seeded in an
 * explicit {@link TransactionTemplate} and cleaned up in {@link #cleanUp}.
 */
@SpringBootTest
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
@Import(SlotReleaseOnAuctionLifecycleIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SlotReleaseOnAuctionLifecycleIntegrationTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingAuctionBroadcastPublisher capturingPublisher() {
            return new CapturingAuctionBroadcastPublisher();
        }
    }

    @Autowired AuctionEndTask auctionEndTask;
    @Autowired CancellationService cancellationService;
    @Autowired FeaturedBoardSlotService slotService;
    @Autowired FeaturedBoardSlotRepository slotRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;
    @Autowired CapturingAuctionBroadcastPublisher capturingPublisher;

    // Seeded entity ids -- nulled in cleanUp.
    private Long sellerId;
    private Long auctionId;
    // Extra user ids to clean up (e.g. admin seeded by adminCancel test).
    private final java.util.List<Long> extraUserIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        capturingPublisher.reset();
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM featured_board_slots WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (sellerId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM sl_im_message WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM users WHERE id = " + sellerId);
                }
                for (Long uid : extraUserIds) {
                    st.execute("DELETE FROM notification WHERE user_id = " + uid);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + uid);
                    st.execute("DELETE FROM users WHERE id = " + uid);
                }
            }
        }
        auctionId = null;
        sellerId = null;
        extraUserIds.clear();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void auctionEnd_releases_slot() {
        seedActiveExpiredAuctionWithSlot();

        auctionEndTask.closeOne(auctionId);

        assertThat(slotRepo.findActiveByAuctionId(auctionId)).isEmpty();
    }

    @Test
    void cancel_releases_slot() {
        seedActiveAuctionWithSlot();

        cancellationService.cancel(auctionId, "test cancel", "127.0.0.1");

        assertThat(slotRepo.findActiveByAuctionId(auctionId)).isEmpty();
    }

    @Test
    void adminCancel_releases_slot() {
        Long adminId = seedAdmin();
        extraUserIds.add(adminId);
        seedActiveAuctionWithSlot();

        cancellationService.cancelByAdmin(auctionId, adminId, "admin cancel test");

        assertThat(slotRepo.findActiveByAuctionId(auctionId)).isEmpty();
    }

    @Test
    void auctionEnd_noSlot_isIdempotent() {
        // Auction with no featured slot: closeOne must not throw.
        seedActiveExpiredAuctionWithSlot();
        // Release the slot before closeOne runs.
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            slotRepo.findActiveByAuctionId(auctionId).ifPresent(sl -> {
                sl.setReleasedAt(OffsetDateTime.now());
                slotRepo.save(sl);
            }));

        auctionEndTask.closeOne(auctionId);

        assertThat(slotRepo.findActiveByAuctionId(auctionId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Seeding helpers
    // -------------------------------------------------------------------------

    /**
     * Seeds an ACTIVE auction whose endsAt is in the past (so {@code closeOne}
     * classifies it as NO_BIDS and flips to EXPIRED), assigns a featured-board slot,
     * and records {@code auctionId} / {@code sellerId} for cleanup.
     */
    private void seedActiveExpiredAuctionWithSlot() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = buildAndSaveSeller();
            Auction auction = buildAndSaveAuction(seller, OffsetDateTime.now().minusSeconds(5));
            slotService.assign(auction);
            sellerId = seller.getId();
            auctionId = auction.getId();
        });
    }

    /**
     * Seeds an ACTIVE auction whose endsAt is in the future (cancellable state),
     * assigns a featured-board slot.
     */
    private void seedActiveAuctionWithSlot() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = buildAndSaveSeller();
            Auction auction = buildAndSaveAuction(seller, OffsetDateTime.now().plusHours(48));
            slotService.assign(auction);
            sellerId = seller.getId();
            auctionId = auction.getId();
        });
    }

    private Long seedAdmin() {
        return new TransactionTemplate(txManager).execute(s -> {
            User admin = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("slot-admin-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Slot Test Admin")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
            return admin.getId();
        });
    }

    private User buildAndSaveSeller() {
        return userRepo.save(User.builder()
            .username("u-" + UUID.randomUUID().toString().substring(0, 8))
            .email("slot-seller-" + UUID.randomUUID() + "@example.com")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("Slot Test Seller")
            .slAvatarUuid(UUID.randomUUID())
            .verified(true)
            .build());
    }

    private Auction buildAndSaveAuction(User seller, OffsetDateTime endsAt) {
        UUID parcelUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = auctionRepo.save(Auction.builder()
            .title("Slot Release Test Listing")
            .slParcelUuid(parcelUuid)
            .seller(seller)
            .status(AuctionStatus.ACTIVE)
            .verificationTier(VerificationTier.SCRIPT)
            .startingBid(500L)
            .currentBid(0L)
            .bidCount(0)
            .durationHours(168)
            .snipeProtect(false)
            .listingFeePaid(true)
            .consecutiveWorldApiFailures(0)
            .commissionRate(new BigDecimal("0.05"))
            .startsAt(now.minusHours(2))
            .endsAt(endsAt)
            .originalEndsAt(endsAt)
            .build());
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
            .slParcelUuid(parcelUuid)
            .ownerUuid(seller.getSlAvatarUuid())
            .ownerType("agent")
            .parcelName("Slot Release Test Parcel")
            .regionName("Test Region")
            .regionMaturityRating("GENERAL")
            .areaSqm(512)
            .positionX(64.0).positionY(64.0).positionZ(22.0)
            .build());
        return auctionRepo.save(auction);
    }
}
