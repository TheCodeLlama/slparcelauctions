package com.slparcelauctions.backend.parceltag.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagCategory;

/**
 * Admin view of a parcel tag. {@code category} is a nested object so the
 * admin UI can show the human label while still referencing the row by
 * code on edit operations.
 */
public record AdminParcelTagDto(
        String code,
        String label,
        CategoryRef category,
        String description,
        Boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record CategoryRef(String code, String label, Boolean active) {
        public static CategoryRef from(ParcelTagCategory c) {
            return new CategoryRef(c.getCode(), c.getLabel(), c.getActive());
        }
    }

    public static AdminParcelTagDto from(ParcelTag t) {
        return new AdminParcelTagDto(
                t.getCode(),
                t.getLabel(),
                CategoryRef.from(t.getCategory()),
                t.getDescription(),
                t.getActive(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
