package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
 * Integration coverage for {@link CouponGrantSweeper#sweep()}. Drives the
 * sweeper directly (we don't wait for the cron) and asserts that only
 * ACTIVE grants past their {@code expiresAt} are transitioned to
 * {@link CouponGrantState#EXPIRED}, plus a second-run idempotency check.
 *
 * <p>Mirrors the {@code @SpringBootTest} + {@code @Transactional} +
 * scheduler-mute conventions from {@link CouponRepositoryIntegrationTest}.
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
class CouponGrantSweeperTest {

    @Autowired CouponGrantSweeper sweeper;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;
    @Autowired UserRepository userRepo;

    @PersistenceContext EntityManager em;

    private User admin;
    private User holder;
    private Coupon coupon;

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

        coupon = couponRepo.save(Coupon.builder()
                .code("SWEEPER-" + shortId())
                .durationDays(30)
                .createdByUserId(admin.getId())
                .build());
        em.flush();
    }

    @Test
    void sweep_flipsOnlyExpiredActiveGrants_andIsIdempotent() {
        // (a) ACTIVE + past expiry: should flip.
        CouponGrant expired = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .expiresAt(OffsetDateTime.now().minusHours(1))
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());

        // (b) ACTIVE + future expiry: should stay ACTIVE.
        CouponGrant fresh = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());

        // (c) ACTIVE + null expiry: should stay ACTIVE.
        CouponGrant infinite = grantRepo.save(CouponGrant.builder()
                .coupon(coupon).user(holder)
                .expiresAt(null)
                .state(CouponGrantState.ACTIVE)
                .source(CouponGrantSource.REDEMPTION)
                .build());

        em.flush();

        sweeper.sweep();
        em.flush();
        em.clear();

        assertThat(grantRepo.findById(expired.getId()))
                .get().extracting(CouponGrant::getState)
                .isEqualTo(CouponGrantState.EXPIRED);
        assertThat(grantRepo.findById(fresh.getId()))
                .get().extracting(CouponGrant::getState)
                .isEqualTo(CouponGrantState.ACTIVE);
        assertThat(grantRepo.findById(infinite.getId()))
                .get().extracting(CouponGrant::getState)
                .isEqualTo(CouponGrantState.ACTIVE);

        // Idempotency: a second sweep observes zero ACTIVE-past-expiry rows.
        // Re-asserting the states after a second invocation is the cleanest
        // signal at the service layer (the repository row-count assertion is
        // already covered in CouponRepositoryIntegrationTest#markExpired_...).
        sweeper.sweep();
        em.flush();
        em.clear();

        assertThat(grantRepo.findById(expired.getId()))
                .get().extracting(CouponGrant::getState)
                .isEqualTo(CouponGrantState.EXPIRED);
        assertThat(grantRepo.findById(fresh.getId()))
                .get().extracting(CouponGrant::getState)
                .isEqualTo(CouponGrantState.ACTIVE);
        assertThat(grantRepo.findById(infinite.getId()))
                .get().extracting(CouponGrant::getState)
                .isEqualTo(CouponGrantState.ACTIVE);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
