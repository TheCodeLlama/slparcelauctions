package com.slparcelauctions.backend.wallet;

/**
 * A user-facing ledger row: a {@link UserLedgerEntry} with the optional
 * collapsed withdrawal status.
 *
 * <p>The {@link UserLedgerRepository#findCollapsedForUser} query returns
 * these. {@code withdrawalStatus} is non-null only for
 * {@link UserLedgerEntryType#WITHDRAW_QUEUED} entries — for every other
 * entry type it is null.
 */
public record LedgerRow(UserLedgerEntry entry, WithdrawalStatus withdrawalStatus) {}
