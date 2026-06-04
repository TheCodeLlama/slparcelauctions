package com.slparcelauctions.backend.promotion.dto;

import java.util.List;

public record FeaturedBoardPayloadDto(
        int boardIndex,
        int cycleSeconds,
        List<FeaturedBoardListingDto> listings,
        Source source
) {
    public enum Source { PROMO_01, ALGORITHMIC, PLACEHOLDER }
}
