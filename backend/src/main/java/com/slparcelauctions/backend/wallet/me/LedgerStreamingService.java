package com.slparcelauctions.backend.wallet.me;

import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.wallet.LedgerRow;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

import lombok.RequiredArgsConstructor;

/**
 * Streams collapsed ledger rows for the given user + filter. Caller MUST
 * close the stream (try-with-resources) and MUST hold an active read-only
 * transaction. Sorted DESC by {@code created_at}.
 *
 * <p>Used by the CSV export endpoint so multi-thousand-row downloads don't
 * hold the full result set in memory. Delegates to
 * {@link UserLedgerRepository#streamCollapsedForUser} so the streaming and
 * paginated paths share one query shape — same WHERE, same status CASE
 * EXPR, same row projection.
 */
@Service
@RequiredArgsConstructor
public class LedgerStreamingService {

    private final UserLedgerRepository ledgerRepository;

    @Transactional(readOnly = true)
    public Stream<LedgerRow> streamFiltered(Long userId, LedgerFilter filter) {
        return ledgerRepository.streamCollapsedForUser(userId, filter);
    }
}
