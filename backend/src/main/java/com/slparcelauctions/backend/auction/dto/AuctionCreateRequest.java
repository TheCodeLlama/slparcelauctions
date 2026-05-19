package com.slparcelauctions.backend.auction.dto;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/auctions.
 *
 * <p>Verification is no longer chosen at create time. The seller fires
 * {@code PUT /api/v1/auctions/{publicId}/verify} (empty body) after
 * paying the listing fee and the backend performs a single synchronous
 * World API ownership check (see {@code AuctionVerificationService}).
 *
 * <p>{@code slParcelUuid} replaced the legacy {@code parcelId} field —
 * the caller supplies the SL parcel UUID and the backend performs a live
 * lookup + snapshot on create.
 *
 * <p>Validation rules enforced via JSR-380 + extra checks in AuctionService
 * (reserve &gt;= starting, buy_now &gt;= max, etc.).
 */
public record AuctionCreateRequest(
        @NotNull UUID slParcelUuid,
        @NotBlank(message = "title must not be blank")
        @Size(max = 120, message = "title must be at most 120 characters")
        String title,
        @NotNull @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        @NotNull Integer durationHours,          // validated to be in {24,48,72,168,336}
        @NotNull Boolean snipeProtect,
        Integer snipeWindowMin,                    // required iff snipeProtect
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags,          // parcel_tag codes
        /**
         * When non-null, the auction is created under this realty group (the agent
         * is also the seller). The controller routes to
         * {@link com.slparcelauctions.backend.realty.listing.RealtyGroupListingService} which
         * asserts {@code CREATE_LISTING} and snapshots {@code agent_fee_rate} +
         * {@code agent_fee_split} onto the auction row. When null, behavior is identical to
         * the individual-listing path.
         */
        UUID listAsGroupPublicId) {
}
