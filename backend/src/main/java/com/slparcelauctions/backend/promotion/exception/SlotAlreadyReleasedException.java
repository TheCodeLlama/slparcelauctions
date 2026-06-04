package com.slparcelauctions.backend.promotion.exception;

import java.util.UUID;

/**
 * Admin attempted to move a slot that has already been released. Maps to
 * HTTP 409 / problem code {@code SLOT_ALREADY_RELEASED}.
 */
public class SlotAlreadyReleasedException extends RuntimeException {
    private final UUID slotPublicId;

    public SlotAlreadyReleasedException(UUID slotPublicId) {
        super("Slot " + slotPublicId + " is already released");
        this.slotPublicId = slotPublicId;
    }

    public UUID getSlotPublicId() {
        return slotPublicId;
    }
}
