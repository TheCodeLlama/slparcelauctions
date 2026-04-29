package com.slparcelauctions.backend.auction.saved;

import java.util.List;

/**
 * Response body for {@code GET /api/v1/me/saved/ids}. Bare-bones list of
 * auction ids ordered most-recently-saved first. Hot path for browse-page
 * heart-overlay rendering; intentionally not paginated since the cap is
 * {@value SavedAuctionService#SAVED_CAP}.
 */
public record SavedAuctionIdsResponse(List<Long> ids) {}
