package com.slparcelauctions.backend.realty.moderation.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Thrown when an admin tries to issue a new suspension against a realty group that
 * already has an active (unlifted, unexpired) suspension. Surfaced as 409 Conflict.
 *
 * <p>Sub-project F spec §9.
 */
@Getter
public class SuspensionAlreadyActiveException extends RuntimeException {

    private final UUID groupPublicId;

    public SuspensionAlreadyActiveException(UUID groupPublicId) {
        super("An active suspension already exists for realty group: " + groupPublicId);
        this.groupPublicId = groupPublicId;
    }
}
