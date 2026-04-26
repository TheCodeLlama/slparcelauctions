package com.slparcelauctions.backend.user;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class UpdateUserFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerUpdateMeReadBack_persistsFields() throws Exception {
        // Register
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"update-flow@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Original\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();

        // PUT /me to update both fields
        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Updated Name\",\"bio\":\"Updated bio text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Name"))
                .andExpect(jsonPath("$.bio").value("Updated bio text"));

        // GET /me returns the persisted values
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Name"))
                .andExpect(jsonPath("$.bio").value("Updated bio text"));
    }

    @Test
    void getMe_returnsAllPrivateFields() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"full-fields@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Full\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                // Public fields
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.displayName").value("Full"))
                .andExpect(jsonPath("$.verified").value(false))
                // Private fields (extended in Task 5)
                .andExpect(jsonPath("$.email").value("full-fields@example.com"))
                .andExpect(jsonPath("$.emailVerified").exists())
                .andExpect(jsonPath("$.notifyEmail").exists())
                .andExpect(jsonPath("$.notifySlIm").exists())
                // SL fields are null for an unverified user. Jackson's default
                // serializes them as explicit JSON null, so the keys are present
                // with nullValue() rather than missing.
                .andExpect(jsonPath("$.slAvatarName").value(nullValue()))
                .andExpect(jsonPath("$.slBornDate").value(nullValue()))
                .andExpect(jsonPath("$.slPayinfo").value(nullValue()))
                .andExpect(jsonPath("$.verifiedAt").value(nullValue()));
    }
}
