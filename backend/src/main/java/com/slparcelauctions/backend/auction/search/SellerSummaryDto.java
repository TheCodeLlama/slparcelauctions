package com.slparcelauctions.backend.auction.search;

import java.math.BigDecimal;

public record SellerSummaryDto(
        Long id,
        String displayName,
        String avatarUrl,
        BigDecimal averageRating,
        Integer reviewCount) {
}
