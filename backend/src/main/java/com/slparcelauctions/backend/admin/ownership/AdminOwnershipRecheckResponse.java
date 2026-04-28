package com.slparcelauctions.backend.admin.ownership;

import com.slparcelauctions.backend.auction.AuctionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminOwnershipRecheckResponse(
        boolean ownerMatch,
        UUID observedOwner,
        UUID expectedOwner,
        OffsetDateTime checkedAt,
        AuctionStatus auctionStatus) {
}
