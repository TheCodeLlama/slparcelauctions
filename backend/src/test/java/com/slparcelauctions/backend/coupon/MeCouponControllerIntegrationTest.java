package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import com.slparcelauctions.backend.coupon.dto.RedeemCouponRequest;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration coverage for {@link MeCouponController} and the
 * {@link CouponExceptionHandler} status mapping. Spins the full Spring
 * context so JWT auth, slice advice, and JPA all participate; each test
 * rolls back via {@code @Transactional}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code GET /me/coupons} default = active grants only</li>
 *   <li>{@code GET /me/coupons?filter=history} = non-ACTIVE grants only</li>
 *   <li>{@code POST /me/coupons/redeem} happy path returns 201 +
 *       {@code CouponGrantDto}</li>
 *   <li>Every rejection axis from spec section 4 maps to its expected
 *       HTTP status + {@code code} property on the {@code ProblemDetail}</li>
 *   <li>{@code GET /me/listings/prospective-discounts} returns the
 *       resolver snapshot with coupon-code attribution</li>
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
class MeCouponControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @PersistenceContext EntityManager em;

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private User admin;
    private User holder;
    private User other;
    private String holderJwt;

    @BeforeEach
    void seed() {
        admin = userRepo.save(User.builder()
                .username("admin-" + shortId())
                .email("admin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Admin").verified(true).build());
        holder = userRepo.save(User.builder()
                .username("holder-" + shortId())
                .email("holder-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Holder").verified(true).build());
        other = userRepo.save(User.builder()
                .username("other-" + shortId())
                .email("other-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Other").verified(true).build());
        holderJwt = jwtService.issueAccessToken(
                new AuthPrincipal(holder.getId(), holder.getPublicId(),
                        holder.getEmail(), 0L, Role.USER));
    }

    /* ============================================================ */
    /* GET /me/coupons                                              */
    /* ============================================================ */

    @Test
    void list_default_returnsActiveGrantsOnly() throws Exception {
        Coupon coupon = saveCoupon("LIST-ACTIVE-" + shortId(), 30, null);
        // (a) ACTIVE grant - should appear.
        CouponGrant active = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .grantedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        // (b) EXPIRED grant - should NOT appear.
        grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .grantedAt(OffsetDateTime.now().minusDays(40))
                .expiresAt(OffsetDateTime.now().minusDays(1))
                .state(CouponGrantState.EXPIRED)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        // (c) ACTIVE for another user - must not leak.
        grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(other)
                .grantedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        em.flush();

        mockMvc.perform(get("/api/v1/me/coupons")
                        .header("Authorization", "Bearer " + holderJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].publicId").value(active.getPublicId().toString()))
                .andExpect(jsonPath("$[0].code").value(coupon.getCode()))
                .andExpect(jsonPath("$[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$[0].source").value("REDEMPTION"));
    }

    @Test
    void list_history_returnsNonActiveGrantsOnly() throws Exception {
        Coupon coupon = saveCoupon("LIST-HIST-" + shortId(), 30, null);
        grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .grantedAt(OffsetDateTime.now())
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        CouponGrant expired = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .grantedAt(OffsetDateTime.now().minusDays(40))
                .expiresAt(OffsetDateTime.now().minusDays(1))
                .state(CouponGrantState.EXPIRED)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        CouponGrant revoked = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .grantedAt(OffsetDateTime.now().minusDays(10))
                .state(CouponGrantState.REVOKED)
                .source(CouponGrantSource.ADMIN_GRANT)
                .build());
        em.flush();

        mockMvc.perform(get("/api/v1/me/coupons?filter=history")
                        .header("Authorization", "Bearer " + holderJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // history view is desc by grantedAt so revoked (newer) is first
                .andExpect(jsonPath("$[0].publicId").value(revoked.getPublicId().toString()))
                .andExpect(jsonPath("$[0].state").value("REVOKED"))
                .andExpect(jsonPath("$[1].publicId").value(expired.getPublicId().toString()))
                .andExpect(jsonPath("$[1].state").value("EXPIRED"));
    }

    /* ============================================================ */
    /* POST /me/coupons/redeem - happy path                         */
    /* ============================================================ */

    @Test
    void redeem_happyPath_returns201AndCreatesGrant() throws Exception {
        Coupon coupon = saveCoupon("REDEEM-OK-" + shortId(), 30, null);
        em.flush();

        mockMvc.perform(post("/api/v1/me/coupons/redeem")
                        .header("Authorization", "Bearer " + holderJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(coupon.getCode())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.couponPublicId").value(coupon.getPublicId().toString()))
                .andExpect(jsonPath("$.code").value(coupon.getCode()))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.source").value("REDEMPTION"));

        // Persisted grant exists for the holder.
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(), holder.getId()))
                .isEqualTo(1);
    }

    /* ============================================================ */
    /* POST /me/coupons/redeem - rejection axes                     */
    /* ============================================================ */

    @Test
    void redeem_unknownCode_returns404WithCode() throws Exception {
        mockMvc.perform(post("/api/v1/me/coupons/redeem")
                        .header("Authorization", "Bearer " + holderJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody("DOES-NOT-EXIST-" + shortId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_CODE"))
                .andExpect(jsonPath("$.title").value("Coupon error"));
    }

    @Test
    void redeem_paused_returns409WithCode() throws Exception {
        Coupon paused = couponRepo.save(Coupon.builder()
                .code("PAUSED-" + shortId())
                .durationDays(30)
                .active(false)
                .createdByUserId(admin.getId())
                .build());
        em.flush();

        mockMvc.perform(post("/api/v1/me/coupons/redeem")
                        .header("Authorization", "Bearer " + holderJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(paused.getCode())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAUSED"));
    }

    @Test
    void redeem_expired_returns409WithCode() throws Exception {
        Coupon expired = couponRepo.save(Coupon.builder()
                .code("EXPIRED-" + shortId())
                .durationDays(30)
                .redeemableUntil(OffsetDateTime.now().minusHours(1))
                .createdByUserId(admin.getId())
                .build());
        em.flush();

        mockMvc.perform(post("/api/v1/me/coupons/redeem")
                        .header("Authorization", "Bearer " + holderJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(expired.getCode())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPIRED"));
    }

    @Test
    void redeem_maxReached_returns409WithCode() throws Exception {
        Coupon coupon = couponRepo.save(Coupon.builder()
                .code("MAX-" + shortId())
                .durationDays(30)
                .maxTotalRedemptions(1)
                .createdByUserId(admin.getId())
                .build());
        // Pre-existing grant for another user takes the only slot.
        grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(other)
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        em.flush();

        mockMvc.perform(post("/api/v1/me/coupons/redeem")
                        .header("Authorization", "Bearer " + holderJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(coupon.getCode())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAX_REACHED"));
    }

    @Test
    void redeem_notEligible_returns403WithCode() throws Exception {
        Coupon allowlisted = Coupon.builder()
                .code("ALLOW-" + shortId())
                .durationDays(30)
                .createdByUserId(admin.getId())
                .build();
        // Allowlist contains `other` only - holder must be NOT_ELIGIBLE.
        allowlisted.getAllowedUsers().add(other);
        couponRepo.save(allowlisted);
        em.flush();

        mockMvc.perform(post("/api/v1/me/coupons/redeem")
                        .header("Authorization", "Bearer " + holderJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(allowlisted.getCode())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ELIGIBLE"));
    }

    @Test
    void redeem_alreadyRedeemed_returns409WithCode() throws Exception {
        Coupon coupon = saveCoupon("ALREADY-" + shortId(), 30, null);
        // Holder already has a grant at maxPerUser=1.
        grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        em.flush();

        mockMvc.perform(post("/api/v1/me/coupons/redeem")
                        .header("Authorization", "Bearer " + holderJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(coupon.getCode())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REDEEMED"));
    }

    /* ============================================================ */
    /* GET /me/listings/prospective-discounts                       */
    /* ============================================================ */

    @Test
    void prospective_withActiveCoupon_returnsAttributedSnapshot() throws Exception {
        Coupon transientCoupon = Coupon.builder()
                .code("PROSP-" + shortId())
                .durationDays(30)
                .createdByUserId(admin.getId())
                .build();
        transientCoupon.getDiscounts().add(CouponDiscount.builder()
                .coupon(transientCoupon)
                .target(DiscountTarget.LISTING_FEE)
                .op(DiscountOp.OVERRIDE)
                .value(new BigDecimal("0"))
                .sortOrder(0)
                .build());
        // OVERRIDE on COMMISSION_RATE treats value as a percent (2 = 2%),
        // returning rate 0.02 — see CouponDiscountCalculator#applyCommission.
        transientCoupon.getDiscounts().add(CouponDiscount.builder()
                .coupon(transientCoupon)
                .target(DiscountTarget.COMMISSION_RATE)
                .op(DiscountOp.OVERRIDE)
                .value(new BigDecimal("2"))
                .sortOrder(1)
                .build());
        Coupon coupon = couponRepo.save(transientCoupon);
        em.flush();
        grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .grantedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        em.flush();

        mockMvc.perform(get("/api/v1/me/listings/prospective-discounts")
                        .header("Authorization", "Bearer " + holderJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingFeeLindens").value(0))
                .andExpect(jsonPath("$.commissionRate").value(0.02))
                .andExpect(jsonPath("$.listingFeeCouponCode").value(coupon.getCode()))
                .andExpect(jsonPath("$.listingFeeCouponPublicId").value(coupon.getPublicId().toString()))
                .andExpect(jsonPath("$.commissionCouponCode").value(coupon.getCode()))
                .andExpect(jsonPath("$.commissionCouponPublicId").value(coupon.getPublicId().toString()));
    }

    @Test
    void prospective_withoutCoupon_returnsDefaultsWithNullAttribution() throws Exception {
        // Holder has no active grants; resolver returns the configured
        // defaults with null grant-ids, and the mapper sets both
        // attribution fields to null.
        mockMvc.perform(get("/api/v1/me/listings/prospective-discounts")
                        .header("Authorization", "Bearer " + holderJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingFeeCouponCode").doesNotExist())
                .andExpect(jsonPath("$.listingFeeCouponPublicId").doesNotExist())
                .andExpect(jsonPath("$.commissionCouponCode").doesNotExist())
                .andExpect(jsonPath("$.commissionCouponPublicId").doesNotExist());
    }

    /* ============================================================ */
    /* Helpers                                                      */
    /* ============================================================ */

    private Coupon saveCoupon(String code, Integer durationDays, Integer maxTotalRedemptions) {
        Coupon c = couponRepo.save(Coupon.builder()
                .code(code)
                .durationDays(durationDays)
                .maxTotalRedemptions(maxTotalRedemptions)
                .createdByUserId(admin.getId())
                .build());
        return c;
    }

    private String redeemBody(String code) throws Exception {
        return objectMapper.writeValueAsString(new RedeemCouponRequest(code));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
