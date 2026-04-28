package com.slparcelauctions.backend.user.deletion.exception;

import java.util.List;

public class OpenEscrowsException extends RuntimeException implements DeletionPreconditionException {
    private final List<Long> escrowIds;

    public OpenEscrowsException(List<Long> escrowIds) {
        super("User has " + escrowIds.size() + " open escrow(s)");
        this.escrowIds = escrowIds;
    }

    @Override public String getCode() { return "OPEN_ESCROWS"; }
    @Override public List<Long> getBlockingIds() { return escrowIds; }
}
