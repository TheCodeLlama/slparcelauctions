package com.slparcelauctions.backend.user;

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

/**
 * Slice-ish tests for {@code PUT /api/v1/users/me}. Uses @SpringBootTest rather
 * than @WebMvcTest because exercising JWT-authenticated routes against the real
 * security filter chain is simpler than mocking it — same rationale as
 * SlVerificationControllerSliceTest from Epic 02 sub-spec 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class UserControllerUpdateMeSliceTest {

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Register + login and return the accessToken for use in Authorization headers. */
    private String registerAndLogin(String email) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"Tester\"}",
                email);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    @Test
    void put_me_happyPath_returns200() throws Exception {
        String token = registerAndLogin("put-me-happy@example.com");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice Resident\",\"bio\":\"Designer of whimsical cottages\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Resident"))
                .andExpect(jsonPath("$.bio").value("Designer of whimsical cottages"));
    }

    @Test
    void put_me_displayNameTooLong_returns400() throws Exception {
        String token = registerAndLogin("put-me-longname@example.com");
        String name51 = "A".repeat(51);

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + name51 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/validation"));
    }

    @Test
    void put_me_bioTooLong_returns400() throws Exception {
        String token = registerAndLogin("put-me-longbio@example.com");
        String bio501 = "x".repeat(501);

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bio\":\"" + bio501 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/validation"));
    }

    @Test
    void put_me_displayNameEmpty_returns400() throws Exception {
        String token = registerAndLogin("put-me-empty@example.com");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void put_me_nullFields_skipsUpdate() throws Exception {
        String token = registerAndLogin("put-me-null@example.com");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":null,\"bio\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Tester"));
    }

    /**
     * SECURITY CANARY — do not remove. This test enforces the
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)} guard on
     * {@link com.slparcelauctions.backend.user.dto.UpdateUserRequest}. Without it
     * a client could sneak {@code email}, {@code role}, or {@code verified} fields
     * into a PUT /me body and potentially escalate privilege via field injection.
     */
    @Test
    void put_me_rejectsUnknownFields_returns400() throws Exception {
        String token = registerAndLogin("put-me-canary@example.com");

        mockMvc.perform(put("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"email\":\"hacker@example.com\",\"role\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/user/unknown-field"))
                .andExpect(jsonPath("$.code").value("USER_UNKNOWN_FIELD"));
    }

    @Test
    void put_me_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"New Name\"}"))
                .andExpect(status().isUnauthorized());
    }
}
