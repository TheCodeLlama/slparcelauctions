package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.coupon.dto.CouponDiscountDto;
import com.slparcelauctions.backend.coupon.dto.CreateCouponRequest;
import com.slparcelauctions.backend.coupon.dto.PatchCouponRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Admin-CRUD integration coverage for {@link CouponService} (Plan Task 6
 * scope). Redemption, direct-grant, and signup-window backfill arrive
 * in Tasks 7 and 8; this suite stays focused on the create/find/list/
 * patch/archive surface.
 *
 * <p>Follows the {@code @SpringBootTest} + scheduler-mute pattern from
 * {@link CouponGrantSweeperTest}.
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
class CouponServiceAdminCrudTest {

    @Autowired CouponService service;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @Autowired UserRepository userRepo;

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
    void createCoupon_happyPath_singleDiscount_noAllowlist() {
        CreateCouponRequest req = createBuilder("HAPPY-" + shortId()).build();

        Coupon saved = service.createCoupon(req, admin.getId());
        em.flush();
        em.clear();

        Coupon reloaded = couponRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCode()).isEqualTo(req.code());
        assertThat(reloaded.getDurationDays()).isEqualTo(30);
        assertThat(reloaded.getMaxPerUser()).isEqualTo(1);
        assertThat(reloaded.getActive()).isTrue();
        assertThat(reloaded.getNotifyOnGrant()).isTrue();
        assertThat(reloaded.getCreatedByUserId()).isEqualTo(admin.getId());
        assertThat(reloaded.getDiscounts()).hasSize(1);
        CouponDiscount d = reloaded.getDiscounts().get(0);
        assertThat(d.getTarget()).isEqualTo(DiscountTarget.LISTING_FEE);
        assertThat(d.getOp()).isEqualTo(DiscountOp.PERCENT_OFF);
        assertThat(d.getValue()).isEqualByComparingTo("25.00");
        assertThat(d.getSortOrder()).isZero();
        assertThat(reloaded.getAllowedUsers()).isEmpty();
    }

    @Test
    void createCoupon_multiDiscount_withAllowlist_persistsBothUsers() {
        CreateCouponRequest req = createBuilder("BUNDLE-" + shortId())
                .discounts(List.of(
                        new CouponDiscountDto(DiscountTarget.LISTING_FEE,
                                DiscountOp.PERCENT_OFF, new BigDecimal("50.00"), null),
                        new CouponDiscountDto(DiscountTarget.COMMISSION_RATE,
                                DiscountOp.OVERRIDE, new BigDecimal("0.0500"), null)))
                .allowedUserPublicIds(
                        List.of(holderA.getPublicId(), holderB.getPublicId()))
                .build();

        Coupon saved = service.createCoupon(req, admin.getId());
        em.flush();
        em.clear();

        Coupon reloaded = couponRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDiscounts()).hasSize(2);
        assertThat(reloaded.getDiscounts())
                .extracting(CouponDiscount::getTarget)
                .containsExactlyInAnyOrder(
                        DiscountTarget.LISTING_FEE, DiscountTarget.COMMISSION_RATE);
        assertThat(reloaded.getDiscounts())
                .extracting(CouponDiscount::getSortOrder)
                .containsExactlyInAnyOrder(0, 1);
        assertThat(reloaded.getAllowedUsers())
                .extracting(User::getPublicId)
                .containsExactlyInAnyOrder(holderA.getPublicId(), holderB.getPublicId());
    }

    @Test
    void createCoupon_missingBothLifetimeAxes_throwsLifetimeRequired() {
        CreateCouponRequest req = createBuilder("NOLIFE-" + shortId())
                .durationDays(null)
                .useCount(null)
                .build();
        assertThatThrownBy(() -> service.createCoupon(req, admin.getId()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.LIFETIME_REQUIRED));
    }

    @Test
    void createCoupon_partialSignupWindow_throwsSignupWindowPaired() {
        CreateCouponRequest req = createBuilder("HALFWIN-" + shortId())
                .signupWindowStart(LocalDate.now())
                .signupWindowEnd(null)
                .build();
        assertThatThrownBy(() -> service.createCoupon(req, admin.getId()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.SIGNUP_WINDOW_PAIRED));
    }

    @Test
    void createCoupon_duplicateCode_throwsImmutableField() {
        String code = "DUPE-" + shortId();
        service.createCoupon(createBuilder(code).build(), admin.getId());
        em.flush();
        assertThatThrownBy(() ->
                service.createCoupon(createBuilder(code).build(), admin.getId()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.IMMUTABLE_FIELD));
    }

    @Test
    void patch_rejectsLifetimeFields_whenGrantsExist() {
        Coupon c = service.createCoupon(createBuilder("LOCK-" + shortId()).build(), admin.getId());
        em.flush();
        seedGrant(c, holderA);
        em.flush();

        assertThatThrownBy(() -> service.patch(c.getPublicId(),
                patchBuilder().durationDays(60).build()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.IMMUTABLE_FIELD));

        assertThatThrownBy(() -> service.patch(c.getPublicId(),
                patchBuilder().useCount(5).build()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.IMMUTABLE_FIELD));

        assertThatThrownBy(() -> service.patch(c.getPublicId(),
                patchBuilder().maxPerUser(3).build()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.IMMUTABLE_FIELD));
    }

    @Test
    void patch_acceptsLifetimeFields_whenNoGrants() {
        Coupon c = service.createCoupon(createBuilder("OPEN-" + shortId()).build(), admin.getId());
        em.flush();

        PatchCouponRequest req = patchBuilder()
                .durationDays(60)
                .useCount(5)
                .maxPerUser(3)
                .build();
        Coupon patched = service.patch(c.getPublicId(), req);
        em.flush();
        em.clear();

        Coupon reloaded = couponRepo.findById(patched.getId()).orElseThrow();
        assertThat(reloaded.getDurationDays()).isEqualTo(60);
        assertThat(reloaded.getUseCount()).isEqualTo(5);
        assertThat(reloaded.getMaxPerUser()).isEqualTo(3);
    }

    @Test
    void patch_updatesAllowedUsers_whenProvided() {
        Coupon c = service.createCoupon(createBuilder("ALLOW-" + shortId()).build(), admin.getId());
        em.flush();

        PatchCouponRequest req = patchBuilder()
                .allowedUserPublicIds(List.of(holderA.getPublicId(), holderB.getPublicId()))
                .build();
        service.patch(c.getPublicId(), req);
        em.flush();
        em.clear();

        Coupon reloaded = couponRepo.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getAllowedUsers())
                .extracting(User::getPublicId)
                .containsExactlyInAnyOrder(holderA.getPublicId(), holderB.getPublicId());
    }

    @Test
    void archive_deletesCoupon_whenZeroGrants() {
        Coupon c = service.createCoupon(createBuilder("DELME-" + shortId()).build(), admin.getId());
        em.flush();
        UUID pid = c.getPublicId();

        service.archive(pid);
        em.flush();
        em.clear();

        assertThat(couponRepo.findByPublicId(pid)).isEmpty();
    }

    @Test
    void archive_softArchives_whenGrantsExist() {
        Coupon c = service.createCoupon(createBuilder("SOFT-" + shortId()).build(), admin.getId());
        em.flush();
        seedGrant(c, holderA);
        em.flush();
        UUID pid = c.getPublicId();
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);

        service.archive(pid);
        em.flush();
        em.clear();

        Coupon reloaded = couponRepo.findByPublicId(pid).orElseThrow();
        assertThat(reloaded.getActive()).isFalse();
        assertThat(reloaded.getRedeemableUntil()).isNotNull();
        assertThat(reloaded.getRedeemableUntil()).isAfterOrEqualTo(before);
    }

    @Test
    void findByPublicId_unknown_throwsUnknownCode() {
        assertThatThrownBy(() -> service.findByPublicId(UUID.randomUUID()))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CouponRedemptionError.UNKNOWN_CODE));
    }

    @Test
    void listAdmin_filtersByCodeQActiveAndTarget() {
        String tag = shortId();
        service.createCoupon(createBuilder("FOO-" + tag).build(), admin.getId());
        service.createCoupon(createBuilder("BAR-" + tag).active(false).build(), admin.getId());
        service.createCoupon(createBuilder("FOO-COMM-" + tag)
                .discounts(List.of(
                        new CouponDiscountDto(DiscountTarget.COMMISSION_RATE,
                                DiscountOp.OVERRIDE, new BigDecimal("0.0500"), null)))
                .build(), admin.getId());
        em.flush();

        Page<Coupon> byCode = service.listAdmin(
                "foo-", null, null, PageRequest.of(0, 50));
        assertThat(byCode.getContent())
                .extracting(Coupon::getCode)
                .allMatch(code -> code.toLowerCase().contains("foo-"));
        assertThat(byCode.getContent())
                .extracting(Coupon::getCode)
                .contains("FOO-" + tag, "FOO-COMM-" + tag);

        Page<Coupon> activeOnly = service.listAdmin(
                tag, true, null, PageRequest.of(0, 50));
        assertThat(activeOnly.getContent())
                .extracting(Coupon::getActive)
                .allMatch(Boolean.TRUE::equals);

        Page<Coupon> commissionOnly = service.listAdmin(
                tag, null, DiscountTarget.COMMISSION_RATE, PageRequest.of(0, 50));
        assertThat(commissionOnly.getContent())
                .extracting(Coupon::getCode)
                .containsExactly("FOO-COMM-" + tag);
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

    /** Mutable builder for {@link CreateCouponRequest} with a sensible baseline. */
    private static CreateRequestBuilder createBuilder(String code) {
        CreateRequestBuilder b = new CreateRequestBuilder();
        b.code = code;
        b.description = "desc";
        b.durationDays = 30;
        b.discounts = List.of(new CouponDiscountDto(DiscountTarget.LISTING_FEE,
                DiscountOp.PERCENT_OFF, new BigDecimal("25.00"), null));
        return b;
    }

    private static PatchRequestBuilder patchBuilder() {
        return new PatchRequestBuilder();
    }

    private void seedGrant(Coupon coupon, User user) {
        Coupon attached = couponRepo.findById(coupon.getId()).orElseThrow();
        User attachedUser = userRepo.findById(user.getId()).orElseThrow();
        grantRepo.save(CouponGrant.builder()
                .coupon(attached)
                .user(attachedUser)
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.ADMIN_GRANT)
                .build());
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
        CreateRequestBuilder signupWindowStart(LocalDate v) { this.signupWindowStart = v; return this; }
        CreateRequestBuilder signupWindowEnd(LocalDate v) { this.signupWindowEnd = v; return this; }
        CreateRequestBuilder active(Boolean v) { this.active = v; return this; }
        CreateRequestBuilder discounts(List<CouponDiscountDto> v) { this.discounts = v; return this; }
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

    private static final class PatchRequestBuilder {
        String description;
        Boolean active;
        Boolean notifyOnGrant;
        OffsetDateTime redeemableUntil;
        Integer maxTotalRedemptions;
        List<UUID> allowedUserPublicIds;
        Integer durationDays;
        Integer useCount;
        Integer maxPerUser;

        PatchRequestBuilder durationDays(Integer v) { this.durationDays = v; return this; }
        PatchRequestBuilder useCount(Integer v) { this.useCount = v; return this; }
        PatchRequestBuilder maxPerUser(Integer v) { this.maxPerUser = v; return this; }
        PatchRequestBuilder allowedUserPublicIds(List<UUID> v) {
            this.allowedUserPublicIds = v;
            return this;
        }

        PatchCouponRequest build() {
            return new PatchCouponRequest(description, active, notifyOnGrant,
                    redeemableUntil, maxTotalRedemptions, allowedUserPublicIds,
                    durationDays, useCount, maxPerUser);
        }
    }
}
