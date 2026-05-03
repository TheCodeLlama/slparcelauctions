package com.slparcelauctions.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.review.broadcast.ReviewBroadcastPublisher;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewSubmitRequest;
import com.slparcelauctions.backend.review.exception.ReviewAlreadySubmittedException;
import com.slparcelauctions.backend.review.exception.ReviewIneligibleException;
import com.slparcelauctions.backend.review.exception.ReviewWindowClosedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for {@link ReviewService#submit(Long, User, ReviewSubmitRequest)}.
 * All dependencies are mocked; the happy path and every 4xx branch are
 * exercised. The simultaneous-reveal path is intentionally NOT tested here —
 * Task 1 does not ship it; Task 2 will.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceSubmitTest {

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
    User stranger;
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
        stranger = User.builder().email("stranger@example.com").passwordHash("x").build();
        stranger.setId(99L);

        auction = Auction.builder()
                .title("Lakefront")
                .seller(seller)
                .slParcelUuid(UUID.randomUUID())
                .winnerUserId(winner.getId())
                .photos(List.of())
                .build();
        auction.setId(555L);

        // Escrow: COMPLETED 1 day ago — well within the 14-day window.
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

    @Test
    void submit_rejectsWhenAuctionNotFound() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.submit(555L, winner, new ReviewSubmitRequest(5, null)))
                .isInstanceOf(AuctionNotFoundException.class);

        verify(reviewRepo, never()).save(any());
    }

    @Test
    void submit_rejectsWhenEscrowMissing() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.submit(555L, winner, new ReviewSubmitRequest(5, null)))
                .isInstanceOf(ReviewIneligibleException.class)
                .hasMessageContaining("no escrow");

        verify(reviewRepo, never()).save(any());
    }

    @Test
    void submit_rejectsWhenEscrowNotCompleted() {
        escrow.setState(EscrowState.TRANSFER_PENDING);
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() ->
                service.submit(555L, winner, new ReviewSubmitRequest(5, null)))
                .isInstanceOf(ReviewIneligibleException.class)
                .hasMessageContaining("escrow has completed");

        verify(reviewRepo, never()).save(any());
    }

    @Test
    void submit_rejectsAfterFourteenDayWindow() {
        escrow.setCompletedAt(NOW.minusDays(15));
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() ->
                service.submit(555L, winner, new ReviewSubmitRequest(5, null)))
                .isInstanceOf(ReviewWindowClosedException.class);

        verify(reviewRepo, never()).save(any());
    }

    @Test
    void submit_rejectsCallerNotParty() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() ->
                service.submit(555L, stranger, new ReviewSubmitRequest(5, null)))
                .isInstanceOf(ReviewIneligibleException.class)
                .hasMessageContaining("seller or winner");

        verify(reviewRepo, never()).save(any());
    }

    @Test
    void submit_rejectsWhenAuctionHasNoWinner() {
        auction.setWinnerUserId(null);
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));

        assertThatThrownBy(() ->
                service.submit(555L, seller, new ReviewSubmitRequest(5, null)))
                .isInstanceOf(ReviewIneligibleException.class)
                .hasMessageContaining("no recorded winner");
    }

    @Test
    void submit_rejectsDuplicate() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        Review existing = Review.builder().auction(auction).reviewer(winner)
                .reviewee(seller).reviewedRole(ReviewedRole.SELLER)
                .rating(4).visible(true).build();
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, 20L))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                service.submit(555L, winner, new ReviewSubmitRequest(5, null)))
                .isInstanceOf(ReviewAlreadySubmittedException.class);

        verify(reviewRepo, never()).save(any());
    }

    @Test
    void submit_winnerReviewingSeller_persistsPending() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, 20L))
                .thenReturn(Optional.empty());
        when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(1_001L);
            r.setSubmittedAt(NOW);
            return r;
        });
        when(responseRepo.findByReviewId(1_001L)).thenReturn(Optional.empty());

        ReviewDto dto = service.submit(555L, winner,
                new ReviewSubmitRequest(5, "Smooth transfer"));

        ArgumentCaptor<Review> cap = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepo).save(cap.capture());
        Review saved = cap.getValue();
        assertThat(saved.getReviewer()).isEqualTo(winner);
        assertThat(saved.getReviewee()).isEqualTo(seller);
        assertThat(saved.getReviewedRole()).isEqualTo(ReviewedRole.SELLER);
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getText()).isEqualTo("Smooth transfer");
        assertThat(saved.getVisible()).isFalse();

        // DTO visible flag + pending flag for the reviewer's own view.
        assertThat(dto.visible()).isFalse();
        assertThat(dto.pending()).isTrue();
        assertThat(dto.reviewedRole()).isEqualTo(ReviewedRole.SELLER);
        assertThat(dto.rating()).isEqualTo(5);
        assertThat(dto.text()).isEqualTo("Smooth transfer");
        // No photos on the seeded auction — primary photo URL is null.
        assertThat(dto.auctionPrimaryPhotoUrl()).isNull();
    }

    @Test
    void submit_sellerReviewingWinner_persistsPending_withBuyerRole() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(userRepo.findById(20L)).thenReturn(Optional.of(winner));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, 10L))
                .thenReturn(Optional.empty());
        when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(1_002L);
            r.setSubmittedAt(NOW);
            return r;
        });
        when(responseRepo.findByReviewId(1_002L)).thenReturn(Optional.empty());

        ReviewDto dto = service.submit(555L, seller,
                new ReviewSubmitRequest(4, null));

        ArgumentCaptor<Review> cap = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepo).save(cap.capture());
        Review saved = cap.getValue();
        assertThat(saved.getReviewer()).isEqualTo(seller);
        assertThat(saved.getReviewee()).isEqualTo(winner);
        assertThat(saved.getReviewedRole()).isEqualTo(ReviewedRole.BUYER);
        assertThat(saved.getRating()).isEqualTo(4);
        assertThat(saved.getText()).isNull();
        assertThat(saved.getVisible()).isFalse();

        assertThat(dto.reviewedRole()).isEqualTo(ReviewedRole.BUYER);
        assertThat(dto.revieweeId()).isEqualTo(winner.getId());
        assertThat(dto.pending()).isTrue();
    }

    @Test
    void submit_acceptsAtExactWindowBoundary() {
        // Boundary: exactly at escrow.completedAt + 14 days = now. isAfter
        // returns false at the boundary (strict inequality), so submit
        // succeeds. A submit at now + 1 nanosecond would be rejected.
        escrow.setCompletedAt(NOW.minusDays(14));
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, 20L))
                .thenReturn(Optional.empty());
        when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(1_003L);
            r.setSubmittedAt(NOW);
            return r;
        });
        when(responseRepo.findByReviewId(1_003L)).thenReturn(Optional.empty());

        ReviewDto dto = service.submit(555L, winner, new ReviewSubmitRequest(5, null));

        assertThat(dto.id()).isEqualTo(1_003L);
    }
}
