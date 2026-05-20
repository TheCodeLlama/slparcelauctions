package com.slparcelauctions.backend.escrow.command;

/**
 * Source domain the command pertains to. {@code AUCTION_ESCROW} commands
 * affect an {@code Escrow} row (payout on confirm, refund on dispute /
 * freeze / timeout). {@code LISTING_FEE_REFUND} commands correspond to
 * {@link com.slparcelauctions.backend.auction.ListingFeeRefund} rows; Task 9
 * wires the queuing trigger, Task 7 carries the enum so the dispatcher and
 * callback paths handle both purposes in one code path.
 */
public enum TerminalCommandPurpose {
    AUCTION_ESCROW,
    LISTING_FEE_REFUND,
    ADMIN_WITHDRAWAL,
    /**
     * User-initiated wallet withdrawal (site- or terminal-initiated).
     * Recipient UUID is locked to the user's verified SL avatar — never
     * client-supplied. Callback flow is handled by
     * {@code WalletWithdrawalCallbackHandler}: success appends a
     * {@code WITHDRAW_COMPLETED} ledger row; failure after retry exhaustion
     * appends a {@code WITHDRAW_REVERSED} row crediting balance back.
     */
    WALLET_WITHDRAWAL,

    /**
     * Realty-group-initiated wallet withdrawal. Recipient UUID is always the
     * group leader's verified SL avatar. The originating
     * realty_group_ledger.id is encoded in the idempotency key as
     * "GWAL-{id}" (see RealtyGroupWalletService.withdraw). Callback flow is
     * handled by GroupWalletWithdrawalCallbackHandler.
     */
    GROUP_WALLET_WITHDRAWAL,

    /**
     * Dormancy-driven auto-return of a user's available wallet balance to the
     * user's verified SL avatar. Queued by
     * {@code UserWalletDormancyTask.autoReturn} at phase 4 of the dormancy
     * state machine. The originating user id + dormancy-start epoch are
     * encoded in the idempotency key as
     * {@code user-dormancy-{userId}-{startedAtEpochSeconds}}. See spec
     * docs/superpowers/specs/2026-05-19-user-wallet-dormancy-design.md.
     */
    USER_WALLET_DORMANCY_AUTO_RETURN
}
