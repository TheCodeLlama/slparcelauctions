package com.slparcelauctions.backend.support;

/**
 * Persisted lifecycle state of a {@link SupportTicket}. Only two values
 * exist on purpose - the "needs admin attention" admin-queue filter is
 * derived from {@code lastMessageAuthor = USER AND status = OPEN}, not
 * a third enum value, to avoid drift between separate flags and reality.
 */
public enum SupportTicketStatus {
    OPEN,
    RESOLVED
}
