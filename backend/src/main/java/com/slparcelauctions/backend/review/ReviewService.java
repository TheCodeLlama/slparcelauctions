package com.slparcelauctions.backend.review;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionPhoto;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewSubmitRequest;
import com.slparcelauctions.backend.review.exception.ReviewAlreadySubmittedException;
import com.slparcelauctions.backend.review.exception.ReviewIneligibleException;
import com.slparcelauctions.backend.review.exception.ReviewWindowClosedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Blind-reveal review service (Epic 08 sub-spec 1 §4.1). Task 1 ships
 * the {@link #submit(Long, User, ReviewSubmitRequest)} entry point only —
 * the simultaneous-reveal branch, the day-14 scheduler path, aggregate
 * recompute, and the read endpoints all land in Task 2. The Task 2 code
 * will add {@code reveal(Long)} + {@code recomputeAggregates(...)} +
 * list endpoints without re-touching the write path here.
 *
 * <p>Every submit opens a fresh JPA transaction. Eligibility is checked
 * in a fixed order so the 422 / 409 / 404 responses are stable for the
 * frontend: auction-not-found → escrow-missing / not-COMPLETED →
 * window-closed → caller-not-a-party → duplicate-review.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    /**
     * Hard cutoff for the review window. Reviews submitted strictly after
     * {@code escrow.completedAt + 14 days} are rejected with
     * {@link ReviewWindowClosedException} (422). The day-14 scheduler
     * sweep (Task 2) uses the same duration so a review submitted at
     * {@code T+13d23h59m} is accepted but the sweep that fires at
     * {@code T+14d} reveals it.
     */
    public static final Duration REVIEW_WINDOW = Duration.ofDays(14);

    private final ReviewRepository reviewRepo;
    private final ReviewResponseRepository responseRepo;
    private final AuctionRepository auctionRepo;
    private final EscrowRepository escrowRepo;
    private final UserRepository userRepo;
    private final Clock clock;

    /**
     * Persist a new {@link Review} for the caller against the given
     * auction. Runs every eligibility check before the INSERT so the
     * caller sees a clean 422 / 409 instead of a constraint-violation
     * stack trace, but the DB's {@code uq_reviews_auction_reviewer}
     * unique constraint is the last-line-of-defence under concurrent
     * submits by the same reviewer (duplicate INSERT → DB error →
     * handled by the slice's exception advice chain).
     *
     * <p>Task 1 persists {@code visible=false} and returns. Task 2 adds
     * the simultaneous-reveal branch: if the counterparty already
     * submitted, both rows are flipped visible inside the same tx and a
     * {@code REVIEW_REVEALED} envelope is broadcast on afterCommit.
     */
    @Transactional
    public ReviewDto submit(Long auctionId, User caller, ReviewSubmitRequest req) {
        Auction auction = auctionRepo.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        Escrow escrow = escrowRepo.findByAuctionId(auctionId)
                .orElseThrow(() -> new ReviewIneligibleException(
                        "Auction has no escrow; reviews open only after the sale closes."));

        if (escrow.getState() != EscrowState.COMPLETED) {
            throw new ReviewIneligibleException(
                    "Reviews are only accepted once the escrow has completed.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime windowCloses = escrow.getCompletedAt().plus(REVIEW_WINDOW);
        if (now.isAfter(windowCloses)) {
            throw new ReviewWindowClosedException(
                    "The 14-day review window for this auction has closed.");
        }

        Long sellerId = auction.getSeller().getId();
        Long winnerUserId = auction.getWinnerUserId();
        Long callerId = caller.getId();

        if (winnerUserId == null) {
            // Defensive — a COMPLETED escrow must have a winner; this
            // branch indicates data corruption worth surfacing rather
            // than quietly 403-ing.
            throw new ReviewIneligibleException(
                    "Auction has no recorded winner; reviews are unavailable.");
        }
        boolean callerIsSeller = callerId.equals(sellerId);
        boolean callerIsWinner = callerId.equals(winnerUserId);
        if (!callerIsSeller && !callerIsWinner) {
            throw new ReviewIneligibleException(
                    "Only the seller or winner of this auction can submit a review.");
        }

        if (reviewRepo.findByAuctionIdAndReviewerId(auctionId, callerId).isPresent()) {
            throw new ReviewAlreadySubmittedException(
                    "You have already submitted a review for this auction.");
        }

        // Derive reviewee + reviewedRole from caller's role. Caller =
        // seller → reviewing the winner's BUYER behaviour; caller =
        // winner → reviewing the seller's SELLER behaviour. Auction
        // carries only the winner's numeric id (not a managed User
        // reference), so the seller-submitting-about-buyer branch
        // loads the winner User explicitly.
        User reviewee;
        ReviewedRole reviewedRole;
        if (callerIsSeller) {
            reviewee = userRepo.findById(winnerUserId)
                    .orElseThrow(() -> new UserNotFoundException(winnerUserId));
            reviewedRole = ReviewedRole.BUYER;
        } else {
            reviewee = auction.getSeller();
            reviewedRole = ReviewedRole.SELLER;
        }

        Review mine = reviewRepo.save(Review.builder()
                .auction(auction)
                .reviewer(caller)
                .reviewee(reviewee)
                .reviewedRole(reviewedRole)
                .rating(req.rating())
                .text(req.text())
                .visible(false)
                .build());

        log.info("Review {} submitted on auction {}: reviewer={}, reviewedRole={}, visible=false",
                mine.getId(), auctionId, callerId, reviewedRole);

        // Task 2 will add the simultaneous-reveal branch here (counterparty
        // already submitted → flip both visible, recompute aggregates,
        // broadcast REVIEW_REVEALED). Task 1 intentionally leaves this
        // out; see plan §Task 1 Step 21 note.

        // Surface the reviewer's pending submission to themselves so
        // the UI can render the "waiting for the other party" state.
        return ReviewDto.of(mine, callerId,
                responseRepo.findByReviewId(mine.getId()),
                resolvePrimaryPhotoUrl(auction));
    }

    /**
     * Mirrors the listing-detail primary-photo fallback: first photo by
     * {@code sortOrder}, else the parcel's {@code snapshotUrl}. Returned
     * URL is the public {@code /api/v1/auctions/{id}/photos/{photoId}/bytes}
     * proxy path so renderers do not need to know S3 object keys.
     */
    private String resolvePrimaryPhotoUrl(Auction auction) {
        return auction.getPhotos().stream()
                .sorted(Comparator.comparing(AuctionPhoto::getSortOrder))
                .findFirst()
                .map(p -> "/api/v1/auctions/" + auction.getId() + "/photos/" + p.getId() + "/bytes")
                .orElseGet(() -> auction.getParcel() == null ? null : auction.getParcel().getSnapshotUrl());
    }
}
