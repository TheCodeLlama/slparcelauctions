package com.slparcelauctions.backend.admin.reports.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionStatus;

public record AdminReportListingRowDto(
    Long auctionId, String auctionTitle, AuctionStatus auctionStatus,
    String parcelRegionName, Long sellerUserId, String sellerDisplayName,
    Long openReportCount, OffsetDateTime latestReportAt
) {}
