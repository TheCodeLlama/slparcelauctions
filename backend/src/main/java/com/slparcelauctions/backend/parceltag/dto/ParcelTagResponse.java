package com.slparcelauctions.backend.parceltag.dto;

import com.slparcelauctions.backend.parceltag.ParcelTag;

public record ParcelTagResponse(
        String code,
        String label,
        String category,
        String description,
        Integer sortOrder) {

    public static ParcelTagResponse from(ParcelTag t) {
        return new ParcelTagResponse(
                t.getCode(), t.getLabel(), t.getCategory(), t.getDescription(), t.getSortOrder());
    }
}
