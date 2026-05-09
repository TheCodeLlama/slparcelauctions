package com.slparcelauctions.backend.admin.listings.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code AdminListingService} when an admin mutation hits a state
 * precondition that rules out the requested action, OR when a list query
 * carries an out-of-whitelist sort column. The {@code code} is a
 * machine-readable string the frontend uses to tailor messaging; the HTTP
 * status is selected per code at the exception-handler layer.
 */
public class AdminListingStateException extends RuntimeException {
    private final String code;

    public AdminListingStateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus suggestedStatus() {
        return switch (code) {
            case "INVALID_STATUS_FOR_ACTION", "ALREADY_SUSPENDED", "NOT_SUSPENDED" ->
                    HttpStatus.CONFLICT;
            case "LISTING_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_SORT_COLUMN",
                 "FEATURE_REQUIRES_ACTIVE_STATUS",
                 "FEATURED_UNTIL_REQUIRES_FEATURED_TRUE" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
