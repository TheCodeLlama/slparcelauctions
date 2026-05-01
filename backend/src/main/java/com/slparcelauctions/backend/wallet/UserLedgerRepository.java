package com.slparcelauctions.backend.wallet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
