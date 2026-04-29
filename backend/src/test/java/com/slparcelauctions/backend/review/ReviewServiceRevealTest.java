package com.slparcelauctions.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.review.broadcast.ReviewBroadcastPublisher;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewSubmitRequest;
import com.slparcelauctions.backend.review.exception.ReviewNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for {@link ReviewService#reveal(Long)} +
 * {@link ReviewService#recomputeAggregates(User, ReviewedRole)} + the
 * simultaneous-reveal branch in {@code submit}.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceRevealTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-01T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Mock ReviewRepository reviewRepo;
    @Mock ReviewResponseRepository responseRepo;
    @Mock ReviewFlagRepository flagRepo;
    @Mock AuctionRepository auctionRepo;
    @Mock EscrowRepository escrowRepo;
    @Mock UserRepository userRepo;
    @Mock ReviewBroadcastPublisher broadcastPublisher;
    @Mock NotificationPublisher notificationPublisher;

    ReviewService service;

    User seller;
    User winner;
    Auction auction;
    Escrow escrow;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new ReviewService(reviewRepo, responseRepo, flagRepo, auctionRepo,
                escrowRepo, userRepo, broadcastPublisher, notificationPublisher, clock);

        seller = User.builder().email("seller@example.com").passwordHash("x").build();
        seller.setId(10L);
        seller.setDisplayName("Sally");
        winner = User.builder().email("winner@example.com").passwordHash("x").build();
        winner.setId(20L);
        winner.setDisplayName("Willy");

        Parcel parcel = Parcel.builder().snapshotUrl("https://snap/1.jpg").build();
        auction = Auction.builder()
                .title("Lakefront")
                .seller(seller)
                .parcel(parcel)
                .winnerUserId(winner.getId())
                .photos(List.of())
                .build();
        auction.setId(555L);

        escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.COMPLETED)
                .completedAt(NOW.minusDays(1))
                .finalBidAmount(1_000L)
                .commissionAmt(50L)
                .payoutAmt(950L)
                .paymentDeadline(NOW.minusDays(3))
                .build();
    }

    private Review pendingReview(Long id, User reviewer, User reviewee, ReviewedRole role, int rating) {
        Review r = Review.builder()
                .auction(auction)
                .reviewer(reviewer)
                .reviewee(reviewee)
                .reviewedRole(role)
                .rating(rating)
                .visible(false)
                .build();
        r.setId(id);
        return r;
    }

    @Test
    void reveal_flipsVisibilityAndStampsRevealedAt() {
        Review r = pendingReview(100L, winner, seller, ReviewedRole.SELLER, 5);
        when(reviewRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(reviewRepo.computeSellerAggregate(seller.getId()))
                .thenReturn(new Aggregate(new BigDecimal("5.00"), 1));

        service.reveal(100L);

        assertThat(r.getVisible()).isTrue();
        assertThat(r.getRevealedAt()).isEqualTo(NOW);
        verify(reviewRepo).save(r);
        verify(broadcastPublisher).publishReviewRevealed(any(ReviewRevealedEnvelope.class));
    }

    @Test
    void reveal_recomputesSellerAggregate() {
        Review r = pendingReview(101L, winner, seller, ReviewedRole.SELLER, 3);
        when(reviewRepo.findByIdForUpdate(101L)).thenReturn(Optional.of(r));
        when(reviewRepo.computeSellerAggregate(seller.getId()))
                .thenReturn(new Aggregate(new BigDecimal("4.25"), 4));

        service.reveal(101L);

        assertThat(seller.getAvgSellerRating()).isEqualByComparingTo(new BigDecimal("4.25"));
        assertThat(seller.getTotalSellerReviews()).isEqualTo(4);
        verify(userRepo).save(seller);
    }

    @Test
    void reveal_recomputesBuyerAggregate_whenReviewedRoleIsBuyer() {
        Review r = pendingReview(102L, seller, winner, ReviewedRole.BUYER, 4);
        when(reviewRepo.findByIdForUpdate(102L)).thenReturn(Optional.of(r));
        when(reviewRepo.computeBuyerAggregate(winner.getId()))
                .thenReturn(new Aggregate(new BigDecimal("4.00"), 1));

        service.reveal(102L);

        assertThat(winner.getAvgBuyerRating()).isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat(winner.getTotalBuyerReviews()).isEqualTo(1);
    }

    @Test
    void reveal_idempotent_whenAlreadyVisible() {
        Review r = pendingReview(103L, winner, seller, ReviewedRole.SELLER, 5);
        r.setVisible(true);
        r.setRevealedAt(NOW.minusDays(2));
        when(reviewRepo.findByIdForUpdate(103L)).thenReturn(Optional.of(r));

        service.reveal(103L);

        // Early-return path — no save, no broadcast, no recompute.
        verify(reviewRepo, never()).save(any(Review.class));
        verify(userRepo, never()).save(any(User.class));
        verify(broadcastPublisher, never())
                .publishReviewRevealed(any(ReviewRevealedEnvelope.class));
        // revealedAt unchanged
        assertThat(r.getRevealedAt()).isEqualTo(NOW.minusDays(2));
    }

    @Test
    void reveal_throwsWhenReviewNotFound() {
        when(reviewRepo.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        try {
            service.reveal(999L);
        } catch (ReviewNotFoundException e) {
            assertThat(e.getMessage()).contains("999");
            return;
        }
        throw new AssertionError("expected ReviewNotFoundException");
    }

    @Test
    void reveal_envelopeCarriesRevealedAtFromClock() {
        Review r = pendingReview(104L, winner, seller, ReviewedRole.SELLER, 5);
        when(reviewRepo.findByIdForUpdate(104L)).thenReturn(Optional.of(r));
        when(reviewRepo.computeSellerAggregate(seller.getId()))
                .thenReturn(new Aggregate(new BigDecimal("5.00"), 1));

        service.reveal(104L);

        ArgumentCaptor<ReviewRevealedEnvelope> cap =
                ArgumentCaptor.forClass(ReviewRevealedEnvelope.class);
        verify(broadcastPublisher).publishReviewRevealed(cap.capture());
        ReviewRevealedEnvelope env = cap.getValue();
        assertThat(env.type()).isEqualTo("REVIEW_REVEALED");
        assertThat(env.auctionId()).isEqualTo(555L);
        assertThat(env.reviewId()).isEqualTo(104L);
        assertThat(env.reviewerId()).isEqualTo(20L);
        assertThat(env.revieweeId()).isEqualTo(10L);
        assertThat(env.reviewedRole()).isEqualTo(ReviewedRole.SELLER);
        assertThat(env.revealedAt()).isEqualTo(NOW);
    }

    @Test
    void submit_simultaneousReveal_flipsBothRowsAndRecomputesBoth() {
        // Counterparty (seller) already submitted a pending review about the winner.
        Review sellerPending = pendingReview(200L, seller, winner, ReviewedRole.BUYER, 4);
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, winner.getId()))
                .thenReturn(Optional.empty());
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, seller.getId()))
                .thenReturn(Optional.of(sellerPending));
        when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
            Review saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(201L);
            saved.setSubmittedAt(NOW);
            return saved;
        });
        when(reviewRepo.computeSellerAggregate(seller.getId()))
                .thenReturn(new Aggregate(new BigDecimal("5.00"), 1));
        when(reviewRepo.computeBuyerAggregate(winner.getId()))
                .thenReturn(new Aggregate(new BigDecimal("4.00"), 1));
        when(responseRepo.findByReviewId(any())).thenReturn(Optional.empty());

        ReviewDto dto = service.submit(555L, winner, new ReviewSubmitRequest(5, "gg"));

        // Both reviews should be visible now.
        assertThat(sellerPending.getVisible()).isTrue();
        assertThat(sellerPending.getRevealedAt()).isEqualTo(NOW);

        // Capture the winner's newly saved review (visible + revealedAt stamped).
        ArgumentCaptor<Review> cap = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepo, times(3)).save(cap.capture());
        Review winnerReview = cap.getAllValues().stream()
                .filter(r -> r.getReviewer().getId().equals(winner.getId()))
                .findFirst().orElseThrow();
        assertThat(winnerReview.getVisible()).isTrue();
        assertThat(winnerReview.getRevealedAt()).isEqualTo(NOW);

        // Both aggregates recomputed.
        verify(reviewRepo).computeSellerAggregate(seller.getId());
        verify(reviewRepo).computeBuyerAggregate(winner.getId());

        // Both envelopes broadcast.
        verify(broadcastPublisher, times(2))
                .publishReviewRevealed(any(ReviewRevealedEnvelope.class));

        // Viewer sees their own DTO as visible post-reveal.
        assertThat(dto.visible()).isTrue();
    }

    @Test
    void submit_noCounterparty_doesNotReveal() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, winner.getId()))
                .thenReturn(Optional.empty());
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, seller.getId()))
                .thenReturn(Optional.empty());
        when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
            Review saved = inv.getArgument(0);
            saved.setId(300L);
            saved.setSubmittedAt(NOW);
            return saved;
        });
        when(responseRepo.findByReviewId(300L)).thenReturn(Optional.empty());

        ReviewDto dto = service.submit(555L, winner, new ReviewSubmitRequest(5, "gg"));

        assertThat(dto.visible()).isFalse();
        verify(broadcastPublisher, never())
                .publishReviewRevealed(any(ReviewRevealedEnvelope.class));
    }

    @Test
    void submit_counterpartyAlreadyVisible_doesNotRevealAgain() {
        // Edge case: counterparty review exists and is already visible —
        // the scheduler previously flipped it. We should NOT re-flip or
        // re-broadcast. The new row still lands as visible=false until
        // its own day-14 sweep.
        Review sellerAlreadyRevealed = pendingReview(400L, seller, winner, ReviewedRole.BUYER, 4);
        sellerAlreadyRevealed.setVisible(true);
        sellerAlreadyRevealed.setRevealedAt(NOW.minusDays(1));

        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, winner.getId()))
                .thenReturn(Optional.empty());
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, seller.getId()))
                .thenReturn(Optional.of(sellerAlreadyRevealed));
        when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
            Review saved = inv.getArgument(0);
            saved.setId(401L);
            saved.setSubmittedAt(NOW);
            return saved;
        });
        when(responseRepo.findByReviewId(401L)).thenReturn(Optional.empty());

        ReviewDto dto = service.submit(555L, winner, new ReviewSubmitRequest(5, "gg"));

        assertThat(dto.visible()).isFalse();
        // Not re-revealed.
        assertThat(sellerAlreadyRevealed.getRevealedAt()).isEqualTo(NOW.minusDays(1));
        verify(broadcastPublisher, never())
                .publishReviewRevealed(any(ReviewRevealedEnvelope.class));
    }
}
