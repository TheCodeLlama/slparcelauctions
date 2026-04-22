package com.slparcelauctions.backend.escrow.command;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link TerminalCommand}. Queries here keep the command
 * dispatcher path — which runs every 30 seconds by default — off the hot
 * path of general JPA finders: {@link #findDispatchable} only looks at
 * {@code QUEUED} + retry-ready {@code FAILED} rows and orders by
 * {@code nextAttemptAt}, and {@link #findStaleInFlight} only touches rows
 * whose {@code dispatchedAt} is older than the configured cutoff.
 */
public interface TerminalCommandRepository extends JpaRepository<TerminalCommand, Long> {

    /**
     * Ids of commands the dispatcher should attempt this sweep. Rows are
     * eligible when either {@code status = QUEUED}, or {@code status = FAILED}
     * with {@code requires_manual_review = false} (the retry-ready set). The
     * {@code nextAttemptAt &lt;= :now} gate enforces the backoff schedule.
     * Sorted ascending on {@code nextAttemptAt} so older deadlines run
     * first under a large backlog.
     */
    @Query("""
            SELECT c.id FROM TerminalCommand c
            WHERE (c.status = com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.QUEUED
                OR (c.status = com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.FAILED
                    AND c.requiresManualReview = false))
              AND c.nextAttemptAt <= :now
            ORDER BY c.nextAttemptAt ASC
            """)
    List<Long> findDispatchable(@Param("now") OffsetDateTime now);

    /**
     * Ids of commands still {@code IN_FLIGHT} whose {@code dispatchedAt} is
     * older than the supplied cutoff — i.e. the terminal never called back
     * within the configured {@code commandInFlightTimeout}. These get
     * requeued by the dispatcher's staleness sweep.
     */
    @Query("""
            SELECT c.id FROM TerminalCommand c
            WHERE c.status = com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.IN_FLIGHT
              AND c.dispatchedAt < :cutoff
            """)
    List<Long> findStaleInFlight(@Param("cutoff") OffsetDateTime cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM TerminalCommand c WHERE c.id = :id")
    Optional<TerminalCommand> findByIdForUpdate(@Param("id") Long id);

    /**
     * Looks up a command by its {@code idempotency_key}. Used by the callback
     * endpoint to resolve the row from the terminal-supplied key without
     * trusting the terminal to echo the synthetic DB id.
     */
    Optional<TerminalCommand> findByIdempotencyKey(String idempotencyKey);

    /**
     * Counts PAYOUT commands for the given escrow that are not yet complete.
     * Task 8 uses this to implement the payout-in-flight guard: the timeout
     * job must not expire an escrow with an active payout command.
     */
    @Query("""
            SELECT COUNT(c) FROM TerminalCommand c
            WHERE c.escrowId = :escrowId
              AND c.action = com.slparcelauctions.backend.escrow.command.TerminalCommandAction.PAYOUT
              AND c.status IN (com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.QUEUED,
                               com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.IN_FLIGHT,
                               com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.FAILED)
            """)
    long countActivePayoutCommands(@Param("escrowId") Long escrowId);
}
