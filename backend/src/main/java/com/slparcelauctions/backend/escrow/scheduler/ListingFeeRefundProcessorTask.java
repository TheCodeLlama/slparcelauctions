package com.slparcelauctions.backend.escrow.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.ListingFeeRefund;
import com.slparcelauctions.backend.auction.ListingFeeRefundRepository;
import com.slparcelauctions.backend.auction.RefundStatus;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-refund worker invoked by {@link ListingFeeRefundProcessorJob}. Locks
 * the refund row under {@code PESSIMISTIC_WRITE}, re-validates the state
 * + not-yet-queued invariants, then issues an instant SLParcels-side
 * wallet credit to whichever wallet originally paid the listing fee
 * (group wallet for case-3 listings, user wallet for case-1/2).
 *
 * <p>Per-refund work runs in a fresh transaction ({@code REQUIRES_NEW}) so
 * the sweep's loop-level exception handling can isolate failures — a
 * single bad refund can't stall the rest of the sweep.
 *
 * <p>The re-validation guards against concurrency: a refund could have
 * been flipped to PROCESSED between the sweep's snapshot read and this
 * lock (e.g. admin tooling marks it manually completed). Short-circuiting
 * leaves the refund untouched.
 *
 * <p><b>No in-world payout path.</b> Per platform policy, refunds always
 * stay inside SLParcels wallets. The depositor can withdraw to their SL
 * avatar at any time via the regular Withdraw flow if they want the L$
 * out of the system.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListingFeeRefundProcessorTask {

    private final ListingFeeRefundRepository listingFeeRefundRepo;
    private final WalletService walletService;
    private final UserRepository userRepo;
    private final Clock clock;
    private final RealtyGroupLedgerRepository realtyGroupLedgerRepository;
    private final RealtyGroupWalletService realtyGroupWalletService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void queueOne(Long refundId) {
        ListingFeeRefund refund = listingFeeRefundRepo.findByIdForUpdate(refundId).orElse(null);
        if (refund == null) {
            log.debug("ListingFeeRefund {} skipped: row vanished between sweep and lock",
                    refundId);
            return;
        }
        if (refund.getStatus() != RefundStatus.PENDING) {
            log.debug("ListingFeeRefund {} skipped under lock: status={} (not PENDING)",
                    refundId, refund.getStatus());
            return;
        }
        // Route to group wallet when the original listing-fee debit came
        // from a realty_group_ledger row (case-3, D-era group listing).
        // Otherwise credit the seller's user wallet (case-1/2 individual
        // listing, or C-era group listing).
        Long auctionId = refund.getAuction().getId();
        Optional<RealtyGroupLedgerEntry> groupDebit =
            realtyGroupLedgerRepository.findListingFeeDebitForAuction(auctionId);
        if (groupDebit.isPresent()) {
            realtyGroupWalletService.creditListingFeeRefund(
                groupDebit.get().getGroupId(), auctionId, refund.getAmount(), refund.getId());
            refund.setStatus(RefundStatus.PROCESSED);
            refund.setProcessedAt(OffsetDateTime.now(clock));
            listingFeeRefundRepo.save(refund);
            log.info("ListingFeeRefund {} credited to group wallet (groupId={}, amount=L${})",
                refund.getId(), groupDebit.get().getGroupId(), refund.getAmount());
            return;
        }
        User seller = userRepo.findByIdForUpdate(
                refund.getAuction().getSeller().getId()).orElseThrow();
        walletService.creditListingFeeRefund(seller, refund.getAmount(), refund.getId());
        refund.setStatus(RefundStatus.PROCESSED);
        refund.setProcessedAt(OffsetDateTime.now(clock));
        listingFeeRefundRepo.save(refund);
        log.info("ListingFeeRefund {} credited to user wallet (sellerId={}, amount=L${})",
            refund.getId(), seller.getId(), refund.getAmount());
    }
}
