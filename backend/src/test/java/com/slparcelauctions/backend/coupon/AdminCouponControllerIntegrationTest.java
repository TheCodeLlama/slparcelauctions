package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.slparcelauctions.backend.coupon.dto.CouponDiscountDto;
import com.slparcelauctions.backend.coupon.dto.CreateCouponRequest;
import com.slparcelauctions.backend.coupon.dto.PatchCouponRequest;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration coverage for {@link AdminCouponController}. Spins the full
 * Spring context (auth chain + slice advice + JPA) and exercises every
 * endpoint via {@link MockMvc} with a real admin JWT.
 *
 * <p>Test surface (Plan Task 13):
 * <ul>
 *   <li>POST happy paths: single discount, multi-discount + allowlist,
 *       signup-window matching pre-existing user</li>
 *   <li>POST rejections: LIFETIME_REQUIRED, SIGNUP_WINDOW_PAIRED,
 *       duplicate code (IMMUTABLE_FIELD)</li>
 *   <li>GET list filters: none, q (code prefix), active=false,
 *       discount_target=COMMISSION_RATE</li>
 *   <li>GET detail by publicId</li>
 *   <li>PATCH allowed fields (description, active) when grants exist;
 *       rejection of {@code durationDays} when grants exist; PATCH
 *       allowed for {@code durationDays} when zero grants</li>
 *   <li>DELETE hard-deletes at zero grants; soft-archives when grants
 *       exist</li>
 *   <li>403 for non-admin token on any endpoint</li>
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
class AdminCouponControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @PersistenceContext EntityManager em;

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private User admin;
    private User regularUser;
    private User otherUser;
    private String adminJwt;
    private String userJwt;

    @BeforeEach
    void seed() {
        admin = userRepo.save(User.builder()
                .username("admin-" + shortId())
                .email("admin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Admin").role(Role.ADMIN).verified(true).build());
        regularUser = userRepo.save(User.builder()
                .username("user-" + shortId())
                .email("user-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("User").role(Role.USER).verified(true).build());
        otherUser = userRepo.save(User.builder()
                .username("other-" + shortId())
                .email("other-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Other").role(Role.USER).verified(true).build());
        adminJwt = jwtService.issueAccessToken(new AuthPrincipal(
                admin.getId(), admin.getPublicId(), admin.getEmail(), 0L, Role.ADMIN));
        userJwt = jwtService.issueAccessToken(new AuthPrincipal(
                regularUser.getId(), regularUser.getPublicId(), regularUser.getEmail(),
                0L, Role.USER));
    }

    /* ============================================================ */
    /* POST /api/v1/admin/coupons - happy paths                     */
    /* ============================================================ */

    @Test
    void post_singleDiscount_noAllowlist_returns201AndCouponDto() throws Exception {
        String code = "POST-OK-" + shortId();
        CreateCouponRequest req = new CreateCouponRequest(
                code, "single discount coupon",
                30, null, null, null, 1,
                null, null, true, true,
                List.of(new CouponDiscountDto(
                        DiscountTarget.LISTING_FEE, DiscountOp.PERCENT_OFF,
                        new BigDecimal("25.00"), null)),
                null);

        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.description").value("single discount coupon"))
                .andExpect(jsonPath("$.durationDays").value(30))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.discounts.length()").value(1))
                .andExpect(jsonPath("$.discounts[0].target").value("LISTING_FEE"))
                .andExpect(jsonPath("$.discounts[0].op").value("PERCENT_OFF"))
                .andExpect(jsonPath("$.allowedUserPublicIds.length()").value(0));
    }

    @Test
    void post_multiDiscount_withAllowlist_persistsBoth() throws Exception {
        String code = "POST-MULTI-" + shortId();
        CreateCouponRequest req = new CreateCouponRequest(
                code, "multi discount with allowlist",
                60, null, null, null, 1,
                null, null, true, true,
                List.of(
                        new CouponDiscountDto(DiscountTarget.LISTING_FEE,
                                DiscountOp.OVERRIDE, new BigDecimal("0"), 0),
                        new CouponDiscountDto(DiscountTarget.COMMISSION_RATE,
                                DiscountOp.OVERRIDE, new BigDecimal("2"), 1)),
                List.of(regularUser.getPublicId(), otherUser.getPublicId()));

        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.discounts.length()").value(2))
                .andExpect(jsonPath("$.allowedUserPublicIds.length()").value(2));

        Coupon saved = couponRepo.findByCodeIgnoreCase(code).orElseThrow();
        assertThat(saved.getAllowedUsers()).hasSize(2);
        assertThat(saved.getDiscounts()).hasSize(2);
    }

    @Test
    void post_signupWindowMatchingPreExistingUser_grantsThatUser() throws Exception {
        // Backdate regularUser's created_at to "yesterday" so the
        // signup window covers it; CreationTimestamp set it to "now"
        // on insert.
        backdateCreatedAtDays(regularUser, 1);

        String code = "SIGNUP-" + shortId();
        LocalDate today = LocalDate.now();
        CreateCouponRequest req = new CreateCouponRequest(
                code, "signup window coupon",
                30, null, null, null, 1,
                today.minusDays(5), today.plusDays(5),
                true, true,
                List.of(new CouponDiscountDto(
                        DiscountTarget.LISTING_FEE, DiscountOp.PERCENT_OFF,
                        new BigDecimal("10.00"), null)),
                null);

        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        Coupon saved = couponRepo.findByCodeIgnoreCase(code).orElseThrow();
        // regularUser falls inside the window and is granted; other and
        // admin are dated outside the window (admin via setUp default,
        // other never backdated so its created_at is "now" which is
        // inside the window). Backdate other and admin so the assertion
        // is deterministic.
        long grantsForRegular = grantRepo.countByCouponIdAndUserId(
                saved.getId(), regularUser.getId());
        assertThat(grantsForRegular).isEqualTo(1);
        CouponGrant g = grantRepo.findByCouponIdAndUserId(
                saved.getId(), regularUser.getId()).orElseThrow();
        assertThat(g.getSource()).isEqualTo(CouponGrantSource.SIGNUP_WINDOW);
    }

    /* ============================================================ */
    /* POST /api/v1/admin/coupons - rejections                      */
    /* ============================================================ */

    @Test
    void post_missingLifetime_returns409_lifetimeRequired() throws Exception {
        CreateCouponRequest req = new CreateCouponRequest(
                "NO-LIFETIME-" + shortId(), "missing lifetime",
                null, null, null, null, 1,
                null, null, true, true,
                List.of(new CouponDiscountDto(
                        DiscountTarget.LISTING_FEE, DiscountOp.PERCENT_OFF,
                        new BigDecimal("10.00"), null)),
                null);

        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIFETIME_REQUIRED"));
    }

    @Test
    void post_unpairedSignupWindow_returns409_signupWindowPaired() throws Exception {
        CreateCouponRequest req = new CreateCouponRequest(
                "BAD-SIGNUP-" + shortId(), "unpaired window",
                30, null, null, null, 1,
                LocalDate.now().minusDays(5), null,
                true, true,
                List.of(new CouponDiscountDto(
                        DiscountTarget.LISTING_FEE, DiscountOp.PERCENT_OFF,
                        new BigDecimal("10.00"), null)),
                null);

        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIGNUP_WINDOW_PAIRED"));
    }

    @Test
    void post_duplicateCode_returns409_immutableField() throws Exception {
        String code = "DUP-" + shortId();
        couponRepo.save(Coupon.builder()
                .code(code).durationDays(30)
                .createdByUserId(admin.getId()).build());
        em.flush();

        CreateCouponRequest req = new CreateCouponRequest(
                code, "duplicate", 30, null, null, null, 1,
                null, null, true, true,
                List.of(new CouponDiscountDto(
                        DiscountTarget.LISTING_FEE, DiscountOp.PERCENT_OFF,
                        new BigDecimal("10.00"), null)),
                null);

        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMMUTABLE_FIELD"));
    }

    /* ============================================================ */
    /* GET /api/v1/admin/coupons - list + filters                   */
    /* ============================================================ */

    @Test
    void list_noFilter_returnsPagedContent() throws Exception {
        saveCoupon("LIST-A-" + shortId(), true, DiscountTarget.LISTING_FEE);
        saveCoupon("LIST-B-" + shortId(), true, DiscountTarget.LISTING_FEE);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(24));
    }

    @Test
    void list_qPrefix_filtersByCode() throws Exception {
        String prefix = "QFILT" + shortId();
        Coupon match = saveCoupon(prefix + "-A", true, DiscountTarget.LISTING_FEE);
        saveCoupon("OTHER-" + shortId(), true, DiscountTarget.LISTING_FEE);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons")
                        .param("q", prefix)
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId")
                        .value(match.getPublicId().toString()));
    }

    @Test
    void list_activeFalse_filtersInactive() throws Exception {
        String prefix = "AFILT" + shortId();
        saveCoupon(prefix + "-ACTIVE", true, DiscountTarget.LISTING_FEE);
        Coupon inactive = saveCoupon(prefix + "-INACTIVE", false, DiscountTarget.LISTING_FEE);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons")
                        .param("q", prefix)
                        .param("active", "false")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId")
                        .value(inactive.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].active").value(false));
    }

    @Test
    void list_discountTarget_filtersByTarget() throws Exception {
        String prefix = "DFILT" + shortId();
        saveCoupon(prefix + "-LF", true, DiscountTarget.LISTING_FEE);
        Coupon commission = saveCoupon(prefix + "-CR", true, DiscountTarget.COMMISSION_RATE);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons")
                        .param("q", prefix)
                        .param("discount_target", "COMMISSION_RATE")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId")
                        .value(commission.getPublicId().toString()));
    }

    /* ============================================================ */
    /* GET /api/v1/admin/coupons/{publicId}                         */
    /* ============================================================ */

    @Test
    void get_byPublicId_returnsCouponDto() throws Exception {
        Coupon c = saveCoupon("DETAIL-" + shortId(), true, DiscountTarget.LISTING_FEE);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/coupons/" + c.getPublicId())
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(c.getPublicId().toString()))
                .andExpect(jsonPath("$.code").value(c.getCode()))
                .andExpect(jsonPath("$.discounts.length()").value(1));
    }

    /* ============================================================ */
    /* PATCH /api/v1/admin/coupons/{publicId}                       */
    /* ============================================================ */

    @Test
    void patch_descriptionAndActive_allowedEvenWithGrants() throws Exception {
        Coupon c = saveCoupon("PATCH-DESC-" + shortId(), true, DiscountTarget.LISTING_FEE);
        grantRepo.save(CouponGrant.builder()
                .coupon(c).user(regularUser)
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        em.flush();

        PatchCouponRequest req = new PatchCouponRequest(
                "updated description", false, null, null, null, null,
                null, null, null);

        mockMvc.perform(patch("/api/v1/admin/coupons/" + c.getPublicId())
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("updated description"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void patch_durationDays_rejectedWhenGrantsExist() throws Exception {
        Coupon c = saveCoupon("PATCH-DUR-" + shortId(), true, DiscountTarget.LISTING_FEE);
        grantRepo.save(CouponGrant.builder()
                .coupon(c).user(regularUser)
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        em.flush();

        PatchCouponRequest req = new PatchCouponRequest(
                null, null, null, null, null, null,
                90, null, null);

        mockMvc.perform(patch("/api/v1/admin/coupons/" + c.getPublicId())
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMMUTABLE_FIELD"));
    }

    @Test
    void patch_durationDays_acceptedWhenZeroGrants() throws Exception {
        Coupon c = saveCoupon("PATCH-DUR-OK-" + shortId(), true, DiscountTarget.LISTING_FEE);
        em.flush();

        PatchCouponRequest req = new PatchCouponRequest(
                null, null, null, null, null, null,
                90, null, null);

        mockMvc.perform(patch("/api/v1/admin/coupons/" + c.getPublicId())
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationDays").value(90));
    }

    /* ============================================================ */
    /* DELETE /api/v1/admin/coupons/{publicId}                      */
    /* ============================================================ */

    @Test
    void delete_zeroGrants_hardDeletes() throws Exception {
        Coupon c = saveCoupon("DEL-HARD-" + shortId(), true, DiscountTarget.LISTING_FEE);
        em.flush();
        Long id = c.getId();
        UUID publicId = c.getPublicId();

        mockMvc.perform(delete("/api/v1/admin/coupons/" + publicId)
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isNoContent());

        em.flush();
        em.clear();
        assertThat(couponRepo.findById(id)).isEmpty();
    }

    @Test
    void delete_withGrants_softArchives() throws Exception {
        Coupon c = saveCoupon("DEL-SOFT-" + shortId(), true, DiscountTarget.LISTING_FEE);
        grantRepo.save(CouponGrant.builder()
                .coupon(c).user(regularUser)
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        em.flush();
        Long id = c.getId();
        UUID publicId = c.getPublicId();

        mockMvc.perform(delete("/api/v1/admin/coupons/" + publicId)
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isNoContent());

        em.flush();
        em.clear();
        Coupon after = couponRepo.findById(id).orElseThrow();
        assertThat(after.getActive()).isFalse();
        assertThat(after.getRedeemableUntil()).isNotNull();
    }

    /* ============================================================ */
    /* Auth                                                         */
    /* ============================================================ */

    @Test
    void list_nonAdminToken_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void post_nonAdminToken_returns403() throws Exception {
        CreateCouponRequest req = new CreateCouponRequest(
                "FORBID-" + shortId(), "forbidden", 30, null, null, null, 1,
                null, null, true, true,
                List.of(new CouponDiscountDto(
                        DiscountTarget.LISTING_FEE, DiscountOp.PERCENT_OFF,
                        new BigDecimal("10.00"), null)),
                null);
        mockMvc.perform(post("/api/v1/admin/coupons")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_nonAdminToken_returns403() throws Exception {
        Coupon c = saveCoupon("DEL-FORBID-" + shortId(), true, DiscountTarget.LISTING_FEE);
        em.flush();

        mockMvc.perform(delete("/api/v1/admin/coupons/" + c.getPublicId())
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isForbidden());
    }

    /* ============================================================ */
    /* Helpers                                                      */
    /* ============================================================ */

    private Coupon saveCoupon(String code, boolean active, DiscountTarget target) {
        Coupon c = Coupon.builder()
                .code(code)
                .description(code + " desc")
                .durationDays(30)
                .active(active)
                .notifyOnGrant(true)
                .maxPerUser(1)
                .createdByUserId(admin.getId())
                .build();
        c.getDiscounts().add(CouponDiscount.builder()
                .coupon(c).target(target).op(DiscountOp.PERCENT_OFF)
                .value(new BigDecimal("10.00")).sortOrder(0)
                .build());
        return couponRepo.save(c);
    }

    private void backdateCreatedAtDays(User u, int daysAgo) {
        OffsetDateTime ts = OffsetDateTime.now(ZoneOffset.UTC).minusDays(daysAgo);
        em.createNativeQuery("UPDATE users SET created_at = :ts WHERE id = :id")
                .setParameter("ts", ts)
                .setParameter("id", u.getId())
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
