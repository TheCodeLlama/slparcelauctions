package com.slparcelauctions.backend.auction.search;

import java.util.List;

/**
 * Lean parcel projection for each browse / featured-row / search-result
 * card. {@code tags} is a list of human-readable labels (e.g.
 * "Waterfront", "Hilltop") that the frontend renders directly into chip
 * elements. Previously this field carried the full {@link
 * com.slparcelauctions.backend.parceltag.ParcelTag} entity which leaked
 * audit columns ({@code active}, {@code createdAt}, {@code updatedAt})
 * and crashed React SSR ("Objects are not valid as a React child")
 * because the frontend's TS type already expected {@code string[]}.
 */
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
        List<String> tags) {
}
