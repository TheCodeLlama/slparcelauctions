package com.slparcelauctions.backend.realty.slgroup.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
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
import com.slparcelauctions.backend.common.BaseEntity;
import com.slparcelauctions.backend.realty.slgroup.SlGroupReverifyResult;
import com.slparcelauctions.backend.realty.slgroup.admin.dto.AdminSlGroupRowDto;
import com.slparcelauctions.backend.realty.slgroup.exception.NoDriftDetectedException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link AdminSlGroupController}.
 *
 * <p>Mirrors the moderation-package slice-test convention
 * ({@code @SpringBootTest + @AutoConfigureMockMvc}) so the real JWT auth chain
 * runs end-to-end. {@link AdminSlGroupService} is replaced with a Mockito bean —
 * each test case stubs the service-level behavior or asserts the controller
 * delegated correctly. The controller is intentionally a thin façade, so the
 * coverage focuses on path / status / body / arg-forwarding correctness rather
 * than re-asserting the service-layer audit / DB semantics already covered by
 * {@code SlGroupForceUnregisterServiceTest} and (forthcoming) AdminSlGroupServiceTest
 * equivalents.
 *
 * <p>Sub-project F spec §6.6, §13.3, §13.4, §13.5. Test surface matches Task 25:
 * <ul>
 *   <li>{@code postRecheck_returns200WithResult}</li>
 *   <li>{@code postRecheck_writesAdminAction} — verifies controller forwards
 *       through to service.recheck so the audit-row write (which the service
 *       performs) is invoked.</li>
 *   <li>{@code postAckDrift_clearsDriftAndUpdatesFounderSnapshotIfChanged}</li>
 *   <li>{@code postAckDrift_whenNoDrift_returns409NoDriftDetected}</li>
 *   <li>{@code deleteForce_returns204AndCascades}</li>
 *   <li>{@code deleteForce_withoutForceParam_delegatesToExistingNonForceUnregister}</li>
 * </ul>
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
    "slpa.realty.invitation-expiry.enabled=false",
    "slpa.realty.group-suspension-expiry.enabled=false",
    "slpa.realty.group-bulk-suspend.enabled=false",
    "slpa.realty.sl-group.reverify.enabled=false"
})
class AdminSlGroupControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-eeee-0025-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-eeee-0025-000000000002");
    private static final UUID GROUP_PUBLIC_ID    = UUID.fromString("00000000-0000-eeee-0025-000000000100");
    private static final UUID SL_GROUP_PUBLIC_ID = UUID.fromString("00000000-0000-eeee-0025-000000000200");
    private static final UUID OBSERVED_FOUNDER   = UUID.fromString("00000000-0000-eeee-0025-000000000300");
    private static final UUID NEW_FOUNDER_UUID   = UUID.fromString("00000000-0000-eeee-0025-000000000301");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminSlGroupService adminSlGroupService;

    private Long adminDbId;
    private User adminUser;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminUser = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-slg-ctrl@x.com").username("admin-slg-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin Slg").role(Role.ADMIN).verified(true).build()));
        adminDbId = adminUser.getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-slg-ctrl@x.com").username("user-slg-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Regular User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            adminDbId, ADMIN_UUID, "admin-slg-ctrl@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            userDbId, USER_UUID, "user-slg-ctrl@x.com", 1L, Role.USER));
    }

    private String recheckPath() {
        return "/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID
            + "/sl-groups/" + SL_GROUP_PUBLIC_ID + "/recheck";
    }

    private String ackDriftPath() {
        return "/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID
            + "/sl-groups/" + SL_GROUP_PUBLIC_ID + "/ack-drift";
    }

    private String deletePath() {
        return "/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID
            + "/sl-groups/" + SL_GROUP_PUBLIC_ID;
    }

    // ─── POST /recheck ───────────────────────────────────────────────────

    @Test
    void postRecheck_returns200WithResult() throws Exception {
        when(adminSlGroupService.recheck(eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId)))
            .thenReturn(new SlGroupReverifyResult(true, "FOUNDER_CHANGED", OBSERVED_FOUNDER));

        mvc.perform(post(recheckPath())
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.driftDetected").value(true))
           .andExpect(jsonPath("$.driftReason").value("FOUNDER_CHANGED"))
           .andExpect(jsonPath("$.currentFounderUuid").value(OBSERVED_FOUNDER.toString()));
    }

    @Test
    void postRecheck_writesAdminAction() throws Exception {
        // The actual audit-row write lives inside AdminSlGroupService.recheck — the
        // controller's only job is to invoke it with the resolved admin id and the
        // path UUIDs. Verifying the service is called proves the audit pipeline is
        // engaged; the service-layer test would cover the audit row's contents.
        when(adminSlGroupService.recheck(eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId)))
            .thenReturn(new SlGroupReverifyResult(false, null, OBSERVED_FOUNDER));

        mvc.perform(post(recheckPath())
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());

        verify(adminSlGroupService, times(1))
            .recheck(eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId));
    }

    // ─── POST /ack-drift ─────────────────────────────────────────────────

    @Test
    void postAckDrift_clearsDriftAndUpdatesFounderSnapshotIfChanged() throws Exception {
        // The controller delegates the mutation to the service then re-loads the
        // row via loadAdminRow to render the wire shape. We stub both legs to
        // simulate the founder-rolled-forward state and assert the JSON reflects
        // it.
        doNothing().when(adminSlGroupService)
            .ackDrift(eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId), anyString());
        AdminSlGroupRowDto postAck = new AdminSlGroupRowDto(
            SL_GROUP_PUBLIC_ID,
            UUID.randomUUID(),
            "Drift Test Group",
            true,
            null,
            null,
            NEW_FOUNDER_UUID,         // founder snapshot rolled forward
            NEW_FOUNDER_UUID,         // current founder matches snapshot now
            null,
            0,
            null,                     // drift cleared
            null,
            null,
            null,
            null,
            null,
            null);
        when(adminSlGroupService.loadAdminRow(GROUP_PUBLIC_ID, SL_GROUP_PUBLIC_ID))
            .thenReturn(postAck);

        mvc.perform(post(ackDriftPath())
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "Confirmed handover with leader."
                    }
                    """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.publicId").value(SL_GROUP_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.founderAvatarUuid").value(NEW_FOUNDER_UUID.toString()))
           .andExpect(jsonPath("$.driftDetectedAt").doesNotExist())
           .andExpect(jsonPath("$.driftReason").doesNotExist());

        // Verify the controller forwarded the admin id + notes through.
        verify(adminSlGroupService).ackDrift(
            eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId),
            eq("Confirmed handover with leader."));
    }

    @Test
    void postAckDrift_whenNoDrift_returns409NoDriftDetected() throws Exception {
        doThrow(new NoDriftDetectedException(SL_GROUP_PUBLIC_ID))
            .when(adminSlGroupService)
            .ackDrift(eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId), any());

        mvc.perform(post(ackDriftPath())
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "Trying to ack a row with no drift."
                    }
                    """))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("NO_DRIFT_DETECTED"))
           .andExpect(jsonPath("$.slGroupPublicId").value(SL_GROUP_PUBLIC_ID.toString()));
    }

    // ─── DELETE / ────────────────────────────────────────────────────────

    @Test
    void deleteForce_returns204AndCascades() throws Exception {
        doNothing().when(adminSlGroupService).forceUnregister(
            eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId), eq("FRAUD"));

        mvc.perform(delete(deletePath())
                .param("force", "true")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "FRAUD"
                    }
                    """))
           .andExpect(status().isNoContent());

        // The force=true branch delegates to forceUnregister (which itself
        // cascades through BulkListingSuspendService.suspendAll). The non-force
        // path must NOT be invoked.
        verify(adminSlGroupService, times(1)).forceUnregister(
            eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId), eq("FRAUD"));
        verify(adminSlGroupService, never())
            .unregister(any(UUID.class), any(UUID.class), anyLong());
    }

    @Test
    void deleteForce_withoutForceParam_delegatesToExistingNonForceUnregister() throws Exception {
        doNothing().when(adminSlGroupService).unregister(
            eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId));

        mvc.perform(delete(deletePath())
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "CLEANUP"
                    }
                    """))
           .andExpect(status().isNoContent());

        // Default ?force=false → non-force path. forceUnregister must not fire.
        verify(adminSlGroupService, times(1)).unregister(
            eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID), eq(adminDbId));
        verify(adminSlGroupService, never()).forceUnregister(
            any(UUID.class), any(UUID.class), anyLong(), anyString());
    }

    // ─── auth defense-in-depth ─────────────────────────────────────────

    @Test
    void postRecheck_withoutAdminAuth_returns403() throws Exception {
        mvc.perform(post(recheckPath())
                .header("Authorization", "Bearer " + userToken())
                .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isForbidden());

        verifyNoInteractions(adminSlGroupService);
    }

    // ─── helpers ──────────────────────────────────────────────────────

    /**
     * BaseEntity.publicId has no public setter — kept here in case future test
     * cases need to seed entities with deterministic ids (mirrors the other
     * slice tests' pattern). Unused right now because the controller's
     * collaborator is fully mocked; left in to keep the file shape consistent
     * with the other admin slice tests.
     */
    @SuppressWarnings("unused")
    private static void setPublicId(BaseEntity entity, UUID publicId) {
        try {
            Field f = BaseEntity.class.getDeclaredField("publicId");
            f.setAccessible(true);
            f.set(entity, publicId);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to set publicId via reflection", e);
        }
    }
}
