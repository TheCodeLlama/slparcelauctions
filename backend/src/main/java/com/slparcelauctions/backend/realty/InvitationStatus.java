package com.slparcelauctions.backend.realty;

/**
 * Lifecycle states for a {@link RealtyGroupInvitation}.
 *
 * <p>Persisted as {@code VARCHAR(10)} with a CHECK constraint over these names (see
 * Flyway V24). {@link #PENDING} is the only state in which an invitation can be accepted
 * or declined; the partial unique index on {@code (group_id, invited_user_id) WHERE
 * status = 'PENDING'} prevents two live invitations for the same pair.
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    REVOKED,
    EXPIRED
}
