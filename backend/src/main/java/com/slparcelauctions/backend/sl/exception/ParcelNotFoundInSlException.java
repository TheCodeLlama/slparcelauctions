package com.slparcelauctions.backend.sl.exception;

import java.util.UUID;

/** World API returned 404 for the given parcel UUID. Maps to HTTP 404. */
public class ParcelNotFoundInSlException extends RuntimeException {

    private final UUID parcelUuid;

    public ParcelNotFoundInSlException(UUID parcelUuid) {
        super("Parcel not found in SL: " + parcelUuid);
        this.parcelUuid = parcelUuid;
    }

    public UUID getParcelUuid() {
        return parcelUuid;
    }
}
