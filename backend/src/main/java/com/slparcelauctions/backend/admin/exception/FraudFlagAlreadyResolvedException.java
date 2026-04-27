package com.slparcelauctions.backend.admin.exception;

import lombok.Getter;

@Getter
public class FraudFlagAlreadyResolvedException extends RuntimeException {
    private final Long flagId;

    public FraudFlagAlreadyResolvedException(Long flagId) {
        super("FraudFlag " + flagId + " already resolved");
        this.flagId = flagId;
    }
}
