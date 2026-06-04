package com.slparcelauctions.backend.promotion.dto;

import java.util.UUID;

public record PurchaseFeaturedResponse(
        UUID slotPublicId,
        int boardIndex,
        int position,
        long priceLindens,
        long newBalanceLindens
) {}
