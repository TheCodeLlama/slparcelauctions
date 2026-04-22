package com.slparcelauctions.backend.escrow;

import java.time.OffsetDateTime;
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

    /**
     * Returns every escrow id whose 48h {@code paymentDeadline} has already
     * passed while the row is still {@link EscrowState#ESCROW_PENDING} — the
     * winner never paid. Used by the timeout sweeper (spec §4.6) to flip
     * these rows to {@link EscrowState#EXPIRED}; no L$ is held so no refund
     * is queued.
     */
    @Query("""
            SELECT e.id FROM Escrow e
            WHERE e.state = com.slparcelauctions.backend.escrow.EscrowState.ESCROW_PENDING
              AND e.paymentDeadline < :now
            """)
    List<Long> findExpiredPendingIds(@Param("now") OffsetDateTime now);

    /**
     * Returns every escrow id whose 72h {@code transferDeadline} has already
     * passed while the row is still {@link EscrowState#TRANSFER_PENDING} —
     * the seller never transferred the parcel — AND there is no active
     * PAYOUT {@link com.slparcelauctions.backend.escrow.command.TerminalCommand}
     * in flight. The {@code NOT EXISTS} subquery is the payout-in-flight
     * guard: once ownership is confirmed and a payout command is queued
     * ({@code QUEUED} / {@code IN_FLIGHT} / {@code FAILED} pending retry),
     * the 72h deadline has been satisfied from the seller's side and the
     * backend is just finishing up — a false timeout here would double-spend
     * by refunding the winner while the payout is still attempting to
     * deliver to the seller. Used by the timeout sweeper (spec §4.6) to
     * flip these rows to {@link EscrowState#EXPIRED} and queue the winner's
     * refund.
     */
    @Query("""
            SELECT e.id FROM Escrow e
            WHERE e.state = com.slparcelauctions.backend.escrow.EscrowState.TRANSFER_PENDING
              AND e.transferDeadline < :now
              AND NOT EXISTS (
                  SELECT 1 FROM TerminalCommand c
                  WHERE c.escrowId = e.id
                    AND c.action = com.slparcelauctions.backend.escrow.command.TerminalCommandAction.PAYOUT
                    AND c.status IN (
                        com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.QUEUED,
                        com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.IN_FLIGHT,
                        com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.FAILED))
            """)
    List<Long> findExpiredTransferPendingIds(@Param("now") OffsetDateTime now);
}
