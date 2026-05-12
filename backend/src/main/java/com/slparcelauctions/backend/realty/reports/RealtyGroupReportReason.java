package com.slparcelauctions.backend.realty.reports;

/**
 * Reason a user submitted a {@link RealtyGroupReport} against a realty group.
 *
 * <p>Sub-project F spec §4.2.
 */
public enum RealtyGroupReportReason {
    FRAUDULENT_LISTINGS,
    MISLEADING_ATTRIBUTION,
    HARASSMENT,
    IMPERSONATION,
    SPAM,
    OTHER
}
