package com.slparcelauctions.backend.admin.ban.exception;

public class BanNotFoundException extends RuntimeException {
    public BanNotFoundException(Long id) {
        super("Ban not found: " + id);
    }
}
