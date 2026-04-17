package com.slparcelauctions.backend.auction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record DevPayRequest(
        @Min(1) Long amount,
        @Size(max = 255) String txnRef) {
}
