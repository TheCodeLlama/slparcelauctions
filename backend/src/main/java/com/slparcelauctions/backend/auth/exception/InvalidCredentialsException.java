package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code AuthService.login} when email is unknown or password doesn't match.
 * Both cases throw this same exception with the same response shape (401 AUTH_INVALID_CREDENTIALS)
 * so the endpoint doesn't leak email existence via response differences. BCrypt's constant-time
 * comparison handles the timing-attack mitigation.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Email or password is incorrect.");
    }
}
