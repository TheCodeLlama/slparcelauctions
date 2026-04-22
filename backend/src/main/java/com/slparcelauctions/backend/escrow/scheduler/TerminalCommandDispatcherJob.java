package com.slparcelauctions.backend.escrow.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled sweep that every {@code slpa.escrow.command-dispatcher-job.fixed-delay}
 * (default 30s) runs the IN_FLIGHT staleness recovery and dispatches every
 * QUEUED / retry-ready FAILED command (spec §7.4).
 *
 * <p>The order is deliberate: the staleness sweep runs first, flipping
 * abandoned IN_FLIGHT rows back to FAILED with {@code nextAttemptAt=now},
 * so the dispatchable query picks them up in the very next iteration. This
 * prevents a terminal that silently dies from holding a command in
 * IN_FLIGHT for two sweeps before retry.
 *
 * <p>Per-command failures must never take down the sweep, so the inner
 * loop catches and logs {@code RuntimeException} — a single bad row can't
 * stall the rest. Mirrors the pattern from
 * {@link EscrowOwnershipMonitorJob}.
 */
@Service
@ConditionalOnProperty(
        value = "slpa.escrow.command-dispatcher-job.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class TerminalCommandDispatcherJob {

    private final TerminalCommandRepository cmdRepo;
    private final TerminalCommandDispatcherTask dispatcherTask;
    private final EscrowConfigProperties props;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${slpa.escrow.command-dispatcher-job.fixed-delay:PT30S}")
    public void dispatch() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration staleCutoff = props.commandInFlightTimeout();

        List<Long> stale = cmdRepo.findStaleInFlight(now.minus(staleCutoff));
        if (!stale.isEmpty()) {
            log.info("TerminalCommandDispatcherJob recovering {} stale IN_FLIGHT commands",
                    stale.size());
            for (Long id : stale) {
                try {
                    dispatcherTask.markStaleAndRequeue(id);
                } catch (RuntimeException e) {
                    log.error("Stale-requeue failed for command {}: {}",
                            id, e.getMessage(), e);
                }
            }
        }

        List<Long> dispatchable = cmdRepo.findDispatchable(now);
        if (dispatchable.isEmpty()) {
            return;
        }
        log.info("TerminalCommandDispatcherJob dispatching {} commands", dispatchable.size());
        for (Long id : dispatchable) {
            try {
                dispatcherTask.dispatchOne(id);
            } catch (RuntimeException e) {
                log.error("Dispatch failed for command {}: {}", id, e.getMessage(), e);
            }
        }
    }
}
