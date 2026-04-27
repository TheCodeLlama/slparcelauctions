package com.slparcelauctions.backend.sl;

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
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice-style test for the SL verification controller. Uses {@code @SpringBootTest}
 * over {@code @WebMvcTest} because the full Spring Security filter chain plus
 * {@link SlHeaderValidator} + {@link SlConfigProperties} wiring is the unit under
 * test - mocking the chain in a true slice is more ceremony than the test is worth.
 * Matches the pattern used by {@code AuthFlowIntegrationTest}.
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
class SlVerificationControllerSliceTest {

    @Autowired MockMvc mockMvc;

    private static final String VALID_BODY = """
        {
          "verificationCode":"000000",
          "avatarUuid":"11111111-1111-1111-1111-111111111111",
          "avatarName":"Tester",
          "displayName":"Tester",
          "username":"tester",
          "bornDate":"2012-01-01",
          "payInfo":3
        }
        """;

    @Test
    void missingShardHeader_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-SecondLife-Owner-Key", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/sl/invalid-headers"))
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));
    }

    @Test
    void wrongShard_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-SecondLife-Shard", "Beta")
                .header("X-SecondLife-Owner-Key", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));
    }

    @Test
    void unknownOwnerKey_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", "00000000-0000-0000-0000-000000000999"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));
    }

    @Test
    void validHeadersButNonExistentCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/sl/code-not-found"))
                .andExpect(jsonPath("$.code").value("SL_CODE_NOT_FOUND"));
    }
}
