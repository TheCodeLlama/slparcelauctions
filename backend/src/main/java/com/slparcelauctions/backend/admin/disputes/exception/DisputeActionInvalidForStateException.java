package com.slparcelauctions.backend.admin.disputes.exception;

import com.slparcelauctions.backend.admin.disputes.AdminDisputeAction;

public class DisputeActionInvalidForStateException extends RuntimeException {
    public DisputeActionInvalidForStateException(
            long escrowId, AdminDisputeAction action, String state) {
        super("Action " + action + " not valid for escrow " + escrowId
                + " in state " + state);
    }
}
