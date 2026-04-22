package com.slparcelauctions.backend.escrow;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EscrowRepository extends JpaRepository<Escrow, Long> {

    Optional<Escrow> findByAuctionId(Long auctionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Escrow e WHERE e.id = :id")
    Optional<Escrow> findByIdForUpdate(@Param("id") Long id);

    /**
     * Returns every escrow id currently in {@link EscrowState#TRANSFER_PENDING},
     * ordered by {@code lastCheckedAt} ascending so escrows that have never
     * been checked (or whose last check is oldest) are swept first. Used by
     * the ownership-monitor scheduler (spec §4.5) — the per-id
     * {@code findByIdForUpdate} lock acquisition happens inside the
     * per-escrow worker transaction, not here.
     */
    @Query("""
            SELECT e.id FROM Escrow e
            WHERE e.state = com.slparcelauctions.backend.escrow.EscrowState.TRANSFER_PENDING
            ORDER BY e.lastCheckedAt ASC NULLS FIRST
            """)
    List<Long> findTransferPendingIds();
}
