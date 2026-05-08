package com.slparcelauctions.backend.parceltag.exception;

import lombok.Getter;

@Getter
public class ParcelTagCategoryCodeConflictException extends RuntimeException {
    private final String categoryCode;

    public ParcelTagCategoryCodeConflictException(String categoryCode) {
        super("Parcel tag category already exists: " + categoryCode);
        this.categoryCode = categoryCode;
    }
}
