package com.slparcelauctions.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class AuthFlowIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    protected final ObjectMapper objectMapper = new ObjectMapper();
    // Additional fields added in Task 32 for reuse cascade test

    // -------------------------------------------------------------------------
    // Task 30: register then use access token
    // -------------------------------------------------------------------------

    @Test
    void registerThenUseAccessToken() throws Exception {
        // Step 1: POST /api/auth/register
        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"reg@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Reg User\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.user.email").value("reg@example.com"))
            .andExpect(header().exists("Set-Cookie"))
            .andReturn();

        // Step 2: Extract access token from response body
        String body = registerResult.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asText();

        // Step 3: GET /api/users/me with Bearer token
        mockMvc.perform(get("/api/users/me")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("reg@example.com"));
    }

    // -------------------------------------------------------------------------
    // Task 31: login → refresh → use new token
    // -------------------------------------------------------------------------

    @Test
    void loginThenRefreshThenUseNewToken() throws Exception {
        // Setup: register a user
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"refresh@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Refresh User\"}"))
            .andExpect(status().isCreated());

        // Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"refresh@example.com\",\"password\":\"hunter22abc\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(header().exists("Set-Cookie"))
            .andReturn();

        // Extract refresh cookie A from login response
        String refreshCookieA = extractRefreshCookie(loginResult);
        assertThat(refreshCookieA).isNotBlank();

        // POST /refresh with cookie A
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookieA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(header().exists("Set-Cookie"))
            .andReturn();

        // Assert new refresh cookie B is different from A
        String refreshCookieB = extractRefreshCookie(refreshResult);
        assertThat(refreshCookieB).isNotBlank();
        assertThat(refreshCookieB).isNotEqualTo(refreshCookieA);

        // Extract new access token and use it on /me
        String newBody = refreshResult.getResponse().getContentAsString();
        String newAccessToken = objectMapper.readTree(newBody).get("accessToken").asText();

        mockMvc.perform(get("/api/users/me")
                .header("Authorization", "Bearer " + newAccessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("refresh@example.com"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the raw refresh token value from the {@code Set-Cookie} header.
     * Format: {@code refreshToken=<value>; HttpOnly; ...}
     */
    private String extractRefreshCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        if (setCookie == null) return null;
        // Cookie header format: refreshToken=<value>; HttpOnly; Secure; ...
        for (String part : setCookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("refreshToken=")) {
                return trimmed.substring("refreshToken=".length());
            }
        }
        return null;
    }
}
