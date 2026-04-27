package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Slice-ish tests for the avatar endpoints. Uses @SpringBootTest rather than
 * @WebMvcTest because exercising JWT-authenticated routes against the real
 * security filter chain + real multipart resolver is simpler than mocking it.
 * Same pattern as UserControllerUpdateMeSliceTest.
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
class UserControllerAvatarSliceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String registerAndLogin(String email) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"Avatar Tester\"}",
                email);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private Long userIdFromToken(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(me.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void get_avatar_publicEndpointNoAuth_returnsPlaceholder() throws Exception {
        String token = registerAndLogin("avatar-noauth@example.com");
        Long userId = userIdFromToken(token);

        MvcResult result = mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/128"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400, immutable"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).as("response body must be a valid PNG")
                .startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
                            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A);
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 128, 256})
    void get_avatar_allThreeSizesSucceed(int size) throws Exception {
        String token = registerAndLogin("avatar-size-" + size + "@example.com");
        Long userId = userIdFromToken(token);

        MvcResult result = mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/" + size))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).as("response body must be a valid PNG")
                .startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
                            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A);
    }

    @Test
    void get_avatar_invalidSize_returns400() throws Exception {
        String token = registerAndLogin("avatar-invalid-size@example.com");
        Long userId = userIdFromToken(token);

        mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/user/invalid-avatar-size"))
                .andExpect(jsonPath("$.code").value("USER_INVALID_AVATAR_SIZE"));
    }

    @Test
    void get_avatar_nonexistentUser_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/999999999/avatar/128"))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_avatar_unauthenticated_returns401() throws Exception {
        byte[] pngBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", pngBytes);

        mockMvc.perform(multipart("/api/v1/users/me/avatar").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_avatar_unsupportedFormat_returns400() throws Exception {
        String token = registerAndLogin("avatar-bmp@example.com");
        byte[] bmpBytes = Files.readAllBytes(FIXTURES.resolve("avatar-invalid.bmp"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.bmp", "image/bmp", bmpBytes);

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/user/unsupported-image-format"));
    }

    @Test
    void post_avatar_oversizedMultipart_returns413() throws Exception {
        String token = registerAndLogin("avatar-oversized@example.com");
        byte[] bigBytes = Files.readAllBytes(FIXTURES.resolve("avatar-3mb.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", bigBytes);

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/user/upload-too-large"));
    }

    @Test
    void post_avatar_missingFileParam_returns400() throws Exception {
        String token = registerAndLogin("avatar-missing-file@example.com");

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
