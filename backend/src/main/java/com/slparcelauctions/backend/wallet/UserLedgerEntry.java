package com.slparcelauctions.backend.wallet;

import com.slparcelauctions.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Append-only per-user wallet ledger entry.
 *
 * <p>Every L$ movement to or from a user's wallet appends a row here. Rows
 * are never updated — withdraw lifecycle (QUEUED → COMPLETED|REVERSED) is
 * three separate appended rows, not mutations of the original.
 *
 * <p>{@code amount} is always positive; direction is implicit in
 * {@link UserLedgerEntryType}. {@code balanceAfter} and {@code reservedAfter}
 * are snapshots written at insert time — they are the authoritative state
 * for the user's wallet at that point in the ledger.
 *
 * <p>{@code slTransactionId} (LSL {@code llGenerateKey()}) deduplicates
 * terminal-side retries on {@code /sl/wallet/deposit} and
 * {@code /sl/wallet/withdraw-request}. {@code idempotencyKey} (client-supplied)
 * deduplicates frontend retries on {@code /me/wallet/withdraw} and
 * {@code /me/wallet/pay-penalty}. Both have {@code UNIQUE WHERE NOT NULL}
 * indexes — duplicate inserts fail loudly with a constraint violation.
 *
 * <p>See spec docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.2.
 */
@Entity
@Table(name = "user_ledger", indexes = {
        @Index(name = "user_ledger_user_created_idx", columnList = "user_id, created_at DESC")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserLedgerEntry extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private UserLedgerEntryType entryType;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(name = "reserved_after", nullable = false)
    private Long reservedAfter;

    /**
     * Domain-entity discriminator: {@code "AUCTION"}, {@code "ESCROW"},
     * {@code "BID"}, {@code "TERMINAL_COMMAND"}, {@code "LISTING_FEE_REFUND"},
     * {@code "PENALTY"}, {@code "ADJUSTMENT"}, {@code "DORMANCY"}, etc.
     * No FK constraint — ledger rows don't cascade.
     */
    @Column(name = "ref_type", length = 32)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "sl_transaction_id", length = 36)
    private String slTransactionId;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(length = 500)
    private String description;

    @Column(name = "created_by_admin_id")
    private Long createdByAdminId;
}
