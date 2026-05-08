package com.slparcelauctions.backend.parceltag.exception;

import lombok.Getter;

/**
 * Raised when an admin {@code PATCH} or {@code toggle-active} targets a
 * tag {@code code} that doesn't exist. Mapped to HTTP 404 with
 * {@code code=PARCEL_TAG_NOT_FOUND}.
 */
@Getter
public class ParcelTagNotFoundException extends RuntimeException {

    private final String tagCode;

    public ParcelTagNotFoundException(String tagCode) {
        super("Parcel tag not found: " + tagCode);
        this.tagCode = tagCode;
    }
}
