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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.review.broadcast.ReviewBroadcastPublisher;
import com.slparcelauctions.backend.review.dto.ReviewFlagRequest;
import com.slparcelauctions.backend.review.dto.ReviewResponseDto;
import com.slparcelauctions.backend.review.dto.ReviewResponseSubmitRequest;
import com.slparcelauctions.backend.review.exception.ReviewFlagAlreadyExistsException;
import com.slparcelauctions.backend.review.exception.ReviewNotFoundException;
import com.slparcelauctions.backend.review.exception.ReviewResponseAlreadyExistsException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for {@link ReviewService#respondTo(Long, User,
 * ReviewResponseSubmitRequest)} and {@link ReviewService#flag(Long, User,
 * ReviewFlagRequest)}. Every branch is exercised: happy path, 404
 * review-not-found, 403 wrong-caller, 409 duplicate. The
 * {@code flagCount++} side-effect is asserted via ArgumentCaptor on the
 * review save.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceActionTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-01T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Mock ReviewRepository reviewRepo;
    @Mock ReviewResponseRepository responseRepo;
    @Mock ReviewFlagRepository flagRepo;
    @Mock AuctionRepository auctionRepo;
    @Mock EscrowRepository escrowRepo;
    @Mock UserRepository userRepo;
    @Mock ReviewBroadcastPublisher broadcastPublisher;

    ReviewService service;

    User seller;
    User winner;
    User stranger;
    Auction auction;
    Review review; // Winner reviewing seller — reviewee=seller, reviewer=winner

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new ReviewService(reviewRepo, responseRepo, flagRepo, auctionRepo,
                escrowRepo, userRepo, broadcastPublisher, clock);

        seller = User.builder().email("seller@example.com").passwordHash("x").build();
        seller.setId(10L);
        seller.setDisplayName("Sally");
        winner = User.builder().email("winner@example.com").passwordHash("x").build();
        winner.setId(20L);
        winner.setDisplayName("Willy");
        stranger = User.builder().email("stranger@example.com").passwordHash("x").build();
        stranger.setId(99L);
        stranger.setDisplayName("Sam");

        Parcel parcel = Parcel.builder().snapshotUrl("https://snap/1.jpg").build();
        auction = Auction.builder()
                .title("Lakefront")
                .seller(seller)
                .parcel(parcel)
                .winnerUserId(winner.getId())
                .photos(List.of())
                .build();
        auction.setId(555L);

        review = Review.builder()
                .auction(auction)
                .reviewer(winner)
                .reviewee(seller)
                .reviewedRole(ReviewedRole.SELLER)
                .rating(5)
                .text("Smooth")
                .visible(true)
                .flagCount(0)
                .build();
        review.setId(1_001L);
    }

    // ---------- respondTo ----------

    @Test
    void respond_rejectsWhenReviewNotFound() {
        when(reviewRepo.findById(1_001L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.respondTo(1_001L, seller, new ReviewResponseSubmitRequest("Thanks!")))
                .isInstanceOf(ReviewNotFoundException.class);

        verify(responseRepo, never()).save(any());
    }

    @Test
    void respond_rejectsWhenCallerIsNotReviewee() {
        when(reviewRepo.findById(1_001L)).thenReturn(Optional.of(review));

        // Reviewer attempting to respond to their own review is NOT the
        // reviewee — 403.
        assertThatThrownBy(() ->
                service.respondTo(1_001L, winner, new ReviewResponseSubmitRequest("Thanks!")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("reviewee");

        verify(responseRepo, never()).save(any());
    }

    @Test
    void respond_rejectsWhenCallerIsThirdParty() {
        when(reviewRepo.findById(1_001L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() ->
                service.respondTo(1_001L, stranger, new ReviewResponseSubmitRequest("Hi")))
                .isInstanceOf(AccessDeniedException.class);

        verify(responseRepo, never()).save(any());
    }

    @Test
    void respond_rejectsWhenResponseAlreadyExists() {
        when(reviewRepo.findById(1_001L)).thenReturn(Optional.of(review));
        when(responseRepo.existsByReviewId(1_001L)).thenReturn(true);

        assertThatThrownBy(() ->
                service.respondTo(1_001L, seller, new ReviewResponseSubmitRequest("Thanks!")))
                .isInstanceOf(ReviewResponseAlreadyExistsException.class);

        verify(responseRepo, never()).save(any());
    }

    @Test
    void respond_persistsAndReturnsDto() {
        when(reviewRepo.findById(1_001L)).thenReturn(Optional.of(review));
        when(responseRepo.existsByReviewId(1_001L)).thenReturn(false);
        when(responseRepo.save(any(ReviewResponse.class))).thenAnswer(inv -> {
            ReviewResponse r = inv.getArgument(0);
            r.setId(9_001L);
            r.setCreatedAt(NOW);
            return r;
        });

        ReviewResponseDto dto = service.respondTo(1_001L, seller,
                new ReviewResponseSubmitRequest("Appreciate it!"));

        ArgumentCaptor<ReviewResponse> cap = ArgumentCaptor.forClass(ReviewResponse.class);
        verify(responseRepo).save(cap.capture());
        ReviewResponse saved = cap.getValue();
        assertThat(saved.getReview()).isEqualTo(review);
        assertThat(saved.getText()).isEqualTo("Appreciate it!");

        assertThat(dto.id()).isEqualTo(9_001L);
        assertThat(dto.text()).isEqualTo("Appreciate it!");
        assertThat(dto.createdAt()).isEqualTo(NOW);
    }

    // ---------- flag ----------

    @Test
    void flag_rejectsWhenReviewNotFound() {
        when(reviewRepo.findByIdForUpdate(1_001L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.flag(1_001L, stranger,
                        new ReviewFlagRequest(ReviewFlagReason.SPAM, null)))
                .isInstanceOf(ReviewNotFoundException.class);

        verify(flagRepo, never()).save(any());
    }

    @Test
    void flag_rejectsWhenCallerIsReviewer() {
        when(reviewRepo.findByIdForUpdate(1_001L)).thenReturn(Optional.of(review));

        // Winner wrote the review — can't flag own review.
        assertThatThrownBy(() ->
                service.flag(1_001L, winner,
                        new ReviewFlagRequest(ReviewFlagReason.SPAM, null)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("own review");

        verify(flagRepo, never()).save(any());
    }

    @Test
    void flag_rejectsWhenDuplicate() {
        when(reviewRepo.findByIdForUpdate(1_001L)).thenReturn(Optional.of(review));
        when(flagRepo.existsByReviewIdAndFlaggerId(1_001L, stranger.getId())).thenReturn(true);

        assertThatThrownBy(() ->
                service.flag(1_001L, stranger,
                        new ReviewFlagRequest(ReviewFlagReason.ABUSIVE, null)))
                .isInstanceOf(ReviewFlagAlreadyExistsException.class);

        verify(flagRepo, never()).save(any());
    }

    @Test
    void flag_persistsAndIncrementsFlagCount_byReviewee() {
        when(reviewRepo.findByIdForUpdate(1_001L)).thenReturn(Optional.of(review));
        when(flagRepo.existsByReviewIdAndFlaggerId(1_001L, seller.getId())).thenReturn(false);
        when(flagRepo.save(any(ReviewFlag.class))).thenAnswer(inv -> {
            ReviewFlag f = inv.getArgument(0);
            f.setId(8_001L);
            return f;
        });

        service.flag(1_001L, seller,
                new ReviewFlagRequest(ReviewFlagReason.SPAM, null));

        ArgumentCaptor<ReviewFlag> flagCap = ArgumentCaptor.forClass(ReviewFlag.class);
        verify(flagRepo).save(flagCap.capture());
        ReviewFlag saved = flagCap.getValue();
        assertThat(saved.getReview()).isEqualTo(review);
        assertThat(saved.getFlagger()).isEqualTo(seller);
        assertThat(saved.getReason()).isEqualTo(ReviewFlagReason.SPAM);
        assertThat(saved.getElaboration()).isNull();

        // flagCount incremented on the managed entity and persisted.
        ArgumentCaptor<Review> reviewCap = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepo).save(reviewCap.capture());
        assertThat(reviewCap.getValue().getFlagCount()).isEqualTo(1);
    }

    @Test
    void flag_persistsOtherReasonWithElaboration_byThirdParty() {
        when(reviewRepo.findByIdForUpdate(1_001L)).thenReturn(Optional.of(review));
        when(flagRepo.existsByReviewIdAndFlaggerId(1_001L, stranger.getId())).thenReturn(false);
        when(flagRepo.save(any(ReviewFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(1_001L, stranger,
                new ReviewFlagRequest(ReviewFlagReason.OTHER, "Contains a scam link"));

        ArgumentCaptor<ReviewFlag> flagCap = ArgumentCaptor.forClass(ReviewFlag.class);
        verify(flagRepo).save(flagCap.capture());
        ReviewFlag saved = flagCap.getValue();
        assertThat(saved.getReason()).isEqualTo(ReviewFlagReason.OTHER);
        assertThat(saved.getElaboration()).isEqualTo("Contains a scam link");
        assertThat(saved.getFlagger()).isEqualTo(stranger);
    }

    @Test
    void flag_incrementsFromExistingNonZeroFlagCount() {
        review.setFlagCount(3);
        when(reviewRepo.findByIdForUpdate(1_001L)).thenReturn(Optional.of(review));
        when(flagRepo.existsByReviewIdAndFlaggerId(1_001L, stranger.getId())).thenReturn(false);
        when(flagRepo.save(any(ReviewFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(1_001L, stranger,
                new ReviewFlagRequest(ReviewFlagReason.FALSE_INFO, null));

        ArgumentCaptor<Review> reviewCap = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepo).save(reviewCap.capture());
        assertThat(reviewCap.getValue().getFlagCount()).isEqualTo(4);
    }
}
