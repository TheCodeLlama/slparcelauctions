# Homepage Featured + Trending Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` to walk this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship the spec at `docs/superpowers/specs/2026-05-09-homepage-featured-trending-design.md` — Featured rail (admin-curated, target 4, auto-fill from highest current_bid), renamed Trending rail with new bids+saves formula, layout reorder, hero stack pulling from Featured.

**Architecture:** Backend: rename `FeaturedCategory.JUST_LISTED → FEATURED` and `MOST_ACTIVE → TRENDING`, replace their queries, move resource path `/auctions/featured/*` → `/auctions/rails/*`, add `is_featured`+`featured_until` columns + `PATCH /api/v1/admin/listings/{publicId}/featured`. Frontend: reorder `app/page.tsx`, hide empty Ending Soon, hero pulls from Featured, add admin row action + Featured badge column + preset chip.

**Tech Stack:** Spring Boot 4 / Java 26 / PostgreSQL / Flyway / Redis · Next.js 16 / React 19 / TanStack Query / MSW.

---

## File Map

**Backend new:**
- `backend/src/main/resources/db/migration/V23__featured_listings.sql`
- `backend/src/main/java/com/slparcelauctions/backend/admin/listings/dto/SetFeaturedRequest.java`
- `backend/src/test/java/com/slparcelauctions/backend/admin/listings/AdminListingFeaturedControllerIntegrationTest.java`

**Backend modified:**
- `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java` — add `isFeatured`, `featuredUntil` columns
- `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedCategory.java` — rename values + cache keys
- `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedCache.java` — add `invalidate(category)`
- `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedRepository.java` — replace `justListed()`→`featured()`, `mostActive()`→`trending()`
- `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedService.java` — switch arms
- `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedController.java` — request mapping `/auctions/rails`, route renames
- `backend/src/main/java/com/slparcelauctions/backend/security/SecurityConfig.java` — permitAll matcher path
- `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java` — `FEATURE_LISTING`, `UNFEATURE_LISTING`
- `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingController.java` — `setFeatured` PATCH method
- `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingService.java` — `setFeatured` business logic
- `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingQueryRepository.java` — featured filter, new SELECT cols
- `backend/src/main/java/com/slparcelauctions/backend/admin/listings/dto/AdminListingRowDto.java` — `isFeatured`, `featuredUntil`
- `backend/src/main/java/com/slparcelauctions/backend/admin/listings/dto/AdminListingFilterParams.java` — `featured` field
- `backend/src/test/java/com/slparcelauctions/backend/auction/featured/FeaturedRepositoryIntegrationTest.java` — rewrite for new queries
- `backend/src/test/java/com/slparcelauctions/backend/auction/featured/FeaturedControllerIntegrationTest.java` — new URL paths

**Frontend modified:**
- `frontend/src/lib/api/auctions-search.ts` — rename `FeaturedCategory`, change URL
- `frontend/src/lib/admin/types.ts` — `AdminListingRow.isFeatured`/`featuredUntil`, `AdminListingAction` adds `feature`/`unfeature`, `AdminListingsFilters.featured`
- `frontend/src/lib/admin/api.ts` (or equivalent) — `setListingFeatured` API call
- `frontend/src/hooks/admin/useAdminListings.ts` — `useSetFeatured` mutation
- `frontend/src/components/admin/listings/RowActionMenu.tsx` — feature/unfeature entries
- `frontend/src/components/admin/listings/ListingActionModal.tsx` — feature form variant
- `frontend/src/components/admin/listings/AdminListingsTable.tsx` — Featured badge column
- `frontend/src/components/admin/listings/AdminListingsPage.tsx` — featured filter param wiring
- `frontend/src/components/admin/listings/PresetChips.tsx` — Preset.featured field
- `frontend/src/app/admin/listings/page.tsx` — featured preset
- `frontend/src/app/page.tsx` — layout reorder, rename, hide-empty-ending-soon
- `frontend/src/app/page.integration.test.tsx` — re-record fixtures
- `frontend/src/test/msw/handlers.ts` — rail URLs

**Other:**
- `README.md` — homepage section description
- Postman SLPA collection — rail rename + new admin "Set featured" request

---

## Phase 1: Backend foundation (rail rename + queries)

### Task 1: V23 migration + Auction entity columns

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__featured_listings.sql`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`

- [ ] **Step 1: Write migration**

```sql
-- V23__featured_listings.sql
ALTER TABLE auctions
  ADD COLUMN is_featured       BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN featured_until    TIMESTAMPTZ NULL;

CREATE INDEX ix_auctions_featured_active
    ON auctions (ends_at ASC, id ASC)
    WHERE is_featured = TRUE AND status = 'ACTIVE';
```

- [ ] **Step 2: Add fields to `Auction.java`**

Find the existing `@Column(name = "current_bid", ...)` block and add nearby:

```java
@Column(name = "is_featured", nullable = false)
@Builder.Default
private boolean isFeatured = false;

@Column(name = "featured_until")
private OffsetDateTime featuredUntil;
```

- [ ] **Step 3: Run backend tests to confirm migration applies cleanly**

Run: `./mvnw -pl . test -Dtest=FeaturedControllerIntegrationTest -q`
Expected: still passes pre-rename (the Auction entity additions are backwards-compatible).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V23__featured_listings.sql \
        backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java
git commit -m "feat(backend): add is_featured + featured_until columns to auctions"
```

### Task 2: FeaturedCategory rename + cache key change

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedCategory.java`

- [ ] **Step 1: Replace enum**

```java
public enum FeaturedCategory {
    FEATURED("slpa:featured:rail:featured"),
    ENDING_SOON("slpa:featured:rail:ending-soon"),
    TRENDING("slpa:featured:rail:trending");

    private final String cacheKey;
    FeaturedCategory(String cacheKey) { this.cacheKey = cacheKey; }
    public String cacheKey() { return cacheKey; }
}
```

`JUST_LISTED` → `FEATURED`, `MOST_ACTIVE` → `TRENDING`. The compiler will surface broken switch arms in `FeaturedService` next.

- [ ] **Step 2: Don't commit yet** — Tasks 2–7 break the codebase; commit at Task 7's end as one atomic rail rename.

### Task 3: FeaturedCache.invalidate()

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedCache.java`

- [ ] **Step 1: Add invalidate method**

After the `getOrCompute` method:

```java
public void invalidate(FeaturedCategory category) {
    try {
        redis.delete(category.cacheKey());
    } catch (RuntimeException e) {
        log.warn("Failed to invalidate featured cache for {}: {}",
                category, e.toString());
    }
}
```

### Task 4: FeaturedRepository — featured() with curated + fill blend

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedRepository.java`

- [ ] **Step 1: Replace the file body**

