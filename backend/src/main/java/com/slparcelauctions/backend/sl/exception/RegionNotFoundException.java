package com.slparcelauctions.backend.sl.exception;

/** Map API returned no coordinates for the given region name. Maps to HTTP 404. */
public class RegionNotFoundException extends RuntimeException {

    private final String regionName;

    public RegionNotFoundException(String regionName) {
        super("Region not found in SL: " + regionName);
        this.regionName = regionName;
    }

    public String getRegionName() {
        return regionName;
    }
}
