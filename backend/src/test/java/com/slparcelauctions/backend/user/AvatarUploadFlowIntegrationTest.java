package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StorageConfigProperties;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Full-round-trip integration tests against the real dev MinIO bucket. Exercises
 * the register -> upload -> fetch path end-to-end, including the StorageStartupValidator.
 *
 * <p>{@code @Transactional} rolls back the DB rows after each test, but S3 writes are
 * external to the JPA transaction, so {@link #cleanup()} explicitly deletes every uploaded
 * object prefix. Without this, re-running the class piles up dangling avatar objects in
 * the dev MinIO bucket.
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
class AvatarUploadFlowIntegrationTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ObjectStorageService storage;
    @Autowired S3Client s3Client;
    @Autowired StorageConfigProperties storageProps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdUserIds) {
            try {
                storage.deletePrefix("avatars/" + id + "/");
            } catch (Exception ignored) {
                // Best-effort: the test's primary assertions already passed (or failed);
                // a cleanup hiccup should not mask that result.
            }
        }
        createdUserIds.clear();
    }

    private String registerAndTrack(String email) throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format(
                        "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"Avatar Tester\"}",
                        email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(reg.getResponse().getContentAsString());
        // Track the internal Long id for S3 cleanup (avatars/{id}/ key prefix).
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        createdUserIds.add(userId);
        return body.get("accessToken").asText();
    }

    private UUID userPublicIdFromToken(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(me.getResponse().getContentAsString()).get("publicId").asText());
    }

    @Test
    void storageBucketExists_provesStartupValidatorRan() {
        // Single headBucket assertion that proves StorageStartupValidator ran
        // during context startup and either found or created the bucket.
        // If the bucket were missing, headBucket would throw NoSuchBucketException
        // and fail the test.
        s3Client.headBucket(HeadBucketRequest.builder()
                .bucket(storageProps.bucket())
                .build());
    }

    @Test
    void fullFlow_registerUploadFetchReadBack() throws Exception {
        String token = registerAndTrack("avatar-roundtrip@example.com");
        UUID userId = userPublicIdFromToken(token);

        byte[] pngBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", pngBytes);

        // Upload
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePicUrl").value("/api/v1/users/" + userId + "/avatar/256"));

        // Fetch all three sizes and assert each is a valid PNG at the expected dimensions.
        for (int size : new int[]{64, 128, 256}) {
            MvcResult res = mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/" + size))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(header().string("Cache-Control", "public, max-age=86400, immutable"))
                    .andReturn();
            byte[] body = res.getResponse().getContentAsByteArray();
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(body));
            assertThat(img)
                    .as("avatar size %d should decode as a valid PNG (body length=%d)", size, body.length)
                    .isNotNull();
            assertThat(img.getWidth()).isEqualTo(size);
            assertThat(img.getHeight()).isEqualTo(size);
        }
    }

    @Test
    void fullFlow_reuploadOverwritesPriorObject() throws Exception {
        String token = registerAndTrack("avatar-reupload@example.com");
        UUID userId = userPublicIdFromToken(token);

        byte[] firstBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        byte[] secondBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.jpg"));

        // Upload first image
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(new MockMultipartFile("file", "first.png", "image/png", firstBytes))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult firstFetch = mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/256"))
                .andExpect(status().isOk())
                .andReturn();
        byte[] firstStored = firstFetch.getResponse().getContentAsByteArray();

        // Upload second (different) image
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(new MockMultipartFile("file", "second.jpg", "image/jpeg", secondBytes))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult secondFetch = mockMvc.perform(get("/api/v1/users/" + userId + "/avatar/256"))
                .andExpect(status().isOk())
                .andReturn();
        byte[] secondStored = secondFetch.getResponse().getContentAsByteArray();

        assertThat(secondStored)
                .as("re-upload must overwrite the prior object and produce different stored bytes")
                .isNotEqualTo(firstStored);
    }

    @Test
    void fullFlow_uploadedUserHasProfilePicUrlInGetMe() throws Exception {
        String token = registerAndTrack("avatar-getme@example.com");
        UUID userId = userPublicIdFromToken(token);

        byte[] pngBytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                .file(new MockMultipartFile("file", "avatar.png", "image/png", pngBytes))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePicUrl").value("/api/v1/users/" + userId + "/avatar/256"));
    }
}
