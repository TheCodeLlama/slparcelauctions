# Coupon Codes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the coupon system from `docs/superpowers/specs/2026-05-20-coupon-codes-design.md` end-to-end — admins create promotional codes that discount listing fee and/or commission rate; users redeem via wallet or create-listing screen; signup-window auto-grant; full admin CRUD UI.

**Architecture:** New `coupon` package on the backend (entities, repos, service, resolver, sweeper, controllers). Two FKs stamped onto `auctions` so the activation hook knows which grants to decrement. Frontend gets one user-side card (wallet) + inline expander (create-listing) + three admin pages. One Flyway migration `V41__coupon_codes.sql`.

**Tech Stack:** Spring Boot 4 / Java 24 / Flyway / JPA / Lombok / Spring WebSocket (notification fan-out); Next.js 16 / React 19 / TypeScript 5 / Tailwind CSS 4; Vitest + RTL; Postman.

**Spec deviations:** Spec §11 names the migration `V37__coupon_codes.sql`. The actual next number on disk is **`V41`** (V40 = user_ledger_dormancy_auto_return). Plan uses V41 throughout.

---

## File Structure

**Backend — new files under `backend/src/main/java/com/slparcelauctions/backend/coupon/`:**

| File | Responsibility |
|---|---|
| `Coupon.java` | template entity, `@OneToMany` to `CouponDiscount`, `@ManyToMany` to User via `coupon_allowed_users`, `@OneToMany` to `CouponGrant` |
| `CouponDiscount.java` | bundle line entity (target, op, value, sort_order) |
| `CouponGrant.java` | per-user instance entity |
| `DiscountTarget.java` | enum: LISTING_FEE, COMMISSION_RATE |
| `DiscountOp.java` | enum: OVERRIDE, PERCENT_OFF, FLAT_OFF |
| `CouponGrantState.java` | enum: ACTIVE, EXHAUSTED, EXPIRED, REVOKED |
| `CouponGrantSource.java` | enum: REDEMPTION, ADMIN_GRANT, SIGNUP_WINDOW |
| `CouponRepository.java` | spring-data repo + `findByCodeIgnoreCase`, `findActiveSignupWindowMatching(LocalDate today)` |
| `CouponDiscountRepository.java` | spring-data repo |
| `CouponGrantRepository.java` | spring-data repo + `findActiveByUserId`, `findByCouponIdAndUserId`, `countByCouponId`, `countByCouponIdAndUserId`, `markExpired(Instant now)` |
| `CouponDiscountCalculator.java` | pure function `apply(target, op, value, defaultLindens, defaultRate)` |
| `CouponDiscountResolver.java` | service: load grants for user, pick best line per target, return `DiscountSnapshot` |
| `CouponDiscountResolver.DiscountSnapshot` | record (`listingFeeLindens`, `commissionRate`, `listingFeeCouponGrantId`, `commissionCouponGrantId`) |
| `CouponService.java` | CRUD + redemption + direct-grant + revoke + signup-window backfill + user-create hook |
| `CouponGrantSweeper.java` | scheduled bean, hourly sweep |
| `CouponRedemptionError.java` | enum (UNKNOWN_CODE, NOT_ELIGIBLE, ALREADY_REDEEMED, EXPIRED, PAUSED, MAX_REACHED, INACTIVE) |
| `CouponException.java` | runtime exception carrying `CouponRedemptionError` |
| `dto/CouponDto.java` | admin-facing record |
| `dto/CouponDiscountDto.java` | record |
| `dto/CouponGrantDto.java` | user + admin (same shape) record |
| `dto/CouponSummaryDto.java` | admin list view record (totals + status) |
| `dto/ProspectiveDiscountsDto.java` | record returned by `/me/listings/prospective-discounts` |
| `dto/CreateCouponRequest.java` | record |
| `dto/PatchCouponRequest.java` | record (only patchable fields) |
| `dto/RedeemCouponRequest.java` | record |
| `dto/DirectGrantRequest.java` | record (`List<UUID> userPublicIds`) |
| `MeCouponController.java` | `/api/v1/me/coupons/*` |
| `AdminCouponController.java` | `/api/v1/admin/coupons/*` (CRUD) |
| `AdminCouponGrantController.java` | `/api/v1/admin/coupons/{id}/grants/*` |
| `CouponExceptionHandler.java` | `@RestControllerAdvice` mapping `CouponException` to `ApiError` |

**Backend — modifies:**

| File | Change |
|---|---|
| `backend/src/main/resources/db/migration/V41__coupon_codes.sql` | new migration |
| `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java` | add `listingFeeCouponGrantId`, `commissionCouponGrantId` columns |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionCreationService.java` | call resolver, stamp grant IDs onto Auction |
| `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/ListingFeePaymentService.java` | L$0 auto-pay short-circuit |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionActivationService.java` (or equivalent) | consume grants at DRAFT_PAID→ACTIVE |
| `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java` | post-create hook |
| `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java` | `couponGranted(...)` method |
| `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java` | impl |
| `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java` | add `COUPON_GRANTED` |
| `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java` | data shape for coupon-granted |
| `backend/src/main/resources/templates/email/coupon-granted.html` | email template |
| `backend/src/main/resources/application.yml` | `slpa.coupons.sweeper-cron` |

**Frontend — new files:**

| File | Responsibility |
|---|---|
| `frontend/src/lib/api/coupons.ts` | typed API client (`fetchMyCoupons`, `redeemCoupon`, `fetchProspectiveDiscounts`, admin variants) |
| `frontend/src/lib/api/coupons.types.ts` | TS types matching DTOs |
| `frontend/src/hooks/useCoupons.ts` | react-query hook |
| `frontend/src/hooks/useRedeemCoupon.ts` | mutation hook |
| `frontend/src/hooks/useProspectiveDiscounts.ts` | react-query hook |
| `frontend/src/components/wallet/WalletCouponsCard.tsx` | redeem + active list + history collapse |
| `frontend/src/components/wallet/WalletCouponsCard.test.tsx` | RTL tests |
| `frontend/src/components/wallet/CouponGrantCard.tsx` | single grant row |
| `frontend/src/components/wallet/CouponGrantCard.test.tsx` | RTL tests |
| `frontend/src/components/listings/CreateListingCouponSummary.tsx` | inline badges + expander |
| `frontend/src/components/listings/CreateListingCouponSummary.test.tsx` | RTL tests |
| `frontend/src/app/admin/coupons/page.tsx` | list page |
| `frontend/src/app/admin/coupons/new/page.tsx` | create form |
| `frontend/src/app/admin/coupons/[publicId]/page.tsx` | detail page (tabs) |
| `frontend/src/components/admin/coupons/AdminCouponForm.tsx` | shared form (create + edit) |
| `frontend/src/components/admin/coupons/AdminCouponForm.test.tsx` | RTL tests |
| `frontend/src/components/admin/coupons/AdminCouponList.tsx` | table |
| `frontend/src/components/admin/coupons/AdminCouponList.test.tsx` | RTL tests |
| `frontend/src/components/admin/coupons/AdminCouponDetailOverview.tsx` | overview tab |
| `frontend/src/components/admin/coupons/AdminCouponDetailGrants.tsx` | grants tab + direct-grant modal |
| `frontend/src/components/admin/coupons/AdminCouponDetailEdit.tsx` | edit tab |

**Frontend — modifies:**

| File | Change |
|---|---|
| `frontend/src/components/wallet/WalletPanel.tsx` | render `<WalletCouponsCard />` between balance card and ledger |
| `frontend/src/app/listings/(verified)/create/page.tsx` | render `<CreateListingCouponSummary />` in summary block |
| `frontend/src/components/admin/AdminSidebar.tsx` (or wherever the sidebar lives) | add Coupons link |
| `frontend/src/app/dashboard/notifications/page.tsx` | add COUPON_GRANTED preference row |

---

## Task Execution Order

Tasks 1–11 are backend; 12–15 are integration into existing backend flows; 16–18 are backend controllers; 19 is frontend foundation; 20–24 are frontend pages; 25 is wrap-up. Sequential dependency graph — execute in order. Each subagent dispatch should run independently with the full text of its task; do not parallelize.

---

### Task 1: Flyway migration + four entities + repos + four enums

**Files:**
- Create: `backend/src/main/resources/db/migration/V41__coupon_codes.sql`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/Coupon.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponDiscount.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponGrant.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/DiscountTarget.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/DiscountOp.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponGrantState.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponGrantSource.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponDiscountRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponGrantRepository.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/CouponRepositoryIntegrationTest.java`

- [ ] **Step 1: Write migration `V41__coupon_codes.sql`**

Copy the exact SQL from spec §11 (the Migration plan section). Do **not** include a unique `(coupon_id, user_id)` index — the spec resolves to non-unique. Use `BIGSERIAL`, `TIMESTAMPTZ`, `gen_random_uuid()` per repo conventions. Add the two columns + indexes on `auctions` as the final statement block.

- [ ] **Step 2: Write enums (4 files)**

```java
// DiscountTarget.java
package com.slparcelauctions.backend.coupon;
public enum DiscountTarget { LISTING_FEE, COMMISSION_RATE }

// DiscountOp.java
package com.slparcelauctions.backend.coupon;
public enum DiscountOp { OVERRIDE, PERCENT_OFF, FLAT_OFF }

// CouponGrantState.java
package com.slparcelauctions.backend.coupon;
public enum CouponGrantState { ACTIVE, EXHAUSTED, EXPIRED, REVOKED }

