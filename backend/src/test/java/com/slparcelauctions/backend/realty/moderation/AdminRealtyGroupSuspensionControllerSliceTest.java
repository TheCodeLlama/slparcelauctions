package com.slparcelauctions.backend.realty.moderation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import com.slparcelauctions.backend.common.BaseEntity;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionAlreadyActiveException;
import com.slparcelauctions.backend.realty.moderation.exception.SuspensionNotFoundException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link AdminRealtyGroupSuspensionController}.
 *
 * <p>Mirrors the realty-package slice-test convention
 * ({@code @SpringBootTest + @AutoConfigureMockMvc}) so the real JWT auth chain
 * runs end-to-end: this gives us authentic 401/403 paths without manual security
 * stubbing. The {@link RealtyGroupSuspensionService} is replaced with a Mockito
 * bean — every test case stubs the relevant behaviour or asserts the call
 * happened. The mapper is real so the wire-shape contract is exercised.
 *
 * <p>Sub-project F spec §6.2, §9. Test surface matches Task 10:
 * <ul>
 *   <li>{@code postSuspensions_happyPath_returns201WithSuspensionDto}</li>
 *   <li>{@code postSuspensions_withoutAdminAuth_returns403}</li>
 *   <li>{@code postSuspensions_whenAlreadyActive_returns409}</li>
 *   <li>{@code postSuspensions_withBulkSuspendListingsTrue_invokesService}</li>
 *   <li>{@code getSuspensions_returns200WithHistory}</li>
 *   <li>{@code deleteSuspensions_happyPath_returns204AndUpdatesLiftedAt}</li>
 *   <li>{@code deleteSuspensions_whenNotFound_returns404}</li>
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
class AdminRealtyGroupSuspensionControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0010-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0010-000000000002");
    private static final UUID GROUP_PUBLIC_ID = UUID.fromString("00000000-0000-aaaa-0010-000000000100");
    private static final UUID SUSPENSION_PUBLIC_ID = UUID.fromString("00000000-0000-aaaa-0010-000000000200");
    private static final UUID OTHER_SUSPENSION_PUBLIC_ID = UUID.fromString("00000000-0000-aaaa-0010-000000000201");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean RealtyGroupSuspensionService suspensionService;

    private Long adminDbId;
    private Long userDbId;
    private User adminUser;

    @BeforeEach
    void seedUsers() {
        adminUser = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-rgsusp-ctrl@x.com").username("admin-rgsusp-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin Mod").role(Role.ADMIN).verified(true).build()));
        adminDbId = adminUser.getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-rgsusp-ctrl@x.com").username("user-rgsusp-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Regular User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            adminDbId, ADMIN_UUID, "admin-rgsusp-ctrl@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            userDbId, USER_UUID, "user-rgsusp-ctrl@x.com", 1L, Role.USER));
    }

    // ─────────────────────── POST /api/v1/admin/realty-groups/{publicId}/suspensions ───────────────────────

    @Test
    void postSuspensions_happyPath_returns201WithSuspensionDto() throws Exception {
        // Use now-relative timestamps so Bean Validation's @Future on the
        // request DTO and the entity's runtime ACTIVE_TIMED classification
        // (derived from expiresAt > wall-clock now) stay valid regardless
        // of when the test is executed.
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = nowUtc.plusDays(7);
        RealtyGroupSuspension saved = buildSuspension(
            SUSPENSION_PUBLIC_ID,
            nowUtc,
            expiresAt,
            /* liftedAt */ null,
            "spammy listings",
            null);
        when(suspensionService.issue(eq(GROUP_PUBLIC_ID), anyLong(), any(), any(), any(), anyBoolean()))
            .thenReturn(saved);

        String requestBody = String.format("""
                {
                  "reason": "FRAUD",
                  "notes": "spammy listings",
                  "expiresAt": "%s",
                  "bulkSuspendListings": false
                }
                """, expiresAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/suspensions")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.publicId").value(SUSPENSION_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.reason").value("FRAUD"))
           .andExpect(jsonPath("$.notes").value("spammy listings"))
           .andExpect(jsonPath("$.status").value("ACTIVE_TIMED"))
           .andExpect(jsonPath("$.issuedByAdmin.publicId").value(ADMIN_UUID.toString()))
           .andExpect(jsonPath("$.issuedByAdmin.displayName").value(adminUser.getDisplayName()))
           .andExpect(jsonPath("$.liftedAt").doesNotExist())
           .andExpect(jsonPath("$.liftedByAdmin").doesNotExist());
    }

    @Test
    void postSuspensions_withoutAdminAuth_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/suspensions")
                .header("Authorization", "Bearer " + userToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "FRAUD",
                      "notes": "should not be allowed",
                      "bulkSuspendListings": false
                    }
                    """))
           .andExpect(status().isForbidden());
    }

    @Test
    void postSuspensions_whenAlreadyActive_returns409() throws Exception {
        when(suspensionService.issue(eq(GROUP_PUBLIC_ID), anyLong(), any(), any(), any(), anyBoolean()))
            .thenThrow(new SuspensionAlreadyActiveException(GROUP_PUBLIC_ID));

        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/suspensions")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "FRAUD",
                      "notes": "redundant suspension",
                      "bulkSuspendListings": false
                    }
                    """))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("SUSPENSION_ALREADY_ACTIVE"))
           .andExpect(jsonPath("$.groupPublicId").value(GROUP_PUBLIC_ID.toString()));
    }

    @Test
    void postSuspensions_withBulkSuspendListingsTrue_invokesService() throws Exception {
        // See postSuspensions_happyPath for why these are now-relative.
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = nowUtc.plusDays(7);
        RealtyGroupSuspension saved = buildSuspension(
            SUSPENSION_PUBLIC_ID,
            nowUtc,
            expiresAt,
            /* liftedAt */ null,
            "with bulk cascade",
            null);
        when(suspensionService.issue(eq(GROUP_PUBLIC_ID), anyLong(), any(), any(), any(), anyBoolean()))
            .thenReturn(saved);

        String requestBody = String.format("""
                {
                  "reason": "TOS_VIOLATION",
                  "notes": "with bulk cascade",
                  "expiresAt": "%s",
                  "bulkSuspendListings": true
                }
                """, expiresAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/suspensions")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
           .andExpect(status().isCreated());

        // The controller must pass bulkSuspendListings=true through to the service —
        // this is the only safety on a destructive cascading flow.
        verify(suspensionService).issue(
            eq(GROUP_PUBLIC_ID),
            eq(adminDbId),
            eq(SuspensionReason.TOS_VIOLATION),
            eq("with bulk cascade"),
            any(OffsetDateTime.class),
            eq(true));
    }

    // ─────────────────────── GET /api/v1/admin/realty-groups/{publicId}/suspensions ───────────────────────

    @Test
    void getSuspensions_returns200WithHistory() throws Exception {
        // now-relative so the "active" row's expiresAt stays in the
        // future and its status classifies as ACTIVE_TIMED at runtime.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RealtyGroupSuspension active = buildSuspension(
            SUSPENSION_PUBLIC_ID,
            now,
            now.plusDays(7),
            /* liftedAt */ null,
            "active row",
            null);
        RealtyGroupSuspension lifted = buildSuspension(
            OTHER_SUSPENSION_PUBLIC_ID,
            now.minusDays(30),
            now.minusDays(20),
            /* liftedAt */ now.minusDays(25),
            "older lifted row",
            "appeal granted");
        when(suspensionService.listHistory(GROUP_PUBLIC_ID))
            .thenReturn(List.of(active, lifted));

        mvc.perform(get("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/suspensions")
                .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2))
           .andExpect(jsonPath("$[0].publicId").value(SUSPENSION_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$[0].status").value("ACTIVE_TIMED"))
           .andExpect(jsonPath("$[1].publicId").value(OTHER_SUSPENSION_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$[1].status").value("LIFTED"))
           .andExpect(jsonPath("$[1].liftedNotes").value("appeal granted"))
           .andExpect(jsonPath("$[1].liftedByAdmin.publicId").value(ADMIN_UUID.toString()));
    }

    // ─────────────────────── DELETE /api/v1/admin/realty-groups/{publicId}/suspensions/{suspensionPublicId} ───────────────────────

    @Test
    void deleteSuspensions_happyPath_returns204AndUpdatesLiftedAt() throws Exception {
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);
        RealtyGroupSuspension lifted = buildSuspension(
            SUSPENSION_PUBLIC_ID,
            now.minusDays(1),
            now.plusDays(6),
            /* liftedAt */ now,
            "original notes",
            "lifted on appeal");
        when(suspensionService.lift(
                eq(GROUP_PUBLIC_ID), eq(SUSPENSION_PUBLIC_ID), anyLong(), any(), anyBoolean()))
            .thenReturn(lifted);

        mvc.perform(delete("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID
                    + "/suspensions/" + SUSPENSION_PUBLIC_ID)
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "lifted on appeal",
                      "bulkReinstateListings": false
                    }
                    """))
           .andExpect(status().isNoContent());

        verify(suspensionService).lift(
            eq(GROUP_PUBLIC_ID),
            eq(SUSPENSION_PUBLIC_ID),
            eq(adminDbId),
            eq("lifted on appeal"),
            eq(false));
    }

    @Test
    void deleteSuspensions_whenNotFound_returns404() throws Exception {
        when(suspensionService.lift(
                eq(GROUP_PUBLIC_ID), eq(SUSPENSION_PUBLIC_ID), anyLong(), any(), anyBoolean()))
            .thenThrow(new SuspensionNotFoundException(SUSPENSION_PUBLIC_ID));

        mvc.perform(delete("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID
                    + "/suspensions/" + SUSPENSION_PUBLIC_ID)
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "",
                      "bulkReinstateListings": false
                    }
                    """))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value("SUSPENSION_NOT_FOUND"))
           .andExpect(jsonPath("$.suspensionPublicId").value(SUSPENSION_PUBLIC_ID.toString()));
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Build a fully-populated {@link RealtyGroupSuspension} with deterministic
     * {@code publicId} so the wire-shape assertions can pin exact values. The
     * embedded {@code issuedByAdmin} / {@code liftedByAdmin} both reference the
     * seeded admin user so the {@code AdminSummaryDto} carries a real
     * {@code publicId} + {@code displayName}.
     */
    private RealtyGroupSuspension buildSuspension(
            UUID publicId,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime liftedAt,
            String notes,
            String liftedNotes) {
        RealtyGroup group = RealtyGroup.builder()
            .name("Sample Group").slug("sample-group").leaderId(adminDbId).build();
        setPublicId(group, GROUP_PUBLIC_ID);

        RealtyGroupSuspension row = RealtyGroupSuspension.builder()
            .realtyGroup(group)
            .issuedByAdmin(adminUser)
            .reason(SuspensionReason.FRAUD)
            .notes(notes)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .liftedAt(liftedAt)
            .liftedByAdmin(liftedAt == null ? null : adminUser)
            .liftedNotes(liftedNotes)
            .build();
        setPublicId(row, publicId);
        return row;
    }

    /**
     * BaseEntity.publicId has no public setter — reflection is the simplest way
     * to pin a stable id for assertions. Used only in tests.
     */
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
