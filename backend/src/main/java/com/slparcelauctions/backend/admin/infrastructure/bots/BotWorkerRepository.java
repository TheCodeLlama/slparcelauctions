package com.slparcelauctions.backend.admin.infrastructure.bots;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BotWorkerRepository extends JpaRepository<BotWorker, Long> {
    Optional<BotWorker> findBySlUuid(String slUuid);
}
