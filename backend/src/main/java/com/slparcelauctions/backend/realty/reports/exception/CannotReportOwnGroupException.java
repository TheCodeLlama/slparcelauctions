package com.slparcelauctions.backend.realty.reports.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Thrown when a member of a realty group tries to report that same group. Members
 * (including the leader) cannot file user-reports against their own group — the
 * appropriate path for an internal dispute is a fraud-flag or a leadership transfer,
 * not a public report queue entry. Surfaced as 422 Unprocessable Entity.
 *
 * <p>Sub-project F spec §8, §12.1.
 */
@Getter
public class CannotReportOwnGroupException extends RuntimeException {

    private final UUID groupPublicId;

    public CannotReportOwnGroupException(UUID groupPublicId) {
        super("Members cannot report their own realty group: " + groupPublicId);
        this.groupPublicId = groupPublicId;
    }
}
