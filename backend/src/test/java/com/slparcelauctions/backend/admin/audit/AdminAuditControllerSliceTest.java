package com.slparcelauctions.backend.admin.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import java.util.UUID;

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
class AdminAuditControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean AdminActionRepository adminActionRepo;

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(1L, UUID.randomUUID(), "admin@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(2L, UUID.randomUUID(), "user@x.com", 1L, Role.USER));
    }

    private AdminAction buildAction() {
        User admin = User.builder()
            .email("admin@x.com")
            .passwordHash("x")
            .displayName("Admin")
            .build();
        return AdminAction.builder()
            .id(1L)
            .adminUser(admin)
            .actionType(AdminActionType.PROMOTE_USER)
            .targetType(AdminActionTargetType.USER)
            .targetId(99L)
            .notes("promoted")
            .createdAt(OffsetDateTime.now())
            .build();
    }

    // -------------------------------------------------------------------------
    // Auth gates
    // -------------------------------------------------------------------------

    @Test
    void list_anon_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/audit"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/audit")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Response shape
    // -------------------------------------------------------------------------

    @Test
    void list_noFilter_admin_returns200_withPagedShape() throws Exception {
        when(adminActionRepo.findAll(any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new PageImpl<>(Collections.emptyList()));

        mvc.perform(get("/api/v1/admin/audit")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(0))
           .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void list_filterByTargetTypeAndId_returns200() throws Exception {
        AdminAction action = buildAction();
        when(adminActionRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            eq(AdminActionTargetType.USER), eq(99L), any()))
            .thenReturn(new PageImpl<>(List.of(action)));

        mvc.perform(get("/api/v1/admin/audit?targetType=USER&targetId=99")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(1))
           .andExpect(jsonPath("$.content[0].actionType").value("PROMOTE_USER"))
           .andExpect(jsonPath("$.content[0].adminDisplayName").value("Admin"))
           .andExpect(jsonPath("$.content[0].notes").value("promoted"));
    }

    @Test
    void list_filterByAdminUserId_returns200() throws Exception {
        AdminAction action = buildAction();
        when(adminActionRepo.findByAdminUserIdOrderByCreatedAtDesc(eq(1L), any()))
            .thenReturn(new PageImpl<>(List.of(action)));

        mvc.perform(get("/api/v1/admin/audit?adminUserId=1")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(1));
    }
}
