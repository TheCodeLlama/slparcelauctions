package com.slparcelauctions.backend.escrow;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EscrowRepository extends JpaRepository<Escrow, Long> {

    Optional<Escrow> findByAuctionId(Long auctionId);

    /**
     * Batch lookup used by list-paging consumers (My Bids, listings) that need
     * to enrich multiple auction projections with their escrow state in one
     * round-trip instead of N {@link #findByAuctionId} calls. Returns zero or
     * one {@link Escrow} per auction id (escrow rows have a unique
     * {@code auction_id} FK).
     */
    List<Escrow> findByAuctionIdIn(Collection<Long> auctionIds);

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
     * Step-3 (Buy Parcel) sweep finder (spec §6). Returns escrow ids in the
     * Buy-Parcel sub-phase that are due for a World-API owner poll:
     * {@code TRANSFER_PENDING} with {@code sellToConfirmedAt} set (the bot
     * hard gate has cleared), {@code transferConfirmedAt} still null (transfer
     * not yet observed), and the per-escrow pacing cursor
     * {@code nextOwnerCheckAt} either unset or already due.
     *
     * <p><b>Hard gate:</b> escrows whose {@code sellToConfirmedAt} is null are
     * never returned — before Set-Sell-To is bot-confirmed, step-3 owner
     * polling is entirely inert (spec decision §2.1, §6). Ordered by
     * {@code lastCheckedAt ASC NULLS FIRST} so never-checked / oldest-checked
     * escrows are swept first, mirroring {@link #findTransferPendingIds}.
     */
    @Query("SELECT e.id FROM Escrow e WHERE e.state = 'TRANSFER_PENDING' "
            + "AND e.sellToConfirmedAt IS NOT NULL AND e.transferConfirmedAt IS NULL "
            + "AND (e.nextOwnerCheckAt IS NULL OR e.nextOwnerCheckAt <= :now) "
            + "ORDER BY e.lastCheckedAt ASC NULLS FIRST")
    List<Long> findBuyPhaseEscrowIdsDue(@Param("now") OffsetDateTime now);

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

    /**
     * Every COMPLETED escrow where {@code userId} is either seller or
     * winner AND the escrow completed strictly after the supplied
     * threshold — i.e., the 14-day review window is still open. Backs
     * the pending-reviews dashboard endpoint (Epic 08 sub-spec 1 §4.2).
     * The caller filters out escrows the user already reviewed inside
     * the service so an already-reviewed escrow does not round-trip
     * through this method once the reviewer submits.
     *
     * <p>Ordered by {@code completedAt} ascending so the most-urgent
     * rows (oldest {@code completedAt} = soonest {@code windowClosesAt}
     * since the 14-day offset is constant) surface first in the
     * dashboard list.
     */
    @Query("""
            SELECT e FROM Escrow e
            WHERE e.state = com.slparcelauctions.backend.escrow.EscrowState.COMPLETED
              AND e.completedAt > :threshold
              AND (e.auction.seller.id = :userId OR e.auction.winnerUserId = :userId)
            ORDER BY e.completedAt ASC
            """)
    List<Escrow> findCompletedEscrowsForUser(
            @Param("userId") Long userId,
            @Param("threshold") OffsetDateTime threshold);

    /**
     * Admin dispute queue: all escrows in the given state ordered by disputedAt ascending
     * (oldest open dispute first). Used by {@code AdminDisputeQueryService} when a
     * specific status filter is supplied.
     */
    Page<Escrow> findByStateOrderByDisputedAtAsc(EscrowState state, Pageable pageable);

    /**
     * Admin dispute queue: all escrows in any of the given states ordered by disputedAt
     * ascending. Used by {@code AdminDisputeQueryService} when no status filter is
     * applied (defaults to DISPUTED + FROZEN).
     */
    Page<Escrow> findByStateInOrderByDisputedAtAsc(Collection<EscrowState> states, Pageable pageable);

    long countByState(EscrowState state);

    long countByStateNotIn(Collection<EscrowState> states);

    /**
     * Returns FUNDED escrows whose {@code transferDeadline} falls in the
     * supplied window and whose {@code reminderSentAt} is null — i.e., the
     * reminder has not yet been sent. Used by
     * {@link com.slparcelauctions.backend.admin.infrastructure.reminders.EscrowTransferReminderScheduler}
     * to fire a once-per-escrow transfer reminder.
     */
    @Query("""
            SELECT e FROM Escrow e
            WHERE e.state = com.slparcelauctions.backend.escrow.EscrowState.FUNDED
              AND e.transferDeadline BETWEEN :rangeStart AND :rangeEnd
              AND e.reminderSentAt IS NULL
            """)
    List<Escrow> findEscrowsApproachingTransferDeadline(
            @Param("rangeStart") OffsetDateTime rangeStart,
            @Param("rangeEnd") OffsetDateTime rangeEnd);

    @Query("SELECT COALESCE(SUM(e.finalBidAmount), 0) FROM Escrow e WHERE e.state = :state")
    long sumFinalBidAmountByState(@Param("state") EscrowState state);

    @Query("SELECT COALESCE(SUM(e.commissionAmt), 0) FROM Escrow e WHERE e.state = :state")
    long sumCommissionAmtByState(@Param("state") EscrowState state);

    /**
     * Sums the {@code amount} column across all escrows whose state is in the
     * given collection. Used by {@code ReconciliationService} to compute the
     * total locked L$ (FUNDED + TRANSFER_PENDING + DISPUTED + FROZEN). Returns
     * 0 when no rows match.
     */
    @Query("SELECT COALESCE(SUM(e.finalBidAmount), 0) FROM Escrow e WHERE e.state IN :states")
    long sumAmountByStateIn(@Param("states") java.util.Collection<EscrowState> states);

    /**
     * Returns {@code true} when the group has at least one escrow still in a state where
     * L$ is present and movement is ahead: {@link EscrowState#FUNDED},
     * {@link EscrowState#TRANSFER_PENDING}, {@link EscrowState#DISPUTED}, or
     * {@link EscrowState#FROZEN}. Used by the dissolution gate to block dissolve until
     * all group-listed auctions' escrows have settled.
     *
     * <p>{@link EscrowState#ESCROW_PENDING} is intentionally excluded — that state means
     * the winner has not yet paid, so no L$ is held. {@link EscrowState#COMPLETED} and
     * {@link EscrowState#EXPIRED} are terminal/settled and also excluded.
     */
    @Query("""
            SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END
            FROM Escrow e
            WHERE e.auction.realtyGroupId = :groupId
              AND e.state IN (
                  com.slparcelauctions.backend.escrow.EscrowState.FUNDED,
                  com.slparcelauctions.backend.escrow.EscrowState.TRANSFER_PENDING,
                  com.slparcelauctions.backend.escrow.EscrowState.DISPUTED,
                  com.slparcelauctions.backend.escrow.EscrowState.FROZEN)
            """)
    boolean existsInFlightForGroup(@Param("groupId") Long groupId);

    /**
     * Returns the IDs of escrows where the given user is involved (as either
     * seller or winner) and the escrow state is one of the supplied open
     * states. Used by
     * {@link com.slparcelauctions.backend.user.deletion.UserDeletionService}
     * to enforce the OPEN_ESCROWS precondition before account deletion.
     *
     * <p>Winner is identified via {@code auction.winnerUserId} (raw Long on
     * {@link com.slparcelauctions.backend.auction.Auction}); seller is
     * identified via {@code auction.seller.id}.
     */
    @Query("""
            SELECT e.id FROM Escrow e
            WHERE e.state IN :states
              AND (e.auction.seller.id = :userId OR e.auction.winnerUserId = :userId)
            """)
    List<Long> findIdsByUserInvolvedAndStateIn(
            @Param("userId") Long userId,
            @Param("states") Collection<EscrowState> states);
}
