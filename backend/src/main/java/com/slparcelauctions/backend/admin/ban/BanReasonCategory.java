package com.slparcelauctions.backend.admin.ban;

/**
 * High-level reason category for the ban, recorded on the row for admin
 * audit and reporting. Free-text {@code notes} on the {@link Ban} entity
 * carries the narrative; this enum is the structured signal.
 */
public enum BanReasonCategory {
    SHILL_BIDDING,
    FRAUDULENT_SELLER,
    TOS_ABUSE,
    SPAM,
    OTHER
}
