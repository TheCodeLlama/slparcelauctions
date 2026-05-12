package com.slparcelauctions.backend.realty.moderation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.util.Optional;
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

import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService.BulkSuspendResult;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.common.BaseEntity;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link AdminRealtyGroupBulkListingsController}.
 *
 * <p>Mirrors {@code AdminRealtyGroupSuspensionControllerSliceTest}'s convention
 * ({@code @SpringBootTest + @AutoConfigureMockMvc}) so the real JWT auth chain
 * runs end-to-end. {@link BulkListingSuspendService} and
 * {@link RealtyGroupSuspensionRepository} are replaced with Mockito beans;
 * {@link RealtyGroupRepository} is the real Spring Data bean so the seed-and-
 * lookup path through the controller exercises the actual JPA wiring (Hibernate
 * stays in the loop for the {@code findByPublicId} → entity {@code id}
 * translation).
 *
 * <p>Sub-project F spec §6.3. Test surface matches Task 14:
 * <ul>
 *   <li>{@code postSuspendAll_returns200WithCountAndBulkActionId}</li>
 *   <li>{@code postSuspendAll_withGroupSuspensionPublicId_resolvesAndPasses}</li>
 *   <li>{@code postSuspendAll_withoutAdminAuth_returns403}</li>
 *   <li>{@code postReinstateAll_returns200WithReinstatedCount}</li>
 *   <li>{@code postReinstateAll_withoutAdminAuth_returns403}</li>
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
class AdminRealtyGroupBulkListingsControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-bbbb-0014-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-bbbb-0014-000000000002");
    private static final UUID GROUP_PUBLIC_ID = UUID.fromString("00000000-0000-bbbb-0014-000000000100");
    private static final UUID GROUP_SUSPENSION_PUBLIC_ID =
        UUID.fromString("00000000-0000-bbbb-0014-000000000200");
    private static final UUID BULK_ACTION_ID =
        UUID.fromString("00000000-0000-bbbb-0014-000000000300");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository realtyGroupRepository;

    @MockitoBean BulkListingSuspendService bulkService;
    @MockitoBean RealtyGroupSuspensionRepository suspensionRepository;

    private Long adminDbId;
    private Long userDbId;
    private Long groupDbId;

    @BeforeEach
    void seedFixtures() {
        User adminUser = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-rgbulk-ctrl@x.com").username("admin-rgbulk-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin Bulk").role(Role.ADMIN).verified(true).build()));
        adminDbId = adminUser.getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-rgbulk-ctrl@x.com").username("user-rgbulk-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Regular User").role(Role.USER).verified(true).build()))
            .getId();

        RealtyGroup group = realtyGroupRepository.findByPublicId(GROUP_PUBLIC_ID)
            .orElseGet(() -> {
                RealtyGroup g = RealtyGroup.builder()
                    .name("Bulk Test Group")
                    .slug("bulk-test-group-" + UUID.randomUUID())
                    .leaderId(adminDbId)
                    .build();
                setPublicId(g, GROUP_PUBLIC_ID);
                return realtyGroupRepository.save(g);
            });
        groupDbId = group.getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            adminDbId, ADMIN_UUID, "admin-rgbulk-ctrl@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            userDbId, USER_UUID, "user-rgbulk-ctrl@x.com", 1L, Role.USER));
    }

    // ─── POST /api/v1/admin/realty-groups/{publicId}/listings/suspend-all ───

    @Test
    void postSuspendAll_returns200WithCountAndBulkActionId() throws Exception {
        when(bulkService.suspendAll(eq(groupDbId), eq(adminDbId), eq("spam listings"),
                eq("ad-hoc bulk suspend"), eq(null)))
            .thenReturn(new BulkSuspendResult(BULK_ACTION_ID, 7));

        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/listings/suspend-all")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "spam listings",
                      "notes": "ad-hoc bulk suspend"
                    }
                    """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.bulkActionId").value(BULK_ACTION_ID.toString()))
           .andExpect(jsonPath("$.suspendedCount").value(7));

        // Controller must thread `notes` through to the service so the audit
        // row carries the admin commentary alongside reason + bulkActionId.
        verify(bulkService).suspendAll(eq(groupDbId), eq(adminDbId), eq("spam listings"),
            eq("ad-hoc bulk suspend"), eq(null));
    }

    @Test
    void postSuspendAll_withGroupSuspensionPublicId_resolvesAndPasses() throws Exception {
        Long linkedSuspensionDbId = 4242L;
        RealtyGroupSuspension linked = RealtyGroupSuspension.builder()
            .realtyGroup(realtyGroupRepository.findById(groupDbId).orElseThrow())
            .reason(SuspensionReason.FRAUD)
            .notes("linked suspension")
            .build();
        setPublicId(linked, GROUP_SUSPENSION_PUBLIC_ID);
        setBaseId(linked, linkedSuspensionDbId);

        when(suspensionRepository.findByPublicId(GROUP_SUSPENSION_PUBLIC_ID))
            .thenReturn(Optional.of(linked));
        when(bulkService.suspendAll(
                eq(groupDbId), eq(adminDbId), eq("tied to suspension"),
                eq("cascade from group suspension row"), eq(linkedSuspensionDbId)))
            .thenReturn(new BulkSuspendResult(BULK_ACTION_ID, 3));

        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/listings/suspend-all")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "tied to suspension",
                      "notes": "cascade from group suspension row",
                      "groupSuspensionPublicId": "%s"
                    }
                    """.formatted(GROUP_SUSPENSION_PUBLIC_ID)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.suspendedCount").value(3));

        // The controller must translate the UUID to the entity's internal id and
        // pass that as the linkedGroupSuspensionId -- verifying with the literal
        // Long value pins both legs of the resolution. The `notes` body field
        // is also threaded through so the audit row carries the admin context.
        verify(bulkService).suspendAll(
            eq(groupDbId),
            eq(adminDbId),
            eq("tied to suspension"),
            eq("cascade from group suspension row"),
            eq(linkedSuspensionDbId));
    }

    @Test
    void postSuspendAll_withoutAdminAuth_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/listings/suspend-all")
                .header("Authorization", "Bearer " + userToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "should not be allowed",
                      "notes": ""
                    }
                    """))
           .andExpect(status().isForbidden());
    }

    // ─── POST /api/v1/admin/realty-groups/{publicId}/listings/reinstate-all ───

    @Test
    void postReinstateAll_returns200WithReinstatedCount() throws Exception {
        when(bulkService.reinstateAll(eq(groupDbId), eq(adminDbId), eq("appeal granted")))
            .thenReturn(5);

        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/listings/reinstate-all")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "appeal granted"
                    }
                    """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reinstatedCount").value(5));

        verify(bulkService).reinstateAll(eq(groupDbId), eq(adminDbId), eq("appeal granted"));
    }

    @Test
    void postReinstateAll_withoutAdminAuth_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/realty-groups/" + GROUP_PUBLIC_ID + "/listings/reinstate-all")
                .header("Authorization", "Bearer " + userToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "should not be allowed"
                    }
                    """))
           .andExpect(status().isForbidden());
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Pin a deterministic {@code publicId} on the entity so wire-shape and
     * service-arg assertions can use a stable UUID. {@code BaseEntity.publicId}
     * has no public setter; reflection is the cheapest workaround in tests.
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

    /**
     * Pin a deterministic {@code id} on a not-yet-persisted entity so a
     * controller call through the mocked repository can assert the exact id
     * was forwarded to the service. Used only for the
     * {@link RealtyGroupSuspension} stub returned by {@code suspensionRepository}.
     */
    private static void setBaseId(BaseEntity entity, Long id) {
        try {
            Field f = BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to set id via reflection", e);
        }
    }
}
