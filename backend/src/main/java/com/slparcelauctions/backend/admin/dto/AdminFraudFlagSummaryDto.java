package com.slparcelauctions.backend.admin.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;

public record AdminFraudFlagSummaryDto(
    Long id,
    FraudFlagReason reason,
    OffsetDateTime detectedAt,
    Long auctionId,
    String auctionTitle,
    AuctionStatus auctionStatus,
    String parcelRegionName,
    Long parcelLocalId,
    boolean resolved,
    OffsetDateTime resolvedAt,
    String resolvedByDisplayName
) {}
