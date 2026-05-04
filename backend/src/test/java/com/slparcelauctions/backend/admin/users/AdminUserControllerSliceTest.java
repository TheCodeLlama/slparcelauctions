package com.slparcelauctions.backend.admin.users;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
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

import com.slparcelauctions.backend.admin.users.dto.AdminUserDetailDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserSummaryDto;
import com.slparcelauctions.backend.admin.users.exception.SelfDemoteException;
import com.slparcelauctions.backend.admin.users.exception.UserAlreadyAdminException;
import com.slparcelauctions.backend.admin.users.exception.UserNotAdminException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.common.PagedResponse;
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
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminUserControllerSliceTest {

    private static final UUID ADMIN_UUID  = UUID.fromString("00000000-0000-aaaa-0008-000000000001");
    private static final UUID USER_UUID   = UUID.fromString("00000000-0000-aaaa-0008-000000000002");
    private static final UUID TARGET_UUID = UUID.fromString("00000000-0000-aaaa-0008-000000000099");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminUserService adminUserService;
    @MockitoBean AdminRoleService adminRoleService;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-user-ctrl@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-user-ctrl@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
        // Seed the target user so resolveUserId(TARGET_UUID) succeeds in the controller.
        userRepository.findByPublicId(TARGET_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(TARGET_UUID).email("target-user-ctrl@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Target").role(Role.USER).verified(true).build()));
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-user-ctrl@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-user-ctrl@x.com", 1L, Role.USER));
    }

    private static final String NOTES_BODY = "{\"notes\":\"some reason\"}";

    // -------------------------------------------------------------------------
    // GET /api/v1/admin/users — auth gate
    // -------------------------------------------------------------------------

    @Test
    void search_anon_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/users"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void search_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/users")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    @Test
    void search_admin_returns200() throws Exception {
        PagedResponse<AdminUserSummaryDto> empty =
            new PagedResponse<>(Collections.emptyList(), 0L, 0, 0, 25);
        when(adminUserService.search(any(), any())).thenReturn(empty);

        mvc.perform(get("/api/v1/admin/users")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(0));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/admin/users/{id} — auth gate
    // -------------------------------------------------------------------------

    @Test
    void detail_anon_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void detail_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID)
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    @Test
    void detail_admin_returns200() throws Exception {
        AdminUserDetailDto dto = new AdminUserDetailDto(
            TARGET_UUID, "target-user-ctrl@x.com", "Target", null, null,
            Role.USER, false, null, null,
            0L, 0L, 0L, 0L, 0L, null, false, null);
        when(adminUserService.detail(anyLong())).thenReturn(dto);

        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID)
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.publicId").value(TARGET_UUID.toString()));
    }

    // -------------------------------------------------------------------------
    // Tab endpoints — auth gates (spot-check listings and bids)
    // -------------------------------------------------------------------------

    @Test
    void listings_anon_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID + "/listings"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void listings_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID + "/listings")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    @Test
    void listings_admin_returns200() throws Exception {
        PagedResponse<com.slparcelauctions.backend.admin.users.dto.AdminUserListingRowDto> empty =
            new PagedResponse<>(Collections.emptyList(), 0L, 0, 0, 25);
        when(adminUserService.listings(anyLong(), any())).thenReturn(empty);

        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID + "/listings")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk());
    }

    @Test
    void bids_anon_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID + "/bids"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void ips_anon_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID + "/ips"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void ips_admin_returns200() throws Exception {
        when(adminUserService.ips(anyLong())).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/v1/admin/users/" + TARGET_UUID + "/ips")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/admin/users/{id}/promote — auth + conflict paths
    // -------------------------------------------------------------------------

    @Test
    void promote_anon_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/promote")
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void promote_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/promote")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isForbidden());
    }

    @Test
    void promote_alreadyAdmin_returns409_ALREADY_ADMIN() throws Exception {
        doThrow(new UserAlreadyAdminException(0L))
            .when(adminRoleService).promote(anyLong(), anyLong(), anyString());

        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/promote")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("ALREADY_ADMIN"));
    }

    @Test
    void promote_admin_returns200() throws Exception {
        doNothing().when(adminRoleService).promote(anyLong(), anyLong(), anyString());

        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/promote")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/admin/users/{id}/demote — auth + conflict paths
    // -------------------------------------------------------------------------

    @Test
    void demote_anon_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/demote")
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void demote_selfDemote_returns409_SELF_DEMOTE_FORBIDDEN() throws Exception {
        doThrow(new SelfDemoteException())
            .when(adminRoleService).demote(anyLong(), anyLong(), anyString());

        // Use ADMIN_UUID in the path so the service receives the admin's own DB ID.
        mvc.perform(post("/api/v1/admin/users/" + ADMIN_UUID + "/demote")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("SELF_DEMOTE_FORBIDDEN"));
    }

    @Test
    void demote_notAdmin_returns409_NOT_ADMIN() throws Exception {
        doThrow(new UserNotAdminException(0L))
            .when(adminRoleService).demote(anyLong(), anyLong(), anyString());

        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/demote")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("NOT_ADMIN"));
    }

    @Test
    void demote_admin_returns200() throws Exception {
        doNothing().when(adminRoleService).demote(anyLong(), anyLong(), anyString());

        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/demote")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/admin/users/{id}/reset-frivolous-counter
    // -------------------------------------------------------------------------

    @Test
    void resetCounter_anon_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/reset-frivolous-counter")
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void resetCounter_admin_returns200() throws Exception {
        doNothing().when(adminRoleService).resetFrivolousCounter(anyLong(), anyLong(), anyString());

        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/reset-frivolous-counter")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTES_BODY))
           .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Validation: blank notes returns 400
    // -------------------------------------------------------------------------

    @Test
    void promote_blankNotes_returns400_VALIDATION_FAILED() throws Exception {
        mvc.perform(post("/api/v1/admin/users/" + TARGET_UUID + "/promote")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
