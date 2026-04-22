package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.bot.BotTaskConfigProperties.Bot;
import com.slparcelauctions.backend.bot.BotTaskConfigProperties.BotTask;

class BotStartupValidatorTest {

    private static final UUID PLACEHOLDER =
            UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID REAL =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void blankSharedSecret_throws() {
        BotTaskConfigProperties props = props("", REAL);
        assertThatThrownBy(() -> new BotStartupValidator(props).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slpa.bot.shared-secret");
    }

    @Test
    void devPlaceholderSharedSecret_throws() {
        BotTaskConfigProperties props = props("dev-bot-shared-secret", REAL);
        assertThatThrownBy(() -> new BotStartupValidator(props).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev placeholder");
    }

    @Test
    void placeholderPrimaryEscrowUuid_throws() {
        BotTaskConfigProperties props = props("real-secret-12345678", PLACEHOLDER);
        assertThatThrownBy(() -> new BotStartupValidator(props).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary-escrow-uuid");
    }

    @Test
    void validConfig_passes() {
        BotTaskConfigProperties props = props("real-secret-12345678", REAL);
        assertThatCode(() -> new BotStartupValidator(props).validate())
                .doesNotThrowAnyException();
    }

    private static BotTaskConfigProperties props(String secret, UUID primaryEscrow) {
        return new BotTaskConfigProperties(
                new Bot(secret, null, null, 3, 6),
                new BotTask(999_999_999L, primaryEscrow, null, null, null));
    }
}
