package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowCompletedEnvelope;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalHttpClient;
import com.slparcelauctions.backend.escrow.scheduler.EscrowOwnershipMonitorJob;
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
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

import reactor.core.publisher.Mono;

/**
 * End-to-end coverage from TRANSFER_PENDING onwards. Post wallet-first
 * cutover the happy path runs entirely inline: ownership monitor confirms
 * the parcel transfer -> queuePayout credits the seller's SLParcels wallet
 * via WalletService.creditAuctionPayout and flips escrow + auction to
 * COMPLETED inside the same transaction. No TerminalCommand is enqueued
 * and no terminal-callback round-trip happens.
 *
 * <p>The earlier stages (auction-end Escrow creation + payment receipt)
 * have their own integration tests (EscrowCreateOnAuctionEndIntegrationTest,
 * EscrowPaymentIntegrationTest) to avoid re-seeding overhead. The historical
 * PAYOUT TerminalCommand callback machinery still has coverage in
 * TerminalCommandDispatcherTaskTest / TerminalCommandRetryIntegrationTest
 * for any in-flight rows that survive deploy.
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
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Import(EscrowEndToEndIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowEndToEndIntegrationTest {

    private static final String TERMINAL_ID = "terminal-e2e-" + UUID.randomUUID();
    private static final String HTTP_IN_URL = "https://sim-e2e.agni.lindenlab.com:12043/cap/abc";
    private static final String REGION_NAME = "E2ERegion";

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
    @Autowired UserLedgerRepository userLedgerRepo;
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
            // creditAuctionPayout writes user_ledger rows keyed by refType=ESCROW,
            // refId=escrowId. Drop those before deleting the parent escrow / users.
            final Long escrowForCleanup = seededEscrowId;
            if (escrowForCleanup != null) {
                userLedgerRepo.findAll().stream()
                        .filter(e -> "ESCROW".equals(e.getRefType())
                                && escrowForCleanup.equals(e.getRefId()))
                        .forEach(userLedgerRepo::delete);
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
    void fullHappyPath_transferConfirmCompletesInlineAndCreditsSellerWallet() {
        seedTransferPendingWithFundedEscrowAndTerminal();

        long sellerBalanceBefore = userRepo.findById(seededSellerId).orElseThrow()
                .getBalanceLindens();

        // World API reports the winner owns the parcel -> monitor stamps
        // transferConfirmedAt and triggers queuePayout, which now runs the
        // success path inline (credits the seller's wallet, flips escrow ->
        // COMPLETED, writes the ledger rows, notifies the seller). The
        // terminalHttp client must not be invoked along the happy path post
        // wallet-first cutover -- we don't stub it.
        when(worldApi.fetchParcelPage(seededParcelUuid))
                .thenReturn(
                Mono.just(new ParcelPageData(meta(seededWinnerAvatar, "agent"), java.util.UUID.randomUUID())));

        ownershipMonitorJob.sweep();

        // No TerminalCommand of action=PAYOUT was queued for the new sale --
        // queuePayout runs inline now.
        List<TerminalCommand> payoutCmds = findCommandsForEscrow(seededEscrowId).stream()
                .filter(c -> c.getAction() == TerminalCommandAction.PAYOUT)
                .toList();
        assertThat(payoutCmds).isEmpty();

        // Escrow flipped straight to COMPLETED with completedAt stamped.
        Escrow afterMonitor = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(afterMonitor.getState()).isEqualTo(EscrowState.COMPLETED);
        assertThat(afterMonitor.getTransferConfirmedAt()).isNotNull();
        assertThat(afterMonitor.getCompletedAt()).isNotNull();
        assertThat(capturingEscrowPublisher.transferConfirmed).hasSize(1);

        // Seller's SLParcels wallet balance increased by the payout amount.
        long sellerBalanceAfter = userRepo.findById(seededSellerId).orElseThrow()
                .getBalanceLindens();
        assertThat(sellerBalanceAfter).isEqualTo(sellerBalanceBefore + seededPayout);

        // user_ledger has a single AUCTION_PAYOUT_CREDIT row keyed by the escrow
        // with the AUCPAYOUT-{escrowId} idempotency key.
        List<UserLedgerEntry> creditRows = userLedgerRepo.findAll().stream()
                .filter(e -> seededSellerId.equals(e.getUserId()))
                .filter(e -> e.getEntryType() == UserLedgerEntryType.AUCTION_PAYOUT_CREDIT)
                .toList();
        assertThat(creditRows).hasSize(1);
        UserLedgerEntry credit = creditRows.get(0);
        assertThat(credit.getAmount()).isEqualTo(seededPayout);
        assertThat(credit.getRefType()).isEqualTo("ESCROW");
        assertThat(credit.getRefId()).isEqualTo(seededEscrowId);
        assertThat(credit.getIdempotencyKey()).isEqualTo("AUCPAYOUT-" + seededEscrowId);

        // escrow_transactions has PAYOUT + COMMISSION rows, both COMPLETED, no slTxn
        // (no terminal round-trip happened).
        List<EscrowTransaction> ledger =
                escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(seededEscrowId);
        Optional<EscrowTransaction> payoutRow = ledger.stream()
                .filter(t -> t.getType() == EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
                .findFirst();
        assertThat(payoutRow).isPresent();
        assertThat(payoutRow.get().getStatus()).isEqualTo(EscrowTransactionStatus.COMPLETED);
        assertThat(payoutRow.get().getAmount()).isEqualTo(seededPayout);
        assertThat(payoutRow.get().getSlTransactionId()).isNull();
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

        // Epic 08 sub-spec 1 §3.4 / §6.1: the seller's completedSales counter
        // must bump in the same transaction that flipped the escrow to
        // COMPLETED. The reputation + completion-rate pipeline hangs off this
        // increment landing inside the payout-success path.
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

            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("e2e-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("E2E Seller")
                    .slAvatarUuid(sellerAvatar)
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
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
                    .status(AuctionStatus.TRANSFER_PENDING)

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

            // Buy-Parcel sub-phase: sellToConfirmedAt is set so the step-3
            // owner-poll hard gate (findBuyPhaseEscrowIdsDue, spec §6) returns
            // this escrow and the happy-path sweep can confirm the transfer.
            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid, auction.getCommissionRate()))
                    .payoutAmt(commissionCalculator.payout(finalBid, auction.getCommissionRate()))
                    .transferDeadline(now.plusHours(71))
                    .fundedAt(now.minusMinutes(30))
                    .sellToConfirmedAt(now.minusMinutes(15))
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
