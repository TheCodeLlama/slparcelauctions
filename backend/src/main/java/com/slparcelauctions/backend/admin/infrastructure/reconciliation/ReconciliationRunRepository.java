package com.slparcelauctions.backend.admin.infrastructure.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    List<ReconciliationRun> findByRanAtAfterOrderByRanAtDesc(OffsetDateTime since);
}
