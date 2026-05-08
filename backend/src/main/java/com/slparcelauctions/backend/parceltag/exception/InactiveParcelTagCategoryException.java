package com.slparcelauctions.backend.parceltag.exception;

import lombok.Getter;

/**
 * Raised when a tag create/update references a category that exists but is
 * currently {@code active=false}. Mapped to HTTP 400 with
 * {@code code=INACTIVE_PARCEL_TAG_CATEGORY}.
 */
@Getter
public class InactiveParcelTagCategoryException extends RuntimeException {
    private final String categoryCode;

    public InactiveParcelTagCategoryException(String categoryCode) {
        super("Parcel tag category is inactive: " + categoryCode);
        this.categoryCode = categoryCode;
    }
}
