package com.slparcelauctions.backend.parceltag.dto;

import java.util.List;

/**
 * Category grouping for the {@code GET /api/v1/parcel-tags} response. Tags
 * within each group preserve the per-category {@code sort_order} from the
 * {@code parcel_tags} table.
 */
public record ParcelTagGroupResponse(
        String category,
        List<ParcelTagResponse> tags) {
}
