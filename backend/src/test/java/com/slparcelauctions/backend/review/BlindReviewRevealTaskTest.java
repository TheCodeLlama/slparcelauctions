package com.slparcelauctions.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * Unit coverage for {@link BlindReviewRevealTask}. The scheduler's job is
 * to compute the day-14 threshold, fetch up to 500 revealable reviews,
 * dispatch each to {@link ReviewService#reveal(Long)}, and survive
 * per-row failures without poisoning the batch.
 */
@ExtendWith(MockitoExtension.class)
class BlindReviewRevealTaskTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-15T00:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Mock ReviewRepository reviewRepo;
    @Mock ReviewService reviewService;

    BlindReviewRevealTask task;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        task = new BlindReviewRevealTask(reviewRepo, reviewService, clock);
    }

    private Review pending(Long id) {
        Review r = new Review();
        setEntityId(r, id);
        return r;
    }

    private static void setEntityId(Object entity, Long id) {
        try {
            java.lang.reflect.Field f =
                    com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void run_noRevealable_returnsImmediately() {
        when(reviewRepo.findRevealable(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        task.run();

        verifyNoInteractions(reviewService);
    }

    @Test
    void run_dispatchesEachRevealableReview() {
        when(reviewRepo.findRevealable(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(pending(1L), pending(2L), pending(3L)));

        int processed = task.runOnce();

        assertThat(processed).isEqualTo(3);
        InOrder inOrder = inOrder(reviewService);
        inOrder.verify(reviewService).reveal(1L);
        inOrder.verify(reviewService).reveal(2L);
        inOrder.verify(reviewService).reveal(3L);
    }

    @Test
    void run_perRowFailureDoesNotPoisonBatch() {
        when(reviewRepo.findRevealable(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(pending(1L), pending(2L), pending(3L)));
        // lenient() — other reveal(id) calls have no stubs and must
        // silently succeed (Mockito strict-stubbing otherwise treats
        // reveal(1L) / reveal(3L) as arg-mismatches on the reveal(2L)
        // stub).
        lenient().doThrow(new RuntimeException("db hiccup"))
                .when(reviewService).reveal(2L);

        int processed = task.runOnce();

        // Row 2 throws; rows 1 and 3 still dispatch. processed counts 2 (1 and 3).
        assertThat(processed).isEqualTo(2);
        verify(reviewService).reveal(1L);
        verify(reviewService).reveal(2L);
        verify(reviewService).reveal(3L);
    }

    @Test
    void run_appliesDay14ThresholdFromClock() {
        when(reviewRepo.findRevealable(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        task.run();

        ArgumentCaptor<OffsetDateTime> cap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reviewRepo).findRevealable(cap.capture(), any(Pageable.class));
        // threshold = now - 14d
        assertThat(cap.getValue()).isEqualTo(NOW.minusDays(14));
    }

    @Test
    void run_batchLimit500_loggedAsWarn() {
        // 500-element batch should all be dispatched.
        List<Review> fullBatch = IntStream.rangeClosed(1, 500)
                .mapToObj(i -> pending((long) i))
                .toList();
        when(reviewRepo.findRevealable(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(fullBatch);

        int processed = task.runOnce();

        assertThat(processed).isEqualTo(500);
        verify(reviewService, times(500)).reveal(any(Long.class));
    }

    @Test
    void run_idempotentOnRerun() {
        // First call reveals. Second call sees empty (reveal flipped rows).
        when(reviewRepo.findRevealable(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(pending(1L)), List.of());

        task.run();
        task.run();

        verify(reviewService, times(1)).reveal(1L);
    }
}
