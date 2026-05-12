package com.slparcelauctions.backend.realty.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised by {@code RealtyGroupService.dissolveGroup} when the leader attempts
 * to dissolve a realty group that still has registered SL groups attached.
 * The SL group registrations must be unregistered first.
 */
@Getter
public class SlGroupRegisteredBlocksDissolveException extends RuntimeException {
    public static final String CODE = "SL_GROUPS_BLOCK_DISSOLVE";

    private final UUID realtyGroupPublicId;
    private final long count;

    public SlGroupRegisteredBlocksDissolveException(UUID realtyGroupPublicId, long count) {
        super("Cannot dissolve realty group " + realtyGroupPublicId
                + " while " + count + " SL group(s) are still registered.");
        this.realtyGroupPublicId = realtyGroupPublicId;
        this.count = count;
    }
}