```java
package com.slparcelauctions.backend.auction.featured;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.slparcelauctions.backend.auction.Auction;

public interface FeaturedRepository extends Repository<Auction, Long> {

    @Query(value = """
            WITH curated AS (
              SELECT a.*, 0 AS bucket, a.ends_at AS sort_key
              FROM auctions a
              WHERE a.is_featured = TRUE
                AND (a.featured_until IS NULL OR a.featured_until > NOW())
                AND a.status = 'ACTIVE'
                AND a.ends_at > NOW()
              ORDER BY a.ends_at ASC, a.id ASC
              LIMIT 4
            ),
            fill AS (
              SELECT a.*, 1 AS bucket, NULL::timestamptz AS sort_key
              FROM auctions a
              WHERE a.status = 'ACTIVE'
                AND a.ends_at > NOW()
                AND a.id NOT IN (SELECT id FROM curated)
              ORDER BY a.current_bid DESC, a.id DESC
              LIMIT 4
            )
            SELECT * FROM (
              SELECT * FROM curated
              UNION ALL
              SELECT * FROM (
                SELECT * FROM fill
                LIMIT GREATEST(0, 4 - (SELECT COUNT(*) FROM curated))
              ) f
            ) blended
            ORDER BY bucket ASC, sort_key ASC NULLS LAST, current_bid DESC, id DESC
            LIMIT 4
            """, nativeQuery = true)
    List<Auction> featured();

    @Query(value = """
            SELECT a.* FROM auctions a
            WHERE a.status = 'ACTIVE' AND a.ends_at > NOW()
            ORDER BY a.ends_at ASC, a.id ASC
            LIMIT 6
            """, nativeQuery = true)
    List<Auction> endingSoon();

    @Query(value = """
            SELECT a.* FROM auctions a
            WHERE a.status = 'ACTIVE' AND a.ends_at > NOW()
            ORDER BY (
              (SELECT COUNT(*) FROM bids b
                WHERE b.auction_id = a.id
                  AND b.created_at > NOW() - INTERVAL '24 hours') * 2
              +
              (SELECT COUNT(*) FROM saved_auctions s
                WHERE s.auction_id = a.id
                  AND s.saved_at > NOW() - INTERVAL '24 hours')
            ) DESC, a.ends_at ASC, a.id DESC
            LIMIT 6
            """, nativeQuery = true)
    List<Auction> trending();
}
```

`justListed()` and `mostActive()` are deleted — `featured()` and `trending()` replace them.

### Task 5: FeaturedService — switch arm rename

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedService.java:52-56`

- [ ] **Step 1: Update the switch**

```java
List<Auction> rows = switch (category) {
    case FEATURED    -> featuredRepo.featured();
    case ENDING_SOON -> featuredRepo.endingSoon();
    case TRENDING    -> featuredRepo.trending();
};
```

### Task 6: FeaturedController — URL move + route renames

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/featured/FeaturedController.java`

- [ ] **Step 1: Replace controller body**

```java
@RestController
@RequestMapping("/api/v1/auctions/rails")
@RequiredArgsConstructor
public class FeaturedController {

    private final FeaturedService service;

    @GetMapping("/featured")
    public ResponseEntity<FeaturedResponse> featured() {
        return cachedOk(service.get(FeaturedCategory.FEATURED));
    }

    @GetMapping("/ending-soon")
    public ResponseEntity<FeaturedResponse> endingSoon() {
        return cachedOk(service.get(FeaturedCategory.ENDING_SOON));
    }

    @GetMapping("/trending")
    public ResponseEntity<FeaturedResponse> trending() {
        return cachedOk(service.get(FeaturedCategory.TRENDING));
    }

    private static ResponseEntity<FeaturedResponse> cachedOk(FeaturedResponse body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(body);
    }
}
```

