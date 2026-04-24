package com.slparcelauctions.backend.review.exception;

/**
 * The caller already flagged this review — a second POST to
 * {@code /api/v1/reviews/{id}/flag} from the same user would violate the
 * {@code uq_review_flags_review_flagger} unique constraint. Maps to HTTP
 * 409 via {@code ReviewExceptionHandler}. The DB constraint is the
 * last-line-of-defence under concurrent flags by the same caller.
 */
public class ReviewFlagAlreadyExistsException extends RuntimeException {

    public ReviewFlagAlreadyExistsException(Long reviewId) {
        super("You have already flagged review " + reviewId + ".");
    }
}
