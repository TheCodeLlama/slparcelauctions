package com.slparcelauctions.backend.realty.exception;

public class AlreadyMemberException extends RuntimeException {
    public AlreadyMemberException() {
        super("User is already a member of this group");
    }
}
