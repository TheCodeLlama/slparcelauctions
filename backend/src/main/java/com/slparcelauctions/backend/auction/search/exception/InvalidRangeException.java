package com.slparcelauctions.backend.auction.search.exception;

import lombok.Getter;

@Getter
public class InvalidRangeException extends RuntimeException {
    private final String field;

    public InvalidRangeException(String field) {
        super(field + " range has min > max");
        this.field = field;
    }
}
