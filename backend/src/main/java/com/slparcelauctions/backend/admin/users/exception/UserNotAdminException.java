package com.slparcelauctions.backend.admin.users.exception;

public class UserNotAdminException extends RuntimeException {
    public UserNotAdminException(Long userId) {
        super("User " + userId + " is not an admin");
    }
}
