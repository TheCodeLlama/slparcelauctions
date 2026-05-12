package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised when a verification code lookup finds a row whose verification window
 * has elapsed. {@code publicId} may be {@code null} when the code lookup itself
 * fails before the registration row is resolved.
 */
@Getter
public class SlGroupVerificationExpiredException extends RuntimeException {
    public static final String CODE = "SL_GROUP_VERIFICATION_EXPIRED";

    private final UUID publicId;

    public SlGroupVerificationExpiredException(UUID publicId) {
        super(publicId == null
                ? "SL group verification code has expired."
                : "SL group verification has expired: " + publicId);
        this.publicId = publicId;
    }
}
