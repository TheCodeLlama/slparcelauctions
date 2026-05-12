package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised when an SL group registration ({@code realty_group_sl_groups} row)
 * lookup misses by public id. Used by the admin force-unregister / recheck /
 * drift-ack paths in sub-project F so the caller surfaces a 404 instead of
 * masking the miss as a 500.
 */
@Getter
public class SlGroupNotFoundException extends RuntimeException {
    public static final String CODE = "SL_GROUP_NOT_FOUND";

    private final UUID publicId;

    public SlGroupNotFoundException(UUID publicId) {
        super("SL group registration not found: " + publicId);
        this.publicId = publicId;
    }
}
