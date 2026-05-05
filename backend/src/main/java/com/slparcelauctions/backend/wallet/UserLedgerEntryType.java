package com.slparcelauctions.backend.wallet;

/**
 * Discriminator for {@link UserLedgerEntry} rows. Direction (debit vs credit)
 * is implicit in the entry_type — the entry's {@code amount} is always positive.
 *
 * <p>See spec docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.2.
 */
public enum UserLedgerEntryType {
    /**
     * Resident-initiated L$ deposit via the in-world SLParcels Terminal's
     * {@code money()} handler. {@code sl_transaction_id} is the LSL
     * {@code llGenerateKey()} value that deduplicates retries.
     * Increases {@code balance_lindens}.
     */
    DEPOSIT,

    /**
     * Withdrawal initiated (site or terminal). {@code balance_lindens}
     * decremented immediately; the {@code TerminalCommand} fires
     * asynchronously. Resolution is a separate row
     * ({@link #WITHDRAW_COMPLETED} or {@link #WITHDRAW_REVERSED}).
     */
    WITHDRAW_QUEUED,

    /**
     * Successful withdrawal fulfillment — {@code llTransferLindenDollars}
     * succeeded and the {@code /sl/escrow/payout-result} callback acknowledged.
     * Pure ledger row; balance is unchanged from the {@link #WITHDRAW_QUEUED}
     * snapshot.
     */
    WITHDRAW_COMPLETED,

    /**
     * Withdrawal could not be fulfilled (terminal pool exhausted retries,
     * recipient avatar banned by Linden, etc). {@code balance_lindens}
     * credited back to restore pre-withdraw state.
     */
    WITHDRAW_REVERSED,

    /**
     * Bid placement reserved L$ from balance for an active high bid.
     * {@code reserved_lindens} increments; {@code balance_lindens}
     * unchanged (reservations are partitions of balance, not separate L$).
     */
    BID_RESERVED,

    /**
     * Bid reservation released — outbid, auction cancelled, fraud-frozen,
     * escrow funded, or user banned. {@code reserved_lindens} decrements;
     * {@code balance_lindens} unchanged.
     */
    BID_RELEASED,

    /**
     * Auction-end auto-fund debit (or BIN immediate fund). The winner's
     * reservation is released ({@link #BID_RELEASED} row appended in the
     * same transaction) and {@code balance_lindens} decremented by the
     * final bid amount.
     */
    ESCROW_DEBIT,

    /**
     * Escrow refund credited back to wallet (escrow expired during transfer
     * deadline or dispute resolved in winner's favor). Replaces the prior
     * {@code TerminalCommand{action=REFUND}} flow.
     */
    ESCROW_REFUND,

    /**
     * Listing-fee debit on DRAFT → DRAFT_PAID transition. Issued by
     * {@code POST /me/auctions/{id}/pay-listing-fee}.
     */
    LISTING_FEE_DEBIT,

    /**
     * Listing-fee refund credited back to wallet (auction cancelled with no
     * bids, etc, per existing Epic 03/05 refund rules). Replaces the prior
     * {@code TerminalCommand{action=REFUND}} flow.
     */
    LISTING_FEE_REFUND,

    /**
     * Penalty payment debit. Voluntary; users can carry an outstanding
     * penalty indefinitely. The penalty only blocks new bids/listings,
     * never auto-deducted.
     */
    PENALTY_DEBIT,

    /**
     * Admin-issued ledger adjustment. {@code created_by_admin_id} required
     * (constraint enforced at the application layer; see
     * {@code WalletService.adminAdjustment}). Used for forensic corrections
     * after manual reconciliation, refunds-of-last-resort, etc.
     */
    ADJUSTMENT
}
