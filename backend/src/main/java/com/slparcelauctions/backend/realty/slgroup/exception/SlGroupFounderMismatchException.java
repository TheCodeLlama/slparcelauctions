package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised when the founder of the SL group reported by the SL World API does
 * not match the avatar that initiated registration. Either side may be
 * {@code null} when the SL World response omits a founder or the caller
 * principal cannot be resolved.
 */
@Getter
public class SlGroupFounderMismatchException extends RuntimeException {
    public static final String CODE = "SL_GROUP_FOUNDER_MISMATCH";

    private final UUID reportedAvatarUuid;
    private final UUID expectedFounderUuid;

    public SlGroupFounderMismatchException(UUID reportedAvatarUuid, UUID expectedFounderUuid) {
        super("SL group founder mismatch: reported=" + reportedAvatarUuid
                + ", expected=" + expectedFounderUuid);
        this.reportedAvatarUuid = reportedAvatarUuid;
        this.expectedFounderUuid = expectedFounderUuid;
    }
}