// CouponGrantSource.java
package com.slparcelauctions.backend.coupon;
public enum CouponGrantSource { REDEMPTION, ADMIN_GRANT, SIGNUP_WINDOW }
```

- [ ] **Step 3: Write `Coupon.java`**

Extends `BaseMutableEntity`. Uses `@SuperBuilder`. `@OneToMany(mappedBy = "coupon", cascade = ALL, orphanRemoval = true)` for `discounts` and `grants`. `@ManyToMany(fetch = LAZY)` with `@JoinTable(name = "coupon_allowed_users")` for `allowedUsers`. All columns match the migration: `code`, `description`, `durationDays`, `useCount`, `redeemableUntil`, `maxTotalRedemptions`, `maxPerUser` (default 1), `signupWindowStart`, `signupWindowEnd`, `active` (default true), `notifyOnGrant` (default true), `createdByUserId`. No `@PrePersist` for `publicId` — `BaseMutableEntity` handles it.

- [ ] **Step 4: Write `CouponDiscount.java`**

Plain `@Entity` (no BaseEntity — child-of-coupon, no UUID needed). `@ManyToOne` to Coupon with `@JsonIgnore`. Columns: `target` (`@Enumerated(STRING)`), `op` (`@Enumerated(STRING)`), `value` (`BigDecimal`, scale 4), `sortOrder` (int, default 0). Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.

- [ ] **Step 5: Write `CouponGrant.java`**

Extends `BaseMutableEntity`. `@ManyToOne` to Coupon with `@JsonIgnore`, `@ManyToOne` to User with `@JsonIgnore`. Columns: `grantedAt`, `expiresAt` (nullable), `remainingCount` (nullable), `state` (`@Enumerated(STRING)`, default ACTIVE), `source` (`@Enumerated(STRING)`).

- [ ] **Step 6: Write three repos**

`CouponRepository extends JpaRepository<Coupon, Long>` with:

```java
Optional<Coupon> findByCodeIgnoreCase(String code);

@Query("SELECT c FROM Coupon c WHERE c.active = true " +
       "AND c.signupWindowStart IS NOT NULL " +
       "AND c.signupWindowStart <= :today " +
       "AND c.signupWindowEnd >= :today " +
       "AND (c.redeemableUntil IS NULL OR c.redeemableUntil > CURRENT_TIMESTAMP)")
List<Coupon> findActiveSignupWindowMatching(@Param("today") LocalDate today);
```

`CouponDiscountRepository extends JpaRepository<CouponDiscount, Long>` — empty body.

`CouponGrantRepository extends JpaRepository<CouponGrant, Long>` with:

```java
List<CouponGrant> findByUserIdAndStateOrderByGrantedAtAsc(long userId, CouponGrantState state);
List<CouponGrant> findByUserIdOrderByGrantedAtDesc(long userId);  // for history view
Optional<CouponGrant> findByCouponIdAndUserId(long couponId, long userId);
Optional<CouponGrant> findByPublicId(UUID publicId);
long countByCouponId(long couponId);
long countByCouponIdAndUserId(long couponId, long userId);
Page<CouponGrant> findByCouponId(long couponId, Pageable pageable);

@Modifying
@Query("UPDATE CouponGrant g SET g.state = 'EXPIRED', g.updatedAt = CURRENT_TIMESTAMP " +
       "WHERE g.state = 'ACTIVE' AND g.expiresAt IS NOT NULL AND g.expiresAt < :now")
int markExpired(@Param("now") OffsetDateTime now);
```

- [ ] **Step 7: Write `CouponRepositoryIntegrationTest`**

`@DataJpaTest` against H2 (or Testcontainers if repo uses Postgres-specifics). Cover:

```java
@Test
void findByCodeIgnoreCase_matchesRegardlessOfCase() { ... }
@Test
void findActiveSignupWindowMatching_returnsCouponsWithToday_inWindow() { ... }
@Test
void findActiveSignupWindowMatching_excludesPaused() { ... }
@Test
void markExpired_transitionsOnlyActiveGrantsPastExpiry() { ... }
```

- [ ] **Step 8: Run tests**

Run: `cd backend && ./mvnw test -Dtest=CouponRepositoryIntegrationTest`
Expected: 4 green.

- [ ] **Step 9: Boot the app and verify Flyway applies cleanly**

Run: `cd backend && ./mvnw -DskipTests spring-boot:run` (or `docker compose restart backend` if container running). Watch logs for `Successfully applied 1 migration to schema "public", now at version v41`. Then Ctrl-C.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/resources/db/migration/V41__coupon_codes.sql \
        backend/src/main/java/com/slparcelauctions/backend/coupon/ \
        backend/src/test/java/com/slparcelauctions/backend/coupon/CouponRepositoryIntegrationTest.java
git commit -m "feat(coupon): V41 migration + Coupon/CouponDiscount/CouponGrant entities + repos"
```

---

### Task 2: Add `listing_fee_coupon_grant_id` + `commission_coupon_grant_id` to Auction entity

The columns already exist in the V41 migration. This task only adds the JPA mappings on `Auction.java` so Hibernate validate passes.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`

- [ ] **Step 1: Add the two columns near the other listing-fee fields**

```java
@Column(name = "listing_fee_coupon_grant_id")
private Long listingFeeCouponGrantId;

@Column(name = "commission_coupon_grant_id")
private Long commissionCouponGrantId;
```

No `@ManyToOne` mapping — keep them as raw `Long` FKs to avoid pulling the grant into auction graphs.

- [ ] **Step 2: Run validate**

Run: `cd backend && ./mvnw test -Dtest=AuctionRepositoryIntegrationTest` (or any existing `@SpringBootTest` that boots the context).
Expected: green; Hibernate validate finds both columns.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java
git commit -m "feat(coupon): map listing_fee_coupon_grant_id + commission_coupon_grant_id on Auction"
```

---

### Task 3: CouponDiscountCalculator (pure function)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponDiscountCalculator.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/CouponDiscountCalculatorTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CouponDiscountCalculatorTest {

    @Test
    void listingFee_override_returnsValueAsLindens() {
        var result = CouponDiscountCalculator.applyListingFee(
                DiscountOp.OVERRIDE, new BigDecimal("0"), 100L);
        assertThat(result).isEqualTo(0L);
    }

    @Test
    void listingFee_percentOff_appliesAgainstDefault() {
        var result = CouponDiscountCalculator.applyListingFee(
                DiscountOp.PERCENT_OFF, new BigDecimal("50"), 100L);
        assertThat(result).isEqualTo(50L);
    }

    @Test
    void listingFee_flatOff_clampsToZero() {
        var result = CouponDiscountCalculator.applyListingFee(
                DiscountOp.FLAT_OFF, new BigDecimal("150"), 100L);
        assertThat(result).isEqualTo(0L);
    }

    @Test
    void commission_override_returnsValueAsRate() {
        var result = CouponDiscountCalculator.applyCommission(
                DiscountOp.OVERRIDE, new BigDecimal("3.0"), new BigDecimal("0.05"));
        assertThat(result).isEqualByComparingTo("0.03");
    }

    @Test
    void commission_percentOff_appliesAgainstDefault() {
        var result = CouponDiscountCalculator.applyCommission(
                DiscountOp.PERCENT_OFF, new BigDecimal("50"), new BigDecimal("0.05"));
        assertThat(result).isEqualByComparingTo("0.025");
    }

    @Test
    void commission_flatOff_subtractsPoints_clampsToZero() {
        var result = CouponDiscountCalculator.applyCommission(
                DiscountOp.FLAT_OFF, new BigDecimal("10.0"), new BigDecimal("0.05"));
        assertThat(result).isEqualByComparingTo("0.00");
    }
}
```

- [ ] **Step 2: Run, see fail**

Run: `cd backend && ./mvnw test -Dtest=CouponDiscountCalculatorTest`
Expected: compile error.

- [ ] **Step 3: Implement**

```java
package com.slparcelauctions.backend.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CouponDiscountCalculator {

    private CouponDiscountCalculator() {}

    /** Default fee in L$ (integer lindens); returns the resulting fee in lindens, never negative. */
    public static long applyListingFee(DiscountOp op, BigDecimal value, long defaultFee) {
        return switch (op) {
            case OVERRIDE -> Math.max(0L, value.longValueExact());
            case PERCENT_OFF -> {
                BigDecimal pct = value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                BigDecimal multiplier = BigDecimal.ONE.subtract(pct);
                BigDecimal result = new BigDecimal(defaultFee).multiply(multiplier);
                yield Math.max(0L, result.setScale(0, RoundingMode.HALF_UP).longValueExact());
            }
            case FLAT_OFF -> Math.max(0L, defaultFee - value.longValueExact());
        };
    }

    /** Returns the resulting commission rate (e.g. 0.05 = 5%), never negative. */
    public static BigDecimal applyCommission(DiscountOp op, BigDecimal value, BigDecimal defaultRate) {
        BigDecimal result = switch (op) {
            case OVERRIDE -> value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
            case PERCENT_OFF -> {
                BigDecimal pct = value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                yield defaultRate.multiply(BigDecimal.ONE.subtract(pct));
            }
            case FLAT_OFF -> {
                BigDecimal pts = value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                yield defaultRate.subtract(pts);
            }
        };
        if (result.signum() < 0) result = BigDecimal.ZERO;
        return result.setScale(2, RoundingMode.HALF_UP);
    }
}
```

Notes on semantics: `OVERRIDE` for commission interprets `value` as a percent (3.0 = 3%, stored on `commissionRate` as 0.03). `OVERRIDE` for listing fee interprets `value` as lindens (50 = L$50). This matches the spec §2 interpretation table.

- [ ] **Step 4: Run, see green**

Run: `cd backend && ./mvnw test -Dtest=CouponDiscountCalculatorTest`
Expected: 6 green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/CouponDiscountCalculator.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/CouponDiscountCalculatorTest.java
git commit -m "feat(coupon): pure CouponDiscountCalculator + tests"
```

---

