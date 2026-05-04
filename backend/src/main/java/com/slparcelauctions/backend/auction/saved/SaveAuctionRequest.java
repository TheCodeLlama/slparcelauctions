package com.slparcelauctions.backend.auction.saved;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/me/saved}. The {@code auctionPublicId} is
 * the only field — the user is taken from the authenticated principal.
 */
public record SaveAuctionRequest(@NotNull UUID auctionPublicId) {}
