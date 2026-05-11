package com.slparcelauctions.backend.realty.exception;

import java.util.UUID;

import lombok.Getter;

@Getter
public class InvitationNotFoundException extends RuntimeException {
    private final UUID invitationPublicId;

    public InvitationNotFoundException(UUID invitationPublicId) {
        super("Invitation not found: " + invitationPublicId);
        this.invitationPublicId = invitationPublicId;
    }
}
