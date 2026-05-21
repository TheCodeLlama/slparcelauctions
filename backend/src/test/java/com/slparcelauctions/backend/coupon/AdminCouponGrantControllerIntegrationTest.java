package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.coupon.dto.DirectGrantRequest;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration coverage for {@link AdminCouponGrantController}. Spins the
 * full Spring context (auth chain + slice advice + JPA) and exercises
 * every endpoint via {@link MockMvc} with a real admin JWT.
 *
 * <p>Test surface (Plan Task 14):
 * <ul>
 *   <li>GET list returns paged grants, newest first by {@code grantedAt}</li>
 *   <li>GET with {@code state=ACTIVE} filter excludes revoked grants</li>
 *   <li>GET with {@code source=ADMIN_GRANT} filter excludes REDEMPTION
 *       and SIGNUP_WINDOW grants</li>
 *   <li>POST direct-grant to N users returns 201 + N grants with
 *       {@code source=ADMIN_GRANT}</li>
 *   <li>POST direct-grant skips users already at the {@code maxPerUser}
 *       ceiling (idempotent)</li>
 *   <li>POST direct-grant with an unknown user publicId returns 404
 *       {@code UNKNOWN_CODE}</li>
 *   <li>POST revoke flips state to REVOKED and returns the updated DTO</li>
 *   <li>POST revoke with a grant that belongs to a different coupon
 *       returns 404 {@code UNKNOWN_CODE}</li>
 *   <li>Non-admin token returns 403 on every endpoint</li>
 *   <li>Resolver ignores REVOKED grants (regression guard)</li>
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
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class AdminCouponGrantControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @Autowired CouponDiscountResolver resolver;
    @PersistenceContext EntityManager em;

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private User admin;
    private User userA;
    private User userB;
    private User userC;
    private String adminJwt;
    private String userJwt;

    @BeforeEach
    void seed() {
        admin = userRepo.save(User.builder()
                .username("admin-" + shortId())
                .email("admin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Admin").role(Role.ADMIN).verified(true).build());
        userA = userRepo.save(User.builder()
                .username("user-a-" + shortId())
                .email("user-a-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("UserA").role(Role.USER).verified(true).build());
        userB = userRepo.save(User.builder()
                .username("user-b-" + shortId())
                .email("user-b-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("UserB").role(Role.USER).verified(true).build());
        userC = userRepo.save(User.builder()
                .username("user-c-" + shortId())
                .email("user-c-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("UserC").role(Role.USER).verified(true).build());
        adminJwt = jwtService.issueAccessToken(new AuthPrincipal(
                admin.getId(), admin.getPublicId(), admin.getEmail(), 0L, Role.ADMIN));
        userJwt = jwtService.issueAccessToken(new AuthPrincipal(
                userA.getId(), userA.getPublicId(), userA.getEmail(),
                0L, Role.USER));
    }

    /* ============================================================ */
    /* GET /api/v1/admin/coupons/{publicId}/grants                  */
    /* ============================================================ */

    @Test
    void list_returnsPagedGrantsNewestFirst() throws Exception {
        Coupon c = saveCoupon("LIST-" + shortId());
        // Insert in age order: oldest first, newest last. ORDER BY
        // granted_at DESC means the response should reverse that.
        CouponGrant oldest = saveGrant(c, userA, CouponGrantSource.REDEMPTION);
        CouponGrant middle = saveGrant(c, userB, CouponGrantSource.ADMIN_GRANT);
        CouponGrant newest = saveGrant(c, userC, CouponGrantSource.SIGNUP_WINDOW);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons/" + c.getPublicId() + "/grants")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(24))
                .andExpect(jsonPath("$.content[0].publicId")
                        .value(newest.getPublicId().toString()))
                .andExpect(jsonPath("$.content[1].publicId")
                        .value(middle.getPublicId().toString()))
                .andExpect(jsonPath("$.content[2].publicId")
                        .value(oldest.getPublicId().toString()));
    }

    @Test
    void list_stateFilter_excludesNonMatchingStates() throws Exception {
        Coupon c = saveCoupon("STATE-" + shortId());
        CouponGrant active = saveGrant(c, userA, CouponGrantSource.REDEMPTION);
        CouponGrant revoked = saveGrant(c, userB, CouponGrantSource.ADMIN_GRANT);
        revoked.setState(CouponGrantState.REVOKED);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons/" + c.getPublicId() + "/grants")
                        .param("state", "ACTIVE")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId")
                        .value(active.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].state").value("ACTIVE"));
    }

    @Test
    void list_sourceFilter_excludesNonMatchingSources() throws Exception {
        Coupon c = saveCoupon("SOURCE-" + shortId());
        saveGrant(c, userA, CouponGrantSource.REDEMPTION);
        CouponGrant adminGrant = saveGrant(c, userB, CouponGrantSource.ADMIN_GRANT);
        saveGrant(c, userC, CouponGrantSource.SIGNUP_WINDOW);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons/" + c.getPublicId() + "/grants")
                        .param("source", "ADMIN_GRANT")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId")
                        .value(adminGrant.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].source").value("ADMIN_GRANT"));
    }

    /* ============================================================ */
    /* POST /api/v1/admin/coupons/{publicId}/grants                 */
    /* ============================================================ */

    @Test
    void directGrant_threeUsers_returns201AndThreeGrants() throws Exception {
        Coupon c = saveCoupon("BATCH-" + shortId());
        em.flush();

        DirectGrantRequest req = new DirectGrantRequest(
                List.of(userA.getPublicId(), userB.getPublicId(), userC.getPublicId()));

        mockMvc.perform(post("/api/v1/admin/coupons/" + c.getPublicId() + "/grants")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].source").value("ADMIN_GRANT"))
                .andExpect(jsonPath("$[1].source").value("ADMIN_GRANT"))
                .andExpect(jsonPath("$[2].source").value("ADMIN_GRANT"))
                .andExpect(jsonPath("$[0].couponPublicId").value(c.getPublicId().toString()));

        assertThat(grantRepo.countByCouponId(c.getId())).isEqualTo(3);
    }

    @Test
    void directGrant_userAlreadyAtCeiling_skippedSilently() throws Exception {
        Coupon c = saveCoupon("SKIP-" + shortId());
        // userA already holds a grant; maxPerUser=1 (default in helper).
        saveGrant(c, userA, CouponGrantSource.REDEMPTION);
        em.flush();

        DirectGrantRequest req = new DirectGrantRequest(
                List.of(userA.getPublicId(), userB.getPublicId()));

        mockMvc.perform(post("/api/v1/admin/coupons/" + c.getPublicId() + "/grants")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                // Only userB's grant is created; userA is skipped.
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].source").value("ADMIN_GRANT"));

        // userA still has exactly 1 grant (the original REDEMPTION); userB
        // has 1 new ADMIN_GRANT.
        assertThat(grantRepo.countByCouponIdAndUserId(c.getId(), userA.getId()))
                .isEqualTo(1);
        assertThat(grantRepo.countByCouponIdAndUserId(c.getId(), userB.getId()))
                .isEqualTo(1);
    }

    @Test
    void directGrant_unknownUser_returns404UnknownCode() throws Exception {
        Coupon c = saveCoupon("UNKNOWN-" + shortId());
        em.flush();

        DirectGrantRequest req = new DirectGrantRequest(
                List.of(UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/admin/coupons/" + c.getPublicId() + "/grants")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_CODE"));
    }

    /* ============================================================ */
    /* POST /api/v1/admin/coupons/{publicId}/grants/{grantPublicId}/revoke */
    /* ============================================================ */

    @Test
    void revoke_flipsStateToRevokedAndReturnsDto() throws Exception {
        Coupon c = saveCoupon("REVOKE-" + shortId());
        CouponGrant g = saveGrant(c, userA, CouponGrantSource.REDEMPTION);
        em.flush();

        mockMvc.perform(post("/api/v1/admin/coupons/" + c.getPublicId()
                        + "/grants/" + g.getPublicId() + "/revoke")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(g.getPublicId().toString()))
                .andExpect(jsonPath("$.state").value("REVOKED"));

        em.flush();
        em.clear();
        CouponGrant after = grantRepo.findByPublicId(g.getPublicId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(CouponGrantState.REVOKED);
    }

    @Test
    void revoke_grantBelongsToDifferentCoupon_returns404UnknownCode() throws Exception {
        Coupon c1 = saveCoupon("MISMATCH-A-" + shortId());
        Coupon c2 = saveCoupon("MISMATCH-B-" + shortId());
        // Grant is on c2; URL references c1.
        CouponGrant gOnC2 = saveGrant(c2, userA, CouponGrantSource.REDEMPTION);
        em.flush();

        mockMvc.perform(post("/api/v1/admin/coupons/" + c1.getPublicId()
                        + "/grants/" + gOnC2.getPublicId() + "/revoke")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_CODE"));
    }

    /* ============================================================ */
    /* Auth                                                         */
    /* ============================================================ */

    @Test
    void list_nonAdminToken_returns403() throws Exception {
        Coupon c = saveCoupon("FORBID-LIST-" + shortId());
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons/" + c.getPublicId() + "/grants")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void directGrant_nonAdminToken_returns403() throws Exception {
        Coupon c = saveCoupon("FORBID-POST-" + shortId());
        em.flush();

        DirectGrantRequest req = new DirectGrantRequest(List.of(userB.getPublicId()));

        mockMvc.perform(post("/api/v1/admin/coupons/" + c.getPublicId() + "/grants")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void revoke_nonAdminToken_returns403() throws Exception {
        Coupon c = saveCoupon("FORBID-REVOKE-" + shortId());
        CouponGrant g = saveGrant(c, userA, CouponGrantSource.REDEMPTION);
        em.flush();

        mockMvc.perform(post("/api/v1/admin/coupons/" + c.getPublicId()
                        + "/grants/" + g.getPublicId() + "/revoke")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isForbidden());
    }

    /* ============================================================ */
    /* Resolver regression: REVOKED grants do not appear in snapshot */
    /* ============================================================ */

    @Test
    void resolver_ignoresRevokedGrants() throws Exception {
        // OVERRIDE on commission interprets the value as a percent
        // (1.0 -> 0.01 rate). Use 1 here so the coupon's resulting rate
        // (0.01) clearly beats the default (0.05) so the resolver picks
        // this grant.
        Coupon c = saveCouponWithCommissionDiscount(
                "RESOLVER-" + shortId(), new BigDecimal("1"));
        CouponGrant g = saveGrant(c, userA, CouponGrantSource.REDEMPTION);
        em.flush();

        // Before revoke: commission rate from the coupon wins over default.
        CouponDiscountResolver.DiscountSnapshot before = resolver.resolve(userA.getId());
        assertThat(before.commissionCouponGrantId()).isEqualTo(g.getId());
        assertThat(before.commissionRate()).isEqualByComparingTo(new BigDecimal("0.01"));

        // Revoke through the controller.
        mockMvc.perform(post("/api/v1/admin/coupons/" + c.getPublicId()
                        + "/grants/" + g.getPublicId() + "/revoke")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVOKED"));
        em.flush();
        em.clear();

        // After revoke: no coupon wins, snapshot falls back to defaults.
        CouponDiscountResolver.DiscountSnapshot after = resolver.resolve(userA.getId());
        assertThat(after.commissionCouponGrantId()).isNull();
        assertThat(after.listingFeeCouponGrantId()).isNull();
    }

    /* ============================================================ */
    /* Helpers                                                      */
    /* ============================================================ */

    private Coupon saveCoupon(String code) {
        Coupon c = Coupon.builder()
                .code(code)
                .description(code + " desc")
                .durationDays(30)
                .active(true)
                .notifyOnGrant(false)
                .maxPerUser(1)
                .createdByUserId(admin.getId())
                .build();
        c.getDiscounts().add(CouponDiscount.builder()
                .coupon(c).target(DiscountTarget.LISTING_FEE)
                .op(DiscountOp.PERCENT_OFF)
                .value(new BigDecimal("10.00")).sortOrder(0)
                .build());
        return couponRepo.save(c);
    }

    private Coupon saveCouponWithCommissionDiscount(String code, BigDecimal rate) {
        Coupon c = Coupon.builder()
                .code(code)
                .description(code + " desc")
                .durationDays(30)
                .active(true)
                .notifyOnGrant(false)
                .maxPerUser(1)
                .createdByUserId(admin.getId())
                .build();
        c.getDiscounts().add(CouponDiscount.builder()
                .coupon(c).target(DiscountTarget.COMMISSION_RATE)
                .op(DiscountOp.OVERRIDE)
                .value(rate).sortOrder(0)
                .build());
        return couponRepo.save(c);
    }

    private CouponGrant saveGrant(Coupon c, User u, CouponGrantSource source) {
        return grantRepo.save(CouponGrant.builder()
                .coupon(c).user(u)
                .state(CouponGrantState.ACTIVE)
                .source(source)
                .build());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
