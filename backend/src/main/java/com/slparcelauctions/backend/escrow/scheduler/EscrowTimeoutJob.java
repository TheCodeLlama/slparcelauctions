package com.slparcelauctions.backend.escrow.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.escrow.EscrowRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled timeout sweeper for escrow (spec §4.6). Every
 * {@code slpa.escrow.timeout-job.fixed-delay} (default 5m) runs two
 * independent queries — one for ESCROW_PENDING escrows whose 48h
 * {@code paymentDeadline} has passed (winner never paid), one for
 * TRANSFER_PENDING escrows whose 72h {@code transferDeadline} has passed
 * AND have no active PAYOUT command (seller never transferred) — and
 * dispatches each id through the per-escrow
 * {@link EscrowTimeoutTask#expirePayment} /
 * {@link EscrowTimeoutTask#expireTransfer}.
 *
 * <p>The critical invariant is the payout-in-flight guard carried by
 * {@link EscrowRepository#findExpiredTransferPendingIds}: an escrow whose
 * ownership was confirmed and whose payout command is still QUEUED /
 * IN_FLIGHT / FAILED-pending-retry has already satisfied the 72h deadline
 * from the seller's side. Expiring such a row would queue a refund while
 * the payout is still attempting to deliver, double-spending the escrow
 * account during a transient terminal outage. The repo query is the sole
 * enforcement point for this guard; the per-escrow task deliberately does
 * NOT re-check it (the race window is narrow and a duplicate is easier
 * to resolve in the admin queue than a missed expiry).
 *
 * <p>Per-escrow failures must never take down the sweep — the inner loop
 * catches and logs {@code RuntimeException} so a single bad row can't
 * stall the rest. Mirrors {@link EscrowOwnershipMonitorJob} and
 * {@link TerminalCommandDispatcherJob}.
 *
 * <p>Gated by {@code slpa.escrow.timeout-job.enabled}
 * ({@code matchIfMissing=true}) so tests can disable the bean entirely
 * via one property.
 */
@Service
@ConditionalOnProperty(
        value = "slpa.escrow.timeout-job.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class EscrowTimeoutJob {

    private final EscrowRepository escrowRepo;
    private final EscrowTimeoutTask timeoutTask;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${slpa.escrow.timeout-job.fixed-delay:PT5M}")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> pendingIds = escrowRepo.findExpiredPendingIds(now);
        List<Long> transferIds = escrowRepo.findExpiredTransferPendingIds(now);
        if (pendingIds.isEmpty() && transferIds.isEmpty()) {
            return;
        }
        log.info("EscrowTimeoutJob processing {} payment-timeout + {} transfer-timeout",
                pendingIds.size(), transferIds.size());
        for (Long id : pendingIds) {
            try {
                timeoutTask.expirePayment(id, now);
            } catch (RuntimeException e) {
                log.error("Escrow payment-timeout failed for {}: {}", id, e.getMessage(), e);
            }
        }
        for (Long id : transferIds) {
            try {
                timeoutTask.expireTransfer(id, now);
            } catch (RuntimeException e) {
                log.error("Escrow transfer-timeout failed for {}: {}", id, e.getMessage(), e);
            }
        }
    }
}
