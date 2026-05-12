package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Sub-project G section 14 -- reverse-search ban-evasion gate. Thrown when a
 * caller attempts to register an SL group UUID that already has a registration
 * on a suspended realty group (active row in {@code realty_group_suspensions}
 * with {@code lifted_at IS NULL}). Mapped to 409 Conflict with code
 * {@code SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP}.
 */
@Getter
public class SlGroupRegisteredToSuspendedGroupException extends RuntimeException {

    public static final String CODE = "SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP";

    private final UUID slGroupUuid;

    public SlGroupRegisteredToSuspendedGroupException(UUID slGroupUuid) {
        super("This SL group is registered to a suspended SLPA realty group. Contact support.");
        this.slGroupUuid = slGroupUuid;
    }
}
