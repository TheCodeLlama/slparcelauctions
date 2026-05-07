package com.slparcelauctions.backend.admin.ledger.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by the admin global ledger view when filter validation fails
 * (incompatible parameter combinations, unknown sort column, etc.) or
 * when a referenced user can't be resolved. The {@code code} is the
 * machine-readable string the frontend uses to tailor messaging.
 */
public class AdminLedgerStateException extends RuntimeException {
    private final String code;

    public AdminLedgerStateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus suggestedStatus() {
        return switch (code) {
            case "USER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_SORT_COLUMN",
                 "ENTRY_TYPE_REQUIRES_SINGLE_KIND",
                 "REF_ID_REQUIRES_REF_TYPE",
                 "INVALID_KIND",
                 "INVALID_DATE_RANGE" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
