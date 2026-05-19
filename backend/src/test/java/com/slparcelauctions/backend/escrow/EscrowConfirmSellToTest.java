package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionStatusFlipper;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowSellToConfirmedEnvelope;
import com.slparcelauctions.backend.escrow.dispute.DisputeEvidenceUploadService;
import com.slparcelauctions.backend.escrow.review.EscrowManualReviewRepository;
import com.slparcelauctions.backend.escrow.scheduler.SellToBotTaskFactory;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.WalletService;

/**
 * Unit coverage for {@link EscrowService#confirmSellTo}. Mirrors the
 * Mockito-mock-harness style of the other escrow service unit tests and
 * drives the {@code @Transactional(MANDATORY)} method through a real
 * {@link TransactionTemplate} over a fake transaction manager (same
 * pattern as {@code BidServiceTest}) so the registered
 * {@code afterCommit} callback actually fires.
 *
 * <p>Asserts the hard-gate timestamp, the 72h deadline reset, step-3
 * priming, the failure-streak reset, the manual-verify flag clear, the
 * last-result clear, the buyer notification and the broadcast.
 */
@ExtendWith(MockitoExtension.class)
class EscrowConfirmSellToTest {

    private static final Long ESCROW_ID = 501L;
    private static final Long AUCTION_ID = 42L;
    private static final Long SELLER_ID = 7L;
    private static final Long WINNER_ID = 8L;

    @Mock EscrowRepository escrowRepo;
    @Mock AuctionStatusFlipper statusFlipper;
    @Mock EscrowTransactionRepository ledgerRepo;
    @Mock EscrowCommissionCalculator commission;
    @Mock EscrowBroadcastPublisher broadcastPublisher;
    @Mock UserRepository userRepo;
    @Mock FraudFlagRepository fraudFlagRepo;
    @Mock TerminalService terminalService;
    @Mock TerminalRepository terminalRepo;
    @Mock TerminalCommandService terminalCommandService;
    @Mock NotificationPublisher notificationPublisher;
    @Mock DisputeEvidenceUploadService evidenceUploadService;
    @Mock WalletService walletService;
    @Mock EscrowConfigProperties props;
    @Mock EscrowManualReviewRepository manualReviewRepo;
    @Mock SellToBotTaskFactory sellToBotTaskFactory;
    @Mock com.slparcelauctions.backend.realty.RealtyGroupRepository realtyGroupRepo;

    EscrowService service;
    Clock fixed;
    TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-05-17T12:00:00Z"), ZoneOffset.UTC);
        service = new EscrowService(
                escrowRepo, statusFlipper, ledgerRepo, commission, fixed,
                broadcastPublisher, userRepo, fraudFlagRepo, terminalService,
                terminalRepo, terminalCommandService, notificationPublisher,
                evidenceUploadService, walletService, props, manualReviewRepo,
                sellToBotTaskFactory, realtyGroupRepo);
        txTemplate = new TransactionTemplate(new FakeTxManager());
    }

    @Test
    void confirmSellTo_stampsTimestamp_resetsDeadline_primesPoll_notifiesBuyer() {
        Escrow escrow = buildPending();
        // Pre-existing degraded state that confirmSellTo must clear.
        escrow.setConsecutiveSellToBotFailures(3);
        escrow.setManualVerifyPending(true);
        escrow.setSellToLastResult("WRONG_BUYER");
        when(escrowRepo.save(any(Escrow.class))).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime now = OffsetDateTime.now(fixed);
        txTemplate.executeWithoutResult(s -> service.confirmSellTo(escrow, now));

        assertThat(escrow.getSellToConfirmedAt()).isEqualTo(now);
        assertThat(escrow.getTransferDeadline()).isEqualTo(now.plusHours(72));
        assertThat(escrow.getNextOwnerCheckAt()).isEqualTo(now);
        assertThat(escrow.getConsecutiveSellToBotFailures()).isZero();
        assertThat(escrow.getManualVerifyPending()).isFalse();
        assertThat(escrow.getSellToLastResult()).isNull();

        verify(notificationPublisher).escrowSellToSet(WINNER_ID, AUCTION_ID, ESCROW_ID, "Test Parcel");
        verify(broadcastPublisher).publishSellToConfirmed(any(EscrowSellToConfirmedEnvelope.class));
    }

    private Escrow buildPending() {
        User seller = User.builder().id(SELLER_ID).email("seller@example.com").username("seller")
                .verified(true).build();
        Auction auction = Auction.builder()
                .title("Test listing")
                .id(AUCTION_ID).seller(seller)
                .status(AuctionStatus.TRANSFER_PENDING)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(5000L).bidCount(2)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .tags(new HashSet<>())
                .finalBidAmount(5000L)
                .endOutcome(AuctionEndOutcome.SOLD)
                .winnerUserId(WINNER_ID)
                .build();
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .parcelName("Test Parcel")
                .regionName("EscrowRegion")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return Escrow.builder()
                .id(ESCROW_ID)
                .auction(auction)
                .state(EscrowState.TRANSFER_PENDING)
                .finalBidAmount(5000L)
                .commissionAmt(250L)
                .payoutAmt(4750L)
                .transferDeadline(OffsetDateTime.now(fixed).plusHours(20))
                .fundedAt(OffsetDateTime.now(fixed).minusHours(2))
                .consecutiveWorldApiFailures(0)
                .build();
    }

    /**
     * Minimal {@link PlatformTransactionManager} that binds a
     * {@code TransactionSynchronization} registry for the duration of a
     * {@link TransactionTemplate#execute} call and fires the
     * {@code afterCommit} callbacks on successful completion. Mirrors the
     * {@code BidServiceTest.FakeTxManager} pattern.
     */
    private static final class FakeTxManager implements PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                TransactionDefinition def) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            try {
                if (status.isRollbackOnly()) {
                    fireAfterCompletion(false);
                } else {
                    TransactionSynchronizationManager.getSynchronizations().forEach(sy -> {
                        try { sy.beforeCommit(false); } catch (Exception ignored) {}
                    });
                    TransactionSynchronizationManager.getSynchronizations().forEach(sy -> {
                        try { sy.beforeCompletion(); } catch (Exception ignored) {}
                    });
                    TransactionSynchronizationManager.getSynchronizations().forEach(sy -> {
                        try { sy.afterCommit(); } catch (Exception ignored) {}
                    });
                    fireAfterCompletion(true);
                }
            } finally {
                cleanup();
            }
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            try {
                fireAfterCompletion(false);
            } finally {
                cleanup();
            }
        }

        private void fireAfterCompletion(boolean committed) {
            int status = committed
                    ? org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED
                    : org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK;
            TransactionSynchronizationManager.getSynchronizations().forEach(sy -> {
                try { sy.afterCompletion(status); } catch (Exception ignored) {}
            });
        }

        private void cleanup() {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
