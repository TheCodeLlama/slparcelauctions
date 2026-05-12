package com.slparcelauctions.backend.realty.wallet.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Sub-project G §7.3 -- caller asked to withdraw to {@code SL_GROUP} but the
 * realty group has no currently-registered SL group (no row, or the registration
 * was force-unregistered). Surfaced as 422 UNPROCESSABLE_ENTITY.
 */
@Getter
public class SlGroupNotRegisteredException extends RuntimeException {

    private final UUID realtyGroupPublicId;

    public SlGroupNotRegisteredException(UUID realtyGroupPublicId) {
        super("Realty group " + realtyGroupPublicId + " has no registered SL group");
        this.realtyGroupPublicId = realtyGroupPublicId;
    }
}
