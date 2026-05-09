# Header Search Overlay — Design

**Date:** 2026-05-09
**Status:** Approved (brainstorming)
**Touchpoints:** backend `auction.search` package + new `SearchSuggest*`; frontend Header + new `components/search/`; one Flyway migration; Postman collection.

## 1. Problem

The header has a Search icon that ships disabled with the label "Search (coming soon)". `/browse` exposes only structured filters (region, area, price, tags, …); there is no free-text path. New visitors who land on the home page have no way to type "tula" or "skybox" and discover what's listed.

## 2. Goals

- Live typeahead from the header icon: type → debounced backend hit → grouped results in a popover.
- Match against listing **title**, **parcel name**, and **region name**.
- Public — no auth required; matches existing `/browse` posture.
- Mobile parity via a full-screen sheet using the same component body.
- Enter (or "See all results") routes to `/browse?q=<text>`, where the existing filter sidebar still applies.

## 3. Non-goals

- Searching on **seller display name** — out of scope for v1; can be additive later.
- **Recent searches** / **trending regions** in the empty popover state — kept blank in v1.
- A `q=`-aware filter chip on `/browse` — v1 only renders a "Showing results for `'q'`" header above the grid; clearing the input clears the URL param.
- Indexing / search infra beyond Postgres + `pg_trgm`.

## 4. UX summary

```
┌─ Header ─────────────────────────────────┐
│  ... [Search icon] [Bell] [Wallet] ...   │
└──────────┬───────────────────────────────┘
           │ click
┌──────────▼─────────────────────────────────────┐
│  SearchOverlay   (Dialog on mobile,            │
│                   anchored Popover on desktop) │
│  ┌─ <input> ──────────────────────────────┐   │
│  │ Search parcels, regions…                │   │
│  └─────────────────────────────────────────┘   │
│                                                 │
│  Listings (5)                                   │
│    [thumb] Title · Region · L$ bid              │
│    ...                                          │
│                                                 │
│  Regions (3)                                    │
│    [pin]  Tula      · 4 active                  │
│    ...                                          │
│                                                 │
│  See all 23 results for "tul" →                 │
└────────────────┬─────────────────────────────┬──┘
                 │ click row              │ Enter
       /auction/{id} or         /browse?q=tul
       /browse?region=Tula      (renders full grid)
```

- **Trigger:** the existing `IconButton` in `Header.tsx`. Drop `hidden md:inline-flex` so it's visible at all widths; flip its `aria-label` to "Search" (no longer "coming soon"); remove `disabled`.
- **Desktop popover:** anchored to the trigger, ~480px wide, `max-height: ~520px`, click-outside / Esc to close.
- **Mobile sheet:** Headless UI `Dialog` filling the viewport; an X-close button replaces the click-outside-to-close affordance. `autoFocus` the input.
- **Combobox** (Headless UI v2) is the underlying primitive — input + listbox + keyboard nav (arrow up/down, Enter to select, Esc to close) come baked in.

### Selection routing

- Click / Enter on a **listing row** → `router.push("/auction/{publicId}")`, close overlay.
- Click / Enter on a **region row** → `router.push("/browse?region={name}")`, close overlay.
- Click the **footer** "See all N results" → `router.push("/browse?q={trimmed}")`, close overlay.
- **Bare Enter** (no row highlighted) → same as the footer: `/browse?q={trimmed}`.

### State machine the popover renders

| Condition | Render |
|---|---|
| `trimmed.length < 2` | Empty (no group headers, no skeletons). |
| `query.isLoading && data == null` | 4 compact skeleton stripes, no group headers. |
| `query.isFetching && data != null` | Keep prior data visible (no flicker). |
| `data && data.listings.length === 0 && data.regions.length === 0` | "No matches for `'{trimmed}'`." |
| `query.isError` | "Search is unavailable right now." (no retry button.) |

## 5. Backend

### 5.1 New endpoint: `GET /api/v1/search/suggest?q=<text>`

