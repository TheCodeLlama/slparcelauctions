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
 */
@Component
@RequiredArgsConstructor
public class AuctionDtoMapper {

    private final AuctionPhotoRepository photoRepo;

    public PublicAuctionStatus toPublicStatus(AuctionStatus internal) {
        return switch (internal) {
            case ACTIVE -> PublicAuctionStatus.ACTIVE;
            case ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING,
                    COMPLETED, CANCELLED, EXPIRED, DISPUTED -> PublicAuctionStatus.ENDED;
            case DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED ->
                    throw new IllegalStateException(
                            "Non-public status leaked to toPublicStatus: " + internal
                                    + ". The controller should have 404'd before calling the mapper.");
        };
    }

    public PublicAuctionResponse toPublicResponse(Auction a) {
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
                photoList(a));
    }

    public SellerAuctionResponse toSellerResponse(Auction a, PendingVerification pending) {
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
                a.getUpdatedAt());
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
}
