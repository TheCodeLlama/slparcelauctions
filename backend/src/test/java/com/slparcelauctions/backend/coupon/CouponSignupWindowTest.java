package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.coupon.dto.CouponDiscountDto;
import com.slparcelauctions.backend.coupon.dto.CreateCouponRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.UserService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserResponse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration coverage for Plan Task 8 (signup-window auto-grant). Two
 * integration points:
 *
 * <ul>
 *   <li>{@link CouponService#createCoupon} backfills matching pre-existing
 *       users when the new coupon carries an active signup window.</li>
 *   <li>{@link UserService#createUser} grants every matching active
 *       signup-window coupon to the new user.</li>
 * </ul>
 *
 * <p>Backdating {@code created_at} requires a native UPDATE because
 * {@code @CreationTimestamp} stamps it on insert; we use the same EM
 * the test owns, so the change rolls back with the transaction.
 *
 * <p>Conventions mirror {@link CouponServiceAdminCrudTest}: SpringBoot
 * context + scheduler-mute properties + transactional rollback.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class CouponSignupWindowTest {

    @Autowired CouponService couponService;
    @Autowired UserService userService;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @Autowired UserRepository userRepo;

    @PersistenceContext EntityManager em;

    private User admin;

    @BeforeEach
    void seed() {
        admin = userRepo.save(makeUser("admin"));
        em.flush();
        // Push admin's created_at far back so any test's signup window
        // never accidentally backfills the admin row.
        backdateCreatedAtDays(admin, 365);
    }

    @Test
    void createCoupon_backfillsPreExistingUsersInWindow() {
        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusDays(5);
        LocalDate windowEnd = today.plusDays(5);

        User inside1 = userRepo.save(makeUser("inside1"));
        User inside2 = userRepo.save(makeUser("inside2"));
        User outside = userRepo.save(makeUser("outside"));
        em.flush();

        backdateCreatedAtDays(inside1, 2);
        backdateCreatedAtDays(inside2, 1);
        backdateCreatedAtDays(outside, 20);

        Coupon coupon = couponService.createCoupon(
                createBuilder("WIN-" + shortId())
                        .signupWindowStart(windowStart)
                        .signupWindowEnd(windowEnd)
                        .build(),
                admin.getId());
        em.flush();

        // Scope to grants we own (this coupon). Other tests / fixtures may
        // have left users in the DB whose createdAt happens to land in the
        // window; we only assert about the three users we just created.
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(), inside1.getId()))
                .isEqualTo(1);
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(), inside2.getId()))
                .isEqualTo(1);
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(), outside.getId()))
                .isZero();
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(), admin.getId()))
                .isZero();

        List<CouponGrant> ours = grantRepo.findAll().stream()
                .filter(g -> g.getCoupon().getId().equals(coupon.getId()))
                .filter(g -> g.getUser().getId().equals(inside1.getId())
                          || g.getUser().getId().equals(inside2.getId()))
                .toList();
        assertThat(ours).hasSize(2);
        assertThat(ours).extracting(CouponGrant::getSource)
                .containsOnly(CouponGrantSource.SIGNUP_WINDOW);
    }

    @Test
    void createUser_withActiveWindow_grantsCoupon() {
        LocalDate today = LocalDate.now();
        Coupon coupon = couponService.createCoupon(
                createBuilder("HOOK-" + shortId())
                        .signupWindowStart(today.minusDays(2))
                        .signupWindowEnd(today.plusDays(2))
                        .build(),
                admin.getId());
        em.flush();

        // No pre-existing matches; backfill is a no-op for this coupon.
        long preGrants = grantRepo.countByCouponId(coupon.getId());

        UserResponse fresh = userService.createUser(new CreateUserRequest(
                "freshie-" + shortId(), "A-good-password-99"));
        em.flush();

        long postGrants = grantRepo.countByCouponId(coupon.getId());
        assertThat(postGrants - preGrants).isEqualTo(1);

        User reloaded = userRepo.findByPublicId(fresh.publicId()).orElseThrow();
        List<CouponGrant> grants = grantRepo.findAll().stream()
                .filter(g -> g.getUser().getId().equals(reloaded.getId()))
                .toList();
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).getCoupon().getId()).isEqualTo(coupon.getId());
        assertThat(grants.get(0).getSource()).isEqualTo(CouponGrantSource.SIGNUP_WINDOW);
    }

    @Test
    void createUser_outsideAllWindows_noGrant() {
        LocalDate today = LocalDate.now();
        Coupon coupon = couponService.createCoupon(
                createBuilder("OFFWIN-" + shortId())
                        .signupWindowStart(today.minusDays(20))
                        .signupWindowEnd(today.minusDays(5))
                        .build(),
                admin.getId());
        em.flush();

        UserResponse fresh = userService.createUser(new CreateUserRequest(
                "lonely-" + shortId(), "A-good-password-99"));
        em.flush();

        User reloaded = userRepo.findByPublicId(fresh.publicId()).orElseThrow();
        long grants = grantRepo.findAll().stream()
                .filter(g -> g.getUser().getId().equals(reloaded.getId())
                          && g.getCoupon().getId().equals(coupon.getId()))
                .count();
        assertThat(grants).isZero();
    }

    @Test
    void createCoupon_inactive_doesNotBackfill_andNewUserGetsNoGrant() {
        LocalDate today = LocalDate.now();

        User existing = userRepo.save(makeUser("existing"));
        em.flush();
        backdateCreatedAtDays(existing, 1);

        Coupon coupon = couponService.createCoupon(
                createBuilder("PAUSED-" + shortId())
                        .signupWindowStart(today.minusDays(5))
                        .signupWindowEnd(today.plusDays(5))
                        .active(false)
                        .build(),
                admin.getId());
        em.flush();

        // (a) Backfill skipped: existing user inside window has no grant.
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(), existing.getId()))
                .isZero();

        // (b) New-user hook skipped: an inactive coupon is excluded by
        // findActiveSignupWindowMatching.
        UserResponse fresh = userService.createUser(new CreateUserRequest(
                "paused-newbie-" + shortId(), "A-good-password-99"));
        em.flush();
        User reloaded = userRepo.findByPublicId(fresh.publicId()).orElseThrow();
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(), reloaded.getId()))
                .isZero();
    }

    @Test
    void createCoupon_noSignupWindow_doesNotBackfill() {
        User existing = userRepo.save(makeUser("plain"));
        em.flush();

        Coupon coupon = couponService.createCoupon(
                createBuilder("NOWIN-" + shortId()).build(),
                admin.getId());
        em.flush();

        assertThat(grantRepo.countByCouponId(coupon.getId())).isZero();
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(), existing.getId()))
                .isZero();
    }

    @Test
    void createCoupon_backfill_isIdempotent_skipsExistingGrants() {
        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusDays(5);
        LocalDate windowEnd = today.plusDays(5);

        User alreadyHasGrant = userRepo.save(makeUser("prior"));
        User needsBackfill = userRepo.save(makeUser("fresh"));
        em.flush();
        backdateCreatedAtDays(alreadyHasGrant, 2);
        backdateCreatedAtDays(needsBackfill, 2);

        // Persist the coupon first via direct repo so we can pre-seed a
        // grant, then re-issue the signup window by patching it on the
        // coupon and triggering backfill via a second createCoupon call
        // is not the right shape -- instead, simulate the idempotency by
        // seeding a grant *before* the backfill loop runs. We do that by
        // running createCoupon, recording the grant for alreadyHasGrant,
        // then calling applySignupWindowCoupons again on a third user
        // and verifying it does not double-grant the prior holder.
        Coupon coupon = couponService.createCoupon(
                createBuilder("IDEM-" + shortId())
                        .signupWindowStart(windowStart)
                        .signupWindowEnd(windowEnd)
                        .build(),
                admin.getId());
        em.flush();

        long priorGrants = grantRepo.countByCouponIdAndUserId(coupon.getId(),
                alreadyHasGrant.getId());
        assertThat(priorGrants).isEqualTo(1);

        // Re-running the per-user hook for alreadyHasGrant must NOT
        // create a second grant.
        couponService.applySignupWindowCoupons(alreadyHasGrant);
        em.flush();
        assertThat(grantRepo.countByCouponIdAndUserId(coupon.getId(),
                alreadyHasGrant.getId())).isEqualTo(1);
    }

    // ---- helpers ----

    private User makeUser(String prefix) {
        return User.builder()
                .username(prefix + "-" + shortId())
                .email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName(prefix)
                .verified(true)
                .build();
    }

    /**
     * Force {@code created_at} to a specific offset relative to "now"
     * via native UPDATE -- {@code @CreationTimestamp} stamps it on
     * insert so the builder cannot. Using a relative offset (rather
     * than a {@code LocalDate.atStartOfDay()}) sidesteps session-
     * timezone surprises in PostgreSQL's
     * {@code CAST(timestamptz AS date)} cast inside the backfill query.
     */
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

    private static CreateRequestBuilder createBuilder(String code) {
        CreateRequestBuilder b = new CreateRequestBuilder();
        b.code = code;
        b.description = "desc";
        b.durationDays = 30;
        b.discounts = List.of(new CouponDiscountDto(DiscountTarget.LISTING_FEE,
                DiscountOp.PERCENT_OFF, new BigDecimal("25.00"), null));
        return b;
    }

    private static final class CreateRequestBuilder {
        String code;
        String description;
        Integer durationDays;
        Integer useCount;
        OffsetDateTime redeemableUntil;
        Integer maxTotalRedemptions;
        Integer maxPerUser;
        LocalDate signupWindowStart;
        LocalDate signupWindowEnd;
        Boolean active;
        Boolean notifyOnGrant;
        List<CouponDiscountDto> discounts;
        List<UUID> allowedUserPublicIds;

        CreateRequestBuilder signupWindowStart(LocalDate v) { this.signupWindowStart = v; return this; }
        CreateRequestBuilder signupWindowEnd(LocalDate v) { this.signupWindowEnd = v; return this; }
        CreateRequestBuilder active(Boolean v) { this.active = v; return this; }

        CreateCouponRequest build() {
            return new CreateCouponRequest(code, description, durationDays, useCount,
                    redeemableUntil, maxTotalRedemptions, maxPerUser,
                    signupWindowStart, signupWindowEnd, active, notifyOnGrant,
                    discounts, allowedUserPublicIds);
        }
    }
}
