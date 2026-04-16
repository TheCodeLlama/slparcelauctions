package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BotTaskRepository extends JpaRepository<BotTask, Long> {

    List<BotTask> findByStatusOrderByCreatedAtAsc(BotTaskStatus status);

    List<BotTask> findByStatusAndCreatedAtBefore(BotTaskStatus status, OffsetDateTime threshold);
}
