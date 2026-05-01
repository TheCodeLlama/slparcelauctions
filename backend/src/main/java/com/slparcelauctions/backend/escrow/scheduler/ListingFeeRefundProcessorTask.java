package com.slparcelauctions.backend.escrow.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.ListingFeeRefund;
import com.slparcelauctions.backend.auction.ListingFeeRefundRepository;
import com.slparcelauctions.backend.auction.RefundStatus;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.WalletService;

import org.springframework.beans.factory.annotation.Value;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-refund worker invoked by {@link ListingFeeRefundProcessorJob}. Locks
 * the refund row under {@code PESSIMISTIC_WRITE}, re-validates the state
 * + not-yet-queued invariants, delegates to
 * {@link TerminalCommandService#queueListingFeeRefund} to create the
 * {@code TerminalCommand} row (the dispatcher picks it up on its next
 * tick), then stamps the command id + {@code lastQueuedAt} back onto the
 * refund.
 *
 * <p>Per-refund work runs in a fresh transaction ({@code REQUIRES_NEW}) so
 * the sweep's loop-level exception handling can isolate failures — a
 * single bad refund can't stall the rest of the sweep. Mirrors
 * {@link EscrowTimeoutTask} and
 * {@link com.slparcelauctions.backend.escrow.scheduler.TerminalCommandDispatcherTask}.
 *
 * <p>The re-validation guards against concurrency: a refund could have
 * been flipped to PROCESSED between the sweep's snapshot read and this
 * lock (e.g. admin tooling marks it manually completed), or it could have
 * been queued by a racing sweep instance. Short-circuiting on either
 * leaves the refund untouched; the dispatcher still owns the downstream
 * retry semantics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListingFeeRefundProcessorTask {

    private final ListingFeeRefundRepository listingFeeRefundRepo;
    private final TerminalCommandService terminalCommandService;
    private final WalletService walletService;
    private final UserRepository userRepo;
    private final Clock clock;

    @Value("${slpa.wallet.enforcement-enabled:false}")
    private boolean walletEnforcementEnabled;

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
        if (refund.getTerminalCommandId() != null) {
            log.debug("ListingFeeRefund {} skipped under lock: terminalCommandId already set ({})",
                    refundId, refund.getTerminalCommandId());
            return;
        }
        if (walletEnforcementEnabled) {
            // Wallet model: refund is a wallet credit, not a TerminalCommand REFUND.
            User seller = userRepo.findByIdForUpdate(
                    refund.getAuction().getSeller().getId()).orElseThrow();
            walletService.creditListingFeeRefund(seller, refund.getAmount(), refund.getId());
            refund.setStatus(RefundStatus.PROCESSED);
            refund.setProcessedAt(OffsetDateTime.now(clock));
            listingFeeRefundRepo.save(refund);
            log.info("ListingFeeRefund {} credited to wallet (sellerId={}, amount=L${})",
                    refund.getId(), seller.getId(), refund.getAmount());
        } else {
            TerminalCommand cmd = terminalCommandService.queueListingFeeRefund(refund);
            refund.setTerminalCommandId(cmd.getId());
            refund.setLastQueuedAt(OffsetDateTime.now(clock));
            listingFeeRefundRepo.save(refund);
            log.info("Queued ListingFeeRefund {} as TerminalCommand {} (L${} to seller {})",
                    refund.getId(), cmd.getId(), refund.getAmount(),
                    refund.getAuction().getSeller().getId());
        }
    }
}
