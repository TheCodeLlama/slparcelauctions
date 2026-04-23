# Epic 07 sub-spec 1 — Browse & Search Backend

**Date:** 2026-04-23
**Branch target:** `task/07-sub-1-browse-search-backend` off `dev`
**Scope:** Backend surface that powers the public auction-discovery experience — filterable/sortable search, distance search, featured rows, public site stats, saved-auctions model ("Curator Tray"), and enriched listing-detail read path. Plus a maturity-rating modernization prerequisite task (`PG/Mature/Adult` → `GENERAL/MODERATE/ADULT`).

Frontend surfaces (browse page, homepage featured rows, listing detail UI polish, Curator Tray drawer, `/users/{id}/listings` page, profile OpenGraph metadata, listing-wizard `title` input) ship in Epic 07 sub-spec 2.

---

## §1 — Scope

**In scope:**

- Maturity rating modernization — translate at the SL World API ingest boundary + dev-data touch-up.
- New `Auction.title` field (seller-written headline) with validation + DTO surfacing.
- `GET /api/v1/auctions/search` — public, paginated, filter/sort/distance/pagination, 30 s Redis cache, 60 rpm/IP rate limit.
- Three featured endpoints: `/api/v1/auctions/featured/ending-soon`, `/featured/just-listed`, `/featured/most-active` — each independently Redis-cached at 60 s.
- `GET /api/v1/stats/public` — bundled four-count response with `asOf` timestamp + 60 s Redis cache.
- `SavedAuction` entity + four authenticated endpoints (`POST /me/saved`, `DELETE /me/saved/{auctionId}`, `GET /me/saved/ids`, `GET /me/saved/auctions`) with 500/user hard cap enforced via advisory lock.
- Extended `GET /api/v1/auctions/{id}` — additive photo gallery + enriched seller block (rating, review count, completed sales, server-computed `completionRate`, member-since date).
- Distance search — `CachedRegionResolver` wrapping `SlMapApiClient` (7-day positive / 10-min negative Redis TTLs), explicit bounding-box pre-filter alongside sqrt refinement.
- New JPA indexes on `auctions`, `parcels`, `auction_tags`, and `saved_auctions`.
- Full test coverage per CONVENTIONS.md (unit + slice + integration + MockMvc + index-existence assertions).

**Out of scope (explicitly deferred):**

- Any frontend work — Epic 07 sub-spec 2 covers browse page, listing detail UI, homepage featured rows, Curator Tray drawer, `/users/{id}/listings` page, listing-wizard `title` input, profile OpenGraph metadata.
- Event-driven cache invalidation (new-bid event → bust cache keys) — purely TTL-driven in sub-spec 1. Revisit if the 30 s / 60 s staleness windows prove insufficient at scale.
- Real EXPLAIN ANALYZE query-plan assertions in CI — deferred to a manual / periodic check against staging-sized data. CI gate uses index-existence via `pg_indexes` instead (see §13).
- Per-user rate limiting on `/me/saved/*` — the 500-row cap is the effective limiter; no per-user bucket.
- Event-driven invalidation of `featured/*` keys — TTL-only.
- Search-response cache sharing across CDN edges — `Cache-Control` headers are set for public caching, but sub-spec 1 does not configure a CDN.
- Frontend `ListingCard` variant work, listing-wizard `title` input, Curator Tray drawer UI, `/users/{id}/listings` page, profile `generateMetadata`.
- Email change flow, account deletion UI, notification preferences editor — all rerouted away from Epic 07 in `DEFERRED_WORK.md` (to Epic 09 Task 02, Epic 10, Epic 09 Task 04 respectively).

---

## §2 — Sub-spec split rationale

Epic 07 (tasks 01-04 from `docs/implementation/epic-07/`) contains:

1. Search & Filter API (backend)
2. Browse & Search Page (frontend)
3. Listing Detail Page Enhancements (frontend)
4. Homepage & Featured Listings (both — backend endpoints + frontend page)

Plus three Epic-07-specific deferred items that land in this epic per `DEFERRED_WORK.md`:

- Curator Tray saved-auctions model + UI
- `/users/{id}/listings` page
- Public profile OpenGraph metadata

Sub-spec split:

- **Sub-spec 1 (this spec, backend-only):** search API, featured endpoints, public stats, saved-auctions model + API, listing-detail endpoint extension, maturity modernization, new `Auction.title` field (backend validation + DTO).
- **Sub-spec 2 (frontend-only, future spec):** browse page, homepage featured rows + page refresh, listing detail page polish (SSR + SLURLs + photo gallery + seller profile card + breadcrumbs), Curator Tray drawer, `/users/{id}/listings` page, profile `generateMetadata`, listing-wizard `title` input.

The split matches the Epic 04 / 05 precedent. Keeping the full REST surface reviewable as one unit before frontend work starts avoids the "half-the-API, half-the-UI" interleaving that would otherwise churn both halves on every design drift.

---

## §3 — Architecture overview

**One-paragraph summary.** A new search controller exposes `GET /api/v1/auctions/search` backed by an `AuctionSearchPredicateBuilder` that emits a single parameterized query via JPA Criteria API with stable `ORDER BY` + `id` tiebreakers. Three lightweight featured endpoints each have their own Redis-cached query and their own Micrometer timer so per-row p99 regressions are attributable. Distance search resolves region names through the existing `SlMapApiClient`, wrapped in a `CachedRegionResolver` (7-day positive / 10-min negative Redis TTL), and computes Euclidean distance in the SQL using `parcel.grid_x/grid_y` — with explicit bounding-box pre-filters so the planner can't trap a sequential scan even when row counts are small. Saved-auctions ships a new `SavedAuction` entity + four authenticated endpoints; card "hearts" are populated client-side via `GET /me/saved/ids`, so the public search response stays user-agnostic and Redis-cacheable at 30 s TTL across all anonymous + authed callers. `bucket4j-spring-boot-starter` with a Redis-backed bucket store enforces a per-IP 60 rpm limit on `/search` only. The existing `/auctions/{id}` grows an additive photo gallery + richer seller block (rating, review count, completed sales, server-computed `completionRate`, member-since) without breaking existing callers.

**Caching taxonomy.**

| Key pattern | TTL | Purpose | Invalidation |
|---|---|---|---|
| `slpa:search:<SHA-256(canonical-query)>` | 30 s | `/auctions/search` response cache | TTL |
| `slpa:featured:ending-soon` | 60 s | Featured row | TTL |
| `slpa:featured:just-listed` | 60 s | Featured row | TTL |
| `slpa:featured:most-active` | 60 s | Featured row | TTL |
| `slpa:stats:public` | 60 s | Public four-count stats | TTL |
| `slpa:grid-coord:<normalized-region-name>` | 7 d positive / 10 min negative | Region → `(grid_x, grid_y)` | TTL |
| `slpa:bucket:/auctions/search:<client-ip>` | per bucket state | Rate-limit bucket | `bucket4j` internals |

No event-driven invalidation. The TTLs are the invalidators. Event-driven bust-on-bid would devolve to bust-on-every-bid across every cached key, which is equivalent to no cache at all.

