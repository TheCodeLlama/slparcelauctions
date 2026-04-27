package com.slparcelauctions.backend.admin.reports.exception;

public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException(Long id) { super("Report not found: " + id); }
}
