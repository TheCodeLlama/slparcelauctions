package com.slparcelauctions.backend.escrow.scheduler;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.ListingFeeRefund;
import com.slparcelauctions.backend.auction.ListingFeeRefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled sweep that drains PENDING {@link ListingFeeRefund} rows into
 * the {@code TerminalCommand} queue. Spec §10.3, §11.
 *
 * <p>{@code CancellationService} (Epic 03 sub-spec 1) writes
 * {@code ListingFeeRefund} rows with {@code status=PENDING} when a seller
 * cancels a paid auction before verification. This job is what Epic 05
 * sub-spec 1 was scoped to deliver: it picks up every PENDING refund that
 * has not yet been queued
 * ({@code findPendingAwaitingDispatch} = status PENDING + terminalCommandId IS NULL)
 * and fans each one out to the per-refund
 * {@link ListingFeeRefundProcessorTask#queueOne} worker, which calls
 * {@code TerminalCommandService.queueListingFeeRefund} to stamp the FK
 * back onto the refund row.
 *
 * <p>Per-refund failures must never take down the sweep — the inner loop
 * catches and logs {@code RuntimeException} so a single bad row can't
 * stall the rest. Mirrors {@link TerminalCommandDispatcherJob},
 * {@link EscrowTimeoutJob}, {@link EscrowOwnershipMonitorJob}.
 *
 * <p>Default cadence (1 minute) is considerably slower than the
 * dispatcher's 30s tick: refund dispatch is not time-critical (the L$
 * is already held at the SLParcels avatar account) and the idempotency guard
 * on {@code terminalCommandId IS NULL} makes extra sweeps cheap but
 * wasteful. Gated by {@code slpa.escrow.listing-fee-refund-job.enabled}
 * ({@code matchIfMissing=true}) so tests can disable the bean entirely
 * via one property.
 */
@Service
@ConditionalOnProperty(
        value = "slpa.escrow.listing-fee-refund-job.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ListingFeeRefundProcessorJob {

    private final ListingFeeRefundRepository listingFeeRefundRepo;
    private final ListingFeeRefundProcessorTask processorTask;

    @Scheduled(fixedDelayString = "${slpa.escrow.listing-fee-refund-job.fixed-delay:PT1M}")
    public void drainPending() {
        List<ListingFeeRefund> pending = listingFeeRefundRepo.findPendingAwaitingDispatch();
        if (pending.isEmpty()) {
            return;
        }
        log.info("ListingFeeRefundProcessorJob queuing {} pending refunds", pending.size());
        for (ListingFeeRefund refund : pending) {
            try {
                processorTask.queueOne(refund.getId());
            } catch (RuntimeException e) {
                log.error("Failed to queue ListingFeeRefund {}: {}",
                        refund.getId(), e.getMessage(), e);
            }
        }
    }
}
