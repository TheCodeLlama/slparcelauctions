package com.slparcelauctions.backend.admin.ban;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.slparcelauctions.backend.admin.ban.dto.AdminBanRowDto;
import com.slparcelauctions.backend.admin.ban.exception.BanAlreadyLiftedException;
import com.slparcelauctions.backend.admin.ban.exception.BanNotFoundException;
import com.slparcelauctions.backend.admin.ban.exception.BanTypeFieldMismatchException;
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
class AdminBanControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0005-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0005-000000000002");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminBanService adminBanService;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-ban-ctrl@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-ban-ctrl@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-ban-ctrl@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-ban-ctrl@x.com", 1L, Role.USER));
    }

    private AdminBanRowDto sampleRow() {
        return new AdminBanRowDto(
            1L, BanType.IP, "10.0.0.1", null,
            null, null, null,
            BanReasonCategory.SPAM, "spam ban",
            1L, "Admin",
            null, OffsetDateTime.now(),
            null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/admin/bans — auth gate
    // -------------------------------------------------------------------------

    @Test
    void list_anon_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/bans"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/bans")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    @Test
    void list_admin_returns200() throws Exception {
        PagedResponse<AdminBanRowDto> response =
            new PagedResponse<>(List.of(sampleRow()), 1L, 1, 0, 25);
        when(adminBanService.list(anyString(), any(), any())).thenReturn(response);

        mvc.perform(get("/api/v1/admin/bans")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(1));
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/admin/bans — auth gate + validation
    // -------------------------------------------------------------------------

    @Test
    void create_anon_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/bans")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"banType\":\"IP\",\"ipAddress\":\"10.0.0.1\","
                + "\"reasonCategory\":\"SPAM\",\"reasonText\":\"test\"}"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void create_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/bans")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"banType\":\"IP\",\"ipAddress\":\"10.0.0.1\","
                + "\"reasonCategory\":\"SPAM\",\"reasonText\":\"test\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void create_emptyReasonText_returns400_VALIDATION_FAILED() throws Exception {
        mvc.perform(post("/api/v1/admin/bans")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"banType\":\"IP\",\"ipAddress\":\"10.0.0.1\","
                + "\"reasonCategory\":\"SPAM\",\"reasonText\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void create_banTypeMismatch_returns400_BAN_TYPE_FIELD_MISMATCH() throws Exception {
        when(adminBanService.create(any(), anyLong()))
            .thenThrow(new BanTypeFieldMismatchException("IP ban requires ipAddress and no slAvatarUuid"));

        mvc.perform(post("/api/v1/admin/bans")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"banType\":\"IP\",\"ipAddress\":\"10.0.0.1\","
                + "\"slAvatarUuid\":\"" + UUID.randomUUID() + "\","
                + "\"reasonCategory\":\"SPAM\",\"reasonText\":\"test\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("BAN_TYPE_FIELD_MISMATCH"));
    }

    @Test
    void create_admin_returns200() throws Exception {
        when(adminBanService.create(any(), anyLong())).thenReturn(sampleRow());

        mvc.perform(post("/api/v1/admin/bans")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"banType\":\"IP\",\"ipAddress\":\"10.0.0.1\","
                + "\"reasonCategory\":\"SPAM\",\"reasonText\":\"spam ban\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(1));
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/admin/bans/{id}/lift — auth gate + validation
    // -------------------------------------------------------------------------

    @Test
    void lift_anon_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/bans/42/lift")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"liftedReason\":\"lifting\"}"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void lift_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/bans/42/lift")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"liftedReason\":\"lifting\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void lift_emptyLiftedReason_returns400_VALIDATION_FAILED() throws Exception {
        mvc.perform(post("/api/v1/admin/bans/42/lift")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"liftedReason\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void lift_banNotFound_returns404_BAN_NOT_FOUND() throws Exception {
        when(adminBanService.lift(anyLong(), anyLong(), anyString()))
            .thenThrow(new BanNotFoundException(42L));

        mvc.perform(post("/api/v1/admin/bans/42/lift")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"liftedReason\":\"lifting ban\"}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value("BAN_NOT_FOUND"));
    }

    @Test
    void lift_alreadyLifted_returns409_BAN_ALREADY_LIFTED() throws Exception {
        when(adminBanService.lift(anyLong(), anyLong(), anyString()))
            .thenThrow(new BanAlreadyLiftedException(42L));

        mvc.perform(post("/api/v1/admin/bans/42/lift")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"liftedReason\":\"lifting ban\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("BAN_ALREADY_LIFTED"));
    }

    @Test
    void lift_admin_returns200() throws Exception {
        AdminBanRowDto liftedRow = new AdminBanRowDto(
            42L, BanType.IP, "10.0.0.1", null,
            null, null, null,
            BanReasonCategory.SPAM, "spam ban",
            1L, "Admin",
            null, OffsetDateTime.now(),
            OffsetDateTime.now(), 1L, "Admin", "lifting ban");
        when(adminBanService.lift(anyLong(), anyLong(), anyString())).thenReturn(liftedRow);

        mvc.perform(post("/api/v1/admin/bans/42/lift")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"liftedReason\":\"lifting ban\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.liftedReason").value("lifting ban"));
    }
}
