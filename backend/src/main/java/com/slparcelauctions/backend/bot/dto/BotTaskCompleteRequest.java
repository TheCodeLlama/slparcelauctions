package com.slparcelauctions.backend.bot.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Callback body from the bot worker (Epic 06) or the dev stub. One of two
 * shapes depending on {@code result}:
 *
 * <ul>
 *   <li>{@code SUCCESS} — parcel was listed for sale to the primary escrow
 *       avatar at the sentinel price. Populates {@code authBuyerId},
 *       {@code salePrice}, plus refreshed parcel metadata
 *       ({@code parcelOwner, parcelName, areaSqm, regionName, positionX/Y/Z}).
 *       The service validates {@code authBuyerId} matches the configured
 *       primary escrow UUID and {@code salePrice == sentinel} before
 *       transitioning the auction to ACTIVE.</li>
 *   <li>{@code FAILURE} — bot could not verify. Populates
 *       {@code failureReason}. Auction goes to VERIFICATION_FAILED; no refund
 *       (seller can retry via PUT /verify).</li>
 * </ul>
 */
public record BotTaskCompleteRequest(
        @NotNull String result,
        UUID authBuyerId,
        Long salePrice,
        UUID parcelOwner,
        String parcelName,
        Integer areaSqm,
        String regionName,
        Double positionX,
        Double positionY,
        Double positionZ,
        @Size(max = 500) String failureReason) {
}