**SSR vs browser calls.** Frontend SSR (Next.js inside the Docker network) uses the in-network URL `http://backend:8080`. Browser hydration + client-side fetches use `NEXT_PUBLIC_API_URL` from the browser-facing `.env`. Pattern already established in prior epics — named here so it isn't assumed.

---

## §4 — Data model

All schema changes land through JPA entity edits with `ddl-auto: update` (no Flyway migrations, per CONVENTIONS.md).

### §4.1 New entity: `SavedAuction`

```java
@Entity
@Table(name = "saved_auctions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_saved_auctions_user_auction",
        columnNames = {"user_id", "auction_id"}),
    indexes = {
        @Index(name = "ix_saved_auctions_user_saved_at",
               columnList = "user_id, saved_at DESC")
    })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SavedAuction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Auction auction;

    @Column(name = "saved_at", nullable = false)
    private OffsetDateTime savedAt;
}
```

- `(user_id, auction_id)` unique constraint guarantees one row per user/auction pair. Idempotent POST returns the existing row on duplicate (Q4c).
- `(user_id, saved_at DESC)` composite index backs both the Curator Tray default sort (`saved_at DESC`) and the `/me/saved/ids` scan (user_id filter).
- `@OnDelete(CASCADE)` on `auction` — when an auction is ever hard-deleted (Epic 10 admin tooling), orphan `SavedAuction` rows disappear with it.

### §4.2 Modified: `Auction`

- New column: `title VARCHAR(120) NOT NULL`.
- New indexes on `@Table(indexes = ...)`:
  - `ix_auctions_status_activated_at (status, activated_at DESC)` — "Just Listed" featured + `sort=newest`.
  - `ix_auctions_status_current_bid (status, current_bid)` — `sort=lowest_price` + price range filter.
  - `ix_auctions_seller_status (seller_id, status)` — `sellerId` filter + `/users/{id}/auctions` preview.
  - `ix_auctions_status_reserve (status, reserve_price)` — reserve-status filter.

### §4.3 Modified: `Parcel`

- `maturityRating` domain values: `"PG"` → `"GENERAL"`, `"MATURE"` → `"MODERATE"`, `"ADULT"` unchanged. `length = 10` column stays (`"MODERATE"` is 8 chars).
- Field comment updated: `// "GENERAL", "MODERATE", "ADULT" — canonical SL terminology. Translated from the SL World API XML values ("PG", "Mature", "Adult") at ingest (SlWorldApiClient.parseHtml). See Epic 07 sub-spec 1.`
- New indexes:
  - `ix_parcels_grid_coords (grid_x, grid_y)` — distance search bounding-box + sqrt refinement.
  - `ix_parcels_area (area)` — size range filter.
  - `ix_parcels_maturity (maturity_rating)` — maturity filter.

### §4.4 Modified: `auction_tags` join table

- New index: `ix_auction_tags_tag (tag)` — supports `EXISTS (... WHERE tag IN (...))` predicate in the tags filter.

### §4.5 No new tables for caches

Grid-coord region resolution lives entirely in Redis (`slpa:grid-coord:<name>`). Grid Survey remains the authority; Redis is a bounded-TTL memoization layer.

### §4.6 No `bid_count_6h` denormalized column

The "Most Active" featured row runs the COUNT subquery on cache miss (§8), not on a denormalized counter. Featured endpoints are cached at 60 s TTL, so the expensive read happens at most once per minute per backend instance — the same cadence a scheduled refresh would run, but only when traffic demands it. Avoids a column, a refresh job, and write amplification on the bid hot path.

---

## §5 — API surface

All new endpoints return the standard error envelope on 4xx / 5xx:

```json
{ "code": "INVALID_FILTER_VALUE", "message": "maturity must be one of GENERAL, MODERATE, ADULT", "field": "maturity" }
```

### §5.1 `GET /api/v1/auctions/search`

**Auth:** public (no `Authorization` header required).
**Cache-Control:** `public, max-age=30`.
**Rate limit:** 60 rpm per IP.

**Query parameters (all optional unless noted):**

| Name | Type | Values / constraints |
|---|---|---|
| `status` | enum | Single value, currently only `ACTIVE` is supported (default). Reserved for future expansion. |
| `region` | string | Exact region name match (case-insensitive). |
| `min_area`, `max_area` | int | Sqm. `min_area <= max_area` or 400 `INVALID_RANGE`. |
| `min_price`, `max_price` | long | L$. `min_price <= max_price` or 400 `INVALID_RANGE`. |
| `maturity` | multi-enum | `GENERAL`, `MODERATE`, `ADULT`. Repeat param or CSV. |
| `tags` | multi-enum | Any `ParcelTag` value. Repeat or CSV. Default logic OR. |
| `tags_mode` | enum | `or` (default), `and`. |
| `reserve_status` | enum | `all` (default), `reserve_met`, `reserve_not_met`, `no_reserve`. |
| `snipe_protection` | enum | `any` (default), `true`, `false`. |
| `verification_tier` | multi-enum | `SCRIPT`, `BOT`, `HUMAN`. |
| `ending_within` | int (hours) | e.g. `ending_within=24` → `ends_at <= NOW() + 24h AND ends_at > NOW()`. |
| `near_region` | string | Region name for distance anchor. Resolved via `CachedRegionResolver`. |
| `distance` | int (regions) | Max radius. Clamped to ≤ 50 silently. Ignored when `near_region` absent. |
| `seller_id` | long | Filter to auctions by this seller. |
| `sort` | enum | `newest` (default), `ending_soonest`, `most_bids`, `lowest_price`, `largest_area`, `nearest`. |
| `page` | int | 0-indexed. `< 0` clamped to 0. |
| `size` | int | Default 24. `> 100` clamped to 100. |

**Response:**

```json
{
  "content": [ /* AuctionSearchResultDto array — shape in §5.1.1 */ ],
  "page": 0,
  "size": 24,
  "totalElements": 287,
  "totalPages": 12,
  "first": true,
  "last": false,
  "meta": {
    "sortApplied": "ending_soonest",
    "nearRegionResolved": { "name": "Tula", "gridX": 997, "gridY": 1036 }
  }
}
```

`meta.nearRegionResolved` is present only when `near_region` was in the request and resolved cleanly. Absent otherwise.

#### §5.1.1 `AuctionSearchResultDto` shape

Shared by `/auctions/search`, the three `/featured/*` endpoints, and `/me/saved/auctions`. Single shape, single frontend renderer (`ListingCard`).

