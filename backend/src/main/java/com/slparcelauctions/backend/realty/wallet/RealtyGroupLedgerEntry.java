package com.slparcelauctions.backend.realty.wallet;

import com.slparcelauctions.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Append-only ledger row for a realty group's wallet. See spec §3.2.
 * Never mutate an existing row; resolve by appending a new row.
 */
@Entity
@Table(name = "realty_group_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroupLedgerEntry extends BaseEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private RealtyGroupLedgerEntryType entryType;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "reserved_after", nullable = false)
    private long reservedAfter;

    @Column(name = "ref_type", length = 32)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "sl_transaction_id", length = 36)
    private String slTransactionId;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(length = 500)
    private String description;

    @Column(name = "created_by_admin_id")
    private Long createdByAdminId;
}
