package com.slparcelauctions.backend.realty.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
     * the bytes; {@code get} replays them; {@code presignGet}/{@code exists}/{@code
     * delete} use the default Mockito returns since the controller never calls them on
     * the happy paths under test.
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
        // Mock storage backs put/get with an in-memory map per test.
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

    // ─────────────────────── POST .../logo ───────────────────────

    @Test
    void postLogo_leader_returns200_andPersistsObjectKey() throws Exception {
        RealtyGroup g = createGroup(leader);
        MockMultipartFile file = pngFile(16, 16);

        MvcResult result = mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo")
                .file(file)
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicId").value(g.getPublicId().toString()))
            .andExpect(jsonPath("$.logoUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image"))
            .andReturn();

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getLogoLightObjectKey())
            .isEqualTo("realty-groups/" + g.getPublicId() + "/logo.webp");
        assertThat(fresh.getLogoLightContentType()).isEqualTo("image/webp");
        assertThat(fresh.getLogoLightSizeBytes()).isGreaterThan(0L);
        // The chokepoint actually encoded WebP into the mocked storage layer.
        assertThat(store).containsKey(fresh.getLogoLightObjectKey());
    }

    @Test
    void postLogo_unauthenticated_returns401() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo")
                .file(pngFile(16, 16)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void postLogo_memberWithoutEditPermission_returns403() throws Exception {
        // Agent is a member, but EDIT_GROUP_PROFILE is not in their permission set.
        RealtyGroup g = createGroup(leader);
        addMember(g, agent);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_PERMISSION_DENIED"));
    }

    @Test
    void postLogo_outsider_returns403() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void postLogo_unknownGroup_returns404() throws Exception {
        mvc.perform(multipart("/api/v1/realty-groups/" + UUID.randomUUID() + "/logo")
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

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo")
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

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo")
                .file(text)
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_FORMAT"));
    }

    // ─────────────────────── POST .../cover ───────────────────────

    @Test
    void postCover_leader_returns200_andPersistsObjectKey() throws Exception {
        RealtyGroup g = createGroup(leader);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/cover/image"));

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getCoverLightObjectKey())
            .isEqualTo("realty-groups/" + g.getPublicId() + "/cover.webp");
        assertThat(fresh.getCoverLightContentType()).isEqualTo("image/webp");
        assertThat(fresh.getCoverLightSizeBytes()).isGreaterThan(0L);
    }

    @Test
    void postCover_memberWithEditPermission_returns200() throws Exception {
        // Delegating EDIT_GROUP_PROFILE to a non-leader member must let them upload.
        RealtyGroup g = createGroup(leader);
        RealtyGroupMember row = addMember(g, agent);
        row.setPermissionSet(java.util.EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE));
        memberRepository.save(row);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isOk());
    }

    // ─────────────────────── GET .../logo/image ───────────────────────

    @Test
    void getLogoImage_afterUpload_returns200_andBytes() throws Exception {
        RealtyGroup g = createGroup(leader);
        // POST first so the byte path has something to fetch from the in-memory store.
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/logo")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/logo/image"))
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
    void getLogoImage_notSet_returns404() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/logo/image"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_IMAGE_NOT_FOUND"))
            .andExpect(jsonPath("$.kind").value("LOGO"));
    }

    @Test
    void getLogoImage_dissolvedGroup_returns410() throws Exception {
        RealtyGroup g = createGroup(leader);
        g.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(g);

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/logo/image"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    @Test
    void getCoverImage_afterUpload_returns200_andBytes() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/cover")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/cover/image"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"))
            .andExpect(header().string("Cache-Control", "max-age=300, public"));
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
     * to WebP, exercising the real pipeline. ARGB so the encoder picks the lossless
     * path with alpha, but that's fine for these tests.
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

    /** Suppresses unused-warning for {@code eq} import; kept for future negative tests. */
    @SuppressWarnings("unused")
    private static void touchUnused() {
        eq("noop");
    }
}
