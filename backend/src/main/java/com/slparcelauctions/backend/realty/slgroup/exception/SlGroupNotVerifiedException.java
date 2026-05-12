package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised when an operation requires a verified SL group registration but the
 * registration row is still in a non-verified state (pending / failed / expired).
 */
@Getter
public class SlGroupNotVerifiedException extends RuntimeException {
    public static final String CODE = "SL_GROUP_NOT_VERIFIED";

    private final UUID publicId;

    public SlGroupNotVerifiedException(UUID publicId) {
        super("SL group registration is not verified: " + publicId);
        this.publicId = publicId;
    }
}
