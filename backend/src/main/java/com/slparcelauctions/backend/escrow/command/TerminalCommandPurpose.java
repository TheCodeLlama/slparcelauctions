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
    WALLET_WITHDRAWAL
}
