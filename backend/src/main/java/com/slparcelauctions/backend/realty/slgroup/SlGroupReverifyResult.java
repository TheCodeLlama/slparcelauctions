package com.slparcelauctions.backend.realty.slgroup;

import java.util.UUID;

/**
 * Return value of {@link SlGroupReverifyService#recheck(Long)}. Captures the
 * outcome of a single revalidation pass against {@code world.secondlife.com}.
 *
 * <p>Sub-project F spec §13.2.
 *
 * @param driftDetected      {@code true} if the row is now flagged as drifted
 *                           (either by this pass or because a previous pass
 *                           already flagged it and this one observed the same
 *                           condition).
 * @param driftReason        The coded reason stored on the row, or {@code null}
 *                           if no drift is flagged. Carries the
 *                           {@link SlGroupDriftReason#name()} string so callers
 *                           receive the same value the DB stores.
 * @param currentFounderUuid The founder UUID observed on this fetch, or
 *                           {@code null} when the fetch failed (timeout / 5xx /
 *                           404). Callers that surface this to admins should
 *                           treat {@code null} as "we did not learn anything
 *                           this round."
 */
public record SlGroupReverifyResult(
        boolean driftDetected,
        String driftReason,
        UUID currentFounderUuid
) {}