```json
{
  "id": 12345,
  "title": "Premium Waterfront — Must Sell!",
  "status": "ACTIVE",

  "parcel": {
    "id": 9876,
    "name": "Bayside Cottage Lot",
    "region": "Tula",
    "area": 1024,
    "maturity": "MODERATE",
    "snapshotUrl": "/api/parcels/9876/snapshot",
    "gridX": 997,
    "gridY": 1036,
    "positionX": 80,
    "positionY": 104,
    "positionZ": 89,
    "tags": ["BEACHFRONT", "ROADSIDE"]
  },

  "primaryPhotoUrl": "/api/auctions/12345/photos/7/content",

  "seller": {
    "id": 42,
    "displayName": "resident.name",
    "avatarUrl": "/api/users/42/avatar",
    "averageRating": 4.82,
    "reviewCount": 12
  },

  "verificationTier": "BOT",
  "currentBid": 12500,
  "startingBid": 5000,
  "reservePrice": 10000,
  "reserveMet": true,
  "buyNowPrice": 25000,
  "bidCount": 7,
  "endsAt": "2026-04-25T18:30:00Z",
  "snipeProtect": true,
  "snipeWindowMin": 5,

  "distanceRegions": 3.2
}
```

**Field rules:**

- `primaryPhotoUrl` — resolved server-side: first `AuctionPhoto` by `sort_order ASC, id ASC` if any exist, else falls back to `parcel.snapshotUrl`. Frontend never makes the fallback decision.
- `reserveMet` — precomputed server-side: `reservePrice IS NULL OR currentBid >= reservePrice`. Keeps the reserve-status badge logic centralized.
- `buyNowPrice`, `reservePrice`, `snipeWindowMin` — nullable. Seller didn't set a reserve / buy-it-now / custom snipe window.
- `seller.averageRating`, `seller.reviewCount` — nullable only when seller has no completed reviews (new seller); otherwise pulled from denormalized `User.avgSellerRating` + `User.totalSellerReviews`.
- `distanceRegions` — present (non-null) **only** when `near_region` was in the request. Absent / `null` otherwise. Rounded to 1 decimal place.
- `parcel.maturity` — always one of `"GENERAL" | "MODERATE" | "ADULT"` (post-modernization).
- No `timeRemaining` string — frontend derives from `endsAt` via shared `CountdownTimer`.
- No bid-history array, no gallery — those are detail-page data, not card data.

**Errors:**

