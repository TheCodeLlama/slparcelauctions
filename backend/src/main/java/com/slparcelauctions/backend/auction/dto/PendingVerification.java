package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.VerificationMethod;

/**
 * Populated on SellerAuctionResponse when status == VERIFICATION_PENDING.
 * Per method: REZZABLE has {code, codeExpiresAt}; SALE_TO_BOT has
 * {botTaskId, instructions}; UUID_ENTRY has none (Method A is synchronous,
 * the response never carries this object populated — it transitions directly
 * to ACTIVE or VERIFICATION_FAILED).
 */
public record PendingVerification(
        VerificationMethod method,
        String code,
        OffsetDateTime codeExpiresAt,
        Long botTaskId,
        String instructions) {
}
