package com.slparcelauctions.backend.realty.reports.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Thrown when an admin tries to resolve/dismiss a realty-group report that does not
 * exist. Surfaced as 404 Not Found.
 *
 * <p>Sub-project F spec §8, §12.3.
 */
@Getter
public class ReportNotFoundException extends RuntimeException {

    private final UUID reportPublicId;

    public ReportNotFoundException(UUID reportPublicId) {
        super("Realty group report not found: " + reportPublicId);
        this.reportPublicId = reportPublicId;
    }
}
