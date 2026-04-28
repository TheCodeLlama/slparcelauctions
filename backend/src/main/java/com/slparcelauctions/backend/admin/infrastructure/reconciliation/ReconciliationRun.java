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
}
