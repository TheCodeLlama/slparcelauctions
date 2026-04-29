package com.slparcelauctions.backend.auction.search.exception;

import lombok.Getter;

/**
 * The {@code near_region} query parameter resolved to no SL region in the
 * Grid Survey CAP service. Maps to HTTP 400 in {@link SearchExceptionHandler}.
 *
 * <p>Distinct from {@link com.slparcelauctions.backend.sl.exception.RegionNotFoundException}
 * (which is thrown deeper in {@code SlMapApiClient.parse}) so that the
 * search layer can map this to a 400 with a {@code field=near_region}
 * envelope without coupling to the lower-level type.
 */
@Getter
public class RegionNotFoundException extends RuntimeException {

    private final String regionName;

    public RegionNotFoundException(String regionName) {
        super("Region not found in Grid Survey: " + regionName);
        this.regionName = regionName;
    }
}
