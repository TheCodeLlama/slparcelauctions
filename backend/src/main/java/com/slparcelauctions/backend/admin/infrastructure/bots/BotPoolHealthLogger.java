package com.slparcelauctions.backend.admin.infrastructure.bots;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.bot-pool-health-log", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@Slf4j
public class BotPoolHealthLogger {

    private final AdminBotPoolService service;

    @Scheduled(fixedDelayString = "${slpa.bot-pool-health-log.delay-ms:300000}")
    public void log() {
        var rows = service.getHealth();
        long alive = rows.stream().filter(BotPoolHealthRow::isAlive).count();
        long total = rows.size();
        long dead = total - alive;
        log.info("Bot pool: {}/{} healthy, {} dead", alive, total, dead);
    }
}
