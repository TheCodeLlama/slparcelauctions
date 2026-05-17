package com.slparcelauctions.backend.admin.listings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.listings.exception.AdminListingStateException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration coverage for the admin-cancel-from-escrow path
 * ({@link AdminListingService#cancel(UUID, Long, String)} routing through
 * {@code CancellationService.cancelByAdminFromEscrow}) introduced by the
 * 2026-05-17 auction-status state-machine rewire.
 *
 * <p>Seeds an auction in {@code TRANSFER_PENDING} with a funded escrow row
 * (mirrors the post-close state machine that
 * {@code EscrowService.createForEndedAuction} leaves behind), then exercises
 * the admin cancel endpoint to assert:
 *
 * <ul>
 *   <li>Auction flips to {@link AuctionStatus#CANCELLED}.</li>
 *   <li>Escrow row transitions to {@link EscrowState#EXPIRED} with
 *       {@code freezeReason = ADMIN_CANCEL}.</li>
 *   <li>Winner is refunded via an {@code AUCTION_ESCROW_REFUND} ledger row.</li>
 *   <li>Seller receives {@code LISTING_REMOVED_BY_ADMIN}.</li>
 *   <li>Winner receives {@code LISTING_CANCELLED_DURING_ESCROW}.</li>
 *   <li>Routing through this path on a non-TRANSFER_PENDING auction rejects
 *       with {@code INVALID_STATUS_FOR_ACTION}.</li>
 * </ul>
 *
 * <p>Mirrors the seeding + teardown shape used by
 * {@code EscrowCreateOnAuctionEndIntegrationTest} and
 * {@code AdminListingFeaturedIntegrationTest}: raw JDBC teardown in
 * {@code @AfterEach} so leftover rows from a failure don't leak into
 * subsequent tests that share the database.
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
class AdminListingCancelFromEscrowIntegrationTest {

    @Autowired AdminListingService adminListingService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired UserRepository userRepo;
    @Autowired AdminActionRepository adminActionRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private Long sellerId;
    private Long winnerId;
    private Long adminId;
    private Long auctionId;
    private Long escrowId;
    private UUID auctionPublicId;
    private final long finalBid = 5_000L;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("admin-cancel-seller-" + UUID.randomUUID() + "@x.com")
                    .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                    .displayName("Admin Cancel Seller").verified(true).build());
            sellerId = seller.getId();

            User winner = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("admin-cancel-winner-" + UUID.randomUUID() + "@x.com")
                    .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                    .displayName("Admin Cancel Winner").verified(true)
                    .balanceLindens(0L).reservedLindens(0L).build());
            winnerId = winner.getId();

            User admin = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("admin-cancel-admin-" + UUID.randomUUID() + "@x.com")
                    .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                    .displayName("Admin Cancel Admin").role(Role.ADMIN).build());
            adminId = admin.getId();

            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Admin-Cancel Test Lot")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    // TRANSFER_PENDING mirrors what EscrowService.createForEndedAuction
                    // leaves the auction in after a SOLD/BOUGHT_NOW close.
                    .status(AuctionStatus.TRANSFER_PENDING)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .currentBid(finalBid)
                    .bidCount(2)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .startsAt(now.minusHours(3))
                    .endsAt(now.minusSeconds(1))
                    .originalEndsAt(now.minusSeconds(1))
                    .endedAt(now.minusSeconds(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(winner.getId())
                    .finalBidAmount(finalBid)
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("Admin Cancel Parcel")
                    .regionName("Coniston")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);
            auctionId = auction.getId();
            auctionPublicId = auction.getPublicId();

            // Funded escrow row in TRANSFER_PENDING — production state after
            // createForEndedAuction's auto-fund. fundedAt non-null so the
            // refund queues; payoutAmt mirrors commission math.
            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(250L)
                    .payoutAmt(finalBid - 250L)
                    .consecutiveWorldApiFailures(0)
                    .fundedAt(now.minusMinutes(5))
                    .transferDeadline(now.plusHours(72))
                    .build());
            escrowId = escrow.getId();

            // Original AUCTION_ESCROW_PAYMENT ledger row (auto-fund debit).
            escrowTxRepo.save(EscrowTransaction.builder()
                    .escrow(escrow)
                    .auction(auction)
                    .type(EscrowTransactionType.AUCTION_ESCROW_PAYMENT)
                    .status(com.slparcelauctions.backend.escrow.EscrowTransactionStatus.COMPLETED)
                    .amount(finalBid)
                    .payer(winner)
                    .completedAt(now.minusMinutes(5))
                    .build());
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM escrow_transactions WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM escrows WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM admin_actions WHERE target_type = 'LISTING' AND target_id = " + auctionId);
                    st.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM listing_fee_refunds WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                for (Long uid : List.of(
                        sellerId == null ? -1L : sellerId,
                        winnerId == null ? -1L : winnerId,
                        adminId == null ? -1L : adminId)) {
                    if (uid > 0) {
                        st.execute("DELETE FROM user_ledger WHERE user_id = " + uid);
                        st.execute("DELETE FROM notification WHERE user_id = " + uid);
                        st.execute("DELETE FROM sl_im_message WHERE user_id = " + uid);
                        st.execute("DELETE FROM admin_actions WHERE admin_user_id = " + uid);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + uid);
                        st.execute("DELETE FROM users WHERE id = " + uid);
                    }
                }
            }
        }
        sellerId = null;
        winnerId = null;
        adminId = null;
        auctionId = null;
        escrowId = null;
        auctionPublicId = null;
    }

    @Test
    void cancelFromTransferPending_flipsAuctionAndEscrow_refundsWinner_notifiesBothParties() {
        adminListingService.cancel(auctionPublicId, adminId, "Listing taken down by admin");

        // Auction -> CANCELLED.
        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.CANCELLED);

        // Escrow -> EXPIRED with ADMIN_CANCEL reason.
        Escrow escrow = escrowRepo.findById(escrowId).orElseThrow();
        assertThat(escrow.getState()).isEqualTo(EscrowState.EXPIRED);
        assertThat(escrow.getFreezeReason()).isEqualTo(FreezeReason.ADMIN_CANCEL.name());
        assertThat(escrow.getExpiredAt()).isNotNull();

        // Refund ledger row written.
        List<EscrowTransaction> ledger = escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(escrowId);
        assertThat(ledger)
                .extracting(EscrowTransaction::getType)
                .contains(EscrowTransactionType.AUCTION_ESCROW_REFUND);
        EscrowTransaction refund = ledger.stream()
                .filter(t -> t.getType() == EscrowTransactionType.AUCTION_ESCROW_REFUND)
                .findFirst().orElseThrow();
        assertThat(refund.getAmount()).isEqualTo(finalBid);

        // Notifications.
        List<Notification> sellerNotifs = notificationRepo.findAllByUserId(sellerId);
        assertThat(sellerNotifs)
                .extracting(Notification::getCategory)
                .contains(NotificationCategory.LISTING_REMOVED_BY_ADMIN);

        List<Notification> winnerNotifs = notificationRepo.findAllByUserId(winnerId);
        assertThat(winnerNotifs)
                .extracting(Notification::getCategory)
                .contains(NotificationCategory.LISTING_CANCELLED_DURING_ESCROW);

        // Admin action row records the cancel for audit trail.
        var actions = adminActionRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                AdminActionTargetType.LISTING, auctionId,
                org.springframework.data.domain.Pageable.unpaged());
        assertThat(actions.getContent())
                .extracting(a -> a.getActionType())
                .contains(AdminActionType.CANCEL_LISTING_FROM_REPORT);
    }

    @Test
    void cancelOnNonTransferPendingAuction_rejectsWithInvalidStatus() {
        // Flip the seeded auction back to ACTIVE before routing — the admin
        // listing service then delegates to cancelByAdmin (CANCELLABLE set),
        // which DOES accept ACTIVE. To exercise the rejection branch we put
        // it into a status that neither path accepts: COMPLETED is terminal
        // and outside CANCELLABLE.
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction a = auctionRepo.findById(auctionId).orElseThrow();
            a.setStatus(AuctionStatus.COMPLETED);
            auctionRepo.save(a);
        });

        assertThatThrownBy(() -> adminListingService.cancel(
                auctionPublicId, adminId, "Should reject"))
                .isInstanceOfSatisfying(AdminListingStateException.class, e ->
                        assertThat(e.getCode()).isEqualTo("INVALID_STATUS_FOR_ACTION"));

        // Auction status unchanged.
        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.COMPLETED);
    }
}