### Task 7: SecurityConfig — permitAll matcher update + commit

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/security/SecurityConfig.java`

- [ ] **Step 1: Find and replace the existing matcher**

Search the file for `/api/v1/auctions/featured` — it should appear in a `requestMatchers(HttpMethod.GET, ...).permitAll()` chain.

Replace `/api/v1/auctions/featured/*` (or `/**`) with `/api/v1/auctions/rails/**`.

- [ ] **Step 2: Run unit tests to confirm compilation + smoke**

Run: `./mvnw -pl . test -Dtest=FeaturedServiceTest,FeaturedCacheTest -q`
Expected: pass (FeaturedService compiles against new enum + repo signatures).

If `FeaturedServiceTest` references `JUST_LISTED` / `MOST_ACTIVE` directly, replace with `FEATURED` / `TRENDING` in the test file as part of this task.

- [ ] **Step 3: Commit the rail-rename block (Tasks 2–7)**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/featured/ \
        backend/src/main/java/com/slparcelauctions/backend/security/SecurityConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/featured/FeaturedServiceTest.java
git commit -m "refactor(rails): rename featured rails, move to /auctions/rails/*

JUST_LISTED -> FEATURED with curated + fill blend (target 4)
MOST_ACTIVE -> TRENDING with bids*2 + saves over 24h
URL path /api/v1/auctions/featured/* -> /api/v1/auctions/rails/*
SecurityConfig permitAll matcher updated
FeaturedCache gains invalidate(category) for admin write path"
```

### Task 8: FeaturedRepositoryIntegrationTest — rewrite for new queries

**Files:**
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/featured/FeaturedRepositoryIntegrationTest.java`

- [ ] **Step 1: Replace the file**

The existing file seeds `endingSoon`, `justListed`, `mostActive`. Replace with tests for `featured()` and keep+update tests for `endingSoon()` (unchanged) and `trending()`. Use the existing `seedActive(...)` helper signature; preserve the `back-date created_at via native UPDATE` pattern for `bids.created_at` timing.

```java
@Test
void featured_zeroCurated_returnsTopFourByCurrentBidDesc() {
    // Seed 5 ACTIVE auctions, none flagged
    Auction a1 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a1, 100L);
    Auction a2 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a2, 500L);
    Auction a3 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a3, 300L);
    Auction a4 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a4, 200L);
    Auction a5 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a5, 400L);

    List<Auction> result = featuredRepo.featured();
    assertThat(result).hasSize(4);
    assertThat(result.get(0).getCurrentBid()).isEqualTo(500L);
    assertThat(result.get(1).getCurrentBid()).isEqualTo(400L);
    assertThat(result.get(2).getCurrentBid()).isEqualTo(300L);
    assertThat(result.get(3).getCurrentBid()).isEqualTo(200L);
}

@Test
void featured_twoCurated_fourActives_returnsCuratedThenFill() {
    Auction c1 = seedActive(plusHours(2), minusDays(1)); setFeatured(c1, null); setCurrentBid(c1, 50L);
    Auction c2 = seedActive(plusHours(5), minusDays(1)); setFeatured(c2, null); setCurrentBid(c2, 60L);
    Auction f1 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(f1, 1000L);
    Auction f2 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(f2, 800L);
    Auction f3 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(f3, 900L);

    List<Auction> result = featuredRepo.featured();
    assertThat(result).hasSize(4);
    // Curated first (sorted by ends_at ASC), then fill (sorted by current_bid DESC).
    assertThat(result.get(0).getId()).isEqualTo(c1.getId());
    assertThat(result.get(1).getId()).isEqualTo(c2.getId());
    assertThat(result.get(2).getId()).isEqualTo(f1.getId()); // 1000
    assertThat(result.get(3).getId()).isEqualTo(f3.getId()); // 900
}

@Test
void featured_sixCurated_returnsSoonestFour() {
    Auction[] rows = new Auction[6];
    for (int i = 0; i < 6; i++) {
        rows[i] = seedActive(plusHours(i + 1), minusDays(1));
        setFeatured(rows[i], null);
    }
    List<Auction> result = featuredRepo.featured();
    assertThat(result).hasSize(4);
    assertThat(result).extracting(Auction::getId)
            .containsExactly(rows[0].getId(), rows[1].getId(), rows[2].getId(), rows[3].getId());
}

@Test
void featured_excludesPastFeaturedUntil() {
    Auction expired = seedActive(plusDays(7), minusDays(1));
    setFeatured(expired, OffsetDateTime.now().minusHours(1));
    Auction live = seedActive(plusDays(7), minusDays(1));
    setFeatured(live, OffsetDateTime.now().plusHours(1));

    List<Auction> result = featuredRepo.featured();
    assertThat(result).extracting(Auction::getId).contains(live.getId()).doesNotContain(expired.getId());
}

@Test
void featured_includesNullFeaturedUntil() {
    Auction permanent = seedActive(plusDays(7), minusDays(1));
    setFeatured(permanent, null);
    List<Auction> result = featuredRepo.featured();
    assertThat(result).extracting(Auction::getId).contains(permanent.getId());
}

@Test
void featured_excludesNonActiveStatuses() {
    Auction draft = seedActive(plusDays(7), minusDays(1));
    setFeatured(draft, null);
    em.createNativeQuery("UPDATE auctions SET status = 'CANCELLED' WHERE id = :id")
        .setParameter("id", draft.getId()).executeUpdate();
    List<Auction> result = featuredRepo.featured();
    assertThat(result).extracting(Auction::getId).doesNotContain(draft.getId());
}

@Test
void endingSoon_unchanged_ordersByEndsAtAsc_limit6() {
    for (int i = 0; i < 8; i++) seedActive(plusHours(i + 1), minusDays(1));
    List<Auction> result = featuredRepo.endingSoon();
    assertThat(result).hasSize(6);
    for (int i = 0; i < 5; i++) {
        assertThat(result.get(i).getEndsAt()).isBeforeOrEqualTo(result.get(i + 1).getEndsAt());
    }
}

@Test
void trending_scoresBidsTwoSavesOne_over24h() {
    Auction hot = seedActive(plusDays(7), minusDays(2));
    Auction warm = seedActive(plusDays(7), minusDays(2));
    Auction cold = seedActive(plusDays(7), minusDays(2));

    // hot: 3 recent bids = 6 + 1 recent save = 7 points
    seedRecentBids(hot, 3);
    seedRecentSaves(hot, 1);
    // warm: 1 recent bid = 2 + 4 recent saves = 6 points
    seedRecentBids(warm, 1);
    seedRecentSaves(warm, 4);
    // cold: 0 bids, 0 saves = 0 points

    List<Auction> result = featuredRepo.trending();
    assertThat(result.subList(0, 3)).extracting(Auction::getId)
            .containsExactly(hot.getId(), warm.getId(), cold.getId());
}

@Test
void trending_excludesEventsOutside24h() {
    Auction noisy = seedActive(plusDays(7), minusDays(2));
    Auction silent = seedActive(plusDays(7), minusDays(2));
    seedBidAt(noisy, OffsetDateTime.now().minusDays(3)); // outside window
    seedSaveAt(silent, OffsetDateTime.now().minusHours(1)); // inside

    List<Auction> result = featuredRepo.trending();
    // silent has 1 point, noisy has 0 — silent ranks above noisy
    int silentIdx = indexOf(result, silent.getId());
    int noisyIdx  = indexOf(result, noisy.getId());
    assertThat(silentIdx).isLessThan(noisyIdx);
}
```

Helpers to add (private methods on the test class):

```java
private static OffsetDateTime plusHours(int h) { return OffsetDateTime.now().plusHours(h); }
private static OffsetDateTime plusDays(int d)  { return OffsetDateTime.now().plusDays(d); }
private static OffsetDateTime minusDays(int d) { return OffsetDateTime.now().minusDays(d); }

private void setFeatured(Auction a, OffsetDateTime until) {
    em.createNativeQuery(
        "UPDATE auctions SET is_featured = TRUE, featured_until = :u WHERE id = :id")
        .setParameter("u", until)
        .setParameter("id", a.getId())
        .executeUpdate();
}

private void setCurrentBid(Auction a, long amount) {
    em.createNativeQuery("UPDATE auctions SET current_bid = :b WHERE id = :id")
        .setParameter("b", amount).setParameter("id", a.getId()).executeUpdate();
}

private void seedRecentBids(Auction a, int n) {
    for (int i = 0; i < n; i++) seedBidAt(a, OffsetDateTime.now().minusMinutes(5));
}

private void seedBidAt(Auction a, OffsetDateTime when) {
    Bid bid = bidRepo.save(Bid.builder()
            .auction(a).bidder(seller)
            .amount(100L).type(BidType.MAX)
            .build());
    em.createNativeQuery("UPDATE bids SET created_at = :t WHERE id = :id")
        .setParameter("t", when).setParameter("id", bid.getId()).executeUpdate();
    em.flush();
}

private void seedRecentSaves(Auction a, int n) {
    for (int i = 0; i < n; i++) seedSaveAt(a, OffsetDateTime.now().minusMinutes(5));
}

private void seedSaveAt(Auction a, OffsetDateTime when) {
    User saver = userRepo.save(User.builder()
            .username("u-" + UUID.randomUUID().toString().substring(0, 8))
            .email("saver-" + UUID.randomUUID() + "@ex.com")
            .passwordHash("x").slAvatarUuid(UUID.randomUUID())
            .displayName("S").verified(true).build());
    em.createNativeQuery(
        "INSERT INTO saved_auctions (public_id, version, created_at, updated_at, user_id, auction_id, saved_at) " +
        "VALUES (gen_random_uuid(), 0, NOW(), NOW(), :u, :a, :t)")
        .setParameter("u", saver.getId())
        .setParameter("a", a.getId())
        .setParameter("t", when)
        .executeUpdate();
    em.flush();
}

private static int indexOf(List<Auction> rows, Long id) {
    for (int i = 0; i < rows.size(); i++) if (rows.get(i).getId().equals(id)) return i;
    return -1;
}
```

The existing `seedActive` helper on the test (returns an Auction) is reused as-is.

- [ ] **Step 2: Run the test class**

Run: `./mvnw -pl . test -Dtest=FeaturedRepositoryIntegrationTest -q`
Expected: PASS (all 9 tests).

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/slparcelauctions/backend/auction/featured/FeaturedRepositoryIntegrationTest.java
git commit -m "test(rails): rewrite repository tests for featured() + trending()"
```

### Task 9: FeaturedControllerIntegrationTest — new URL paths

**Files:**
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/featured/FeaturedControllerIntegrationTest.java`

- [ ] **Step 1: Replace URLs and cache key prefix**

```java
// in clearCache():
var keys = redis.keys("slpa:featured:rail:*");

// in tests:
mockMvc.perform(get("/api/v1/auctions/rails/ending-soon"))...
mockMvc.perform(get("/api/v1/auctions/rails/featured"))...   // was just-listed
mockMvc.perform(get("/api/v1/auctions/rails/trending"))...    // was most-active

// cache key assertions:
redis.keys("slpa:featured:rail:ending-soon");   // size 1
redis.keys("slpa:featured:rail:featured");      // empty after only ending-soon hit
redis.keys("slpa:featured:rail:trending");      // empty
```

Rename test methods to match (`justListed_unauth_returns200` → `featured_unauth_returns200`, `mostActive_*` → `trending_*`).

- [ ] **Step 2: Run**

Run: `./mvnw -pl . test -Dtest=FeaturedControllerIntegrationTest -q`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/slparcelauctions/backend/auction/featured/FeaturedControllerIntegrationTest.java
git commit -m "test(rails): controller integration tests use /auctions/rails/* paths"
```

---

## Phase 2: Admin endpoint

### Task 10: AdminActionType enum + AdminListingStateException codes

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java`

- [ ] **Step 1: Append two values**

```java
PARCEL_TAG_CATEGORY_TOGGLED_ACTIVE,
FEATURE_LISTING,
UNFEATURE_LISTING
```

(Comma after the prior last value; preserve trailing-no-comma style.)

- [ ] **Step 2: Don't commit; bundle with Tasks 11–13.**

### Task 11: SetFeaturedRequest DTO

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/dto/SetFeaturedRequest.java`

- [ ] **Step 1: Write the DTO**

```java
package com.slparcelauctions.backend.admin.listings.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.FutureOrPresent;

/**
 * PATCH body for /api/v1/admin/listings/{publicId}/featured.
 * {@code featured = false} ignores {@code featuredUntil}.
 */
