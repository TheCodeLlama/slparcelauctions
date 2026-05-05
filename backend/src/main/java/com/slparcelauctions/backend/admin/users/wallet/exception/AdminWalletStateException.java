package com.slparcelauctions.backend.admin.users.wallet.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code AdminWalletService} when an admin mutation hits a state
 * precondition that rules out the requested action. The {@code code} is a
 * machine-readable string the frontend uses to tailor messaging; the HTTP
 * status is selected per code at the exception-handler layer.
 */
public class AdminWalletStateException extends RuntimeException {
    private final String code;

    public AdminWalletStateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus suggestedStatus() {
        return switch (code) {
            case "AMOUNT_ZERO", "AMOUNT_EXCEEDS_OWED", "RESERVATION_FLOOR" ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case "ALREADY_FROZEN", "NOT_FROZEN", "NOT_IN_DORMANCY",
                 "BOT_PROCESSING", "WITHDRAWAL_NOT_PENDING" ->
                    HttpStatus.CONFLICT;
            case "COMMAND_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "COMMAND_USER_MISMATCH" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
