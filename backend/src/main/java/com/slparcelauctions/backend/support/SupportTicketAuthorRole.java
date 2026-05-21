package com.slparcelauctions.backend.support;

/**
 * Snapshotted role of whoever authored a given
 * {@link SupportTicketMessage}. Stored on the message row at write time
 * so later role changes (promotion / demotion) never rewrite historical
 * authorship. Also stored on the parent ticket as
 * {@code lastMessageAuthor} to power the admin "needs admin reply"
 * filter without a subquery.
 */
public enum SupportTicketAuthorRole {
    USER,
    ADMIN
}
