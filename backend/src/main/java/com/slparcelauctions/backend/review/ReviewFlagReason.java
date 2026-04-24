package com.slparcelauctions.backend.review;

/**
 * Enumerated flag reason for {@link ReviewFlag}. {@code OTHER} requires a
 * non-null {@code elaboration} (enforced at the DTO layer in Task 3).
 */
public enum ReviewFlagReason {
    SPAM,
    ABUSIVE,
    OFF_TOPIC,
    FALSE_INFO,
    OTHER
}
