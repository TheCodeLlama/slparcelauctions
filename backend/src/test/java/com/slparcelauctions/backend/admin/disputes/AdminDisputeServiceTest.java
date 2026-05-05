package com.slparcelauctions.backend.admin.disputes;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.admin.disputes.exception.AlsoCancelInvalidForActionException;
import com.slparcelauctions.backend.admin.disputes.exception.DisputeActionInvalidForStateException;
import com.slparcelauctions.backend.admin.disputes.exception.DisputeNotFoundException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowCommissionCalculator;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration tests for {@link AdminDisputeService#resolve}.
 *
 * <p>Seeds Auction + Escrow rows directly in the required starting state
 * (DISPUTED or FROZEN) so each test exercises the orchestration logic
 * without requiring the full upstream auction lifecycle to reach those
 * states. Teardown is FK-ordered via raw JDBC so constraint violations
 * during cleanup do not mask test failures.
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
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminDisputeServiceTest {

    @Autowired AdminDisputeService service;
    @Autowired EscrowRepository escrowRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired TerminalCommandRepository terminalCommandRepo;
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

    // -------------------------------------------------------------------------
    // State-transition happy paths
    // -------------------------------------------------------------------------

    @Test
    void recognizePayment_disputedToTransferPending() {
        seed(EscrowState.DISPUTED, /* funded */ true);

        AdminDisputeResolveResponse resp = service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.RECOGNIZE_PAYMENT, null, "Payment confirmed by admin"),
                adminId);

        assertThat(resp.newState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(resp.refundQueued()).isFalse();
        assertThat(resp.listingCancelled()).isFalse();

        Escrow persisted = loadEscrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
    }

    @Test
    void resetToFunded_noCheckbox_disputedToFunded() {
        seed(EscrowState.DISPUTED, /* funded */ true);

        AdminDisputeResolveResponse resp = service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.RESET_TO_FUNDED, false, "Dispute dismissed"),
                adminId);

        assertThat(resp.newState()).isEqualTo(EscrowState.FUNDED);
        assertThat(resp.refundQueued()).isFalse();
        assertThat(resp.listingCancelled()).isFalse();

        Escrow persisted = loadEscrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.FUNDED);
    }

    @Test
    void resetToFunded_withCheckbox_disputedToExpired_refundQueuedAndListingCancelled() {
        seed(EscrowState.DISPUTED, /* funded */ true);

        AdminDisputeResolveResponse resp = service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.RESET_TO_FUNDED, true, "Cancel and refund"),
                adminId);

        assertThat(resp.newState()).isEqualTo(EscrowState.EXPIRED);
        assertThat(resp.refundQueued()).isTrue();
        assertThat(resp.listingCancelled()).isTrue();

        Escrow persisted = loadEscrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.EXPIRED);

        // Terminal REFUND command must have been queued.
        long refundCount = new TransactionTemplate(txManager).execute(s ->
                terminalCommandRepo.findAll().stream()
                        .filter(c -> c.getEscrowId() != null && c.getEscrowId().equals(escrowId))
                        .count());
        assertThat(refundCount).isGreaterThanOrEqualTo(1);

        // Auction must have been cancelled.
        Auction auction = new TransactionTemplate(txManager).execute(s ->
                auctionRepo.findById(auctionId).orElseThrow());
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
    }

    @Test
    void resumeTransfer_frozenToTransferPending() {
        seed(EscrowState.FROZEN, /* funded */ true);

        AdminDisputeResolveResponse resp = service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.RESUME_TRANSFER, null, "Ownership verified"),
                adminId);

        assertThat(resp.newState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(resp.refundQueued()).isFalse();
        assertThat(resp.listingCancelled()).isFalse();

        Escrow persisted = loadEscrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
    }

    @Test
    void markExpired_frozenToExpired_refundQueued() {
        seed(EscrowState.FROZEN, /* funded */ true);

        AdminDisputeResolveResponse resp = service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.MARK_EXPIRED, null, "Escrow expired by admin"),
                adminId);

        assertThat(resp.newState()).isEqualTo(EscrowState.EXPIRED);
        assertThat(resp.refundQueued()).isTrue();
        assertThat(resp.listingCancelled()).isFalse();

        Escrow persisted = loadEscrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.EXPIRED);

        long refundCount = new TransactionTemplate(txManager).execute(s ->
                terminalCommandRepo.findAll().stream()
                        .filter(c -> c.getEscrowId() != null && c.getEscrowId().equals(escrowId))
                        .count());
        assertThat(refundCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void markExpired_unfundedFrozen_doesNotQueueRefund() {
        seed(EscrowState.FROZEN, /* funded */ false);

        AdminDisputeResolveResponse resp = service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.MARK_EXPIRED, null, "Expired unfunded"),
                adminId);

        assertThat(resp.newState()).isEqualTo(EscrowState.EXPIRED);
        // fundedAt is null → no refund to queue.
        assertThat(resp.refundQueued()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Validation rejections
    // -------------------------------------------------------------------------

    @Test
    void recognizePayment_fromFrozen_throwsDisputeActionInvalidForState() {
        seed(EscrowState.FROZEN, /* funded */ true);

        assertThatThrownBy(() -> service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.RECOGNIZE_PAYMENT, null, "wrong state"),
                adminId))
                .isInstanceOf(DisputeActionInvalidForStateException.class)
                .hasMessageContaining("RECOGNIZE_PAYMENT")
                .hasMessageContaining("FROZEN");

        // State must be unchanged.
        assertThat(loadEscrow().getState()).isEqualTo(EscrowState.FROZEN);
    }

    @Test
    void resumeTransfer_fromDisputed_throwsDisputeActionInvalidForState() {
        seed(EscrowState.DISPUTED, /* funded */ true);

        assertThatThrownBy(() -> service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.RESUME_TRANSFER, null, "wrong state"),
                adminId))
                .isInstanceOf(DisputeActionInvalidForStateException.class)
                .hasMessageContaining("RESUME_TRANSFER")
                .hasMessageContaining("DISPUTED");

        assertThat(loadEscrow().getState()).isEqualTo(EscrowState.DISPUTED);
    }

    @Test
    void alsoCancelWithRecognizePayment_throwsAlsoCancelInvalidForAction() {
        seed(EscrowState.DISPUTED, /* funded */ true);

        assertThatThrownBy(() -> service.resolve(
                escrowId,
                new AdminDisputeResolveRequest(AdminDisputeAction.RECOGNIZE_PAYMENT, true, "bad combo"),
                adminId))
                .isInstanceOf(AlsoCancelInvalidForActionException.class)
                .hasMessageContaining("RECOGNIZE_PAYMENT");

        assertThat(loadEscrow().getState()).isEqualTo(EscrowState.DISPUTED);
    }

    @Test
    void unknownEscrowId_throwsDisputeNotFoundException() {
        long nonExistentId = Long.MAX_VALUE - 1;

        assertThatThrownBy(() -> service.resolve(
                nonExistentId,
                new AdminDisputeResolveRequest(AdminDisputeAction.RECOGNIZE_PAYMENT, null, "no such escrow"),
                1L))
                .isInstanceOf(DisputeNotFoundException.class)
                .hasMessageContaining(String.valueOf(nonExistentId));
    }

    // -------------------------------------------------------------------------
    // Seeding helpers
    // -------------------------------------------------------------------------

    private void seed(EscrowState startingState, boolean funded) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("dispute-resolve-seller-" + UUID.randomUUID() + "@test.com")
                    .passwordHash("hash")
                    .displayName("Dispute Resolve Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .cancelledWithBids(0)
                    .penaltyBalanceOwed(0L)
                    .build());
            User bidder = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("dispute-resolve-bidder-" + UUID.randomUUID() + "@test.com")
                    .passwordHash("hash")
                    .displayName("Dispute Resolve Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .cancelledWithBids(0)
                    .penaltyBalanceOwed(0L)
                    .build());
            User admin = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("dispute-resolve-admin-" + UUID.randomUUID() + "@test.com")
                    .passwordHash("hash")
                    .displayName("Dispute Resolve Admin")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .role(Role.ADMIN)
                    .build());

            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            long finalBid = 5_000L;
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Dispute Resolve Test Lot")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.ENDED)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .currentBid(finalBid)
                    .bidCount(2)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
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
                    .state(startingState)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid))
                    .payoutAmt(commissionCalculator.payout(finalBid))
                    .paymentDeadline(now.plusHours(48))
                    .consecutiveWorldApiFailures(0);

            if (funded) {
                escrowBuilder
                        .fundedAt(now.minusMinutes(30))
                        .transferDeadline(now.plusHours(72));
            }

            if (startingState == EscrowState.DISPUTED) {
                escrowBuilder
                        .disputedAt(now.minusMinutes(10))
                        .disputeReasonCategory("SELLER_NOT_RESPONSIVE")
                        .disputeDescription("Seller gone silent");
            }
            if (startingState == EscrowState.FROZEN) {
                escrowBuilder
                        .frozenAt(now.minusMinutes(10))
                        .freezeReason("UNKNOWN_OWNER");
            }

            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("Dispute Test Parcel")
                    .regionName("Test Region")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);

            Escrow escrow = escrowRepo.save(escrowBuilder.build());

            sellerId = seller.getId();
            bidderId = bidder.getId();
            adminId = admin.getId();
            auctionId = auction.getId();
            escrowId = escrow.getId();
        });
    }

    private Escrow loadEscrow() {
        return new TransactionTemplate(txManager).execute(s ->
                escrowRepo.findById(escrowId).orElseThrow());
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (escrowId != null) {
                    st.execute("DELETE FROM terminal_commands WHERE escrow_id = " + escrowId);
                    st.execute("DELETE FROM escrow_transactions WHERE escrow_id = " + escrowId);
                    st.execute("DELETE FROM escrows WHERE id = " + escrowId);
                }
                if (auctionId != null) {
                    st.execute("DELETE FROM admin_actions WHERE target_id = " + auctionId
                            + " AND target_type = 'AUCTION'");
                    st.execute("DELETE FROM admin_actions WHERE target_id = " + escrowId
                            + " AND target_type = 'DISPUTE'");
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
                        st.execute("DELETE FROM users WHERE id = " + uid);
                    }
                }
            }
        }
        sellerId = bidderId = adminId = auctionId = escrowId = null;
    }
}