public record SetFeaturedRequest(
    boolean featured,
    @FutureOrPresent OffsetDateTime featuredUntil
) {}
```

### Task 12: AdminListingService.setFeatured()

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingService.java`

- [ ] **Step 1: Add dependency on FeaturedCache**

Add to the field block:

```java
private final com.slparcelauctions.backend.auction.featured.FeaturedCache featuredCache;
```

(Or import properly at top.)

- [ ] **Step 2: Add setFeatured method**

After `reinstate(...)`:

```java
@Transactional
public AdminListingRowDto setFeatured(UUID publicId,
                                      Long adminUserId,
                                      SetFeaturedRequest req) {
    Auction auction = resolveOrThrow(publicId);

    if (auction.getStatus() != AuctionStatus.ACTIVE) {
        throw new AdminListingStateException(
            "FEATURE_REQUIRES_ACTIVE_STATUS",
            "Cannot feature a listing in status " + auction.getStatus());
    }
    if (!req.featured() && req.featuredUntil() != null) {
        throw new AdminListingStateException(
            "FEATURED_UNTIL_REQUIRES_FEATURED_TRUE",
            "featuredUntil cannot be set when featured=false");
    }

    auction.setFeatured(req.featured());
    auction.setFeaturedUntil(req.featured() ? req.featuredUntil() : null);
    auctionRepo.save(auction);

    featuredCache.invalidate(
        com.slparcelauctions.backend.auction.featured.FeaturedCategory.FEATURED);

    adminActionService.record(
        adminUserId,
        req.featured() ? AdminActionType.FEATURE_LISTING
                       : AdminActionType.UNFEATURE_LISTING,
        AdminActionTargetType.LISTING,
        auction.getId(),
        null,
        SOURCE_METADATA);

    return queryRepo.findRowByPublicId(publicId)
        .orElseThrow(() -> new AdminListingStateException(
            "LISTING_NOT_FOUND", "Listing not found after write: " + publicId));
}
```

