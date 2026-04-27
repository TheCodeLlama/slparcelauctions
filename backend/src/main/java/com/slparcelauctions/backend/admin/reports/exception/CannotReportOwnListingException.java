package com.slparcelauctions.backend.admin.reports.exception;

public class CannotReportOwnListingException extends RuntimeException {
    public CannotReportOwnListingException() {
        super("Cannot report your own listing");
    }
}
