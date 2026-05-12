package com.slparcelauctions.backend.realty.moderation.exception;

import java.time.OffsetDateTime;

import lombok.Getter;

/**
 * Thrown by {@code RealtyGroupGuard} (Task 7) when a caller attempts to operate on a
 * realty group that currently has an active suspension or permanent ban. Surfaced as
 * 409 Conflict with a {@code ProblemDetail} body carrying {@link #status} (SUSPENDED
 * or BANNED) and {@link #expiresAt} (null for permanent ban) so the frontend can render
 * an appropriate guard banner.
 *
 * <p>Sub-project F spec §5.2, §5.3.
 */
@Getter
public class RealtyGroupSuspendedException extends RuntimeException {

    public enum Status {
        /** Timed suspension with a known expiry. */
        SUSPENDED,
        /** Permanent ban — no expiry. */
        BANNED
    }

    private final Status status;
    /** Null when {@link #status} is {@link Status#BANNED}. */
    private final OffsetDateTime expiresAt;
    private final String reason;

    public RealtyGroupSuspendedException(Status status, OffsetDateTime expiresAt, String reason) {
        super(buildMessage(status, expiresAt, reason));
        this.status = status;
        this.expiresAt = expiresAt;
        this.reason = reason;
    }

    private static String buildMessage(Status status, OffsetDateTime expiresAt, String reason) {
        if (status == Status.BANNED) {
            return "Realty group is banned" + (reason == null ? "" : " (reason=" + reason + ")");
        }
        return "Realty group is suspended until " + expiresAt
            + (reason == null ? "" : " (reason=" + reason + ")");
    }
}
