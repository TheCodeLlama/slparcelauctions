package com.slparcelauctions.backend.bot;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code slpa.bot.*} and {@code slpa.bot-task.*} keys.
 * See {@code application.yml} for defaults. Owned by the bot package — both
 * {@link BotSharedSecretAuthorizer} and {@link BotStartupValidator} depend
 * on this record so the config is read once and shared.
 *
 * <p>Pared down to the two values still consumed in production after the
 * ownership-only verification refactor (spec 2026-05-16): the bot shared
 * secret for callback auth and the primary escrow UUID for the dev-placeholder
 * fail-fast check. The monitor / sentinel / timeout fields were dropped along
 * with the retired bot-driven verification + monitor task lifecycle.
 */
@ConfigurationProperties(prefix = "slpa")
public record BotTaskConfigProperties(
        Bot bot,
        BotTask botTask) {

    public record Bot(String sharedSecret) {}

    public record BotTask(UUID primaryEscrowUuid) {}
}
