package com.slparcelauctions.backend.auction.scheduled;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Times out stuck bot tasks. Two passes:
 * <ul>
 *   <li>{@link #sweepPending()} — existing 48h PENDING sweep (unclaimed
 *       tasks that were never picked up).</li>
 *   <li>{@link #sweepInProgress()} — new in Epic 06. Catches workers that
 *       crashed mid-execution. VERIFY tasks flip to FAILED; MONITOR_* tasks
 *       re-arm to PENDING (different worker retries next cycle). Closes
 *       the "IN_PROGRESS bot task timeout" deferred item.</li>
 * </ul>
 *
 * <p>For PENDING tasks older than {@code slpa.bot-task.timeout-hours}
 * (default 48 h) the task is flipped to FAILED with reason {@code "TIMEOUT"}
 * and the auction reverts to VERIFICATION_FAILED (only if it is still
 * VERIFICATION_PENDING — defensive guard for auctions that were cancelled
 * or otherwise moved on). There is no refund: the seller still owes the
 * listing fee and can click Verify again for a fresh task.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BotTaskTimeoutJob {

    private final BotTaskService service;

    @Value("${slpa.bot-task.timeout-hours:48}")
    private int timeoutHours;

    @Value("${slpa.bot-task.in-progress-timeout:PT20M}")
    private Duration inProgressTimeout;

    @Scheduled(fixedDelayString = "${slpa.bot-task.timeout-check-interval:PT15M}")
    public void sweepPending() {
        Duration threshold = Duration.ofHours(timeoutHours);
        List<BotTask> timedOut = service.findPendingOlderThan(threshold);
        if (timedOut.isEmpty()) return;
        timedOut.forEach(service::markTimedOut);
        log.info("BotTaskTimeoutJob: PENDING sweep timed out {} task(s)",
                timedOut.size());
    }

    @Scheduled(fixedDelayString = "${slpa.bot-task.timeout-check-interval:PT15M}")
    public void sweepInProgress() {
        List<BotTask> stalled = service.findInProgressOlderThan(inProgressTimeout);
        if (stalled.isEmpty()) return;
        stalled.forEach(service::handleInProgressTimeout);
        log.info("BotTaskTimeoutJob: IN_PROGRESS sweep cleared {} task(s) "
                        + "(threshold={})",
                stalled.size(), inProgressTimeout);
    }
}
