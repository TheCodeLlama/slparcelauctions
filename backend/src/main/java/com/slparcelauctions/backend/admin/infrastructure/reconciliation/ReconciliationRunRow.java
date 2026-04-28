package com.slparcelauctions.backend.admin.infrastructure.reconciliation;

import java.time.OffsetDateTime;

public record ReconciliationRunRow(
        Long id,
        OffsetDateTime ranAt,
        ReconciliationStatus status,
        Long expected,
        Long observed,
        Long drift,
        String errorMessage) {
}
