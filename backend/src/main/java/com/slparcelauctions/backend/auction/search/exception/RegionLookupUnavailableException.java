package com.slparcelauctions.backend.auction.search.exception;

/**
 * The Grid Survey upstream failed transiently (timeout, 5xx, network).
 * Maps to HTTP 503 in {@link SearchExceptionHandler} so the client knows
 * to retry rather than re-shaping the request.
 */
public class RegionLookupUnavailableException extends RuntimeException {

    public RegionLookupUnavailableException(String message) {
        super(message);
    }
}
