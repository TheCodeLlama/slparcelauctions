package com.slparcelauctions.backend.escrow.broadcast;

/**
 * Abstraction over the WebSocket broadcast layer for escrow events. The
 * production implementation is {@link StompEscrowBroadcastPublisher}; a
 * no-op fallback steps aside for slices that don't need real broadcasts.
 * Every publish method is safe to invoke from a
 * {@code TransactionSynchronization.afterCommit} callback. Method set
 * grows per task as new envelope variants land.
 */
public interface EscrowBroadcastPublisher {

    /**
     * Publishes an {@link EscrowCreatedEnvelope} after the auction-end
     * transaction commits. Called by {@code EscrowService.createForEndedAuction}
     * via an {@code afterCommit} callback so subscribers never observe a row
     * that gets rolled back with the close.
     */
    void publishCreated(EscrowCreatedEnvelope envelope);

    /**
     * Publishes an {@link EscrowDisputedEnvelope} after the dispute-filing
     * transaction commits. Called by {@code EscrowService.fileDispute} via an
     * {@code afterCommit} callback so subscribers never observe a dispute
     * transition that gets rolled back.
     */
    void publishDisputed(EscrowDisputedEnvelope envelope);

    /**
     * Publishes an {@link EscrowFundedEnvelope} after the payment-receiving
     * transaction commits. Called by {@code EscrowService.acceptPayment} via
     * an {@code afterCommit} callback so subscribers never see a FUNDED →
     * TRANSFER_PENDING transition that gets rolled back on a late DB failure.
     */
    void publishFunded(EscrowFundedEnvelope envelope);

    /**
     * Publishes an {@link EscrowTransferConfirmedEnvelope} after the
     * ownership-monitor transaction commits. Called by
     * {@code EscrowService.confirmTransfer} via an {@code afterCommit}
     * callback so subscribers never see a confirmation that gets rolled
     * back on a late DB failure.
     */
    void publishTransferConfirmed(EscrowTransferConfirmedEnvelope envelope);

    /**
     * Publishes an {@link EscrowFrozenEnvelope} after the freeze transaction
     * commits. Called by {@code EscrowService.freezeForFraud} via an
     * {@code afterCommit} callback so subscribers never see a freeze that
     * gets rolled back.
     */
    void publishFrozen(EscrowFrozenEnvelope envelope);

    /**
     * Publishes an {@link EscrowCompletedEnvelope} after the terminal's
     * payout-result callback transaction commits. Called by
     * {@code TerminalCommandService.applyCallback} via an
     * {@code afterCommit} callback so subscribers never observe a completion
     * that gets rolled back on a late DB failure.
     */
    void publishCompleted(EscrowCompletedEnvelope envelope);

    /**
     * Publishes an {@link EscrowRefundCompletedEnvelope} after a REFUND
     * command's callback transaction commits. Called by
     * {@code TerminalCommandService.applyCallback} so subscribers never
     * observe a rolled-back refund completion.
     */
    void publishRefundCompleted(EscrowRefundCompletedEnvelope envelope);

    /**
     * Publishes an {@link EscrowPayoutStalledEnvelope} after a terminal
     * command exhausts its retry budget and is flipped to
     * {@code requires_manual_review = true}. Called by both
     * {@code TerminalCommandService.applyCallback} (when the terminal
     * reports a final failure) and
     * {@code TerminalCommandDispatcherTask.dispatchOne} (when transport
     * failures run the counter past {@code MAX_ATTEMPTS}).
     */
    void publishPayoutStalled(EscrowPayoutStalledEnvelope envelope);
}