### Task 4: CouponDiscountResolver

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponDiscountResolver.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/CouponDiscountResolverTest.java`

- [ ] **Step 1: Write the resolver**

```java
package com.slparcelauctions.backend.coupon;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponDiscountResolver {

    private final CouponGrantRepository grantRepository;

    @Value("${slpa.listing-fee.amount-lindens:100}")
    private long defaultFeeLindens;

    @Value("${slpa.commission.default-rate:0.05}")
    private BigDecimal defaultCommissionRate;

    public record DiscountSnapshot(
            long listingFeeLindens,
            BigDecimal commissionRate,
            Long listingFeeCouponGrantId,
            Long commissionCouponGrantId
    ) {}

    public DiscountSnapshot resolve(long userId) {
        List<CouponGrant> active = grantRepository.findByUserIdAndStateOrderByGrantedAtAsc(
                userId, CouponGrantState.ACTIVE);
        OffsetDateTime now = OffsetDateTime.now();
        List<CouponGrant> usable = active.stream()
                .filter(g -> g.getExpiresAt() == null || g.getExpiresAt().isAfter(now))
                .filter(g -> g.getRemainingCount() == null || g.getRemainingCount() > 0)
                .toList();

        ResolvedLine listingFee = pickBest(usable, DiscountTarget.LISTING_FEE,
                defaultFeeLindens, defaultCommissionRate);
        ResolvedLine commission = pickBest(usable, DiscountTarget.COMMISSION_RATE,
                defaultFeeLindens, defaultCommissionRate);

        return new DiscountSnapshot(
                listingFee != null ? listingFee.feeLindens : defaultFeeLindens,
                commission != null ? commission.commissionRate : defaultCommissionRate,
                listingFee != null ? listingFee.grantId : null,
                commission != null ? commission.grantId : null
        );
    }

    private record ResolvedLine(Long grantId, long feeLindens, BigDecimal commissionRate,
                                  boolean noUseCount, OffsetDateTime grantedAt) {}

    private ResolvedLine pickBest(List<CouponGrant> grants, DiscountTarget target,
                                   long defaultFee, BigDecimal defaultRate) {
        ResolvedLine best = null;
        for (CouponGrant g : grants) {
            for (CouponDiscount d : g.getCoupon().getDiscounts()) {
                if (d.getTarget() != target) continue;
                ResolvedLine candidate;
                if (target == DiscountTarget.LISTING_FEE) {
                    long fee = CouponDiscountCalculator.applyListingFee(d.getOp(), d.getValue(), defaultFee);
                    candidate = new ResolvedLine(g.getId(), fee, defaultRate,
                            g.getRemainingCount() == null, g.getGrantedAt());
                } else {
                    BigDecimal rate = CouponDiscountCalculator.applyCommission(d.getOp(), d.getValue(), defaultRate);
                    candidate = new ResolvedLine(g.getId(), defaultFee, rate,
                            g.getRemainingCount() == null, g.getGrantedAt());
                }
                if (isBetter(candidate, best, target)) best = candidate;
            }
        }
        return best;
    }

    private boolean isBetter(ResolvedLine candidate, ResolvedLine current, DiscountTarget target) {
        if (current == null) return true;
        int cmp = target == DiscountTarget.LISTING_FEE
                ? Long.compare(candidate.feeLindens, current.feeLindens)
                : candidate.commissionRate.compareTo(current.commissionRate);
        if (cmp != 0) return cmp < 0;
        // Tiebreak 1: no-count wins
        if (candidate.noUseCount != current.noUseCount) return candidate.noUseCount;
        // Tiebreak 2: FIFO (earlier granted_at wins)
        return candidate.grantedAt.isBefore(current.grantedAt);
    }
}
```

- [ ] **Step 2: Write `CouponDiscountResolverTest`**

```java
package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CouponDiscountResolverTest {

    private CouponGrantRepository repo;
    private CouponDiscountResolver resolver;

    @BeforeEach
    void setUp() {
        repo = mock(CouponGrantRepository.class);
        resolver = new CouponDiscountResolver(repo);
        ReflectionTestUtils.setField(resolver, "defaultFeeLindens", 100L);
        ReflectionTestUtils.setField(resolver, "defaultCommissionRate", new BigDecimal("0.05"));
    }

    @Test
    void noGrants_returnsDefaults() {
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), eq(CouponGrantState.ACTIVE)))
                .thenReturn(List.of());
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeLindens()).isEqualTo(100L);
        assertThat(snap.commissionRate()).isEqualByComparingTo("0.05");
        assertThat(snap.listingFeeCouponGrantId()).isNull();
        assertThat(snap.commissionCouponGrantId()).isNull();
    }

    @Test
    void singleGrant_bothTargets_stampsSameGrantId() {
        CouponGrant g = grantWithBundle(42L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"),
                new BundleLine(DiscountTarget.COMMISSION_RATE, DiscountOp.OVERRIDE, "3.0"));
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), any())).thenReturn(List.of(g));
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeLindens()).isZero();
        assertThat(snap.commissionRate()).isEqualByComparingTo("0.03");
        assertThat(snap.listingFeeCouponGrantId()).isEqualTo(42L);
        assertThat(snap.commissionCouponGrantId()).isEqualTo(42L);
    }

    @Test
    void tiebreak_noCountPreferred() {
        CouponGrant withCount = grantWithBundle(1L, false,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        withCount.setGrantedAt(OffsetDateTime.now().minusDays(10));
        CouponGrant withoutCount = grantWithBundle(2L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        withoutCount.setGrantedAt(OffsetDateTime.now().minusDays(1));
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), any()))
                .thenReturn(List.of(withCount, withoutCount));
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeCouponGrantId()).isEqualTo(2L);
    }

    @Test
    void tiebreak_fifoByGrantedAt() {
        CouponGrant older = grantWithBundle(1L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        older.setGrantedAt(OffsetDateTime.now().minusDays(10));
        CouponGrant newer = grantWithBundle(2L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        newer.setGrantedAt(OffsetDateTime.now().minusDays(1));
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), any()))
                .thenReturn(List.of(older, newer));
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeCouponGrantId()).isEqualTo(1L);
    }

    @Test
    void expiredByClock_butActiveByDb_isIgnored() {
        CouponGrant stale = grantWithBundle(99L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        stale.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), any())).thenReturn(List.of(stale));
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeCouponGrantId()).isNull();
    }

    record BundleLine(DiscountTarget target, DiscountOp op, String value) {}

    private CouponGrant grantWithBundle(long grantId, boolean noCount, BundleLine... lines) {
        Coupon c = new Coupon();
        c.setId(100L + grantId);
        List<CouponDiscount> ds = java.util.Arrays.stream(lines).map(l -> CouponDiscount.builder()
                .coupon(c).target(l.target()).op(l.op()).value(new BigDecimal(l.value()))
                .build()).toList();
        c.setDiscounts(ds);
        CouponGrant g = CouponGrant.builder()
                .coupon(c)
                .state(CouponGrantState.ACTIVE)
                .grantedAt(OffsetDateTime.now())
                .remainingCount(noCount ? null : 1)
                .build();
        g.setId(grantId);
        return g;
    }
}
```

- [ ] **Step 3: Run, see green**

Run: `cd backend && ./mvnw test -Dtest=CouponDiscountResolverTest`
Expected: 5 green.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/CouponDiscountResolver.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/CouponDiscountResolverTest.java
git commit -m "feat(coupon): CouponDiscountResolver with deterministic tiebreaks"
```

---

### Task 5: CouponGrantSweeper (hourly EXPIRED-state job)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponGrantSweeper.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/CouponGrantSweeperTest.java`
- Modify: `backend/src/main/resources/application.yml` — add `slpa.coupons.sweeper-cron: "0 0 * * * *"`

- [ ] **Step 1: Implement sweeper**

```java
package com.slparcelauctions.backend.coupon;

import java.time.OffsetDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class CouponGrantSweeper {

    private final CouponGrantRepository repository;

    @Scheduled(cron = "${slpa.coupons.sweeper-cron:0 0 * * * *}", zone = "UTC")
    @Transactional
    public void sweep() {
        int count = repository.markExpired(OffsetDateTime.now());
        if (count > 0) log.info("CouponGrantSweeper transitioned {} grants to EXPIRED", count);
    }
}
```

- [ ] **Step 2: Add application.yml key**

Append under `slpa:`:

```yaml
  coupons:
    sweeper-cron: "0 0 * * * *"   # hourly, UTC
```

- [ ] **Step 3: Write `CouponGrantSweeperTest`**

`@DataJpaTest` (or `@SpringBootTest`) with two grants — one expired, one not — assert only expired transitions. Idempotency test: run twice, second run returns 0 changes.

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=CouponGrantSweeperTest`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/CouponGrantSweeper.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/CouponGrantSweeperTest.java \
        backend/src/main/resources/application.yml
git commit -m "feat(coupon): hourly grant sweeper marks expired grants"
```

---

### Task 6: CouponService — admin CRUD core

Implements: `createCoupon(CreateCouponRequest, adminUserId)`, `findByPublicId(UUID)`, `listAdmin(filters, pageable)`, `patch(UUID, PatchCouponRequest)`, `archive(UUID)`. **Does not** include redemption, direct-grant, signup-window backfill, or revoke — those land in Task 7 and 8.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponRedemptionError.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/CreateCouponRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/PatchCouponRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/CouponDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/CouponDiscountDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/CouponSummaryDto.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/CouponServiceAdminCrudTest.java`

- [ ] **Step 1: Write `CouponRedemptionError` enum**

```java
package com.slparcelauctions.backend.coupon;

public enum CouponRedemptionError {
    UNKNOWN_CODE, NOT_ELIGIBLE, ALREADY_REDEEMED, EXPIRED, PAUSED, MAX_REACHED, INACTIVE,
    IMMUTABLE_FIELD, LIFETIME_REQUIRED, SIGNUP_WINDOW_PAIRED
}
```

- [ ] **Step 2: Write `CouponException`**

```java
package com.slparcelauctions.backend.coupon;

import lombok.Getter;

@Getter
public class CouponException extends RuntimeException {
    private final CouponRedemptionError code;
    public CouponException(CouponRedemptionError code) { super(code.name()); this.code = code; }
    public CouponException(CouponRedemptionError code, String detail) { super(detail); this.code = code; }
}
```

- [ ] **Step 3: Write DTOs**

```java
// CreateCouponRequest.java
public record CreateCouponRequest(
        @NotBlank @Size(max=64) String code,
        String description,
        Integer durationDays,
        Integer useCount,
        OffsetDateTime redeemableUntil,
        Integer maxTotalRedemptions,
        @Min(1) Integer maxPerUser,
        LocalDate signupWindowStart,
        LocalDate signupWindowEnd,
        Boolean active,
        Boolean notifyOnGrant,
        @NotEmpty List<CouponDiscountDto> discounts,
        List<UUID> allowedUserPublicIds
) {}

// PatchCouponRequest.java -- all fields nullable
public record PatchCouponRequest(
        String description,
        Boolean active,
        Boolean notifyOnGrant,
        OffsetDateTime redeemableUntil,
        Integer maxTotalRedemptions,
        List<UUID> allowedUserPublicIds,
        // These three are accepted but rejected with IMMUTABLE_FIELD when totalGrants > 0:
        Integer durationDays,
        Integer useCount,
        Integer maxPerUser
) {}

// CouponDto.java -- admin-facing
public record CouponDto(
        UUID publicId, String code, String description,
        Integer durationDays, Integer useCount,
        OffsetDateTime redeemableUntil, Integer maxTotalRedemptions, Integer maxPerUser,
        LocalDate signupWindowStart, LocalDate signupWindowEnd,
        boolean active, boolean notifyOnGrant,
        List<CouponDiscountDto> discounts,
        List<UUID> allowedUserPublicIds,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
) {}

