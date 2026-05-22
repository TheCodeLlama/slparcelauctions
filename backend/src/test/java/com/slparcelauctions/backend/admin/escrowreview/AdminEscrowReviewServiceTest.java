package com.slparcelauctions.backend.admin.escrowreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.admin.escrowreview.exception.EscrowReviewAlreadyResolvedException;
import com.slparcelauctions.backend.admin.escrowreview.exception.EscrowReviewNotFoundException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowCommissionCalculator;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.review.EscrowManualReview;
import com.slparcelauctions.backend.escrow.review.EscrowManualReviewRepository;
import com.slparcelauctions.backend.escrow.review.ManualReviewReason;
import com.slparcelauctions.backend.escrow.review.ManualReviewResolution;
import com.slparcelauctions.backend.escrow.review.ManualReviewRole;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;
import com.slparcelauctions.backend.escrow.review.ManualReviewStep;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration tests for {@link AdminEscrowReviewService}.
 *
 * <p>Seeds Auction + Escrow + EscrowManualReview rows directly so each test
 * exercises the resolve/list/detail orchestration without driving the full
 * upstream escrow lifecycle. Teardown is FK-ordered via raw JDBC.
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
class AdminEscrowReviewServiceTest {

    @Autowired AdminEscrowReviewService service;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowManualReviewRepository reviewRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long bidderId;
    private Long adminId;
    private Long auctionId;
    private Long escrowId;
    private UUID reviewPublicId;

    // -------------------------------------------------------------------------
    // resolve() — the four resolution actions
    // -------------------------------------------------------------------------

    @Test
    void forceConfirmSellTo_stampsReviewResolved_andConfirmsSellTo() {
        seed(EscrowState.TRANSFER_PENDING, ManualReviewStep.SET_SELL_TO, /* sellToConfirmed */ false);

        AdminEscrowReviewResolveResponse resp = service.resolve(
                reviewPublicId,
                new AdminEscrowReviewResolveRequest(
                        ManualReviewResolution.FORCE_CONFIRM_SELL_TO, "Admin verified sell-to in-world"),
                adminId);

        assertThat(resp.newStatus()).isEqualTo(ManualReviewStatus.RESOLVED);
        assertThat(resp.resolution()).isEqualTo(ManualReviewResolution.FORCE_CONFIRM_SELL_TO);
        assertThat(resp.resolvedAt()).isNotNull();

        EscrowManualReview review = loadReview();
        assertThat(review.getStatus()).isEqualTo(ManualReviewStatus.RESOLVED);
        assertThat(review.getResolution()).isEqualTo(ManualReviewResolution.FORCE_CONFIRM_SELL_TO);
        assertThat(review.getResolvedByAdminId()).isEqualTo(adminId);
        assertThat(review.getResolvedAt()).isNotNull();
        assertThat(review.getAdminNotes()).isEqualTo("Admin verified sell-to in-world");

        Escrow escrow = loadEscrow();
        assertThat(escrow.getSellToConfirmedAt()).isNotNull();
        assertThat(escrow.getTransferDeadline()).isNotNull();
    }

    @Test
    void forceCompleteTransfer_stampsReviewResolved_andConfirmsTransfer() {
        seed(EscrowState.TRANSFER_PENDING, ManualReviewStep.BUY_PARCEL, /* sellToConfirmed */ true);

        AdminEscrowReviewResolveResponse resp = service.resolve(
                reviewPublicId,
                new AdminEscrowReviewResolveRequest(
                        ManualReviewResolution.FORCE_COMPLETE_TRANSFER, "Admin verified purchase in-world"),
                adminId);

        assertThat(resp.newStatus()).isEqualTo(ManualReviewStatus.RESOLVED);
        assertThat(resp.resolution()).isEqualTo(ManualReviewResolution.FORCE_COMPLETE_TRANSFER);

        EscrowManualReview review = loadReview();
        assertThat(review.getStatus()).isEqualTo(ManualReviewStatus.RESOLVED);

        Escrow escrow = loadEscrow();
        assertThat(escrow.getTransferConfirmedAt()).isNotNull();
    }

