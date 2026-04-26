package com.slparcelauctions.backend.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class VerificationFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerThenGenerateThenReadActiveThenRegenerate() throws Exception {
        // Register a user
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"verif@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Verif\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();

        // /active returns 404 before any code exists
        mockMvc.perform(get("/api/v1/verification/active")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());

        // Generate a code
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        String firstCode = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();
        assertThat(firstCode).matches("^[0-9]{6}$");

        // /active now returns it
        mockMvc.perform(get("/api/v1/verification/active")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(firstCode));

        // Regenerate - old code should be voided, new code returned
        MvcResult gen2 = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        String secondCode = objectMapper.readTree(gen2.getResponse().getContentAsString())
                .get("code").asText();

        // /active now returns the new one
        mockMvc.perform(get("/api/v1/verification/active")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(secondCode));
    }
}
