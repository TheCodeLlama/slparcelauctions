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
}
