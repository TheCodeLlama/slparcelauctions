package com.slparcelauctions.backend.auction.search.exception;

public class DistanceRequiresNearRegionException extends RuntimeException {
    public DistanceRequiresNearRegionException() {
        super("distance parameter requires near_region");
    }
}
