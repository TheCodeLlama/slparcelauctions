package com.slparcelauctions.backend.bot;

import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code slpa.bot.*} and {@code slpa.bot-task.*} keys.
 * See {@code application.yml} for defaults. Owned by the bot package — both
 * {@link BotSharedSecretAuthorizer} and {@link BotStartupValidator} depend
 * on this record so the config is read once and shared.
 *
 * <p>Existing {@code @Value} reads in other classes (e.g.
 * {@link BotTaskService}, {@link com.slparcelauctions.backend.auction.AuctionVerificationService},
 * {@link com.slparcelauctions.backend.auction.scheduled.BotTaskTimeoutJob})
 * continue to work against the same YAML keys; Task 12's doc sweep can
 * migrate them to this record but that's deliberately out of scope for
 * Task 3.
 */
@ConfigurationProperties(prefix = "slpa")
public record BotTaskConfigProperties(
        Bot bot,
        BotTask botTask) {

    public record Bot(
            String sharedSecret,
            Duration monitorAuctionInterval,
            Duration monitorEscrowInterval,
            int accessDeniedStreakThreshold,
            int teleportsPerMinute) {}

    public record BotTask(
            long sentinelPriceLindens,
            UUID primaryEscrowUuid,
            Duration timeout,
            Duration inProgressTimeout,
            Duration timeoutCheckInterval) {}
}
