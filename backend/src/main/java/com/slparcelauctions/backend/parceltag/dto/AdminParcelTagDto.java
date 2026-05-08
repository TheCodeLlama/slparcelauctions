package com.slparcelauctions.backend.parceltag.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.parceltag.ParcelTag;

/**
 * Admin view of a parcel tag — exposes the {@code active} flag and timestamps
 * the public {@link ParcelTagResponse} hides. Code is admin-immutable but
 * exposed here so the admin UI can reference rows by the same key the
 * backend stores.
 */
public record AdminParcelTagDto(
        String code,
        String label,
        String category,
        String description,
        Integer sortOrder,
        Boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static AdminParcelTagDto from(ParcelTag t) {
        return new AdminParcelTagDto(
                t.getCode(),
                t.getLabel(),
                t.getCategory(),
                t.getDescription(),
                t.getSortOrder(),
                t.getActive(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
