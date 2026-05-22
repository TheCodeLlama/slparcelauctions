package com.slparcelauctions.backend.common.image;

import lombok.Getter;

/**
 * Raised by {@link ImageVariant#parse(String)} when a controller receives a
 * variant token outside the {@code light} / {@code dark} pair. Mapped to
 * {@code 400 INVALID_VARIANT} by the global exception handler. The
 * {@link #getValue()} accessor carries the rejected token so the
 * {@code ProblemDetail} body can echo it back for debugging.
 */
@Getter
public class InvalidVariantException extends RuntimeException {

    private final String value;

    public InvalidVariantException(String value) {
        super("Invalid variant: " + value);
        this.value = value;
    }
}
