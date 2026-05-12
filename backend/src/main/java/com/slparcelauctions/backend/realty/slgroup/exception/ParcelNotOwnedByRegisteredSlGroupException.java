package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised by the listing path when a parcel selected for a realty-group auction
 * is not owned by an SL group that is registered + verified to that realty
 * group. Caller supplies {@code detailMessage} to differentiate the
 * "personal land" vs. "wrong realty group" cases.
 */
@Getter
public class ParcelNotOwnedByRegisteredSlGroupException extends RuntimeException {
    public static final String CODE = "PARCEL_NOT_OWNED_BY_REGISTERED_SL_GROUP";

    private final UUID parcelUuid;
    private final UUID realtyGroupPublicId;

    public ParcelNotOwnedByRegisteredSlGroupException(
            UUID parcelUuid, UUID realtyGroupPublicId, String detailMessage) {
        super(detailMessage);
        this.parcelUuid = parcelUuid;
        this.realtyGroupPublicId = realtyGroupPublicId;
    }
}
