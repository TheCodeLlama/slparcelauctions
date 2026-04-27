package com.slparcelauctions.backend.admin.ban.exception;

public class BanAlreadyLiftedException extends RuntimeException {
    public BanAlreadyLiftedException(Long id) {
        super("Ban " + id + " already lifted");
    }
}
