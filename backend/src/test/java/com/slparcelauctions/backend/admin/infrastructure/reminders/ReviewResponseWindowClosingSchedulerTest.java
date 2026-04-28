package com.slparcelauctions.backend.admin.infrastructure.reminders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.review.Review;
import com.slparcelauctions.backend.review.ReviewRepository;
import com.slparcelauctions.backend.review.ReviewedRole;
import com.slparcelauctions.backend.user.User;

/**
 * Unit coverage for {@link ReviewResponseWindowClosingScheduler#run}.
 *
 * <p>The scheduler queries
 * {@link ReviewRepository#findReviewsApproachingResponseClose} with the
 * {@code revealedAt} equivalents of {@code [now+24h, now+48h]} deadline bounds
 * and fires {@link NotificationPublisher#reviewResponseWindowClosing} for each
 * result, then stamps {@code responseClosingReminderSentAt}.
 *
 * <p>Response window = 14 days (336h) from {@code revealedAt}. Reminder fires
 * when {@code responseDeadline = revealedAt + 336h ∈ [now+24h, now+48h]},
 * which means {@code revealedAt ∈ [now-312h, now-288h]}.
 */
@ExtendWith(MockitoExtension.class)
class ReviewResponseWindowClosingSchedulerTest {

    private static final long RESPONSE_WINDOW_HOURS = 14L * 24L;  // 336h

    @Mock ReviewRepository reviewRepo;
    @Mock NotificationPublisher publisher;

    ReviewResponseWindowClosingScheduler scheduler;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-27T09:05:00Z"), ZoneOffset.UTC);
        scheduler = new ReviewResponseWindowClosingScheduler(reviewRepo, publisher, fixed);
    }

    @Test
    void noOp_whenNoReviewsInWindow() {
        when(reviewRepo.findReviewsApproachingResponseClose(any(), any()))
                .thenReturn(List.of());

        scheduler.run();

        verify(publisher, never()).reviewResponseWindowClosing(
                anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void passesCorrectRevealedAtBoundsToRepository() {
        when(reviewRepo.findReviewsApproachingResponseClose(any(), any()))
                .thenReturn(List.of());

        scheduler.run();

        OffsetDateTime now = OffsetDateTime.now(fixed);
        // Reminder fires when deadline ∈ [now+24h, now+48h]
        // deadline = revealedAt + 336h → revealedAt ∈ [now-312h, now-288h]
        ArgumentCaptor<OffsetDateTime> startCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> endCap   = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reviewRepo).findReviewsApproachingResponseClose(
                startCap.capture(), endCap.capture());

        assertThat(startCap.getValue()).isEqualTo(now.minusHours(RESPONSE_WINDOW_HOURS - 48L));
        assertThat(endCap.getValue()).isEqualTo(now.minusHours(RESPONSE_WINDOW_HOURS - 24L));
    }

    @Test
    void firesReminderAndStampsReminderSentAt_forEachReviewInWindow() {
        OffsetDateTime revealedAt = OffsetDateTime.now(fixed).minusHours(300);
        Review review = buildVisibleReview(42L, 10L, 20L, "Parcel X", revealedAt);
        when(reviewRepo.findReviewsApproachingResponseClose(any(), any()))
                .thenReturn(List.of(review));

        scheduler.run();

        OffsetDateTime expectedDeadline = revealedAt.plusHours(RESPONSE_WINDOW_HOURS);
        verify(publisher).reviewResponseWindowClosing(
                eq(20L),           // reviewee id
                eq(42L),           // review id
                eq(10L),           // auction id
                eq("Parcel X"),
                eq(expectedDeadline));

        ArgumentCaptor<Review> savedCaptor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepo).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getResponseClosingReminderSentAt()).isNotNull();
    }

    @Test
    void firesReminderForAllReviewsInWindow() {
        OffsetDateTime revealedAt1 = OffsetDateTime.now(fixed).minusHours(295);
        OffsetDateTime revealedAt2 = OffsetDateTime.now(fixed).minusHours(310);
        Review r1 = buildVisibleReview(1L, 100L, 200L, "Parcel A", revealedAt1);
        Review r2 = buildVisibleReview(2L, 101L, 201L, "Parcel B", revealedAt2);
        when(reviewRepo.findReviewsApproachingResponseClose(any(), any()))
                .thenReturn(List.of(r1, r2));

        scheduler.run();

        verify(publisher).reviewResponseWindowClosing(
                eq(200L), eq(1L), eq(100L), eq("Parcel A"), any());
        verify(publisher).reviewResponseWindowClosing(
                eq(201L), eq(2L), eq(101L), eq("Parcel B"), any());
    }

    // ---------------------------------------------------------------------------
    // Seed helpers
    // ---------------------------------------------------------------------------

    private Review buildVisibleReview(long reviewId, long auctionId, long revieweeId,
                                       String parcelTitle, OffsetDateTime revealedAt) {
        User reviewee = User.builder().id(revieweeId).build();

        Auction auction = Auction.builder()
                .id(auctionId)
                .title(parcelTitle)
                .build();

        return Review.builder()
                .id(reviewId)
                .auction(auction)
                .reviewer(User.builder().id(999L).build())
                .reviewee(reviewee)
                .reviewedRole(ReviewedRole.SELLER)
                .rating(4)
                .visible(true)
                .revealedAt(revealedAt)
                .build();
    }
}
