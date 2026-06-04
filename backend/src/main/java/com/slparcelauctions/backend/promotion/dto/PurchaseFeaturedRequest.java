package com.slparcelauctions.backend.promotion.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record PurchaseFeaturedRequest(@NotNull UUID auctionPublicId) {}
