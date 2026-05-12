package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised when an admin attempts to acknowledge drift on an SL group registration
 * that is not currently flagged as drifted. The drift-ack flow is only meaningful
 * for rows whose {@code drift_detected_at} is non-null; calling it on a row in
 * any other state is a 409 (the request is well-formed but inapplicable to the
 * row's current state).
 *
 * <p>Sub-project F spec §13.4.
 */
@Getter
public class NoDriftDetectedException extends RuntimeException {
    public static final String CODE = "NO_DRIFT_DETECTED";

    private final UUID slGroupPublicId;

    public NoDriftDetectedException(UUID slGroupPublicId) {
        super("SL group registration has no drift to acknowledge: " + slGroupPublicId);
        this.slGroupPublicId = slGroupPublicId;
    }
}
