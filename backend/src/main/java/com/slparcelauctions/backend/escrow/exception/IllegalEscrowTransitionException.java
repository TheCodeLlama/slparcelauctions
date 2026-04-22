package com.slparcelauctions.backend.escrow.exception;

import com.slparcelauctions.backend.escrow.EscrowState;

import lombok.Getter;

@Getter
public class IllegalEscrowTransitionException extends RuntimeException {

    private final Long escrowId;
    private final EscrowState currentState;
    private final EscrowState attemptedTarget;

    public IllegalEscrowTransitionException(
            Long escrowId, EscrowState currentState, EscrowState attemptedTarget) {
        super("Escrow " + escrowId + " cannot transition from "
                + currentState + " to " + attemptedTarget);
        this.escrowId = escrowId;
        this.currentState = currentState;
        this.attemptedTarget = attemptedTarget;
    }
}
