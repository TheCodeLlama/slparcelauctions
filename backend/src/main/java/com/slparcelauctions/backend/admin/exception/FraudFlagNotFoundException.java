package com.slparcelauctions.backend.admin.exception;

public class FraudFlagNotFoundException extends RuntimeException {
    public FraudFlagNotFoundException(Long flagId) {
        super("FraudFlag not found: " + flagId);
    }
}
