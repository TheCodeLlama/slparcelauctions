package com.slparcelauctions.backend.bot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-stack auth test for the bot bearer token gate (Epic 06 Task 3).
 * Verifies the {@code /api/v1/bot/**} surface rejects missing / wrong
 * bearer tokens (401 via {@link com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint})
 * and admits the right one.
 *
 * <p>Uses the {@code dev} profile so Postgres + Redis connect against the
 * local dev containers — the project has a single non-default profile
 * ({@code dev}) that covers both local runs and {@code @SpringBootTest}s.
 * The test reads the dev {@code slpa.bot.shared-secret} from
 * {@code application-dev.yml} rather than overriding it via
 * {@code @TestPropertySource}. An override would force Spring to cache a
 * distinct context (and open a distinct Hikari pool) versus
 * {@link BotTaskControllerIntegrationTest}, which shares this property
 * shape — the dev Postgres container's {@code max_connections} is tight
 * enough that introducing another context variant exhausts it across the
 * full suite run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
class BotTaskControllerAuthIntegrationTest {

    /**
     * Matches {@code slpa.bot.shared-secret} in {@code application-dev.yml}.
     * Intentionally inlined as a string literal: the point of the test is
     * to prove the wiring actually reads this specific config value.
     */
    private static final String DEV_BOT_SECRET = "dev-bot-shared-secret";

    @Autowired
    private MockMvc mvc;

    @Test
    void claim_withoutBearer_returns401() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void claim_withWrongBearer_returns401() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .header("Authorization", "Bearer wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void claim_withCorrectBearer_returnsNoContent() throws Exception {
        // Queue is empty in this test → 204.
        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());
    }
}
