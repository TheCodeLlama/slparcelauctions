package com.slparcelauctions.backend.realty.exception;

public class LeaderCannotLeaveException extends RuntimeException {
    public LeaderCannotLeaveException() {
        super("Leader cannot leave the group; transfer leadership or dissolve first");
    }
}
