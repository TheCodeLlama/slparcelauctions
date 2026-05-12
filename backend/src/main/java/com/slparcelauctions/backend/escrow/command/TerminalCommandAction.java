package com.slparcelauctions.backend.escrow.command;

/**
 * Outbound action the terminal should execute. {@code PAYOUT} debits the
 * escrow account and credits the seller (less commission); {@code REFUND}
 * debits the escrow account and credits the auction winner. Persisted as the
 * enum {@code name()} in the {@code terminal_commands.action} column.
 */
public enum TerminalCommandAction {
    PAYOUT,
    REFUND,
    WITHDRAW,
    /**
     * Sub-project G -- pay L$ from the group wallet to a registered SL group
     * (rather than to an avatar). Bot fulfillment via
     * {@code Self.GiveGroupMoney(slGroupUuid, amount, memo)}. See spec §7.3 / §7.4.
     */
    WITHDRAW_GROUP
}
