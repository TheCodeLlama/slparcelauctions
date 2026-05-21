package com.slparcelauctions.backend.realty.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Regression coverage for the {@link org.hibernate.LazyInitializationException}
 * class of bug that surfaced on {@code GET /api/v1/admin/coupons/{publicId}}
 * (PR #388). The plan Task 3 default-listing slot is exposed on the primary
 * public DTO endpoint ({@code GET /api/v1/realty-groups/{publicId}}); the DTO
 * mapper walks the agent-rows collection at mapping time. This test confirms
 * a fresh upload of both variants on a group renders both URL fields in the
 * response without tripping LazyInit when the controller runs in its own
 * transaction (no enclosing test-level {@code @Transactional}).
 *
 * <p>Pattern matches the existing realty-package regression-test conventions:
 * non-{@code @Transactional} test class, {@code @Transactional} seed +
 * cleanup so the controller's call survives a closed session and we still
 * scrub fixtures between runs.
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
class RealtyGroupDefaultListingImageLazyInitRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired RealtyGroupRepository groupRepo;
    @Autowired RealtyGroupMemberRepository memberRepo;

    // Mocked away so the storage layer wires cleanly. The GET we exercise here
    // hits the JSON DTO endpoint - it does not request bytes - so the mock
    // never receives a get() call. Seeding the object-key column on the entity
    // is enough to drive the mapper's URL emission.
    @MockitoBean ObjectStorageService storage;

    private User leader;
    private Long leaderId;
    private Long groupId;
    private UUID groupPublicId;

    @BeforeEach
    @Transactional
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        leader = userRepo.save(User.builder()
                .username("dli-lazy-" + suffix)
                .email("dli-lazy-" + suffix + "@example.com")
                .passwordHash("x")
                .displayName("LazyInit Leader")
                .build());
        leaderId = leader.getId();

        RealtyGroup g = groupRepo.save(RealtyGroup.builder()
                .name("LazyInit Group " + suffix)
                .slug("lazy-init-group-" + suffix)
                .leaderId(leader.getId())
                .build());
        // Seed both default-listing variants on the entity. The bytes never
        // need to exist; the mapper only inspects the *ObjectKey columns.
        g.setDefaultListingLightObjectKey("realty-groups/" + g.getPublicId() + "/default-listing-light.webp");
        g.setDefaultListingLightContentType("image/webp");
        g.setDefaultListingLightSizeBytes(1024L);
        g.setDefaultListingDarkObjectKey("realty-groups/" + g.getPublicId() + "/default-listing-dark.webp");
        g.setDefaultListingDarkContentType("image/webp");
        g.setDefaultListingDarkSizeBytes(1024L);
        g = groupRepo.save(g);
        groupId = g.getId();
        groupPublicId = g.getPublicId();

        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
                .groupId(g.getId())
                .userId(leader.getId())
                .joinedAt(OffsetDateTime.now())
                .build();
        leaderRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepo.save(leaderRow);
    }

    @AfterEach
    @Transactional
    void cleanup() {
        if (groupId != null) {
            memberRepo.findAll().stream()
                    .filter(m -> m.getGroupId().equals(groupId))
                    .forEach(memberRepo::delete);
            groupRepo.findById(groupId).ifPresent(groupRepo::delete);
        }
        if (leaderId != null) {
            userRepo.findById(leaderId).ifPresent(userRepo::delete);
        }
    }

    @Test
    void getPublicDto_outsideEnclosingTx_returnsBothDefaultListingUrls() throws Exception {
        // Without @Transactional(readOnly=true) on the controller method, the
        // mapper's walk of group members would trip LazyInitializationException
        // on a real HTTP request because the service tx has closed before the
        // mapper runs. The public group controller already has @Transactional;
        // this test guards that both default-listing URLs render correctly
        // through the closed-session boundary alongside the other mapper work.
        mockMvc.perform(get("/api/v1/realty-groups/" + groupPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(groupPublicId.toString()))
                .andExpect(jsonPath("$.defaultListingLightUrl").value(
                        "/api/v1/realty-groups/" + groupPublicId + "/default-listing/image?variant=light"))
                .andExpect(jsonPath("$.defaultListingDarkUrl").value(
                        "/api/v1/realty-groups/" + groupPublicId + "/default-listing/image?variant=dark"));
    }
}