    @Test
    void refundWinner_stampsReviewResolved_andExpiresEscrow() {
        seed(EscrowState.TRANSFER_PENDING, ManualReviewStep.BUY_PARCEL, /* sellToConfirmed */ true);

        AdminEscrowReviewResolveResponse resp = service.resolve(
                reviewPublicId,
                new AdminEscrowReviewResolveRequest(
                        ManualReviewResolution.REFUND_WINNER, "Seller never delivered; refund the winner"),
                adminId);

        assertThat(resp.newStatus()).isEqualTo(ManualReviewStatus.RESOLVED);
        assertThat(resp.resolution()).isEqualTo(ManualReviewResolution.REFUND_WINNER);

        EscrowManualReview review = loadReview();
        assertThat(review.getStatus()).isEqualTo(ManualReviewStatus.RESOLVED);

        Escrow escrow = loadEscrow();
        assertThat(escrow.getState()).isEqualTo(EscrowState.EXPIRED);
        assertThat(escrow.getExpiredAt()).isNotNull();
    }

    @Test
    void dismiss_stampsReviewDismissed_noEscrowChange() {
        seed(EscrowState.TRANSFER_PENDING, ManualReviewStep.SET_SELL_TO, /* sellToConfirmed */ false);

        AdminEscrowReviewResolveResponse resp = service.resolve(
                reviewPublicId,
                new AdminEscrowReviewResolveRequest(
                        ManualReviewResolution.DISMISS, "Not actionable; closing"),
                adminId);

        assertThat(resp.newStatus()).isEqualTo(ManualReviewStatus.DISMISSED);
        assertThat(resp.resolution()).isEqualTo(ManualReviewResolution.DISMISS);

        EscrowManualReview review = loadReview();
        assertThat(review.getStatus()).isEqualTo(ManualReviewStatus.DISMISSED);
        assertThat(review.getResolvedByAdminId()).isEqualTo(adminId);

        Escrow escrow = loadEscrow();
        assertThat(escrow.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(escrow.getSellToConfirmedAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // resolve() — guards
    // -------------------------------------------------------------------------

    @Test
    void resolvingAlreadyResolvedReview_throwsConflict() {
        seed(EscrowState.TRANSFER_PENDING, ManualReviewStep.SET_SELL_TO, /* sellToConfirmed */ false);

        service.resolve(
                reviewPublicId,
                new AdminEscrowReviewResolveRequest(ManualReviewResolution.DISMISS, "first close"),
                adminId);

        assertThatThrownBy(() -> service.resolve(
                reviewPublicId,
                new AdminEscrowReviewResolveRequest(ManualReviewResolution.DISMISS, "second close"),
                adminId))
                .isInstanceOf(EscrowReviewAlreadyResolvedException.class);
    }

    @Test
    void resolvingUnknownReview_throwsNotFound() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> service.resolve(
                missing,
                new AdminEscrowReviewResolveRequest(ManualReviewResolution.DISMISS, "no such review"),
                1L))
                .isInstanceOf(EscrowReviewNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // list() + detail()
    // -------------------------------------------------------------------------

    @Test
    void list_returnsOpenReviewRow() {
        seed(EscrowState.TRANSFER_PENDING, ManualReviewStep.SET_SELL_TO, /* sellToConfirmed */ false);

        Page<AdminEscrowReviewRow> page = service.list(ManualReviewStatus.OPEN, 0, 20);

        assertThat(page.getContent())
                .anySatisfy(row -> {
                    assertThat(row.reviewPublicId()).isEqualTo(reviewPublicId);
                    assertThat(row.status()).isEqualTo(ManualReviewStatus.OPEN);
                    assertThat(row.step()).isEqualTo(ManualReviewStep.SET_SELL_TO);
                    assertThat(row.parcelName()).isEqualTo("Escrow Review Parcel");
                });
    }

    @Test
    void detail_returnsEscrowSnapshotAndEvidence() {
        seed(EscrowState.TRANSFER_PENDING, ManualReviewStep.SET_SELL_TO, /* sellToConfirmed */ false);

        AdminEscrowReviewDetail detail = service.detail(reviewPublicId);

        assertThat(detail.reviewPublicId()).isEqualTo(reviewPublicId);
        assertThat(detail.status()).isEqualTo(ManualReviewStatus.OPEN);
        assertThat(detail.step()).isEqualTo(ManualReviewStep.SET_SELL_TO);
        assertThat(detail.parcelName()).isEqualTo("Escrow Review Parcel");
        assertThat(detail.escrowState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(detail.sellToLastResult()).isEqualTo("WRONG_BUYER");
        assertThat(detail.sellToVerifyAttempts()).isEqualTo(3);
    }

    @Test
    void detail_unknownReview_throwsNotFound() {
        assertThatThrownBy(() -> service.detail(UUID.randomUUID()))
                .isInstanceOf(EscrowReviewNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Seeding helpers
    // -------------------------------------------------------------------------

    private void seed(EscrowState escrowState, ManualReviewStep step, boolean sellToConfirmed) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-review-seller-" + UUID.randomUUID() + "@test.com")
                    .passwordHash("hash")
                    .displayName("Escrow Review Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .cancelledWithBids(0)
                    .penaltyBalanceOwed(0L)
                    .build());
            User bidder = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-review-bidder-" + UUID.randomUUID() + "@test.com")
                    .passwordHash("hash")
                    .displayName("Escrow Review Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .cancelledWithBids(0)
                    .penaltyBalanceOwed(0L)
                    .build());
            User admin = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-review-admin-" + UUID.randomUUID() + "@test.com")
                    .passwordHash("hash")
                    .displayName("Escrow Review Admin")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .role(Role.ADMIN)
                    .build());

            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            long finalBid = 5_000L;
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Escrow Review Test Lot")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
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
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());

            Escrow.EscrowBuilder escrowBuilder = Escrow.builder()
                    .auction(auction)
                    .state(escrowState)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid, auction.getCommissionRate()))
                    .payoutAmt(commissionCalculator.payout(finalBid, auction.getCommissionRate()))
                    .consecutiveWorldApiFailures(0)
                    .fundedAt(now.minusMinutes(30))
                    .transferDeadline(now.plusHours(72))
                    .sellToLastResult("WRONG_BUYER")
                    .sellToVerifyAttempts(3)
                    .buyVerifySellerAttempts(0)
                    .buyVerifyBuyerAttempts(0);
            if (sellToConfirmed) {
                escrowBuilder.sellToConfirmedAt(now.minusMinutes(10));
            }

            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("Escrow Review Parcel")
                    .regionName("Test Region")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .slurl("http://maps.secondlife.com/secondlife/Test%20Region/128/64/22")
                    .build());
            auctionRepo.save(auction);

            Escrow escrow = escrowRepo.save(escrowBuilder.build());

            EscrowManualReview review = reviewRepo.save(EscrowManualReview.builder()
                    .escrow(escrow)
                    .requestedByUserId(bidder.getId())
                    .requestedRole(ManualReviewRole.BUYER)
                    .step(step)
                    .reason(ManualReviewReason.USER_REQUESTED)
                    .status(ManualReviewStatus.OPEN)
                    .build());

            sellerId = seller.getId();
            bidderId = bidder.getId();
            adminId = admin.getId();
            auctionId = auction.getId();
            escrowId = escrow.getId();
            reviewPublicId = review.getPublicId();
        });
    }

    private Escrow loadEscrow() {
        return new TransactionTemplate(txManager).execute(s ->
                escrowRepo.findById(escrowId).orElseThrow());
    }

    private EscrowManualReview loadReview() {
        return new TransactionTemplate(txManager).execute(s ->
                reviewRepo.findByPublicId(reviewPublicId).orElseThrow());
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (escrowId != null) {
                    st.execute("DELETE FROM escrow_manual_reviews WHERE escrow_id = " + escrowId);
                    st.execute("DELETE FROM terminal_commands WHERE escrow_id = " + escrowId);
                    st.execute("DELETE FROM escrow_transactions WHERE escrow_id = " + escrowId);
                    st.execute("DELETE FROM escrows WHERE id = " + escrowId);
                }
                if (auctionId != null) {
                    st.execute("DELETE FROM admin_actions WHERE target_id = " + escrowId
                            + " AND target_type = 'ESCROW_REVIEW'");
                    st.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM bids WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                for (Long uid : new Long[]{sellerId, bidderId, adminId}) {
                    if (uid != null) {
                        st.execute("DELETE FROM notification WHERE user_id = " + uid);
                        st.execute("DELETE FROM sl_im_message WHERE user_id = " + uid);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + uid);
                        st.execute("DELETE FROM user_ledger WHERE user_id = " + uid);
                        st.execute("DELETE FROM users WHERE id = " + uid);
                    }
                }
            }
        }
        sellerId = bidderId = adminId = auctionId = escrowId = null;
        reviewPublicId = null;
    }
}