// CouponDiscountDto.java
public record CouponDiscountDto(DiscountTarget target, DiscountOp op, BigDecimal value, Integer sortOrder) {}

// CouponSummaryDto.java -- admin list row
public record CouponSummaryDto(
        UUID publicId, String code, String description,
        boolean active, OffsetDateTime redeemableUntil,
        List<CouponDiscountDto> discounts,
        long totalGrants, long activeGrants, Integer maxTotalRedemptions
) {}
```

- [ ] **Step 4: Implement `CouponService` (admin CRUD core only)**

```java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CouponService {

    private final CouponRepository couponRepo;
    private final CouponGrantRepository grantRepo;
    private final UserRepository userRepo;

    public Coupon createCoupon(CreateCouponRequest req, long adminUserId) {
        validateLifetime(req.durationDays(), req.useCount());
        validateSignupWindowPaired(req.signupWindowStart(), req.signupWindowEnd());
        if (couponRepo.findByCodeIgnoreCase(req.code()).isPresent())
            throw new CouponException(CouponRedemptionError.IMMUTABLE_FIELD, "code already exists");
        Coupon c = Coupon.builder()
                .code(req.code())
                .description(req.description())
                .durationDays(req.durationDays())
                .useCount(req.useCount())
                .redeemableUntil(req.redeemableUntil())
                .maxTotalRedemptions(req.maxTotalRedemptions())
                .maxPerUser(req.maxPerUser() != null ? req.maxPerUser() : 1)
                .signupWindowStart(req.signupWindowStart())
                .signupWindowEnd(req.signupWindowEnd())
                .active(req.active() == null ? true : req.active())
                .notifyOnGrant(req.notifyOnGrant() == null ? true : req.notifyOnGrant())
                .createdByUserId(adminUserId)
                .build();
        // discounts
        int order = 0;
        List<CouponDiscount> discounts = new ArrayList<>();
        for (CouponDiscountDto d : req.discounts()) {
            discounts.add(CouponDiscount.builder()
                    .coupon(c).target(d.target()).op(d.op()).value(d.value()).sortOrder(order++)
                    .build());
        }
        c.setDiscounts(discounts);
        // allowed users
        if (req.allowedUserPublicIds() != null && !req.allowedUserPublicIds().isEmpty()) {
            Set<User> users = req.allowedUserPublicIds().stream()
                    .map(pid -> userRepo.findByPublicId(pid).orElseThrow())
                    .collect(Collectors.toSet());
            c.setAllowedUsers(users);
        }
        return couponRepo.save(c);
    }

    public Coupon findByPublicId(UUID publicId) {
        return couponRepo.findByPublicId(publicId)
                .orElseThrow(() -> new CouponException(CouponRedemptionError.UNKNOWN_CODE));
    }

    public Page<Coupon> listAdmin(String q, Boolean active, DiscountTarget target, Pageable pageable) {
        // Build Specification: code ILIKE q if set; active if set; any discount.target = target if set
        Specification<Coupon> spec = Specification.where(null);
        if (q != null && !q.isBlank()) spec = spec.and((root, cq, cb) ->
                cb.like(cb.lower(root.get("code")), "%" + q.toLowerCase() + "%"));
        if (active != null) spec = spec.and((root, cq, cb) -> cb.equal(root.get("active"), active));
        if (target != null) spec = spec.and((root, cq, cb) -> {
            Join<Coupon, CouponDiscount> j = root.join("discounts");
            cq.distinct(true);
            return cb.equal(j.get("target"), target);
        });
        return couponRepo.findAll(spec, pageable);  // requires Coupon repo to extend JpaSpecificationExecutor
    }

    public Coupon patch(UUID publicId, PatchCouponRequest req) {
        Coupon c = findByPublicId(publicId);
        long totalGrants = grantRepo.countByCouponId(c.getId());
        if (totalGrants > 0) {
            if (req.durationDays() != null) throw new CouponException(CouponRedemptionError.IMMUTABLE_FIELD, "durationDays");
            if (req.useCount() != null) throw new CouponException(CouponRedemptionError.IMMUTABLE_FIELD, "useCount");
            if (req.maxPerUser() != null) throw new CouponException(CouponRedemptionError.IMMUTABLE_FIELD, "maxPerUser");
        } else {
            if (req.durationDays() != null) c.setDurationDays(req.durationDays());
            if (req.useCount() != null) c.setUseCount(req.useCount());
            if (req.maxPerUser() != null) c.setMaxPerUser(req.maxPerUser());
        }
        if (req.description() != null) c.setDescription(req.description());
        if (req.active() != null) c.setActive(req.active());
        if (req.notifyOnGrant() != null) c.setNotifyOnGrant(req.notifyOnGrant());
        if (req.redeemableUntil() != null) c.setRedeemableUntil(req.redeemableUntil());
        if (req.maxTotalRedemptions() != null) c.setMaxTotalRedemptions(req.maxTotalRedemptions());
        if (req.allowedUserPublicIds() != null) {
            Set<User> users = req.allowedUserPublicIds().stream()
                    .map(pid -> userRepo.findByPublicId(pid).orElseThrow())
                    .collect(Collectors.toSet());
            c.setAllowedUsers(users);
        }
        return c;
    }

    public void archive(UUID publicId) {
        Coupon c = findByPublicId(publicId);
        long totalGrants = grantRepo.countByCouponId(c.getId());
        if (totalGrants > 0) {
            c.setActive(false);
            c.setRedeemableUntil(OffsetDateTime.now());
        } else {
            couponRepo.delete(c);
        }
    }

    private void validateLifetime(Integer durationDays, Integer useCount) {
        if (durationDays == null && useCount == null)
            throw new CouponException(CouponRedemptionError.LIFETIME_REQUIRED);
    }
    private void validateSignupWindowPaired(LocalDate start, LocalDate end) {
        if ((start == null) != (end == null))
            throw new CouponException(CouponRedemptionError.SIGNUP_WINDOW_PAIRED);
    }
}
```

Note: `CouponRepository` must `extends JpaRepository<Coupon, Long>, JpaSpecificationExecutor<Coupon>`. Update its declaration accordingly (a one-line change).

- [ ] **Step 5: Write `CouponServiceAdminCrudTest`**

`@SpringBootTest` + `@AutoConfigureMockMvc`. Cover:
- `createCoupon` success with single + multi-discount, with + without allowlist
- `createCoupon` rejects missing lifetime (LIFETIME_REQUIRED)
- `createCoupon` rejects unpaired signup window (SIGNUP_WINDOW_PAIRED)
- `createCoupon` rejects duplicate code
- `patch` rejects durationDays/useCount/maxPerUser when totalGrants > 0
- `patch` accepts all those when totalGrants = 0
- `archive` deletes when 0 grants; soft-archives (sets `active=false`, `redeemableUntil=now`) when >0

- [ ] **Step 6: Run tests**

Run: `cd backend && ./mvnw test -Dtest=CouponServiceAdminCrudTest`
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/ \
        backend/src/test/java/com/slparcelauctions/backend/coupon/CouponServiceAdminCrudTest.java
git commit -m "feat(coupon): CouponService admin CRUD core + DTOs + exception"
```

---

### Task 7: CouponService — redemption + direct-grant + revoke

Extends `CouponService` from Task 6 with:
- `redeem(long userId, String code)` — the user-typed flow from spec §4
- `directGrant(UUID couponPublicId, List<UUID> userPublicIds, long adminUserId)` — admin grants without code redemption
- `revokeGrant(UUID couponPublicId, UUID grantPublicId, long adminUserId)` — admin revokes
- `createGrant(Coupon, User, CouponGrantSource)` — shared internal helper (computes `expiresAt`, `remainingCount`, persists, returns grant)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/DirectGrantRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/RedeemCouponRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/CouponGrantDto.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/CouponServiceRedemptionTest.java`

- [ ] **Step 1: Add the three DTOs**

```java
public record DirectGrantRequest(@NotEmpty List<UUID> userPublicIds) {}
public record RedeemCouponRequest(@NotBlank String code) {}
public record CouponGrantDto(
        UUID publicId, UUID couponPublicId, String code,
        OffsetDateTime grantedAt, OffsetDateTime expiresAt, Integer remainingCount,
        CouponGrantState state, CouponGrantSource source,
        List<CouponDiscountDto> discounts
) {}
```

- [ ] **Step 2: Add `createGrant` internal helper to `CouponService`**

```java
private CouponGrant createGrant(Coupon c, User u, CouponGrantSource source) {
    OffsetDateTime now = OffsetDateTime.now();
    OffsetDateTime expires = c.getDurationDays() != null
            ? now.plusDays(c.getDurationDays())
            : null;
    CouponGrant g = CouponGrant.builder()
            .coupon(c).user(u)
            .grantedAt(now)
            .expiresAt(expires)
            .remainingCount(c.getUseCount())
            .state(CouponGrantState.ACTIVE)
            .source(source)
            .build();
    return grantRepo.save(g);
}
```

- [ ] **Step 3: Add `redeem`**

```java
public CouponGrant redeem(long userId, String code) {
    Coupon c = couponRepo.findByCodeIgnoreCase(code)
            .orElseThrow(() -> new CouponException(CouponRedemptionError.UNKNOWN_CODE));
    if (!c.isActive()) throw new CouponException(CouponRedemptionError.PAUSED);
    if (c.getRedeemableUntil() != null && c.getRedeemableUntil().isBefore(OffsetDateTime.now()))
        throw new CouponException(CouponRedemptionError.EXPIRED);
    if (c.getMaxTotalRedemptions() != null
            && grantRepo.countByCouponId(c.getId()) >= c.getMaxTotalRedemptions())
        throw new CouponException(CouponRedemptionError.MAX_REACHED);
    if (c.getAllowedUsers() != null && !c.getAllowedUsers().isEmpty()
            && c.getAllowedUsers().stream().noneMatch(u -> u.getId().equals(userId)))
        throw new CouponException(CouponRedemptionError.NOT_ELIGIBLE);
    if (grantRepo.countByCouponIdAndUserId(c.getId(), userId) >= c.getMaxPerUser())
        throw new CouponException(CouponRedemptionError.ALREADY_REDEEMED);
    User u = userRepo.findById(userId).orElseThrow();
    return createGrant(c, u, CouponGrantSource.REDEMPTION);
}
```

- [ ] **Step 4: Add `directGrant`**

```java
public List<CouponGrant> directGrant(UUID couponPublicId, List<UUID> userPublicIds) {
    Coupon c = findByPublicId(couponPublicId);
    List<CouponGrant> created = new ArrayList<>();
    for (UUID uid : userPublicIds) {
        User u = userRepo.findByPublicId(uid).orElseThrow();
        if (grantRepo.countByCouponIdAndUserId(c.getId(), u.getId()) >= c.getMaxPerUser())
            continue;  // skip silently — admin direct-grant is idempotent
        created.add(createGrant(c, u, CouponGrantSource.ADMIN_GRANT));
    }
    return created;
}
```

- [ ] **Step 5: Add `revokeGrant`**

```java
public CouponGrant revokeGrant(UUID couponPublicId, UUID grantPublicId) {
    Coupon c = findByPublicId(couponPublicId);
    CouponGrant g = grantRepo.findByPublicId(grantPublicId)
            .orElseThrow(() -> new CouponException(CouponRedemptionError.UNKNOWN_CODE));
    if (!g.getCoupon().getId().equals(c.getId()))
        throw new CouponException(CouponRedemptionError.UNKNOWN_CODE);
    g.setState(CouponGrantState.REVOKED);
    return g;
}
```

- [ ] **Step 6: Write `CouponServiceRedemptionTest`**

`@SpringBootTest`. Cover every error in spec §4 (UNKNOWN_CODE, PAUSED, EXPIRED, MAX_REACHED, NOT_ELIGIBLE, ALREADY_REDEEMED) plus the happy path; direct-grant happy path; direct-grant on a user who already has max grants is skipped; revoke transitions state to REVOKED.

- [ ] **Step 7: Run tests**

Run: `cd backend && ./mvnw test -Dtest=CouponServiceRedemptionTest`

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/CouponService.java \
        backend/src/main/java/com/slparcelauctions/backend/coupon/dto/ \
        backend/src/test/java/com/slparcelauctions/backend/coupon/CouponServiceRedemptionTest.java
git commit -m "feat(coupon): redeem + direct-grant + revoke flows"
```

