package com.slparcelauctions.backend.review;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionPhoto;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.review.broadcast.ReviewBroadcastPublisher;
import com.slparcelauctions.backend.review.dto.AuctionReviewsResponse;
import com.slparcelauctions.backend.review.dto.PendingReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewFlagRequest;
import com.slparcelauctions.backend.review.dto.ReviewResponseDto;
import com.slparcelauctions.backend.review.dto.ReviewResponseSubmitRequest;
import com.slparcelauctions.backend.review.dto.ReviewSubmitRequest;
import com.slparcelauctions.backend.review.exception.ReviewAlreadySubmittedException;
import com.slparcelauctions.backend.review.exception.ReviewFlagAlreadyExistsException;
import com.slparcelauctions.backend.review.exception.ReviewIneligibleException;
import com.slparcelauctions.backend.review.exception.ReviewNotFoundException;
import com.slparcelauctions.backend.review.exception.ReviewResponseAlreadyExistsException;
import com.slparcelauctions.backend.review.exception.ReviewWindowClosedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Blind-reveal review service (Epic 08 sub-spec 1 §4.1 / §5). Task 1
 * shipped submit; Task 2 fills in the rest — reveal + aggregate recompute
 * + WS broadcast + list endpoints. The simultaneous-reveal branch in
 * {@link #submit(Long, User, ReviewSubmitRequest)} fires when the
 * counterparty already submitted: both reviews flip to {@code visible=true}
 * inside the same transaction, aggregates are recomputed for both
 * reviewees, and a {@code REVIEW_REVEALED} envelope fires on afterCommit
 * for each.
 *
 * <p>Every submit / reveal opens a fresh JPA transaction. Eligibility is
 * checked in a fixed order so the 422 / 409 / 404 responses are stable
 * for the frontend: auction-not-found → escrow-missing / not-COMPLETED →
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
     * sweep uses the same duration so a review submitted at
     * {@code T+13d23h59m} is accepted but the sweep that fires at
     * {@code T+14d} reveals it.
     */
    public static final Duration REVIEW_WINDOW = Duration.ofDays(14);

    private final ReviewRepository reviewRepo;
    private final ReviewResponseRepository responseRepo;
    private final ReviewFlagRepository flagRepo;
    private final AuctionRepository auctionRepo;
    private final EscrowRepository escrowRepo;
    private final UserRepository userRepo;
    private final ReviewBroadcastPublisher broadcastPublisher;
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
     * <p>If the counterparty already submitted a pending review, both
     * rows flip to {@code visible=true} inside this transaction and a
     * {@code REVIEW_REVEALED} envelope fires on afterCommit for each —
     * aggregates are recomputed for both reviewees in the same tx so
     * the UI's refetch sees a consistent {@code User.avg*Rating} /
     * {@code total*Reviews} snapshot.
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

        // Simultaneous-reveal branch (spec §5 Q9 blind-on-fact). If the
        // counterparty already submitted a pending review, flip both
        // rows visible inside the same transaction so aggregates + WS
        // broadcast fire atomically. Counterparty = the OTHER party
        // (seller XOR winner) — distinct from {@code reviewee}, which
        // is whoever the caller is reviewing. If the caller is the
        // seller they are reviewing the winner (buyer-role), so the
        // counterparty's own submission — their review of the seller —
        // has reviewerId == winnerUserId.
        Long counterpartyId = callerIsSeller ? winnerUserId : sellerId;
        Optional<Review> counterparty = reviewRepo
                .findByAuctionIdAndReviewerId(auctionId, counterpartyId);
        if (counterparty.isPresent()
                && !Boolean.TRUE.equals(counterparty.get().getVisible())) {
            revealNow(mine, now);
            revealNow(counterparty.get(), now);
            recomputeAggregates(mine.getReviewee(), mine.getReviewedRole());
            recomputeAggregates(counterparty.get().getReviewee(),
                    counterparty.get().getReviewedRole());
            registerBroadcast(mine);
            registerBroadcast(counterparty.get());
        }

        // Surface the reviewer's pending submission to themselves so
        // the UI can render the "waiting for the other party" state.
        return ReviewDto.of(mine, callerId,
                responseRepo.findByReviewId(mine.getId()),
                resolvePrimaryPhotoUrl(auction));
    }

    /**
     * Flip a single review from {@code visible=false} to {@code true},
     * recompute the reviewee's aggregate, and queue a
     * {@code REVIEW_REVEALED} envelope on afterCommit. Idempotent — a
     * second call on an already-visible review is a no-op so the
     * scheduler can retry after a crash without overwriting
     * {@code revealedAt} or double-broadcasting. Takes a pessimistic
     * write lock to prevent two schedulers (or a scheduler and a
     * simultaneous-submit) from racing on the same row.
     */
    @Transactional
    public void reveal(Long reviewId) {
        Review r = reviewRepo.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (Boolean.TRUE.equals(r.getVisible())) {
            return; // idempotent early return
        }

        revealNow(r, OffsetDateTime.now(clock));
        recomputeAggregates(r.getReviewee(), r.getReviewedRole());
        registerBroadcast(r);

        log.info("Review {} revealed: auction={}, reviewee={}, role={}",
                r.getId(), r.getAuction().getId(), r.getReviewee().getId(),
                r.getReviewedRole());
    }

    /**
     * Full recompute of {@code avg*Rating} + {@code total*Reviews} over
     * the reviewee's currently-visible reviews in the given role. Runs
     * after every reveal — a full recompute (not incremental) because
     * the scheduler can catch up on multiple reveals across a single
     * reviewee and the running-total math would drift if a flag moderation
     * action ever marks a row invisible. {@code Aggregate} normalises the
     * null-AVG case (COUNT=0 → avg=null) so the DECIMAL(3,2) column
     * accepts the write.
     */
    @Transactional
    public void recomputeAggregates(User reviewee, ReviewedRole role) {
        if (role == ReviewedRole.SELLER) {
            Aggregate agg = reviewRepo.computeSellerAggregate(reviewee.getId());
            reviewee.setAvgSellerRating(agg.avg());
            reviewee.setTotalSellerReviews(agg.count());
        } else {
            Aggregate agg = reviewRepo.computeBuyerAggregate(reviewee.getId());
            reviewee.setAvgBuyerRating(agg.avg());
            reviewee.setTotalBuyerReviews(agg.count());
        }
        userRepo.save(reviewee);
    }

    /**
     * Auction-scoped review list. Public — anon callers see the visible
     * reviews only. When the caller is authenticated AND a party to the
     * completed escrow, the response enriches with their own pending
     * submission, a {@code canReview} bool, and the absolute
     * {@code windowClosesAt} timestamp so the UI can render the "14 day"
     * countdown without a second round-trip.
     */
    @Transactional(readOnly = true)
    public AuctionReviewsResponse listForAuction(Long auctionId, User caller) {
        Auction auction = auctionRepo.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        Optional<Escrow> maybeEscrow = escrowRepo.findByAuctionId(auctionId);

        List<Review> visible = reviewRepo.findByAuctionIdAndVisibleTrue(auctionId);
        String primaryPhoto = resolvePrimaryPhotoUrl(auction);
        Long callerId = caller == null ? null : caller.getId();

        List<ReviewDto> visibleDtos = visible.stream()
                .map(r -> ReviewDto.of(r, callerId,
                        responseRepo.findByReviewId(r.getId()), primaryPhoto))
                .toList();

        ReviewDto myPending = null;
        boolean canReview = false;
        OffsetDateTime windowCloses = null;

        if (caller != null && maybeEscrow.isPresent()
                && maybeEscrow.get().getState() == EscrowState.COMPLETED) {
            Escrow e = maybeEscrow.get();
            Long sellerId = auction.getSeller().getId();
            Long winnerId = auction.getWinnerUserId();
            boolean isParty = callerId.equals(sellerId)
                    || (winnerId != null && callerId.equals(winnerId));
            if (isParty) {
                windowCloses = e.getCompletedAt().plus(REVIEW_WINDOW);
                OffsetDateTime now = OffsetDateTime.now(clock);
                boolean windowOpen = !now.isAfter(windowCloses);
                Optional<Review> mine = reviewRepo
                        .findByAuctionIdAndReviewerId(auctionId, callerId);
                canReview = windowOpen && mine.isEmpty();
                myPending = mine
                        .filter(r -> !Boolean.TRUE.equals(r.getVisible()))
                        .map(r -> ReviewDto.of(r, callerId,
                                responseRepo.findByReviewId(r.getId()), primaryPhoto))
                        .orElse(null);
            }
        }

        return new AuctionReviewsResponse(visibleDtos, myPending, canReview, windowCloses);
    }

    /**
     * Paginated public list of a user's visible reviews in the given
     * role. Sort order is {@code revealedAt DESC} so the newest reveal
     * is first on each page. Viewer gating is disabled (passing
     * {@code null} to {@link ReviewDto#of}) because the profile-page use
     * case is third-party browsing — the reviewer always sees their own
     * pending reviews via the dashboard's pending-review endpoint, not
     * here.
     */
    @Transactional(readOnly = true)
    public Page<ReviewDto> listForUser(Long userId, ReviewedRole role, Pageable page) {
        Pageable sorted = PageRequest.of(page.getPageNumber(), page.getPageSize(),
                Sort.by(Sort.Direction.DESC, "revealedAt"));
        Page<Review> reviews = reviewRepo
                .findByRevieweeIdAndReviewedRoleAndVisibleTrue(userId, role, sorted);
        return reviews.map(r -> ReviewDto.of(r, null,
                responseRepo.findByReviewId(r.getId()),
                resolvePrimaryPhotoUrl(r.getAuction())));
    }

    /**
     * Every completed escrow where the caller is seller or winner, the
     * 14-day window is still open, and the caller has not yet submitted
     * a review. Used by the dashboard's "waiting for your review" tab.
     * {@link PendingReviewDto#of} takes {@code now} from this method's
     * Clock so a frozen-clock test can assert {@code hoursRemaining}
     * deterministically. The counterparty is resolved here (the seller
     * is a managed {@link User} ref on {@code Auction.seller}; the
     * winner is only a numeric id via {@code Auction.winnerUserId}, so
     * we load it explicitly when the caller is the seller).
     */
    @Transactional(readOnly = true)
    public List<PendingReviewDto> listPendingForCaller(User caller) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime threshold = now.minus(REVIEW_WINDOW);
        return escrowRepo.findCompletedEscrowsForUser(caller.getId(), threshold)
                .stream()
                .filter(e -> reviewRepo
                        .findByAuctionIdAndReviewerId(e.getAuction().getId(), caller.getId())
                        .isEmpty())
                .map(e -> PendingReviewDto.of(e, caller,
                        resolveCounterparty(e, caller), now))
                .toList();
    }

    /**
     * Persist a one-time reviewee response to a review (Epic 08 sub-spec
     * 1 §4.3). Only the {@code review.reviewee} may respond — every other
     * caller (including the reviewer and any third party) is rejected as
     * 403. A second response on the same review is rejected as 409; the
     * {@code review_id}-unique FK on {@code review_responses} is the
     * last-line-of-defence under concurrent double-clicks, mapped to the
     * same 409 by {@code ReviewExceptionHandler}'s constraint-name
     * fallback.
     */
    @Transactional
    public ReviewResponseDto respondTo(Long reviewId, User caller,
                                       ReviewResponseSubmitRequest req) {
        Review r = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (!r.getReviewee().getId().equals(caller.getId())) {
            throw new AccessDeniedException("Only the reviewee can respond to a review.");
        }
        if (responseRepo.existsByReviewId(reviewId)) {
            throw new ReviewResponseAlreadyExistsException(reviewId);
        }
        ReviewResponse resp = responseRepo.save(ReviewResponse.builder()
                .review(r)
                .text(req.text())
                .build());
        log.info("Review {} response persisted: reviewee={}, responseId={}",
                reviewId, caller.getId(), resp.getId());
        return ReviewResponseDto.of(resp);
    }

    /**
     * Persist a moderation flag against a review (Epic 08 sub-spec 1
     * §4.4). The review's reviewer cannot flag their own review (403);
     * every other authenticated user may flag at most one time per
     * review. Side-effect: {@code review.flagCount} is incremented inside
     * the same transaction so the admin moderation queue (Epic 10) sees a
     * consistent count. No auto-hide — flags are a moderation signal, not
     * censorship. The {@code uq_review_flags_review_flagger} unique
     * constraint backs the pre-check; a racing flag by the same user is
     * mapped to the same 409 by {@code ReviewExceptionHandler}'s
     * constraint-name fallback.
     *
     * <p>The review row is loaded with a pessimistic write lock
     * ({@code findByIdForUpdate}) so two concurrent flags on the same
     * review do not race on {@code flagCount}.
     */
    @Transactional
    public void flag(Long reviewId, User caller, ReviewFlagRequest req) {
        Review r = reviewRepo.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (r.getReviewer().getId().equals(caller.getId())) {
            throw new AccessDeniedException("You cannot flag your own review.");
        }
        if (flagRepo.existsByReviewIdAndFlaggerId(reviewId, caller.getId())) {
            throw new ReviewFlagAlreadyExistsException(reviewId);
        }
        flagRepo.save(ReviewFlag.builder()
                .review(r)
                .flagger(caller)
                .reason(req.reason())
                .elaboration(req.elaboration())
                .build());
        r.setFlagCount(r.getFlagCount() + 1);
        reviewRepo.save(r);
        log.info("Review {} flagged by user {} (reason={}); flagCount={}",
                reviewId, caller.getId(), req.reason(), r.getFlagCount());
    }

    private User resolveCounterparty(Escrow e, User viewer) {
        Long sellerId = e.getAuction().getSeller().getId();
        if (sellerId.equals(viewer.getId())) {
            Long winnerUserId = e.getAuction().getWinnerUserId();
            if (winnerUserId == null) {
                return null;
            }
            return userRepo.findById(winnerUserId).orElse(null);
        }
        return e.getAuction().getSeller();
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

    private void revealNow(Review r, OffsetDateTime now) {
        r.setVisible(true);
        r.setRevealedAt(now);
        reviewRepo.save(r);
    }

    /**
     * Register an afterCommit callback that publishes the
     * {@code REVIEW_REVEALED} envelope. The envelope snapshot is built
     * inside the tx — {@code r.getRevealedAt()} is the value we just
     * stamped — so the afterCommit callback does not re-read a possibly
     * detached entity. If no transaction is active (e.g. unit test with
     * a spied service), the publisher is called immediately; this matches
     * the escrow publisher's behaviour and keeps slice tests simple.
     */
    private void registerBroadcast(Review r) {
        ReviewRevealedEnvelope envelope = ReviewRevealedEnvelope.of(r);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishReviewRevealed(envelope);
                        }
                    });
        } else {
            broadcastPublisher.publishReviewRevealed(envelope);
        }
    }
}
