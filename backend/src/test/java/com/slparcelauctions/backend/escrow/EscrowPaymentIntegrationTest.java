package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowFundedEnvelope;
import com.slparcelauctions.backend.escrow.payment.EscrowCallbackResponseReason;
import com.slparcelauctions.backend.escrow.payment.dto.EscrowPaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * End-to-end coverage for {@code EscrowService.acceptPayment} — the payment
 * receiving flow behind {@code POST /api/v1/sl/escrow/payment}. Seeds a full
 * auction + ESCROW_PENDING escrow + registered terminal + winner with a known
 * {@code sl_avatar_uuid}, then exercises the happy path plus two domain
 * REFUND branches (wrong payer, expired deadline). The
 * {@link EscrowBroadcastPublisher} is swapped for a
 * {@link CapturingEscrowBroadcastPublisher} so envelope fanout can be
 * asserted without a live STOMP broker.
 *
 * <p>Cleanup follows the FK-respecting order from the other escrow
 * integration tests, with the addition of the fraud-flag and terminal rows
 * this flow can produce.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false"
})
@Import(EscrowPaymentIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowPaymentIntegrationTest {

    private static final String TERMINAL_ID = "terminal-integ-" + UUID.randomUUID();
    private static final String HTTP_IN_URL = "https://sim-integ.agni.lindenlab.com:12043/cap/abc";
    private static final String REGION_NAME = "PaymentIntegRegion";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @Autowired EscrowService escrowService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired TerminalRepository terminalRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededParcelId;
    private Long seededSellerId;
    private Long seededBidderId;
    private UUID seededWinnerSlUuid;
    private long seededFinalBid;

    @BeforeEach
    void resetCapture() {
        capturingEscrowPublisher.reset();
    }

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            fraudFlagRepo.findByAuctionId(seededAuctionId).forEach(fraudFlagRepo::delete);
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
                refreshTokenRepo.findAllByUserId(userId)
                        .forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PLAYER)
                        .forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PARCEL)
                        .forEach(verificationCodeRepo::delete);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
            terminalRepo.findById(TERMINAL_ID).ifPresent(terminalRepo::delete);
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededParcelId = null;
        seededSellerId = null;
        seededBidderId = null;
        seededWinnerSlUuid = null;
    }

    @Test
    void validPayment_transitionsToTransferPendingAndBroadcasts() {
        // Payment deadline 48h ahead so now() is safely inside the window.
        seedAuctionWithPendingEscrow(OffsetDateTime.now().plusHours(48));

        OffsetDateTime before = OffsetDateTime.now();
        SlCallbackResponse resp = escrowService.acceptPayment(new EscrowPaymentRequest(
                seededAuctionId, seededWinnerSlUuid.toString(), seededFinalBid,
                "sl-txn-" + UUID.randomUUID(), TERMINAL_ID, SHARED_SECRET));
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.reason()).isNull();
        assertThat(resp.message()).isNull();

        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(persisted.getFundedAt()).isNotNull();
        assertThat(persisted.getFundedAt()).isBetween(before, after);
        // transferDeadline = fundedAt + 72h.
        assertThat(persisted.getTransferDeadline())
                .isCloseTo(persisted.getFundedAt().plusHours(72),
                        within(1, ChronoUnit.MICROS));

        // Exactly one COMPLETED ledger row for the payment.
        List<EscrowTransaction> ledger = escrowTxRepo
                .findByEscrowIdOrderByCreatedAtAsc(seededEscrowId);
        assertThat(ledger).hasSize(1);
        EscrowTransaction ledgerRow = ledger.get(0);
        assertThat(ledgerRow.getType()).isEqualTo(EscrowTransactionType.AUCTION_ESCROW_PAYMENT);
        assertThat(ledgerRow.getStatus()).isEqualTo(EscrowTransactionStatus.COMPLETED);
        assertThat(ledgerRow.getAmount()).isEqualTo(seededFinalBid);
        assertThat(ledgerRow.getTerminalId()).isEqualTo(TERMINAL_ID);
        assertThat(ledgerRow.getPayer()).isNotNull();
        assertThat(ledgerRow.getPayer().getId()).isEqualTo(seededBidderId);
        assertThat(ledgerRow.getCompletedAt()).isNotNull();

        // ESCROW_FUNDED envelope fired afterCommit.
        assertThat(capturingEscrowPublisher.funded).hasSize(1);
        EscrowFundedEnvelope env = capturingEscrowPublisher.funded.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_FUNDED");
        assertThat(env.auctionId()).isEqualTo(seededAuctionId);
        assertThat(env.escrowId()).isEqualTo(seededEscrowId);
        assertThat(env.state()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(env.transferDeadline())
                .isCloseTo(persisted.getTransferDeadline(), within(1, ChronoUnit.MICROS));

        assertThat(fraudFlagRepo.findByAuctionId(seededAuctionId)).isEmpty();
    }

    @Test
    void wrongPayer_createsFraudFlagAndRefundsWithoutTransition() {
        seedAuctionWithPendingEscrow(OffsetDateTime.now().plusHours(48));

        UUID imposter = UUID.randomUUID();
        String txnKey = "sl-txn-" + UUID.randomUUID();
        SlCallbackResponse resp = escrowService.acceptPayment(new EscrowPaymentRequest(
                seededAuctionId, imposter.toString(), seededFinalBid,
                txnKey, TERMINAL_ID, SHARED_SECRET));

        assertThat(resp.status()).isEqualTo("REFUND");
        assertThat(resp.reason()).isEqualTo(EscrowCallbackResponseReason.WRONG_PAYER);
        assertThat(resp.message()).contains("Payer does not match");

        // State unchanged — still ESCROW_PENDING, not funded.
        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(persisted.getFundedAt()).isNull();
        assertThat(persisted.getTransferDeadline()).isNull();

        // One fraud flag with the expected reason + evidence payload.
        List<FraudFlag> flags = fraudFlagRepo.findByAuctionId(seededAuctionId);
        assertThat(flags).hasSize(1);
        FraudFlag flag = flags.get(0);
        assertThat(flag.getReason()).isEqualTo(FraudFlagReason.ESCROW_WRONG_PAYER);
        assertThat(flag.isResolved()).isFalse();
        assertThat(flag.getDetectedAt()).isNotNull();
        assertThat(flag.getEvidenceJson())
                .containsEntry("expectedPayerUuid", seededWinnerSlUuid.toString())
                .containsEntry("actualPayerUuid", imposter.toString())
                .containsEntry("slTransactionKey", txnKey);

        // FAILED ledger row recorded for forensic replay.
        List<EscrowTransaction> ledger = escrowTxRepo
                .findByEscrowIdOrderByCreatedAtAsc(seededEscrowId);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getStatus()).isEqualTo(EscrowTransactionStatus.FAILED);
        assertThat(ledger.get(0).getErrorMessage()).isEqualTo("WRONG_PAYER");

        // No ESCROW_FUNDED envelope on a refund.
        assertThat(capturingEscrowPublisher.funded).isEmpty();
    }

    @Test
    void expiredDeadline_refundsWithoutTransition() {
        // paymentDeadline 1h in the past → ESCROW_EXPIRED branch.
        seedAuctionWithPendingEscrow(OffsetDateTime.now().minusHours(1));

        SlCallbackResponse resp = escrowService.acceptPayment(new EscrowPaymentRequest(
                seededAuctionId, seededWinnerSlUuid.toString(), seededFinalBid,
                "sl-txn-" + UUID.randomUUID(), TERMINAL_ID, SHARED_SECRET));

        assertThat(resp.status()).isEqualTo("REFUND");
        assertThat(resp.reason()).isEqualTo(EscrowCallbackResponseReason.ESCROW_EXPIRED);

        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(persisted.getFundedAt()).isNull();
        assertThat(persisted.getTransferDeadline()).isNull();

        // FAILED ledger row with the expired reason.
        List<EscrowTransaction> ledger = escrowTxRepo
                .findByEscrowIdOrderByCreatedAtAsc(seededEscrowId);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getStatus()).isEqualTo(EscrowTransactionStatus.FAILED);
        assertThat(ledger.get(0).getErrorMessage()).isEqualTo("ESCROW_EXPIRED");

        assertThat(capturingEscrowPublisher.funded).isEmpty();
        assertThat(fraudFlagRepo.findByAuctionId(seededAuctionId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Seeding — persists a full Auction + ESCROW_PENDING Escrow + Terminal
    // row plus a winner User with a known sl_avatar_uuid so acceptPayment
    // can cross-match the payerUuid.
    // -------------------------------------------------------------------------

    private void seedAuctionWithPendingEscrow(OffsetDateTime paymentDeadline) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder()
                    .email("escrow-pay-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow Payment Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            UUID winnerUuid = UUID.randomUUID();
            User bidder = userRepo.save(User.builder()
                    .email("escrow-pay-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow Payment Bidder")
                    .slAvatarUuid(winnerUuid)
                    .verified(true)
                    .build());
            Parcel parcel = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(UUID.randomUUID())
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .regionName("EscrowPaymentRegion")
                    .continentName("Sansara")
                    .areaSqm(1024)
                    .maturityRating("MODERATE")
                    .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            OffsetDateTime now = OffsetDateTime.now();
            long finalBid = 5_000L;
            Auction auction = auctionRepo.save(Auction.builder()
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
                    .startsAt(now.minusHours(2))
                    .endsAt(now.minusSeconds(1))
                    .originalEndsAt(now.minusSeconds(1))
                    .endedAt(now.minusSeconds(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());

            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.ESCROW_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid))
                    .payoutAmt(commissionCalculator.payout(finalBid))
                    .paymentDeadline(paymentDeadline)
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
            seededParcelId = parcel.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
            seededWinnerSlUuid = winnerUuid;
            seededFinalBid = finalBid;
        });
    }
}
