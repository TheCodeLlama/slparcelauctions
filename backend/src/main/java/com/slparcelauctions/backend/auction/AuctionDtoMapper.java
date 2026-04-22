package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.dto.AuctionPhotoResponse;
import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.PublicAuctionStatus;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

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
 * {@code ...WithEscrow(auction, ..., escrow)} overloads to avoid N+1 queries.
 */
@Component
@RequiredArgsConstructor
public class AuctionDtoMapper {

    private final AuctionPhotoRepository photoRepo;
    private final EscrowRepository escrowRepo;

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
                a.getId(),
                a.getSeller().getId(),
                ParcelResponse.from(a.getParcel()),
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
                a.getId(),
                a.getSeller().getId(),
                ParcelResponse.from(a.getParcel()),
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
                a.getWinnerId(),
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
     * On-demand escrow lookup for single-auction mapper entry points. Returns
     * null for unpersisted auctions (tests) and for auctions that have not yet
     * reached ENDED. Batch callers should pre-load escrows and use the
     * {@code (Auction, Escrow)} overloads to avoid one query per row.
     */
    private Escrow resolveEscrow(Auction a) {
        if (a.getId() == null) {
            return null;
        }
        return escrowRepo.findByAuctionId(a.getId()).orElse(null);
    }
}
