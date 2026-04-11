package com.slparcelauctions.backend.common.exception;

/**
 * Thrown when a resource existed but has been permanently removed. Maps to HTTP 410 via
 * {@link GlobalExceptionHandler}.
 */
public class ResourceGoneException extends RuntimeException {
    public ResourceGoneException(String message) { super(message); }
}
