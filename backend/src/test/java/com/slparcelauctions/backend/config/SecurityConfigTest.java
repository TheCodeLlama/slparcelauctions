package com.slparcelauctions.backend.config;

import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration slice confirming the security filter chain routes requests correctly:
 * public endpoints are reachable without auth, protected endpoints return 401.
 *
 * <p>Uses {@code @SpringBootTest} (full context) so the real {@link JwtAuthenticationFilter}
 * and {@link com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint} beans participate
 * in the filter chain — not mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    // ── public endpoints ──────────────────────────────────────────────────────

    @Test
    void healthEndpoint_isPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }

    // ── protected endpoints ───────────────────────────────────────────────────

    @Test
    void meEndpoint_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1)
    void meEndpoint_notRejectedBySecurityWhenAuthenticated() throws Exception {
        // The security layer must pass the request through for an authenticated principal.
        // The controller delegates to the real service; the response may be 2xx or 4xx
        // depending on whether the user exists in the DB — that is fine. What matters is
        // that the filter chain does NOT return 401 or 403.
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError(
                                "Expected security to pass through for authenticated principal, " +
                                "but got HTTP " + status);
                    }
                });
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

    @Test
    void corsAllowsRequestsFromConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void corsRejectsRequestsFromUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://evil.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
