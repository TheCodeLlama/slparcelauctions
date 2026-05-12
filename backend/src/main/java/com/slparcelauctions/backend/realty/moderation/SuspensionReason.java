package com.slparcelauctions.backend.realty.moderation;

/**
 * Reason an admin issued a {@link RealtyGroupSuspension} against a realty group.
 *
 * <p>Sub-project F spec §4.1. The reason is informational for audit and admin UI;
 * downstream behavior (timed vs permanent ban) is driven by the suspension row's
 * {@code expiresAt}, not by this enum.
 */
public enum SuspensionReason {
    FRAUD,
    REPORTS_RESOLVED_AGAINST,
    TOS_VIOLATION,
    ABUSE,
    OTHER
}
