package com.slparcelauctions.backend.review.exception;

/**
 * Lookup for a {@link com.slparcelauctions.backend.review.Review} by id
 * returned empty. Maps to HTTP 404 via {@code ReviewExceptionHandler}.
 * Landed in Task 1 so Task 2's GET endpoints have the exception
 * available; Task 1 itself does not raise this exception.
 */
public class ReviewNotFoundException extends RuntimeException {
    public ReviewNotFoundException(Long id) {
        super("Review not found: " + id);
    }

    public ReviewNotFoundException(java.util.UUID publicId) {
        super("Review not found: " + publicId);
    }
}
