package com.slparcelauctions.backend.realty.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link RealtyGroupImageController}. Mirrors the codebase
 * convention used elsewhere in the realty slice ({@code @SpringBootTest +
 * @AutoConfigureMockMvc + @ActiveProfiles("dev")}) and seeds fixtures through real
 * repositories.
 *
 * <p>Plan Task 2: every endpoint is variant-aware. POST/DELETE take a {@code
 * {variant}} path-param, GET takes a {@code ?variant=} query param; anything
 * outside {@code light}/{@code dark} surfaces as {@code 400 INVALID_VARIANT}.
 *
 * <p>{@link ObjectStorageService} is the only collaborator mocked — the real
 * {@code ImageStorageService} chokepoint runs end-to-end (sniff + decode + resize + WebP
 * encode) so the test exercises the production pipeline. The mock backs every {@code put}
 * with an in-memory {@code Map<key, bytes>} so a follow-up {@code GET .../logo/image} on
 * the same test returns the bytes that were written. This keeps the test self-contained
 * (no MinIO dependency) without short-circuiting the encoder path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class RealtyGroupImageControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;

    /**
     * Mocked S3-facing layer with a per-test in-memory backing map. {@code put} records
     * the bytes; {@code get} replays them; {@code delete} removes the entry so DELETE
     * happy-path tests observe a real eviction. {@code presignGet}/{@code exists} use
     * the default Mockito returns since the controller never calls them on the happy
     * paths under test.
     */
    @MockitoBean ObjectStorageService objectStorage;

    private final java.util.Map<String, byte[]> store = new ConcurrentHashMap<>();

    private User leader;
    private User agent;
    private User outsider;
    private String leaderJwt;
    private String agentJwt;
    private String outsiderJwt;

    @BeforeEach
    void seed() {
        store.clear();
        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            byte[] bytes = inv.getArgument(1);
            store.put(key, bytes);
            return null;
        }).when(objectStorage)
          .put(org.mockito.ArgumentMatchers.anyString(),
               org.mockito.ArgumentMatchers.any(byte[].class),
               org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            byte[] bytes = store.get(key);
            if (bytes == null) {
                throw new ObjectNotFoundException(key, null);
            }
            return new StoredObject(bytes, "image/webp", bytes.length);
        }).when(objectStorage).get(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            store.remove(key);
            return null;
        }).when(objectStorage).delete(org.mockito.ArgumentMatchers.anyString());

        leader = userRepository.save(User.builder()
            .username("img-l-" + UUID.randomUUID().toString().substring(0, 8))
            .email("img-l-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Leader").build());
        agent = userRepository.save(User.builder()
            .username("img-a-" + UUID.randomUUID().toString().substring(0, 8))
            .email("img-a-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Agent").build());
        outsider = userRepository.save(User.builder()
            .username("img-o-" + UUID.randomUUID().toString().substring(0, 8))
            .email("img-o-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Outsider").build());

        leaderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));
        agentJwt = jwtService.issueAccessToken(new AuthPrincipal(
            agent.getId(), agent.getPublicId(), agent.getEmail(), 0L, Role.USER));
        outsiderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            outsider.getId(), outsider.getPublicId(), outsider.getEmail(), 0L, Role.USER));
    }

    // ─────────────────────── POST .../logo/{variant} ───────────────────────

    @Test
    void postLogoLight_leader_returns200_andPersistsObjectKey() throws Exception {
        RealtyGroup g = createGroup(leader);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicId").value(g.getPublicId().toString()))
            .andExpect(jsonPath("$.logoLightUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image?variant=light"))
            .andExpect(jsonPath("$.logoDarkUrl").doesNotExist());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getLogoLightObjectKey())
            .isEqualTo("realty-groups/" + g.getPublicId() + "/logo-light.webp");
        assertThat(fresh.getLogoLightContentType()).isEqualTo("image/webp");
        assertThat(fresh.getLogoLightSizeBytes()).isGreaterThan(0L);
        assertThat(fresh.getLogoDarkObjectKey()).isNull();
        assertThat(store).containsKey(fresh.getLogoLightObjectKey());
    }

    @Test
    void postLogoDark_leader_returns200_andPersistsObjectKey() throws Exception {
        RealtyGroup g = createGroup(leader);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/dark")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logoDarkUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image?variant=dark"))
            .andExpect(jsonPath("$.logoLightUrl").doesNotExist());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getLogoDarkObjectKey())
            .isEqualTo("realty-groups/" + g.getPublicId() + "/logo-dark.webp");
        assertThat(fresh.getLogoLightObjectKey()).isNull();
    }

    @Test
    void postLogo_invalidVariant_returns400() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/sepia")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_VARIANT"))
            .andExpect(jsonPath("$.value").value("sepia"));
    }

    @Test
    void postLogo_unauthenticated_returns401() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void postLogo_memberWithoutEditPermission_returns403() throws Exception {
        RealtyGroup g = createGroup(leader);
        addMember(g, agent);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_PERMISSION_DENIED"));
    }

    @Test
    void postLogo_outsider_returns403() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void postLogo_unknownGroup_returns404() throws Exception {
        mvc.perform(multipart("/api/v1/realty-groups/" + UUID.randomUUID() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_NOT_FOUND"));
    }

    @Test
    void postLogo_dissolvedGroup_returns410() throws Exception {
        RealtyGroup g = createGroup(leader);
        g.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(g);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    @Test
    void postLogo_unsupportedContentType_returns415() throws Exception {
        RealtyGroup g = createGroup(leader);
        MockMultipartFile text = new MockMultipartFile(
            "file", "x.txt", "text/plain",
            "hello-this-is-not-an-image-payload".getBytes());

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(text)
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_FORMAT"));
    }

    // ─────────────────────── POST .../cover/{variant} ───────────────────────

    @Test
    void postCoverLight_leader_returns200_andPersistsObjectKey() throws Exception {
        RealtyGroup g = createGroup(leader);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/light")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverLightUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/cover/image?variant=light"))
            .andExpect(jsonPath("$.coverDarkUrl").doesNotExist());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getCoverLightObjectKey())
            .isEqualTo("realty-groups/" + g.getPublicId() + "/cover-light.webp");
        assertThat(fresh.getCoverLightContentType()).isEqualTo("image/webp");
        assertThat(fresh.getCoverLightSizeBytes()).isGreaterThan(0L);
    }

    @Test
    void postCoverDark_leader_returns200_andPersistsObjectKey() throws Exception {
        RealtyGroup g = createGroup(leader);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/dark")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverDarkUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/cover/image?variant=dark"));

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getCoverDarkObjectKey())
            .isEqualTo("realty-groups/" + g.getPublicId() + "/cover-dark.webp");
    }

    @Test
    void postCover_memberWithEditPermission_returns200() throws Exception {
        RealtyGroup g = createGroup(leader);
        RealtyGroupMember row = addMember(g, agent);
        row.setPermissionSet(java.util.EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE));
        memberRepository.save(row);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isOk());
    }

    // ─────────────────────── DELETE .../logo/{variant} ───────────────────────

    @Test
    void deleteLogoLight_leavesDarkPopulated() throws Exception {
        RealtyGroup g = createGroup(leader);
        // Upload both variants first.
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/dark")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logoLightUrl").doesNotExist())
            .andExpect(jsonPath("$.logoDarkUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image?variant=dark"));

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getLogoLightObjectKey()).isNull();
        assertThat(fresh.getLogoLightContentType()).isNull();
        assertThat(fresh.getLogoLightSizeBytes()).isNull();
        assertThat(fresh.getLogoDarkObjectKey()).isNotNull();
    }

    @Test
    void deleteLogoDark_leavesLightPopulated() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/dark")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId() + "/logo/dark")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logoLightUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image?variant=light"))
            .andExpect(jsonPath("$.logoDarkUrl").doesNotExist());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getLogoLightObjectKey()).isNotNull();
        assertThat(fresh.getLogoDarkObjectKey()).isNull();
    }

    // ─────────────────────── DELETE .../cover/{variant} ───────────────────────

    @Test
    void deleteCoverLight_leavesDarkPopulated() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/dark")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId() + "/cover/light")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverLightUrl").doesNotExist())
            .andExpect(jsonPath("$.coverDarkUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/cover/image?variant=dark"));

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getCoverLightObjectKey()).isNull();
        assertThat(fresh.getCoverDarkObjectKey()).isNotNull();
    }

    @Test
    void deleteCoverDark_leavesLightPopulated() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/dark")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId() + "/cover/dark")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverLightUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/cover/image?variant=light"))
            .andExpect(jsonPath("$.coverDarkUrl").doesNotExist());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getCoverLightObjectKey()).isNotNull();
        assertThat(fresh.getCoverDarkObjectKey()).isNull();
    }

    @Test
    void deleteCover_unsetVariant_isIdempotent() throws Exception {
        // Deleting a variant that was never uploaded returns 200 with cleared columns
        // (no exception from the best-effort storage delete on a null key).
        RealtyGroup g = createGroup(leader);
        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId() + "/cover/light")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getCoverLightObjectKey()).isNull();
    }

    // ─────────────────────── GET .../logo/image?variant= ───────────────────────

    @Test
    void getLogoImageLight_afterUpload_returns200_andBytes() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/logo/image")
                .param("variant", "light"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"))
            .andExpect(header().string("Cache-Control", "max-age=300, public"))
            .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        // WebP magic bytes: "RIFF" .... "WEBP"
        assertThat(new String(bytes, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 4)).isEqualTo("WEBP");
    }

    @Test
    void getLogoImageDark_notSet_returns404() throws Exception {
        RealtyGroup g = createGroup(leader);
        // Only light uploaded; dark slot empty.
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/logo/image")
                .param("variant", "dark"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_IMAGE_NOT_FOUND"))
            .andExpect(jsonPath("$.kind").value("LOGO"));
    }

    @Test
    void getLogoImage_invalidVariant_returns400() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/logo/image")
                .param("variant", "sepia"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_VARIANT"))
            .andExpect(jsonPath("$.value").value("sepia"));
    }

    @Test
    void getLogoImage_missingVariant_returns400() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/logo/image"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getLogoImage_dissolvedGroup_returns410() throws Exception {
        RealtyGroup g = createGroup(leader);
        g.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(g);

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/logo/image")
                .param("variant", "light"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    // ─────────────────────── GET .../cover/image?variant= ───────────────────────

    @Test
    void getCoverImageLight_afterUpload_returns200_andBytes() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/cover/image")
                .param("variant", "light"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"))
            .andExpect(header().string("Cache-Control", "max-age=300, public"));
    }

    @Test
    void getCoverImageDark_afterUpload_returns200_andBytes() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover/dark")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/cover/image")
                .param("variant", "dark"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"));
    }

    @Test
    void getCoverImage_invalidVariant_returns400() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/cover/image")
                .param("variant", "neon"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_VARIANT"));
    }

    // ─────────────────────── helpers ───────────────────────

    private RealtyGroup createGroup(User leaderUser) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup g = groupRepository.save(RealtyGroup.builder()
            .name("Image Group " + suffix)
            .slug("image-group-" + suffix)
            .leaderId(leaderUser.getId())
            .build());
        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(leaderUser.getId()).joinedAt(OffsetDateTime.now()).build();
        leaderRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepository.save(leaderRow);
        return g;
    }

    private RealtyGroupMember addMember(RealtyGroup g, User u) {
        RealtyGroupMember row = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(u.getId()).joinedAt(OffsetDateTime.now()).build();
        row.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        return memberRepository.save(row);
    }

    /**
     * Generates a small in-memory PNG. {@code size}x{@code size} pixels, opaque red.
     * Used as the upload payload — the chokepoint decodes it via ImageIO and re-encodes
     * to WebP, exercising the real pipeline.
     */
    private static MockMultipartFile pngFile(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, 0xFF0000);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return new MockMultipartFile("file", "x.png", "image/png", baos.toByteArray());
    }
}