```java
@RestController
@RequestMapping("/api/v1/search/suggest")
@RequiredArgsConstructor
public class SearchSuggestController {

    private final SearchSuggestService service;

    @GetMapping
    public ResponseEntity<SuggestResponse> suggest(@RequestParam String q) {
        // Empty / short queries return an empty envelope without hitting the
        // DB — saves a round-trip while the user is still typing the first
        // character. The frontend hook also gates on length>=2, but the
        // backend re-checks because the contract is public.
        String trimmed = q == null ? "" : q.trim();
        if (trimmed.length() < 2) {
            return ResponseEntity.ok(SuggestResponse.empty());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)).cachePublic())
                .body(service.suggest(trimmed));
    }
}
```

**Auth:** public — added to `SecurityConfig`'s permitAll list, mirroring `/api/v1/auctions/search/**`.

**Cache:** 15s public `Cache-Control` on responses. No Redis read-through (the existing `SearchResponseCache` is keyed on the full filter object; reusing it would mostly miss because typeahead inputs change every keystroke). Browser HTTP cache is enough at typeahead cadence.

### 5.2 Response DTOs

```java
public record SuggestResponse(
    List<SuggestListingDto> listings,    // max 5
    List<SuggestRegionDto> regions,      // max 3
    int totalListings                    // count of all matching ACTIVE auctions
) {
    public static SuggestResponse empty() {
        return new SuggestResponse(List.of(), List.of(), 0);
    }
}

public record SuggestListingDto(
    UUID publicId,
    String title,
    String regionName,
    String parcelName,        // nullable
    String primaryPhotoUrl,   // nullable, relative path the frontend wraps in apiUrl()
    long currentBid           // L$
) {}

public record SuggestRegionDto(
    String name,              // also doubles as the slug for /browse?region=
    int activeAuctionCount    // surfaced as "4 active" under the row
) {}
```

`totalListings` powers the "See all N results" footer; if it equals `listings.size()` the footer is suppressed because the popover already shows everything.

### 5.3 Service-layer matching strategy

Use raw native SQL via a `@Query` repository (or `JdbcTemplate` if cleaner) rather than Specification + Criteria — the suggest path benefits from explicit `similarity()` ordering, which Criteria doesn't express cleanly.

```sql
-- Listings query (ACTIVE auctions only)
SELECT a.public_id, a.title, r.name AS region_name,
       ps.parcel_name, ph.public_id AS photo_public_id,
       a.current_bid
FROM auctions a
JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
JOIN regions r                   ON r.id = ps.region_id
LEFT JOIN auction_photos ph      ON ph.auction_id = a.id AND ph.sort_order = 0
WHERE a.status = 'ACTIVE'
  AND (a.title ILIKE :pattern
       OR ps.parcel_name ILIKE :pattern
       OR r.name ILIKE :pattern)
ORDER BY GREATEST(
    similarity(a.title, :raw),
    similarity(COALESCE(ps.parcel_name, ''), :raw),
    similarity(r.name, :raw)
) DESC
LIMIT 5;

-- Regions query (only regions with at least one ACTIVE auction)
SELECT r.name, COUNT(a.id) AS active_count
FROM regions r
JOIN auction_parcel_snapshots ps ON ps.region_id = r.id
JOIN auctions a ON a.id = ps.auction_id AND a.status = 'ACTIVE'
WHERE r.name ILIKE :pattern
GROUP BY r.name
ORDER BY similarity(r.name, :raw) DESC
LIMIT 3;

-- totalListings count (independent — also gated on ACTIVE)
SELECT COUNT(*)
FROM auctions a
JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
JOIN regions r                   ON r.id = ps.region_id
WHERE a.status = 'ACTIVE'
  AND (a.title ILIKE :pattern
       OR ps.parcel_name ILIKE :pattern
       OR r.name ILIKE :pattern);
```

`:pattern` is `%foo%`, `:raw` is `foo`. All three queries hit the same trigram GIN indexes.

`primaryPhotoUrl` is built in the service layer as `/api/v1/photos/{photo_public_id}` (relative path; the frontend wraps in `apiUrl()`), nullable when no photo exists.

### 5.4 Existing `/api/v1/auctions/search` — add `q=` filter

`AuctionSearchQuery` record gains a `String q` field:

