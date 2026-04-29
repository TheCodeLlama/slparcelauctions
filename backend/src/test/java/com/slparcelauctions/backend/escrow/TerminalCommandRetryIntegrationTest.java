package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowPayoutStalledEnvelope;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.escrow.command.TerminalHttpClient;
import com.slparcelauctions.backend.escrow.scheduler.TerminalCommandDispatcherJob;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * Integration coverage for the retry state machine in
 * {@code TerminalCommandDispatcherTask}. Seeds a QUEUED payout command +
 * live terminal, then stubs {@link TerminalHttpClient} so every POST fails.
 * Drives four dispatcher sweeps by invoking
 * {@link TerminalCommandDispatcherJob#dispatch()} explicitly (with
 * {@code nextAttemptAt} back-dated between sweeps so the backoff gate
 * passes) and asserts the final row state is FAILED with
 * {@code requires_manual_review=true} and an
 * {@code ESCROW_PAYOUT_STALLED} envelope captured.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        // Keep the dispatcher bean eligible for autowiring but disable the
        // @Scheduled tick so only the explicit sweeps we drive execute.
        "slpa.escrow.command-dispatcher-job.enabled=true",
        "slpa.escrow.command-dispatcher-job.fixed-delay=PT24H",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Import(TerminalCommandRetryIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TerminalCommandRetryIntegrationTest {

    private static final String TERMINAL_ID = "terminal-retry-" + UUID.randomUUID();
    private static final String HTTP_IN_URL = "https://sim-retry.agni.lindenlab.com:12043/cap/abc";
    private static final String REGION_NAME = "RetryIntegRegion";

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @MockitoBean TerminalHttpClient terminalHttp;

    @Autowired TerminalCommandDispatcherJob dispatcherJob;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired TerminalRepository terminalRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededCommandId;
    private Long seededParcelId;
    private Long seededSellerId;
    private Long seededBidderId;

    @BeforeEach
    void resetCapture() {
        capturingEscrowPublisher.reset();
    }

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            if (seededCommandId != null) {
                cmdRepo.findById(seededCommandId).ifPresent(cmdRepo::delete);
            }
            escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(seededEscrowId)
                    .forEach(escrowTxRepo::delete);
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrowRepo::delete);
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            if (seededParcelId != null) {
                parcelRepo.findById(seededParcelId).ifPresent(parcelRepo::delete);
            }
            for (Long userId : new Long[]{seededBidderId, seededSellerId}) {
                if (userId == null) continue;
                refreshTokenRepo.findAllByUserId(userId).forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PLAYER).forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PARCEL).forEach(verificationCodeRepo::delete);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
            terminalRepo.findById(TERMINAL_ID).ifPresent(terminalRepo::delete);
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededCommandId = null;
        seededParcelId = null;
        seededSellerId = null;
        seededBidderId = null;
    }

    @Test
    void fourConsecutiveTransportFailures_stallCommand_broadcastStalledEnvelope() {
        seedCommandTerminalAndEscrow();

        // Every POST fails — the dispatcher counts attempts and at the 4th
        // flip stamps requires_manual_review=true and publishes the stall.
        when(terminalHttp.post(anyString(), any()))
                .thenReturn(TerminalHttpClient.TerminalHttpResult.fail("terminal 5xx"));

        // Sweep 1: QUEUED → IN_FLIGHT attempt 1 → FAILED attempt 1 (backoff 1m).
        dispatcherJob.dispatch();
        TerminalCommand after1 = cmdRepo.findById(seededCommandId).orElseThrow();
        assertThat(after1.getStatus()).isEqualTo(TerminalCommandStatus.FAILED);
        assertThat(after1.getAttemptCount()).isEqualTo(1);
        assertThat(after1.getRequiresManualReview()).isFalse();

        // Back-date nextAttemptAt so the next sweep picks the row up.
        backdateNextAttempt();

        // Sweep 2: FAILED → IN_FLIGHT attempt 2 → FAILED attempt 2 (backoff 5m).
        dispatcherJob.dispatch();
        TerminalCommand after2 = cmdRepo.findById(seededCommandId).orElseThrow();
        assertThat(after2.getAttemptCount()).isEqualTo(2);
        assertThat(after2.getRequiresManualReview()).isFalse();

        backdateNextAttempt();

        // Sweep 3: FAILED → IN_FLIGHT attempt 3 → FAILED attempt 3 (backoff 15m).
        dispatcherJob.dispatch();
        TerminalCommand after3 = cmdRepo.findById(seededCommandId).orElseThrow();
        assertThat(after3.getAttemptCount()).isEqualTo(3);
        assertThat(after3.getRequiresManualReview()).isFalse();

        backdateNextAttempt();

        // Sweep 4: FAILED → IN_FLIGHT attempt 4 → STALL (requires_manual_review=true).
        dispatcherJob.dispatch();
        TerminalCommand stalled = cmdRepo.findById(seededCommandId).orElseThrow();
        assertThat(stalled.getStatus()).isEqualTo(TerminalCommandStatus.FAILED);
        assertThat(stalled.getAttemptCount()).isEqualTo(4);
        assertThat(stalled.getRequiresManualReview()).isTrue();
        assertThat(stalled.getLastError()).isEqualTo("terminal 5xx");

        // Stall envelope captured after the 4th sweep commit.
        assertThat(capturingEscrowPublisher.payoutStalled).hasSize(1);
        EscrowPayoutStalledEnvelope env = capturingEscrowPublisher.payoutStalled.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_PAYOUT_STALLED");
        assertThat(env.auctionId()).isEqualTo(seededAuctionId);
        assertThat(env.escrowId()).isEqualTo(seededEscrowId);
        assertThat(env.attemptCount()).isEqualTo(4);
        assertThat(env.lastError()).isEqualTo("terminal 5xx");

        // A subsequent sweep must NOT re-dispatch a stalled row.
        backdateNextAttempt();
        dispatcherJob.dispatch();
        List<Long> dispatchable = cmdRepo.findDispatchable(OffsetDateTime.now().plusHours(1));
        assertThat(dispatchable).doesNotContain(seededCommandId);
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private void seedCommandTerminalAndEscrow() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder()
                    .email("retry-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Retry Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder()
                    .email("retry-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Retry Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            Parcel parcel = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(UUID.randomUUID())
                    .ownerUuid(bidder.getSlAvatarUuid())
                    .ownerType("agent")
                    .regionName(REGION_NAME)
                    .continentName("Sansara")
                    .areaSqm(1024)
                    .maturityRating("MODERATE")
                    .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            OffsetDateTime now = OffsetDateTime.now();
            long finalBid = 5_000L;
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Test listing")
                    .parcel(parcel)
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
                    .startsAt(now.minusHours(3))
                    .endsAt(now.minusHours(1))
                    .originalEndsAt(now.minusHours(1))
                    .endedAt(now.minusHours(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());
            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid))
                    .payoutAmt(commissionCalculator.payout(finalBid))
                    .paymentDeadline(now.minusMinutes(30))
                    .transferDeadline(now.plusHours(70))
                    .fundedAt(now.minusMinutes(30))
                    .transferConfirmedAt(now.minusMinutes(5))
                    .consecutiveWorldApiFailures(0)
                    .build());

            terminalRepo.save(Terminal.builder()
                    .terminalId(TERMINAL_ID)
                    .httpInUrl(HTTP_IN_URL)
                    .regionName(REGION_NAME)
                    .active(true)
                    .lastSeenAt(now)
                    .build());

            TerminalCommand cmd = cmdRepo.save(TerminalCommand.builder()
                    .escrowId(escrow.getId())
                    .action(TerminalCommandAction.PAYOUT)
                    .purpose(TerminalCommandPurpose.AUCTION_ESCROW)
                    .recipientUuid(seller.getSlAvatarUuid().toString())
                    .amount(escrow.getPayoutAmt())
                    .status(TerminalCommandStatus.QUEUED)
                    .attemptCount(0)
                    .requiresManualReview(false)
                    .idempotencyKey("ESC-" + escrow.getId() + "-PAYOUT-1")
                    .nextAttemptAt(now.minusSeconds(1))
                    .build());

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededParcelId = parcel.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
            seededCommandId = cmd.getId();
        });
    }

    private void backdateNextAttempt() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            TerminalCommand cmd = cmdRepo.findById(seededCommandId).orElseThrow();
            cmd.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
            cmdRepo.save(cmd);
        });
    }
}
