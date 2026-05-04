package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
import com.slparcelauctions.backend.escrow.broadcast.EscrowCompletedEnvelope;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.escrow.command.TerminalHttpClient;
import com.slparcelauctions.backend.escrow.command.dto.PayoutResultRequest;
import com.slparcelauctions.backend.escrow.scheduler.EscrowOwnershipMonitorJob;
import com.slparcelauctions.backend.escrow.scheduler.TerminalCommandDispatcherJob;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

import reactor.core.publisher.Mono;

/**
 * End-to-end coverage from TRANSFER_PENDING onwards: ownership monitor
 * confirm → payout queued → dispatcher POSTs → callback success → state
 * COMPLETED + ledger (PAYOUT + COMMISSION) + envelope. The earlier stages
 * (auction-end Escrow creation + payment receipt) have their own
 * integration tests (EscrowCreateOnAuctionEndIntegrationTest,
 * EscrowPaymentIntegrationTest) to avoid re-seeding overhead.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        // Keep the escrow monitor + dispatcher beans autowirable but disable
        // the @Scheduled ticks so only our explicit sweep calls execute.
        "slpa.escrow.ownership-monitor-job.enabled=true",
        "slpa.escrow.ownership-monitor-job.fixed-delay=PT24H",
        "slpa.escrow.command-dispatcher-job.enabled=true",
        "slpa.escrow.command-dispatcher-job.fixed-delay=PT24H",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Import(EscrowEndToEndIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowEndToEndIntegrationTest {

    private static final String TERMINAL_ID = "terminal-e2e-" + UUID.randomUUID();
    private static final String HTTP_IN_URL = "https://sim-e2e.agni.lindenlab.com:12043/cap/abc";
    private static final String REGION_NAME = "E2ERegion";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean TerminalHttpClient terminalHttp;

    @Autowired EscrowOwnershipMonitorJob ownershipMonitorJob;
    @Autowired TerminalCommandDispatcherJob dispatcherJob;
    @Autowired TerminalCommandService terminalCommandService;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired TerminalRepository terminalRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededSellerId;
    private Long seededBidderId;
    private UUID seededParcelUuid;
    private UUID seededAuctionPublicId;
    private UUID seededEscrowPublicId;
    private UUID seededWinnerAvatar;
    private UUID seededSellerAvatar;
    private long seededFinalBid;
    private long seededPayout;
    private long seededCommission;

    @BeforeEach
    void resetCapture() {
        capturingEscrowPublisher.reset();
    }

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            cmdRepo.findAll().stream()
                    .filter(c -> c.getEscrowId() != null
                            && c.getEscrowId().equals(seededEscrowId))
                    .forEach(cmdRepo::delete);
            escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(seededEscrowId)
                    .forEach(escrowTxRepo::delete);
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
            terminalRepo.findById(TERMINAL_ID).ifPresent(terminalRepo::delete);
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededSellerId = null;
        seededBidderId = null;
        seededParcelUuid = null;
        seededWinnerAvatar = null;
        seededSellerAvatar = null;
    }

    @Test
    void fullHappyPath_transferConfirmDispatchCompleteCallback_rowsAndEnvelopesLineUp() {
        seedTransferPendingWithFundedEscrowAndTerminal();

        // World API reports the winner owns the parcel → monitor stamps
        // transferConfirmedAt and queues the PAYOUT command.
        when(worldApi.fetchParcelPage(seededParcelUuid))
                .thenReturn(
                Mono.just(new ParcelPageData(meta(seededWinnerAvatar, "agent"), java.util.UUID.randomUUID())));
        // Dispatcher's HTTP POST ACKs → command flips to IN_FLIGHT.
        when(terminalHttp.post(anyString(), any()))
                .thenReturn(TerminalHttpClient.TerminalHttpResult.ok());

        ownershipMonitorJob.sweep();

        // After the monitor sweep: escrow transferConfirmedAt stamped, one
        // PAYOUT command queued for this escrow.
        Escrow afterMonitor = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(afterMonitor.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(afterMonitor.getTransferConfirmedAt()).isNotNull();
        assertThat(capturingEscrowPublisher.transferConfirmed).hasSize(1);

        List<TerminalCommand> queued = findCommandsForEscrow(seededEscrowId);
        assertThat(queued).hasSize(1);
        TerminalCommand cmd = queued.get(0);
        assertThat(cmd.getAction()).isEqualTo(TerminalCommandAction.PAYOUT);
        assertThat(cmd.getPurpose()).isEqualTo(TerminalCommandPurpose.AUCTION_ESCROW);
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.QUEUED);
        assertThat(cmd.getAmount()).isEqualTo(seededPayout);

        // Dispatcher sweep: QUEUED → IN_FLIGHT.
        dispatcherJob.dispatch();
        TerminalCommand afterDispatch = cmdRepo.findById(cmd.getId()).orElseThrow();
        assertThat(afterDispatch.getStatus()).isEqualTo(TerminalCommandStatus.IN_FLIGHT);
        assertThat(afterDispatch.getAttemptCount()).isEqualTo(1);
        assertThat(afterDispatch.getTerminalId()).isEqualTo(TERMINAL_ID);
        assertThat(afterDispatch.getDispatchedAt()).isNotNull();

        // Simulate the terminal's payout-result callback: success.
        String slTxn = "sl-txn-" + UUID.randomUUID();
        terminalCommandService.applyCallback(new PayoutResultRequest(
                afterDispatch.getIdempotencyKey(), true, slTxn, null,
                TERMINAL_ID, SHARED_SECRET));

        // Command COMPLETED.
        TerminalCommand afterCallback = cmdRepo.findById(cmd.getId()).orElseThrow();
        assertThat(afterCallback.getStatus()).isEqualTo(TerminalCommandStatus.COMPLETED);
        assertThat(afterCallback.getCompletedAt()).isNotNull();

        // Escrow COMPLETED with completedAt stamped.
        Escrow afterEscrowCallback = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(afterEscrowCallback.getState()).isEqualTo(EscrowState.COMPLETED);
        assertThat(afterEscrowCallback.getCompletedAt()).isNotNull();

        // Ledger has two new rows (PAYOUT + COMMISSION), both COMPLETED.
        List<EscrowTransaction> ledger =
                escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(seededEscrowId);
        Optional<EscrowTransaction> payoutRow = ledger.stream()
                .filter(t -> t.getType() == EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
                .findFirst();
        assertThat(payoutRow).isPresent();
        assertThat(payoutRow.get().getStatus()).isEqualTo(EscrowTransactionStatus.COMPLETED);
        assertThat(payoutRow.get().getAmount()).isEqualTo(seededPayout);
        assertThat(payoutRow.get().getSlTransactionId()).isEqualTo(slTxn);
        assertThat(payoutRow.get().getPayee()).isNotNull();
        assertThat(payoutRow.get().getPayee().getId()).isEqualTo(seededSellerId);

        Optional<EscrowTransaction> commissionRow = ledger.stream()
                .filter(t -> t.getType() == EscrowTransactionType.AUCTION_ESCROW_COMMISSION)
                .findFirst();
        assertThat(commissionRow).isPresent();
        assertThat(commissionRow.get().getStatus()).isEqualTo(EscrowTransactionStatus.COMPLETED);
        assertThat(commissionRow.get().getAmount()).isEqualTo(seededCommission);

        // ESCROW_COMPLETED envelope fanned out.
        assertThat(capturingEscrowPublisher.completed).hasSize(1);
        EscrowCompletedEnvelope env = capturingEscrowPublisher.completed.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_COMPLETED");
        assertThat(env.auctionPublicId()).isEqualTo(seededAuctionPublicId);
        assertThat(env.escrowPublicId()).isEqualTo(seededEscrowPublicId);
        assertThat(env.state()).isEqualTo(EscrowState.COMPLETED);

        // Epic 08 sub-spec 1 §3.4 / §6.1: the seller's completedSales
        // counter must bump in the same transaction that flipped the escrow
        // to COMPLETED. Prior to sub-spec 1 this counter was declared but
        // never written; the reputation & completion-rate pipeline hangs
        // off this increment landing inside the payout-success handler.
        User seller = userRepo.findById(seededSellerId).orElseThrow();
        assertThat(seller.getCompletedSales()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers / seeding
    // -------------------------------------------------------------------------

    private List<TerminalCommand> findCommandsForEscrow(Long escrowId) {
        return cmdRepo.findAll().stream()
                .filter(c -> c.getEscrowId() != null && c.getEscrowId().equals(escrowId))
                .toList();
    }

    private ParcelMetadata meta(UUID owner, String ownerType) {
        return new ParcelMetadata(
                seededParcelUuid, owner, ownerType, null,
                "Test Parcel", REGION_NAME,
                1024, "desc", "http://example.com/snap.jpg", "MODERATE",
                128.0, 64.0, 22.0);
    }

    private void seedTransferPendingWithFundedEscrowAndTerminal() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            UUID sellerAvatar = UUID.randomUUID();
            UUID winnerAvatar = UUID.randomUUID();
            UUID parcelUuid = UUID.randomUUID();

            User seller = userRepo.save(User.builder()
                    .email("e2e-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("E2E Seller")
                    .slAvatarUuid(sellerAvatar)
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder()
                    .email("e2e-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("E2E Bidder")
                    .slAvatarUuid(winnerAvatar)
                    .verified(true)
                    .build());
            OffsetDateTime now = OffsetDateTime.now();
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
                    .startsAt(now.minusHours(3))
                    .endsAt(now.minusHours(1))
                    .originalEndsAt(now.minusHours(1))
                    .endedAt(now.minusHours(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(winnerAvatar)
                    .ownerType("agent")
                    .parcelName("E2E Test Parcel")
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
                    .paymentDeadline(now.minusMinutes(30))
                    .transferDeadline(now.plusHours(71))
                    .fundedAt(now.minusMinutes(30))
                    .consecutiveWorldApiFailures(0)
                    .build());

            terminalRepo.save(Terminal.builder()
                    .terminalId(TERMINAL_ID)
                    .httpInUrl(HTTP_IN_URL)
                    .regionName(REGION_NAME)
                    .active(true)
                    .lastSeenAt(now)
                    .build());

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
            seededParcelUuid = parcelUuid;
            seededWinnerAvatar = winnerAvatar;
            seededAuctionPublicId = auction.getPublicId();
            seededEscrowPublicId = escrow.getPublicId();
            seededSellerAvatar = sellerAvatar;
            seededFinalBid = finalBid;
            seededPayout = escrow.getPayoutAmt();
            seededCommission = escrow.getCommissionAmt();
        });
    }
}
