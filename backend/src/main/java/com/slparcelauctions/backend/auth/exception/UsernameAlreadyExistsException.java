package com.slparcelauctions.backend.auth.exception;

/**
 * Thrown by {@code AuthService.register} when the username is already in use. Maps to
 * 409 AUTH_USERNAME_EXISTS via {@code AuthExceptionHandler}.
 */
public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException(String username) {
        super("An account with username " + username + " already exists.");
    }
}