---

### Task 8: Signup-window backfill on coupon save + user-create hook

Two integration points share `createGrant`:
1. `createCoupon(...)` — if `signupWindowStart/End` are set, backfill matching existing users in the same transaction.
2. `UserService.create()` — post-commit hook fires `applySignupWindowCoupons(newUser)`.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/CouponSignupWindowTest.java`

- [ ] **Step 1: Add backfill to `createCoupon`**

At the end of `createCoupon`, before returning:

```java
if (c.getSignupWindowStart() != null) {
    List<User> matches = userRepo.findByCreatedAtDateBetween(
            c.getSignupWindowStart(), c.getSignupWindowEnd());
    for (User u : matches) {
        if (grantRepo.countByCouponIdAndUserId(c.getId(), u.getId()) == 0) {
            createGrant(c, u, CouponGrantSource.SIGNUP_WINDOW);
        }
    }
}
```

Add the matching query to `UserRepository`:

```java
@Query("SELECT u FROM User u WHERE CAST(u.createdAt AS LocalDate) BETWEEN :start AND :end")
List<User> findByCreatedAtDateBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
```

- [ ] **Step 2: Add `applySignupWindowCoupons` to `CouponService`**

```java
public void applySignupWindowCoupons(User newUser) {
    LocalDate today = LocalDate.now();
    List<Coupon> matches = couponRepo.findActiveSignupWindowMatching(today);
    for (Coupon c : matches) {
        if (grantRepo.countByCouponIdAndUserId(c.getId(), newUser.getId()) == 0) {
            createGrant(c, newUser, CouponGrantSource.SIGNUP_WINDOW);
        }
    }
}
```

- [ ] **Step 3: Wire into `UserService.create()`**

After the existing user save (find the `userRepository.save(user)` call). Inject `CouponService` as a new dependency. Call `couponService.applySignupWindowCoupons(savedUser)` immediately after save in the same transaction.

- [ ] **Step 4: Write `CouponSignupWindowTest`**

`@SpringBootTest`. Cover:
- Coupon saved with window matching 2 existing users: 2 grants created
- New user created within active window: gets grant
- New user created outside window: gets nothing
- Coupon saved with paused signup window (`active=false`): no backfill, new-user hook no-op

- [ ] **Step 5: Run tests**

Run: `cd backend && ./mvnw test -Dtest=CouponSignupWindowTest`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/CouponService.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserService.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/CouponSignupWindowTest.java
git commit -m "feat(coupon): signup-window backfill on save + user-create hook"
```

---

### Task 9: NotificationPublisher.couponGranted + COUPON_GRANTED category

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategoryCheckConstraintInitializer.java` (extend the enum's allowed values for the DB CHECK constraint)
- Create: `backend/src/main/resources/templates/email/coupon-granted.html`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponService.java` — call publisher inside `createGrant` when `source != REDEMPTION && coupon.notifyOnGrant`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/CouponNotificationTest.java`

- [ ] **Step 1: Add `COUPON_GRANTED` to `NotificationCategory` enum + update the CHECK constraint initializer**

- [ ] **Step 2: Add publisher method**

```java
// NotificationPublisher.java
void couponGranted(long userId, UUID couponPublicId, CouponGrantSource source);
```

- [ ] **Step 3: Implement in `NotificationPublisherImpl`**

The impl loads the coupon, builds the data blob (code, discount summary string, expires-at), fans out in-app/email/IM per existing pattern. The discount summary string is human-readable per type: `"Free listings for 30 days"`, `"3% commission for next 5 listings"`, etc. Compose it with a static helper `CouponDiscountSummary.describe(Coupon)`.

- [ ] **Step 4: Create `coupon-granted.html` email template**

Use the existing email-template patterns; subject "You received a coupon"; body shows code, discounts, expiry; CTA link to `/wallet#coupons`.

- [ ] **Step 5: Wire publisher into `CouponService.createGrant`**

```java
private CouponGrant createGrant(Coupon c, User u, CouponGrantSource source) {
    // ... existing builder + save ...
    CouponGrant saved = grantRepo.save(g);
    if (source != CouponGrantSource.REDEMPTION && c.isNotifyOnGrant()) {
        notificationPublisher.couponGranted(u.getId(), c.getPublicId(), source);
    }
    return saved;
}
```

Add `NotificationPublisher` to the service's constructor-injected dependencies.

- [ ] **Step 6: Write `CouponNotificationTest`**

Mock publisher. Assert:
- Direct-grant fires `couponGranted` exactly once
- Signup-window grant fires once
- Redemption does NOT fire
- Coupon with `notifyOnGrant=false` does NOT fire even for direct-grant

- [ ] **Step 7: Run tests**

Run: `cd backend && ./mvnw test -Dtest=CouponNotificationTest`

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/ \
        backend/src/main/resources/templates/email/coupon-granted.html \
        backend/src/main/java/com/slparcelauctions/backend/coupon/CouponService.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/CouponNotificationTest.java
git commit -m "feat(notify): couponGranted publisher method + email template"
```

---

### Task 10: AuctionCreationService + ListingFeePaymentService integration

Wire `CouponDiscountResolver` into listing creation. Snapshot fee + commission + grant IDs onto the Auction at create. Update `ListingFeePaymentService` to auto-pay L$0.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionCreationService.java` (or the equivalent `AuctionService.create()` if no dedicated creation service)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/ListingFeePaymentService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/AuctionCreationCouponSnapshotTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/ListingFeeAutoPayTest.java`

- [ ] **Step 1: Inject `CouponDiscountResolver` into the listing-creation service**

- [ ] **Step 2: At listing creation, call resolver and stamp**

```java
var snapshot = couponDiscountResolver.resolve(sellerUserId);
auction.setListingFeeAmt(snapshot.listingFeeLindens());
auction.setCommissionRate(snapshot.commissionRate());
auction.setListingFeeCouponGrantId(snapshot.listingFeeCouponGrantId());
auction.setCommissionCouponGrantId(snapshot.commissionCouponGrantId());
```

- [ ] **Step 3: Add L$0 auto-pay path to `ListingFeePaymentService`**

Add a new public method:

```java
@Transactional
public void autoPayIfFreeAfterCreation(Auction auction) {
    if (auction.getListingFeeAmt() != null && auction.getListingFeeAmt() == 0L
            && Boolean.FALSE.equals(auction.getListingFeePaid())
            && auction.getStatus() == AuctionStatus.DRAFT) {
        auction.setListingFeePaid(true);
        auction.setListingFeePaidAt(OffsetDateTime.now(clock));
        auction.setStatus(AuctionStatus.DRAFT_PAID);
        EscrowTransaction tx = EscrowTransaction.builder()
                .auctionId(auction.getId()).user(seller(auction))
                .type(EscrowTransactionType.LISTING_FEE_PAYMENT)
                .amountLindens(0L)
                .status(EscrowTransactionStatus.COMPLETED)
                .slTransactionKey(null)  // no SL txn for L$0
                .memo("listing fee waived via coupon grant id="
                        + auction.getListingFeeCouponGrantId())
                .build();
            escrowRepo.save(tx);
            auctionRepo.save(auction);
    }
}
```

Call it from the listing-creation service immediately after stamping the snapshot.

- [ ] **Step 4: Write `AuctionCreationCouponSnapshotTest`**

`@SpringBootTest`. User with an ACTIVE grant creates a listing. Assert: `listing_fee_amt`, `commission_rate`, `listing_fee_coupon_grant_id`, `commission_coupon_grant_id` are all set per the resolver snapshot.

- [ ] **Step 5: Write `ListingFeeAutoPayTest`**

Cover:
- L$0 grant → listing transitions DRAFT to DRAFT_PAID, `listingFeePaid=true`, `LISTING_FEE_PAYMENT` ledger row at amount=0
- Partial discount (e.g. L$50) → status stays DRAFT, terminal flow handles the rest

- [ ] **Step 6: Run tests**

Run: `cd backend && ./mvnw test -Dtest=AuctionCreationCouponSnapshotTest,ListingFeeAutoPayTest`

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/main/java/com/slparcelauctions/backend/escrow/payment/ListingFeePaymentService.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/AuctionCreationCouponSnapshotTest.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/ListingFeeAutoPayTest.java
git commit -m "feat(coupon): snapshot discounts onto Auction; L\$0 listing fee auto-pays"
```

