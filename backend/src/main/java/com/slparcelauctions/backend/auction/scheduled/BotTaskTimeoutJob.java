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
 * Times out stuck Method C (SALE_TO_BOT) bot tasks.
 *
 * <p>Runs every {@code slpa.bot-task.timeout-check-interval} (default
 * {@code PT15M}). For every PENDING task older than
 * {@code slpa.bot-task.timeout-hours} (default 48 h), transitions the task to
 * FAILED with reason {@code "TIMEOUT"} and flips the associated auction back
 * to VERIFICATION_FAILED (only if it is still VERIFICATION_PENDING — defensive
 * guard for auctions that were cancelled or otherwise moved on). There is no
 * refund: the seller still owes the listing fee and can click Verify again
 * for a fresh task.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BotTaskTimeoutJob {

    private final BotTaskService service;

    @Value("${slpa.bot-task.timeout-hours:48}")
    private int timeoutHours;

    @Scheduled(fixedDelayString = "${slpa.bot-task.timeout-check-interval:PT15M}")
    public void sweep() {
        Duration threshold = Duration.ofHours(timeoutHours);
        List<BotTask> timedOut = service.findPendingOlderThan(threshold);
        if (timedOut.isEmpty()) return;
        timedOut.forEach(service::markTimedOut);
        log.info("BotTaskTimeoutJob: timed out {} bot task(s) this sweep", timedOut.size());
    }
}
