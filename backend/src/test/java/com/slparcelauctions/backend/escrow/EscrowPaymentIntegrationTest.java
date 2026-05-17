package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.payment.EscrowCallbackResponseReason;
import com.slparcelauctions.backend.escrow.payment.dto.EscrowPaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * End-to-end coverage for {@code EscrowService.acceptPayment} â€” the payment
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
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
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
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired TerminalRepository terminalRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededSellerId;
    private Long seededBidderId;
    private UUID seededWinnerSlUuid;
    private UUID seededAuctionPublicId;
    private UUID seededEscrowPublicId;
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
                notificationRepo.deleteAllByUserId(userId);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
            terminalRepo.findById(TERMINAL_ID).ifPresent(terminalRepo::delete);
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededSellerId = null;
        seededBidderId = null;
        seededWinnerSlUuid = null;
    }

    // Wallet-only escrow funding (spec 2026-05-16): the terminal is no
    // longer a valid funding channel for escrow. Escrows auto-fund from
    // the winner's bid reservation in createForEndedAuction. This
    // endpoint is preserved only as a defensive refund path so a mis-
    // rezzed legacy terminal can't get L$ stuck; every call returns an
    // ESCROW_EXPIRED REFUND after writing a FAILED ledger row.

    @Test
    void validPayment_transitionsToTransferPendingAndBroadcasts() {
        // Renamed semantics: even a "valid" payment now refunds with
        // ESCROW_EXPIRED and writes a FAILED ledger row. State unchanged.
        seedAuctionWithPendingEscrow();

        SlCallbackResponse resp = escrowService.acceptPayment(new EscrowPaymentRequest(
                seededAuctionId, seededWinnerSlUuid.toString(), seededFinalBid,
                "sl-txn-" + UUID.randomUUID(), TERMINAL_ID, SHARED_SECRET));

        assertThat(resp.status()).isEqualTo("REFUND");
        assertThat(resp.reason()).isEqualTo(EscrowCallbackResponseReason.ESCROW_EXPIRED);
        assertThat(resp.message()).contains("SLParcels wallet");

        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(persisted.getFundedAt()).isNull();
        assertThat(persisted.getTransferDeadline()).isNull();

        List<EscrowTransaction> ledger = escrowTxRepo
                .findByEscrowIdOrderByCreatedAtAsc(seededEscrowId);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getStatus()).isEqualTo(EscrowTransactionStatus.FAILED);
        assertThat(ledger.get(0).getErrorMessage()).isEqualTo("ESCROW_EXPIRED");

        assertThat(capturingEscrowPublisher.funded).isEmpty();
        assertThat(fraudFlagRepo.findByAuctionId(seededAuctionId)).isEmpty();
    }

    @Test
    void wrongPayer_createsFraudFlagAndRefundsWithoutTransition() {
        // Wrong-payer detection is gone too — every terminal payment
        // refunds with ESCROW_EXPIRED regardless of payer identity. No
        // fraud flag is created because the path no longer reaches the
        // payer-match check.
        seedAuctionWithPendingEscrow();

        UUID imposter = UUID.randomUUID();
        String txnKey = "sl-txn-" + UUID.randomUUID();
        SlCallbackResponse resp = escrowService.acceptPayment(new EscrowPaymentRequest(
                seededAuctionId, imposter.toString(), seededFinalBid,
                txnKey, TERMINAL_ID, SHARED_SECRET));

        assertThat(resp.status()).isEqualTo("REFUND");
        assertThat(resp.reason()).isEqualTo(EscrowCallbackResponseReason.ESCROW_EXPIRED);

        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(persisted.getFundedAt()).isNull();
        assertThat(persisted.getTransferDeadline()).isNull();

        List<EscrowTransaction> ledger = escrowTxRepo
                .findByEscrowIdOrderByCreatedAtAsc(seededEscrowId);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getStatus()).isEqualTo(EscrowTransactionStatus.FAILED);
        assertThat(ledger.get(0).getErrorMessage()).isEqualTo("ESCROW_EXPIRED");

        // No fraud flag in the wallet-only path.
        assertThat(fraudFlagRepo.findByAuctionId(seededAuctionId)).isEmpty();
        assertThat(capturingEscrowPublisher.funded).isEmpty();
    }

    @Test
    void expiredDeadline_refundsWithoutTransition() {
        // paymentDeadline is retired; this test now asserts the same
        // unconditional refund behavior that the other two assert.
        seedAuctionWithPendingEscrow();

        SlCallbackResponse resp = escrowService.acceptPayment(new EscrowPaymentRequest(
                seededAuctionId, seededWinnerSlUuid.toString(), seededFinalBid,
                "sl-txn-" + UUID.randomUUID(), TERMINAL_ID, SHARED_SECRET));

        assertThat(resp.status()).isEqualTo("REFUND");
        assertThat(resp.reason()).isEqualTo(EscrowCallbackResponseReason.ESCROW_EXPIRED);

        Escrow persisted = escrowRepo.findById(seededEscrowId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(persisted.getFundedAt()).isNull();
        assertThat(persisted.getTransferDeadline()).isNull();

        List<EscrowTransaction> ledger = escrowTxRepo
                .findByEscrowIdOrderByCreatedAtAsc(seededEscrowId);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getStatus()).isEqualTo(EscrowTransactionStatus.FAILED);
        assertThat(ledger.get(0).getErrorMessage()).isEqualTo("ESCROW_EXPIRED");

        assertThat(capturingEscrowPublisher.funded).isEmpty();
        assertThat(fraudFlagRepo.findByAuctionId(seededAuctionId)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Seeding â€” persists a full Auction + ESCROW_PENDING Escrow + Terminal
    // row plus a winner User with a known sl_avatar_uuid so acceptPayment
    // can cross-match the payerUuid.
    // -------------------------------------------------------------------------

    private void seedAuctionWithPendingEscrow() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-pay-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow Payment Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            UUID winnerUuid = UUID.randomUUID();
            User bidder = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-pay-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow Payment Bidder")
                    .slAvatarUuid(winnerUuid)
                    .verified(true)
                    .build());
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            long finalBid = 5_000L;
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Test listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.ENDED)

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
                    .startsAt(now.minusHours(2))
                    .endsAt(now.minusSeconds(1))
                    .originalEndsAt(now.minusSeconds(1))
                    .endedAt(now.minusSeconds(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());

            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("Payment Test Parcel")
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
            seededWinnerSlUuid = winnerUuid;
            seededAuctionPublicId = auction.getPublicId();
            seededEscrowPublicId = escrow.getPublicId();
            seededFinalBid = finalBid;
        });
    }
}
