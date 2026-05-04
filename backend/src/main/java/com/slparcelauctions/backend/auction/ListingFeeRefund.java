package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "listing_fee_refunds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ListingFeeRefund extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "txn_ref", length = 255)
    private String txnRef;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    /**
     * FK to {@code terminal_commands.id} for the REFUND command dispatched for
     * this refund. Modelled as a plain {@code Long} column (not a JPA
     * relationship) because {@code TerminalCommand} lives in the escrow
     * package and referencing it here would create a circular dependency
     * across package boundaries. Nullable: stays null until
     * {@link com.slparcelauctions.backend.escrow.scheduler.ListingFeeRefundProcessorJob}
     * has queued the command. Presence is the idempotency guard — the
     * processor skips any refund whose {@code terminalCommandId} is already
     * set so a row that's been queued once never queues again.
     */
    @Column(name = "terminal_command_id")
    private Long terminalCommandId;

    /**
     * Timestamp of the last successful queue attempt. Stamped alongside
     * {@link #terminalCommandId} so operators can distinguish "refund
     * created just now, processor not run yet" from "refund queued at T
     * but command still awaiting dispatch." Purely observational — the
     * processor does not read this field, it reads
     * {@code terminalCommandId IS NULL}.
     */
    @Column(name = "last_queued_at")
    private OffsetDateTime lastQueuedAt;

}
