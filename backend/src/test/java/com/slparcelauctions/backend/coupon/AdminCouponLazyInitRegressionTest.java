package com.slparcelauctions.backend.coupon;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Regression coverage for the {@link org.hibernate.LazyInitializationException}
 * surfaced in prod on {@code GET /api/v1/admin/coupons/{publicId}}: the mapper
 * iterated {@code Coupon.discounts} (LAZY @OneToMany) after the service
 * transaction closed.
 *
 * <p>The companion {@link AdminCouponControllerIntegrationTest} runs every
 * test method inside a class-level {@code @Transactional}, which causes
 * MockMvc's invocation to share the test's open session - so the bug never
 * triggered there. This class deliberately omits {@code @Transactional} on
 * the test methods so the controller call sees the same boundary as a real
 * HTTP request: setup writes commit, the controller runs in its own
 * transaction, and the post-service mapper has to survive a closed session.
 *
 * <p>Cleanup is manual in {@link #cleanup()} because there's no
 * test-wrapping rollback.
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
class AdminCouponLazyInitRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired CouponRepository couponRepo;

    private User admin;
    private String adminJwt;
    private Long createdCouponId;

    @BeforeEach
    @Transactional
    void seed() {
        // Unique username/email per run; this method commits, so we
        // can't rely on rollback to clean it up.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepo.save(User.builder()
                .username("lazy-init-admin-" + suffix)
                .email("lazy-init-admin-" + suffix + "@example.com")
                .passwordHash("x")
                .role(Role.ADMIN)
                .build());
        adminJwt = jwtService.issueAccessToken(new AuthPrincipal(
                admin.getId(), admin.getPublicId(), admin.getEmail(), 0L, Role.ADMIN));

        // Coupon with at least one discount line - the LAZY @OneToMany
        // collection is exactly what triggered the bug.
        Coupon coupon = Coupon.builder()
                .code("LAZY-INIT-" + suffix)
                .description("regression for LazyInitializationException")
                .durationDays(30)
                .maxPerUser(1)
                .active(true)
                .notifyOnGrant(true)
                .createdByUserId(admin.getId())
                .build();
        coupon.setDiscounts(List.of(
                CouponDiscount.builder()
                        .coupon(coupon)
                        .target(DiscountTarget.LISTING_FEE)
                        .op(DiscountOp.OVERRIDE)
                        .value(new BigDecimal("0"))
                        .sortOrder(0)
                        .build()));
        Coupon saved = couponRepo.save(coupon);
        createdCouponId = saved.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        if (createdCouponId != null) {
            couponRepo.findById(createdCouponId).ifPresent(couponRepo::delete);
        }
        if (admin != null) {
            userRepo.findById(admin.getId()).ifPresent(userRepo::delete);
        }
    }

    @Test
    void getDetail_outsideEnclosingTx_initializesLazyCollections() throws Exception {
        // Without the fix, the controller's mapper trips
        // LazyInitializationException on coupon.discounts and the global
        // handler returns 500 + INTERNAL_SERVER_ERROR. With the fix
        // (@EntityGraph on findByPublicId + @Transactional on the
        // controller method), the call returns 200 with the discount
        // list serialized.
        UUID publicId = couponRepo.findById(createdCouponId).orElseThrow().getPublicId();
        mockMvc.perform(get("/api/v1/admin/coupons/" + publicId)
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("LAZY-INIT-")))
                .andExpect(jsonPath("$.discounts").isArray())
                .andExpect(jsonPath("$.discounts[0].target").value("LISTING_FEE"))
                .andExpect(jsonPath("$.discounts[0].op").value("OVERRIDE"));
    }

    @Test
    void listFirstPage_outsideEnclosingTx_initializesLazyCollectionsPerRow() throws Exception {
        // List path: AdminCouponController.list maps each Page<Coupon>
        // entry via mapper.toSummary which iterates coupon.discounts.
        // Same LazyInit risk, defended by @Transactional(readOnly=true)
        // on the controller method.
        mockMvc.perform(get("/api/v1/admin/coupons?q=LAZY-INIT-")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].discounts[0].target")
                        .value(org.hamcrest.Matchers.hasItem("LISTING_FEE")));
    }
}
