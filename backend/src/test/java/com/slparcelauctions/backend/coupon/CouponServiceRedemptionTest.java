package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration coverage for the redemption / direct-grant / revoke
 * additions to {@link CouponService} (Plan Task 7). Exercises every
 * rejection axis from spec section 4 plus the admin direct-grant and
 * revoke flows.
 *
 * <p>Follows the {@code @SpringBootTest} + scheduler-mute pattern from
 * {@link CouponServiceAdminCrudTest}.
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
class CouponServiceRedemptionTest {

    @Autowired CouponService service;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @Autowired UserRepository userRepo;

    @PersistenceContext EntityManager em;

    private User admin;
    private User holderA;
    private User holderB;
    private User holderC;

    @BeforeEach
    void seed() {
        admin = userRepo.save(makeUser("admin"));
        holderA = userRepo.save(makeUser("holder-a"));
        holderB = userRepo.save(makeUser("holder-b"));
        holderC = userRepo.save(makeUser("holder-c"));
        em.flush();
    }

    @Test
    void redeem_happyPath_createsActiveGrantWithRedemptionSource() {
        Coupon c = service.createCoupon(createBuilder("HAPPY-" + shortId())
                .durationDays(30)
                .useCount(2)
                .build(), admin.getId());
        em.flush();
        OffsetDateTime before = OffsetDateTime.now();

        CouponGrant g = service.redeem(holderA.getId(), c.getCode());
        em.flush();
        em.clear();

        CouponGrant reloaded = grantRepo.findByPublicId(g.getPublicId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(CouponGrantState.ACTIVE);
        assertThat(reloaded.getSource()).isEqualTo(CouponGrantSource.REDEMPTION);
        assertThat(reloaded.getRemainingCount()).isEqualTo(2);
        assertThat(reloaded.getGrantedAt()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(reloaded.getExpiresAt()).isNotNull();
        // expiresAt = now + durationDays; allow a small clock skew.
        long days = ChronoUnit.DAYS.between(
                reloaded.getGrantedAt(), reloaded.getExpiresAt());
        assertThat(days).isBetween(29L, 30L);
    }

    @Test
    void redeem_caseInsensitiveCodeLookup() {
        Coupon c = service.createCoupon(createBuilder("MiXeD-" + shortId()).build(), admin.getId());
        em.flush();

        CouponGrant g = service.redeem(holderA.getId(), c.getCode().toLowerCase());

        assertThat(g.getCoupon().getId()).isEqualTo(c.getId());
    }

    @Test
    void redeem_unknownCode_throwsUnknownCode() {
        assertThatThrownBy(() -> service.redeem(holderA.getId(), "NOPE-" + shortId()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.UNKNOWN_CODE));
    }

    @Test
    void redeem_inactiveCoupon_throwsPaused() {
        Coupon c = service.createCoupon(createBuilder("PAUSED-" + shortId())
                .active(false).build(), admin.getId());
        em.flush();

        assertThatThrownBy(() -> service.redeem(holderA.getId(), c.getCode()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.PAUSED));
    }

    @Test
    void redeem_pastRedeemableUntil_throwsExpired() {
        Coupon c = service.createCoupon(createBuilder("EXPIRED-" + shortId())
                .redeemableUntil(OffsetDateTime.now().minusDays(1))
                .build(), admin.getId());
        em.flush();

        assertThatThrownBy(() -> service.redeem(holderA.getId(), c.getCode()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.EXPIRED));
    }

    @Test
    void redeem_maxTotalReached_throwsMaxReached() {
        Coupon c = service.createCoupon(createBuilder("CAPPED-" + shortId())
                .maxTotalRedemptions(1)
                .build(), admin.getId());
        em.flush();

        service.redeem(holderA.getId(), c.getCode());
        em.flush();

        assertThatThrownBy(() -> service.redeem(holderB.getId(), c.getCode()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.MAX_REACHED));
    }

    @Test
    void redeem_userNotInAllowlist_throwsNotEligible() {
        Coupon c = service.createCoupon(createBuilder("ALLOW-" + shortId())
                .allowedUserPublicIds(List.of(holderA.getPublicId()))
                .build(), admin.getId());
        em.flush();

        assertThatThrownBy(() -> service.redeem(holderB.getId(), c.getCode()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.NOT_ELIGIBLE));
    }

    @Test
    void redeem_userOnAllowlist_succeeds() {
        Coupon c = service.createCoupon(createBuilder("ALLOWED-" + shortId())
                .allowedUserPublicIds(List.of(holderA.getPublicId()))
                .build(), admin.getId());
        em.flush();

        CouponGrant g = service.redeem(holderA.getId(), c.getCode());

        assertThat(g.getUser().getId()).isEqualTo(holderA.getId());
    }

    @Test
    void redeem_sameUserSecondTime_throwsAlreadyRedeemed() {
        Coupon c = service.createCoupon(createBuilder("ONCE-" + shortId())
                .maxPerUser(1)
                .build(), admin.getId());
        em.flush();

        service.redeem(holderA.getId(), c.getCode());
        em.flush();

        assertThatThrownBy(() -> service.redeem(holderA.getId(), c.getCode()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.ALREADY_REDEEMED));
    }

    @Test
    void directGrant_happyPath_grantsAllUsers() {
        Coupon c = service.createCoupon(createBuilder("BATCH-" + shortId()).build(), admin.getId());
        em.flush();

        List<CouponGrant> grants = service.directGrant(c.getPublicId(),
                List.of(holderA.getPublicId(), holderB.getPublicId(), holderC.getPublicId()));
        em.flush();
        em.clear();

        assertThat(grants).hasSize(3);
        assertThat(grantRepo.countByCouponId(c.getId())).isEqualTo(3);
        assertThat(grants).allMatch(g -> g.getSource() == CouponGrantSource.ADMIN_GRANT);
        assertThat(grants).allMatch(g -> g.getState() == CouponGrantState.ACTIVE);
    }

    @Test
    void directGrant_skipsUserAtMaxPerUserCeiling() {
        Coupon c = service.createCoupon(createBuilder("IDEMP-" + shortId())
                .maxPerUser(1)
                .build(), admin.getId());
        em.flush();
        service.directGrant(c.getPublicId(), List.of(holderA.getPublicId()));
        em.flush();

        List<CouponGrant> second = service.directGrant(c.getPublicId(),
                List.of(holderA.getPublicId(), holderB.getPublicId()));
        em.flush();
        em.clear();

        // holderA already at ceiling, only holderB receives a new grant.
        assertThat(second).hasSize(1);
        assertThat(second.get(0).getUser().getId()).isEqualTo(holderB.getId());
        assertThat(grantRepo.countByCouponId(c.getId())).isEqualTo(2);
    }

    @Test
    void directGrant_unknownUser_throwsUnknownCode() {
        Coupon c = service.createCoupon(createBuilder("STRANGER-" + shortId()).build(), admin.getId());
        em.flush();

        assertThatThrownBy(() -> service.directGrant(c.getPublicId(),
                List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.UNKNOWN_CODE));
    }

    @Test
    void revokeGrant_transitionsStateToRevoked() {
        Coupon c = service.createCoupon(createBuilder("REVOKE-" + shortId()).build(), admin.getId());
        em.flush();
        CouponGrant g = service.redeem(holderA.getId(), c.getCode());
        em.flush();

        service.revokeGrant(c.getPublicId(), g.getPublicId());
        em.flush();
        em.clear();

        CouponGrant reloaded = grantRepo.findByPublicId(g.getPublicId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(CouponGrantState.REVOKED);
    }

    @Test
    void revokeGrant_mismatchedCoupon_throwsUnknownCode() {
        Coupon c1 = service.createCoupon(createBuilder("ONE-" + shortId()).build(), admin.getId());
        Coupon c2 = service.createCoupon(createBuilder("TWO-" + shortId()).build(), admin.getId());
        em.flush();
        CouponGrant g1 = service.redeem(holderA.getId(), c1.getCode());
        em.flush();

        assertThatThrownBy(() -> service.revokeGrant(c2.getPublicId(), g1.getPublicId()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.UNKNOWN_CODE));
    }

    @Test
    void revokeGrant_unknownGrant_throwsUnknownCode() {
        Coupon c = service.createCoupon(createBuilder("GHOST-" + shortId()).build(), admin.getId());
        em.flush();

        assertThatThrownBy(() -> service.revokeGrant(c.getPublicId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.UNKNOWN_CODE));
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

        CreateRequestBuilder durationDays(Integer v) { this.durationDays = v; return this; }
        CreateRequestBuilder useCount(Integer v) { this.useCount = v; return this; }
        CreateRequestBuilder redeemableUntil(OffsetDateTime v) {
            this.redeemableUntil = v;
            return this;
        }
        CreateRequestBuilder maxTotalRedemptions(Integer v) {
            this.maxTotalRedemptions = v;
            return this;
        }
        CreateRequestBuilder maxPerUser(Integer v) { this.maxPerUser = v; return this; }
        CreateRequestBuilder active(Boolean v) { this.active = v; return this; }
        CreateRequestBuilder allowedUserPublicIds(List<UUID> v) {
            this.allowedUserPublicIds = v;
            return this;
        }

        CreateCouponRequest build() {
            return new CreateCouponRequest(code, description, durationDays, useCount,
                    redeemableUntil, maxTotalRedemptions, maxPerUser,
                    signupWindowStart, signupWindowEnd, active, notifyOnGrant,
                    discounts, allowedUserPublicIds);
        }
    }
}
