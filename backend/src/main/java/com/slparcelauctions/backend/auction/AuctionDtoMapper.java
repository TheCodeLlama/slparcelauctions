package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.dto.AuctionPhotoResponse;
import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse.SellerSummary;
import com.slparcelauctions.backend.auction.dto.PublicAuctionStatus;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;
import com.slparcelauctions.backend.user.SellerCompletionRateMapper;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Auction → DTO conversion. The {@link #toPublicStatus(AuctionStatus)} method
 * is the linchpin of the privacy guarantee: the 4 terminal statuses collapse
 * to ENDED, and the 4 pre-ACTIVE statuses throw IllegalStateException because
 * the controller is responsible for returning 404 to non-sellers before this
 * mapper is called.
 *
 * <p>Photos are resolved inline via {@code photoRepo.findByAuctionIdOrderBySortOrderAsc}.
 * This introduces an N+1 pattern on {@code listMine} (one photo query per
 * auction); for sub-spec 1 the N-per-seller count is low and the optimization
 * is documented as a follow-up (see DEFERRED_WORK.md).
 *
 * <p>Escrow enrichment: single-auction entry points
 * ({@link #toPublicResponse(Auction)} / {@link #toSellerResponse(Auction,
 * PendingVerification)}) resolve the optional {@link Escrow} via
 * {@link EscrowRepository#findByAuctionId(Long)} on demand. Batch callers
 * should pre-load escrows into a map and use the
 * {@code toPublicResponse(Auction, Escrow)} /
 * {@code toSellerResponse(Auction, PendingVerification, Escrow)} overloads
 * to avoid N+1 queries.
 */
@Component
@RequiredArgsConstructor
public class AuctionDtoMapper {

    private final AuctionPhotoRepository photoRepo;
    private final EscrowRepository escrowRepo;
    private final UserRepository userRepo;

    public PublicAuctionStatus toPublicStatus(AuctionStatus internal) {
        return switch (internal) {
            case ACTIVE -> PublicAuctionStatus.ACTIVE;
            case ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING,
                    COMPLETED, CANCELLED, EXPIRED, DISPUTED, SUSPENDED -> PublicAuctionStatus.ENDED;
            case DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED ->
                    throw new IllegalStateException(
                            "Non-public status leaked to toPublicStatus: " + internal
                                    + ". The controller should have 404'd before calling the mapper.");
        };
    }

    public PublicAuctionResponse toPublicResponse(Auction a) {
        return toPublicResponse(a, resolveEscrow(a));
    }

    /**
     * Batch-safe overload — pass the already-loaded escrow (or null when the
     * auction is ACTIVE / pre-ENDED) to avoid the fallback fetch inside
     * {@link #toPublicResponse(Auction)}.
     */
    public PublicAuctionResponse toPublicResponse(Auction a, Escrow escrow) {
        boolean hasReserve = a.getReservePrice() != null;
        boolean reserveMet = hasReserve && a.getCurrentBid() != null
                && a.getCurrentBid() >= a.getReservePrice();
        return new PublicAuctionResponse(
                a.getPublicId(),
                a.getSeller().getPublicId(),
                a.getTitle(),
                ParcelResponse.from(a.getParcelSnapshot()),
                toPublicStatus(a.getStatus()),
                a.getVerificationTier(),
                a.getStartingBid(),
                hasReserve,
                reserveMet,
                a.getBuyNowPrice(),
                a.getCurrentBid(),
                a.getBidCount(),
                currentHighBid(a),
                bidderCount(a),
                a.getDurationHours(),
                a.getSnipeProtect(),
                a.getSnipeWindowMin(),
                a.getStartsAt(),
                a.getEndsAt(),
                a.getOriginalEndsAt(),
                a.getSellerDesc(),
                tagList(a),
                photoList(a),
                sellerSummary(a.getSeller()),
                escrow == null ? null : escrow.getState(),
                escrow == null ? null : escrow.getTransferConfirmedAt());
    }

    public SellerAuctionResponse toSellerResponse(Auction a, PendingVerification pending) {
        return toSellerResponse(a, pending, resolveEscrow(a));
    }

    /**
     * Batch-safe overload — pass the already-loaded escrow (or null) to avoid
     * the fallback fetch inside {@link #toSellerResponse(Auction,
     * PendingVerification)}.
     */
    public SellerAuctionResponse toSellerResponse(Auction a, PendingVerification pending, Escrow escrow) {
        return new SellerAuctionResponse(
                a.getPublicId(),
                a.getSeller().getPublicId(),
                a.getTitle(),
                ParcelResponse.from(a.getParcelSnapshot()),
                a.getStatus(),
                a.getVerificationMethod(),
                a.getVerificationTier(),
                pending,
                a.getVerificationNotes(),
                a.getStartingBid(),
                a.getReservePrice(),
                a.getBuyNowPrice(),
                a.getCurrentBid(),
                a.getBidCount(),
                currentHighBid(a),
                bidderCount(a),
                resolveWinnerPublicId(a),
                a.getDurationHours(),
                a.getSnipeProtect(),
                a.getSnipeWindowMin(),
                a.getStartsAt(),
                a.getEndsAt(),
                a.getOriginalEndsAt(),
                a.getSellerDesc(),
                tagList(a),
                photoList(a),
                a.getListingFeePaid(),
                a.getListingFeeAmt(),
                a.getListingFeeTxn(),
                a.getListingFeePaidAt(),
                a.getCommissionRate(),
                a.getCommissionAmt(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                escrow == null ? null : escrow.getState(),
                escrow == null ? null : escrow.getTransferConfirmedAt());
    }

    /**
     * Returns the current high bid as a {@link BigDecimal}, or null when no
     * bids have been placed. Epic 04 will populate real values; until then
     * {@code currentBid} is the entity default of 0, which we project as null
     * so consumers can render "—" rather than "L$0".
     */
    private BigDecimal currentHighBid(Auction a) {
        Long cb = a.getCurrentBid();
        if (cb == null || cb == 0L) {
            return null;
        }
        return BigDecimal.valueOf(cb);
    }

    /**
     * Returns the bidder count as a {@link Long}, defaulting to 0 for
     * historical rows with a null {@code bidCount}.
     */
    private Long bidderCount(Auction a) {
        Integer bc = a.getBidCount();
        return bc == null ? 0L : bc.longValue();
    }

    private List<ParcelTagResponse> tagList(Auction a) {
        return a.getTags().stream()
                .sorted(Comparator.comparing(t -> t.getSortOrder() == null ? 0 : t.getSortOrder()))
                .map(ParcelTagResponse::from)
                .toList();
    }

    private List<AuctionPhotoResponse> photoList(Auction a) {
        if (a.getId() == null) {
            return List.of();
        }
        return photoRepo.findByAuctionIdOrderBySortOrderAsc(a.getId()).stream()
                .map(AuctionPhotoResponse::from)
                .toList();
    }

    /**
     * Builds the {@link SellerSummary} block for {@link PublicAuctionResponse}.
     * Returns {@code null} only when the seller association is unset (defensive
     * — every persisted auction has a non-null seller). Avatar URL points at
     * the existing {@code GET /api/v1/users/{id}/avatar/256} endpoint, which
     * already serves cached + placeholder avatars. {@code completionRate} is
     * delegated to {@link SellerCompletionRateMapper#compute(int, int, int)} so
     * the rounding + zero-denominator policy lives in one place and the private
     * {@code cancelledWithBids} + {@code escrowExpiredUnfulfilled} counters
     * never reach the wire. See Epic 08 sub-spec 1 §3.5 for the 3-arg widening.
     */
    private SellerSummary sellerSummary(User s) {
        if (s == null) {
            return null;
        }
        Integer completed = s.getCompletedSales();
        Integer cancelled = s.getCancelledWithBids();
        Integer expiredUnfulfilled = s.getEscrowExpiredUnfulfilled();
        int completedInt = completed == null ? 0 : completed;
        int cancelledInt = cancelled == null ? 0 : cancelled;
        int expiredUnfulfilledInt = expiredUnfulfilled == null ? 0 : expiredUnfulfilled;
        return new SellerSummary(
                s.getPublicId(),
                s.getDisplayName(),
                s.getPublicId() == null ? null : "/api/v1/users/" + s.getPublicId() + "/avatar/256",
                s.getAvgSellerRating(),
                s.getTotalSellerReviews(),
                completed,
                SellerCompletionRateMapper.compute(completedInt, cancelledInt, expiredUnfulfilledInt),
                s.getCreatedAt() == null ? null : s.getCreatedAt().toLocalDate());
    }

    /**
     * Resolves the auction's soft-FK {@code winnerId} column (internal Long)
     * to the winner's {@code publicId} (UUID) for outbound DTO emission.
     * Returns null when the auction has no winner yet (pre-ENDED states).
     * Uses a simple {@code findById} lookup; correctness over performance
     * per task spec.
     */
    private UUID resolveWinnerPublicId(Auction auction) {
        if (auction.getWinnerId() == null) {
            return null;
        }
        return userRepo.findById(auction.getWinnerId())
                .map(User::getPublicId)
                .orElse(null);
    }

    /**
     * On-demand escrow lookup for single-auction mapper entry points. Returns
     * null for unpersisted auctions (tests) and for auctions whose status
     * cannot possibly carry an escrow row (ACTIVE and pre-ACTIVE states, plus
     * CANCELLED/EXPIRED/SUSPENDED terminal states where no sale occurred).
     * Batch callers should pre-load escrows and use the
     * {@code (Auction, Escrow)} overloads to avoid one query per row.
     */
    private Escrow resolveEscrow(Auction a) {
        if (a.getId() == null) {
            return null;
        }
        if (!hasEscrowBearingStatus(a.getStatus())) {
            return null;
        }
        return escrowRepo.findByAuctionId(a.getId()).orElse(null);
    }

    /**
     * Whether an auction in the given status might have an escrow row. Escrow
     * rows are created only on ENDED + (SOLD|BOUGHT_NOW) and then follow the
     * ESCROW_PENDING → ESCROW_FUNDED → TRANSFER_PENDING → COMPLETED lifecycle
     * (with DISPUTED as a possible off-ramp). ACTIVE and pre-ACTIVE statuses,
     * plus CANCELLED/EXPIRED/SUSPENDED terminal statuses (where no sale
     * occurred), never carry an escrow row, so we skip the repo query for
     * them.
     */
    private static boolean hasEscrowBearingStatus(AuctionStatus s) {
        return switch (s) {
            case DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED,
                    ACTIVE, CANCELLED, EXPIRED, SUSPENDED -> false;
            case ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING,
                    COMPLETED, DISPUTED -> true;
        };
    }
}
