package com.slparcelauctions.backend.admin.reports.exception;

public class MustBeVerifiedToReportException extends RuntimeException {
    public MustBeVerifiedToReportException() {
        super("Verify your SL avatar to report listings");
    }
}
