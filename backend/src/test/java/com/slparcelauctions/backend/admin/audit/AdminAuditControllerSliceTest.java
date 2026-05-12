package com.slparcelauctions.backend.admin.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
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
class AdminAuditControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0003-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0003-000000000002");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminActionRepository adminActionRepo;
    @MockitoBean RealtyGroupRepository realtyGroupRepository;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-audit-ctrl@x.com").username("admin-audit-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-audit-ctrl@x.com").username("user-audit-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-audit-ctrl@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-audit-ctrl@x.com", 1L, Role.USER));
    }

    private AdminAction buildAction() {
        User admin = User.builder()
            .email("admin@x.com").username("admin")
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

    // -------------------------------------------------------------------------
    // entityType / entityId filters — spec §17 (Task 32, Realty Groups F)
    // -------------------------------------------------------------------------

    private AdminAction buildRealtyGroupAction() {
        User admin = User.builder()
            .email("admin@x.com").username("admin")
            .passwordHash("x")
            .displayName("Admin")
            .build();
        return AdminAction.builder()
            .id(2L)
            .adminUser(admin)
            .actionType(AdminActionType.REALTY_GROUP_SUSPEND)
            .targetType(AdminActionTargetType.REALTY_GROUP)
            .targetId(42L)
            .notes("group suspended")
            .createdAt(OffsetDateTime.now())
            .build();
    }

    @Test
    void list_filterByEntityTypeRealtyGroup_returnsOnlyRealtyGroupActions() throws Exception {
        AdminAction action = buildRealtyGroupAction();
        // groupId=null means "entityType filter only, no specific group".
        when(adminActionRepo.findRealtyGroupActions(isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(action)));

        mvc.perform(get("/api/v1/admin/audit?entityType=REALTY_GROUP")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(1))
           .andExpect(jsonPath("$.content[0].actionType").value("REALTY_GROUP_SUSPEND"));
    }

    @Test
    void list_filterByEntityId_narrowsToSpecificGroup() throws Exception {
        UUID groupPublicId = UUID.fromString("00000000-0000-bbbb-0001-000000000001");
        RealtyGroup group = RealtyGroup.builder().id(42L).publicId(groupPublicId).build();
        when(realtyGroupRepository.findByPublicId(groupPublicId)).thenReturn(Optional.of(group));

        AdminAction action = buildRealtyGroupAction();
        when(adminActionRepo.findRealtyGroupActions(eq(42L), any()))
            .thenReturn(new PageImpl<>(List.of(action)));

        mvc.perform(get("/api/v1/admin/audit?entityType=REALTY_GROUP&entityId=" + groupPublicId)
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(1))
           .andExpect(jsonPath("$.content[0].actionType").value("REALTY_GROUP_SUSPEND"));
    }

    @Test
    void list_filterByEntityIdUnknown_returnsEmptyPage() throws Exception {
        UUID unknown = UUID.fromString("00000000-0000-bbbb-0001-0000000000ff");
        when(realtyGroupRepository.findByPublicId(unknown)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/admin/audit?entityType=REALTY_GROUP&entityId=" + unknown)
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(0))
           .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void list_combinedEntityFilters_appliesBoth() throws Exception {
        UUID groupPublicId = UUID.fromString("00000000-0000-bbbb-0001-000000000002");
        RealtyGroup group = RealtyGroup.builder().id(99L).publicId(groupPublicId).build();
        when(realtyGroupRepository.findByPublicId(groupPublicId)).thenReturn(Optional.of(group));

        AdminAction action = buildRealtyGroupAction();
        when(adminActionRepo.findRealtyGroupActions(eq(99L), any()))
            .thenReturn(new PageImpl<>(List.of(action)));

        mvc.perform(get("/api/v1/admin/audit?entityType=REALTY_GROUP&entityId=" + groupPublicId)
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(1));

        // Regression: the unfiltered path is unaffected by the new branch.
        when(adminActionRepo.findAll(any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new PageImpl<>(Collections.emptyList()));
        mvc.perform(get("/api/v1/admin/audit")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalElements").value(0));
    }
}
