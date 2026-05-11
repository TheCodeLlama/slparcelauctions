package com.slparcelauctions.backend.parceltag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.parceltag.dto.AdminParcelTagCategoryDto;
import com.slparcelauctions.backend.parceltag.dto.CreateParcelTagCategoryRequest;
import com.slparcelauctions.backend.parceltag.dto.UpdateParcelTagCategoryRequest;
import com.slparcelauctions.backend.parceltag.exception.ParcelTagCategoryCodeConflictException;
import com.slparcelauctions.backend.parceltag.exception.ParcelTagCategoryNotFoundException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

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
class AdminParcelTagCategoryControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0004-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0004-000000000002");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminParcelTagCategoryService service;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-ptc@x.com").username("admin-ptc")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-ptc@x.com").username("user-ptc")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(
            new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-ptc@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(
            new AuthPrincipal(userDbId, USER_UUID, "user-ptc@x.com", 1L, Role.USER));
    }

    private static AdminParcelTagCategoryDto sampleDto(String code) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AdminParcelTagCategoryDto(code, "Label", "Desc", true, now, now);
    }

    @Test
    void list_returns200WithAllCategories() throws Exception {
        when(service.listAll()).thenReturn(List.of(sampleDto("ALPHA"), sampleDto("BETA")));

        mvc.perform(get("/api/v1/admin/parcel-tag-categories")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("ALPHA"))
            .andExpect(jsonPath("$[1].code").value("BETA"));
    }

    @Test
    void create_returns201WithDto() throws Exception {
        when(service.create(eq(adminDbId), any(CreateParcelTagCategoryRequest.class)))
            .thenReturn(sampleDto("TERRAIN"));

        mvc.perform(post("/api/v1/admin/parcel-tag-categories")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"TERRAIN","label":"Terrain","description":"Land surface kind."}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("TERRAIN"));
    }

    @Test
    void create_invalidCode_returns400() throws Exception {
        mvc.perform(post("/api/v1/admin/parcel-tag-categories")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"lowercase","label":"L"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void create_duplicateCode_returns409() throws Exception {
        when(service.create(eq(adminDbId), any(CreateParcelTagCategoryRequest.class)))
            .thenThrow(new ParcelTagCategoryCodeConflictException("TERRAIN"));

        mvc.perform(post("/api/v1/admin/parcel-tag-categories")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"TERRAIN","label":"L"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PARCEL_TAG_CATEGORY_CODE_CONFLICT"));
    }

    @Test
    void update_returns200WithUpdatedDto() throws Exception {
        when(service.update(eq(adminDbId), eq("TERRAIN"), any(UpdateParcelTagCategoryRequest.class)))
            .thenReturn(sampleDto("TERRAIN"));

        mvc.perform(patch("/api/v1/admin/parcel-tag-categories/TERRAIN")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"label":"New label"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("TERRAIN"));
    }

    @Test
    void update_missingCode_returns404() throws Exception {
        when(service.update(eq(adminDbId), eq("NOPE"), any(UpdateParcelTagCategoryRequest.class)))
            .thenThrow(new ParcelTagCategoryNotFoundException("NOPE"));

        mvc.perform(patch("/api/v1/admin/parcel-tag-categories/NOPE")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"label":"X"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PARCEL_TAG_CATEGORY_NOT_FOUND"));
    }

    @Test
    void toggleActive_returns200() throws Exception {
        when(service.toggleActive(eq(adminDbId), eq("TERRAIN")))
            .thenReturn(sampleDto("TERRAIN"));

        mvc.perform(post("/api/v1/admin/parcel-tag-categories/TERRAIN/toggle-active")
                .header("Authorization", "Bearer " + adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("TERRAIN"));
    }

    @Test
    void nonAdmin_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/parcel-tag-categories")
                .header("Authorization", "Bearer " + userToken()))
            .andExpect(status().isForbidden());
    }

    @Test
    void anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/parcel-tag-categories"))
            .andExpect(status().isUnauthorized());
    }
}
