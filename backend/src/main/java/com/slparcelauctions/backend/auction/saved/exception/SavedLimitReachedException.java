package com.slparcelauctions.backend.auction.saved.exception;

/**
 * Raised when a user attempts to save a 501st auction. Maps to HTTP 409
 * with code {@code SAVED_LIMIT_REACHED} and {@code cap=500} in the
 * problem-detail envelope.
 */
public class SavedLimitReachedException extends RuntimeException {

    public SavedLimitReachedException() {
        super("Saved auction limit reached (500)");
    }
}
