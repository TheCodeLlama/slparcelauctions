package com.slparcelauctions.backend.escrow.payment;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.escrow.payment.dto.ListingFeePaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Production listing-fee payment handler. Spec §10.3.
 *
 * <p>The in-world listing-fee terminal POSTs to
 * {@code POST /api/v1/sl/listing-fee/payment} after receiving the seller's
 * L$ for a DRAFT-stage auction. This service is the single authoritative
 * enforcement point for the payment trust pipeline — the HTTP layer only
 * validates the SL-injected headers:
 *
 * <ol>
 *   <li>Shared secret match (constant-time via
 *       {@link TerminalService#assertSharedSecret})</li>
 *   <li>Idempotency on {@code slTransactionKey} (replays of a COMPLETED
 *       LISTING_FEE_PAYMENT ledger row short-circuit with OK)</li>
 *   <li>Terminal registered (known, not necessarily live)</li>
 *   <li>Auction exists</li>
 *   <li>State gate — DRAFT proceeds; DRAFT_PAID yields ALREADY_PAID
 *       ERROR; any other status yields ESCROW_EXPIRED REFUND</li>
 *   <li>Payer match — the seller's {@code slAvatarUuid} case-insensitive
 *       against {@code payerUuid}</li>
 *   <li>Amount match — exact equality against
 *       {@link Auction#getListingFeeAmt()}</li>
 * </ol>
 *
 * <p>On success, transitions the auction from DRAFT to DRAFT_PAID,
 * stamps the listing-fee fields ({@code listingFeePaid=true},
 * {@code listingFeeAmt}, {@code listingFeeTxn}, {@code listingFeePaidAt}),
 * writes a COMPLETED {@link EscrowTransaction} ledger row of type
 * {@code LISTING_FEE_PAYMENT}, and returns OK. No WebSocket envelope
 * fans out — listing-fee payments are a seller-only signal and the
 * dashboard surfaces them via the seller auction DTO's
 * {@code listingFeePaid} flag.
 *
 * <p>The dev-profile {@code DevAuctionController.pay} continues to work
 * for browser-driven smoke tests. Both write the same three fields on
 * the Auction row; the production path adds the ledger row (a proper L$
 * movement deserves a ledger entry, a mock dev payment does not).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListingFeePaymentService {

    private final AuctionRepository auctionRepo;
    private final TerminalRepository terminalRepo;
    private final TerminalService terminalService;
    private final EscrowTransactionRepository ledgerRepo;
    private final Clock clock;

    @Transactional
    public SlCallbackResponse acceptPayment(ListingFeePaymentRequest req) {
        // 1. Shared secret (constant-time) — same enforcement point the
        // escrow-payment flow uses; neither controller pre-validates.
        terminalService.assertSharedSecret(req.sharedSecret());

        // 2. Idempotency on slTransactionKey. A replay of a successful
        // payment returns OK without touching state; a replay of a
        // previous FAILED row would be uncommon (listing-fee payments
        // don't have the same "wrong payer" fraud-flag surface as escrow)
        // but we keep the pattern consistent.
        Optional<EscrowTransaction> existing = ledgerRepo
                .findFirstBySlTransactionIdAndType(
                        req.slTransactionKey(), EscrowTransactionType.LISTING_FEE_PAYMENT);
        if (existing.isPresent()) {
            EscrowTransaction tx = existing.get();
            if (tx.getStatus() == EscrowTransactionStatus.COMPLETED) {
                return SlCallbackResponse.ok();
            }
            // FAILED replay — reconstruct a REFUND; the listing-fee flow
            // doesn't record the specific reason on the ledger today, so
            // fall back to ESCROW_EXPIRED which is safe for the terminal
            // (it refunds).
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.ESCROW_EXPIRED,
                    "Replay of previously-failed listing-fee payment");
        }

        // 3. Terminal must be registered.
        if (!terminalRepo.existsById(req.terminalId())) {
            return SlCallbackResponse.error(
                    EscrowCallbackResponseReason.UNKNOWN_TERMINAL,
                    "Terminal not registered: " + req.terminalId());
        }

        // 4. Auction must exist. Unknown auction → ERROR (terminal does
        // NOT refund — there's no matching auction record to pay for).
        Auction auction = auctionRepo.findById(req.auctionId()).orElse(null);
        if (auction == null) {
            return SlCallbackResponse.error(
                    EscrowCallbackResponseReason.UNKNOWN_AUCTION,
                    "Auction " + req.auctionId() + " not found");
        }

        // 5. State gate.
        AuctionStatus status = auction.getStatus();
        if (status == AuctionStatus.DRAFT_PAID) {
            // Already paid with a different slTransactionKey. This is the
            // "seller paid twice" case — the terminal's second attempt
            // should NOT refund (we have the L$ and have already flipped
            // the auction); ERROR signals the terminal to leave the money
            // alone and escalate to support.
            return SlCallbackResponse.error(
                    EscrowCallbackResponseReason.ALREADY_PAID,
                    "Auction " + req.auctionId() + " already paid");
        }
        if (status != AuctionStatus.DRAFT) {
            // Any other state (VERIFICATION_PENDING, ACTIVE, ENDED,
            // CANCELLED, SUSPENDED, etc.) — the listing-fee window is
            // closed. REFUND so the seller's L$ comes back; the terminal
            // operator can support-ticket the edge case.
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.ESCROW_EXPIRED,
                    "Auction not in DRAFT state (current: " + status + ")");
        }

        // 6. Payer match — must be the seller. Case-insensitive UUID
        // comparison mirrors the escrow-payment flow (LSL upper-cases
        // key dumps, Java stores lower-case).
        User seller = auction.getSeller();
        if (seller.getSlAvatarUuid() == null
                || !seller.getSlAvatarUuid().toString().equalsIgnoreCase(req.payerUuid())) {
            String expected = seller.getSlAvatarUuid() == null
                    ? "<null>" : seller.getSlAvatarUuid().toString();
            log.warn("Listing-fee wrong-payer on auction {}: expected={}, actual={}",
                    req.auctionId(), expected, req.payerUuid());
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.WRONG_PAYER,
                    "Payer does not match seller");
        }

        // 7. Amount match. The auction's listingFeeAmt is stamped at
        // create time from slpa.listing-fee.amount-lindens; the terminal
        // script must quote the exact amount.
        if (!req.amount().equals(auction.getListingFeeAmt())) {
            return SlCallbackResponse.refund(
                    EscrowCallbackResponseReason.WRONG_AMOUNT,
                    "Expected L$" + auction.getListingFeeAmt()
                            + ", got L$" + req.amount());
        }

        // 8. Transition DRAFT → DRAFT_PAID and stamp the listing-fee
        // fields. Mirror DevAuctionController.pay so seller-side DTO
        // projections behave identically regardless of entry path.
        OffsetDateTime now = OffsetDateTime.now(clock);
        auction.setStatus(AuctionStatus.DRAFT_PAID);
        auction.setListingFeePaid(true);
        auction.setListingFeePaidAt(now);
        auction.setListingFeeTxn(req.slTransactionKey());
        auctionRepo.save(auction);

        // 9. Ledger row — the production entry point writes a
        // LISTING_FEE_PAYMENT row (the dev entry point does not, since
        // no real L$ moved).
        ledgerRepo.save(EscrowTransaction.builder()
                .auction(auction)
                .type(EscrowTransactionType.LISTING_FEE_PAYMENT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(req.amount())
                .payer(seller)
                .slTransactionId(req.slTransactionKey())
                .terminalId(req.terminalId())
                .completedAt(now)
                .build());

        log.info("Listing fee payment accepted: auction {} seller {} amount L${} txn {}",
                req.auctionId(), seller.getId(), req.amount(), req.slTransactionKey());

        return SlCallbackResponse.ok();
    }
}
