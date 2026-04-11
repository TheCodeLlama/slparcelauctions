package com.slparcelauctions.backend.common.exception;

/**
 * Thrown when a requested resource does not exist. Maps to HTTP 404 via
 * {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) { super(message); }
}
