package com.slparcelauctions.backend.realty.wallet.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Sub-project G §7.3 -- the realty group has an SL group registration but the
 * realty group itself is currently SUSPENDED. Withdraw to {@code SL_GROUP} is
 * blocked; withdraw to the leader's avatar is still available. Drift alone
 * (registration has {@code drift_reason != null} but the realty group is not
 * suspended) does NOT trip this exception -- §7.3 explicitly allows that case.
 * Surfaced as 422 UNPROCESSABLE_ENTITY.
 */
@Getter
public class SlGroupRegistrationSuspendedException extends RuntimeException {

    private final UUID realtyGroupPublicId;

    public SlGroupRegistrationSuspendedException(UUID realtyGroupPublicId) {
        super("Realty group " + realtyGroupPublicId + " is suspended; SL-group withdraw blocked");
        this.realtyGroupPublicId = realtyGroupPublicId;
    }
}
