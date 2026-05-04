package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

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
import com.slparcelauctions.backend.verification.VerificationCodeService;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;

/**
 * End-to-end integration test for the SL verification flow:
 * register a user, generate a verification code, then post it to
 * {@code POST /api/v1/sl/verify} with valid SL headers and assert the
 * resulting User row is fully linked + marked verified.
 *
 * <p>Also covers the avatar-already-linked race: a second user attempting
 * to claim the same {@code avatarUuid} must see HTTP 409 with code
 * {@code SL_AVATAR_ALREADY_LINKED}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class SlVerificationFlowIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired VerificationCodeService verificationCodeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullFlow_registerGenerateVerify_updatesUserRow() throws Exception {
        // Register and extract access token + user id
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"slflow@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"SlFlow\"}"))
                .andExpect(status().isCreated()).andReturn();
        String regBody = reg.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(regBody).get("accessToken").asText();
        Long userId = userRepository.findByEmail("slflow@example.com").orElseThrow().getId();

        // Generate verification code
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();

        // Fire /sl/verify
        String body = String.format("""
            {
              "verificationCode":"%s",
              "avatarUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "avatarName":"Test Resident",
              "displayName":"Test",
              "username":"test.resident",
              "bornDate":"2012-05-15",
              "payInfo":3
            }
            """, code);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.slAvatarName").value("Test Resident"));

        // Assert User row was fully linked
        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getVerified()).isTrue();
        assertThat(user.getSlAvatarName()).isEqualTo("Test Resident");
        assertThat(user.getSlAvatarUuid().toString()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(user.getSlDisplayName()).isEqualTo("Test");
        assertThat(user.getSlUsername()).isEqualTo("test.resident");
        assertThat(user.getSlPayinfo()).isEqualTo(3);
        assertThat(user.getVerifiedAt()).isNotNull();

        // Confirm the verification code row is now used=true (no active code for this user).
        Optional<ActiveCodeResponse> active =
                verificationCodeService.findActive(userId, VerificationCodeType.PLAYER);
        assertThat(active)
                .as("verification code should be consumed after successful verify")
                .isEmpty();
    }

    @Test
    void secondVerifyWithSameAvatar_returns409() throws Exception {
        // First user: register + generate + verify with avatar cccc...
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dup1@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Dup1\"}"))
                .andExpect(status().isCreated()).andReturn();
        String t1 = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();
        MvcResult gen1 = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + t1))
                .andExpect(status().isOk()).andReturn();
        String code1 = objectMapper.readTree(gen1.getResponse().getContentAsString())
                .get("code").asText();
        String body1 = String.format("""
            {"verificationCode":"%s","avatarUuid":"cccccccc-cccc-cccc-cccc-cccccccccccc",
            "avatarName":"A","displayName":"A","username":"a.a","bornDate":"2012-01-01","payInfo":3}
            """, code1);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON).content(body1)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk());

        // Second user tries to link the SAME avatar
        MvcResult reg2 = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dup2@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Dup2\"}"))
                .andExpect(status().isCreated()).andReturn();
        String t2 = objectMapper.readTree(reg2.getResponse().getContentAsString())
                .get("accessToken").asText();
        MvcResult gen2 = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + t2))
                .andExpect(status().isOk()).andReturn();
        String code2 = objectMapper.readTree(gen2.getResponse().getContentAsString())
                .get("code").asText();
        String body2 = String.format("""
            {"verificationCode":"%s","avatarUuid":"cccccccc-cccc-cccc-cccc-cccccccccccc",
            "avatarName":"B","displayName":"B","username":"b.b","bornDate":"2012-02-02","payInfo":3}
            """, code2);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON).content(body2)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SL_AVATAR_ALREADY_LINKED"));
    }
}
