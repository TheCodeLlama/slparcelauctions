package com.slparcelauctions.backend.realty.exception;

/**
 * Raised by {@code RealtyGroupService.dissolveGroup} when the leader attempts to dissolve a
 * group that has at least one auction in DRAFT / VERIFICATION_PENDING / ACTIVE status.
 * Admin force-dissolve bypasses this guard — see {@code dissolveGroupAsAdmin}.
 */
public class ActiveListingsBlockDissolveException extends RuntimeException {

    public ActiveListingsBlockDissolveException() {
        super("Cannot dissolve a realty group while it has active listings.");
    }
}
