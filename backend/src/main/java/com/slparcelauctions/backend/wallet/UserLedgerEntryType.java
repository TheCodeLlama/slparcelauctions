package com.slparcelauctions.backend.wallet;

/**
 * Discriminator for {@link UserLedgerEntry} rows. Direction (debit vs credit)
 * is implicit in the entry_type and {@code amount} is positive, except for
 * {@link #ADJUSTMENT} whose sign carries the direction of an admin correction.
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
     * {@code AdminWalletService.adjust}). Used for forensic corrections
     * after manual reconciliation, refunds-of-last-resort, etc.
     *
     * <p>Direction is encoded in the sign of {@code amount} (positive credit,
     * negative debit); the {@code user_ledger_amount_check} constraint admits
     * a negative amount only for this entry type (see migration V38).
     */
    ADJUSTMENT,

    /**
     * Sub-project D -- listing-agent's slice of agent_fee_amt at escrow completion.
     * Credited to the listing agent's user wallet after the seller's PAYOUT terminal
     * command succeeds. See spec §7.2 (Site B).
     */
    AGENT_FEE_CREDIT,

    /**
     * Sub-project E -- listing agent's group-sale commission slice at escrow completion.
     * Credited to the listing agent's user wallet after the group-sale group-wallet PAYOUT
     * succeeds. {@code amount = floor((final_bid - platform_commission) * agent_commission_rate)}.
     * See spec §9.6.
     */
    AGENT_COMMISSION_CREDIT,

    /**
     * Sub-project H -- debit on a member-initiated transfer from this user's personal
     * SLParcels wallet into a realty group's wallet. Paired with a {@code MEMBER_DEPOSIT}
     * row on {@code realty_group_ledger} that shares the same {@code idempotencyKey}.
     * {@code description} carries the group display name (and optional user-supplied memo).
     */
    GROUP_WALLET_DEPOSIT_DEBIT
}
