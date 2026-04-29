package com.slparcelauctions.backend.admin.infrastructure.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminReconciliationService {

    private final ReconciliationRunRepository repo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ReconciliationRunRow> recentRuns(int days) {
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(days);
        return repo.findByRanAtAfterOrderByRanAtDesc(since).stream()
                .map(r -> new ReconciliationRunRow(
                        r.getId(), r.getRanAt(), r.getStatus(),
                        r.getExpectedLockedSum(), r.getObservedBalance(),
                        r.getDrift(), r.getErrorMessage()))
                .toList();
    }
}