(Auction needs `setFeatured(boolean)` and `setFeaturedUntil(OffsetDateTime)` — Lombok `@Setter` already generates these from Task 1's field additions.)

The `queryRepo.findRowByPublicId(publicId)` is a new convenience method on the query repo — added in Task 14.

- [ ] **Step 3: Add `AuctionStatus` import** if not already present.

```java
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.admin.listings.dto.SetFeaturedRequest;
```

### Task 13: AdminListingController PATCH endpoint

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingController.java`

- [ ] **Step 1: Add imports**

```java
import org.springframework.web.bind.annotation.PatchMapping;
import com.slparcelauctions.backend.admin.listings.dto.SetFeaturedRequest;
```

- [ ] **Step 2: Add endpoint**

After existing action mappings:

```java
@PatchMapping("/{publicId}/featured")
public AdminListingRowDto setFeatured(
        @PathVariable UUID publicId,
        @RequestBody @Valid SetFeaturedRequest req,
        @AuthenticationPrincipal AuthPrincipal principal) {
    return service.setFeatured(publicId, principal.userId(), req);
}
```

(`AuthPrincipal#userId` is an existing method that returns the authenticated admin's `Long` user id; mirror the pattern from the existing action methods on this controller.)

- [ ] **Step 3: Run quick smoke**

Run: `./mvnw -pl . compile -q`
Expected: success.

### Task 14: AdminListingRowDto + AdminListingFilterParams + AdminListingQueryRepository

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/dto/AdminListingRowDto.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/dto/AdminListingFilterParams.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingQueryRepository.java`

- [ ] **Step 1: Add fields to `AdminListingRowDto`**

```java
public record AdminListingRowDto(
    UUID publicId,
    String title,
    UUID sellerPublicId,
    String sellerUsername,
    AuctionStatus status,
    boolean hasReserve,
    OffsetDateTime createdAt,
    Long startingBid,
    Long currentBid,
    Integer bidCount,
    Long saveCount,
    OffsetDateTime endsAt,
    String region,
    boolean isFeatured,
    OffsetDateTime featuredUntil
) {}
```

- [ ] **Step 2: Add `featured` to `AdminListingFilterParams`**

```java
public record AdminListingFilterParams(
    String search,
    List<AuctionStatus> statuses,
    Boolean hasReserve,
    Boolean featured
) {}
```

- [ ] **Step 3: Update query repository**

In `AdminListingQueryRepository.search`:

(a) Extend WHERE construction:

```java
if (params.featured() != null && params.featured()) {
    where.append(" AND a.is_featured = TRUE ");
    where.append(" AND (a.featured_until IS NULL OR a.featured_until > NOW()) ");
}
```

(b) Add the new SELECT cols:

```java
String selectSql = """
    SELECT
        a.public_id        AS public_id,
        a.title            AS title,
        u.public_id        AS seller_public_id,
        u.username         AS seller_username,
        a.status           AS status,
        (a.reserve_price IS NOT NULL) AS has_reserve,
        a.created_at       AS created_at,
        a.starting_bid     AS starting_bid,
        a.current_bid      AS current_bid,
        a.bid_count        AS bid_count,
        COALESCE(s.save_count, 0) AS save_count,
        a.ends_at          AS ends_at,
        ps.region_name     AS region_name,
        a.is_featured      AS is_featured,
        a.featured_until   AS featured_until
    FROM auctions a
    JOIN users u ON u.id = a.seller_id
    LEFT JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
    LEFT JOIN (
        SELECT auction_id, COUNT(*) AS save_count
          FROM saved_auctions
         GROUP BY auction_id
    ) s ON s.auction_id = a.id
    """ + where + buildOrderBy(pageable.getSort()) +
    " LIMIT :limit OFFSET :offset ";
```

(c) Update `mapRow`:

```java
private AdminListingRowDto mapRow(Object[] r) {
    return new AdminListingRowDto(
        (UUID) r[0],
        (String) r[1],
        (UUID) r[2],
        (String) r[3],
        AuctionStatus.valueOf((String) r[4]),
        (Boolean) r[5],
        toOffsetDateTime(r[6]),
        toLong(r[7]),
        toLong(r[8]),
        toInteger(r[9]),
        toLong(r[10]),
        toOffsetDateTime(r[11]),
        (String) r[12],
        (Boolean) r[13],
        toOffsetDateTime(r[14])
    );
}
```

(d) Add `findRowByPublicId` (new method, used by `setFeatured` to return the row after write):

```java
public Optional<AdminListingRowDto> findRowByPublicId(UUID publicId) {
    String sql = """
        SELECT
            a.public_id, a.title, u.public_id, u.username, a.status,
            (a.reserve_price IS NOT NULL),
            a.created_at, a.starting_bid, a.current_bid, a.bid_count,
            COALESCE(s.save_count, 0),
            a.ends_at, ps.region_name,
            a.is_featured, a.featured_until
        FROM auctions a
        JOIN users u ON u.id = a.seller_id
        LEFT JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
        LEFT JOIN (
            SELECT auction_id, COUNT(*) AS save_count
              FROM saved_auctions
             GROUP BY auction_id
        ) s ON s.auction_id = a.id
        WHERE a.public_id = :publicId
        """;
    Query q = em.createNativeQuery(sql);
    q.setParameter("publicId", publicId);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = q.getResultList();
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(mapRow(rows.get(0)));
}
```

(Add `import java.util.Optional;` if missing.)

- [ ] **Step 4: Update existing controller list endpoint to thread `featured` filter**

In `AdminListingController.list`:

```java
@RequestParam(required = false) Boolean featured,
```

then

```java
AdminListingFilterParams params = new AdminListingFilterParams(
    normalize(search),
    status == null ? List.of() : status,
    hasReserve,
    featured
);
```

Find each existing call site of the `AdminListingFilterParams` constructor in the codebase and add a `null` 4th arg if the caller doesn't filter on featured (keeps the change tight to the new field). Compile error will surface them; AdminListingService isn't a constructor caller (the controller is) so this is one site.

- [ ] **Step 5: Compile**

Run: `./mvnw -pl . compile -q`
Expected: success.

### Task 15: AdminListingFeaturedControllerIntegrationTest

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/admin/listings/AdminListingFeaturedControllerIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

Mirror the auth/seeding pattern of any other AdminListingController test. Spec acceptance criteria:

```java
package com.slparcelauctions.backend.admin.listings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
// shared seeder helper / token issuer — match the pattern used by other admin tests in this package

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminListingFeaturedControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    // existing test fixture / helper beans for token + auction seeding

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/listings/{id}/featured", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"featured\":true}"))
                .andExpect(status().isUnauthorized());
    }

    // 403 non-admin, 404 unknown publicId, 400 non-ACTIVE status,
    // 400 past featuredUntil, 400 featured=false + featuredUntil set,
    // 200 happy path mirroring existing admin test fixtures.
}
```

The exact seeder helper / admin-token method name varies by package convention — match what e.g. an existing `AdminWalletControllerIntegrationTest` (or similar) uses. Replicate, do not invent.

- [ ] **Step 2: Run**

Run: `./mvnw -pl . test -Dtest=AdminListingFeaturedControllerIntegrationTest -q`
Expected: PASS.

- [ ] **Step 3: Commit Tasks 10–15 as one block**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/ \
        backend/src/test/java/com/slparcelauctions/backend/admin/listings/AdminListingFeaturedControllerIntegrationTest.java
git commit -m "feat(admin): add PATCH /api/v1/admin/listings/{id}/featured

- SetFeaturedRequest DTO + AdminListingService.setFeatured
- New audit codes: FEATURE_LISTING, UNFEATURE_LISTING
- AdminListingRowDto + AdminListingQueryRepository expose isFeatured/featuredUntil
- AdminListingFilterParams + WHERE clause filter on featured=true
- Cache invalidation on FeaturedCategory.FEATURED after write"
```

---

## Phase 3: Frontend types + API

### Task 16: Frontend admin types

**Files:**
- Modify: `frontend/src/lib/admin/types.ts`

- [ ] **Step 1: Read the file first** to find the existing `AdminListingRow`, `AdminListingAction`, and `AdminListingsFilters` shapes.

- [ ] **Step 2: Extend types**

