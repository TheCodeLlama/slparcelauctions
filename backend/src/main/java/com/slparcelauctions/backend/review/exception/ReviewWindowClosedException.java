package com.slparcelauctions.backend.review.exception;

/**
 * Caller attempted to submit a review after the 14-day window
 * ({@code escrow.completedAt + 14 days}) has passed. Separate exception
 * from {@link ReviewIneligibleException} so the frontend can render a
 * window-specific message without string-matching the ineligible
 * explanation. Maps to HTTP 422.
 */
public class ReviewWindowClosedException extends RuntimeException {
    public ReviewWindowClosedException(String message) {
        super(message);
    }
}
