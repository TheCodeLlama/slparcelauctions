package com.slparcelauctions.backend.verification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint;
import com.slparcelauctions.backend.auth.JwtAuthenticationFilter;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.bot.BotSharedSecretAuthorizer;
import com.slparcelauctions.backend.config.SecurityConfig;

/**
 * Slice tests for {@link VerificationController}. Exercises the security filter chain
 * (no {@code addFilters = false}) so unauthenticated requests are rejected with 401 by
 * {@link JwtAuthenticationEntryPoint}.
 *
 * <p><strong>Honesty note:</strong> the 200-path of these endpoints uses
 * {@code @AuthenticationPrincipal AuthPrincipal} which is awkward to inject at the slice
 * layer without spinning up the full JWT filter chain. Real 200-path coverage lives in
 * {@code VerificationFlowIntegrationTest}, which goes through the full filter chain end-to-end.
 * The slice test here just confirms 401 wiring.
 */
@WebMvcTest(VerificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
        "jwt.access-token-lifetime=PT15M",
        "jwt.refresh-token-lifetime=P7D"
})
class VerificationControllerSliceTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean VerificationCodeService service;
    @MockitoBean JwtService jwtService;
    @MockitoBean JwtConfig jwtConfig;
    // SecurityConfig depends on BotSharedSecretAuthorizer (Epic 06 Task 3).
    @MockitoBean BotSharedSecretAuthorizer botSharedSecretAuthorizer;

    @Test
    void getActive_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/verification/active"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void generate_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/verification/generate"))
                .andExpect(status().isUnauthorized());
    }
}