```ts
export type AdminListingAction =
  | "warn"
  | "suspend"
  | "cancel"
  | "reinstate"
  | "feature"
  | "unfeature";

export type AdminListingRow = {
  // ...existing fields...
  isFeatured: boolean;
  featuredUntil: string | null;
};

export type AdminListingsFilters = {
  // ...existing fields...
  featured?: boolean | null;
};
```

(Preserve existing fields — only the listed properties are added.)

### Task 17: Frontend API call + mutation hook

**Files:**
- Modify: `frontend/src/lib/admin/api.ts` (or wherever existing admin API calls live — confirm via grep)
- Modify: `frontend/src/hooks/admin/useAdminListings.ts`

- [ ] **Step 1: Add API call**

```ts
export async function setListingFeatured(
  publicId: string,
  body: { featured: boolean; featuredUntil: string | null },
): Promise<AdminListingRow> {
  return api.patch<AdminListingRow>(
    `/api/v1/admin/listings/${publicId}/featured`,
    body,
  );
}
```

(Match the existing convention for `api.patch` / `api.post` from sibling functions.)

- [ ] **Step 2: Add mutation hook**

```ts
export function useSetFeatured() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: {
      publicId: string;
      body: { featured: boolean; featuredUntil: string | null };
    }) => setListingFeatured(args.publicId, args.body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin", "listings"] });
    },
  });
}
```

- [ ] **Step 3: Don't commit; bundle with frontend admin UI tasks.**

### Task 18: Frontend lib/api/auctions-search rename + URL

**Files:**
- Modify: `frontend/src/lib/api/auctions-search.ts`

- [ ] **Step 1: Replace `FeaturedCategory` type and URL**

```ts
export type FeaturedCategory = "featured" | "ending-soon" | "trending";

export function fetchFeatured(
  category: FeaturedCategory,
): Promise<{ content: AuctionSearchResultDto[] }> {
  return api.get(`/api/v1/auctions/rails/${category}`);
}
```

(`just-listed` and `most-active` are removed; the homepage will be updated in Task 22.)

---

## Phase 4: Frontend admin UI

### Task 19: RowActionMenu — feature/unfeature

**Files:**
- Modify: `frontend/src/components/admin/listings/RowActionMenu.tsx`

- [ ] **Step 1: Update Props**

```ts
type Props = {
  status: AuctionStatus;
  isFeatured: boolean;
  onPick: (action: AdminListingAction) => void;
};
```

- [ ] **Step 2: Add gates + menu entries**

In `gateFor`:

```ts
case "feature":
  if (status !== "ACTIVE")
    return { allowed: false, reason: "Only ACTIVE listings can be featured" };
  return { allowed: true };
case "unfeature":
  return { allowed: true };
```

In the `ACTIONS` array:

```ts
{ key: "feature",   label: "Feature listing",   destructive: false },
{ key: "unfeature", label: "Unfeature listing", destructive: false },
```

In the render block, conditionally hide the entry that doesn't match the row's flag:

```tsx
{ACTIONS.filter(a => {
   if (a.key === "feature")   return !isFeatured;
   if (a.key === "unfeature") return  isFeatured;
   return true;
 }).map((action) => { ... })}
```

### Task 20: ListingActionModal — feature variant

**Files:**
- Modify: `frontend/src/components/admin/listings/ListingActionModal.tsx`

- [ ] **Step 1: Add CONFIG entries**

```ts
const CONFIG: Record<AdminListingAction, ActionConfig> = {
  // ...existing entries...
  feature: {
    title: "Feature listing",
    primaryLabel: "Feature listing",
    variant: "primary",
    body: "The listing will appear on the homepage Featured rail. Optional expiry — leave blank for permanent.",
    placeholder: "",
  },
  unfeature: {
    title: "Unfeature listing",
    primaryLabel: "Unfeature listing",
    variant: "destructive",
    body: "The listing will be removed from the homepage Featured rail.",
    placeholder: "",
  },
};
```

- [ ] **Step 2: Branch the form**

For `feature`/`unfeature` actions, replace the notes textarea with a datetime-local input (feature only) and skip the notes validation:

```tsx
const isFeatureMode = action === "feature" || action === "unfeature";
const setFeatured = useSetFeatured();

const [featuredUntil, setFeaturedUntil] = useState<string>("");

// Reset when modal opens:
useEffect(() => {
  if (!open) return;
  setNotes("");
  setFeaturedUntil("");
}, [open]);

const mutation =
  action === "warn" ? warn :
  action === "suspend" ? suspend :
  action === "cancel" ? cancel :
  action === "reinstate" ? reinstate :
  setFeatured;

function handleSubmit(e: React.FormEvent) {
  e.preventDefault();
  if (isFeatureMode) {
    setFeatured.mutate({
      publicId: row.publicId,
      body: {
        featured: action === "feature",
        featuredUntil: action === "feature" && featuredUntil
          ? new Date(featuredUntil).toISOString()
          : null,
      },
    }, { onSuccess: onClose });
    return;
  }
  if (!canSubmit) return;
  mutation.mutate(
    { publicId: row.publicId, body: { notes: trimmed } },
    { onSuccess: onClose },
  );
}
```

In the JSX, when `isFeatureMode`, render only:

```tsx
{action === "feature" && (
  <div className="flex flex-col gap-1">
    <label htmlFor="featured-until" className="text-xs font-medium text-fg">
      Featured until (optional — blank = permanent)
    </label>
    <input
      id="featured-until"
      type="datetime-local"
      value={featuredUntil}
      onChange={(e) => setFeaturedUntil(e.target.value)}
      data-testid="featured-until-input"
      className="rounded-lg bg-bg-muted px-4 py-3 text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
    />
  </div>
)}
```

The `unfeature` variant has no input — just confirm + submit. `canSubmit` for feature/unfeature collapses to `!setFeatured.isPending`.

### Task 21: AdminListingsTable — Featured badge column + plumb new prop

**Files:**
- Modify: `frontend/src/components/admin/listings/AdminListingsTable.tsx`

- [ ] **Step 1: Add column to COLUMNS**

```ts
{ key: "featured", label: "Featured", sortable: false, align: "left" },
```

(Position before `actions`.)

- [ ] **Step 2: Render the cell**

In the row map, add a cell rendering a chip when `row.isFeatured`:

```tsx
<td className="py-2.5 px-3 text-[12px]">
  {row.isFeatured && (
    <span className="inline-flex items-center px-2 py-0.5 rounded-full bg-brand/10 text-brand text-[11px] font-medium">
      Featured
    </span>
  )}
</td>
```

- [ ] **Step 3: Pass `isFeatured` to RowActionMenu**

```tsx
<RowActionMenu
  status={row.status}
  isFeatured={row.isFeatured}
  onPick={(action) => { ... }}
/>
```

### Task 22: PresetChips + AdminListingsPage featured filter param

