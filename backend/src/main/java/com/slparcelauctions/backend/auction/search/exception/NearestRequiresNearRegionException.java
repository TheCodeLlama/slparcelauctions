package com.slparcelauctions.backend.auction.search.exception;

public class NearestRequiresNearRegionException extends RuntimeException {
    public NearestRequiresNearRegionException() {
        super("sort=nearest requires near_region parameter");
    }
}
