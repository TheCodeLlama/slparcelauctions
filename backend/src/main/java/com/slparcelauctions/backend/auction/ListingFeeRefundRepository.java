package com.slparcelauctions.backend.auction;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ListingFeeRefundRepository extends JpaRepository<ListingFeeRefund, Long> {

    /**
     * Finds all {@link ListingFeeRefund} rows that are awaiting dispatch:
     * {@code status=PENDING} and {@code terminalCommandId IS NULL}. Used by
     * {@link com.slparcelauctions.backend.escrow.scheduler.ListingFeeRefundProcessorJob}
     * to drain pending refunds. Ordered by creation time so the oldest
     * refunds get queued first — important for operator trust ("we process
     * refunds in order") and for minimising the time a seller waits for L$
     * back.
     *
     * <p>The {@code terminalCommandId IS NULL} predicate is the idempotency
     * guard. Once the processor has queued a command it stamps the id on
     * the refund row; subsequent sweeps skip it. The processor does NOT
     * re-examine the command's status — if the command failed at the
     * terminal, the refund stays PENDING on the refund table but the
     * Task 7 callback path will eventually flip it to PROCESSED when the
     * retry succeeds, or the admin queue picks it up when the retries
     * exhaust.
     */
    @Query("""
            SELECT r FROM ListingFeeRefund r
            WHERE r.status = com.slparcelauctions.backend.auction.RefundStatus.PENDING
              AND r.terminalCommandId IS NULL
            ORDER BY r.createdAt ASC
            """)
    List<ListingFeeRefund> findPendingAwaitingDispatch();

    /**
     * Pessimistic-lock variant for the per-refund task path. Serialises
     * the queue-command step against any concurrent admin flow that might
     * touch the refund row (e.g. a future "cancel this refund" endpoint)
     * and against the Task 7 callback that flips {@code status=PROCESSED}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ListingFeeRefund r WHERE r.id = :id")
    Optional<ListingFeeRefund> findByIdForUpdate(@Param("id") Long id);
}
