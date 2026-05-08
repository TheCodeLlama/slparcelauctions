package com.slparcelauctions.backend.parceltag.exception;

import lombok.Getter;

@Getter
public class ParcelTagCategoryNotFoundException extends RuntimeException {
    private final String categoryCode;

    public ParcelTagCategoryNotFoundException(String categoryCode) {
        super("Parcel tag category not found: " + categoryCode);
        this.categoryCode = categoryCode;
    }
}
