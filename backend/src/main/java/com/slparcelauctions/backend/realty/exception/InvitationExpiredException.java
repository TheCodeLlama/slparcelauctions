package com.slparcelauctions.backend.realty.exception;

import java.util.UUID;

import lombok.Getter;

@Getter
public class InvitationExpiredException extends RuntimeException {
    private final UUID invitationPublicId;

    public InvitationExpiredException(UUID invitationPublicId) {
        super("Invitation is no longer pending: " + invitationPublicId);
        this.invitationPublicId = invitationPublicId;
    }
}
