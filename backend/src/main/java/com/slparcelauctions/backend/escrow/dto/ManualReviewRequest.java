package com.slparcelauctions.backend.escrow.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body for {@code POST /api/v1/auctions/{auctionPublicId}/escrow/manual-review}
 * (spec §7). The whole body is optional — a bare POST opens (or returns the
 * existing) review with no note.
 */
public record ManualReviewRequest(@Size(max = 1000) String note) { }
