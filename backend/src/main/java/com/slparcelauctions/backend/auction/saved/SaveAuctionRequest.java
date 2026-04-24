package com.slparcelauctions.backend.auction.saved;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/me/saved}. The {@code auctionId} is
 * the only field — the user is taken from the authenticated principal.
 */
public record SaveAuctionRequest(@NotNull Long auctionId) {}
