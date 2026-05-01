package com.slparcelauctions.backend.wallet.me;

import java.time.OffsetDateTime;
import java.util.List;

import com.slparcelauctions.backend.wallet.UserLedgerEntryType;

/**
 * Filter parameters for the {@code GET /me/wallet/ledger} endpoint.
 *
 * <p>All fields are nullable — a {@code null} field means "no filter on this
 * dimension". {@link #from} is treated as inclusive ({@code >=}); {@link #to}
 * is treated as exclusive ({@code <}) to make day-bucket queries (e.g.,
 * {@code from=2026-04-01,to=2026-05-01}) cover the full month without overlap.
 */
public record LedgerFilter(
        List<UserLedgerEntryType> entryTypes,
        OffsetDateTime from,
        OffsetDateTime to,
        Long amountMin,
        Long amountMax
) {}
