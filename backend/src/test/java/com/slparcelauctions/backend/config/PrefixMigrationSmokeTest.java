package com.slparcelauctions.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrative smoke test that pins the {@code /api} → {@code /api/v1} prefix
 * migration. Four routes across the renamed surface: two positive paths that
 * must resolve under the new prefix and two negative paths that must NOT
 * resolve under the old prefix. If any future refactor accidentally restores
 * the old prefix or drops the new one, this test fails loudly and CI blocks
 * the regression.
 *
 * <p>Old-path assertion uses {@code is4xxClientError()} rather than a strict
 * {@code 404} because Spring Security's filter chain runs before the MVC
 * dispatcher: requests to {@code /api/health} (no longer matched by any
 * permit rule and not matched by the {@code /api/v1/**} catch-all) fall
 * through to {@code .anyRequest().denyAll()}, which the JWT entry point
 * reports as {@code 401}, not {@code 404}. The point of the assertion is
 * "old paths are dead," not "old paths return exactly 404," so any 4xx is
 * the honest pass condition.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class PrefixMigrationSmokeTest {

    @Autowired MockMvc mockMvc;

    @Test
    void renamedRoutesResolve_andOldRoutesAreDead() throws Exception {
        // Positive proof — register on the new path returns 201.
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"prefix-smoke@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Smoke\"}"))
                .andExpect(status().isCreated());

        // Positive proof — health on the new path returns 200.
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());

        // Negative proof — old health path is dead (4xx, in practice 401 from
        // the JWT entry point because denyAll() fires before MVC's 404 handler).
        mockMvc.perform(get("/api/health"))
                .andExpect(status().is4xxClientError());

        // Negative proof — old register path is dead. Same 4xx rationale.
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@y.z\",\"password\":\"hunter22abc\",\"displayName\":\"x\"}"))
                .andExpect(status().is4xxClientError());
    }
}
