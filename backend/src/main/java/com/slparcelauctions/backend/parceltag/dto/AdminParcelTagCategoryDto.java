package com.slparcelauctions.backend.parceltag.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.parceltag.ParcelTagCategory;

public record AdminParcelTagCategoryDto(
        String code,
        String label,
        String description,
        Boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static AdminParcelTagCategoryDto from(ParcelTagCategory c) {
        return new AdminParcelTagCategoryDto(
                c.getCode(),
                c.getLabel(),
                c.getDescription(),
                c.getActive(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
