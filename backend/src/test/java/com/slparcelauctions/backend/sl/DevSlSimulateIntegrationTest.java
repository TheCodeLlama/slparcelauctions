package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * End-to-end integration test for the dev-profile simulate helper. Walks the
 * exact browser flow: register a user, generate a verification code, then POST
 * the code to {@code /api/v1/dev/sl/simulate-verify} with no other fields.
 * Confirms the helper synthesizes SL headers + body defaults and delegates to
 * the real {@link SlVerificationService} successfully.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class DevSlSimulateIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void simulate_withJustCode_linksAvatarWithDefaults() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sim@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Sim\"}"))
                .andExpect(status().isCreated()).andReturn();
        String regBody = reg.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(regBody).get("accessToken").asText();
        Long userId = objectMapper.readTree(regBody).get("user").get("id").asLong();

        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();

        mockMvc.perform(post("/api/v1/dev/sl/simulate-verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"verificationCode\":\"%s\"}", code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.slAvatarName").value("Dev Tester"));

        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getVerified()).isTrue();
        assertThat(user.getSlAvatarName()).isEqualTo("Dev Tester");
    }
}
