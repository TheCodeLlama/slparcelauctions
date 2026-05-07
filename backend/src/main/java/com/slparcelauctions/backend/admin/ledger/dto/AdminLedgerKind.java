package com.slparcelauctions.backend.admin.ledger.dto;

/**
 * The five money-event source tables that feed the admin global ledger view.
 * Each value corresponds to one arm of the UNION ALL native query in
 * {@code AdminLedgerQueryRepository}. The frontend sends these as a
 * repeatable {@code kinds=} query param to scope which arms are compiled
 * into the SQL at request time; an empty set means "all five".
 */
public enum AdminLedgerKind {
    USER_LEDGER,
    ESCROW_TXN,
    TERMINAL_CMD,
    WITHDRAWAL,
    BID_RESERVATION
}