**Files:**
- Modify: `frontend/src/components/admin/listings/PresetChips.tsx`
- Modify: `frontend/src/components/admin/listings/AdminListingsPage.tsx`
- Modify: `frontend/src/app/admin/listings/page.tsx`

- [ ] **Step 1: Extend Preset type**

```ts
export type Preset = {
  key: string;
  label: string;
  statuses: AuctionStatus[];
  sort: AdminListingsFilters["sort"];
  featured?: boolean;
};
```

`isActive` extends:

```ts
function isActive(p: Preset, f: AdminListingsFilters): boolean {
  // existing checks
  const featuredMatches = (p.featured ?? false) === (f.featured ?? false);
  return featuredMatches && /* prior logic */;
}
```

- [ ] **Step 2: Wire `featured` URL param into AdminListingsPage**

Read:

```ts
const urlFeatured: boolean | null = (() => {
  const raw = searchParams?.get("featured");
  if (raw === "true") return true;
  return null;
})();
```

Add to `filters`:

```ts
const filters: AdminListingsFilters = {
  // ...
  featured: urlFeatured ?? undefined,
};
```

Add to `navigate` updates type and writes:

```ts
function navigate(updates: {
  // ...
  featured?: boolean | null | undefined;
}) {
  // ...
  const nextFeatured = "featured" in updates ? updates.featured : urlFeatured;
  if (nextFeatured === true) params.set("featured", "true");
  // null/undefined drops the param
}
```

Update `handlePresetPick`:

```ts
function handlePresetPick(preset: Preset | null) {
  if (preset) {
    navigate({
      statuses: lockedStatuses ?? preset.statuses,
      sort: preset.sort,
      featured: preset.featured ?? null,
      page: 0,
    });
  } else {
    navigate({
      statuses: lockedStatuses ?? defaultStatuses,
      sort: defaultSort,
      featured: null,
      page: 0,
    });
  }
}
```

- [ ] **Step 3: Add the preset to admin/listings/page.tsx**

In the `PRESETS` array:

```ts
{
  key: "featured",
  label: "Featured",
  statuses: ["ACTIVE"],
  sort: { column: "endsAt", direction: "asc" },
  featured: true,
},
```

- [ ] **Step 4: Plumb featured to the API call**

In `useAdminListingsList` (or its API helper) — append `featured=true` to the query string when `filters.featured === true`. Mirror how `hasReserve` is threaded.

- [ ] **Step 5: Run frontend tests + verify**

```bash
cd frontend && npm test -- --run admin
npm run verify
```

Expected: PASS (no admin tests assert on Preset shape directly; the new chip is additive).

- [ ] **Step 6: Commit Tasks 16–22 as one block**

```bash
git add frontend/src/lib/admin/ \
        frontend/src/hooks/admin/useAdminListings.ts \
        frontend/src/components/admin/listings/ \
        frontend/src/app/admin/listings/page.tsx \
        frontend/src/lib/api/auctions-search.ts
git commit -m "feat(admin): per-listing Feature/Unfeature row action + Featured filter"
```

---

## Phase 5: Frontend homepage

### Task 23: Update MSW handlers

**Files:**
- Modify: `frontend/src/test/msw/handlers.ts`

- [ ] **Step 1: Replace rail handler URLs**

Find the three default handlers for `/api/v1/auctions/featured/{ending-soon,just-listed,most-active}` and replace with `/api/v1/auctions/rails/{ending-soon,featured,trending}` returning `{ content: [] }`. Also add the admin set-featured PATCH handler returning a stub `AdminListingRow`.

### Task 24: Rewrite app/page.tsx

**Files:**
- Modify: `frontend/src/app/page.tsx`

- [ ] **Step 1: Replace body**

```tsx
import { Hero } from "@/components/marketing/Hero";
import { FeaturedRow } from "@/components/marketing/FeaturedRow";
import { TrustStrip } from "@/components/marketing/TrustStrip";
import { fetchFeatured } from "@/lib/api/auctions-search";

export const dynamic = "force-dynamic";

export default async function HomePage() {
  const [featured, endingSoon, trending] = await Promise.allSettled([
    fetchFeatured("featured"),
    fetchFeatured("ending-soon"),
    fetchFeatured("trending"),
  ]);

  const heroFeatured =
    featured.status === "fulfilled" ? featured.value.content.slice(0, 3) : [];

  // Per issue #155: hide Ending Soon when fulfilled-empty. A rejected
  // fetch still surfaces the unavailable placeholder via FeaturedRow.
  const hideEndingSoon =
    endingSoon.status === "fulfilled" && endingSoon.value.content.length === 0;

  return (
    <>
      <Hero featured={heroFeatured} />
      <FeaturedRow
        title="Featured"
        sub="Hand-picked premium parcels."
        sortLink="/browse"
        result={featured}
        emptyMessage="No featured listings right now."
        columns={4}
      />
      <TrustStrip />
      {hideEndingSoon ? null : (
        <FeaturedRow
          title="Ending soon"
          sub="Auctions closing in the next few hours."
          sortLink="/browse?sort=ending_soonest"
          result={endingSoon}
          columns={4}
        />
      )}
      <FeaturedRow
        title="Trending"
        sub="Most-watched parcels right now."
        sortLink="/browse?sort=most_bids"
        result={trending}
        emptyMessage="No active bidding to highlight right now."
        columns={4}
      />
    </>
  );
}
```

### Task 25: Rewrite page.integration.test.tsx

**Files:**
- Modify: `frontend/src/app/page.integration.test.tsx`

- [ ] **Step 1: Replace tests**

