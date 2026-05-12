package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised by {@code SlGroupService.unregister} when an attempt is made to
 * unregister an SL group registration that still has active listings tied to it.
 */
@Getter
public class RegisteredSlGroupHasListingsException extends RuntimeException {
    public static final String CODE = "REGISTERED_SL_GROUP_HAS_LISTINGS";

    private final UUID slGroupPublicId;

    public RegisteredSlGroupHasListingsException(UUID slGroupPublicId) {
        super("Registered SL group still has active listings: " + slGroupPublicId);
        this.slGroupPublicId = slGroupPublicId;
    }
}
