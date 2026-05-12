package com.slparcelauctions.backend.realty.reports.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Thrown when a user submits a report against a realty group they already have an
 * OPEN report on. Mirrors the partial-unique index
 * {@code uq_rg_reports_one_open_per_reporter} (V28); when two requests race past the
 * pre-check, the second one trips the index and {@code DataIntegrityViolationException}
 * is translated to this exception. Surfaced as 409 Conflict.
 *
 * <p>Sub-project F spec §8, §12.1.
 */
@Getter
public class AlreadyReportedException extends RuntimeException {

    private final UUID groupPublicId;

    public AlreadyReportedException(UUID groupPublicId) {
        super("Reporter already has an open report against realty group: " + groupPublicId);
        this.groupPublicId = groupPublicId;
    }
}
