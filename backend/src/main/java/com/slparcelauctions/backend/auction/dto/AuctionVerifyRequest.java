package com.slparcelauctions.backend.auction.dto;

import com.slparcelauctions.backend.auction.VerificationMethod;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/v1/auctions/{id}/verify}.
 *
 * <p>Per Epic 03 sub-spec 2 §7.2, the verification method is chosen by the
 * seller at activate time (not at create time). The service persists the
 * chosen method on the auction before dispatching to the method-specific
 * flow (UUID_ENTRY / REZZABLE / SALE_TO_BOT).
 *
 * <p>Group-owned land must use {@link VerificationMethod#SALE_TO_BOT} —
 * any other method triggers a
 * {@link com.slparcelauctions.backend.auction.exception.GroupLandRequiresSaleToBotException}
 * (HTTP 422).
 */
public record AuctionVerifyRequest(
        @NotNull VerificationMethod method) {
}
