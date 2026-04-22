package com.slparcelauctions.backend.escrow.scheduler;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.escrow.EscrowRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled sweep that every {@code slpa.escrow.ownership-monitor-job.fixed-delay}
 * (default 5m) queries every {@code TRANSFER_PENDING} escrow id and dispatches
 * each through {@link EscrowOwnershipCheckTask#checkOne}. Spec §4.5.
 *
 * <p>The sweep runs on the default Spring task scheduler thread. Per-escrow
 * work is synchronous (one escrow at a time) because TRANSFER_PENDING
 * cardinality is small — a single stalled World API call blocks subsequent
 * checks for at most the World API's retry budget, which is already
 * bounded. If cardinality grows, we can swap the inner invocation to
 * {@code @Async} without touching the scheduler shape.
 *
 * <p>Gated by {@code slpa.escrow.ownership-monitor-job.enabled}
 * ({@code matchIfMissing=true}), mirroring the auction monitor gate so
 * tests can disable the bean entirely via one property.
 */
@Service
@ConditionalOnProperty(
        value = "slpa.escrow.ownership-monitor-job.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class EscrowOwnershipMonitorJob {

    private final EscrowRepository escrowRepo;
    private final EscrowOwnershipCheckTask checkTask;

    @Scheduled(fixedDelayString = "${slpa.escrow.ownership-monitor-job.fixed-delay:PT5M}")
    public void sweep() {
        List<Long> ids = escrowRepo.findTransferPendingIds();
        if (ids.isEmpty()) {
            return;
        }
        log.info("EscrowOwnershipMonitorJob processing {} transfer-pending escrows", ids.size());
        for (Long id : ids) {
            try {
                checkTask.checkOne(id);
            } catch (RuntimeException e) {
                // Per-escrow failures must never take down the sweep — we log
                // and keep going so a single bad row can't stall the rest.
                log.error("Escrow ownership check failed for escrow {}: {}",
                        id, e.getMessage(), e);
            }
        }
    }
}
