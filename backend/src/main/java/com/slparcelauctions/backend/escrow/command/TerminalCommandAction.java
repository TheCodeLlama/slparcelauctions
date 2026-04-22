package com.slparcelauctions.backend.escrow.command;

/**
 * Outbound action the terminal should execute. {@code PAYOUT} debits the
 * escrow account and credits the seller (less commission); {@code REFUND}
 * debits the escrow account and credits the auction winner. Persisted as the
 * enum {@code name()} in the {@code terminal_commands.action} column.
 */
public enum TerminalCommandAction {
    PAYOUT,
    REFUND
}
