package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ListingFeeRefund;
import com.slparcelauctions.backend.auction.ListingFeeRefundRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.RefundStatus;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.escrow.command.dto.PayoutResultRequest;
import com.slparcelauctions.backend.escrow.payment.EscrowCallbackResponseReason;
import com.slparcelauctions.backend.escrow.payment.ListingFeePaymentService;
import com.slparcelauctions.backend.escrow.payment.dto.ListingFeePaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.escrow.scheduler.ListingFeeRefundProcessorJob;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.testsupport.TestRegions;

/**
 * End-to-end coverage for the listing-fee flow shipped by Epic 05 sub-spec 1
 * Task 9. Exercises both sides of the flow:
 *
 * <ol>
 *   <li>Inbound listing-fee payment: seed a DRAFT auction with a known
 *       seller avatar UUID and a registered terminal, POST via
 *       {@link ListingFeePaymentService#acceptPayment}, assert the
 *       transition to DRAFT_PAID + listingFee fields + LISTING_FEE_PAYMENT
 *       ledger row.</li>
 *   <li>Outbound listing-fee refund: seed a PENDING
 *       {@link ListingFeeRefund} row (as if {@code CancellationService}
 *       had created one on a cancelled-paid auction), run
 *       {@link ListingFeeRefundProcessorJob#drainPending()}, assert the
 *       {@code terminalCommandId} is stamped + a TerminalCommand with
 *       purpose=LISTING_FEE_REFUND exists + a success callback flips the
 *       refund to PROCESSED (Task 7 callback logic).</li>
 * </ol>
 *
 * <p>Scheduled jobs are disabled or slowed to PT24H so only the explicit
 * drives we make execute — the TerminalCommand the refund produces
 * intentionally stays QUEUED (no live dispatcher) so we can run the
 * success-callback branch manually without a real terminal.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        // Keep the dispatcher bean autowirable but disable the tick so the
        // TerminalCommand we observe stays QUEUED.
        "slpa.escrow.command-dispatcher-job.enabled=true",
        "slpa.escrow.command-dispatcher-job.fixed-delay=PT24H",
        // Keep the refund processor bean autowirable + slow its tick so
        // only the explicit drainPending() call we make executes.
        "slpa.escrow.listing-fee-refund-job.enabled=true",
        "slpa.escrow.listing-fee-refund-job.fixed-delay=PT24H",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ListingFeeFlowIntegrationTest {

    private static final String TERMINAL_ID = "terminal-lf-" + UUID.randomUUID();
    private static final String HTTP_IN_URL = "https://sim-lf.agni.lindenlab.com:12043/cap/abc";
    private static final String REGION_NAME = "ListingFeeRegion";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @Autowired ListingFeePaymentService listingFeePaymentService;
    @Autowired ListingFeeRefundProcessorJob listingFeeRefundProcessorJob;
    @Autowired TerminalCommandService terminalCommandService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired EscrowTransactionRepository ledgerRepo;
    @Autowired ListingFeeRefundRepository listingFeeRefundRepo;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired TerminalRepository terminalRepo;
    @Autowired PlatformTransactionManager txManager;

    private Long seededAuctionId;
    private Long seededParcelId;
    private Long seededSellerId;
    private Long seededRefundId;
    private Long seededCommandId;
    private UUID seededSellerAvatarUuid;

    @AfterEach
    void cleanUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            if (seededRefundId != null) {
                listingFeeRefundRepo.findById(seededRefundId).ifPresent(listingFeeRefundRepo::delete);
            }
            if (seededCommandId != null) {
                cmdRepo.findById(seededCommandId).ifPresent(cmdRepo::delete);
            }
            if (seededAuctionId != null) {
                ledgerRepo.findAll().stream()
                        .filter(r -> r.getAuction() != null
                                && r.getAuction().getId().equals(seededAuctionId))
                        .forEach(ledgerRepo::delete);
                bidRepo.deleteAllByAuctionId(seededAuctionId);
                proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
                auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            }
            if (seededParcelId != null) {
                parcelRepo.findById(seededParcelId).ifPresent(parcelRepo::delete);
            }
            if (seededSellerId != null) {
                refreshTokenRepo.findAllByUserId(seededSellerId).forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(seededSellerId,
                        VerificationCodeType.PLAYER).forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(seededSellerId,
                        VerificationCodeType.PARCEL).forEach(verificationCodeRepo::delete);
                userRepo.findById(seededSellerId).ifPresent(userRepo::delete);
            }
            terminalRepo.findById(TERMINAL_ID).ifPresent(terminalRepo::delete);
        });
        seededAuctionId = null;
        seededParcelId = null;
        seededSellerId = null;
        seededRefundId = null;
        seededCommandId = null;
        seededSellerAvatarUuid = null;
    }

    @Test
    void validListingFeePayment_transitionsDraftToDraftPaidAndWritesLedger() {
        seedDraftAuctionWithTerminal();

        String txnKey = "sl-txn-lf-" + UUID.randomUUID();
        SlCallbackResponse resp = listingFeePaymentService.acceptPayment(
                new ListingFeePaymentRequest(
                        seededAuctionId,
                        seededSellerAvatarUuid.toString(),
                        100L,
                        txnKey,
                        TERMINAL_ID,
                        SHARED_SECRET));

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.reason()).isNull();

        Auction persisted = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AuctionStatus.DRAFT_PAID);
        assertThat(persisted.getListingFeePaid()).isTrue();
        assertThat(persisted.getListingFeePaidAt()).isNotNull();
        assertThat(persisted.getListingFeeTxn()).isEqualTo(txnKey);

        // One COMPLETED LISTING_FEE_PAYMENT ledger row bound to the auction.
        List<EscrowTransaction> ledger = ledgerRepo.findAll().stream()
                .filter(r -> r.getAuction() != null
                        && r.getAuction().getId().equals(seededAuctionId))
                .toList();
        assertThat(ledger).hasSize(1);
        EscrowTransaction row = ledger.get(0);
        assertThat(row.getType()).isEqualTo(EscrowTransactionType.LISTING_FEE_PAYMENT);
        assertThat(row.getStatus()).isEqualTo(EscrowTransactionStatus.COMPLETED);
        assertThat(row.getAmount()).isEqualTo(100L);
        assertThat(row.getTerminalId()).isEqualTo(TERMINAL_ID);
        assertThat(row.getSlTransactionId()).isEqualTo(txnKey);
        assertThat(row.getPayer()).isNotNull();
        assertThat(row.getPayer().getId()).isEqualTo(seededSellerId);
    }

    @Test
    void wrongAmount_refundsAndLeavesAuctionInDraft() {
        seedDraftAuctionWithTerminal();

        SlCallbackResponse resp = listingFeePaymentService.acceptPayment(
                new ListingFeePaymentRequest(
                        seededAuctionId,
                        seededSellerAvatarUuid.toString(),
                        50L,  // auction wants 100
                        "sl-txn-lf-" + UUID.randomUUID(),
                        TERMINAL_ID,
                        SHARED_SECRET));

        assertThat(resp.status()).isEqualTo("REFUND");
        assertThat(resp.reason()).isEqualTo(EscrowCallbackResponseReason.WRONG_AMOUNT);

        Auction persisted = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AuctionStatus.DRAFT);
        assertThat(persisted.getListingFeePaid()).isFalse();
        assertThat(persisted.getListingFeeTxn()).isNull();
    }

    @Test
    void wrongPayer_refundsAndLeavesAuctionInDraft() {
        seedDraftAuctionWithTerminal();

        UUID imposter = UUID.randomUUID();
        SlCallbackResponse resp = listingFeePaymentService.acceptPayment(
                new ListingFeePaymentRequest(
                        seededAuctionId,
                        imposter.toString(),
                        100L,
                        "sl-txn-lf-" + UUID.randomUUID(),
                        TERMINAL_ID,
                        SHARED_SECRET));

        assertThat(resp.status()).isEqualTo("REFUND");
        assertThat(resp.reason()).isEqualTo(EscrowCallbackResponseReason.WRONG_PAYER);

        Auction persisted = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AuctionStatus.DRAFT);
        assertThat(persisted.getListingFeePaid()).isFalse();
    }

    @Test
    void pendingRefund_isQueuedByProcessorAndFlipsToProcessedOnCallback() {
        seedDraftAuctionWithTerminal();
        seedPendingRefund();

        // Before sweep: refund PENDING, no command stamped.
        ListingFeeRefund before = listingFeeRefundRepo.findById(seededRefundId).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(before.getTerminalCommandId()).isNull();
        assertThat(before.getLastQueuedAt()).isNull();

        // Run the processor explicitly.
        listingFeeRefundProcessorJob.drainPending();

        // After sweep: terminalCommandId + lastQueuedAt stamped, a
        // LISTING_FEE_REFUND TerminalCommand exists with recipient=seller.
        ListingFeeRefund afterSweep = listingFeeRefundRepo.findById(seededRefundId).orElseThrow();
        assertThat(afterSweep.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(afterSweep.getTerminalCommandId()).isNotNull();
        assertThat(afterSweep.getLastQueuedAt()).isNotNull();

        TerminalCommand cmd = cmdRepo.findById(afterSweep.getTerminalCommandId()).orElseThrow();
        seededCommandId = cmd.getId();
        assertThat(cmd.getPurpose()).isEqualTo(TerminalCommandPurpose.LISTING_FEE_REFUND);
        assertThat(cmd.getAction()).isEqualTo(TerminalCommandAction.REFUND);
        assertThat(cmd.getListingFeeRefundId()).isEqualTo(seededRefundId);
        assertThat(cmd.getRecipientUuid()).isEqualTo(seededSellerAvatarUuid.toString());
        assertThat(cmd.getAmount()).isEqualTo(100L);
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.QUEUED);

        // Second sweep is idempotent: terminalCommandId is already set so
        // the refund is skipped by findPendingAwaitingDispatch.
        listingFeeRefundProcessorJob.drainPending();
        assertThat(cmdRepo.findAll().stream()
                .filter(c -> c.getListingFeeRefundId() != null
                        && c.getListingFeeRefundId().equals(seededRefundId))
                .count()).isEqualTo(1L);

        // Simulate the terminal's success callback via the Task 7 path.
        String slTxn = "sl-txn-refund-" + UUID.randomUUID();
        terminalCommandService.applyCallback(new PayoutResultRequest(
                cmd.getIdempotencyKey(), true, slTxn, null, TERMINAL_ID, SHARED_SECRET));

        // Refund flipped to PROCESSED with processedAt + txnRef; command
        // flipped to COMPLETED; one COMPLETED LISTING_FEE_REFUND ledger row.
        ListingFeeRefund afterCallback = listingFeeRefundRepo.findById(seededRefundId).orElseThrow();
        assertThat(afterCallback.getStatus()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(afterCallback.getProcessedAt()).isNotNull();
        assertThat(afterCallback.getTxnRef()).isEqualTo(slTxn);

        TerminalCommand finalCmd = cmdRepo.findById(cmd.getId()).orElseThrow();
        assertThat(finalCmd.getStatus()).isEqualTo(TerminalCommandStatus.COMPLETED);
        assertThat(finalCmd.getCompletedAt()).isNotNull();

        List<EscrowTransaction> refundLedger = ledgerRepo.findAll().stream()
                .filter(r -> r.getAuction() != null
                        && r.getAuction().getId().equals(seededAuctionId)
                        && r.getType() == EscrowTransactionType.LISTING_FEE_REFUND)
                .toList();
        assertThat(refundLedger).hasSize(1);
        EscrowTransaction row = refundLedger.get(0);
        assertThat(row.getStatus()).isEqualTo(EscrowTransactionStatus.COMPLETED);
        assertThat(row.getAmount()).isEqualTo(100L);
        assertThat(row.getSlTransactionId()).isEqualTo(slTxn);
        // command.terminalId only gets stamped when the dispatcher actually
        // POSTs the command; we're skipping that phase, so terminalId on the
        // refund ledger row mirrors command.terminalId=null.
        assertThat(row.getTerminalId()).isNull();
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private void seedDraftAuctionWithTerminal() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            UUID sellerUuid = UUID.randomUUID();
            User seller = userRepo.save(User.builder()
                    .email("lf-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Listing Fee Seller")
                    .slAvatarUuid(sellerUuid)
                    .verified(true)
                    .build());
            Parcel parcel = parcelRepo.save(Parcel.builder()
                    .region(TestRegions.mainland())
                    .slParcelUuid(UUID.randomUUID())
                    .ownerUuid(sellerUuid)
                    .ownerType("agent")
                                                            .areaSqm(1024)
                                        .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Test listing")
                    .parcel(parcel)
                    .seller(seller)
                    .status(AuctionStatus.DRAFT)
                    .startingBid(500L)
                    .reservePrice(1_000L)
                    .currentBid(0L)
                    .bidCount(0)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(false)
                    .listingFeeAmt(100L)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .build());
            terminalRepo.save(Terminal.builder()
                    .terminalId(TERMINAL_ID)
                    .httpInUrl(HTTP_IN_URL)
                    .regionName(REGION_NAME)
                    .active(true)
                    .lastSeenAt(now)
                    .build());

            seededSellerId = seller.getId();
            seededSellerAvatarUuid = sellerUuid;
            seededParcelId = parcel.getId();
            seededAuctionId = auction.getId();
        });
    }

    private void seedPendingRefund() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            Auction auction = auctionRepo.findById(seededAuctionId).orElseThrow();
            ListingFeeRefund refund = listingFeeRefundRepo.save(ListingFeeRefund.builder()
                    .auction(auction)
                    .amount(100L)
                    .status(RefundStatus.PENDING)
                    .build());
            seededRefundId = refund.getId();
        });
    }
}
