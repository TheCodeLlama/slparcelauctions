package com.slparcelauctions.backend.escrow.command;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Outbound command row for the terminal dispatcher (spec §7.2, §7.3). Each
 * row represents exactly one PAYOUT or REFUND side effect that the escrow
 * state machine has committed to, queued for an in-world terminal to
 * execute. The dispatcher flips to {@code IN_FLIGHT} on successful POST, the
 * terminal's callback flips to {@code COMPLETED} or {@code FAILED}, and the
 * dispatcher's IN_FLIGHT staleness sweep requeues callback-less rows so the
 * retry state machine makes forward progress even when a terminal
 * disappears.
 *
 * <p>{@code idempotency_key} is a globally unique string computed from
 * {@code prefix-sourceId-action-seq} (e.g. {@code ESC-42-PAYOUT-1}) and is
 * the only identity the callback uses to find the row — the terminal is
 * not trusted to echo back our synthetic {@code id}. A unique DB constraint
 * prevents double-insert on a retry racing against the initial create.
 *
 * <p>{@code requires_manual_review} flips {@code true} once the retry budget
 * is exhausted (4 attempts with 1m/5m/15m backoff per spec §7.4); the
 * dispatcher skips these rows and the admin queue owns re-enabling them. The
 * {@code ESCROW_PAYOUT_STALLED} envelope is broadcast on the transition so
 * the UI can surface the stall without polling the DB.
 */
@Entity
@Table(name = "terminal_commands",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tcmd_idempotency_key",
                        columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "ix_tcmd_status_next_attempt",
                        columnList = "status, next_attempt_at"),
                @Index(name = "ix_tcmd_escrow", columnList = "escrow_id"),
                @Index(name = "ix_tcmd_listing_fee_refund",
                        columnList = "listing_fee_refund_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TerminalCommand extends BaseMutableEntity {

    /** Non-null for {@code AUCTION_ESCROW} rows; null for listing-fee refunds. */
    @Column(name = "escrow_id")
    private Long escrowId;

    /** Non-null for {@code LISTING_FEE_REFUND} rows; null otherwise. */
    @Column(name = "listing_fee_refund_id")
    private Long listingFeeRefundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TerminalCommandAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TerminalCommandPurpose purpose;

    @Column(name = "recipient_uuid", nullable = false, length = 36)
    private String recipientUuid;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TerminalCommandStatus status;

    @Column(name = "terminal_id", length = 100)
    private String terminalId;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "shared_secret_version", length = 20)
    private String sharedSecretVersion;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Builder.Default
    @Column(name = "requires_manual_review", nullable = false,
            columnDefinition = "boolean not null default false")
    private Boolean requiresManualReview = false;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
