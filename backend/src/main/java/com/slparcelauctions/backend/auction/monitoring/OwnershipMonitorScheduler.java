package com.slparcelauctions.backend.auction.monitoring;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled orchestrator for the ownership-monitor subsystem (spec §8.2).
 * Runs at {@code slpa.ownership-monitor.scheduler-frequency} cadence, queries
 * ACTIVE auctions whose {@code lastOwnershipCheckAt} is due, and dispatches
 * each via {@link OwnershipCheckTask#checkOne} on the async executor. The
 * scheduler thread returns immediately after dispatch, so one slow World API
 * lookup cannot stall checks for other auctions.
 *
 * <p>Gated by {@code slpa.ownership-monitor.enabled} ({@code matchIfMissing=true}).
 * Setting the flag to {@code false} keeps the bean out of the context entirely,
 * so no scheduler thread spins up in tests that disable the monitor.
 *
 * <p>The {@link Scheduled} expression is
 * {@code ${slpa.ownership-monitor.scheduler-frequency:PT30S}} rather than
 * reading {@link OwnershipMonitorProperties#getSchedulerFrequency()} because
 * Spring's {@code @Scheduled} resolves property-placeholder strings at bean
 * post-processing time, which precedes property binding. Keeping the default
 * ({@code PT30S}) in the annotation mirrors {@code application.yml}.
 */
@Service
@ConditionalOnProperty(
        value = "slpa.ownership-monitor.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OwnershipMonitorScheduler {

    private final AuctionRepository auctionRepo;
    private final OwnershipCheckTask ownershipCheckTask;
    private final OwnershipMonitorProperties props;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${slpa.ownership-monitor.scheduler-frequency:PT30S}")
    public void dispatchDueChecks() {
        // {@code now} is passed alongside {@code cutoff} so the post-cancel
        // watch-window predicate ({@code postCancelWatchUntil > :now}) and the
        // cadence gate ({@code lastOwnershipCheckAt < :cutoff}) are evaluated
        // against the same wall-clock instant — the two values are derived
        // from one {@link Clock#instant()} read so a slow scheduler tick can
        // not see different "now"s on the two predicates.
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime cutoff = now.minusMinutes(props.getCheckIntervalMinutes());
        List<Long> dueIds = auctionRepo.findDueForOwnershipCheck(cutoff, now);
        if (dueIds.isEmpty()) {
            return;
        }
        log.debug("Dispatching ownership checks for {} auctions (cutoff={})",
                dueIds.size(), cutoff);
        for (Long id : dueIds) {
            ownershipCheckTask.checkOne(id);
        }
    }
}