- 400 `INVALID_FILTER_VALUE` — unknown enum.
- 400 `INVALID_RANGE` — `min > max` on a range pair.
- 400 `REGION_NOT_FOUND` — `near_region` couldn't be resolved by Grid Survey.
- 400 `NEAREST_REQUIRES_NEAR_REGION` — `sort=nearest` without `near_region`.
- 429 `TOO_MANY_REQUESTS` + `Retry-After` header — per-IP bucket exhausted.
- 503 `REGION_LOOKUP_UNAVAILABLE` — Grid Survey upstream 5xx / timeout (distance search degrades visibly, doesn't silently drop the filter).

### §5.2 Featured endpoints

Three independent endpoints, identical response shape, per-endpoint Redis cache and per-endpoint Micrometer timers:

- `GET /api/v1/auctions/featured/ending-soon`
- `GET /api/v1/auctions/featured/just-listed`
- `GET /api/v1/auctions/featured/most-active`

**Response:**

```json
{ "content": [ /* AuctionSearchResultDto array, up to 6 entries */ ] }
```

**Auth:** public.
**Cache-Control:** `public, max-age=60`.
**Rate limit:** none (bounded-cardinality URLs; Redis TTL is the origin-protection layer).

**Partial-failure isolation.** A 5xx from one endpoint doesn't affect the other two. Homepage SSR fires them in parallel via `Promise.all`; the frontend renders the two healthy rows + an empty placeholder for the failed one.

### §5.3 `GET /api/v1/stats/public`

**Auth:** public.
**Cache-Control:** `public, max-age=60`.

**Response:**

```json
{
  "activeAuctions":   147,
  "activeBidTotalL":  4829650,
  "completedSales":   1283,
  "registeredUsers":  9472,
  "asOf":             "2026-04-23T15:42:10Z"
}
```

All four counts come from a single bundled backend compute on cache miss. `asOf` is the moment the cache entry was populated.

### §5.4 Saved-auctions endpoints (Curator Tray)

All four require `Authorization: Bearer <jwt>` and return 401 when unauthenticated.

#### `POST /api/v1/me/saved`

**Body:** `{ "auctionId": 12345 }`

**Behavior:**

1. Load auction by id. Missing → 404 `AUCTION_NOT_FOUND`.
2. Reject if `auction.status ∈ {DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED}` → 403 `CANNOT_SAVE_PRE_ACTIVE`. All post-activation statuses allowed.
3. Check for existing `(user, auction)` row. Present → 200 OK with existing row (idempotent intent; not a 409).
4. Acquire per-user advisory lock (`pg_advisory_xact_lock(hashtext('saved:' || user_id))`) for the remainder of the transaction.
5. Count user's current rows. `>= 500` → 409 `SAVED_LIMIT_REACHED` (lock releases on rollback).
6. Insert `SavedAuction` with `savedAt = now()`. Return 200 OK + the row.

**Response (200):**

```json
{ "auctionId": 12345, "savedAt": "2026-04-23T15:42:10Z" }
```

#### `DELETE /api/v1/me/saved/{auctionId}`

`DELETE FROM saved_auctions WHERE user_id = :me AND auction_id = :auctionId`. Returns 204 regardless of whether a row was present. Fully idempotent.

#### `GET /api/v1/me/saved/ids`

Hot-path heart-overlay fetch. Returns the full ID set (bounded to 500 per user).

**Response:**

```json
{ "ids": [12345, 9876, 543, 21, 7] }
```

**Cache-Control:** `private, max-age=0` — must revalidate per request. React Query handles client-side freshness, invalidating on POST/DELETE mutations.

#### `GET /api/v1/me/saved/auctions`

Paginated full-card list for the Curator Tray drawer. Shares search vocabulary.

**Query parameters:** every parameter from `§5.1` except `near_region` / `distance` / `nearest` sort (distance search not applicable) + `seller_id` (meaningless in saved context) + new parameter:

- `statusFilter` — enum: `active_only` (default), `all`, `ended_only`.

**`statusFilter` mapping:**

| Value | Predicate |
|---|---|
| `active_only` (default) | `auction.status = 'ACTIVE'` |
| `ended_only` | `auction.status NOT IN ('DRAFT', 'DRAFT_PAID', 'VERIFICATION_PENDING', 'ACTIVE')` |
| `all` | no status predicate |

The `ended_only` predicate is an exclusion of pre-active states rather than an enumeration of terminal states — any future terminal state added to `AuctionStatus` automatically falls into "ended" without a predicate touch-up. The `VERIFICATION_FAILED` status isn't listed in the exclusion set because the POST guard in `§5.4` ensures no saved row can point to a pre-active auction.

**Default sort:** `saved_at DESC`. All other `/search` sorts accepted.

**Response:** identical `Page<AuctionSearchResultDto>` envelope as `/search`.

### §5.5 `GET /api/v1/auctions/{id}` — extended

Additive changes. Path unchanged. Existing callers unaffected.

**New fields:**

- `title` — top-level string (from new `Auction.title`).
- `photos: AuctionPhotoDto[]` — full seller-uploaded set, sorted by `(sort_order ASC, id ASC)`.
  ```json
  { "id": 7, "url": "/api/auctions/12345/photos/7/content", "sortOrder": 0, "caption": null }
  ```
- Seller block enrichment:
  ```json
  {
    "id": 42,
    "displayName": "resident.name",
    "avatarUrl": "/api/users/42/avatar",
    "averageRating": 4.82,
    "reviewCount": 12,
    "completedSales": 8,
    "completionRate": 0.67,
    "memberSince": "2025-11-14"
  }
  ```

  - `averageRating`, `reviewCount`, `completedSales`, `memberSince` come from denormalized columns on `User`.
  - `completionRate` is **server-computed** from `completedSales / (completedSales + cancelledWithBids)`, rounded `HALF_UP` to 2 decimal places, null when both denominator counters are zero. `cancelledWithBids` itself stays off the wire.

**No breaking changes.** All new fields are additive. Existing consumers ignore them.

**Hydration.** Single `@EntityGraph(attributePaths = { "parcel", "seller", "photos" })` on the existing find. Single-row fetch, so the paginated-collection trap from `§6` does not apply.

---

## §6 — Search core (`/auctions/search`)

### §6.1 Filter predicate building

`AuctionSearchPredicateBuilder` takes a validated `AuctionSearchQuery` record and emits a single `Specification<Auction>` via JPA Criteria API. Each filter maps to a composable predicate; predicates AND together. The builder is pure — input = validated query record, output = specification — so unit tests exercise it without a Spring context.

**Filter → predicate mapping (key cases):**

- `region` → `LOWER(p.regionName) = LOWER(:region)`.
- `min_area` / `max_area` → `p.area BETWEEN :min AND :max`.
- `min_price` / `max_price` → `a.currentBid BETWEEN :min AND :max`.
- `maturity` (multi) → `p.maturityRating IN (:values)`.
- `tags` OR (default) → `EXISTS (SELECT 1 FROM auction_tags t WHERE t.auction_id = a.id AND t.tag IN (:tags))`.
- `tags` AND (`tags_mode=and`) → `SELECT 1 FROM auction_tags WHERE auction_id = a.id AND tag IN (:tags) GROUP BY auction_id HAVING COUNT(DISTINCT tag) = :n`.
- `reserve_status`:
  - `no_reserve` → `a.reservePrice IS NULL`.
  - `reserve_met` → `a.reservePrice IS NOT NULL AND a.currentBid >= a.reservePrice`.
  - `reserve_not_met` → `a.reservePrice IS NOT NULL AND (a.currentBid IS NULL OR a.currentBid < a.reservePrice)`.
- `snipe_protection=true` → `a.snipeProtect = true`.
- `snipe_protection=false` → `a.snipeProtect = false`.
- `verification_tier` (multi) → `a.verificationTier IN (:values)`.
- `ending_within=H` → `a.endsAt <= :now + H hours AND a.endsAt > :now`.
- `seller_id` → `a.seller.id = :sellerId`.

### §6.2 Sort tiebreakers

Every sort carries `id` as the final stabilizer. Implemented as a `switch` over a `Sort` enum:

| Sort | ORDER BY |
|---|---|
| `newest` (default) | `activated_at DESC, id DESC` |
| `ending_soonest` | `ends_at ASC, id ASC` |
| `most_bids` | `(SELECT COUNT(*) FROM bids WHERE auction_id = a.id AND created_at > NOW() - INTERVAL '6 hours') DESC, ends_at ASC, id DESC` |
| `lowest_price` | `COALESCE(current_bid, starting_bid) ASC, id ASC` |
| `largest_area` | `parcel.area DESC, id DESC` |
| `nearest` | `distance ASC, id ASC` — requires `near_region`; 400 otherwise |

`lowest_price` uses `COALESCE` so zero-bid auctions sort by their starting price alongside priced auctions — not clustered at the top as would happen with `NULLS FIRST`.

### §6.3 Result hydration (three-query pattern)

Collection fetches combined with `Pageable` trigger Hibernate's `HHH90003004` — Hibernate can't paginate in SQL, fetches all matching rows into memory, and paginates in Java. Invisible at a few hundred active auctions, catastrophic at a few thousand. Avoided by **not** join-fetching collections on paginated queries.

**Queries per search page:**

1. **Main paginated query.** `@EntityGraph(attributePaths = { "parcel", "seller" })` — both `@ManyToOne`, safe to join-fetch. Pagination happens in SQL.
2. **Tags batch.** `SELECT auction_id, tag FROM auction_tags WHERE auction_id IN (:pageIds)`. Grouped by auction_id into a `Map<Long, Set<ParcelTag>>`.
3. **Primary photos batch.** `SELECT <columns> FROM auction_photos WHERE auction_id IN (:pageIds) AND sort_order = 0`. Mapped into a `Map<Long, AuctionPhotoDto>`.

DTO mapping merges the three streams in-service. No N+1, no in-memory pagination.

### §6.4 Response cache (30 s Redis)

- Cache key: `slpa:search:<SHA-256(canonical-JSON(query-record))>`. Canonicalization: sort entry keys alphabetically, omit `null` fields, sort tag sets before hashing — same filter in different query-string orders produces the same key.
- Cache stores the serialized `Page<AuctionSearchResultDto>` (Jackson JSON, UTF-8 bytes).
- Miss → execute the three-query hydration + write to Redis with 30 s TTL → return.
- Hit → deserialize + return directly.
- Invalidation: purely TTL-driven. No event-driven busting.

### §6.5 Rate limiting (60 rpm / IP)

- `bucket4j-spring-boot-starter` configured with a Redis-backed `ProxyManager` so the bucket state is shared across backend instances.
- Bucket: `60 tokens / 1 minute refill, greedy refill`. 1 token per request.
- Client identity: `X-Forwarded-For` header (first IP), falls back to `ServletRequest.getRemoteAddr()`.
- Interceptor registered for `/api/v1/auctions/search` only. `/featured/*` and `/stats/public` are excluded.
- Exhausted bucket → 429 `TOO_MANY_REQUESTS` + `Retry-After: <seconds>` + standard envelope.

### §6.6 Validation

Controller-level `@Validated` + bean-validation annotations on `AuctionSearchQuery` handle enum parsing, nullability, primitive ranges.

Semantic validation sits in `AuctionSearchQueryValidator` and runs before the predicate builder:

- `min_area > max_area` or `min_price > max_price` → 400 `INVALID_RANGE`.
- `sort=nearest` without `near_region` → 400 `NEAREST_REQUIRES_NEAR_REGION`.
- `size > 100` → clamp silently, log at DEBUG.
- `page < 0` → clamp to 0, log at DEBUG.
- `distance` without `near_region` → inert parameter, logged at DEBUG but not an error.
- Unknown query parameter names → ignored silently (forward-compat).

---

## §7 — Distance search

### §7.1 Region resolution (`CachedRegionResolver`)

Wraps the existing `SlMapApiClient`.

```java
@Service
@RequiredArgsConstructor
public class CachedRegionResolver {
    private final SlMapApiClient client;
    private final StringRedisTemplate redis;

    private static final Duration POSITIVE_TTL = Duration.ofDays(7);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(10);
    private static final String NEG_SENTINEL = "__NOT_FOUND__";

    public Optional<GridCoord> resolve(String regionName) {
        String key = "slpa:grid-coord:" + regionName.trim().toLowerCase(Locale.ROOT);
        String cached = redis.opsForValue().get(key);
        if (NEG_SENTINEL.equals(cached)) return Optional.empty();
        if (cached != null) return Optional.of(GridCoord.parse(cached));

        // Miss path:
        SlMapApiClient.Result result = client.resolve(regionName);
        switch (result.status()) {
            case FOUND -> {
                redis.opsForValue().set(key, result.coord().serialize(), POSITIVE_TTL);
                return Optional.of(result.coord());
            }
            case NOT_FOUND -> {
                redis.opsForValue().set(key, NEG_SENTINEL, NEGATIVE_TTL);
                return Optional.empty();
            }
            case UPSTREAM_ERROR -> {
                // Do NOT cache — let the next caller retry.
                throw new RegionLookupUnavailableException(regionName);
            }
        }
    }
}
```

### §7.2 Distance predicate + sort

When `near_region` is present and resolves cleanly to `(x0, y0)`:

```sql
SELECT a.*,
       sqrt(power(p.grid_x - :x0, 2) + power(p.grid_y - :y0, 2)) AS distance
FROM auctions a
JOIN parcels p ON p.id = a.parcel_id
WHERE ...                                       -- other predicates
  AND p.grid_x BETWEEN :x0 - :d AND :x0 + :d    -- bounding-box pre-filter
  AND p.grid_y BETWEEN :y0 - :d AND :y0 + :d    -- bounding-box pre-filter
  AND sqrt(power(p.grid_x - :x0, 2) + power(p.grid_y - :y0, 2)) <= :d
ORDER BY ...
```

**Bounding-box pre-filters emitted explicitly.** Postgres' planner sometimes derives them from the sqrt predicate and sometimes doesn't. Emitting both the box + the sqrt refinement guarantees the planner can use `ix_parcels_grid_coords` for a range scan, and the sqrt narrows to exact circular distance within the box.

**`distance` param clamps to ≤ 50 silently.** Default 10.

**`sort=nearest`** → `ORDER BY distance ASC, id ASC`. The computed `distance` column flows through to the DTO as `distanceRegions`.

### §7.3 Error model

- Region resolves cleanly → 200 with `meta.nearRegionResolved` populated.
- Region not found in Grid Survey → 400 `REGION_NOT_FOUND`.
- Grid Survey upstream 5xx / timeout → 503 `REGION_LOOKUP_UNAVAILABLE` (propagated from `RegionLookupUnavailableException`).

Distance search degrades visibly on upstream failure rather than silently dropping the filter and returning unfiltered results — matches the "400 on semantic errors, clamp on scalar overshoots" invariant from `§6.6`.

---

## §8 — Featured rows

### §8.1 Three endpoints

Three independent endpoints (`§5.2`). Each has its own Redis key, its own TTL, its own Micrometer timer, and its own partial-failure boundary.

**Why three endpoints, not one bundled response:**

- Partial-failure isolation — one slow or failing query doesn't degrade the other two rows.
- Per-category event-driven invalidation is viable (if we ever want it) — `/just-listed` is dirtied by new-listing events, `/most-active` by new-bid events, `/ending-soon` by neither. A bundled key would have to bust on every event.
- Per-endpoint monitoring — per-category p99 is visible in Micrometer timers. A bundled endpoint hides which row regressed.
- Independent pagination if any row ever grows — adding a scrollable "Most Active Rail" with its own pagination is an endpoint-level change, not a response-shape break.

### §8.2 Queries

**`/featured/ending-soon`:**

```sql
SELECT a.*
FROM auctions a
WHERE a.status = 'ACTIVE' AND a.ends_at > NOW()
ORDER BY a.ends_at ASC, a.id ASC
LIMIT 6
```

Backed by existing `ix_auctions_status_ends_at`.

**`/featured/just-listed`:**

```sql
SELECT a.*
FROM auctions a
WHERE a.status = 'ACTIVE'
ORDER BY a.activated_at DESC, a.id DESC
LIMIT 6
```

Uses Epic 04's `activated_at` timestamp (DRAFT → ACTIVE transition), not `created_at`. Backed by new `ix_auctions_status_activated_at`.

**`/featured/most-active`:**

```sql
SELECT a.*
FROM auctions a
WHERE a.status = 'ACTIVE'
ORDER BY (
  SELECT COUNT(*) FROM bids b
  WHERE b.auction_id = a.id
    AND b.created_at > NOW() - INTERVAL '6 hours'
) DESC, a.ends_at ASC, a.id DESC
LIMIT 6
```

Subquery covered by existing `ix_bids_auction_created (auction_id, created_at)`. No denormalized counter, no refresh job — the Redis TTL is the rate limiter on the expensive read.

### §8.3 Hydration

Identical three-query pattern as `§6.3` — main query + tags batch + photos batch. Limit is 6, so per-endpoint cost is trivial.

### §8.4 Per-endpoint metrics

Each endpoint registers its own Micrometer timer:

- `slpa.featured.ending_soon.duration`
- `slpa.featured.just_listed.duration`
- `slpa.featured.most_active.duration`

At scale this is the per-row p99 breakout that tells ops which row regressed.

### §8.5 Partial-failure isolation

Each endpoint controller has its own exception handler. A 5xx propagates to the caller; the frontend renders an empty row placeholder for the failing endpoint without affecting the other two.

---

## §9 — Public stats (`/stats/public`)

### §9.1 Bundled response

Four counts returned in a single response, bundled into a single Redis key with a single 60 s TTL:

```json
{
  "activeAuctions":   147,
  "activeBidTotalL":  4829650,
  "completedSales":   1283,
  "registeredUsers":  9472,
  "asOf":             "2026-04-23T15:42:10Z"
}
```

### §9.2 Queries

- `activeAuctions` = `SELECT COUNT(*) FROM auctions WHERE status = 'ACTIVE'` (uses `ix_auctions_status_ends_at`, status leading column covers equality).
- `activeBidTotalL` = `SELECT COALESCE(SUM(current_bid), 0) FROM auctions WHERE status = 'ACTIVE'`. Sum of current bids across live auctions — "money currently committed", not lifetime.
- `completedSales` = `SELECT COUNT(*) FROM auctions WHERE status = 'COMPLETED'`. Uses terminal escrow-settled state (Epic 05).
- `registeredUsers` = `SELECT COUNT(*) FROM users`. No filter today; revisit when Epic 10 soft-delete lands (subtract `status = DELETED`).

All four executed together on cache miss, cached as a single value, served as a single GET on hit.

### §9.3 `asOf` semantics

The moment the cache entry was populated. Lets the frontend show "stats updated Xs ago" microcopy if desired, and is the diagnostic field when someone reports "the number looks wrong."

### §9.4 Cache behavior

- Key: `slpa:stats:public`.
- TTL: 60 s.
- Cache-Control: `public, max-age=60`.
- No event-driven invalidation.

---

## §10 — Saved auctions

### §10.1 Entity

See `§4.1`.

### §10.2 Endpoints

See `§5.4` for request/response contract. This section covers implementation detail.

### §10.3 POST — cap enforcement via advisory lock

A naive `SELECT COUNT(*) ... INSERT` is racy: two parallel POSTs can both read 499 rows and both insert, landing at 501. The `(user_id, auction_id)` unique constraint doesn't help — different auction IDs.

Mitigation:

```java
@Transactional
public SavedAuctionDto save(Long userId, Long auctionId) {
    // Load auction, check pre-active guard, check idempotent duplicate ... (omitted for brevity)

    // Acquire per-user advisory lock. Lock lives only for this transaction.
    jdbcTemplate.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        Object.class,
        "saved:" + userId);

    long count = savedAuctionRepository.countByUserId(userId);
    if (count >= 500) {
        throw new SavedLimitReachedException();
    }

    SavedAuction row = SavedAuction.builder()
        .user(userRef)
        .auction(auctionRef)
        .savedAt(OffsetDateTime.now(clock))
        .build();
    return mapper.toDto(savedAuctionRepository.save(row));
}
```

- `pg_advisory_xact_lock` scopes per-user. Different users don't block each other. Same user's concurrent POSTs serialize.
- Lock releases on commit or rollback automatically — no `pg_advisory_xact_unlock` call.
- Bounded contention: cap only matters for a user who has ~500 rows, so the lock is unlikely to be held for more than a millisecond in practice.

### §10.4 Idempotent semantics

- POST: duplicate `(user, auction)` → 200 OK + existing row. Never a 409.
- DELETE: missing row → 204. Never a 404.
- 409 is reserved for the cap (`SAVED_LIMIT_REACHED`).
- 403 is reserved for pre-active save attempts (`CANNOT_SAVE_PRE_ACTIVE`).

### §10.5 GET /ids hot path

Simple `SELECT auction_id FROM saved_auctions WHERE user_id = :me` ordered by `saved_at DESC` (stable). Bounded to 500 rows. Returns `{ "ids": [...] }`. React Query caches + invalidates on mutations.

### §10.6 GET /auctions full-card list

Reuses `AuctionSearchPredicateBuilder` and the three-query hydration from `§6.3`, composed with an additional `auction.id IN (SELECT auction_id FROM saved_auctions WHERE user_id = :me)` predicate and the `statusFilter` mapping from `§5.4`.

### §10.7 Retention across status transitions

No lifecycle hook. Saved rows are orthogonal to auction lifecycle state. When an auction transitions to a terminal state (COMPLETED / CANCELLED / etc.), saved rows stay. Orphan case (auction hard-deleted by Epic 10 admin tooling): cascade-deleted via `@OnDelete(CASCADE)` on the `@ManyToOne Auction` join.

---

## §11 — Listing detail endpoint extension

### §11.1 Additive changes

See `§5.5` for the full contract. All changes are additive — no field removed, no field renamed.

### §11.2 Single `@EntityGraph` fetch

Because `/auctions/{id}` fetches exactly one row, the pagination-collection trap from `§6.3` does not apply. One `@EntityGraph(attributePaths = { "parcel", "seller", "photos" })` on `findById` fetches everything in one query via LEFT JOINs.

### §11.3 Completion-rate mapping

Server-computed. `cancelledWithBids` stays off the wire. One-line mapper:

```java
BigDecimal completionRate = null;
int denom = user.getCompletedSales() + user.getCancelledWithBids();
if (denom > 0) {
    completionRate = BigDecimal.valueOf(user.getCompletedSales())
            .divide(BigDecimal.valueOf(denom), 2, RoundingMode.HALF_UP);
}
```

- Rounds `HALF_UP` to 2 decimal places. Serializes as `0.67` not `0.6666666666`.
- Null when both counters are zero (new seller with no sales history — frontend uses null as "New Seller" badge trigger alongside `completedSales < 3`).
- `0.00` when seller has only cancellations-with-bids — valid signal, not null.

### §11.4 Title field surfacing

`auction.title` surfaces as a top-level string on the response. Value is required on `Auction` (Q10) so never null.

### §11.5 Caching

Unchanged from Epic 04 — endpoint stays uncached at the backend. Detail pages are SSR'd fresh; frontend layer handles browser/CDN cache headers separately.

---

## §12 — Maturity rating modernization

### §12.1 Translation at SL ingest boundary

`SlWorldApiClient.parseHtml` translates the raw XML values from Linden Lab's World API to canonical SLPA storage values:

```
"PG"     → "GENERAL"
"Mature" → "MODERATE"
"Adult"  → "ADULT"
```

Exact XML casing preserved in the mapping (user-confirmed — SL's World API emits `"PG"`, `"Mature"`, `"Adult"`). Mapper unit-tested against all three values.

### §12.2 Unknown-value rejection

An XML value outside the three known inputs gets rejected at the ingest boundary — log a WARN and throw a `ParcelIngestException` that bubbles up to the caller. No silent `"Teen"` / `"Whatever"` storage. Better to fail the lookup loudly than to store an unknown value that silently drops the row from every maturity filter later.

### §12.3 Dev-data touch-up

One-shot startup task gated by `@Profile("dev")`:

```sql
UPDATE parcels SET maturity_rating = 'GENERAL'
 WHERE UPPER(maturity_rating) = 'PG';
UPDATE parcels SET maturity_rating = 'MODERATE'
 WHERE UPPER(maturity_rating) = 'MATURE';
UPDATE parcels SET maturity_rating = 'ADULT'
 WHERE UPPER(maturity_rating) = 'ADULT'
   AND maturity_rating <> 'ADULT';
```

- Case-insensitive `UPPER()` comparison because existing dev rows follow the XML casing (`"Mature"` not `"MATURE"`). A case-sensitive predicate would silently miss them.
- Third statement normalizes `"Adult"` → `"ADULT"` without rewriting already-correct `"ADULT"` rows.
- Idempotent — safe to run on every dev-profile startup.
- Logs the row count touched at INFO.
- Removed from the codebase after the first post-launch cleanup pass. Tracked as a small deferred item in `DEFERRED_WORK.md`.

### §12.4 Filter validation

`AuctionSearchQueryValidator` rejects any `maturity` value outside `{GENERAL, MODERATE, ADULT}` with 400 `INVALID_FILTER_VALUE` + allowed-values list in the error envelope.

### §12.5 Parcel field comment correction

Old comment in `Parcel.java:100` (`// "PG", "MATURE", "ADULT"`) was already stale — actual storage followed the XML casing, not uppercase. New comment:

```java
// "GENERAL", "MODERATE", "ADULT" — canonical SL terminology.
// Translated from the SL World API XML values ("PG", "Mature", "Adult")
// at ingest (SlWorldApiClient.parseHtml). See Epic 07 sub-spec 1.
private String maturityRating;
```

### §12.6 Why a prerequisite task, not bundled with search

Bundling would block the search task behind "rewrite ingest + touch dev data" before any search work could start. Keeping modernization standalone means both tasks run cleanly in sequence — modernization ships first with tight tests, search builds against the new vocabulary.

---

## §13 — Testing strategy

Mirrors the Epic 04 / 05 / 06 precedent. Unit + slice (`@DataJpaTest`) + MockMvc + integration (`@SpringBootTest` with Testcontainers Postgres + Redis).

### §13.1 Unit tests (pure logic)

- `AuctionSearchPredicateBuilder` — each filter maps to the expected `Specification`. Parameterized tests for every enum value, every range combo, empty-query default. Target ~30 cases.
- `AuctionSearchQueryValidator` — invalid enums, `min > max`, `nearest` without `near_region`, size/page clamping. Every error path + envelope field name. Target ~20 cases.
- Maturity ingest mapper — exact XML casing (`PG` → `GENERAL`, `Mature` → `MODERATE`, `Adult` → `ADULT`) + unknown-value rejection. Target ~6 cases.
- Completion-rate mapper — zero denominator, both-positive, cancelled-only, rounding. Target ~5 cases.
- Cache-key canonicalization — same filters in different query-string orders produce the same key; tag set order insensitive. Target ~10 cases.
- Distance-search SQL builder — emits both bounding-box predicates + sqrt refinement when `near_region` is present. Target ~5 cases.

### §13.2 Slice tests (`@DataJpaTest` + Testcontainers Postgres)

- `AuctionSearchRepository` — every filter combination returns expected rows against a seeded fixture. Target ~15 cases.
- `SavedAuctionRepository` — unique constraint enforcement, cap counting under concurrent inserts (advisory lock correctness, verified via `Executors.newFixedThreadPool`), `ended_only` predicate against the actual `AuctionStatus` enum. Target ~10 cases.
- `FeaturedQueryRepository` — three featured queries return correctly ordered rows; subquery in `most-active` uses `ix_bids_auction_created`. Target ~6 cases.
- N+1 guard — a search page of 24 auctions issues exactly 3 SQL statements (via Hibernate statistics or `@SqlStatementCount`). Target ~3 cases.

### §13.3 MockMvc controller tests

- `AuctionSearchController` — every query param → happy path + error envelope. 200 response shape against a fixed JSON template. 429 on 61st request/minute. Target ~20 cases.
- `FeaturedController` — three endpoints, one test each for cache-hit vs cache-miss path. Target ~6 cases.
- `PublicStatsController` — bundled four-count response shape + `asOf` populated. Target ~3 cases.
- `SavedAuctionController` — full CRUD, 401 on unauth, 409 at cap, 403 on pre-active save, idempotent re-save + re-delete. Target ~15 cases.
- `AuctionDetailController` extension — new fields present; nullable `completionRate` for new sellers; `cancelledWithBids` absent from the response (regression guard against accidental exposure). Target ~4 cases.

### §13.4 Integration tests (`@SpringBootTest` + Testcontainers Postgres + Redis)

- Search cache hit/miss — same query twice, second call doesn't touch DB (assertion via Hibernate query counter). Target ~3 cases.
- Region-coord cache — positive hit survives 7 days (clock advancement), negative hit expires in 10 minutes, upstream 503 bypasses cache. Target ~4 cases.
- Rate limit — Redis-backed bucket enforcement across two simulated backend instances. Target ~2 cases.
- Featured endpoint cache — key-per-endpoint, one-endpoint eviction doesn't bust siblings. Target ~3 cases.

### §13.5 Index existence assertions (CI-gated, deterministic)

`PgIndexExistenceTest` — integration test that queries `pg_indexes` and asserts every index promised by the design is present in the live schema:

```java
@Test
void every_promised_index_exists() {
    Set<String> required = Set.of(
        "ix_auctions_status_activated_at",
        "ix_auctions_status_current_bid",
        "ix_auctions_seller_status",
        "ix_auctions_status_reserve",
        "ix_parcels_grid_coords",
        "ix_parcels_area",
        "ix_parcels_maturity",
        "ix_auction_tags_tag",
        "ix_saved_auctions_user_saved_at",
        "uk_saved_auctions_user_auction"
    );
    Set<String> actual = jdbc.queryForList(
        "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
        String.class).stream().collect(Collectors.toSet());
    assertThat(actual).containsAll(required);
}
```

Deterministic (no planner dependency); catches accidental removal of `@Index` annotations or column renames out from under an index.

### §13.6 Query plan verification (NOT CI-gated)

Real `EXPLAIN ANALYZE` plan shape assertions flake in CI against a Testcontainers fixture — with a small seeded table the planner often picks a sequential scan because the table fits in a single page. The correct place for plan verification is a scripted check against a staging-sized dataset (tens of thousands of auctions, realistic tag distributions), run manually ahead of a release or when tuning.

Captured in `FOOTGUNS.md` under "Query plan testing: don't put EXPLAIN ANALYZE in CI against a Testcontainers fixture" so the next engineer reviewing the query plan path doesn't re-invent the flake.

### §13.7 Out of scope

- Frontend behavior — sub-spec 2.
- End-to-end testing with a real Next.js SSR process — sub-spec 2.
- Grid Survey integration (live upstream) — existing `SlMapApiClient` tests cover the client; `CachedRegionResolver` tests use a `@MockBean` `SlMapApiClient`.

### §13.8 Estimated test volume

~160 new test methods across unit + slice + MockMvc + integration + index-existence. Matches Epic 04 / 05 backend sub-spec density.

---

## §14 — Task breakdown

Eight tasks in dependency order. Subagent-driven-development executes sequentially; later tasks assume earlier tasks have landed.

### Task 1 — Maturity rating modernization (prerequisite)

- Translate at `SlWorldApiClient.parseHtml` ingest boundary (XML → canonical storage).
- Update `Parcel.maturityRating` field comment.
- Add dev-profile startup touch-up (`@EventListener(ApplicationReadyEvent)` with `@Profile("dev")`) using case-insensitive predicates.
- Unit tests: mapper covers all three exact XML casings + unknown-value rejection + touch-up idempotence.
- Slice test: touch-up rewrites seeded legacy rows, leaves canonical rows untouched.

### Task 2 — `Auction.title` field (prerequisite)

- New `title VARCHAR(120) NOT NULL` column via `@Column(nullable = false, length = 120)`.
- `@NotBlank` + `@Size(max = 120)` bean validation on create/update paths.
- `AuctionRequestDto` + `AuctionService.createDraft` accept and persist `title`. Missing/blank → 400 with envelope.
- Existing response DTOs surface `title` as a top-level field (additive).
- Controller + slice tests cover the validation envelope.
- Listing-wizard frontend input lands in sub-spec 2.

### Task 3 — Search endpoint core

- `AuctionSearchQuery` record + `@Validated` bindings.
- `AuctionSearchQueryValidator` — invalid enums, range checks, `nearest`-without-`near_region`, size/page clamps.
- `AuctionSearchPredicateBuilder` → `Specification<Auction>` via Criteria API covering every filter from `§5.1` + `§6.1`.
- `AuctionSearchResultDto` matching the full shape.
- Entity index additions on `Auction`, `Parcel`, `auction_tags`.
- Three-query hydration pattern (`§6.3`) — main paginated join + tags batch + photos batch.
- 30 s Redis cache keyed on canonicalized query (`§6.4`).
- 60 rpm / IP rate limit via `bucket4j` Redis bucket (`§6.5`).
- MockMvc controller tests + slice tests + cache integration test + `PgIndexExistenceTest`.

### Task 4 — Distance search

- `CachedRegionResolver` wrapping `SlMapApiClient` with 7-day positive / 10-min negative Redis TTLs + upstream-error no-cache.
- Distance predicate + sort integrated into the search builder from Task 3 (explicit bounding-box pre-filter + sqrt refinement).
- `meta.nearRegionResolved` population.
- 400 `REGION_NOT_FOUND`, 400 `NEAREST_REQUIRES_NEAR_REGION`, 503 `REGION_LOOKUP_UNAVAILABLE`.
- Integration test: positive/negative/upstream-error paths in `CachedRegionResolver`.

### Task 5 — Featured endpoints (three)

- `FeaturedController` with three routes.
- Per-endpoint Redis cache with 60 s TTL + per-endpoint Micrometer timers.
- Shared `AuctionSearchResultDto` + tags/photos batch hydration.
- `/most-active` subquery against existing `ix_bids_auction_created`.
- Per-endpoint exception handler — partial-failure isolation.

### Task 6 — Public stats endpoint

- `PublicStatsController` + `PublicStatsService`.
- Bundled four-count query + `asOf` + 60 s Redis cache (`§9`).

### Task 7 — Saved auctions (depends on Task 3)

- New `SavedAuction` entity + `SavedAuctionRepository`.
- `SavedAuctionController` + `SavedAuctionService`.
- All four endpoints (`§5.4`) with idempotent semantics.
- Per-user advisory lock for cap enforcement (`§10.3`).
- `GET /me/saved/auctions` reuses Task 3's `AuctionSearchPredicateBuilder` + result DTO + hydration pattern. **Must sequence after Task 3** — running earlier means duplicating infrastructure.
- `statusFilter` predicate per `§5.4`.

### Task 8 — Extend `/auctions/{id}` detail endpoint

- Additive response fields: `title`, `photos[]`, seller block enrichment.
- `@EntityGraph(parcel, seller, photos)` on the existing find — single-row fetch, no pagination trap.
- Completion-rate mapper in a shared service (keeps `cancelledWithBids` off the wire).
- Regression-guard test: response JSON does not contain `cancelledWithBids`.

### Ordering summary

```
Task 1 ──┐                              (prerequisite, no deps)
         ├─> Task 3 ──┬─> Task 4        (search core → distance)
Task 2 ──┤            └─> Task 7        (saved auctions reuses search infra)
         ├─> Task 5                     (featured endpoints — independent after 1+2)
         ├─> Task 6                     (public stats — independent after 1+2)
         └─> Task 8                     (detail extension — independent after 1+2)
```

Tasks 6 and 8 are independent after Tasks 1 + 2. Task 7 additionally depends on Task 3 — the `/me/saved/auctions` endpoint reuses Task 3's predicate builder, result DTO, and hydration pattern. Running 7 before 3 means duplicating that infrastructure.

**Estimated spec + plan size.** Spec ~850 lines, plan ~5000 lines. Roughly matches Epic 04 / 05 sub-spec 1 density.

---

## §15 — Error envelope reference

All 4xx / 5xx responses use the standard envelope:

```json
{ "code": "<CODE>", "message": "<human>", "field": "<fieldName or null>" }
```

**New codes introduced by this sub-spec:**

| Code | Status | Context |
|---|---|---|
| `INVALID_FILTER_VALUE` | 400 | Unknown enum value on a search filter. `field` set. |
| `INVALID_RANGE` | 400 | `min > max` on a range pair. `field` set to the range name. |
| `NEAREST_REQUIRES_NEAR_REGION` | 400 | `sort=nearest` without `near_region`. |
| `REGION_NOT_FOUND` | 400 | Grid Survey returned no match for `near_region`. |
| `REGION_LOOKUP_UNAVAILABLE` | 503 | Grid Survey upstream 5xx / timeout. |
| `TOO_MANY_REQUESTS` | 429 | Per-IP search bucket exhausted. `Retry-After` header set. |
| `AUCTION_NOT_FOUND` | 404 | `POST /me/saved` on a non-existent auction ID. |
| `CANNOT_SAVE_PRE_ACTIVE` | 403 | `POST /me/saved` on a pre-activation status. |
| `SAVED_LIMIT_REACHED` | 409 | User already has 500 saved rows. |

---

## §16 — Deferred-work tie-ins

This sub-spec closes three items from `DEFERRED_WORK.md`:

- **"Saved / watchlist Curator Tray"** — backing model + API ships here (`§4.1`, `§5.4`, `§10`). Sub-spec 2 builds the drawer UI + heart button on the shared `ListingCard`.
- **"Per-user public listings page `/users/{id}/listings`"** — backed by `sellerId` filter on `/auctions/search` (`§5.1`). Sub-spec 2 builds the page.
- **"Profile page SEO metadata (OpenGraph)"** — sub-spec 2 generates OG meta via `generateMetadata` using data from existing `/users/{id}` endpoint (no backend change needed).

Four items previously ambiguously tagged "Epic 07" in `DEFERRED_WORK.md` are rerouted to their proper homes in this sub-spec's commit:

- Email change flow → **Epic 09 Task 02** (reuses transactional-email plumbing).
- Account deletion UI → **Epic 10** (shares cascade rules with ban/takedown system).
- Notification preferences editor → **Epic 09 Task 04** (canonical home already exists).
- Profile OpenGraph metadata → **Epic 07** (folded into SSR/SEO sweep in sub-spec 2).

---

## §17 — Success criteria

Sub-spec 1 ships when:

- All eight tasks in `§14` land with CI green.
- ~160 new test methods passing (unit + slice + MockMvc + integration + index existence).
- `PgIndexExistenceTest` asserts every index from `§4.2–§4.4` + `§4.1` is present in the live schema.
- `/auctions/search` returns correct results under all filter combinations from `§5.1` (verified via MockMvc).
- Distance search resolves region names, returns `meta.nearRegionResolved`, and caches positive/negative hits at the TTLs from `§7.1`.
- Three featured endpoints return correctly ordered rows (verified via slice tests) with per-endpoint Redis isolation (verified via integration test).
- Public stats endpoint returns four counts + `asOf` within one bundled Redis read.
- Four saved-auctions endpoints return correct idempotent / cap-enforced responses; advisory lock serializes concurrent POSTs.
- `/auctions/{id}` returns `title`, `photos[]`, enriched seller block with server-computed `completionRate`; response does not contain `cancelledWithBids`.
- Maturity values stored and filtered as `GENERAL` / `MODERATE` / `ADULT`; dev-data touch-up normalizes existing rows on startup.
- Rate-limit bucket enforces 60 rpm per IP on `/search` only; bucket state shared across backend instances via Redis.
- README + CONVENTIONS + FOOTGUNS + DEFERRED_WORK swept for staleness introduced or resolved by this sub-spec.
- PR from `task/07-sub-1-browse-search-backend` merges cleanly to `dev`.
