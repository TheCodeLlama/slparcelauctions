package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BotTaskRepository extends JpaRepository<BotTask, Long> {

    List<BotTask> findByStatusOrderByCreatedAtAsc(BotTaskStatus status);

    /** All bot tasks ever created for an escrow (any status). */
    List<BotTask> findByEscrowId(Long escrowId);

    /**
     * The single open (PENDING / IN_PROGRESS) recurring task of a given type
     * for an escrow. Used by the manual "Verify Sell To" expedite path to
     * bump {@code next_run_at} forward so the bot re-checks immediately. The
     * "one open task per escrow" invariant (enforced at funding in Phase 3)
     * means at most one row matches, so the returned {@link List} holds at
     * most one element. {@code List} (not {@code Optional}) is the safer
     * return type — if the invariant is ever violated this still binds
     * cleanly instead of throwing {@code IncorrectResultSizeDataAccessException}.
     * The caller treats an empty list as a no-op (the 30-min auto-cadence
     * still applies once Phase 3 creates the task).
     */
    @Query("""
            SELECT t FROM BotTask t
             WHERE t.escrow.id = :escrowId
               AND t.taskType = :type
               AND t.status IN (com.slparcelauctions.backend.bot.BotTaskStatus.PENDING,
                                com.slparcelauctions.backend.bot.BotTaskStatus.IN_PROGRESS)
             ORDER BY t.createdAt ASC
            """)
    List<BotTask> findOpenByEscrowAndType(
            @Param("escrowId") Long escrowId, @Param("type") BotTaskType type);

    /**
     * Atomically claim the next due task. PENDING rows with
     * {@code next_run_at} in the future are skipped. {@code FOR UPDATE
     * SKIP LOCKED} means concurrent claims from other workers never
     * block; each sees the next unlocked row.
     *
     * <p>Native query because Spring Data JPQL does not have a portable
     * {@code SKIP LOCKED} clause; it is Postgres-specific syntax (and
     * since this codebase runs exclusively on Postgres that is fine).
     * See FOOTGUNS section F.86.
     */
    @Query(value = """
            SELECT * FROM bot_tasks
             WHERE status = 'PENDING'
               AND (next_run_at IS NULL OR next_run_at <= :now)
             ORDER BY created_at ASC
             FOR UPDATE SKIP LOCKED
             LIMIT 1
            """, nativeQuery = true)
    Optional<BotTask> claimNext(@Param("now") OffsetDateTime now);

    /**
     * Returns {@code true} when an open (not COMPLETED or FAILED) task of the
     * given type already exists for this auction. Used by
     * {@link com.slparcelauctions.backend.auction.parcelscan.ParcelScanService}
     * to prevent duplicate SCAN_PARCEL enqueues. A CANCELLED task does not block
     * re-enqueue; PENDING and IN_PROGRESS do.
     */
    @Query("SELECT COUNT(t) > 0 FROM BotTask t " +
           "WHERE t.auction.id = :auctionId " +
           "AND t.taskType = :type " +
           "AND t.status NOT IN (com.slparcelauctions.backend.bot.BotTaskStatus.COMPLETED, " +
           "com.slparcelauctions.backend.bot.BotTaskStatus.FAILED, " +
           "com.slparcelauctions.backend.bot.BotTaskStatus.CANCELLED)")
    boolean existsPendingByAuctionIdAndType(@Param("auctionId") Long auctionId,
                                            @Param("type") BotTaskType type);

    /**
     * Deletes all non-terminal (PENDING or IN_PROGRESS) tasks of the given type
     * for the given auction. Used by the admin re-enqueue path to clear any
     * orphaned or in-flight task before calling
     * {@link com.slparcelauctions.backend.auction.parcelscan.ParcelScanService#enqueueIfEligible}
     * so the eligibility check in rule 3 does not block re-enqueue.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM BotTask t " +
           "WHERE t.auction.id = :auctionId " +
           "AND t.taskType = :type " +
           "AND t.status NOT IN (com.slparcelauctions.backend.bot.BotTaskStatus.COMPLETED, " +
           "com.slparcelauctions.backend.bot.BotTaskStatus.FAILED, " +
           "com.slparcelauctions.backend.bot.BotTaskStatus.CANCELLED)")
    void deletePendingByAuctionIdAndType(@Param("auctionId") Long auctionId,
                                         @Param("type") BotTaskType type);
}
