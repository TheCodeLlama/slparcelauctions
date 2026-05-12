package com.slparcelauctions.backend.realty.reports.exception;

import lombok.Getter;

/**
 * Thrown when the per-reporter daily report quota has been exceeded. The bucket is
 * shared across listing reports and realty-group reports (5/day, spec §12.1) so a
 * spammer cannot rotate target types to multiply their cap. Surfaced as 429 Too Many
 * Requests.
 *
 * <p>Sub-project F spec §8, §12.1.
 */
@Getter
public class ReportRateLimitedException extends RuntimeException {

    private final int dailyLimit;

    public ReportRateLimitedException(int dailyLimit) {
        super("Daily report limit exceeded (" + dailyLimit + "/day)");
        this.dailyLimit = dailyLimit;
    }
}
