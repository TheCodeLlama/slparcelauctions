package com.slparcelauctions.backend.auction.dto;

import jakarta.validation.constraints.Size;

public record AuctionCancelRequest(
        @Size(max = 500) String reason) {
}
