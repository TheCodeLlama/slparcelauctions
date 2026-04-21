package com.slparcelauctions.backend.auction.auctionend;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.AuctionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled sweeper that closes expired ACTIVE auctions (spec §8). Runs at
 * {@code slpa.auction-end.scheduler-frequency} cadence (default {@code PT15S}).
 * The loop queries {@link AuctionRepository#findActiveIdsDueForEnd} for every
 * ACTIVE auction whose {@code endsAt} has passed and hands each id to
 * {@link AuctionEndTask#closeOne} in turn.
 *
 * <p><strong>Sequential dispatch.</strong> Unlike the ownership-monitor, each
 * close is invoked on the scheduler thread rather than the async executor.
 * At Phase 1 volumes the due-list is small and each close is a fast DB round
 * trip; introducing per-close async dispatch would add bounded-thread-pool
 * concerns with no measurable throughput benefit. A single close failure is
 * logged and swallowed so the sweep continues through the rest of the batch.
 *
 * <p>Gated by {@code slpa.auction-end.enabled} ({@code matchIfMissing=true}).
 * Integration tests that create ACTIVE auctions with expired {@code endsAt}
 * MUST flip this flag to {@code false} to keep the real scheduler from racing
 * the explicit {@code /api/v1/dev/auction-end/run-once} trigger.
 *
 * <p>The {@link Scheduled} expression uses the property-placeholder
 * {@code ${slpa.auction-end.scheduler-frequency:PT15S}} rather than a
 * {@code @ConfigurationProperties} getter because Spring's {@code @Scheduled}
 * resolves the annotation value at bean post-processing time, which precedes
 * property binding. Keeping the default ({@code PT15S}) in the annotation
 * mirrors {@code application.yml}.
 */
@Service
@ConditionalOnProperty(
        value = "slpa.auction-end.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AuctionEndScheduler {

    private final AuctionRepository auctionRepo;
    private final AuctionEndTask auctionEndTask;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${slpa.auction-end.scheduler-frequency:PT15S}")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> dueIds = auctionRepo.findActiveIdsDueForEnd(now);
        if (dueIds.isEmpty()) {
            return;
        }
        log.info("AuctionEndScheduler processing {} due auctions", dueIds.size());
        for (Long id : dueIds) {
            try {
                auctionEndTask.closeOne(id);
            } catch (RuntimeException e) {
                // A single auction's close failure must not abort the sweep —
                // the remaining due auctions should still be processed. The
                // next cron tick will retry the failed id.
                log.error("Failed to close auction {}: {}", id, e.getMessage(), e);
            }
        }
    }
}
