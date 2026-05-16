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
 * <p>Most of the {@link Bot} and {@link BotTask} fields were originally
 * declared to drive the VERIFY / MONITOR_AUCTION / MONITOR_ESCROW task types
 * that were retired by the ownership-only verification refactor (spec
 * 2026-05-16). They remain on the record as future-extension scaffolding —
 * the underlying YAML keys are no longer in {@code application.yml}, so
 * Spring binds them with null / zero defaults, which is fine because no
 * production caller currently reads them.
 *
 * <p>Existing {@code @Value} reads in other classes (e.g.
 * {@link com.slparcelauctions.backend.auction.scheduled.BotTaskTimeoutJob})
 * continue to work against the same YAML keys.
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
