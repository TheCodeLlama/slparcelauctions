package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bot worker task queue. The verification + monitoring task types
 * (VERIFY / MONITOR_AUCTION / MONITOR_ESCROW) were retired with the
 * ownership-only verification refactor (spec 2026-05-16); the
 * {@link BotTaskType} enum is intentionally empty so no production
 * caller currently enqueues a row. The {@link #claim} and
 * {@link #findPending} surfaces are kept as future-extension
 * scaffolding -- a new task type can plug in by adding an enum value
 * and a producer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotTaskService {

    private final BotTaskRepository botTaskRepo;
    private final Clock clock;

    /**
     * Atomically claims the next due PENDING task for {@code botUuid}.
     * Wraps {@link BotTaskRepository#claimNext} in a transaction and
     * stamps the row with {@code assignedBotUuid} + status=IN_PROGRESS
     * before the lock releases. Returns empty when the queue has no
     * due tasks -- the worker should back off and retry.
     */
    @Transactional
    public Optional<BotTask> claim(UUID botUuid) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return botTaskRepo.claimNext(now).map(task -> {
            task.setStatus(BotTaskStatus.IN_PROGRESS);
            task.setAssignedBotUuid(botUuid);
            log.debug("Bot task {} claimed by {}", task.getId(), botUuid);
            return botTaskRepo.save(task);
        });
    }

    @Transactional(readOnly = true)
    public List<BotTask> findPending() {
        return botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING);
    }

    /**
     * Enqueue a SCAN_PARCEL task for the given auction. The task is created in
     * PENDING state with no assigned bot. The caller is responsible for ensuring
     * eligibility (scan included, no raster on file, no existing pending task).
     *
     * <p>{@code sentinelPrice} is set to 0 -- SCAN_PARCEL does not use a price
     * sentinel; the field is NOT NULL so we satisfy the constraint with zero.
     */
    @Transactional
    public BotTask enqueueScanParcel(Auction auction) {
        BotTask task = BotTask.builder()
                .taskType(BotTaskType.SCAN_PARCEL)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(auction.getSlParcelUuid())
                .regionName(auction.getParcelSnapshot() != null
                        ? auction.getParcelSnapshot().getRegionName()
                        : null)
                .sentinelPrice(0L)
                .build();
        BotTask saved = botTaskRepo.save(task);
        log.info("Enqueued SCAN_PARCEL bot task {} for auction {}", saved.getId(), auction.getId());
        return saved;
    }

    /**
     * Mark a task COMPLETED. Sets {@code completedAt} to now and persists.
     */
    @Transactional
    public BotTask markCompleted(BotTask task) {
        task.setStatus(BotTaskStatus.COMPLETED);
        task.setCompletedAt(OffsetDateTime.now(clock));
        return botTaskRepo.save(task);
    }
}
