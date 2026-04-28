package com.slparcelauctions.backend.auction.monitoring;

import com.slparcelauctions.backend.auction.AuctionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OwnershipCheckResult(
        boolean ownerMatch,
        UUID expectedOwner,
        UUID observedOwner,
        OffsetDateTime checkedAt,
        AuctionStatus auctionStatus) {
}