---

### Task 11: AuctionActivationService — consume grants at DRAFT_PAID→ACTIVE

The transition DRAFT_PAID → ACTIVE (after verification) is the consumption point. Decrement `remaining_count` once per stamped grant on the auction. If both stamps reference the same grant, decrement only once. Cancellation before ACTIVE does NOT decrement (Q5 spec).

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionActivationService.java` (or wherever the ACTIVE transition lives — search for `setStatus(AuctionStatus.ACTIVE)`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/AuctionActivationCouponConsumptionTest.java`

- [ ] **Step 1: Locate the ACTIVE-transition method**

Run: `grep -rn "AuctionStatus.ACTIVE" backend/src/main/java/com/slparcelauctions/backend/auction/ | grep setStatus`

Confirm one or more sites where `setStatus(ACTIVE)` is called. Likely candidates: `AuctionActivationService.activate()` or `AuctionService.activate()`. Add the consumption hook there.

- [ ] **Step 2: Add the consumption logic**

```java
private void consumeCouponGrants(Auction a) {
    Set<Long> grantIds = new LinkedHashSet<>();
    if (a.getListingFeeCouponGrantId() != null) grantIds.add(a.getListingFeeCouponGrantId());
    if (a.getCommissionCouponGrantId() != null) grantIds.add(a.getCommissionCouponGrantId());
    for (Long id : grantIds) {
        CouponGrant g = couponGrantRepo.findById(id).orElse(null);
        if (g == null || g.getRemainingCount() == null) continue;
        int next = g.getRemainingCount() - 1;
        g.setRemainingCount(next);
        if (next <= 0) g.setState(CouponGrantState.EXHAUSTED);
    }
}
```

Call `consumeCouponGrants(auction)` immediately before/after `setStatus(ACTIVE)` within the same transaction.

Inject `CouponGrantRepository` into the activation service.

- [ ] **Step 3: Write `AuctionActivationCouponConsumptionTest`**

`@SpringBootTest`. Three scenarios:
- Single grant stamped on both targets → decrement once
- Two different grants stamped → both decrement once
- Cancellation in DRAFT_PAID → no decrement
- Grant with `remainingCount=1` decrements to 0 and transitions to EXHAUSTED

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=AuctionActivationCouponConsumptionTest`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/test/java/com/slparcelauctions/backend/coupon/AuctionActivationCouponConsumptionTest.java
git commit -m "feat(coupon): consume grants on listing activation (idempotent per grant)"
```

---

### Task 12: MeCouponController — user-facing endpoints

Implements `GET /api/v1/me/coupons`, `POST /api/v1/me/coupons/redeem`, `GET /api/v1/me/listings/prospective-discounts`.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/MeCouponController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/dto/ProspectiveDiscountsDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/CouponExceptionHandler.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/MeCouponControllerIntegrationTest.java`

- [ ] **Step 1: Write `ProspectiveDiscountsDto`**

```java
public record ProspectiveDiscountsDto(
        long listingFeeLindens,
        BigDecimal commissionRate,
        UUID listingFeeCouponPublicId,    // null if no coupon
        String listingFeeCouponCode,      // null if no coupon
        UUID commissionCouponPublicId,    // null if no coupon
        String commissionCouponCode       // null if no coupon
) {}
```

- [ ] **Step 2: Write the controller**

```java
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeCouponController {

    private final CouponService couponService;
    private final CouponDiscountResolver resolver;
    private final CouponGrantRepository grantRepo;
    private final CouponMapper mapper;  // new helper for DTO conversion

    @GetMapping("/coupons")
    public List<CouponGrantDto> list(@AuthenticationPrincipal AuthPrincipal p,
                                      @RequestParam(defaultValue = "active") String filter) {
        if ("history".equalsIgnoreCase(filter)) {
            return grantRepo.findByUserIdOrderByGrantedAtDesc(p.userId()).stream()
                    .filter(g -> g.getState() != CouponGrantState.ACTIVE)
                    .map(mapper::toGrantDto).toList();
        }
        return grantRepo.findByUserIdAndStateOrderByGrantedAtAsc(p.userId(), CouponGrantState.ACTIVE)
                .stream().map(mapper::toGrantDto).toList();
    }

    @PostMapping("/coupons/redeem")
    public ResponseEntity<CouponGrantDto> redeem(@AuthenticationPrincipal AuthPrincipal p,
                                                  @Valid @RequestBody RedeemCouponRequest req) {
        CouponGrant g = couponService.redeem(p.userId(), req.code());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toGrantDto(g));
    }

    @GetMapping("/listings/prospective-discounts")
    public ProspectiveDiscountsDto prospective(@AuthenticationPrincipal AuthPrincipal p) {
        var snap = resolver.resolve(p.userId());
        return mapper.toProspective(snap);
    }
}
```

Add a `CouponMapper` helper class. `toGrantDto` builds the DTO from grant + coupon (includes code + discount summary); `toProspective` enriches with coupon public_id/code via repo lookup.

- [ ] **Step 3: Write `CouponExceptionHandler`**

```java
@RestControllerAdvice
public class CouponExceptionHandler {

