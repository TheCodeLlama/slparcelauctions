package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionStatus;

public record AdminUserListingRowDto(
    Long auctionId,
    String title,
    String regionName,
    AuctionStatus status,
    OffsetDateTime endsAt,
    Long finalBidAmount
) {}
