package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionStatus;

public record AdminUserBidRowDto(
    Long bidId,
    Long auctionId,
    String auctionTitle,
    long amount,
    OffsetDateTime placedAt,
    AuctionStatus auctionStatus
) {}
