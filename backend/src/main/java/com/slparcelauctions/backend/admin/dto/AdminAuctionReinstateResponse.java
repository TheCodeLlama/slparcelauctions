package com.slparcelauctions.backend.admin.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionStatus;

public record AdminAuctionReinstateResponse(
    Long auctionId,
    AuctionStatus status,
    OffsetDateTime newEndsAt,
    long suspensionDurationSeconds
) {}
