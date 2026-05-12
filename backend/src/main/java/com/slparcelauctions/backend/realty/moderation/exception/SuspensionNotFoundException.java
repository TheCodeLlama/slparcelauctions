package com.slparcelauctions.backend.realty.moderation.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Thrown when a lift operation references a suspension that does not exist, has
 * already been lifted, or does not belong to the addressed realty group. Surfaced
 * as 404 Not Found.
 *
 * <p>Sub-project F spec §9.
 */
@Getter
public class SuspensionNotFoundException extends RuntimeException {

    private final UUID suspensionPublicId;

    public SuspensionNotFoundException(UUID suspensionPublicId) {
        super("Realty group suspension not found: " + suspensionPublicId);
        this.suspensionPublicId = suspensionPublicId;
    }
}
