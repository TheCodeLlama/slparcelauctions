package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BotTaskRepository extends JpaRepository<BotTask, Long> {

    List<BotTask> findByStatusOrderByCreatedAtAsc(BotTaskStatus status);

    List<BotTask> findByStatusAndCreatedAtBefore(BotTaskStatus status, OffsetDateTime threshold);

    /**
     * Atomically claim the next due task. PENDING rows with {@code next_run_at}
     * in the future (future monitor cycles) are skipped. {@code FOR UPDATE
     * SKIP LOCKED} means concurrent claims from other workers never block;
     * each sees the next unlocked row.
     *
     * <p>Native query because Spring Data JPQL does not have a portable
     * {@code SKIP LOCKED} clause; it is Postgres-specific syntax (and since
     * this codebase runs exclusively on Postgres that is fine). See FOOTGUNS
     * §F.86.
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
     * IN_PROGRESS rows older than {@code threshold}. Used by the bot-task
     * timeout sweep to detect workers that crashed mid-task.
     */
    List<BotTask> findByStatusAndLastUpdatedAtBefore(
            BotTaskStatus status, OffsetDateTime threshold);

    /**
     * Bulk-cancels live (PENDING or IN_PROGRESS) monitor rows for an auction
     * on close / suspend / cancel. {@code lastUpdatedAt = :now} is explicit in
     * the SET clause because {@link org.springframework.data.jpa.repository.Modifying}
     * bypasses Hibernate's {@link org.hibernate.annotations.UpdateTimestamp}
     * handler (FOOTGUNS §F.87). Idempotent: returns 0 when no rows match.
     */
    @Modifying
    @Query("""
            UPDATE BotTask t
               SET t.status = com.slparcelauctions.backend.bot.BotTaskStatus.CANCELLED,
                   t.completedAt = :now,
                   t.lastUpdatedAt = :now
             WHERE t.auction.id = :auctionId
               AND t.taskType IN :types
               AND t.status IN (com.slparcelauctions.backend.bot.BotTaskStatus.PENDING,
                                com.slparcelauctions.backend.bot.BotTaskStatus.IN_PROGRESS)
            """)
    int cancelLiveByAuctionIdAndTypes(
            @Param("auctionId") Long auctionId,
            @Param("types") Collection<BotTaskType> types,
            @Param("now") OffsetDateTime now);

    /**
     * Bulk-cancels live MONITOR_ESCROW rows for an escrow on its terminal
     * transition. Same {@code lastUpdatedAt} caveat as
     * {@link #cancelLiveByAuctionIdAndTypes}. Idempotent.
     */
    @Modifying
    @Query("""
            UPDATE BotTask t
               SET t.status = com.slparcelauctions.backend.bot.BotTaskStatus.CANCELLED,
                   t.completedAt = :now,
                   t.lastUpdatedAt = :now
             WHERE t.escrow.id = :escrowId
               AND t.taskType = com.slparcelauctions.backend.bot.BotTaskType.MONITOR_ESCROW
               AND t.status IN (com.slparcelauctions.backend.bot.BotTaskStatus.PENDING,
                                com.slparcelauctions.backend.bot.BotTaskStatus.IN_PROGRESS)
            """)
    int cancelLiveByEscrowId(
            @Param("escrowId") Long escrowId,
            @Param("now") OffsetDateTime now);
}
