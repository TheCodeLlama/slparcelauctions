package com.slparcelauctions.backend.user;

public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }

    public static UserAlreadyExistsException username(String username) {
        return new UserAlreadyExistsException("User with username already exists: " + username);
    }
}
