package com.slparcelauctions.backend.realty.exception;

public class InvitationAlreadyPendingException extends RuntimeException {
    public InvitationAlreadyPendingException() {
        super("A pending invitation already exists for this user in this group");
    }
}
