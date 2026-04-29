package com.slparcelauctions.backend.review.exception;

/**
 * Caller has already submitted a review for this auction — a duplicate
 * POST would violate the {@code uq_reviews_auction_reviewer}
 * constraint. Maps to HTTP 409 via {@code ReviewExceptionHandler}.
 */
public class ReviewAlreadySubmittedException extends RuntimeException {
    public ReviewAlreadySubmittedException(String message) {
        super(message);
    }
}
