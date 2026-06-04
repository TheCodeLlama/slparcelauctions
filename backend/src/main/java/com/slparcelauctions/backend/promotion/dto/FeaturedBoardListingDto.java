package com.slparcelauctions.backend.promotion.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FeaturedBoardListingDto(
        UUID publicId,
        String title,
        String region,
        Integer sqm,
        String photoUrl,
        long currentBid,
        OffsetDateTime endsAt,
        String listingUrl,
        String slurl
) {}
