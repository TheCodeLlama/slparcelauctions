package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.AuctionStatus;

public record AdminUserBidRowDto(
    Long bidId,
    Long auctionId,
    UUID auctionPublicId,
    String auctionTitle,
    long amount,
    OffsetDateTime placedAt,
    AuctionStatus auctionStatus
) {}
