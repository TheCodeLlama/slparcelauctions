package com.slparcelauctions.backend.config.dto;

/**
 * Public response for {@code GET /api/v1/config/listing-fee}.
 *
 * <p>Exposes the current listing-fee amount (in L$, whole units) so the
 * frontend Activate step can render the exact fee the user is about to
 * be charged without duplicating the backend config. The value is
 * sourced from {@code slpa.listing-fee.amount-lindens}.
 */
public record ListingFeeConfigResponse(long amountLindens) {
}
