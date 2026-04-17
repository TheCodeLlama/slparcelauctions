package com.slparcelauctions.backend.parcel.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/parcels/lookup}. Jackson rejects
 * unparseable UUID strings at deserialization, returning HTTP 400 before
 * Bean Validation runs.
 */
public record ParcelLookupRequest(
        @NotNull UUID slParcelUuid) {
}
