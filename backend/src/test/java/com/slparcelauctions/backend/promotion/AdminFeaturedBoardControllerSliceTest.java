package com.slparcelauctions.backend.promotion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class AdminFeaturedBoardControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0017-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0017-000000000002");
    private static final UUID SLOT_UUID  = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean FeaturedBoardSlotService slotService;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-fb-ctrl@x.com").username("admin-fb-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-fb-ctrl@x.com").username("user-fb-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-fb-ctrl@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-fb-ctrl@x.com", 1L, Role.USER));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/admin/featured-boards -- auth gate
    // -------------------------------------------------------------------------

    @Test
    void list_anon_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/featured-boards"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/featured-boards")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    @Test
    void list_admin_returns200WithRows() throws Exception {
        when(slotService.allActive()).thenReturn(List.of());

        mvc.perform(get("/api/v1/admin/featured-boards")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").isArray());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/admin/featured-boards/{slotPublicId}/release -- auth gate
    // -------------------------------------------------------------------------

    @Test
    void release_anon_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/featured-boards/" + SLOT_UUID + "/release"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void release_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/featured-boards/" + SLOT_UUID + "/release")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    @Test
    void release_admin_returns200() throws Exception {
        doNothing().when(slotService).forceRelease(any());

        mvc.perform(post("/api/v1/admin/featured-boards/" + SLOT_UUID + "/release")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // PATCH /api/v1/admin/featured-boards/{slotPublicId}/move -- auth gate
    // -------------------------------------------------------------------------

    @Test
    void move_anon_returns401() throws Exception {
        mvc.perform(patch("/api/v1/admin/featured-boards/" + SLOT_UUID + "/move")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"boardIndex\":1,\"position\":0}"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void move_userRole_returns403() throws Exception {
        mvc.perform(patch("/api/v1/admin/featured-boards/" + SLOT_UUID + "/move")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"boardIndex\":1,\"position\":0}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void move_admin_returns200() throws Exception {
        doNothing().when(slotService).move(any(), anyInt(), anyInt());

        mvc.perform(patch("/api/v1/admin/featured-boards/" + SLOT_UUID + "/move")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"boardIndex\":2,\"position\":3}"))
           .andExpect(status().isOk());
    }

    /**
     * boardIndex=99 violates the @Max(13) constraint on MovePromotionSlotRequest,
     * so Bean Validation fires before the service is called and returns VALIDATION_FAILED.
     * The INVALID_BOARD_INDEX code path is exercised when move() is invoked
     * programmatically with a value that bypasses the DTO (e.g. future internal callers).
     */
    @Test
    void move_invalidBoardIndex_returns400_VALIDATION_FAILED() throws Exception {
        mvc.perform(patch("/api/v1/admin/featured-boards/" + SLOT_UUID + "/move")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"boardIndex\":99,\"position\":0}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
