package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BotTaskRepository extends JpaRepository<BotTask, Long> {

    List<BotTask> findByStatusOrderByCreatedAtAsc(BotTaskStatus status);

    /**
     * The single open (PENDING / IN_PROGRESS) recurring task of a given type
     * for an escrow. Used by the manual "Verify Sell To" expedite path to
     * bump {@code next_run_at} forward so the bot re-checks immediately. The
     * "one open task per escrow" invariant (enforced at funding in Phase 3)
     * means at most one row matches; the helper returns {@code Optional} and
     * the caller treats absence as a no-op (the 30-min auto-cadence still
     * applies once Phase 3 creates the task).
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
}
