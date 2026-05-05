package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.AuctionStatus;

public record AdminUserListingRowDto(
    Long auctionId,
    UUID auctionPublicId,
    String title,
    String regionName,
    AuctionStatus status,
    OffsetDateTime endsAt,
    Long finalBidAmount
) {}
