package com.slparcelauctions.backend.auction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationLogRepository extends JpaRepository<CancellationLog, Long> {
}
