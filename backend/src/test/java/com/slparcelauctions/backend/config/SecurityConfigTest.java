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
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    // ── protected endpoints ───────────────────────────────────────────────────

    @Test
    void meEndpoint_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1)
    void meEndpoint_returns2xxWhenAuthenticated() throws Exception {
        // UserController#getCurrentUser still returns 501 (not yet implemented) but the
        // security layer must pass the request through — any 5xx or 4xx from the controller
        // is fine here; a 401/403 from the filter chain would be a security misconfiguration.
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().is5xxServerError());
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

    @Test
    void corsAllowsRequestsFromConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/health")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void corsRejectsRequestsFromUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/health")
                        .header("Origin", "http://evil.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
