package com.slparcelauctions.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.review.broadcast.ReviewBroadcastPublisher;
import com.slparcelauctions.backend.review.dto.AuctionReviewsResponse;
import com.slparcelauctions.backend.review.dto.PendingReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for the list paths on {@link ReviewService} —
 * {@code listForAuction}, {@code listForUser}, {@code listPendingForCaller}.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceListTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-01T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Mock ReviewRepository reviewRepo;
    @Mock ReviewResponseRepository responseRepo;
    @Mock AuctionRepository auctionRepo;
    @Mock EscrowRepository escrowRepo;
    @Mock UserRepository userRepo;
    @Mock ReviewBroadcastPublisher broadcastPublisher;

    ReviewService service;

    User seller;
    User winner;
    User stranger;
    Auction auction;
    Escrow escrow;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new ReviewService(reviewRepo, responseRepo, auctionRepo,
                escrowRepo, userRepo, broadcastPublisher, clock);

        seller = User.builder().email("seller@example.com").passwordHash("x").build();
        seller.setId(10L);
        seller.setDisplayName("Sally");
        winner = User.builder().email("winner@example.com").passwordHash("x").build();
        winner.setId(20L);
        winner.setDisplayName("Willy");
        stranger = User.builder().email("x@example.com").passwordHash("x").build();
        stranger.setId(99L);

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

    private Review makeVisible(Long id, User reviewer, User reviewee, ReviewedRole role, int rating) {
        Review r = Review.builder()
                .auction(auction)
                .reviewer(reviewer)
                .reviewee(reviewee)
                .reviewedRole(role)
                .rating(rating)
                .visible(true)
                .build();
        r.setId(id);
        r.setSubmittedAt(NOW.minusDays(10));
        r.setRevealedAt(NOW.minusDays(5));
        return r;
    }

    private Review makePending(Long id, User reviewer, User reviewee, ReviewedRole role, int rating) {
        Review r = Review.builder()
                .auction(auction)
                .reviewer(reviewer)
                .reviewee(reviewee)
                .reviewedRole(role)
                .rating(rating)
                .visible(false)
                .build();
        r.setId(id);
        r.setSubmittedAt(NOW.minusHours(2));
        return r;
    }

    @Test
    void listForAuction_throwsWhenAuctionNotFound() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listForAuction(555L, null))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void listForAuction_anonymousCaller_seesOnlyVisibleReviews() {
        Review r = makeVisible(1L, winner, seller, ReviewedRole.SELLER, 5);
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndVisibleTrue(555L)).thenReturn(List.of(r));
        when(responseRepo.findByReviewId(1L)).thenReturn(Optional.empty());

        AuctionReviewsResponse resp = service.listForAuction(555L, null);

        assertThat(resp.reviews()).hasSize(1);
        assertThat(resp.reviews().get(0).id()).isEqualTo(1L);
        assertThat(resp.myPendingReview()).isNull();
        assertThat(resp.canReview()).isFalse();
        assertThat(resp.windowClosesAt()).isNull();
    }

    @Test
    void listForAuction_partyCaller_canReviewWithOpenWindow() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndVisibleTrue(555L)).thenReturn(List.of());
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, winner.getId()))
                .thenReturn(Optional.empty());

        AuctionReviewsResponse resp = service.listForAuction(555L, winner);

        assertThat(resp.reviews()).isEmpty();
        assertThat(resp.canReview()).isTrue();
        assertThat(resp.myPendingReview()).isNull();
        assertThat(resp.windowClosesAt()).isEqualTo(escrow.getCompletedAt()
                .plus(ReviewService.REVIEW_WINDOW));
    }

    @Test
    void listForAuction_partyCaller_withPendingReview_exposesPending() {
        Review mine = makePending(77L, winner, seller, ReviewedRole.SELLER, 5);
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndVisibleTrue(555L)).thenReturn(List.of());
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, winner.getId()))
                .thenReturn(Optional.of(mine));
        when(responseRepo.findByReviewId(77L)).thenReturn(Optional.empty());

        AuctionReviewsResponse resp = service.listForAuction(555L, winner);

        assertThat(resp.canReview()).isFalse(); // already submitted
        assertThat(resp.myPendingReview()).isNotNull();
        assertThat(resp.myPendingReview().id()).isEqualTo(77L);
        assertThat(resp.myPendingReview().pending()).isTrue();
    }

    @Test
    void listForAuction_partyCaller_windowClosed_cannotReview() {
        escrow.setCompletedAt(NOW.minusDays(15));
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndVisibleTrue(555L)).thenReturn(List.of());
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, winner.getId()))
                .thenReturn(Optional.empty());

        AuctionReviewsResponse resp = service.listForAuction(555L, winner);

        assertThat(resp.canReview()).isFalse();
        assertThat(resp.windowClosesAt()).isBefore(NOW);
    }

    @Test
    void listForAuction_strangerAuthenticated_noPendingNoCanReview() {
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndVisibleTrue(555L)).thenReturn(List.of());

        AuctionReviewsResponse resp = service.listForAuction(555L, stranger);

        assertThat(resp.canReview()).isFalse();
        assertThat(resp.myPendingReview()).isNull();
        assertThat(resp.windowClosesAt()).isNull();
    }

    @Test
    void listForAuction_escrowNotCompleted_noEnrichment() {
        escrow.setState(EscrowState.TRANSFER_PENDING);
        when(auctionRepo.findById(555L)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(555L)).thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndVisibleTrue(555L)).thenReturn(List.of());

        AuctionReviewsResponse resp = service.listForAuction(555L, winner);

        assertThat(resp.canReview()).isFalse();
        assertThat(resp.myPendingReview()).isNull();
        assertThat(resp.windowClosesAt()).isNull();
    }

    @Test
    void listForUser_returnsPagedReviews() {
        Review r = makeVisible(5L, winner, seller, ReviewedRole.SELLER, 5);
        Page<Review> pageResult = new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1);
        when(reviewRepo.findByRevieweeIdAndReviewedRoleAndVisibleTrue(
                eq(seller.getId()), eq(ReviewedRole.SELLER), any(Pageable.class)))
                .thenReturn(pageResult);
        when(responseRepo.findByReviewId(5L)).thenReturn(Optional.empty());

        Page<ReviewDto> result = service.listForUser(seller.getId(),
                ReviewedRole.SELLER, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(5L);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void listPendingForCaller_returnsOpenEscrowsWithoutReviews() {
        when(escrowRepo.findCompletedEscrowsForUser(eq(winner.getId()), any()))
                .thenReturn(List.of(escrow));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, winner.getId()))
                .thenReturn(Optional.empty());

        List<PendingReviewDto> result = service.listPendingForCaller(winner);

        assertThat(result).hasSize(1);
        PendingReviewDto p = result.get(0);
        assertThat(p.auctionId()).isEqualTo(555L);
        assertThat(p.viewerRole()).isEqualTo(ReviewedRole.BUYER);
        // counterparty is the seller for a winner-viewer
        assertThat(p.counterpartyId()).isEqualTo(seller.getId());
        assertThat(p.counterpartyDisplayName()).isEqualTo("Sally");
        // window closes escrow.completedAt + 14d; now is 1 day after completion
        // → 13 days remaining → 13*24 = 312 hours.
        assertThat(p.hoursRemaining()).isEqualTo(13L * 24L);
    }

    @Test
    void listPendingForCaller_filtersOutAuctionsAlreadyReviewed() {
        Review already = makePending(88L, winner, seller, ReviewedRole.SELLER, 5);
        when(escrowRepo.findCompletedEscrowsForUser(eq(winner.getId()), any()))
                .thenReturn(List.of(escrow));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, winner.getId()))
                .thenReturn(Optional.of(already));

        List<PendingReviewDto> result = service.listPendingForCaller(winner);

        assertThat(result).isEmpty();
    }

    @Test
    void listPendingForCaller_sellerView_counterpartyIsWinner() {
        when(escrowRepo.findCompletedEscrowsForUser(eq(seller.getId()), any()))
                .thenReturn(List.of(escrow));
        when(reviewRepo.findByAuctionIdAndReviewerId(555L, seller.getId()))
                .thenReturn(Optional.empty());
        when(userRepo.findById(winner.getId())).thenReturn(Optional.of(winner));

        List<PendingReviewDto> result = service.listPendingForCaller(seller);

        assertThat(result).hasSize(1);
        PendingReviewDto p = result.get(0);
        assertThat(p.viewerRole()).isEqualTo(ReviewedRole.SELLER);
        assertThat(p.counterpartyId()).isEqualTo(winner.getId());
        assertThat(p.counterpartyDisplayName()).isEqualTo("Willy");
    }
}
