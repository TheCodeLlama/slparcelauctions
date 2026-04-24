package com.slparcelauctions.backend.auction.search.exception;

import lombok.Getter;

@Getter
public class InvalidFilterValueException extends RuntimeException {
    private final String field;
    private final String rejectedValue;
    private final String allowedValues;

    public InvalidFilterValueException(String field, String rejectedValue, String allowedValues) {
        super(field + " must be one of " + allowedValues + " (got: " + rejectedValue + ")");
        this.field = field;
        this.rejectedValue = rejectedValue;
        this.allowedValues = allowedValues;
    }
}
