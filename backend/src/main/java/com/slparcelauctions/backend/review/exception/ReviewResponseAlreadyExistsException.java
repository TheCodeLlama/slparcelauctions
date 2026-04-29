package com.slparcelauctions.backend.review.exception;

/**
 * The reviewee has already posted a response to this review — a second
 * POST to {@code /api/v1/reviews/{id}/respond} would violate the
 * {@code review_id}-unique FK on {@code review_responses}. Maps to HTTP
 * 409 via {@code ReviewExceptionHandler}. The DB constraint is the
 * last-line-of-defence under concurrent responses by the same reviewee.
 */
public class ReviewResponseAlreadyExistsException extends RuntimeException {

    public ReviewResponseAlreadyExistsException(Long reviewId) {
        super("Review " + reviewId + " already has a response.");
    }
}
