package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised by {@code SlGroupService.register} when the supplied SL group UUID is
 * already registered to a realty group (active or unregistered=false row).
 */
@Getter
public class SlGroupAlreadyRegisteredException extends RuntimeException {
    public static final String CODE = "SL_GROUP_ALREADY_REGISTERED";

    private final UUID slGroupUuid;

    public SlGroupAlreadyRegisteredException(UUID slGroupUuid) {
        super("SL group " + slGroupUuid + " is already registered to a realty group.");
        this.slGroupUuid = slGroupUuid;
    }
}
