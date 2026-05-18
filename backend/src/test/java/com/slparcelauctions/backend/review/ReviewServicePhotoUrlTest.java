package com.slparcelauctions.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionPhoto;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.review.broadcast.ReviewBroadcastPublisher;
import com.slparcelauctions.backend.review.dto.AuctionReviewsResponse;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Regression for the prod bug: {@link ReviewService#resolvePrimaryPhotoUrl}
 * built the review-list thumbnail as
 * {@code "/api/v1/auctions/" + auction.getId() + "/photos/" + photo.getId()
 * + "/bytes"} — a route that does not exist (only POST/DELETE/PATCH live
 * under {@code /api/v1/auctions/{id}/photos}) and fed numeric DB ids. The
 * auction-detail review list and the profile Reviews tab 404'd. The fix
 * routes through {@code PhotoUrl} so every review thumbnail is the canonical
 * {@code /api/v1/photos/{publicId}}.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServicePhotoUrlTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-01T10:00:00Z");
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Mock ReviewRepository reviewRepo;
    @Mock ReviewResponseRepository responseRepo;
    @Mock ReviewFlagRepository flagRepo;
    @Mock AuctionRepository auctionRepo;
    @Mock EscrowRepository escrowRepo;
    @Mock UserRepository userRepo;
    @Mock ReviewBroadcastPublisher broadcastPublisher;
    @Mock NotificationPublisher notificationPublisher;
    @Mock ApplicationEventPublisher eventPublisher;

    ReviewService service;

    User seller;
    User winner;
    Auction auction;
    Escrow escrow;
    AuctionPhoto photo;

    private static final long AUCTION_NUMERIC_ID = 555L;
    private static final long PHOTO_NUMERIC_ID = 4242L;
    private final UUID photoPublicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new ReviewService(reviewRepo, responseRepo, flagRepo, auctionRepo,
                escrowRepo, userRepo, broadcastPublisher, notificationPublisher,
                eventPublisher, clock);

        seller = User.builder().id(10L).email("seller@example.com")
                .username("seller").passwordHash("x").displayName("Sally").build();
        winner = User.builder().id(20L).email("winner@example.com")
                .username("winner").passwordHash("x").displayName("Willy").build();

        photo = AuctionPhoto.builder().publicId(photoPublicId).sortOrder(0).build();
        setEntityId(photo, PHOTO_NUMERIC_ID);

        auction = Auction.builder()
                .title("Lakefront")
                .seller(seller)
                .slParcelUuid(UUID.randomUUID())
                .winnerUserId(winner.getId())
                .photos(List.of(photo))
                .build();
        setEntityId(auction, AUCTION_NUMERIC_ID);

        escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.COMPLETED)
                .completedAt(NOW.minusDays(1))
                .finalBidAmount(1_000L)
                .commissionAmt(50L)
                .payoutAmt(950L)
                .build();
    }

    private Review visibleReview(long id) {
        Review r = Review.builder()
                .auction(auction)
                .reviewer(winner)
                .reviewee(seller)
                .reviewedRole(ReviewedRole.SELLER)
                .rating(5)
                .visible(true)
                .build();
        setEntityId(r, id);
        r.setSubmittedAt(NOW.minusDays(10));
        r.setRevealedAt(NOW.minusDays(5));
        return r;
    }

    private void assertCanonicalPhotoUrl(String url) {
        assertThat(url).isEqualTo("/api/v1/photos/" + photoPublicId);
        // Exact prod-bug assertions — all would FAIL under the old code.
        assertThat(url).doesNotContain("/auctions/");
        assertThat(url).doesNotContain("/bytes");
        assertThat(url).doesNotContain("/photos/" + PHOTO_NUMERIC_ID);
        assertThat(url).doesNotContain("/" + AUCTION_NUMERIC_ID + "/");
    }

    @Test
    void listForAuction_thumbnailUsesFlatPhotosEndpointWithPublicId() {
        Review r = visibleReview(1L);
        when(auctionRepo.findById(AUCTION_NUMERIC_ID)).thenReturn(Optional.of(auction));
        when(escrowRepo.findByAuctionId(AUCTION_NUMERIC_ID))
                .thenReturn(Optional.of(escrow));
        when(reviewRepo.findByAuctionIdAndVisibleTrue(AUCTION_NUMERIC_ID))
                .thenReturn(List.of(r));
        when(responseRepo.findByReviewId(1L)).thenReturn(Optional.empty());

        AuctionReviewsResponse resp = service.listForAuction(AUCTION_NUMERIC_ID, null);

        assertThat(resp.reviews()).hasSize(1);
        assertCanonicalPhotoUrl(resp.reviews().get(0).auctionPrimaryPhotoUrl());
    }

    @Test
    void listForUser_thumbnailUsesFlatPhotosEndpointWithPublicId() {
        Review r = visibleReview(5L);
        Page<Review> pageResult =
                new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1);
        when(reviewRepo.findByRevieweeIdAndReviewedRoleAndVisibleTrue(
                eq(seller.getId()), eq(ReviewedRole.SELLER), any(Pageable.class)))
                .thenReturn(pageResult);
        when(responseRepo.findByReviewId(5L)).thenReturn(Optional.empty());

        Page<ReviewDto> result = service.listForUser(seller.getId(),
                ReviewedRole.SELLER, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertCanonicalPhotoUrl(result.getContent().get(0).auctionPrimaryPhotoUrl());
    }

    private static void setEntityId(Object entity, long id) {
        try {
            java.lang.reflect.Field f = com.slparcelauctions.backend.common
                    .BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
