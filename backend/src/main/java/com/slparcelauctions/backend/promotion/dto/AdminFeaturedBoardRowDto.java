package com.slparcelauctions.backend.promotion.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminFeaturedBoardRowDto(
        UUID slotPublicId,
        int boardIndex,
        int position,
        UUID auctionPublicId,
        String auctionTitle,
        long currentBid,
        OffsetDateTime endsAt,
        OffsetDateTime assignedAt
) {}
