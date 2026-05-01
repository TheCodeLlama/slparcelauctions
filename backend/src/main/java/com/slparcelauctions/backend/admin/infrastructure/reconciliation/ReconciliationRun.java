package com.slparcelauctions.backend.admin.infrastructure.reconciliation;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "reconciliation_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ran_at", nullable = false)
    private OffsetDateTime ranAt;

    @Column(name = "expected_locked_sum", nullable = false)
    private Long expectedLockedSum;

    @Column(name = "observed_balance")
    private Long observedBalance;

    @Column(name = "drift")
    private Long drift;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReconciliationStatus status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * Sum of {@code users.balance_lindens} at run time. Added by the wallet
     * model. Pre-wallet runs are NULL.
     */
    @Column(name = "wallet_balance_total")
    private Long walletBalanceTotal;

    /**
     * Sum of active {@code bid_reservations.amount} at run time.
     * Sub-breakdown of {@code wallet_balance_total} (reservations are
     * partitions of balance, not separate L$); recorded for forensics
     * but NOT added to expected.
     */
    @Column(name = "wallet_reserved_total")
    private Long walletReservedTotal;

    /**
     * Sum of escrows in locked states (FUNDED/TRANSFER_PENDING/DISPUTED/
     * FROZEN). Duplicate of {@code expectedLockedSum} for clarity in the
     * wallet-era schema; pre-wallet runs are NULL.
     */
    @Column(name = "escrow_locked_total")
    private Long escrowLockedTotal;
}
