package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code AuthService.register} when the email is already in use. Maps to
 * 409 AUTH_EMAIL_EXISTS via {@code AuthExceptionHandler}.
 */
public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("An account with email " + email + " already exists.");
    }
}
