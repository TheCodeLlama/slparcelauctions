package com.slparcelauctions.backend.auction.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record AuctionPhotoOrderRequest(
        @NotNull @NotEmpty List<@NotNull UUID> photoPublicIds) {}
