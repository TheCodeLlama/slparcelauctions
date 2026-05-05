package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.common.ClockOverrideConfig;
import com.slparcelauctions.backend.common.ClockOverrideConfig.MutableFixedClock;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowExpiredEnvelope;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.escrow.scheduler.EscrowTimeoutJob;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * End-to-end coverage of {@link EscrowTimeoutJob}. Four scenarios:
 *
 * <ol>
 *   <li><b>ESCROW_PENDING past 48h</b> → state=EXPIRED, {@code expiredAt}
 *       stamped, no refund {@code TerminalCommand} queued (no L$ was held),
 *       {@code ESCROW_EXPIRED} envelope with
 *       {@code reason=PAYMENT_TIMEOUT} captured.</li>
 *   <li><b>TRANSFER_PENDING past 72h</b> (no payout in flight) →
 *       state=EXPIRED, {@code expiredAt} stamped, one REFUND
 *       {@code TerminalCommand} for the escrow exists,
 *       {@code ESCROW_EXPIRED} envelope with
 *       {@code reason=TRANSFER_TIMEOUT} captured.</li>
 *   <li><b>CRITICAL payout-in-flight guard</b> — TRANSFER_PENDING past
 *       72h with an IN_FLIGHT PAYOUT command: escrow must remain
 *       TRANSFER_PENDING, no new refund queued, no envelope captured.
 *       Exercises the {@code NOT EXISTS} subquery in
 *       {@link EscrowRepository#findExpiredTransferPendingIds}.</li>
 *   <li><b>Deadline not yet past</b> → no-op.</li>
 * </ol>
 *
 * <p>Time is controlled by {@link ClockOverrideConfig.MutableFixedClock} so
 * the test can advance past the deadlines without rebuilding the context.
 * The {@code @Scheduled} cron is disabled via
 * {@code slpa.escrow.timeout-job.enabled=false} and we invoke
 * {@link EscrowTimeoutJob#sweep()} explicitly so the test owns the timing.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        // Keep the timeout bean eligible for autowiring but disable the
        // @Scheduled tick so only the explicit sweeps we drive execute.
        "slpa.escrow.timeout-job.enabled=true",
        "slpa.escrow.timeout-job.fixed-delay=PT24H",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Import({ClockOverrideConfig.class, EscrowTimeoutIntegrationTest.CapturingConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowTimeoutIntegrationTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @Autowired EscrowTimeoutJob job;
    @Autowired EscrowRepository escrowRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired UserRepository userRepo;
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;
    @Autowired MutableFixedClock testClock;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededSellerId;
    private Long seededBidderId;
    private java.util.UUID seededAuctionPublicId;
    private java.util.UUID seededEscrowPublicId;

    @BeforeEach
    void resetCapture() {
        capturingEscrowPublisher.reset();
        // Reset the clock to the default so each test starts from a known
        // instant. Deadlines are seeded relative to this base instant.
        testClock.set(ClockOverrideConfig.DEFAULT_INSTANT);
    }

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            if (seededEscrowId != null) {
                cmdRepo.findAll().stream()
                        .filter(c -> seededEscrowId.equals(c.getEscrowId()))
                        .forEach(cmdRepo::delete);
                escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(seededEscrowId)
                        .forEach(escrowTxRepo::delete);
            }
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrowRepo::delete);
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            for (Long userId : new Long[]{seededBidderId, seededSellerId}) {
                if (userId == null) continue;
                refreshTokenRepo.findAllByUserId(userId).forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PLAYER).forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PARCEL).forEach(verificationCodeRepo::delete);
                notificationRepo.deleteAllByUserId(userId);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededSellerId = null;
        seededBidderId = null;
    }

    @Test
    void escrowPendingPastPaymentDeadline_expiresWithoutRefund_broadcastsPaymentTimeout() {
        OffsetDateTime base = OffsetDateTime.now(testClock);
        // paymentDeadline 1h ahead of base now; advance clock 2h to push past.
        seedPendingEscrow(base.plusHours(1));
        testClock.advance(Duration.ofHours(2));

        job.sweep();

        Escrow refreshed = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(refreshed.getState()).isEqualTo(EscrowState.EXPIRED);
        assertThat(refreshed.getExpiredAt()).isNotNull();
        assertThat(refreshed.getExpiredAt())
                .isEqualTo(OffsetDateTime.now(testClock));

        // No refund queued — the winner never paid, no L$ is held.
        List<TerminalCommand> commands = cmdRepo.findAll().stream()
                .filter(c -> seededEscrowId.equals(c.getEscrowId()))
                .toList();
        assertThat(commands).isEmpty();

        assertThat(capturingEscrowPublisher.expired).hasSize(1);
        EscrowExpiredEnvelope env = capturingEscrowPublisher.expired.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_EXPIRED");
        assertThat(env.auctionPublicId()).isEqualTo(seededAuctionPublicId);
        assertThat(env.escrowPublicId()).isEqualTo(seededEscrowPublicId);
        assertThat(env.state()).isEqualTo(EscrowState.EXPIRED);
        assertThat(env.reason()).isEqualTo("PAYMENT_TIMEOUT");

        // Epic 08 sub-spec 1 §6.1: a payment-timeout is buyer-fault, not
        // seller-fault. The seller's escrowExpiredUnfulfilled counter must
        // stay at zero — if it incremented here, a seller's completion rate
        // would drop every time a winner failed to pay.
        User seller = userRepo.findById(seededSellerId).orElseThrow();
        assertThat(seller.getEscrowExpiredUnfulfilled()).isEqualTo(0);
    }

    @Test
    void transferPendingPastTransferDeadline_expiresAndQueuesRefund_broadcastsTransferTimeout() {
        OffsetDateTime base = OffsetDateTime.now(testClock);
        // transferDeadline 1h ahead of base now; advance clock 2h to push past.
        seedTransferPendingEscrow(base.plusHours(1), base.minusHours(2));
        testClock.advance(Duration.ofHours(2));

        job.sweep();

        Escrow refreshed = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(refreshed.getState()).isEqualTo(EscrowState.EXPIRED);
        assertThat(refreshed.getExpiredAt()).isNotNull();
        assertThat(refreshed.getExpiredAt())
                .isEqualTo(OffsetDateTime.now(testClock));

        // Exactly one REFUND TerminalCommand for AUCTION_ESCROW on this escrow.
        List<TerminalCommand> commands = cmdRepo.findAll().stream()
                .filter(c -> seededEscrowId.equals(c.getEscrowId()))
                .toList();
        assertThat(commands).hasSize(1);
        TerminalCommand refund = commands.get(0);
        assertThat(refund.getAction()).isEqualTo(TerminalCommandAction.REFUND);
        assertThat(refund.getPurpose()).isEqualTo(TerminalCommandPurpose.AUCTION_ESCROW);
        assertThat(refund.getStatus()).isEqualTo(TerminalCommandStatus.QUEUED);
        assertThat(refund.getAmount()).isEqualTo(refreshed.getFinalBidAmount());

        assertThat(capturingEscrowPublisher.expired).hasSize(1);
        EscrowExpiredEnvelope env = capturingEscrowPublisher.expired.get(0);
        assertThat(env.reason()).isEqualTo("TRANSFER_TIMEOUT");
        assertThat(env.state()).isEqualTo(EscrowState.EXPIRED);

        // Epic 08 sub-spec 1 §3.4 / §6.1: a transfer-timeout is seller-fault
        // (the seller never handed the parcel over inside the 72h window), so
        // the seller's escrowExpiredUnfulfilled counter must bump from 0 to 1.
        // This counter drops the seller's completion rate via the 3-arg
        // SellerCompletionRateMapper denominator.
        User seller = userRepo.findById(seededSellerId).orElseThrow();
        assertThat(seller.getEscrowExpiredUnfulfilled()).isEqualTo(1);
    }

    @Test
    void transferPendingPastDeadline_withPayoutInFlight_doesNotExpire_doesNotBroadcast() {
        // CRITICAL INVARIANT: the repo query's NOT EXISTS subquery must
        // exclude escrows that already have a PAYOUT command mid-flight.
        // Once ownership is confirmed the 72h deadline is satisfied from
        // the seller's side — expiring here would double-spend by refunding
        // the winner while the payout is still attempting to deliver.
        OffsetDateTime base = OffsetDateTime.now(testClock);
        seedTransferPendingEscrow(base.plusHours(1), base.minusHours(2));
        seedPayoutCommand(seededEscrowId, TerminalCommandStatus.IN_FLIGHT, base);
        testClock.advance(Duration.ofHours(2));

        job.sweep();

        Escrow refreshed = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(refreshed.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(refreshed.getExpiredAt()).isNull();

        // Only the original PAYOUT command — no REFUND queued.
        List<TerminalCommand> commands = cmdRepo.findAll().stream()
                .filter(c -> seededEscrowId.equals(c.getEscrowId()))
                .toList();
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).getAction()).isEqualTo(TerminalCommandAction.PAYOUT);

        assertThat(capturingEscrowPublisher.expired).isEmpty();
    }

    @Test
    void escrowPendingWithFutureDeadline_noop() {
        OffsetDateTime base = OffsetDateTime.now(testClock);
        // paymentDeadline 100h ahead of base — well outside the sweep window.
        seedPendingEscrow(base.plusHours(100));

        job.sweep();

        Escrow refreshed = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(refreshed.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(refreshed.getExpiredAt()).isNull();

        assertThat(capturingEscrowPublisher.expired).isEmpty();
        List<TerminalCommand> commands = cmdRepo.findAll().stream()
                .filter(c -> seededEscrowId.equals(c.getEscrowId()))
                .toList();
        assertThat(commands).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private void seedPendingEscrow(OffsetDateTime paymentDeadline) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = saveSeller();
            User bidder = saveBidder();
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime base = OffsetDateTime.now(testClock);
            long finalBid = 5_000L;
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Test listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.ENDED)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .reservePrice(1_000L)
                    .currentBid(finalBid)
                    .bidCount(2)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(base.minusHours(3))
                    .endsAt(base.minusHours(1))
                    .originalEndsAt(base.minusHours(1))
                    .endedAt(base.minusHours(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("Timeout Test Parcel")
                    .regionName("Coniston")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);
            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.ESCROW_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid))
                    .payoutAmt(commissionCalculator.payout(finalBid))
                    .paymentDeadline(paymentDeadline)
                    .consecutiveWorldApiFailures(0)
                    .build());

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
            seededAuctionPublicId = auction.getPublicId();
            seededEscrowPublicId = escrow.getPublicId();
        });
    }

    private void seedTransferPendingEscrow(OffsetDateTime transferDeadline,
                                           OffsetDateTime fundedAt) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = saveSeller();
            User bidder = saveBidder();
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime base = OffsetDateTime.now(testClock);
            long finalBid = 5_000L;
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Test listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.ENDED)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .reservePrice(1_000L)
                    .currentBid(finalBid)
                    .bidCount(2)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(base.minusHours(75))
                    .endsAt(base.minusHours(73))
                    .originalEndsAt(base.minusHours(73))
                    .endedAt(base.minusHours(73))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("Timeout Test Parcel")
                    .regionName("Coniston")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);
            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid))
                    .payoutAmt(commissionCalculator.payout(finalBid))
                    .paymentDeadline(fundedAt)
                    .transferDeadline(transferDeadline)
                    .fundedAt(fundedAt)
                    .consecutiveWorldApiFailures(0)
                    .build());

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
            seededAuctionPublicId = auction.getPublicId();
            seededEscrowPublicId = escrow.getPublicId();
        });
    }

    private void seedPayoutCommand(Long escrowId,
                                   TerminalCommandStatus status,
                                   OffsetDateTime when) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Escrow escrow = escrowRepo.findById(escrowId).orElseThrow();
            cmdRepo.save(TerminalCommand.builder()
                    .escrowId(escrowId)
                    .action(TerminalCommandAction.PAYOUT)
                    .purpose(TerminalCommandPurpose.AUCTION_ESCROW)
                    .recipientUuid(escrow.getAuction().getSeller().getSlAvatarUuid().toString())
                    .amount(escrow.getPayoutAmt())
                    .status(status)
                    .attemptCount(1)
                    .requiresManualReview(false)
                    .idempotencyKey("ESC-" + escrowId + "-PAYOUT-1")
                    .nextAttemptAt(when)
                    .dispatchedAt(status == TerminalCommandStatus.IN_FLIGHT ? when : null)
                    .build());
        });
    }

    private User saveSeller() {
        return userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("timeout-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Timeout Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
    }

    private User saveBidder() {
        return userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("timeout-bidder-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Timeout Bidder")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
    }

}
