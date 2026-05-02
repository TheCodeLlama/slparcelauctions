package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

/**
 * Wrapper returned by {@code SlWorldApiClient.fetchParcelPage}: the parsed
 * {@link ParcelMetadata} plus the parcel's region SL UUID, which the lookup
 * flow uses to fetch the region page in a follow-up call.
 *
 * <p>The region UUID is extracted from {@code <a href="/region/{UUID}">} in
 * the parcel page body, not from a meta tag — the parcel page only exposes
 * the region UUID via the body link, while {@code regionid} appears on the
 * region page itself.
 */
public record ParcelPageData(ParcelMetadata parcel, UUID regionUuid) {
}
