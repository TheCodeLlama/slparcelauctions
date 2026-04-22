package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
