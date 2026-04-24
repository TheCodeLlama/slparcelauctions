package com.slparcelauctions.backend.review.exception;

/**
 * Caller attempted to submit a review for an auction they cannot review
 * (not a party; escrow not COMPLETED; escrow missing). Maps to HTTP 422
 * via {@code ReviewExceptionHandler} so the frontend can distinguish
 * authentic-user-just-wrong-context (422) from forbidden-role (403).
 */
public class ReviewIneligibleException extends RuntimeException {
    public ReviewIneligibleException(String message) {
        super(message);
    }
}
