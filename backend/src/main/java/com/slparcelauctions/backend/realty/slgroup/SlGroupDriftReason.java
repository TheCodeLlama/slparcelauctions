package com.slparcelauctions.backend.realty.slgroup;

/**
 * Coded reasons recorded onto {@link RealtyGroupSlGroup#getDriftReason()
 * realty_group_sl_groups.drift_reason} when the periodic reverify task
 * detects that the SL group on the SL side no longer matches the state we
 * captured at verification time.
 *
 * <p>Sub-project F spec §13.2.
 *
 * <ul>
 *   <li>{@link #FOUNDER_CHANGED} — the World API still serves the group, but the
 *       founder UUID is no longer the avatar that owned the founder terminal at
 *       verification time. The realty group probably lost SL-side ownership of
 *       the in-world group.</li>
 *   <li>{@link #GROUP_NOT_FOUND} — the World API returned a 404 for the group's
 *       UUID. The SL group was deleted (or made unreachable). Recorded
 *       immediately, no failure-counter ramp.</li>
 *   <li>{@link #FETCH_FAILED_REPEATEDLY} — the World API failed for non-404
 *       reasons (timeout, 5xx, network error) on at least
 *       {@code slpa.realty.sl-group.reverify-fetch-failure-threshold} consecutive
 *       reverify attempts. Recorded only after the counter crosses the threshold
 *       so transient outages don't mass-drift every registration.</li>
 * </ul>
 */
public enum SlGroupDriftReason {
    FOUNDER_CHANGED,
    GROUP_NOT_FOUND,
    FETCH_FAILED_REPEATEDLY
}
