package com.slparcelauctions.backend.realty.exception;

public class CannotRemoveLeaderException extends RuntimeException {
    public CannotRemoveLeaderException() {
        super("The leader cannot be removed without leadership transfer");
    }

    public CannotRemoveLeaderException(String message) {
        super(message);
    }
}