    @ExceptionHandler(CouponException.class)
    public ResponseEntity<ApiError> handle(CouponException e) {
        HttpStatus status = switch (e.getCode()) {
            case UNKNOWN_CODE -> HttpStatus.NOT_FOUND;
            case NOT_ELIGIBLE -> HttpStatus.FORBIDDEN;
            case ALREADY_REDEEMED, EXPIRED, PAUSED, MAX_REACHED, IMMUTABLE_FIELD,
                    LIFETIME_REQUIRED, SIGNUP_WINDOW_PAIRED, INACTIVE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(
                ApiError.builder().code(e.getCode().name()).message(e.getMessage()).build());
    }
}
```

- [ ] **Step 4: Write `MeCouponControllerIntegrationTest`**

`@SpringBootTest + @AutoConfigureMockMvc`. Cover happy path + every redemption error code; GET list returns ACTIVE grants; GET history returns non-ACTIVE; prospective-discounts returns the resolver snapshot.

- [ ] **Step 5: Run tests**

Run: `cd backend && ./mvnw test -Dtest=MeCouponControllerIntegrationTest`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/ \
        backend/src/test/java/com/slparcelauctions/backend/coupon/MeCouponControllerIntegrationTest.java
git commit -m "feat(coupon): MeCouponController + redemption error handler"
```

---

### Task 13: AdminCouponController — full CRUD

Implements `GET /api/v1/admin/coupons`, `GET .../{publicId}`, `POST`, `PATCH .../{publicId}`, `DELETE .../{publicId}`.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/AdminCouponController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/AdminCouponControllerIntegrationTest.java`

- [ ] **Step 1: Write the controller**

```java
@RestController
@RequestMapping("/api/v1/admin/coupons")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponController {

    private final CouponService service;
    private final CouponGrantRepository grantRepo;
    private final CouponMapper mapper;

    @GetMapping
    public SearchPagedResponse<CouponSummaryDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(name = "discount_target", required = false) DiscountTarget target,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        Page<Coupon> p = service.listAdmin(q, active, target, PageRequest.of(page, size));
        return new SearchPagedResponse<>(
                p.getContent().stream()
                        .map(c -> mapper.toSummary(c, grantRepo.countByCouponId(c.getId()),
                                grantRepo.countByCouponIdAndStateActive(c.getId())))
                        .toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @GetMapping("/{publicId}")
    public CouponDto get(@PathVariable UUID publicId) {
        return mapper.toDto(service.findByPublicId(publicId));
    }

    @PostMapping
    public ResponseEntity<CouponDto> create(@AuthenticationPrincipal AuthPrincipal p,
                                             @Valid @RequestBody CreateCouponRequest req) {
        Coupon c = service.createCoupon(req, p.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(c));
    }

    @PatchMapping("/{publicId}")
    public CouponDto patch(@PathVariable UUID publicId,
                            @Valid @RequestBody PatchCouponRequest req) {
        return mapper.toDto(service.patch(publicId, req));
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<Void> archive(@PathVariable UUID publicId) {
        service.archive(publicId);
        return ResponseEntity.noContent().build();
    }
}
```

Add `countByCouponIdAndStateActive(long couponId)` to `CouponGrantRepository`:

```java
@Query("SELECT COUNT(g) FROM CouponGrant g WHERE g.coupon.id = :cid AND g.state = 'ACTIVE'")
long countByCouponIdAndStateActive(@Param("cid") long couponId);
```

- [ ] **Step 2: Add `CouponMapper.toDto`, `toSummary`, `toGrantDto`, `toProspective` methods**

These all live in the same `CouponMapper` class. Use plain Java composition; no MapStruct.

- [ ] **Step 3: Write `AdminCouponControllerIntegrationTest`**

`@SpringBootTest + @AutoConfigureMockMvc` with admin auth helper. Cover:
- POST creates coupon (single + multi-discount + allowlist + signup-window). Verify backfill creates grants for matching existing users.
- GET list pagination + filters (q, active, discount_target)
- PATCH blocks immutable fields when totalGrants > 0; allows when 0
- DELETE hard-deletes when 0 grants, soft-archives when >0

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=AdminCouponControllerIntegrationTest`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/AdminCouponController.java \
        backend/src/main/java/com/slparcelauctions/backend/coupon/CouponGrantRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/coupon/CouponMapper.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/AdminCouponControllerIntegrationTest.java
git commit -m "feat(coupon): admin CRUD controller + mapper"
```

---

### Task 14: AdminCouponGrantController — grants list + direct-grant + revoke

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/coupon/AdminCouponGrantController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/coupon/AdminCouponGrantControllerIntegrationTest.java`

- [ ] **Step 1: Write the controller**

```java
@RestController
@RequestMapping("/api/v1/admin/coupons/{publicId}/grants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponGrantController {

    private final CouponService service;
    private final CouponGrantRepository grantRepo;
    private final CouponMapper mapper;

    @GetMapping
    public SearchPagedResponse<CouponGrantDto> list(
            @PathVariable UUID publicId,
            @RequestParam(required = false) CouponGrantState state,
            @RequestParam(required = false) CouponGrantSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        Coupon c = service.findByPublicId(publicId);
        Page<CouponGrant> p = grantRepo.findByCouponIdAndOptionalFilters(
                c.getId(), state, source, PageRequest.of(page, size));
        return new SearchPagedResponse<>(
                p.getContent().stream().map(mapper::toGrantDto).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @PostMapping
    public ResponseEntity<List<CouponGrantDto>> directGrant(
            @PathVariable UUID publicId,
            @Valid @RequestBody DirectGrantRequest req) {
        List<CouponGrant> created = service.directGrant(publicId, req.userPublicIds());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(created.stream().map(mapper::toGrantDto).toList());
    }

    @PostMapping("/{grantPublicId}/revoke")
    public CouponGrantDto revoke(@PathVariable UUID publicId, @PathVariable UUID grantPublicId) {
        return mapper.toGrantDto(service.revokeGrant(publicId, grantPublicId));
    }
}
```

Add `findByCouponIdAndOptionalFilters` to `CouponGrantRepository`:

```java
@Query("SELECT g FROM CouponGrant g WHERE g.coupon.id = :cid " +
       "AND (:state IS NULL OR g.state = :state) " +
       "AND (:source IS NULL OR g.source = :source) " +
       "ORDER BY g.grantedAt DESC")
Page<CouponGrant> findByCouponIdAndOptionalFilters(@Param("cid") long couponId,
        @Param("state") CouponGrantState state, @Param("source") CouponGrantSource source,
        Pageable pageable);
```

- [ ] **Step 2: Write `AdminCouponGrantControllerIntegrationTest`**

Cover direct-grant to multiple users (one happy, one skip-because-already-has-max); revoke transitions state; revoked grant ignored by resolver next call.

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=AdminCouponGrantControllerIntegrationTest`

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/coupon/AdminCouponGrantController.java \
        backend/src/main/java/com/slparcelauctions/backend/coupon/CouponGrantRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/coupon/AdminCouponGrantControllerIntegrationTest.java
git commit -m "feat(coupon): admin grant list + direct-grant + revoke endpoints"
```

---

### Task 15: Frontend types + API client + hooks + WalletCouponsCard

**Files:**
- Create: `frontend/src/lib/api/coupons.types.ts`
- Create: `frontend/src/lib/api/coupons.ts`
- Create: `frontend/src/hooks/useCoupons.ts`
- Create: `frontend/src/hooks/useRedeemCoupon.ts`
- Create: `frontend/src/components/wallet/CouponGrantCard.tsx`
- Create: `frontend/src/components/wallet/CouponGrantCard.test.tsx`
- Create: `frontend/src/components/wallet/WalletCouponsCard.tsx`
- Create: `frontend/src/components/wallet/WalletCouponsCard.test.tsx`
- Modify: `frontend/src/components/wallet/WalletPanel.tsx` — render `<WalletCouponsCard />` between balance card and ledger

- [ ] **Step 1: Write `coupons.types.ts`**

```ts
export type DiscountTarget = "LISTING_FEE" | "COMMISSION_RATE";
export type DiscountOp = "OVERRIDE" | "PERCENT_OFF" | "FLAT_OFF";
export type CouponGrantState = "ACTIVE" | "EXHAUSTED" | "EXPIRED" | "REVOKED";
export type CouponGrantSource = "REDEMPTION" | "ADMIN_GRANT" | "SIGNUP_WINDOW";

export interface CouponDiscountDto {
  target: DiscountTarget;
  op: DiscountOp;
  value: string;       // decimal as string
  sortOrder?: number;
}
export interface CouponGrantDto {
  publicId: string;
  couponPublicId: string;
  code: string;
  grantedAt: string;
  expiresAt: string | null;
  remainingCount: number | null;
  state: CouponGrantState;
  source: CouponGrantSource;
  discounts: CouponDiscountDto[];
}
export interface ProspectiveDiscountsDto {
  listingFeeLindens: number;
  commissionRate: string;
  listingFeeCouponPublicId: string | null;
  listingFeeCouponCode: string | null;
  commissionCouponPublicId: string | null;
  commissionCouponCode: string | null;
}
```

- [ ] **Step 2: Write `coupons.ts` API client**

```ts
import { apiUrl, fetchJson } from "@/lib/api/url";
import type { CouponGrantDto, ProspectiveDiscountsDto } from "./coupons.types";

export async function fetchMyCoupons(filter: "active" | "history") {
  return fetchJson<CouponGrantDto[]>(apiUrl(`/api/v1/me/coupons?filter=${filter}`));
}
export async function redeemCoupon(code: string) {
  return fetchJson<CouponGrantDto>(apiUrl("/api/v1/me/coupons/redeem"), {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code }),
  });
}
export async function fetchProspectiveDiscounts() {
  return fetchJson<ProspectiveDiscountsDto>(apiUrl("/api/v1/me/listings/prospective-discounts"));
}
```

- [ ] **Step 3: Write `useCoupons` + `useRedeemCoupon`**

Standard react-query hooks. `useRedeemCoupon` mutation invalidates `["me-coupons"]` and `["prospective-discounts"]` on success.

- [ ] **Step 4: Write `CouponGrantCard`**

Renders the discount summary (e.g. "Free listings", "3% commission"), expiry as relative string ("expires in 13 days" or "never expires"), remaining count if non-null. Use existing `Card` + `Badge` components.

- [ ] **Step 5: Write `WalletCouponsCard`**

Top: redeem input + Redeem button + inline error rendering for each `CouponRedemptionError` code (i18n keys: `coupon.redeem.error.UNKNOWN_CODE` etc.). Active section: list of `<CouponGrantCard />`. History collapsible: list of non-ACTIVE grants.

- [ ] **Step 6: Write tests**

Vitest + RTL. Cover render, redeem happy path, each error code message, history toggle.

- [ ] **Step 7: Wire into `WalletPanel.tsx`**

Render `<WalletCouponsCard />` between the existing balance card and ledger.

- [ ] **Step 8: Run tests**

Run: `cd frontend && npm test -- --run WalletCouponsCard CouponGrantCard`

- [ ] **Step 9: Run verify guards**

Run: `cd frontend && npm run verify`

- [ ] **Step 10: Commit**

```bash
git add frontend/src/lib/api/coupons.types.ts frontend/src/lib/api/coupons.ts \
        frontend/src/hooks/useCoupons.ts frontend/src/hooks/useRedeemCoupon.ts \
        frontend/src/components/wallet/CouponGrantCard.tsx \
        frontend/src/components/wallet/CouponGrantCard.test.tsx \
        frontend/src/components/wallet/WalletCouponsCard.tsx \
        frontend/src/components/wallet/WalletCouponsCard.test.tsx \
        frontend/src/components/wallet/WalletPanel.tsx
git commit -m "feat(wallet): coupons card with redeem + active + history"
```

---

### Task 16: CreateListingCouponSummary — inline badges + redeem expander

**Files:**
- Create: `frontend/src/hooks/useProspectiveDiscounts.ts`
- Create: `frontend/src/components/listings/CreateListingCouponSummary.tsx`
- Create: `frontend/src/components/listings/CreateListingCouponSummary.test.tsx`
- Modify: `frontend/src/app/listings/(verified)/create/page.tsx` — render `<CreateListingCouponSummary />` in the summary block

- [ ] **Step 1: Write `useProspectiveDiscounts`**

Standard react-query hook calling `fetchProspectiveDiscounts`. Invalidated by `useRedeemCoupon` mutation success.

- [ ] **Step 2: Write `CreateListingCouponSummary`**

Reads `useProspectiveDiscounts()`. Renders:
- Listing fee row: `~~L$<default>~~ L$<discounted>` + badge with coupon code, when `listingFeeCouponPublicId` is set
- Commission row: `~~<default>%~~ <discounted>%` + badge with coupon code, when `commissionCouponPublicId` is set
- Below: "Have a code? Click to redeem" expander; expand reveals input + Apply button; uses `useRedeemCoupon` mutation. Success toast + re-fetch of prospective-discounts.

- [ ] **Step 3: Write tests**

Cover: badges render when discounts apply; no badges when defaults; expander toggle; redeem happy + error.

- [ ] **Step 4: Wire into create-listing page**

Render the component at the bottom of the summary block in the create-listing form. Pass the configured default fee + commission (load via `useConfig()` or hardcode the same values as the resolver default — defer to existing pattern).

- [ ] **Step 5: Run tests + verify**

Run: `cd frontend && npm test -- --run CreateListingCouponSummary && npm run verify`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/hooks/useProspectiveDiscounts.ts \
        frontend/src/components/listings/CreateListingCouponSummary.tsx \
        frontend/src/components/listings/CreateListingCouponSummary.test.tsx \
        frontend/src/app/listings/\(verified\)/create/page.tsx
git commit -m "feat(listing): inline coupon summary + redeem expander on create"
```

---

### Task 17: Admin coupons list page + sidebar link

**Files:**
- Create: `frontend/src/app/admin/coupons/page.tsx`
- Create: `frontend/src/components/admin/coupons/AdminCouponList.tsx`
- Create: `frontend/src/components/admin/coupons/AdminCouponList.test.tsx`
- Modify: `frontend/src/components/admin/AdminSidebar.tsx` (or equivalent) — add Coupons link
- Add to API client `frontend/src/lib/api/coupons.ts`:
  ```ts
  export async function fetchAdminCoupons(params: {q?: string; active?: boolean; target?: DiscountTarget; page?: number; size?: number}) {
    const sp = new URLSearchParams();
    if (params.q) sp.set("q", params.q);
    if (params.active !== undefined) sp.set("active", String(params.active));
    if (params.target) sp.set("discount_target", params.target);
    if (params.page !== undefined) sp.set("page", String(params.page));
    if (params.size !== undefined) sp.set("size", String(params.size));
    return fetchJson<SearchPagedResponse<CouponSummaryDto>>(apiUrl(`/api/v1/admin/coupons?${sp}`));
  }
  ```

- [ ] **Step 1: Write `AdminCouponList`** — table columns per spec §8 (code, description, status pill, discounts collapsed pills, redemptions/cap, expires). Use existing `Table` component patterns from `/admin/users`.

- [ ] **Step 2: Write `/admin/coupons/page.tsx`** — server component shell; passes query params to client component; "Create coupon" button links to `/admin/coupons/new`.

- [ ] **Step 3: Add sidebar link** — alphabetically position "Coupons" (between Bans and Disputes by alphabetic order).

- [ ] **Step 4: Tests + verify**

Run: `cd frontend && npm test -- --run AdminCouponList && npm run verify`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/admin/coupons/ frontend/src/components/admin/coupons/ \
        frontend/src/components/admin/AdminSidebar.tsx \
        frontend/src/lib/api/coupons.ts
git commit -m "feat(admin-ui): coupons list page + sidebar link"
```

---

### Task 18: Admin coupon create form

**Files:**
- Create: `frontend/src/app/admin/coupons/new/page.tsx`
- Create: `frontend/src/components/admin/coupons/AdminCouponForm.tsx`
- Create: `frontend/src/components/admin/coupons/AdminCouponForm.test.tsx`
- Add to API client:
  ```ts
  export async function createAdminCoupon(req: CreateCouponRequest): Promise<CouponDto>
  ```
- Add `CreateCouponRequest` to `coupons.types.ts`:
  ```ts
  export interface CreateCouponRequest {
    code: string;
    description?: string;
    durationDays?: number;
    useCount?: number;
    redeemableUntil?: string;
    maxTotalRedemptions?: number;
    maxPerUser?: number;
    signupWindowStart?: string;
    signupWindowEnd?: string;
    active?: boolean;
    notifyOnGrant?: boolean;
    discounts: CouponDiscountDto[];
    allowedUserPublicIds?: string[];
  }
  ```

- [ ] **Step 1: Write `AdminCouponForm`** — sections 1-6 per spec §8. Repeatable discount-bundle rows with add/remove. Validation: at least one of duration_days/use_count required; signup_window_start/end paired or both empty. Allowed-users picker: autocomplete on email/sl-username via existing `useUserSearch()` hook. Hidden behind a search field for performance.

- [ ] **Step 2: Write `/admin/coupons/new/page.tsx`** — wraps form, handles submit → POST → redirect to detail page.

- [ ] **Step 3: Tests + verify** — cover discount-bundle add/remove, lifetime validation, signup-window paired-or-empty, submit success + error.

Run: `cd frontend && npm test -- --run AdminCouponForm && npm run verify`

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/admin/coupons/new/ \
        frontend/src/components/admin/coupons/AdminCouponForm.tsx \
        frontend/src/components/admin/coupons/AdminCouponForm.test.tsx \
        frontend/src/lib/api/coupons.ts frontend/src/lib/api/coupons.types.ts
git commit -m "feat(admin-ui): create coupon form with discount bundle"
```

---

### Task 19: Admin coupon detail page (3 tabs)

**Files:**
- Create: `frontend/src/app/admin/coupons/[publicId]/page.tsx`
- Create: `frontend/src/components/admin/coupons/AdminCouponDetailOverview.tsx`
- Create: `frontend/src/components/admin/coupons/AdminCouponDetailGrants.tsx`
- Create: `frontend/src/components/admin/coupons/AdminCouponDetailEdit.tsx`
- Create: `frontend/src/components/admin/coupons/AdminCouponDirectGrantModal.tsx`
- Add to API client: `fetchAdminCoupon`, `patchAdminCoupon`, `deleteAdminCoupon`, `fetchAdminCouponGrants`, `directGrantToUsers`, `revokeGrant`

- [ ] **Step 1: Detail page shell with tab routing** — use existing tabbed-detail page patterns (e.g. `/admin/users/[publicId]/page.tsx`).

- [ ] **Step 2: Overview tab** — read-only config + aggregate metrics card.

- [ ] **Step 3: Grants tab** — paged table of `CouponGrantDto`; Direct-grant button opens modal with user-picker → POST `/grants` → refresh table. Revoke button per row with confirmation.

- [ ] **Step 4: Edit tab** — re-use `AdminCouponForm` in patch mode (only patchable fields enabled when `totalGrants > 0` — disable durationDays, useCount, maxPerUser, signupWindow fields with tooltip "locked after first grant").

- [ ] **Step 5: Tests + verify**

Run: `cd frontend && npm test -- --run AdminCouponDetail && npm run verify`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/admin/coupons/\[publicId\]/ \
        frontend/src/components/admin/coupons/ \
        frontend/src/lib/api/coupons.ts
git commit -m "feat(admin-ui): coupon detail page with overview/grants/edit tabs"
```

---

### Task 20: Postman + README sweep + dev smoke + PR

**Files:**
- Modify: `README.md` (root) — add coupon system bullet
- Modify: `docs/implementation/DEFERRED_WORK.md` — append a note if any in-scope work was deferred (should be none per spec §13)
- Modify: Postman collection (via MCP) — add requests mirroring every endpoint from §7

- [ ] **Step 1: Postman additions via `mcp__postman__*` tools**

For each endpoint in spec §7, create a request inside the SLPA collection (`8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`). Variable-chain `couponPublicId`, `couponGrantPublicId` so admin happy-path flow works in one run:
1. POST `/admin/coupons` → capture `couponPublicId`
2. POST `/admin/coupons/{couponPublicId}/grants` → capture `couponGrantPublicId`
3. GET `/me/coupons` (as the target user) → sanity check
4. POST `/me/coupons/redeem` (separate flow for code-based redemption)
5. POST `/admin/coupons/{couponPublicId}/grants/{couponGrantPublicId}/revoke`

Each request gets a `pm.test()` script asserting status and capturing the public IDs.

- [ ] **Step 2: README.md sweep**

Add a short bullet under the existing feature list:
> - Coupon system (admin-managed promotional codes that discount listing fees and/or commission rates; auto-grant by signup-window; full admin CRUD UI)

- [ ] **Step 3: DEFERRED_WORK.md check**

Re-read spec §13 (Out of scope). Confirm nothing in the spec was silently deferred. The spec's "Out of scope" items are listed explicitly there; they don't need to be replicated in DEFERRED_WORK.md (which tracks tactical deferrals, not strategic scope cuts).

- [ ] **Step 4: Local dev smoke**

```bash
docker compose restart backend frontend
# Browser:
#  1. Log in as admin
#  2. /admin/coupons -> Create coupon: code=TEST30, FREE_LISTING DURATION op=OVERRIDE value=0, duration_days=30
#  3. Log in as regular verified user
#  4. /wallet -> Redeem TEST30 -> see active grant in card
#  5. /listings/.../create -> see "L$0" listing fee badge with TEST30
#  6. Submit listing -> verify fee auto-paid, no terminal interaction needed
#  7. Listing activates (after verification) -> if useCount was set, verify decrement; for DURATION-only no decrement
```

- [ ] **Step 5: Run full test suites once more**

```bash
cd backend && ./mvnw test
cd frontend && npm test
cd frontend && npm run verify
```

All green.

- [ ] **Step 6: Push + open PR**

```bash
git push origin feat/coupon-codes
gh pr create --base dev --head feat/coupon-codes \
  --title "feat(coupon): coupon codes system (#165)" \
  --body "$(cat <<'EOF'
## Summary
- Implements coupon system per docs/superpowers/specs/2026-05-20-coupon-codes-design.md (issue #165)
- New tables coupons / coupon_discounts / coupon_grants / coupon_allowed_users (Flyway V41)
- Backend: coupon package with entities, repos, service, resolver, sweeper, three controllers
- Auction.listingFeeAmt + commissionRate snapshotting at create; grant-id stamping; activation consumption
- User-creation hook + coupon-save backfill for signup-window auto-grant
- Frontend: WalletCouponsCard; CreateListingCouponSummary; /admin/coupons list/new/detail
- NotificationPublisher.couponGranted + COUPON_GRANTED category + email template

## Test plan
- [ ] backend ./mvnw test green
- [ ] frontend npm test green
- [ ] frontend npm run verify green
- [ ] Manual smoke: create coupon -> redeem -> create listing -> verify L\$0 fee badge -> activate -> verify count decrement (if count-based)
- [ ] Postman collection: admin happy-path flow runs cleanly
EOF
)"
```

- [ ] **Step 7: Commit Postman + README changes**

```bash
git add README.md docs/implementation/DEFERRED_WORK.md
git commit -m "docs: README + DEFERRED_WORK sweep for coupon system"
git push
```

---

## Self-review

**Spec coverage:**
- §1 Goal — Task 20 PR description references the goal.
- §2 Data model — Task 1 (entities + migration), Task 2 (Auction columns).
- §3 Apply / consume logic — Task 4 (resolver), Task 10 (snapshot at create), Task 11 (consume at activate). Sweeper = Task 5.
- §4 Redemption flow — Task 7 (service) + Task 12 (controller).
- §5 Auto-grant — Task 8 (backfill + hook).
- §6 Notifications — Task 9.
- §7 Endpoints — Task 12 (user), Task 13 (admin CRUD), Task 14 (admin grants).
- §8 Frontend — Task 15 (wallet), Task 16 (create-listing), Task 17 (admin list), Task 18 (admin form), Task 19 (admin detail).
- §9 LSL — no changes (spec says so).
- §10 Configuration — Task 5 (`slpa.coupons.sweeper-cron` in `application.yml`).
- §11 Migration plan — Task 1.
- §12 Testing — distributed across all tasks (each backend task includes its tests; frontend tests in 15-19; Postman in 20).
- §13 Out of scope — Task 20 confirms nothing was deferred.
- §14 Decision log — context only, not implemented.

**Placeholder scan:** No "TBD"/"TODO" sentinels. Task 17 step 3 ("position alphabetically between Bans and Disputes") is concrete enough — engineer can sort. Task 19 step 4 ("re-use AdminCouponForm in patch mode") shows the disable list explicitly.

**Type consistency:**
- `CouponGrantDto` shape declared in Task 7 (DTOs) used in Tasks 12, 13, 14, 15, 19. Fields match.
- `CouponDiscountResolver.DiscountSnapshot` declared in Task 4, consumed by Task 10. Fields match.
- `ProspectiveDiscountsDto` declared in Task 12, consumed in Task 16. Fields match.
- `CreateCouponRequest` Java record in Task 6 matches the TS interface in Task 18.
- `CouponMapper` first referenced in Task 12, fully implemented in Task 13. The Task 12 controller imports it; engineer should stub-create the class in Task 12 and add methods incrementally — flagged in Task 12 step 2 ("Add a `CouponMapper` helper class").

**Resolved during plan-writing:** The spec section header reference in §3 was clarified to point at the activation hook's location; the V37 vs V41 numbering inconsistency was called out in the plan header.
