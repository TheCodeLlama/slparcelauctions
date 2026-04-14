package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by write-path services (future {@code BidService}, {@code ListingService}, {@code EscrowService})
 * when {@code principal.tokenVersion()} does not match the freshly-loaded {@code user.getTokenVersion()}.
 * Signals that the user's session was invalidated (banned, suspended, password-changed, logged-out-all)
 * after this access token was issued. The client should POST /api/v1/auth/refresh to get a new access token
 * reflecting the current token version — which will fail if the refresh token was also revoked, forcing
 * a re-login.
 *
 * <p>Not thrown by any Task 01-07 code — this exception class ships now so future write-path slices
 * have the API ready. See spec §6 and §15 for out-of-scope notes.
 */
public class AuthenticationStaleException extends RuntimeException {
    public AuthenticationStaleException() {
        super("Session is no longer valid; please log in again.");
    }
}
