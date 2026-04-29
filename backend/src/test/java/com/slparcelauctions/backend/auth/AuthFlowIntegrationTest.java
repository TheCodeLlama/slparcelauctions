package com.slparcelauctions.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class AuthFlowIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    protected final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired private com.slparcelauctions.backend.auth.RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private com.slparcelauctions.backend.auth.test.RefreshTokenTestFixture refreshTokenTestFixture;
    @PersistenceContext private EntityManager entityManager;

    // -------------------------------------------------------------------------
    // Task 30: register then use access token
    // -------------------------------------------------------------------------

    @Test
    void registerThenUseAccessToken() throws Exception {
        // Step 1: POST /api/v1/auth/register
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
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

        // Step 3: GET /api/v1/users/me with Bearer token
        mockMvc.perform(get("/api/v1/users/me")
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
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"refresh@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Refresh User\"}"))
            .andExpect(status().isCreated());

        // Login
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
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
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
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

        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + newAccessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("refresh@example.com"));
    }

    // -------------------------------------------------------------------------
    // Task 32: refresh token reuse cascade — THE SECURITY CANARY
    // -------------------------------------------------------------------------

    @Test
    void refreshTokenReuseCascade_revokesAllSessionsAndBumpsTokenVersion() throws Exception {
        // Step 1: Register, capture cookie A + access token A
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"cascade@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Cascade User\"}"))
            .andExpect(status().isCreated())
            .andReturn();

        String registerBody = registerResult.getResponse().getContentAsString();
        String accessTokenA = objectMapper.readTree(registerBody).get("accessToken").asText();
        String cookieA = extractRefreshCookie(registerResult);
        Long userId = objectMapper.readTree(registerBody).get("user").get("id").asLong();
        assertThat(cookieA).isNotBlank();

        // Step 2: Refresh with cookie A → get cookie B
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookieA)))
            .andExpect(status().isOk())
            .andReturn();

        String cookieB = extractRefreshCookie(refreshResult);
        assertThat(cookieB).isNotBlank();
        assertThat(cookieB).isNotEqualTo(cookieA);

        // Step 3: Refresh AGAIN with cookie A (now revoked) → 401 + $.code = AUTH_REFRESH_TOKEN_REUSED
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookieA)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));

        // Flush then clear L1 cache: revokeAllByUserId bulk-updates bypass Hibernate's first-level
        // cache; bumpTokenVersion uses dirty checking and is still pending. Flush ensures the
        // dirty tokenVersion increment is written to the DB before we evict cached entities.
        entityManager.flush();
        entityManager.clear();

        // Step 4: Query DB — all tokens for this user must be revoked
        List<RefreshToken> allTokens = refreshTokenRepository.findAllByUserId(userId);
        assertThat(allTokens).isNotEmpty();
        assertThat(allTokens).allMatch(t -> t.getRevokedAt() != null,
            "all refresh tokens should be revoked after reuse detection");

        // Step 6 (spec ordering): tokenVersion bumped from 0 to 1 — checked here before step 5
        // because presenting the revoked cookieB also triggers a cascade, which bumps tokenVersion
        // again. The spec asserts exactly one bump (from step 3's cascade).
        Long tokenVersion = userRepository.findById(userId).orElseThrow().getTokenVersion();
        assertThat(tokenVersion).isEqualTo(1L);
        // End-to-end "stale access token rejected by write-path service" lands in Task 01-XX
        // when BidService ships — we assert the bump here, not the reject. The cascade calls
        // userService.bumpTokenVersion(); the downstream effect on access tokens is the job of
        // the service-layer freshness check, which lands with the first write-path service.

        // Step 5: Refresh with cookie B → 401 (killed by cascade, not just cookie A)
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookieB)))
            .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Task 33: logout then refresh fails
    // -------------------------------------------------------------------------

    @Test
    void logoutThenRefreshFails() throws Exception {
        // Step 1: Register
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"logout@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Logout User\"}"))
            .andExpect(status().isCreated())
            .andReturn();

        String refreshCookie = extractRefreshCookie(registerResult);
        assertThat(refreshCookie).isNotBlank();

        // Step 2: Logout with cookie → 204 + Set-Cookie (cleared)
        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookie)))
            .andExpect(status().isNoContent())
            .andExpect(header().exists("Set-Cookie"));

        // Step 3: Refresh with the now-revoked cookie → 401
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookie)))
            .andExpect(status().isUnauthorized());

        // Step 4: Logout again (idempotency check) → still 204
        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshCookie)))
            .andExpect(status().isNoContent());

        // Step 5: Logout with no cookie → still 204
        mockMvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------------------
    // Task 34: logout-all invalidates all sessions
    // -------------------------------------------------------------------------

    @Test
    void logoutAllInvalidatesAllSessions() throws Exception {
        // Step 1: Register (device 1) — captures access token 1 + cookie 1
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"logoutall@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"LogoutAll User\"}"))
            .andExpect(status().isCreated())
            .andReturn();

        String registerBody = registerResult.getResponse().getContentAsString();
        String accessToken1 = objectMapper.readTree(registerBody).get("accessToken").asText();
        String cookie1 = extractRefreshCookie(registerResult);
        Long userId = objectMapper.readTree(registerBody).get("user").get("id").asLong();
        assertThat(cookie1).isNotBlank();

        // Step 2: Seed a "device 2" refresh token via fixture
        com.slparcelauctions.backend.auth.test.RefreshTokenTestFixture.InsertedToken device2Token =
            refreshTokenTestFixture.insertValid(userId);

        // Step 3: POST /logout-all with access token 1 → 204
        mockMvc.perform(post("/api/v1/auth/logout-all")
                .header("Authorization", "Bearer " + accessToken1))
            .andExpect(status().isNoContent());

        // Flush then clear L1 cache: revokeAllByUserId bulk-updates bypass Hibernate's first-level
        // cache; bumpTokenVersion uses dirty checking and is still pending. Flush ensures the
        // dirty tokenVersion increment is written to the DB before we evict cached entities.
        entityManager.flush();
        entityManager.clear();

        // Step 4: Query DB — all tokens revoked, tokenVersion bumped from 0 to 1
        List<RefreshToken> allTokens = refreshTokenRepository.findAllByUserId(userId);
        assertThat(allTokens).isNotEmpty();
        assertThat(allTokens).allMatch(t -> t.getRevokedAt() != null,
            "all refresh tokens should be revoked after logout-all");

        Long tokenVersion = userRepository.findById(userId).orElseThrow().getTokenVersion();
        assertThat(tokenVersion).isEqualTo(1L);

        // Step 5: Refresh with cookie 1 → 401
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", cookie1)))
            .andExpect(status().isUnauthorized());

        // Step 6: Refresh with device 2 seeded token → 401
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", device2Token.rawToken())))
            .andExpect(status().isUnauthorized());
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
