package com.slparcelauctions.backend.support;

/**
 * Fixed set of triage buckets a user picks when opening a
 * {@link SupportTicket}. Six values cover the common surfaces; OTHER is
 * the long-tail catch-all. Free-form categories were rejected to keep
 * admin queue filtering tractable.
 */
public enum SupportTicketCategory {
    ACCOUNT,
    BIDDING,
    LISTING,
    ESCROW,
    WALLET,
    OTHER
}
