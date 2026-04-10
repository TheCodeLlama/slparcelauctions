package com.slparcelauctions.backend.user;

public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }

    public static UserAlreadyExistsException email(String email) {
        return new UserAlreadyExistsException("User with email already exists: " + email);
    }
}
