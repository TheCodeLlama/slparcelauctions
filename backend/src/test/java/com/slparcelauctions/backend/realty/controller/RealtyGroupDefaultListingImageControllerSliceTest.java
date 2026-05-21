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
 * Slice-style coverage for {@link RealtyGroupDefaultListingImageController}.
 * Mirrors {@link RealtyGroupImageControllerSliceTest} exactly: same
 * {@code @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("dev")},
 * same in-memory ObjectStorageService backing, same fixture style.
 *
 * <p>Plan Task 3: POST/DELETE {@code /default-listing/{variant}} take a variant
 * path-param, GET {@code /default-listing/image} takes a {@code ?variant=}
 * query param; anything outside {@code light}/{@code dark} surfaces as
 * {@code 400 INVALID_VARIANT}.
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
class RealtyGroupDefaultListingImageControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;

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
            .username("dli-l-" + UUID.randomUUID().toString().substring(0, 8))
            .email("dli-l-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Leader").build());
        agent = userRepository.save(User.builder()
            .username("dli-a-" + UUID.randomUUID().toString().substring(0, 8))
            .email("dli-a-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Agent").build());
        outsider = userRepository.save(User.builder()
            .username("dli-o-" + UUID.randomUUID().toString().substring(0, 8))
            .email("dli-o-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Outsider").build());

        leaderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));
        agentJwt = jwtService.issueAccessToken(new AuthPrincipal(
            agent.getId(), agent.getPublicId(), agent.getEmail(), 0L, Role.USER));
        outsiderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            outsider.getId(), outsider.getPublicId(), outsider.getEmail(), 0L, Role.USER));
    }

    // ─────────────────────── POST .../default-listing/{variant} ───────────────────────

    @Test
    void postLight_leader_returns200_andPersistsObjectKey() throws Exception {
        RealtyGroup g = createGroup(leader);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(64, 64))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicId").value(g.getPublicId().toString()))
            .andExpect(jsonPath("$.defaultListingLightUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image?variant=light"))
            .andExpect(jsonPath("$.defaultListingDarkUrl").doesNotExist());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getDefaultListingLightObjectKey())
            .isEqualTo("realty-groups/" + g.getPublicId() + "/default-listing-light.webp");
        assertThat(fresh.getDefaultListingLightContentType()).isEqualTo("image/webp");
        assertThat(fresh.getDefaultListingLightSizeBytes()).isGreaterThan(0L);
        assertThat(fresh.getDefaultListingDarkObjectKey()).isNull();
        assertThat(store).containsKey(fresh.getDefaultListingLightObjectKey());
    }

    @Test
    void postDark_leader_returns200_andPersistsObjectKey() throws Exception {
        RealtyGroup g = createGroup(leader);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/dark")
                .file(pngFile(64, 64))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultListingDarkUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image?variant=dark"))
            .andExpect(jsonPath("$.defaultListingLightUrl").doesNotExist());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getDefaultListingDarkObjectKey())
            .isEqualTo("realty-groups/" + g.getPublicId() + "/default-listing-dark.webp");
        assertThat(fresh.getDefaultListingLightObjectKey()).isNull();
    }

    @Test
    void post_invalidVariant_returns400() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/sepia")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_VARIANT"))
            .andExpect(jsonPath("$.value").value("sepia"));
    }

    @Test
    void post_unauthenticated_returns401() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(16, 16)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void post_memberWithoutEditPermission_returns403() throws Exception {
        RealtyGroup g = createGroup(leader);
        addMember(g, agent);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_PERMISSION_DENIED"));
    }

    @Test
    void post_outsider_returns403() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_unknownGroup_returns404() throws Exception {
        mvc.perform(multipart("/api/v1/realty-groups/" + UUID.randomUUID() + "/default-listing/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_NOT_FOUND"));
    }

    @Test
    void post_dissolvedGroup_returns410() throws Exception {
        // Mirrors cover/logo behavior: dissolved groups surface as 410 GROUP_DISSOLVED
        // (the existing realty exception handler maps GroupDissolvedException to 410,
        // not 409). The "409 on a dissolved group" wording in the plan brief is
        // approximate; the implemented contract is 410 to match the rest of the slice.
        RealtyGroup g = createGroup(leader);
        g.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(g);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(16, 16))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    @Test
    void post_unsupportedContentType_returns415() throws Exception {
        RealtyGroup g = createGroup(leader);
        MockMultipartFile text = new MockMultipartFile(
            "file", "x.txt", "text/plain",
            "hello-this-is-not-an-image-payload".getBytes());

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(text)
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_FORMAT"));
    }

    @Test
    void post_memberWithEditPermission_returns200() throws Exception {
        RealtyGroup g = createGroup(leader);
        RealtyGroupMember row = addMember(g, agent);
        row.setPermissionSet(java.util.EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE));
        memberRepository.save(row);

        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isOk());
    }

    // ─────────────────────── DELETE .../default-listing/{variant} ───────────────────────

    @Test
    void deleteLight_leavesDarkPopulated() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/dark")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultListingLightUrl").doesNotExist())
            .andExpect(jsonPath("$.defaultListingDarkUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image?variant=dark"));

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getDefaultListingLightObjectKey()).isNull();
        assertThat(fresh.getDefaultListingLightContentType()).isNull();
        assertThat(fresh.getDefaultListingLightSizeBytes()).isNull();
        assertThat(fresh.getDefaultListingDarkObjectKey()).isNotNull();
    }

    @Test
    void deleteDark_leavesLightPopulated() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/dark")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/dark")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultListingLightUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image?variant=light"))
            .andExpect(jsonPath("$.defaultListingDarkUrl").doesNotExist());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getDefaultListingLightObjectKey()).isNotNull();
        assertThat(fresh.getDefaultListingDarkObjectKey()).isNull();
    }

    @Test
    void delete_unsetVariant_isIdempotent() throws Exception {
        // Deleting a variant that was never uploaded returns 200 with cleared columns
        // (no exception from the best-effort storage delete on a null key) - mirrors
        // cover/logo idempotent-delete behavior.
        RealtyGroup g = createGroup(leader);
        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getDefaultListingLightObjectKey()).isNull();
    }

    // ─────────────────────── GET .../default-listing/image?variant= ───────────────────────

    @Test
    void getImageLight_afterUpload_returns200_andBytes() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image")
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
    void getImageDark_afterUpload_returns200_andBytes() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/dark")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image")
                .param("variant", "dark"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"));
    }

    @Test
    void getImageDark_notSet_returns404() throws Exception {
        RealtyGroup g = createGroup(leader);
        // Only light uploaded; dark slot empty.
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image")
                .param("variant", "dark"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_IMAGE_NOT_FOUND"))
            .andExpect(jsonPath("$.kind").value("DEFAULT_LISTING"));
    }

    @Test
    void getImage_invalidVariant_returns400() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image")
                .param("variant", "invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_VARIANT"))
            .andExpect(jsonPath("$.value").value("invalid"));
    }

    @Test
    void getImage_dissolvedGroup_returns410() throws Exception {
        RealtyGroup g = createGroup(leader);
        g.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(g);

        mvc.perform(get("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image")
                .param("variant", "light"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    // ─────────────────────── Light-only / dark-only seed DTO render ───────────────────────

    @Test
    void publicDto_lightOnly_carriesOnlyLightUrl() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/light")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultListingLightUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image?variant=light"))
            .andExpect(jsonPath("$.defaultListingDarkUrl").doesNotExist());
    }

    @Test
    void publicDto_darkOnly_carriesOnlyDarkUrl() throws Exception {
        RealtyGroup g = createGroup(leader);
        mvc.perform(multipart("/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/dark")
                .file(pngFile(32, 32))
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultListingDarkUrl").value(
                "/api/v1/realty-groups/" + g.getPublicId() + "/default-listing/image?variant=dark"))
            .andExpect(jsonPath("$.defaultListingLightUrl").doesNotExist());
    }

    // ─────────────────────── helpers ───────────────────────

    private RealtyGroup createGroup(User leaderUser) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup g = groupRepository.save(RealtyGroup.builder()
            .name("Default Listing Group " + suffix)
            .slug("default-listing-group-" + suffix)
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
     * Generates a small in-memory PNG. {@code w}x{@code h} pixels, opaque green.
     * Used as the upload payload - the chokepoint decodes it via ImageIO and re-encodes
     * to WebP, exercising the real pipeline.
     */
    private static MockMultipartFile pngFile(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, 0x00AA00);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return new MockMultipartFile("file", "x.png", "image/png", baos.toByteArray());
    }
}
