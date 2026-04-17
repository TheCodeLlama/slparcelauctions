package com.slparcelauctions.backend.auction;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.PublicAuctionStatus;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

/**
 * Auction → DTO conversion. The {@link #toPublicStatus(AuctionStatus)} method
 * is the linchpin of the privacy guarantee: the 4 terminal statuses collapse
 * to ENDED, and the 4 pre-ACTIVE statuses throw IllegalStateException because
 * the controller is responsible for returning 404 to non-sellers before this
 * mapper is called.
 */
@Component
public class AuctionDtoMapper {

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
                a.getDurationHours(),
                a.getSnipeProtect(),
                a.getSnipeWindowMin(),
                a.getStartsAt(),
                a.getEndsAt(),
                a.getOriginalEndsAt(),
                a.getSellerDesc(),
                tagList(a),
                List.of());   // photos wired in Task 9
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
                a.getWinnerId(),
                a.getDurationHours(),
                a.getSnipeProtect(),
                a.getSnipeWindowMin(),
                a.getStartsAt(),
                a.getEndsAt(),
                a.getOriginalEndsAt(),
                a.getSellerDesc(),
                tagList(a),
                List.of(),    // photos wired in Task 9
                a.getListingFeePaid(),
                a.getListingFeeAmt(),
                a.getListingFeeTxn(),
                a.getListingFeePaidAt(),
                a.getCommissionRate(),
                a.getCommissionAmt(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }

    private List<ParcelTagResponse> tagList(Auction a) {
        return a.getTags().stream()
                .sorted(Comparator.comparing(t -> t.getSortOrder() == null ? 0 : t.getSortOrder()))
                .map(ParcelTagResponse::from)
                .toList();
    }
}
