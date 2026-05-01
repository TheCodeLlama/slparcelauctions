package com.slparcelauctions.backend.wallet.sl;

/**
 * Machine-readable reasons for {@link SlWalletResponse}. Grouped by the
 * status they accompany:
 *
 * <ul>
 *   <li><b>REFUND (deposit only)</b>: {@link #UNKNOWN_PAYER}, {@link #USER_FROZEN}.
 *       Terminal MUST {@code llTransferLindenDollars} the L$ back.</li>
 *   <li><b>REFUND_BLOCKED (withdraw-request only)</b>:
 *       {@link #INSUFFICIENT_BALANCE}, {@link #USER_FROZEN}, {@link #NOT_LINKED}.
 *       Terminal does NOT refund — no L$ was paid.</li>
 *   <li><b>ERROR (any)</b>: {@link #BAD_HEADERS}, {@link #SECRET_MISMATCH},
 *       {@link #UNKNOWN_TERMINAL}. Terminal does NOT refund (attacker-probe
 *       defense).</li>
 * </ul>
 */
public enum SlWalletResponseReason {
    // REFUND variants (deposit)
    UNKNOWN_PAYER,
    // REFUND_BLOCKED variants (withdraw-request)
    INSUFFICIENT_BALANCE,
    NOT_LINKED,
    // Shared (REFUND + REFUND_BLOCKED)
    USER_FROZEN,
    // ERROR variants
    BAD_HEADERS,
    SECRET_MISMATCH,
    UNKNOWN_TERMINAL
}
