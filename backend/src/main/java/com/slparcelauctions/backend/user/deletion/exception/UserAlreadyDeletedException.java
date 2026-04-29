package com.slparcelauctions.backend.user.deletion.exception;

public class UserAlreadyDeletedException extends RuntimeException {
    public UserAlreadyDeletedException(long userId) {
        super("User " + userId + " is already deleted");
    }
}
