package com.slparcelauctions.backend.wallet.me;

import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.slparcelauctions.backend.wallet.LedgerRow;

/**
 * Custom repository fragment for the user-facing collapsed ledger view.
 * Mixed into {@link com.slparcelauctions.backend.wallet.UserLedgerRepository}
 * via Spring Data's custom-fragment mechanism.
 *
 * <p>Both methods exclude {@code WITHDRAW_COMPLETED} and
 * {@code WITHDRAW_REVERSED} rows entirely — the user sees a single
 * {@code WITHDRAW_QUEUED} row decorated with the
 * {@link com.slparcelauctions.backend.wallet.WithdrawalStatus} of its
 * terminal sibling, computed in-query via SQL EXISTS subqueries.
 */
public interface LedgerCollapsedRepository {

    /**
     * Paginated collapsed-ledger query for {@code GET /me/wallet/ledger}.
     * Sorted DESC by {@code createdAt}. Filter composes via
     * {@link LedgerFilter}.
     */
    Page<LedgerRow> findCollapsedForUser(
            Long userId, LedgerFilter filter, Pageable pageable);

    /**
     * Streaming collapsed-ledger query for the CSV export endpoint. Caller
     * MUST close the stream and MUST run inside a read-only transaction.
     */
    Stream<LedgerRow> streamCollapsedForUser(Long userId, LedgerFilter filter);
}
