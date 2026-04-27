package com.slparcelauctions.backend.admin.users.exception;

public class UserAlreadyAdminException extends RuntimeException {
    public UserAlreadyAdminException(Long userId) {
        super("User " + userId + " is already an admin");
    }
}
