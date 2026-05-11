package com.slparcelauctions.backend.realty.exception;

public class LeaderTransferTargetNotMemberException extends RuntimeException {
    public LeaderTransferTargetNotMemberException() {
        super("Leadership transfer target must already be a member of the group");
    }
}