```java
public record AuctionSearchQuery(
    String q,                              // NEW: nullable free-text query
    String region, ...
)
```

The controller plumbs `@RequestParam(required = false) String q` into the new field. `AuctionSearchPredicateBuilder` adds one new clause when `q != null && !q.isBlank()`:

```java
if (q.q() != null && !q.q().isBlank()) {
    String pattern = "%" + q.q().trim().toLowerCase() + "%";
    Predicate titleMatch  = cb.like(cb.lower(root.get("title")), pattern);
    Predicate parcelMatch = cb.like(cb.lower(snapshot.get("parcelName")), pattern);
    Predicate regionMatch = cb.like(cb.lower(region.get("name")), pattern);
    predicates.add(cb.or(titleMatch, parcelMatch, regionMatch));
}
```

(Snapshot + region joins already exist for other filters; we reuse them.) `q` AND-combines with every other filter — you can text-search within a pinned region.

`SearchCacheKey` includes `q` in its hash so the existing Redis read-through cache keys correctly per query string.

### 5.5 Migration: `V22__pg_trgm_search_indexes.sql`

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_auctions_title_trgm
    ON public.auctions USING gin (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_parcel_snapshots_name_trgm
    ON public.auction_parcel_snapshots USING gin (parcel_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_regions_name_trgm
    ON public.regions USING gin (name gin_trgm_ops);
```

`pg_trgm` is bundled with Postgres 16 and is one of the standard extensions enabled on AWS RDS PG.

### 5.6 Rate limit

A new bucket on the suggest path: **300 requests / minute / IP**. Separate from the existing search bucket (60 rpm) because typeahead amplifies request count by ~10x (one request per debounced keystroke, debounce settled at 250ms means up to 4/sec sustained).

Reuses the existing bucket4j + Lettuce `ProxyManager` plumbing — adds a second `BucketConfiguration` bean (`suggestBucketConfiguration()`) and a sibling interceptor (`SuggestRateLimitInterceptor` mirroring `SearchRateLimitInterceptor`). Wired in `WebMvcRateLimitConfig` against the new path.

429 response includes `Retry-After`. Frontend renders the same "Search is unavailable" copy (typeahead doesn't honor Retry-After explicitly; the bucket refills before the user finishes the next word).

## 6. Frontend

### 6.1 File layout

```
frontend/src/
├── components/
│   └── search/
│       ├── SearchOverlay.tsx          # NEW — popover/sheet shell + state
│       ├── SearchOverlay.test.tsx
│       ├── SearchInput.tsx            # NEW — input + Search icon
│       ├── SearchResultsList.tsx      # NEW — grouped listings + regions + footer
│       └── SearchResultRow.tsx        # NEW — Listing | Region row variants
├── lib/
│   └── api/
│       └── search-suggest.ts          # NEW — fetch wrapper for /search/suggest
├── hooks/
│   ├── useSearchSuggest.ts            # NEW — TanStack Query hook with debounce
│   └── useDebouncedValue.ts           # NEW (if not already present) — small util
├── components/layout/
│   └── Header.tsx                     # MODIFY — replace disabled IconButton
└── lib/search/
    └── url-codec.ts                   # MODIFY — add q field round-trip
```

### 6.2 `useSearchSuggest` hook

```ts
export function useSearchSuggest(rawQuery: string) {
  // Debounce client-side so the React Query key stabilizes per "settled"
  // input. 250ms balances "feels responsive" against the suggest path's
  // 300-rpm bucket — under 200ms feels chattering, over 350ms feels
  // laggy when typing fast.
  const debounced = useDebouncedValue(rawQuery, 250);
  const trimmed = debounced.trim();
  return useQuery({
    queryKey: ["search-suggest", trimmed],
    queryFn: () => searchSuggestApi.suggest(trimmed),
    enabled: trimmed.length >= 2,
    staleTime: 30_000,
    gcTime: 5 * 60_000,
    retry: false,
  });
}
```

### 6.3 `SearchOverlay` component

Headless UI `Combobox` is the right primitive (input + listbox + keyboard nav baked in), wrapped in either:
- **Desktop (md+):** anchored panel rooted at the search icon, ~480px wide, max-height ~520px. Built on `Combobox` with a `<ComboboxOptions static>` panel rendered inside a positioned wrapper (Headless UI 2 supports `anchor` on Combobox-adjacent primitives the same way the existing `Dropdown` uses it on `MenuItems`).
- **Mobile (<md):** Headless UI `Dialog` filling the viewport, with an X close button replacing click-outside. Same `Combobox` body inside.

The split happens via responsive `useMediaQuery("(min-width: 768px)")` toggling the wrapper. Both branches render the same `<SearchInput>` and `<SearchResultsList>` children.

```tsx
<Combobox value={null} onChange={handleSelect}>
  <SearchInput
    value={query}
    onChange={setQuery}
    onBareEnter={() => {
      if (trimmed.length >= 2) {
        router.push(`/browse?q=${encodeURIComponent(trimmed)}`);
        onClose();
      }
    }}
  />
  <SearchResultsList
    state={hookState}     // { isLoading, data, isError }
    trimmed={trimmed}
  />
</Combobox>
```

`handleSelect` is a single discriminated-union router based on the `ComboboxOption` value:
- `{kind: "listing", id}` → `router.push("/auction/" + id)`
- `{kind: "region", name}` → `router.push("/browse?region=" + encodeURIComponent(name))`
- `{kind: "browse", q}` → `router.push("/browse?q=" + encodeURIComponent(q))`

In all three cases the overlay closes.

### 6.4 `SearchResultsList`

Two `<section>`s — Listings, then Regions — with sticky-style group headers (`text-xs uppercase tracking-wider text-fg-muted px-3 py-1`). Each group is suppressed if its array is empty. Below them, a footer row (also a `ComboboxOption` so it's keyboard-navigable) renders when `data.totalListings > data.listings.length`.

`SearchResultRow.Listing`:
- Thumbnail (40x40) — `apiUrl(listing.primaryPhotoUrl) ?? <ParcelPin fallback>`.
- Title (truncate, `text-sm font-semibold`).
- Region · L$ bid (`text-xs text-fg-muted`).

`SearchResultRow.Region`:
- ParcelPin icon (40x40 in a muted square).
- Name (`text-sm font-semibold`).
- "{n} active" (`text-xs text-fg-muted`).

### 6.5 `Header.tsx` change

Replace the disabled `IconButton`:

```tsx
<SearchOverlayTrigger />     // wraps an IconButton + the SearchOverlay state
```

The trigger owns the open/close `useState` and the overlay. Drop `hidden md:inline-flex` so the icon renders at all widths.

### 6.6 `/browse` URL codec change

`lib/search/url-codec.ts`:

```ts
// In queryFromSearchParams:
const q = sp.get("q");
if (q && q.trim().length > 0) query.q = q.trim();

// In searchParamsFromQuery:
putIf(sp, "q", query.q, (v) => v);
```

`AuctionSearchQuery` TypeScript type gains `q?: string`. The browse page renders a small "Showing results for `'foo'`" header above the grid when `q` is set (~5 lines of JSX inside `BrowseShell`); clearing the input clears the URL param.

## 7. Error and edge cases

| Case | Behavior |
|---|---|
| Input < 2 chars (trimmed) | No request fired, popover empty. Frontend gates via `enabled`; backend re-validates and returns `SuggestResponse.empty()` if hit directly. |
| 0 results from backend | "No matches for `'{q}'`." centered in the panel. No retry button. |
| Backend 5xx | "Search is unavailable right now." Silent — next keystroke retries naturally because the query key changes. No toast. |
| Backend 429 (rate-limited) | Same "unavailable" copy. `Retry-After` header is present but typeahead doesn't honor it explicitly. |
| Click row mid-fetch | Selection still works (Headless UI tracks active option from rendered data); navigation closes the overlay before the in-flight request resolves. |
| User backspaces to empty | `enabled: false` → query disabled, popover returns to empty state. Prior results discarded. |
| User pastes a 200-char string | Backend trims; pattern is `%first-200-chars%`. Postgres handles arbitrary-length ILIKE. No frontend cap needed. |
| Non-ASCII input (Cyrillic, etc.) | `pg_trgm` is byte-level — works for any UTF-8. `LOWER()` lowercases consistently. |
| Photo URL is null on a listing row | Fallback to ParcelPin icon, identical to `ListingCard`'s no-photo treatment. |
| `/browse?q=foo` deep-link | URL codec parses `q`, `BrowseShell` issues the search, results render. "Showing results for `'foo'`" header above the grid. |
| Mobile keyboard "Search" key | Same as Enter — fires the bare-Enter handler. |
| Concurrent typing during slow network | TanStack Query auto-cancels the prior fetch on key change. No request laddering. |

## 8. Testing

### 8.1 Backend

`SearchSuggestServiceTest` (slice / unit over the repository):
- 2-char query returns trimmed envelope.
- ILIKE matches across all three columns (title, parcel name, region name).
- Returns max 5 listings + 3 regions even when more match.
- Only `ACTIVE` auctions surface (DRAFT / DRAFT_PAID / CANCELLED / SOLD excluded).
- Regions list excludes regions with no active auctions.
- `similarity()` ranks "Tula" higher than "Old Tula Cove" for query `tul`.
- `totalListings` is total across all matches (not capped at 5).

`SearchSuggestControllerIntegrationTest` (`@SpringBootTest` with Testcontainers Postgres):
- Public — no JWT required.
- 15s `Cache-Control: public, max-age=15` header set.
- 300/min/IP rate limit returns 429 + `Retry-After` after the bucket drains.
- Empty-q and < 2-char-q both return `{listings:[], regions:[], totalListings:0}` with 200 (not 400).

`AuctionSearchPredicateBuilderTest` — extend with cases for `q` filter:
- `q="foo"` matches title-only listings.
- `q="foo"` matches parcel-name-only listings.
- `q="foo"` matches region-name-only listings.
- `q=null` and `q=""` both add no predicate.
- `q + region` AND together (you can text-search within a pinned region).

`SearchCacheKeyTest` — extend:
- `q` is included in the hash; same filters with different `q` values produce different keys.

### 8.2 Frontend

`SearchOverlay.test.tsx`:
- Trigger click opens the popover; Esc closes it.
- Typing < 2 chars renders empty (no fetch).
- Typing >= 2 chars (with debounce settled) shows skeletons → results.
- Listings group + Regions group both render with correct rows.
- Footer "See all X results" only renders when `totalListings > listings.length`.
- Click listing row → `router.push("/auction/{publicId}")`.
- Click region row → `router.push("/browse?region={name}")`.
- Bare Enter (no row active) → `router.push("/browse?q={trimmed}")`.
- Backend 5xx response → "Search is unavailable" copy, no toast.
- 0-result response → "No matches for `'{q}'`."
- Mobile width (`useMediaQuery` mock) renders Dialog; desktop renders the anchored panel.

`useSearchSuggest.test.tsx`:
- Debounce coalesces three fast keystrokes into one fetch.
- `enabled` gates on length >= 2.
- Query key changes per debounced value.

`url-codec.test.ts` — new cases:
- `?q=foo` round-trips through encode/decode.
- `q=""` is dropped on encode.

`Header.test.tsx` — extend:
- Search icon renders at all widths.
- `aria-label="Search"` (no longer "coming soon").
- Click opens the overlay.

`BrowseShell.test.tsx` — extend:
- "Showing results for `'foo'`" header renders when `q` is present in the initial query.

### 8.3 Postman

New folder "Search Suggest" with `GET /api/v1/search/suggest?q={{searchTerm}}`. Variable `searchTerm` seeded in the `SLPA Dev` environment.

## 9. Out of scope (deferred)

- Seller-name search.
- Recent searches / trending regions in empty popover state.
- A removable `q=` filter chip on `/browse` (v1 only renders the "Showing results for" header — clearing the URL param is the only way to clear).
- Search analytics (which queries return zero results, etc.).
- Region autocomplete on `DistanceSearchBlock` — unchanged from the existing `DEFERRED_WORK.md` entry (this design uses a separate code path; consolidating could be a future refactor).
