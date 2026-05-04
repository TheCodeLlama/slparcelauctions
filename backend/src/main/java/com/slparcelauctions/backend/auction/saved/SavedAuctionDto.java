package com.slparcelauctions.backend.auction.saved;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/me/saved}. Returns the auction publicId
 * + saved-at timestamp so the client can display the moment the heart
 * was lit. On idempotent re-saves the {@code savedAt} is the existing
 * row's timestamp, not a fresh "now".
 */
public record SavedAuctionDto(UUID auctionPublicId, OffsetDateTime savedAt) {}
