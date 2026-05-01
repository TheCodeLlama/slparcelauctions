package com.slparcelauctions.backend.wallet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link UserLedgerEntry} append-only rows.
 *
 * <p><strong>Specification queries on this repository MUST go through
 * {@link com.slparcelauctions.backend.wallet.me.LedgerSpecifications#forUser}
 * so the {@code userId} scope is never accidentally omitted.</strong>
 * Direct calls to {@code findAll(Specification)} without that wrapper
 * can leak ledger entries across users — never write one in a new
 * call site.
 */
public interface UserLedgerRepository
        extends JpaRepository<UserLedgerEntry, Long>,
                JpaSpecificationExecutor<UserLedgerEntry> {

    /**
     * Idempotency lookup for the SL-headers wallet endpoints
     * ({@code /sl/wallet/deposit}, {@code /sl/wallet/withdraw-request}).
     * The terminal generates {@code sl_transaction_id} once via
     * {@code llGenerateKey()} and reuses it across retries; finding an
     * existing row means we replay the original response.
     */
    Optional<UserLedgerEntry> findBySlTransactionId(String slTransactionId);

    /**
     * Idempotency lookup for the user-facing wallet endpoints
     * ({@code /me/wallet/withdraw}, {@code /me/wallet/pay-penalty}).
     * Client supplies {@code idempotencyKey} on the request body.
     */
    Optional<UserLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    /**
     * Most recent 50 entries for the wallet panel's recent-activity view.
     */
    List<UserLedgerEntry> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Cursor pagination for the {@code /me/wallet/ledger} endpoint.
     */
    Page<UserLedgerEntry> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Sum of withdrawal amounts that are debited but not yet terminated
     * (no paired {@code WITHDRAW_COMPLETED} or {@code WITHDRAW_REVERSED}
     * row referencing the queued row's id). Drives the "Queued for
     * Withdrawal" indicator in the wallet view.
     *
     * <p>Implementation: each {@code WITHDRAW_COMPLETED}/{@code _REVERSED}
     * row sets {@code refType='USER_LEDGER'} and {@code refId} = the
     * queued row's id, so we exclude any queued row whose id appears as
     * a refId in a terminal row for the same user.
     */
    @Query("""
        SELECT COALESCE(SUM(q.amount), 0)
          FROM UserLedgerEntry q
         WHERE q.userId = :userId
           AND q.entryType = com.slparcelauctions.backend.wallet.UserLedgerEntryType.WITHDRAW_QUEUED
           AND NOT EXISTS (
                SELECT 1
                  FROM UserLedgerEntry t
                 WHERE t.userId = q.userId
                   AND t.refType = 'USER_LEDGER'
                   AND t.refId = q.id
                   AND t.entryType IN (
                        com.slparcelauctions.backend.wallet.UserLedgerEntryType.WITHDRAW_COMPLETED,
                        com.slparcelauctions.backend.wallet.UserLedgerEntryType.WITHDRAW_REVERSED)
           )
        """)
    long sumPendingWithdrawals(@Param("userId") Long userId);
}
