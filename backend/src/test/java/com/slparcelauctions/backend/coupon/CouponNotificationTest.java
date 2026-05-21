package com.slparcelauctions.backend.coupon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.coupon.dto.CouponDiscountDto;
import com.slparcelauctions.backend.coupon.dto.CreateCouponRequest;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.UserService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserResponse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Plan Task 9 -- verifies that {@link CouponService#createGrant} fires
 * {@link NotificationPublisher#couponGranted} for every non-REDEMPTION grant
 * when the parent coupon's {@code notifyOnGrant} flag is on, and stays silent
 * otherwise.
 *
 * <p>{@link NotificationPublisher} is mocked so we can assert on call counts +
 * arguments without depending on the full notification persistence pipeline
 * (the publisher itself has its own integration test in
 * {@code NotificationPublisherImplTest}).
 *
 * <p>Mirrors the {@code @SpringBootTest} + scheduler-mute pattern from
 * {@link CouponServiceRedemptionTest} / {@link CouponSignupWindowTest}.
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
class CouponNotificationTest {

    @Autowired CouponService service;
    @Autowired UserService userService;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean
    NotificationPublisher notificationPublisher;

    @PersistenceContext EntityManager em;

    private User admin;
    private User holderA;
    private User holderB;

    @BeforeEach
    void seed() {
        admin = userRepo.save(makeUser("admin"));
        holderA = userRepo.save(makeUser("holder-a"));
        holderB = userRepo.save(makeUser("holder-b"));
        em.flush();
    }

    @Test
    void directGrant_firesCouponGrantedOncePerUser() {
        Coupon c = service.createCoupon(createBuilder("DIRECT-" + shortId()).build(), admin.getId());
        em.flush();

        service.directGrant(c.getPublicId(),
                List.of(holderA.getPublicId(), holderB.getPublicId()));
        em.flush();

        verify(notificationPublisher, times(1))
                .couponGranted(eq(holderA.getId()), eq(c.getPublicId()), eq(CouponGrantSource.ADMIN_GRANT));
        verify(notificationPublisher, times(1))
                .couponGranted(eq(holderB.getId()), eq(c.getPublicId()), eq(CouponGrantSource.ADMIN_GRANT));
    }

    @Test
    void redeem_doesNotFireCouponGranted() {
        Coupon c = service.createCoupon(createBuilder("SILENT-" + shortId()).build(), admin.getId());
        em.flush();

        service.redeem(holderA.getId(), c.getCode());
        em.flush();

        verify(notificationPublisher, never())
                .couponGranted(any(Long.class), any(UUID.class), any(CouponGrantSource.class));
    }

    @Test
    void directGrant_withNotifyOnGrantFalse_doesNotFire() {
        Coupon c = service.createCoupon(createBuilder("QUIET-" + shortId())
                .notifyOnGrant(false)
                .build(), admin.getId());
        em.flush();

        service.directGrant(c.getPublicId(), List.of(holderA.getPublicId()));
        em.flush();

        verify(notificationPublisher, never())
                .couponGranted(any(Long.class), any(UUID.class), any(CouponGrantSource.class));
    }

    @Test
    void signupWindowBackfill_firesCouponGranted() {
        // Coupon with a signup window that covers today; the admin row was just
        // created so it falls inside the window and gets backfilled.
        LocalDate today = LocalDate.now();
        Coupon c = service.createCoupon(createBuilder("BACKFILL-" + shortId())
                .signupWindowStart(today.minusDays(2))
                .signupWindowEnd(today.plusDays(2))
                .build(), admin.getId());
        em.flush();

        // The seed users (holderA, holderB, admin) were created moments ago so
        // they fall inside the window and get a grant via the backfill path
        // inside createCoupon.
        verify(notificationPublisher, times(1))
                .couponGranted(eq(admin.getId()), eq(c.getPublicId()), eq(CouponGrantSource.SIGNUP_WINDOW));
        verify(notificationPublisher, times(1))
                .couponGranted(eq(holderA.getId()), eq(c.getPublicId()), eq(CouponGrantSource.SIGNUP_WINDOW));
        verify(notificationPublisher, times(1))
                .couponGranted(eq(holderB.getId()), eq(c.getPublicId()), eq(CouponGrantSource.SIGNUP_WINDOW));
    }

    @Test
    void userCreateHook_firesCouponGranted() {
        LocalDate today = LocalDate.now();
        Coupon c = service.createCoupon(createBuilder("HOOK-" + shortId())
                .signupWindowStart(today.minusDays(2))
                .signupWindowEnd(today.plusDays(2))
                .build(), admin.getId());
        em.flush();

        UserResponse fresh = userService.createUser(new CreateUserRequest(
                "newbie-" + shortId(), "A-good-password-99"));
        em.flush();

        User reloaded = userRepo.findByPublicId(fresh.publicId()).orElseThrow();
        verify(notificationPublisher, times(1))
                .couponGranted(eq(reloaded.getId()), eq(c.getPublicId()), eq(CouponGrantSource.SIGNUP_WINDOW));
    }

    @Test
    void directGrant_idempotentSkip_doesNotRefire() {
        Coupon c = service.createCoupon(createBuilder("CEIL-" + shortId())
                .maxPerUser(1)
                .build(), admin.getId());
        em.flush();
        service.directGrant(c.getPublicId(), List.of(holderA.getPublicId()));
        em.flush();

        // Second call -- holderA already at ceiling, no new grant + no publish
        service.directGrant(c.getPublicId(), List.of(holderA.getPublicId()));
        em.flush();

        // Total grants for holderA stays at 1.
        org.assertj.core.api.Assertions.assertThat(
                grantRepo.countByCouponIdAndUserId(c.getId(), holderA.getId())).isEqualTo(1);
        // Publisher fired once total for holderA on this coupon.
        verify(notificationPublisher, times(1))
                .couponGranted(eq(holderA.getId()), eq(c.getPublicId()), eq(CouponGrantSource.ADMIN_GRANT));
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

        CreateRequestBuilder maxPerUser(Integer v) { this.maxPerUser = v; return this; }
        CreateRequestBuilder notifyOnGrant(Boolean v) { this.notifyOnGrant = v; return this; }
        CreateRequestBuilder signupWindowStart(LocalDate v) { this.signupWindowStart = v; return this; }
        CreateRequestBuilder signupWindowEnd(LocalDate v) { this.signupWindowEnd = v; return this; }

        CreateCouponRequest build() {
            return new CreateCouponRequest(code, description, durationDays, useCount,
                    redeemableUntil, maxTotalRedemptions, maxPerUser,
                    signupWindowStart, signupWindowEnd, active, notifyOnGrant,
                    discounts, allowedUserPublicIds);
        }
    }
}
