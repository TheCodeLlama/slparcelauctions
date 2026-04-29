package com.slparcelauctions.backend.auction.search;

import java.util.List;

import com.slparcelauctions.backend.parceltag.ParcelTag;

public record ParcelSummaryDto(
        Long id,
        String name,
        String region,
        Integer area,
        String maturity,
        String snapshotUrl,
        Double gridX,
        Double gridY,
        Double positionX,
        Double positionY,
        Double positionZ,
        List<ParcelTag> tags) {
}
