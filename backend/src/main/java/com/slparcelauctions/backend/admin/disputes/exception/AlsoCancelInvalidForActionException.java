package com.slparcelauctions.backend.admin.disputes.exception;

import com.slparcelauctions.backend.admin.disputes.AdminDisputeAction;

public class AlsoCancelInvalidForActionException extends RuntimeException {
    public AlsoCancelInvalidForActionException(AdminDisputeAction action) {
        super("alsoCancelListing only valid with RESET_TO_FUNDED action (was: " + action + ")");
    }
}