```tsx
describe("HomePage server component", () => {
  it("renders all three rails when every endpoint succeeds", async () => {
    server.use(
      http.get("*/api/v1/auctions/rails/featured", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000065", title: "Featured Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/rails/ending-soon", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000066", title: "Ending Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/rails/trending", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000067", title: "Trending Parcel" })],
        }),
      ),
    );

    const page = await HomePage();
    renderWithProviders(page);

    expect(screen.getByRole("heading", { name: "Featured" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Ending soon" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Trending" })).toBeInTheDocument();

    // Featured Parcel appears twice — Hero stack + Featured rail.
    expect(screen.getAllByText("Featured Parcel").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Ending Parcel")).toBeInTheDocument();
    expect(screen.getByText("Trending Parcel")).toBeInTheDocument();

    // Old titles must be gone.
    expect(screen.queryByRole("heading", { name: "Featured this week" })).toBeNull();
    expect(screen.queryByRole("heading", { name: "Trending across regions" })).toBeNull();
  });

  it("hides Ending Soon when its content is empty", async () => {
    server.use(
      http.get("*/api/v1/auctions/rails/featured", () =>
        HttpResponse.json({ content: [sampleListing({ title: "Featured" })] })),
      http.get("*/api/v1/auctions/rails/ending-soon", () =>
        HttpResponse.json({ content: [] })),
      http.get("*/api/v1/auctions/rails/trending", () =>
        HttpResponse.json({ content: [sampleListing({ title: "Trending" })] })),
    );

    const page = await HomePage();
    renderWithProviders(page);

    expect(screen.queryByRole("heading", { name: "Ending soon" })).toBeNull();
    expect(screen.getByRole("heading", { name: "Featured" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Trending" })).toBeInTheDocument();
  });

  it("renders the Ending Soon unavailable placeholder when its fetch is rejected", async () => {
    server.use(
      http.get("*/api/v1/auctions/rails/featured", () =>
        HttpResponse.json({ content: [sampleListing({ title: "Featured" })] })),
      http.get("*/api/v1/auctions/rails/ending-soon",
        () => new HttpResponse(null, { status: 500 })),
      http.get("*/api/v1/auctions/rails/trending", () =>
        HttpResponse.json({ content: [sampleListing({ title: "Trending" })] })),
    );

    const page = await HomePage();
    renderWithProviders(page);

    // Section header is still present (Ending Soon only hides on empty-fulfilled, not on reject)
    expect(screen.getByRole("heading", { name: "Ending soon" })).toBeInTheDocument();
    expect(screen.getByText(/ending soon auctions are temporarily unavailable/i)).toBeInTheDocument();
  });

  it("isolates other rails when only one fails", async () => {
    server.use(
      http.get("*/api/v1/auctions/rails/featured",
        () => new HttpResponse(null, { status: 500 })),
      http.get("*/api/v1/auctions/rails/ending-soon", () =>
        HttpResponse.json({ content: [sampleListing({ title: "Ending" })] })),
      http.get("*/api/v1/auctions/rails/trending", () =>
        HttpResponse.json({ content: [sampleListing({ title: "Trending" })] })),
    );

    const page = await HomePage();
    renderWithProviders(page);

    expect(screen.getByText(/featured auctions are temporarily unavailable/i)).toBeInTheDocument();
    expect(screen.getByText("Ending")).toBeInTheDocument();
    expect(screen.getByText("Trending")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run tests + verify**

```bash
cd frontend && npm test -- --run page.integration && npm run verify
```

Expected: PASS.

- [ ] **Step 3: Commit Tasks 23–25 as one block**

```bash
git add frontend/src/app/page.tsx \
        frontend/src/app/page.integration.test.tsx \
        frontend/src/test/msw/handlers.ts
git commit -m "feat(home): reorder homepage, rename rails, hide empty Ending Soon

- Order: Hero -> Featured -> TrustStrip -> EndingSoon -> Trending
- Hero stack pulls from Featured rail (was Ending Soon)
- 'Featured this week' -> 'Featured', 'Trending across regions' -> 'Trending'
- Hide Ending Soon section when content is empty (#155)"
```

---

## Phase 6: Docs + Postman + PRs

### Task 26: README sweep

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Find any homepage / rail / "Featured this week" / "Trending across regions" mentions and update them**

Run: `grep -n "Featured this week\|Trending across regions\|just-listed\|most-active\|/auctions/featured" README.md`

Replace any hits with the new naming and `/api/v1/auctions/rails/*` paths. Add a brief note that admin can feature listings via the listings table row action.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs(readme): homepage rail naming + admin Featured action"
```

### Task 27: Postman collection

- [ ] **Step 1: In the SLPA Postman collection (workspace `SLPA`, collection `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`)**:

  - Update the three rail GET requests to use `/api/v1/auctions/rails/{featured,ending-soon,trending}` and rename them to `Get featured`, `Get ending soon`, `Get trending`.
  - Under the existing Admin folder, create a new request `Set listing featured` with method `PATCH`, URL `{{baseUrl}}/api/v1/admin/listings/{{auctionPublicId}}/featured`, body raw JSON `{ "featured": true, "featuredUntil": null }`. Authorization inherits the admin token from the folder.

This step is performed via the Postman MCP tools (`mcp__postman__updateCollectionRequest`, `mcp__postman__createCollectionRequest`).

### Task 28: PR feat → dev

- [ ] **Step 1: Push branch + open PR**

```bash
git push origin feat/homepage-featured-trending
gh pr create --base dev --title "Homepage Featured + Trending: rail rename, layout reorder, admin toggle" \
    --body "$(cat <<'EOF'
## Summary
- Featured rail (target 4) with admin curation + auto-fill (`current_bid` DESC)
- Trending rail formula: `bids*2 + saves` over 24h
- Layout: Hero -> Featured -> TrustStrip -> Ending Soon -> Trending
- Hero stack now pulls from Featured rail
- Hide Ending Soon section when fulfilled-empty (#155)
- Admin row action `Feature listing` / `Unfeature listing` + Featured badge column + filter chip
- Resource path: `/api/v1/auctions/featured/*` -> `/api/v1/auctions/rails/*`

Resolves #173, #155. Foundation for #230.

Spec: `docs/superpowers/specs/2026-05-09-homepage-featured-trending-design.md`.

## Test plan
- [ ] `./mvnw test` green
- [ ] `cd frontend && npm test && npm run verify` green
- [ ] Manual: log in as admin, Feature an ACTIVE listing, see it on the homepage
- [ ] Manual: deploy preview homepage shows new order + titles
EOF
)"
```

- [ ] **Step 2: Wait for CI green**

```bash
gh pr checks --watch
```

- [ ] **Step 3: Merge PR**

```bash
gh pr merge --merge --delete-branch
```

### Task 29: PR dev → main

- [ ] **Step 1: Open dev → main PR**

```bash
gh pr create --base main --head dev --title "Release: Homepage Featured + Trending" \
    --body "Includes: PR #<dev-pr-num>. Spec at docs/superpowers/specs/2026-05-09-homepage-featured-trending-design.md."
```

- [ ] **Step 2: Wait for CI**

```bash
gh pr checks --watch
```

- [ ] **Step 3: Merge** (user has authorized autonomous merge for this task)

```bash
gh pr merge --merge
```

- [ ] **Step 4: Confirm prod deploys started**

```bash
gh run list --branch main --workflow 'deploy backend' --limit 1
aws --profile slpa-prod amplify list-jobs --app-id dil6fhehya5jf --branch-name main --max-items 1
```

Expected: a backend run in progress and an Amplify job running.

---

## Done definition

- All `./mvnw test` green
- All `cd frontend && npm test` green; `npm run verify` clean
- PR #<dev-pr> merged to `dev`
- PR #<main-pr> merged to `main`
- Backend deploy + Amplify build both kicked off
