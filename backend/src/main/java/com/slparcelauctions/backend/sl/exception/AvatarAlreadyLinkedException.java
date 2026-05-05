package com.slparcelauctions.backend.sl.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Thrown when an SL avatar UUID is already linked to a different SLParcels account.
 * Maps to HTTP 409 in {@link com.slparcelauctions.backend.sl.SlExceptionHandler}.
 *
 * <p>Two construction paths: the pre-check inside
 * {@link com.slparcelauctions.backend.sl.SlVerificationService} supplies the UUID;
 * the unique-constraint race path inside {@code SlExceptionHandler} maps the JDBC
 * {@code DataIntegrityViolationException} to a UUID-less variant for response shaping.
 */
@Getter
public class AvatarAlreadyLinkedException extends RuntimeException {
    private final UUID avatarUuid;

    public AvatarAlreadyLinkedException(UUID avatarUuid) {
        super("SL avatar " + avatarUuid + " is already linked to another account.");
        this.avatarUuid = avatarUuid;
    }

    public AvatarAlreadyLinkedException() {
        super("SL avatar is already linked to another account.");
        this.avatarUuid = null;
    }
}
