package com.slparcelauctions.backend.auction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CancellationLogRepository extends JpaRepository<CancellationLog, Long> {

    /**
     * Count of prior cancelled-with-bids log rows for a seller. Drives the
     * ladder index in {@code CancellationService.cancel} — the call MUST run
     * BEFORE the new log row is inserted (off-by-one trap). Result indices:
     * 0 → WARNING, 1 → PENALTY, 2 → PENALTY_AND_30D, 3+ → PERMANENT_BAN.
     * The seller-row pessimistic lock acquired earlier in the same
     * transaction prevents two concurrent cancellations from racing on this
     * count.
     */
    @Query("SELECT count(c) FROM CancellationLog c "
            + "WHERE c.seller.id = :sellerId AND c.hadBids = true")
    long countPriorOffensesWithBids(@Param("sellerId") Long sellerId);
}
