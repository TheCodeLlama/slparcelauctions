package com.slparcelauctions.backend.parceltag.exception;

import lombok.Getter;

/**
 * Raised when an admin tries to {@code POST /api/v1/admin/parcel-tags}
 * with a {@code code} that already exists. Mapped to HTTP 409 with
 * {@code code=PARCEL_TAG_CODE_CONFLICT}.
 */
@Getter
public class ParcelTagCodeConflictException extends RuntimeException {

    private final String tagCode;

    public ParcelTagCodeConflictException(String tagCode) {
        super("Parcel tag already exists: " + tagCode);
        this.tagCode = tagCode;
    }
}
