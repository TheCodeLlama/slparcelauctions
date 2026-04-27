package com.slparcelauctions.backend.admin.users.exception;

public class SelfDemoteException extends RuntimeException {
    public SelfDemoteException() {
        super("Cannot demote yourself.");
    }
}
