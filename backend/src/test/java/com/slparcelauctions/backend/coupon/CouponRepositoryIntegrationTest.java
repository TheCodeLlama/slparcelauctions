package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Repository coverage for the four hot-path coupon queries:
 *
 * <ul>
 *   <li>{@link CouponRepository#findByCodeIgnoreCase} - powers the
 *       wallet redemption form's code lookup.</li>
 *   <li>{@link CouponRepository#findActiveSignupWindowMatching} - drives
 *       the auto-grant hook on user creation.</li>
 *   <li>{@link CouponGrantRepository#markExpired} - hourly sweeper
 *       that flips ACTIVE grants past their {@code expiresAt} to
 *       EXPIRED.</li>
 * </ul>
 *
 * Conventions follow {@code FeaturedRepositoryIntegrationTest}:
 * {@code @SpringBootTest} + {@code @Transactional} so each test rolls
 * back; quieted background schedulers via {@code @TestPropertySource}.
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
class CouponRepositoryIntegrationTest {

    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @Autowired UserRepository userRepo;

    @PersistenceContext EntityManager em;

    private User admin;
    private User holder;

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
    }

    @Test
    void findByCodeIgnoreCase_matchesRegardlessOfCase() {
        Coupon c = couponRepo.save(Coupon.builder()
                .code("WELCOME30")
                .durationDays(30)
                .createdByUserId(admin.getId())
                .build());
        em.flush();

        assertThat(couponRepo.findByCodeIgnoreCase("welcome30"))
                .isPresent().get().extracting(Coupon::getId).isEqualTo(c.getId());
        assertThat(couponRepo.findByCodeIgnoreCase("WELCOME30"))
                .isPresent().get().extracting(Coupon::getId).isEqualTo(c.getId());
        assertThat(couponRepo.findByCodeIgnoreCase("Welcome30"))
                .isPresent().get().extracting(Coupon::getId).isEqualTo(c.getId());
        assertThat(couponRepo.findByCodeIgnoreCase("does-not-exist"))
                .isEmpty();
    }

    @Test
    void findActiveSignupWindowMatching_returnsCouponsWithToday_inWindow() {
        LocalDate today = LocalDate.now();
        Coupon inWindow = couponRepo.save(Coupon.builder()
                .code("INSIDE-" + shortId())
                .durationDays(30)
                .signupWindowStart(today.minusDays(5))
                .signupWindowEnd(today.plusDays(5))
                .createdByUserId(admin.getId())
                .build());
        Coupon beforeWindow = couponRepo.save(Coupon.builder()
                .code("BEFORE-" + shortId())
                .durationDays(30)
                .signupWindowStart(today.plusDays(2))
                .signupWindowEnd(today.plusDays(10))
                .createdByUserId(admin.getId())
                .build());
        Coupon afterWindow = couponRepo.save(Coupon.builder()
                .code("AFTER-" + shortId())
                .durationDays(30)
                .signupWindowStart(today.minusDays(20))
                .signupWindowEnd(today.minusDays(1))
                .createdByUserId(admin.getId())
                .build());
        Coupon noWindow = couponRepo.save(Coupon.builder()
                .code("NOWIN-" + shortId())
                .durationDays(30)
                .createdByUserId(admin.getId())
                .build());
        em.flush();

        List<Coupon> matches = couponRepo.findActiveSignupWindowMatching(today);

        assertThat(matches).extracting(Coupon::getId)
                .contains(inWindow.getId())
                .doesNotContain(beforeWindow.getId(),
                        afterWindow.getId(),
                        noWindow.getId());
    }

    @Test
    void findActiveSignupWindowMatching_excludesPaused() {
        LocalDate today = LocalDate.now();
        Coupon paused = couponRepo.save(Coupon.builder()
                .code("PAUSED-" + shortId())
                .durationDays(30)
                .signupWindowStart(today.minusDays(3))
                .signupWindowEnd(today.plusDays(3))
                .active(false)
                .createdByUserId(admin.getId())
                .build());
        em.flush();

        List<Coupon> matches = couponRepo.findActiveSignupWindowMatching(today);

        assertThat(matches).extracting(Coupon::getId)
                .doesNotContain(paused.getId());
    }

    @Test
    void markExpired_transitionsOnlyActiveGrantsPastExpiry() {
        Coupon coupon = couponRepo.save(Coupon.builder()
                .code("SWEEP-" + shortId())
                .durationDays(30)
                .createdByUserId(admin.getId())
                .build());
        em.flush();

        // (a) ACTIVE, expires in the past - should flip to EXPIRED.
        CouponGrant expired = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .expiresAt(OffsetDateTime.now().minusHours(1))
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());

        // (b) ACTIVE, expires in the future - should stay ACTIVE.
        CouponGrant fresh = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());

        // (c) ACTIVE, no expiry - should stay ACTIVE.
        CouponGrant infinite = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .expiresAt(null)
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());

        // (d) EXHAUSTED, past expiry - sweeper must NOT touch non-ACTIVE rows.
        CouponGrant exhausted = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .expiresAt(OffsetDateTime.now().minusHours(1))
                .state(CouponGrantState.EXHAUSTED)
                .source(CouponGrantSource.REDEMPTION)
                .build());

        // (e) REVOKED, past expiry - sweeper must NOT touch non-ACTIVE rows.
        CouponGrant revoked = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .expiresAt(OffsetDateTime.now().minusHours(1))
                .state(CouponGrantState.REVOKED)
                .source(CouponGrantSource.ADMIN_GRANT)
                .build());

        em.flush();

        int updated = grantRepo.markExpired(OffsetDateTime.now());

        // Flush + clear so the assertions read post-update state from the DB.
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(grantRepo.findById(expired.getId()))
                .get().extracting(CouponGrant::getState).isEqualTo(CouponGrantState.EXPIRED);
        assertThat(grantRepo.findById(fresh.getId()))
                .get().extracting(CouponGrant::getState).isEqualTo(CouponGrantState.ACTIVE);
        assertThat(grantRepo.findById(infinite.getId()))
                .get().extracting(CouponGrant::getState).isEqualTo(CouponGrantState.ACTIVE);
        assertThat(grantRepo.findById(exhausted.getId()))
                .get().extracting(CouponGrant::getState).isEqualTo(CouponGrantState.EXHAUSTED);
        assertThat(grantRepo.findById(revoked.getId()))
                .get().extracting(CouponGrant::getState).isEqualTo(CouponGrantState.REVOKED);

        // Idempotency: re-running mutates zero additional rows.
        int reRun = grantRepo.markExpired(OffsetDateTime.now());
        assertThat(reRun).isEqualTo(0);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
