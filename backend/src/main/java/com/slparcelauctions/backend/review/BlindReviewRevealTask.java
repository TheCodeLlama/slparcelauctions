package com.slparcelauctions.backend.review;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hourly scheduler that flips pending reviews past the review window to
 * {@code visible=true} (spec §5). Runs at the top of every hour and
 * processes up to {@code slpa.review.reveal-batch-limit} reviews per tick;
 * batch-limit hits are logged at WARN so operators notice sustained backlog
 * (catch up on the next tick is fine — reveal is idempotent).
 *
 * <p>Each per-review reveal runs inside its own transaction via
 * {@link ReviewService#reveal(Long)} so a single failing row doesn't
 * poison the rest of the batch. {@code findByIdForUpdate} inside
 * {@code reveal} serialises against a racing simultaneous-submit path
 * that might flip the same row — the idempotent early-return absorbs
 * that race harmlessly.
 *
 * <p>Gated by {@code slpa.review.scheduler.enabled} ({@code matchIfMissing=true})
 * so unit-test slices can disable the scheduler without forcing a test
 * profile. When disabled the bean is not registered at all, which also
 * keeps {@code @EnableScheduling} from spinning up a thread for it.
 */
@Component
@ConditionalOnProperty(
        name = "slpa.review.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BlindReviewRevealTask {

    private final ReviewRepository reviewRepo;
    private final ReviewService reviewService;
    private final ReviewProperties reviewProperties;
    private final Clock clock;

    /** Top of every hour. Cron: {@code second minute hour day month dow}. */
    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        runOnce();
    }

    /**
     * Package-private entry used by integration tests that prefer to
     * invoke the tick explicitly rather than wait for the scheduler
     * thread. Returns the number of reviews revealed this tick so tests
     * can assert on batch size.
     */
    int runOnce() {
        int batchLimit = reviewProperties.revealBatchLimit();
        OffsetDateTime threshold = OffsetDateTime.now(clock)
                .minus(Duration.ofDays(reviewProperties.windowDays()));
        List<Review> revealable = reviewRepo.findRevealable(
                threshold, PageRequest.of(0, batchLimit));
        if (revealable.isEmpty()) {
            return 0;
        }

        log.info("BlindReviewReveal: {} reviews past the review window", revealable.size());
        int processed = 0;
        for (Review r : revealable) {
            try {
                reviewService.reveal(r.getId());
                processed++;
            } catch (Exception e) {
                log.error("Failed to reveal review {}: {}", r.getId(), e.toString());
            }
        }
        if (revealable.size() == batchLimit) {
            log.warn("BlindReviewReveal: hit batch limit {}; re-running next tick",
                    batchLimit);
        }
        return processed;
    }
}
