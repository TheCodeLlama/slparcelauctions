package com.slparcelauctions.backend.admin.infrastructure.reminders;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.review.Review;
import com.slparcelauctions.backend.review.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Daily scheduler (09:05 UTC) that fires a once-per-review response-window-closing
 * reminder when a visible review's response deadline is 24–48 hours away.
 *
 * <p>Response deadline = {@code revealedAt + 14 days} (mirrors the 14-day
 * review-submission window from {@link com.slparcelauctions.backend.review.ReviewService#REVIEW_WINDOW}).
 * The reminder fires when that deadline ∈ [now+24h, now+48h].
 *
 * <p>Equivalently: {@code revealedAt ∈ [now−312h, now−288h]}.
 * The repository query takes the raw {@code revealedAt} bounds so the JPQL
 * stays a simple BETWEEN — no date arithmetic inside JPQL.
 *
 * <p>Only reviews that have not yet received a response (no {@link
 * com.slparcelauctions.backend.review.ReviewResponse} row) are targeted.
 * {@code responseClosingReminderSentAt} is stamped after firing to prevent
 * duplicate reminders on subsequent daily runs.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "slpa.review-response-reminder",
        name = "enabled",
        matchIfMissing = true)
@Slf4j
public class ReviewResponseWindowClosingScheduler {

    /** Response-window length in hours (mirrors ReviewService.REVIEW_WINDOW). */
    private static final long RESPONSE_WINDOW_HOURS = 14L * 24L;  // 336h

    /** Reminder fires when deadline is this many hours away (leading edge). */
    private static final long REMINDER_LEAD_HOURS = 24L;

    /** Reminder fires when deadline is at most this many hours away (trailing edge). */
    private static final long REMINDER_TRAIL_HOURS = 48L;

    private final ReviewRepository reviewRepo;
    private final NotificationPublisher publisher;
    private final Clock clock;

    @Scheduled(cron = "${slpa.review-response-reminder.cron:0 5 9 * * *}", zone = "UTC")
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Deadline window: responseDeadline ∈ [now+24h, now+48h]
        // responseDeadline = revealedAt + 336h
        // → revealedAt ∈ [now+24h-336h, now+48h-336h] = [now-312h, now-288h]
        OffsetDateTime revealedAtStart = now.minusHours(RESPONSE_WINDOW_HOURS - REMINDER_TRAIL_HOURS);
        OffsetDateTime revealedAtEnd   = now.minusHours(RESPONSE_WINDOW_HOURS - REMINDER_LEAD_HOURS);

        List<Review> rows = reviewRepo.findReviewsApproachingResponseClose(revealedAtStart, revealedAtEnd);

        for (Review r : rows) {
            OffsetDateTime responseDeadline = r.getRevealedAt().plusHours(RESPONSE_WINDOW_HOURS);
            publisher.reviewResponseWindowClosing(
                    r.getReviewee().getId(),
                    r.getId(),
                    r.getAuction().getId(),
                    r.getAuction().getTitle(),
                    responseDeadline);
            r.setResponseClosingReminderSentAt(now);
            reviewRepo.save(r);
        }

        log.info("ReviewResponseWindowClosingScheduler: reminded {} review(s)", rows.size());
    }
}
