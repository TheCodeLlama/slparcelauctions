package com.slparcelauctions.backend.bot;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fails fast on non-dev profiles when {@code slpa.bot.shared-secret} is
 * unset / blank / equal to the dev placeholder / too short, or when
 * {@code slpa.bot-task.primary-escrow-uuid} is still the dev placeholder.
 * Mirrors {@link com.slparcelauctions.backend.escrow.config.EscrowStartupValidator}
 * in intent. The dev profile skips this validator entirely so local
 * iteration can boot against the hard-coded placeholders.
 *
 * <p>Closes two DEFERRED_WORK items:
 * <ul>
 *   <li>"Bot service authentication" (Epic 03 sub-spec 1, Task 8).</li>
 *   <li>Bot half of "Primary escrow UUID + SLPA trusted-owner-keys
 *       production config".</li>
 * </ul>
 *
 * <p>The project convention is a single {@code "dev"} profile for both
 * local runs and {@code @SpringBootTest}s. Tests that need the validator to
 * run exercise it directly (unit-style) rather than booting a non-dev
 * context.
 */
@Component
@Profile("!dev")
@RequiredArgsConstructor
@Slf4j
public class BotStartupValidator {

    static final String DEV_PLACEHOLDER_SECRET = "dev-bot-shared-secret";
    static final UUID DEV_PLACEHOLDER_ESCROW_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final int MIN_SECRET_LENGTH = 16;

    private final BotTaskConfigProperties props;

    @PostConstruct
    public void validate() {
        String secret = props.bot() == null ? null : props.bot().sharedSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "slpa.bot.shared-secret must be set in non-dev profiles. "
                            + "Configure via environment variable SLPA_BOT_SHARED_SECRET "
                            + "or a secrets-manager override.");
        }
        if (DEV_PLACEHOLDER_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "slpa.bot.shared-secret is still the dev placeholder "
                            + "\"" + DEV_PLACEHOLDER_SECRET + "\". "
                            + "Rotate via SLPA_BOT_SHARED_SECRET before deploying.");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "slpa.bot.shared-secret must be at least "
                            + MIN_SECRET_LENGTH + " characters; got "
                            + secret.length() + ".");
        }

        UUID primaryEscrow = props.botTask() == null
                ? null : props.botTask().primaryEscrowUuid();
        if (DEV_PLACEHOLDER_ESCROW_UUID.equals(primaryEscrow)) {
            throw new IllegalStateException(
                    "slpa.bot-task.primary-escrow-uuid is still the dev "
                            + "placeholder " + DEV_PLACEHOLDER_ESCROW_UUID
                            + ". Configure via SLPA_PRIMARY_ESCROW_UUID.");
        }

        log.info("Bot startup validation OK: secretLen={}, primaryEscrowUuid={}",
                secret.length(), primaryEscrow);
    }
}
