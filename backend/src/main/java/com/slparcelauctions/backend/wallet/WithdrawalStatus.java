package com.slparcelauctions.backend.wallet;

/**
 * Frontend-facing status of a withdrawal as a single logical row.
 *
 * <p>The append-only ledger model writes three rows per logical withdrawal
 * ({@code WITHDRAW_QUEUED}, then either {@code WITHDRAW_COMPLETED} or
 * {@code WITHDRAW_REVERSED}). The user-facing wallet activity view collapses
 * those into one display row: the {@code WITHDRAW_QUEUED} entry, decorated
 * with the status of its terminal sibling. This enum names that decoration.
 *
 * <p>Null status is reserved for non-withdrawal ledger entries — the field
 * on {@code LedgerEntryDto} is null for {@code DEPOSIT}, {@code BID_RESERVED},
 * {@code ESCROW_DEBIT}, etc.
 */
public enum WithdrawalStatus {
    /** No paired terminal row yet — withdrawal is in flight. */
    PENDING,
    /** Paired {@code WITHDRAW_COMPLETED} row exists — funds delivered. */
    COMPLETED,
    /** Paired {@code WITHDRAW_REVERSED} row exists — funds refunded. */
    REVERSED;
}
