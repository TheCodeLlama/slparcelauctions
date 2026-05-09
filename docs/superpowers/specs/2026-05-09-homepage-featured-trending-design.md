# Homepage Featured + Trending Design

**Date:** 2026-05-09
**Resolves:** #173 (Homepage view), #155 (Show featured above ending soon)
**Foundation for (deferred):** #230 (Featured listings paid add-on)

## Goal

Turn the homepage's three rails into the shape they actually need to be:

1. **Featured** becomes a real concept — admin-curated, with auto-fill when curated supply is short, sized for paid-slot inventory.
2. **Trending** becomes a real metric — bids and saves over a 24h window, weighted to favor committed intent.
3. **Layout** reorders so Featured sits at the top, with the hero stack drawing from the same paid-slot pool.

The data model is forward-compatible with the paid Featured add-on (issue #230). The buyer-facing checkout, billing audit, and end-of-listing unfeature transition are explicitly out of scope here — this spec ships the read-side and admin-side foundation only.

## Architecture

Three coupled changes:

1. **Featured rail.** New `is_featured` + `featured_until` columns on `auctions`. The existing `FeaturedCategory.JUST_LISTED` enum value is renamed `FEATURED` and its repository query is replaced with a curated + auto-fill blend. A new `PATCH /api/v1/admin/auctions/{publicId}/featured` admin endpoint toggles the flag.

2. **Trending rail.** `FeaturedCategory.MOST_ACTIVE` is renamed `TRENDING`. Its repository query becomes a 24h weighted score: `(bids in last 24h) * 2 + (saves in last 24h)`.

3. **Layout reorder.** `frontend/src/app/page.tsx` rearranges the section order to: Hero → Featured → TrustStrip → Ending Soon → Trending. The Hero's right-side stack pulls from the new Featured rail (not Ending Soon). The Ending Soon section is hidden when its content is empty (not rejected — empty).

The resource path also moves from `/api/v1/auctions/featured/<category>` to `/api/v1/auctions/rails/<category>` so the URL doesn't read `featured/featured`. All three rail URLs change in the same deploy. No external clients depend on the old paths.

**Reused, unchanged:** `FeaturedRow` component, `FeaturedService` shape, `FeaturedCache` keying strategy, `Cache-Control: public, max-age=60` header, `MeterRegistry` Timer pattern. The new admin endpoint hangs off the existing admin auction module conventions.

## Data Model

New migration `backend/src/main/resources/db/migration/V23__featured_listings.sql`:

```sql
ALTER TABLE auctions
  ADD COLUMN is_featured       BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN featured_until    TIMESTAMPTZ NULL;

CREATE INDEX ix_auctions_featured_active
    ON auctions (ends_at ASC, id ASC)
    WHERE is_featured = TRUE AND status = 'ACTIVE';
```

The partial index covers exactly the Featured rail's hot path (active featured rows ordered by `ends_at ASC`, top 4) and stays cheap because the row count is tiny.

`featured_until = NULL` means "permanent until admin toggles off" — useful for one-off admin highlights and the launch-time pre-paid-product window. The paid-add-on path will always set a real timestamp.

Auction entity (`Auction.java`) gains:

```java
@Column(name = "is_featured", nullable = false)
private boolean isFeatured = false;

@Column(name = "featured_until")
private OffsetDateTime featuredUntil;
```

No changes to `saved_auctions`, `bids`, or any read-side projection. The trending query reads `bids.created_at` and `saved_auctions.saved_at` directly.

## Backend

### `FeaturedCategory.java`

```java
public enum FeaturedCategory {
    FEATURED("slpa:featured:rail:featured"),       // was JUST_LISTED
    ENDING_SOON("slpa:featured:rail:ending-soon"),
    TRENDING("slpa:featured:rail:trending");        // was MOST_ACTIVE

    private final String cacheKey;
    FeaturedCategory(String cacheKey) { this.cacheKey = cacheKey; }
    public String cacheKey() { return cacheKey; }
}
```

Cache keys change. Old keys (`slpa:featured:just-listed` etc.) become orphan and TTL out within 60s of deploy. No active eviction needed.

### `FeaturedRepository.java`

The Featured query blends curated + auto-fill in a single statement. Curated rows are sorted by `ends_at ASC` and capped at 4. If curated supply is short, the remaining slots are backfilled by `current_bid DESC`, with curated rows always rendering before fill rows in the response (premium-slot semantics — paying customers' rows never get demoted under a non-paying organic fill).

```java
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
      SELECT * FROM (SELECT * FROM fill LIMIT (4 - (SELECT COUNT(*) FROM curated))) f
    ) blended
    ORDER BY bucket ASC, sort_key ASC NULLS LAST, current_bid DESC, id DESC
    LIMIT 4
    """, nativeQuery = true)
List<Auction> featured();
```

The `bucket` column makes curated-first ordering explicit. `sort_key` carries `ends_at` for curated rows and NULL for fill rows, so PostgreSQL's `NULLS LAST` lands fill below curated within the bucket order. Within the fill bucket, `current_bid DESC` orders premium slots higher.

```java
@Query(value = """
    SELECT a.* FROM auctions a
    WHERE a.status = 'ACTIVE' AND a.ends_at > NOW()
    ORDER BY a.ends_at ASC, a.id ASC
    LIMIT 6
    """, nativeQuery = true)
List<Auction> endingSoon();   // unchanged
```

The Trending query computes a weighted score over a 24h window: bids count for 2 points each, saves for 1.

```java
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
```

### `FeaturedService.get()`

The switch arms become:

```java
List<Auction> rows = switch (category) {
    case FEATURED   -> featuredRepo.featured();
    case ENDING_SOON -> featuredRepo.endingSoon();
    case TRENDING    -> featuredRepo.trending();
};
```

### `FeaturedController.java`

The resource path moves to `/api/v1/auctions/rails`:

```java
@RestController
@RequestMapping("/api/v1/auctions/rails")
public class FeaturedController {
    @GetMapping("/featured")    public ... featured()    { ... }
    @GetMapping("/ending-soon") public ... endingSoon()  { ... }
    @GetMapping("/trending")    public ... trending()    { ... }
}
```

The old `/api/v1/auctions/featured/*` paths are deleted. `SecurityConfig.java` `permitAll` matcher updates to `/api/v1/auctions/rails/**`.

### Admin endpoint

The existing `AdminListingController` (`backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingController.java`) is the natural home — it already has class-level `@PreAuthorize("hasRole('ADMIN')")` and a `@RequestMapping("/api/v1/admin/listings")` base path. Add the new method there; do **not** create a separate `AdminFeaturedController`.

```java
@PatchMapping("/{publicId}/featured")
public AdminListingRowDto setFeatured(
        @PathVariable UUID publicId,
        @RequestBody @Valid SetFeaturedRequest req,
        @AuthenticationPrincipal AuthPrincipal principal) {
    return service.setFeatured(publicId, req, principal);
}

public record SetFeaturedRequest(
    boolean featured,
    @FutureOrPresent OffsetDateTime featuredUntil  // null = permanent
) { }
```

The wire URL is `PATCH /api/v1/admin/listings/{publicId}/featured`.

`AdminListingService.setFeatured()` handles the column write, cache invalidation, and DTO conversion. Returns the same `AdminListingRowDto` shape the list endpoint already uses, with the new `isFeatured` and `featuredUntil` fields populated. The frontend uses the response to refresh the row in place.

`AdminListingRowDto` gains two fields:

```java
boolean isFeatured;
OffsetDateTime featuredUntil;  // null when not featured or no expiry
```

`AdminListingFilterParams` gains an optional `Boolean featured` (mirroring the existing `Boolean hasReserve` pattern) so the admin listings list can filter to currently-featured rows for the new preset chip. The query repository (`AdminListingQueryRepository`) extends its WHERE clause with `AND a.is_featured = TRUE AND (a.featured_until IS NULL OR a.featured_until > NOW())` when the param is `TRUE`; ignored when `null` or `FALSE`.

After committing the column write, the service evicts the FEATURED cache key (`featuredCache.invalidate(FeaturedCategory.FEATURED)`) so admin toggles surface immediately rather than waiting up to 60s for the TTL. Trending and Ending Soon caches are not affected and not evicted.

If `FeaturedCache` doesn't already have an `invalidate(category)` method, add one (single Redis `DEL`). Errors from the Redis client are swallowed and logged WARN — admin write does not fail because of a cache invalidation failure; the 60s TTL self-heals.

Admin writes record a Counter `slpa.admin.featured.toggle` tagged `value=on|off`.

## Frontend

### `frontend/src/lib/api/auctions-search.ts`

```ts
export type FeaturedCategory = "featured" | "ending-soon" | "trending";

export function fetchFeatured(category: FeaturedCategory):
  Promise<{ content: AuctionSearchResultDto[] }> {
  return api.get(`/api/v1/auctions/rails/${category}`);
}
```

### `frontend/src/app/page.tsx`

The homepage server component is fully rewritten (still `force-dynamic`):

```tsx
export const dynamic = "force-dynamic";

export default async function HomePage() {
  const [featured, endingSoon, trending] = await Promise.allSettled([
    fetchFeatured("featured"),
    fetchFeatured("ending-soon"),
    fetchFeatured("trending"),
  ]);

  const heroFeatured =
    featured.status === "fulfilled" ? featured.value.content.slice(0, 3) : [];

  // Per issue #155, the Ending Soon section is hidden entirely when it has
  // zero ACTIVE rows. A rejected fetch still renders the "unavailable"
  // placeholder via FeaturedRow — only the empty-but-fulfilled case is
  // suppressed. Featured and Trending still render their empty-state copy.
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

The order matches the approved design: Hero → Featured → TrustStrip → Ending Soon → Trending. Featured uses `columns={4}`. The hide-when-empty rule applies only to Ending Soon (issue #155 explicit); Featured shows its empty-state copy in dev/launch, Trending shows its empty-state copy when there's no active bidding to highlight.

### `Hero.tsx` / `HeroFeaturedStack.tsx`

No code changes needed. The prop signature is already `featured: AuctionSearchResultDto[]`; only the `page.tsx` source feeding it changes.

### `FeaturedRow.tsx`

No code changes. Title is a prop.

### Admin UI surface

Reuses the existing `RowActionMenu` + `ListingActionModal` per-row pattern in `frontend/src/components/admin/listings/`:

- `AdminListingAction` type adds `"feature" | "unfeature"`.
- `RowActionMenu` adds the two entries:
  - `feature` (label "Feature listing") — gated to `status === "ACTIVE"` only.
  - `unfeature` (label "Unfeature listing") — visible only when current row's `isFeatured === true`.
- `ListingActionModal` adds a `feature` form variant with one optional `featuredUntil` input (datetime-local; blank = permanent).
- Backend admin DTO (`AdminListingRowDto`) gains `isFeatured: boolean` and `featuredUntil: string | null`. The table renders a "Featured" badge column.
- Action handler calls `PATCH /api/v1/admin/listings/{publicId}/featured` with `{ featured: true, featuredUntil }` or `{ featured: false }`.
- Add a `featured` preset chip on the existing `AdminListingsRoute` filtering the table to `isFeatured=true`. Costs ~10 lines, completes the admin surface.

No new admin route, no per-auction admin detail page.

## Caching, Invalidation, Observability

**TTL:** unchanged. Redis cache 60s; `Cache-Control: public, max-age=60` headers 60s.

**Cache key migration:** old keys (`slpa:featured:just-listed`, `slpa:featured:most-active`, `slpa:featured:ending-soon`) become orphan post-deploy and fall out within 60s. No `redis-cli DEL` step needed.

**Featured rail invalidation:** the admin write calls `featuredCache.invalidate(FeaturedCategory.FEATURED)` after column commit. Trending and Ending Soon caches are not affected; not evicted on admin Featured writes.

**No invalidation on bid or save events** (Trending). Per-event invalidation would amplify write traffic to Redis for a 60s TTL that already self-heals. Trending tolerates 60s of staleness — that's the point.

**Observability:** existing `slpa.featured.<cat>.duration` Timer pattern; cardinality changes from `{ending_soon, just_listed, most_active}` to `{ending_soon, featured, trending}`. New Counter `slpa.admin.featured.toggle{value=on|off}` on admin writes.

## Error Handling

### Admin endpoint (`PATCH /api/v1/admin/auctions/{publicId}/featured`)

| Failure | Response |
|---|---|
| Auction not found by publicId | 404 via existing `AuctionNotFoundException` handler |
| Auction not in `ACTIVE` status | 400 with code `FEATURE_REQUIRES_ACTIVE_STATUS` |
| `featuredUntil` in the past | 400 with code `FEATURED_UNTIL_MUST_BE_FUTURE` (Bean Validation `@FutureOrPresent`) |
| `featured = false` with `featuredUntil` populated | 400 with code `FEATURED_UNTIL_REQUIRES_FEATURED_TRUE` |
| Caller not admin | 403 (inherited from `AdminListingController`'s class-level `@PreAuthorize("hasRole('ADMIN')")`) |

`featuredUntil = null` is accepted (means permanent until admin toggles off). The frontend row-action menu already gates by status, so `FEATURE_REQUIRES_ACTIVE_STATUS` is defense-in-depth against direct API hits.

### Rail query failures (DB down, query timeout)

`FeaturedService.get()` propagates the exception. `FeaturedController` does not catch. `page.tsx` uses `Promise.allSettled` so a 5xx on one rail leaves the other two intact; the failed rail renders the existing `featured-row-unavailable` placeholder via `FeaturedRow`'s rejected branch.

### Cache invalidation failure (Redis down on admin write)

`FeaturedCache.invalidate()` swallows Redis errors and logs WARN. Admin write succeeds. The 60s TTL self-heals when Redis recovers. Worst case: 60s of stale row.

### Empty rails

- No curated featured + no ACTIVE auctions → query returns 0 rows → `FeaturedRow` renders `emptyMessage="No featured listings right now."` Pre-launch / dev environments hit this naturally.
- Empty Ending Soon (fulfilled with 0 rows) → section omitted from render entirely (issue #155).
- Hero stack with empty `featured` array → `HeroFeaturedStack` already handles its own empty state.

### `featured_until` expiry semantics

The rail query filters `featured_until IS NULL OR featured_until > NOW()`. No background job needed — expired rows simply stop matching. The `is_featured = TRUE` flag stays `TRUE` after expiry; admin can re-extend by setting a new `featured_until` without re-toggling. Optional housekeeping (a nightly job that flips `is_featured = FALSE` for expired rows) is deferred — query-time filtering is sufficient.

### Trending query degradation

`slpa.featured.trending.duration` Timer is the canary. If P95 climbs as the row count grows, candidate mitigations are a `bids(auction_id, created_at)` covering index, a materialized view, or a denormalized score column on `auctions`. Not pre-emptively built.

## Testing

### Backend

`FeaturedRepositoryTest` (existing or new):

- `featured()` with 0 curated, ≥4 ACTIVE → returns 4 ordered by `current_bid DESC`.
- `featured()` with 2 curated, ≥4 ACTIVE → returns 2 curated (`ends_at ASC`) + 2 fill (`current_bid DESC`); curated rows render before fill regardless of price.
- `featured()` with 6 curated → returns 4 with the soonest `ends_at`; the other 2 are excluded.
- `featured()` with `featured_until` in the past → row excluded.
- `featured()` with `featured_until` NULL → row included.
- `featured()` excludes ENDED, EXPIRED, CANCELLED, SUSPENDED auctions even when flagged.
- `trending()` — score formula: 1 bid in last 24h = 2 pt; 1 save in last 24h = 1 pt. Bids and saves outside the 24h window do not count.
- `trending()` — ties broken by `ends_at ASC`, then `id DESC`.

`FeaturedServiceTest`:

- Switch arms route to the right repo method per category.
- Cache hit returns cached without calling the repo.
- Timer registry receives the `slpa.featured.<cat>.duration` sample on miss path.

`AdminListingControllerTest` (extending the existing test class — Featured method):

- 403 without admin role.
- 404 for unknown `publicId`.
- 400 with `FEATURE_REQUIRES_ACTIVE_STATUS` when auction not ACTIVE.
- 400 with `FEATURED_UNTIL_MUST_BE_FUTURE` for past timestamp.
- 400 with `FEATURED_UNTIL_REQUIRES_FEATURED_TRUE` when `featured=false` and `featuredUntil` set.
- Happy path: column writes commit, cache invalidates, response carries updated row.

`FeaturedControllerIntegrationTest` (existing): update for the new URL path `/api/v1/auctions/rails/*` and the renamed categories.

### Frontend

`page.integration.test.tsx` (existing) — re-record fixtures and assert:

- Section render order: Featured before Ending Soon before Trending; TrustStrip between Featured and Ending Soon.
- Hero stack rendered with the `featured` rail's first 3 listings (not Ending Soon's).
- Section titles "Featured" and "Trending" present; "Featured this week" and "Trending across regions" absent.
- Ending Soon section hidden when its content array is empty.
- Ending Soon `featured-row-unavailable` placeholder still renders when its fetch is rejected (separate test case).

`FeaturedRow.test.tsx` (existing) — title-agnostic; verify still green.

`AdminListingsTable.test.tsx` (existing or new) — Featured badge column renders for `isFeatured: true` rows.

`RowActionMenu.test.tsx` (existing or new) — `feature` action visible+enabled for ACTIVE rows; `unfeature` visible only when `isFeatured: true`.

`ListingActionModal.test.tsx` (existing or new) — `feature` variant shows `featuredUntil` input; submitting empty posts `{ featured: true, featuredUntil: null }`; submitting a future datetime posts the ISO string.

MSW handlers — replace `/api/v1/auctions/featured/*` with `/api/v1/auctions/rails/*`; new admin handler for `PATCH /api/v1/admin/auctions/:publicId/featured`.

`npm run verify` guards run unchanged. No new color tokens, dark variants, or inline styles introduced.

### Postman

Mirror in the same task:

- New "Set featured" request under the existing Admin folder, `PATCH /api/v1/admin/listings/{{auctionPublicId}}/featured`, body `{ "featured": true, "featuredUntil": null }`.
- Rename the three rail requests to `Get featured`, `Get ending soon`, `Get trending`.
- Update URLs to `/api/v1/auctions/rails/*`.

## Out of Scope

- **Paid Featured purchase flow** (issue #230). Buyer-facing checkout, Stripe / L$ payment, billing audit, refund-on-failure, automatic listing-end → unfeature transition. The data model here is the foundation; the wallet/checkout/billing path is its own epic. Add to `docs/implementation/DEFERRED_WORK.md`.
- **`auction_featured_promotions` history table.** Required for the paid product (audit, billing reconciliation, "how much did this seller spend on promotion?" queries). Today's two-column shape is correct for admin-curated; the history table layers on top when payment flows arrive.
- **Background expiry job** flipping `is_featured = FALSE` once `featured_until < NOW()`. Functionally unnecessary because the rail query already filters by `featured_until > NOW()`. Skip until a real reason shows up.
- **Per-event cache invalidation for Trending.**
- **Trending materialized view / score rollup.** Two scalar subqueries are fine at launch volume.
- **View-count tracking.** Trending uses bids + saves only.
- **Hero stack source A/B test.** Hard switch; no flag, no ramp.
- **Cache key migration tooling.** TTL absorbs the rename; no `redis-cli DEL` at deploy time.
