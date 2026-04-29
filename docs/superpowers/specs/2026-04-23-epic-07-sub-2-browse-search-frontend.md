# Epic 07 sub-spec 2 — Browse & Search Frontend

**Date:** 2026-04-23
**Branch target:** `task/07-sub-2-browse-search-frontend` off `dev`
**Scope:** Frontend surface that consumes the Epic 07 sub-spec 1 backend — browse page, homepage featured rows, Curator Tray drawer + `/saved` page, `/users/{id}/listings` seller listings page, auction detail rebuild (photos gallery + enriched seller card + SLURL + breadcrumbs + OG metadata), profile OG metadata, and the listing-wizard `title` input.

Small additive backend gaps uncovered during brainstorming (one DTO field, one toast primitive widening) are bundled into the matching frontend tasks rather than spun into a separate backend sub-spec.

---

## §1 — Scope

**In scope:**

- Listing-wizard `title` input (Task 1). `Auction.title` surfacing on `ListingPreviewCard`, `MyListingsTab`, `ListingSummaryRow`, and the detail-page `AuctionHero` headline.
- Canonical `ListingCard` component with three variants (`default`, `compact`, `featured`) (Task 2a).
- New UI primitives — `RangeSlider`, `Pagination`, `BottomSheet`, `Drawer`, `Lightbox`, `ActiveFilterBadge`, `StatusChip` (Tasks 2a, 4, 5).
- Search API client + `useAuctionSearch` React Query hook (Task 2a).
- Browse page (`/browse`) with filter sidebar (glassmorphic mobile bottom sheet, fixed desktop sidebar), sort controls, numbered pagination, URL-as-source-of-truth state, and SSR initial fetch (Task 2b).
- Seller listings page (`/users/{id}/listings`) — same `BrowseShell` internals, `seller_id` pinned, profile header prepended (Task 2b).
- Homepage featured rows — three `FeaturedRow` rails (Ending Soon, Just Listed, Most Active) fetched server-side via `Promise.allSettled` (Task 3).
- Auction detail page rebuild (`/auction/[id]`) — `AuctionGallery` + `Lightbox`, enriched `SellerProfileCard`, `VisitInSecondLifeBlock`, `BreadcrumbNav`, `ParcelLayoutMap` placeholder, `generateMetadata` for OpenGraph (Task 4).
- Profile page OG metadata (`/users/[id]`) via `generateMetadata` (Task 4).
- Curator Tray drawer + `/saved` page — `useSavedAuctions` React Query hook, `CuratorTrayTrigger` heart-count badge in the top nav, responsive Drawer/BottomSheet, heart wiring on every `ListingCard` caller (Task 5).
- Toast primitive widening to support a `warning` variant + structured action button (Task 5). Resolves the `DEFERRED_WORK.md` "Richer outbid toast shape" item early because `useToggleSaved` unauth needs it.
- Small backend DTO addition: `endOutcome` field on `AuctionSearchResultDto` (Task 2a). Enables outcome-aware status chips for ended auctions in the Curator Tray.

**Out of scope (explicitly deferred):**

- **Public `StatsBar` on the homepage.** The backend `/stats/public` endpoint is ready and returns the four-count payload, but rendering a "X active auctions · L$ Y bids" bar is a prior product decision: do not display low activity numbers that read as a liability rather than social proof. Tracked as a new `DEFERRED_WORK.md` entry ("Enable public StatsBar on homepage when activity thresholds warrant it"). Phase 1 homepage shows `Hero`, three featured rails, `HowItWorksSection`, `FeaturesSection`, `CtaSection` — no stats bar.
- **Region autocomplete on `DistanceSearchBlock`.** Free-form text input with server-side validation on submit. Autocomplete is a Phase 2 polish that needs its own design pass (search API for region names, debounce, keyboard nav).
- **Infinite scroll on browse grid.** Numbered pagination per the user's decision. Infinite scroll stays a deferred cross-surface item alongside the existing `BidHistoryList` deferral.
- **Client-side optimistic intent replay after login.** If an anonymous visitor clicks a heart, the toast prompts sign-in — after login they click the heart again. No sessionStorage-backed "replay the save" flow. YAGNI for Phase 1.
- **Bidding UX** — BidPanel, BidSheet, CountdownTimer, OutbidToast all ship from Epic 04 sub-spec 2 and stay untouched. This sub-spec only reads `endsAt`, not the live bid state.
- **Admin surfaces** — no admin-only routes or controls in scope. Admin tooling lands in Epic 10.
- **LSL / in-world surfaces** — out of scope; Epic 11.

---

## §2 — Sub-spec split rationale

Epic 07 (tasks 01-04 from `docs/implementation/epic-07/`) ships across two sub-specs:

- **Sub-spec 1 (shipped, PR #25):** search API, featured endpoints, public stats, saved-auctions model + API, listing-detail endpoint extension, maturity modernization, new `Auction.title` field (backend validation + DTO).
- **Sub-spec 2 (this spec, frontend-primary):** browse page, homepage featured rows, listing-detail page rebuild, Curator Tray drawer, `/users/{id}/listings` page, profile `generateMetadata`, listing-wizard `title` input. Plus two tiny backend additions discovered during frontend brainstorming (`endOutcome` DTO field, toast `warning` variant).

The split matches the Epic 04 / 05 precedent. Frontend-primary rather than frontend-only because real frontend design work surfaces small backend gaps that are cheaper to fix in the consuming task than to defer to a future backend-only pass. See §18 for the full list of small backend additions and their rationale.

---

## §3 — Architecture overview

**One-paragraph summary.** Next.js 16 server components are the default; client components are reached for only where they own state, run effects, or need browser APIs. Every public list surface (`/browse`, `/users/{id}/listings`, homepage featured rows, `/auction/{id}`) does its initial fetch server-side and hydrates a client component with `initialData`. URL searchParams are the source of truth for filter/sort/pagination state on the browse + seller-listings + `/saved` surfaces; Next.js `router.replace` updates the URL without a history entry on each filter change. React Query, already the project's TanStack-Query-backed cross-component cache, owns client-side freshness: list queries, the `/auctions/{id}` detail, and the `/me/saved/*` set all live as keyed entries and invalidate through mutations. A single cross-surface hook `useSavedAuctions` backs the heart button on every `ListingCard` — no separate Context layer, no provider drilling, because React Query already is the cache. The shared DTO from sub-spec 1 (`AuctionSearchResultDto`) drives one canonical `ListingCard` component with three variants (`default`, `compact`, `featured`) used on browse, featured rails, seller listings, Curator Tray list, and `/saved`.

**Route map.**

| Route | Render | Fetches |
|---|---|---|
| `/` | Server | `Promise.allSettled` on `/auctions/featured/ending-soon`, `/featured/just-listed`, `/featured/most-active`. No stats-bar fetch (product-deferred). |
| `/browse` | Server | `/auctions/search` with searchParams-derived query. |
| `/users/[id]/listings` | Server | `/users/{id}` + `/auctions/search?seller_id={id}&...` in parallel. |
| `/saved` | Server shell, client content | Client-side `/me/saved/auctions` via `useSavedAuctions`. Server renders unauth empty state. |
| `/auction/[id]` | Server | `/auctions/{id}` (via React `cache()`-wrapped fetch, shared with `generateMetadata`). |
| `/users/[id]` | Server | `/users/{id}` (via React `cache()`-wrapped fetch, shared with `generateMetadata`). |
| `/listings/(verified)/create` | Existing client component | Existing flow. Task 1 adds title input to the wizard form. |

**Data fetching shape.**

- **Public list endpoints** (`/search`, `/featured/*`): server component fetches using the built-in `fetch` with `Cache-Control` honored (backend sets `public, max-age=30` / `60`). Payload passed to a client `Shell` component as `initialData`. Client hooks uses `useQuery({ initialData })` keyed on the canonical query — the first render needs no client fetch.
- **Authenticated endpoints** (`/me/saved/*`): client-only via `useQuery({ enabled: isAuthenticated })`. No SSR — the server has no auth context, and the saved list is per-user private.
- **Detail endpoint** (`/auctions/{id}`): server fetches via `cache()`-wrapped function shared with `generateMetadata`. Client side keeps the existing WebSocket subscription from Epic 04 for live bid updates.
- **Next.js 16 caveat.** Automatic fetch deduplication across `generateMetadata` and the page component was removed. Use React `cache()` to wrap the fetch function so both calls share a single in-flight promise. Implementer must verify the exact pattern against `node_modules/next/dist/docs/` per `frontend/AGENTS.md`.

**URL state encoding.**

- **Multi-value filters** (`maturity`, `tags`, `verification_tier`): CSV. `?maturity=GENERAL,MODERATE&tags=BEACHFRONT,ROADSIDE`. Backend already accepts both CSV and repeat — CSV keeps URLs short and is the Next.js `searchParams.get` default ergonomic.
- **Scalars**: only emitted when not at default. `?sort=newest` and `?page=0` are omitted.
- **Defaults**: defined once in a `defaultAuctionSearchQuery` constant. A utility `queryFromSearchParams(searchParams): AuctionSearchQuery` and `searchParamsFromQuery(query): URLSearchParams` round-trip cleanly — unit-tested.
- **Canonicalization for React Query keys**: same function the backend spec uses — alphabetized keys, null fields dropped, tag sets sorted — then SHA-256'd for the key. The frontend replicates this as a JS implementation in `lib/search/canonical-key.ts`. Same filters in different query-string orders produce the same React Query key.
- **`seller_id`** is never in browse URL. On `/users/[id]/listings` it's a route param, not a searchParam — the page pins it server-side when building the `AuctionSearchQuery`.

**Cross-surface state.**

- `useSavedAuctions.ts` (lib/hooks): three React Query-backed hooks. No React Context, no Zustand, no global state beyond the React Query cache.
- Tray open/closed: plain `useState` in a layout-level `CuratorTrayMount` wrapper component. One boolean, no provider, no store.
- Toast is the existing `ToastProvider` from Epic 04, widened in Task 5 to accept a `warning` variant + structured action button.

**SSR vs browser URLs.** Server fetches from `http://backend:8080` (Docker in-network); browser fetches from `NEXT_PUBLIC_API_URL`. Existing convention.

---

## §4 — URL-as-source-of-truth for list surfaces

Every list surface that accepts filters/sort/pagination keeps the URL as the single source of truth.

### §4.1 Round-trip codec

```ts
// lib/search/url-codec.ts
export function queryFromSearchParams(sp: URLSearchParams): AuctionSearchQuery
export function searchParamsFromQuery(query: AuctionSearchQuery): URLSearchParams
```

Round-trip invariant — `queryFromSearchParams(searchParamsFromQuery(q)) ≡ q` for every valid query. Unit-tested with parameterized cases for every filter.

**Multi-value encoding:**
```
{ maturity: ["GENERAL", "MODERATE"] }  ↔  ?maturity=GENERAL,MODERATE
```

**Defaults dropped:**
```
{ sort: "newest", page: 0, size: 24 }  ↔  (no params)
```

**Range pairs emitted whole:**
```
{ minPrice: 1000, maxPrice: 50000 }  ↔  ?min_price=1000&max_price=50000
```

**Unknown param names** — ignored silently (forward-compat, matches backend behavior per sub-spec 1 §6.6).

### §4.2 Filter-change propagation

- User interacts with a filter control → `onChange` calls `setQueryField(field, value)`.
- `setQueryField` composes the new query, calls `searchParamsFromQuery`, calls `router.replace(newUrl, { scroll: false })`.
- Next.js re-runs the page as a client-side navigation; the `useAuctionSearch` hook's query key changes; React Query refetches.
- Scroll position stays put (`scroll: false`).
- Browser back/forward works — navigating back restores the prior URL, which restores the prior query, which refetches.

### §4.3 Debouncing

- Range sliders / text inputs: debounce 300ms before the URL update fires.
- Checkboxes / radios: instant, no debounce.
- Mobile staged behavior (§7.4): the user drags sliders and changes checkboxes inside the sheet, no URL fires at all until Apply.

---

## §5 — Component inventory

All new components live under `frontend/src/components/` in feature-based folders per CONVENTIONS.md. New primitives land in `components/ui/`; domain composites in `components/auction/`, `components/browse/`, `components/curator/`, `components/marketing/`.

### §5.1 New UI primitives (`components/ui/`)

| Component | First use | Reuse path | Task |
|---|---|---|---|
| `RangeSlider` | Browse filter sidebar (price, size) | Any future dual-handle range control | 2a |
| `Pagination` | Browse grid, seller listings, Curator Tray | Every paginated list surface | 2a |
| `BottomSheet` | Mobile filter sheet | Mobile Curator Tray; future mobile BidSheet refactor | 2a |
| `Drawer` | Curator Tray desktop | Admin tooling panels, future right-panel needs | 5 |
| `Lightbox` | Auction gallery | Seller galleries, profile photo zoom | 4 |
| `ActiveFilterBadge` | Browse active-filters row | Any filtered-list surface | 2a |
| `StatusChip` | `ListingCard` status overlay | Auction detail status rendering | 2a |

`BottomSheet` and `Drawer` share a single inner `GlassSurface` primitive that implements the glassmorphic backdrop from DESIGN.md (80% `surface-container-lowest` + backdrop blur + ghost border). The two compose `GlassSurface` with different anchoring (bottom-up slide vs right-side slide).

### §5.2 Browse components (`components/browse/`, Task 2b)

| Component | Role |
|---|---|
| `BrowseShell` | Client. Top-level URL↔query state. Composes sidebar + main + pagination. Accepts `initialPage` and `initialQuery` for SSR seeding. Accepts optional `fixedFilters` prop for `seller_id` pinning on seller listings. |
| `FilterSidebar` | Desktop fixed-left sidebar. On mobile, hidden; its trigger button opens a `BottomSheet` with `FilterSidebarContent`. |
| `FilterSidebarContent` | Filter groups composition. Rendered in both the desktop sidebar and the mobile sheet. Accepts `mode: "immediate" | "staged"` — staged mode keeps local state until an `onCommit` is called (used by the sheet's Apply button). |
| `FilterSection` | Collapsible group with uppercase `.label` header. |
| `SortDropdown` | Six sort options; "Nearest" disabled when no `near_region`. |
| `DistanceSearchBlock` | Region text input + distance slider. Server error surfacing. |
| `ActiveFilters` | Row of removable chips using `ActiveFilterBadge`. |
| `ResultsHeader` | Title + result count + view toggle + sort dropdown. |
| `ResultsGrid` | Maps `AuctionSearchResultDto[]` to `ListingCard`. Skeleton, error, empty states. |
| `ResultsEmpty` | Two variants — "no auctions right now" and "no filters match". |

### §5.3 Auction components (`components/auction/`)

New in Task 4:

| Component | Role |
|---|---|
| `AuctionGallery` | Hero + side thumbs + strip. Click → Lightbox. Falls back to `parcelSnapshotUrl` when `photos[]` is empty. |
| `VisitInSecondLifeBlock` | Two buttons — viewer protocol + maps URL. Explanatory copy. |
| `BreadcrumbNav` | Browse → Region → Title. sessionStorage-backed "last browse URL" for the Browse link. JSON-LD microdata. |
| `ParcelLayoutMap` | Placeholder card reserving space for Phase 2 parcel-layout feature. Hidden on mobile. |

Modified in Task 4:
- `SellerProfileCard` — replaced (existing minimal version removed). New fields: rating, review count, completed sales, completion rate percentage, member-since, New Seller badge, link to `/users/{id}`.

Modified in Task 1:
- `AuctionHero` — reads new `title` field; demoted parcel name + region to subtitle.

Modified in Task 2a:
- `ListingCard` — brand new canonical card with three variants (§8). This component does not exist in the pre-sub-spec codebase; the older `ListingPreviewCard` is distinct (shown inside the listing wizard preview only, stays as-is).

### §5.4 Listing components (`components/listing/`, Task 1)

Modified:
- `ListingWizardForm` — adds Title section at the top (above parcel lookup).
- `ListingPreviewCard` — reads new `title`, displays above parcel name.
- `MyListingsTab` / `ListingSummaryRow` — display `title` as primary label, parcel name + region as secondary.

### §5.5 Marketing components (`components/marketing/`, Task 3)

| Component | Role |
|---|---|
| `FeaturedRow` | Labeled horizontal rail with header, "View all →" link, scrollable strip of `ListingCard variant="compact"`. Single component, used three times with different props. |

No `StatsBar` component ships in Phase 1 — see §1 Out of Scope and the `DEFERRED_WORK.md` entry.

### §5.6 Curator components (`components/curator/`, Task 5)

| Component | Role |
|---|---|
| `CuratorTrayMount` | Layout-level wrapper. Owns the tray open/closed `useState` boolean. Renders `CuratorTrayTrigger` in the nav + `CuratorTray` when open. Only mounted for auth users. |
| `CuratorTrayTrigger` | Heart-count badge in the top navigation. Reads `useSavedIds().ids.size`. |
| `CuratorTray` | Responsive shell. Renders `Drawer` on desktop, `BottomSheet` on mobile, with the same child `CuratorTrayContent`. |
| `CuratorTrayContent` | List UI. Accepts `query: AuctionSearchQuery` + optional `onQueryChange`. When `onQueryChange` is omitted (drawer context), owns query state internally with `useState`. When provided (`/saved` page), the host wires it to `router.replace`. |
| `CuratorTrayHeader` | Title ("Your Curator Tray (N saved)") + `SortDropdown` + `statusFilter` dropdown ("Active only / All / Ended"). |
| `CuratorTrayEmpty` | "Save parcels to review them here" + browse CTA. |

### §5.7 Hooks and lib (`lib/`)

| File | Exports |
|---|---|
| `lib/api/auctions-search.ts` | `searchAuctions(query)`, `fetchAuction(id)` — typed clients |
| `lib/api/saved.ts` | `fetchSavedIds()`, `fetchSavedAuctions(query)`, `saveAuction(id)`, `unsaveAuction(id)` |
| `lib/hooks/useAuctionSearch.ts` | `useAuctionSearch(query, { initialData })` |
| `lib/hooks/useSavedAuctions.ts` | `useSavedIds()`, `useToggleSaved()`, `useSavedAuctions(query)` |
| `lib/search/url-codec.ts` | `queryFromSearchParams(sp)`, `searchParamsFromQuery(q)`, `defaultAuctionSearchQuery` |
| `lib/search/canonical-key.ts` | `canonicalKey(query): string` — for React Query keys |
| `lib/search/status-chip.ts` | `deriveStatusChip(auction): { label, tone }` |
| `lib/sl/slurl.ts` | `viewerProtocolUrl(region, x, y, z)`, `mapUrl(region, x, y, z)` — encoding-aware |

### §5.8 Component reuse (honored, per CONVENTIONS.md)

- `TagSelector` (existing `components/listing/`) — reused by the browse filter sidebar.
- `CountdownTimer` (existing `components/ui/`) — reused by every card.
- `ReputationStars` (existing `components/user/`) — reused by `SellerProfileCard`.
- `NewSellerBadge` (existing `components/user/`) — reused by `SellerProfileCard`.
- `EmptyState` (existing `components/ui/`) — reused by every empty-state composition.
- `StatusBadge` (existing `components/ui/`) — new `StatusChip` is a different primitive (overlay on card image with icon + tone); `StatusBadge` stays for inline/header status display.

---

## §6 — Hook contracts

### §6.1 `useAuctionSearch(query, { initialData? })`

```ts
function useAuctionSearch(
  query: AuctionSearchQuery,
  options?: { initialData?: PagedResponse<AuctionSearchResultDto> }
): UseQueryResult<PagedResponse<AuctionSearchResultDto>>
```

- Query key: `["auctions", "search", canonicalKey(query)]`.
- Stale time: 30s (matches backend Redis TTL).
- `initialData` seeds the cache from SSR payload — first client render needs no fetch.
- On 429 `TOO_MANY_REQUESTS`: surfaces the error with `code` and `Retry-After` — `ResultsGrid` renders a "Too many searches — try again in Ns" message.

### §6.2 `useSavedIds()`

```ts
function useSavedIds(): {
  ids: Set<number>;
  isSaved: (auctionId: number) => boolean;
  isLoading: boolean;
}
```

- Query key: `["saved", "ids"]`.
- `enabled: isAuthenticated` from the existing auth hook.
- Stale time: `Infinity` within a session (data only changes via local mutations).
- Unauth: returns `{ ids: new Set(), isSaved: () => false, isLoading: false }` — no null-check needed in consumers.

### §6.3 `useToggleSaved()`

```ts
function useToggleSaved(): {
  toggle: (auctionId: number) => Promise<void>;
  isPending: boolean;
}
```

- Unauth branch: surfaces `toast.warning({ title: "Sign in to save parcels", action: { label: "Sign in", onClick: () => router.push("/login?next=" + encodeURIComponent(currentPath)) } })`. No mutation fires.
- Auth branch:
  - Optimistic update on `["saved", "ids"]` — adds/removes ID from the Set in cache immediately.
  - Fires `POST /me/saved` (save) or `DELETE /me/saved/{id}` (unsave).
  - Rollback on error.
  - `onSettled`: `queryClient.invalidateQueries({ queryKey: ["saved", "ids"] })` AND `queryClient.invalidateQueries({ queryKey: ["saved", "auctions"], refetchType: "active" })`. The `refetchType: "active"` on the auctions invalidation ensures a background fetch doesn't fire for a closed drawer.
- Error handling:
  - 409 `SAVED_LIMIT_REACHED`: `toast.error("Curator Tray is full (500 saved). Remove some to add more.")`, rollback optimistic.
  - 403 `CANNOT_SAVE_PRE_ACTIVE`: `toast.error("This auction isn't available to save yet.")`, rollback. (Shouldn't normally hit — heart isn't rendered on pre-active cards — but defense-in-depth.)
  - 404 `AUCTION_NOT_FOUND`: `toast.error("That auction no longer exists.")`, invalidate both saved caches so the UI cleans up.

### §6.4 `useSavedAuctions(query)`

```ts
function useSavedAuctions(
  query: AuctionSearchQuery & { statusFilter?: "active_only" | "all" | "ended_only" }
): UseQueryResult<PagedResponse<AuctionSearchResultDto>>
```

- Query key: `["saved", "auctions", canonicalKey(query)]`.
- `enabled: isAuthenticated`.
- `statusFilter` defaults to `"active_only"` (matches backend default).
- Stale time: 0 — tray always refetches on open (cheap, auth call, bounded to 500 rows).

---

## §7 — Filter sidebar + search surface mapping

### §7.1 Group ordering (top to bottom)

1. **Distance search block** (top, own section): region text input + distance slider. Explanatory text.
2. **Price**: `RangeSlider` + two L$ inputs. Max from initial payload, rounded up to nearest L$10,000.
3. **Size**: `RangeSlider` + two sqm inputs. Bounds `512 ≤ area ≤ 65536`.
4. **Maturity**: three checkboxes (General, Moderate, Adult). All selected by default.
5. **Parcel tags**: reuses existing `TagSelector`. "Match: any / all" dropdown toggles `tags_mode`.
6. **Reserve status**: four radios (All / Reserve met / Reserve not met / No reserve).
7. **Snipe protection**: three radios (Any / Enabled / Disabled).
8. **Verification tier**: three checkboxes (Script / Bot / Human).
9. **Ending within**: four radios (Any time / 1 hour / 6 hours / 24 hours).

Hidden from the sidebar: `seller_id` (route-pinned only), `status` (ACTIVE-only in Phase 1).

### §7.2 Sort controls

`SortDropdown` above the grid (outside the sidebar). Options: Newest First, Ending Soonest, Most Bids, Lowest Price, Largest Area, Nearest. "Nearest" disabled with hint "Enter a region name to enable" when no `near_region`.

### §7.3 Active filter chips

Row of `ActiveFilterBadge` between sort controls and grid. One chip per applied filter that's not at default. X drops the filter (partial reset). "Clear all" button at the end resets to defaults.

### §7.4 Apply behavior — desktop immediate vs mobile staged

- **Desktop** (`FilterSidebarContent mode="immediate"`): changes propagate through `router.replace` immediately, debounced 300ms for range/text inputs, instant for checkboxes/radios.
- **Mobile** (`FilterSidebarContent mode="staged"` inside `BottomSheet`): changes stay local. "Apply filters" sticky button at the bottom commits all staged changes in one `router.replace`. Closing the sheet without applying discards all staged changes (the sheet remounts `FilterSidebarContent` with the current URL query on next open).
- The Apply button shows only the label "Apply filters" — no predicted result count. A dry-run count would require extra backend calls (burning rate-limit tokens from the 60rpm bucket) to produce a number that's potentially 30s stale from Redis cache.

### §7.5 Region validation

Free-form region text input. No pre-validation call; the real search returns `400 REGION_NOT_FOUND` or `503 REGION_LOOKUP_UNAVAILABLE` when the user applies filters. The error envelope surfaces as an inline message under the input via the `DistanceSearchBlock` component reading the search query's error state. One HTTP call per user action.

### §7.6 Empty-state copy (composed via existing `EmptyState`)

- Zero filters, zero results → "No active auctions right now. Check back soon." + home CTA.
- Filters applied, zero results → "No auctions match your filters." + "Clear all filters" CTA.
- Fetch failed → "Something went wrong loading auctions." + retry.
- Region upstream failure (503) → "We couldn't locate that region. Try another name or remove the distance filter."

---

## §8 — `ListingCard` structure and variants

### §8.1 Anatomy (variant="default")

```
┌─────────────────────────────────────┐
│ ┌─────────────────────────────┐ ♡  │  image: primaryPhotoUrl (server-resolved)
│ │       parcel snapshot       │    │  heart overlay: top-right, 32px tap target
│ │                             │    │  StatusChip: top-left
│ │                       1024m²│    │  area pill: bottom-right over image
│ └─────────────────────────────┘    │
│                                     │
│  PREMIUM WATERFRONT — MUST SELL!   │  title (Manrope, title-lg, -0.02em)
│  Bayside Cottage Lot · Tula         │  parcel name + region (body-md, on-surface-variant)
│                                     │
│  L$ 12,500              🛡 5min     │  current bid (display-sm) + snipe chip
│  7 bids · Reserve met ✓             │  bid count + reserve status
│                                     │
│  02d 14h 22m                        │  countdown (live)
│                                     │
│  BEACHFRONT  ROADSIDE  +2           │  tag pills (first 3 + overflow count)
│                                     │
│  MODERATE · BOT ✓                   │  maturity + verification badges
└─────────────────────────────────────┘
```

### §8.2 Variant matrix

| Element | `default` | `compact` | `featured` |
|---|---|---|---|
| Image aspect | 4:3 | 4:3 | 16:9 tall |
| Title lines | 2, truncated | 1, truncated | 2, no truncation |
| Bid count + reserve | both | count only | both, more prominent |
| Tag pills | top 3 + `+N` | top 2 + `+N` | top 5 |
| Countdown | yes | yes | yes, larger |
| Distance chip | when present | when present | when present |
| Heart overlay | yes | yes | yes |
| Grid width (desktop) | ~340px (3-col at 1280px) | ~280px (rail) | ~520px (2x default) |

### §8.3 Status chip mapping (`deriveStatusChip`)

| Input | Label | Tone |
|---|---|---|
| `status=ACTIVE`, `endsAt > now + 1h` | `LIVE` | red accent |
| `status=ACTIVE`, `endsAt ≤ now + 1h` | `ENDING SOON` | deep red, pulse |
| `endOutcome=SOLD` or `BOUGHT_NOW` | `SOLD` | muted green |
| `endOutcome=RESERVE_NOT_MET` | `RESERVE NOT MET` | muted warning |
| `endOutcome=NO_BIDS` | `NO BIDS` | muted grey |
| `status=CANCELLED` | `CANCELLED` | muted grey |
| `status=SUSPENDED` | `SUSPENDED` | muted warning |
| fallback | `ENDED` | muted grey |

Requires `endOutcome` on `AuctionSearchResultDto` — additive backend change, see §18.

### §8.4 Heart button behavior

- Unfilled outline when `!useSavedIds().isSaved(id)`, filled `primary` (amber) when saved.
- Tap: `useToggleSaved().toggle(id)` — unauth surfaces toast; auth fires optimistic mutation.
- Disabled + not rendered when `auction.status ∈ {DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED}`.
- Nested interactive: click stops propagation to prevent card-link navigation.
- Separate `aria-label` ("Save Bayside Cottage Lot" / "Unsave Bayside Cottage Lot").

### §8.5 Interactive states

- Whole card is a link to `/auction/{id}`. Tab-focusable at card level.
- Card hover: subtle `scale(1.01)` + elevate using DESIGN.md tokens.
- Heart button hover: separate focus ring (nested interactive).
- Touch: `:active` token highlight.

### §8.6 Accessibility

- Card `aria-label` composes title + parcel region + current bid + time remaining.
- Heart button has its own `aria-label`.
- Status chip, maturity, verification badges are `aria-hidden="true"` (content covered by card aria-label).

### §8.7 Explicit exclusions

- No seller identity on the card — detail page territory.
- No buy-now price — crowds the card; detail page handles it.
- No description preview — too noisy for a card.
- No snapshot-fallback logic on the client — server already resolves `primaryPhotoUrl`.

---

## §9 — Browse page + seller listings (Task 2b)

### §9.1 `app/browse/page.tsx`

```tsx
// Server component — no "use client"
import { queryFromSearchParams } from "@/lib/search/url-codec";
import { searchAuctions } from "@/lib/api/auctions-search";
import { BrowseShell } from "@/components/browse/BrowseShell";

export default async function BrowsePage({ searchParams }) {
  const query = queryFromSearchParams(new URLSearchParams(searchParams));
  const initialData = await searchAuctions(query);
  return <BrowseShell initialQuery={query} initialData={initialData} />;
}
```

### §9.2 `app/users/[id]/listings/page.tsx`

```tsx
export default async function SellerListingsPage({ params, searchParams }) {
  const sellerId = Number(params.id);
  const baseQuery = queryFromSearchParams(new URLSearchParams(searchParams));
  const query = { ...baseQuery, sellerId };
  const [user, initialData] = await Promise.all([
    fetchUser(sellerId),
    searchAuctions(query),
  ]);
  return (
    <>
      <SellerHeader user={user} />
      <BrowseShell
        initialQuery={query}
        initialData={initialData}
        fixedFilters={{ sellerId }}
        hiddenFilterGroups={["distance", "seller"]}
      />
    </>
  );
}
```

`fixedFilters` means "user can't change these filters — render them as immutable and always inject them into every sub-query." `hiddenFilterGroups` hides the distance block (region-scoped pages make no sense with `near_region`) — but sort, pagination, and the rest of the filter set stay.

### §9.3 `BrowseShell` responsibilities

- Holds `query` state, seeded from `initialQuery` prop.
- Listens to `useSearchParams()` changes; if URL changes (back button), syncs `query` back.
- Calls `useAuctionSearch(query, { initialData })` — cache-hit on initial render.
- Renders `FilterSidebar` (desktop) and `FilterSidebar mobile trigger` (mobile).
- Renders `ResultsHeader` with result count + view toggle + sort.
- Renders `ActiveFilters` chips.
- Renders `ResultsGrid` with loading / empty / error states.
- Renders `Pagination` at the bottom.
- Exposes `setQueryField(field, value)` and `setQuery(newQuery)` callbacks.
- On every query change: `router.replace(..., { scroll: false })` — URL is the source of truth.
- Writes the current URL to `sessionStorage["last-browse-url"]` on every change — used by the detail page's `BreadcrumbNav` back-link.

### §9.4 SSR + hydration flow

1. Server runs `page.tsx`, fetches with searchParams-derived query.
2. Payload passed to `BrowseShell` as `initialData`.
3. Client mounts `BrowseShell`. `useAuctionSearch` sees `initialData` matching the key → no fetch.
4. User changes a filter → `router.replace(newUrl)` → URL changes → searchParams change → `BrowseShell` re-derives `query` from URL → `useAuctionSearch` key changes → React Query fetches new data → `ResultsGrid` re-renders.

---

## §10 — Homepage featured rows (Task 3)

### §10.1 `app/page.tsx`

```tsx
export default async function HomePage() {
  const [endingSoon, justListed, mostActive] = await Promise.allSettled([
    fetchFeatured("ending-soon"),
    fetchFeatured("just-listed"),
    fetchFeatured("most-active"),
  ]);
  return (
    <>
      <Hero />
      <FeaturedRow title="Ending Soon" sortLink="/browse?sort=ending_soonest"
                   result={endingSoon} />
      <FeaturedRow title="Just Listed" sortLink="/browse?sort=newest"
                   result={justListed} />
      <FeaturedRow title="Most Active" sortLink="/browse?sort=most_bids"
                   result={mostActive} />
      <HowItWorksSection />
      <FeaturesSection />
      <CtaSection />
    </>
  );
}
```

`Promise.allSettled` — one failing rail's 5xx doesn't fail the page.

### §10.2 `FeaturedRow` component

- Inputs: `title`, `sortLink`, `result: PromiseSettledResult<PagedResponse<AuctionSearchResultDto>>`.
- Rejected result → renders an empty-state placeholder ("Ending Soon auctions are temporarily unavailable") with no cards. Page still looks fine.
- Fulfilled result with empty content → renders empty placeholder ("No listings ending soon right now").
- Fulfilled result with content → renders horizontal rail of `ListingCard variant="compact"`.
- Desktop: horizontal scroll with arrow controls (`<` `>`).
- Mobile: horizontal scroll with CSS scroll-snap, no arrows.
- "View all →" link at the top-right of the rail header.

### §10.3 No StatsBar

Product decision. See §1 Out of Scope. `DEFERRED_WORK.md` entry carries the trigger ("when activity thresholds warrant it").

### §10.4 No other homepage changes

`Hero`, `HowItWorksSection`, `FeaturesSection`, `CtaSection` ship from Task 01-10 and stay untouched. Only the three `FeaturedRow` slot into the existing composition between `Hero` and `HowItWorksSection`.

---

## §11 — Auction detail rebuild (Task 4)

### §11.1 SSR + `cache()` pattern

```ts
// app/auction/[id]/page.tsx
import { cache } from "react";
import { fetchAuction } from "@/lib/api/auctions-search";

const getAuction = cache((id: string) => fetchAuction(Number(id)));

export async function generateMetadata({ params }) {
  const auction = await getAuction(params.id);
  return {
    title: `${auction.title} · SLPA`,
    description: `${auction.parcel.name} in ${auction.parcel.region} · ${auction.parcel.area} sqm · L$ ${auction.currentBid.toLocaleString()}`,
    openGraph: {
      title: auction.title,
      description: ...,
      images: [auction.primaryPhotoUrl ?? auction.parcel.snapshotUrl],
      type: "website",
    },
    twitter: { card: "summary_large_image", ... },
  };
}

export default async function AuctionDetailPage({ params }) {
  const auction = await getAuction(params.id);
  return <AuctionDetailClient initialData={auction} />;
}
```

`cache()` from React deduplicates the two fetches into one in-flight request per page render. Implementer verifies the exact Next.js 16 pattern against `node_modules/next/dist/docs/` per `frontend/AGENTS.md`.

### §11.2 Layout additions (desktop)

```
BreadcrumbNav: Browse → Tula → Premium Waterfront           ← NEW
─────────────────────────────────────────────────────────
AuctionGallery (hero + side thumbs + strip)     BidPanel  ← NEW gallery
[ click any image → Lightbox ]                  (existing)
─────────────────────────────────────────────
AuctionHero (title, region, chips)
ParcelInfoPanel (existing)
─────────────────────────────────────────────────────────
VisitInSecondLifeBlock                                      ← NEW
─────────────────────────────────────────────────────────
SellerProfileCard (enriched, REPLACES existing minimal)     ← REPLACED
─────────────────────────────────────────────────────────
ParcelLayoutMap placeholder (copy-only, hidden on mobile)   ← NEW
─────────────────────────────────────────────────────────
BidHistoryList (existing)
```

Mobile: gallery hero → title → sticky `BidSheet` (existing) → seller card → VisitInSL → parcel info → bid history.

### §11.3 `AuctionGallery`

- Inputs: `photos: AuctionPhotoDto[]`, `parcelSnapshotUrl: string`.
- Empty `photos`: fall back to single-image gallery with `parcelSnapshotUrl`.
- Non-empty `photos`: hero = `photos[0]`, side stack = `photos[1]` and `photos[2]`, horizontal strip = `photos[3..]`.
- Click any image → `<Lightbox images={photos} initialIndex={i}>`.
- Keyboard: Tab focuses hero → Enter opens Lightbox at index 0.
- Performance: hero uses Next.js `Image` with `priority` (LCP); non-hero images `loading="lazy"`.
- Accessibility: `<figure>` + `<figcaption>` per photo (uses `photo.caption` when present, else "Photo N of M").

### §11.4 `Lightbox`

- Dark-overlay modal (`rgba(25, 28, 30, 0.95)` per DESIGN.md `on-surface` warmth).
- Portaled to `document.body` (avoids z-index fights with `StickyBidBar`).
- Keyboard: ← →, ESC, Home/End.
- Touch: left/right swipe = next/prev, swipe-down = close.
- Focus trap + focus return on close + `inert` on background.
- Body scroll locked.
- Thumbnails strip at bottom auto-scrolls to keep current index visible.
- Counter ("3 / 8") top-left, close button top-right.

### §11.5 `SellerProfileCard` (replaces existing)

- Inputs: enriched seller block from the `/auctions/{id}` response:
  ```ts
  { id, displayName, avatarUrl, averageRating?, reviewCount?, completedSales, completionRate?, memberSince }
  ```
- Layout: avatar (56px) | display name + member-since | `ReputationStars` + review count | completed-sales stat | completion-rate stat | `NewSellerBadge` when `completedSales < 3` OR `completionRate === null`.
- `completionRate` display: `Math.round(rate * 100) + "%"`. `null` → hidden row with "Too new to calculate" text below.
- Member since: `"Member since Nov 2025"` (month + year only).
- Card is a link to `/users/{id}`.

### §11.6 `VisitInSecondLifeBlock`

- Two buttons: "Open in Viewer" and "View on Map".
- Viewer protocol: `secondlife:///${regionName}/${x}/${y}/${z}` — spaces preserved (SL viewer handles them raw).
- Map URL: `https://maps.secondlife.com/secondlife/${encodeURIComponent(regionName)}/${x}/${y}/${z}` — `%20` for spaces.
- Null/0 positions → fallback `/${regionName}/128/128/0` (SL convention for region-center).
- Explanatory line below: "Open this parcel directly in Second Life, or preview it on the official map."
- Encoding logic lives in `lib/sl/slurl.ts` with unit tests for regions with spaces, apostrophes, and unicode.

### §11.7 `BreadcrumbNav`

- Three levels: `Browse` → `[Region]` → `[Title truncated at 40 chars]`.
- `Browse` href: `sessionStorage.getItem("last-browse-url") ?? "/browse"`. `BrowseShell` writes this key on every URL change (see §9.3).
- `[Region]` href: `/browse?region=${encodeURIComponent(region)}` — coarser region-scoped search; does not preserve the user's previous filters (by design — filter preservation would require encoding the full query into the breadcrumb href).
- JSON-LD `BreadcrumbList` microdata `<script>` tag for SEO.

### §11.8 `ParcelLayoutMap` placeholder

- Card with "Parcel map coming soon" + small map icon.
- Hidden on mobile (nothing to see).
- Reserves space in the desktop layout so Phase 2 work doesn't re-shuffle.

### §11.9 Profile page OG metadata (`app/users/[id]/page.tsx`)

```ts
const getUser = cache((id: string) => fetchUser(Number(id)));

export async function generateMetadata({ params }) {
  const user = await getUser(params.id);
  return {
    title: `${user.displayName} · SLPA`,
    description: user.bio ? truncate(user.bio, 200) : `${user.displayName} on SLPA`,
    openGraph: { title: user.displayName, description: ..., images: user.avatarUrl ? [user.avatarUrl] : [] },
    twitter: { card: "summary" },
    robots: { index: true, follow: true },
  };
}
```

Implementer verifies `/users/{id}` returns `bio`. If missing, small backend addition lands in Task 4.

---

## §12 — Curator Tray (Task 5)

### §12.1 Mount point

`CuratorTrayMount` is rendered once at the app-root layout level, only when auth. Owns:
- `useState<boolean>(false)` — tray open/closed.
- Renders `CuratorTrayTrigger` inside the top nav via a React portal to the nav's tray slot.
- Conditionally renders `CuratorTray` when open.

### §12.2 `CuratorTray` (responsive shell)

- Desktop (≥ md breakpoint): renders a `Drawer` anchored right, 480px wide.
- Mobile (< md breakpoint): renders a `BottomSheet` with 75vh max height.
- Both variants contain the same child `CuratorTrayContent`.
- Body-scroll locked when open; ESC closes; background inert.

### §12.3 `CuratorTrayContent`

- Inputs: `query: AuctionSearchQuery`, optional `onQueryChange?: (q: AuctionSearchQuery) => void`.
- If `onQueryChange` provided (used by `/saved` page host): lifts state up — parent owns query, passes current + change callback. Parent wires `onQueryChange` to `router.replace`.
- If `onQueryChange` absent (used by `CuratorTray` drawer host): owns `query` with internal `useState`. Drawer query state is NOT URL-synced — the tray is ephemeral, not a destination.
- Renders `CuratorTrayHeader` (title + sort + statusFilter dropdowns) + `ResultsGrid variant="compact"` + `Pagination`.
- Calls `useSavedAuctions(query)`.

### §12.4 `app/saved/page.tsx`

- Server component renders the page shell. Unauth → server-rendered "Sign in to see your saved parcels" empty state + sign-in CTA.
- Auth: server renders `<SavedPageContent />` client component. That component derives `query` from searchParams, calls `useSavedAuctions`, renders `CuratorTrayContent` with `onQueryChange` wired to `router.replace`.
- `generateMetadata` returns `{ robots: { index: false, follow: false } }` — no public content.

### §12.5 `CuratorTrayTrigger`

- Heart-count badge in the top nav.
- Count: `useSavedIds().ids.size`.
- Display: numbers 1–99 literal; 100+ → `99+`.
- Hidden (not rendered at all) when unauth — no heart badge that routes to login (confusing).
- Loading state: renders `—` in the count slot.

### §12.6 Heart wiring on existing surfaces

Every surface that renders a `ListingCard` picks up the heart behavior automatically — the card reads `useSavedIds()` + `useToggleSaved()` internally. Task 5 touches no other component's JSX.

### §12.7 Toast primitive widening

Existing `ToastProvider` from Epic 04 + Epic 04-2 ships `success` / `error` variants with plain string payloads. Task 5 widens it:

```ts
type ToastVariant = "success" | "error" | "warning" | "info";
type ToastMessage = {
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
};
```

Resolves the `DEFERRED_WORK.md` "Richer outbid toast shape" item. `OutbidToastProvider` from Epic 04 is updated to use the new shape (drops the imperative `scrollIntoView` side-effect in favor of an action button per the deferred entry's notes).

---

## §13 — Listing wizard title input (Task 1)

### §13.1 `ListingWizardForm` addition

- New "Title" section at the top of the form, above the parcel-lookup card.
- Label: "Listing Title" + helper text: "A short, punchy headline for your listing (max 120 characters)."
- Input: single-line text, `react-hook-form` registered as `title`.
- Zod schema: `z.string().trim().min(1, "Title is required").max(120, "Title must be 120 characters or less")`.
- Character counter below the input — "87 / 120", turns warning-tinted at ≥100.

### §13.2 Surfacing on existing components

- `ListingPreviewCard`: shows `title` as the primary headline above the parcel name.
- `MyListingsTab` / `ListingSummaryRow`: shows `title` as primary label, parcel name + region as secondary.
- `AuctionHero` on detail page: shows `title` as display-sm heading, parcel name + region as subtitle.

---

## §14 — Task breakdown

Six tasks in dependency order. Subagent-driven-development executes sequentially; later tasks assume earlier tasks have landed.

### Task 1 — Listing-wizard title input + surfacing (prerequisite, small)

- Extend `ListingWizardForm` with title section + Zod validation.
- Update `ListingPreviewCard`, `MyListingsTab`, `ListingSummaryRow`, `AuctionHero` to surface `title`.
- Unit tests: Zod schema boundaries (0 / 1 / 120 / 121 chars).
- Component tests: wizard shows counter, surfaces boundary errors, submits with title.
- Doc sweep at end: mark `Auction.title NOT NULL backfill` as clarified (frontend-side shipped).

### Task 2a — Primitives + canonical `ListingCard` + search API client/hook

- Small backend commit: add `endOutcome` nullable field to `AuctionSearchResultDto` + mapper + MockMvc regression test. Commit goes first in the branch so subsequent frontend commits consume it.
- New UI primitives: `RangeSlider`, `Pagination`, `BottomSheet`, `ActiveFilterBadge`, `StatusChip`.
- `ListingCard` with three variants.
- `lib/api/auctions-search.ts` — typed client for `/search`, `/featured/*`, `/auctions/{id}`.
- `lib/hooks/useAuctionSearch.ts`.
- `lib/search/url-codec.ts` + `canonical-key.ts` + `status-chip.ts`.
- Dev-only demo route `/dev/listing-card-demo` showing all three variants rendered. Gated to dev profile only.

### Task 2b — Browse page + seller listings page

- `BrowseShell` + `FilterSidebar` + `FilterSidebarContent` + `FilterSection` + `SortDropdown` + `DistanceSearchBlock` + `ActiveFilters` + `ResultsHeader` + `ResultsGrid` + `ResultsEmpty`.
- `app/browse/page.tsx` + `app/users/[id]/listings/page.tsx`.
- URL ↔ query round-trip + `router.replace` plumbing.
- Desktop-immediate + mobile-staged filter behavior.
- sessionStorage write for "last browse URL".
- Doc sweep at end: remove resolved `DEFERRED_WORK.md` entry "Per-user public listings page `/users/{id}/listings`".
- Add new `DEFERRED_WORK.md` entries: "Region autocomplete for `DistanceSearchBlock`" and "Infinite-scroll on browse grid (if feedback demands)".

### Task 3 — Homepage featured rows

- `FeaturedRow` component.
- Update `app/page.tsx` to server-fetch three featured endpoints via `Promise.allSettled` and slot the rails between `Hero` and `HowItWorksSection`.
- Partial-failure isolation per rail.
- No StatsBar (product-deferred).
- Add `DEFERRED_WORK.md` entry for the deferred StatsBar.

### Task 4 — Auction detail rebuild + OG metadata

- `AuctionGallery` + `Lightbox` primitive.
- Replace `SellerProfileCard` with enriched version.
- `VisitInSecondLifeBlock` + `lib/sl/slurl.ts`.
- `BreadcrumbNav` + sessionStorage fallback + JSON-LD microdata.
- `ParcelLayoutMap` placeholder.
- `generateMetadata` via `cache()`-wrapped fetch on `/auction/[id]` and `/users/[id]`.
- Verify `/users/{id}` returns `bio`; if missing, add field to backend User DTO response in a small Task 4 commit.
- Doc sweep at end: remove resolved `DEFERRED_WORK.md` entry "Profile page SEO metadata (OpenGraph)".

### Task 5 — Curator Tray drawer + `/saved` page

- `Drawer` primitive.
- Toast primitive widening (warning variant + action button).
- `useSavedIds`, `useToggleSaved`, `useSavedAuctions` hooks.
- `CuratorTrayMount`, `CuratorTrayTrigger`, `CuratorTray`, `CuratorTrayContent`, `CuratorTrayHeader`, `CuratorTrayEmpty`.
- `app/saved/page.tsx`.
- Wire `ListingCard` heart to `useToggleSaved` (the card itself changes, not its callers).
- Doc sweep at end: remove resolved `DEFERRED_WORK.md` entries "Saved / watchlist Curator Tray" and "Richer outbid toast shape (warning variant + structured action button)".
- Final README sweep for the entire sub-spec (see §20).

### Ordering summary

```
Task 1 (standalone)

Task 2a ──┬──> Task 2b ──┐
          ├──> Task 3  ──┤
          └──> Task 4  ──┴──> Task 5
```

**Hard dependencies:**
- Task 2b / 3 / 4 all depend on Task 2a (consume `ListingCard`, the search hook, and the primitives).
- Task 5 strictly depends only on Task 2a (the heart lives in `ListingCard`, which Task 2a ships).

**Conservative sequencing:**
- Task 5 ships last even though it could run right after Task 2a. Sequencing it after 2b/3/4 means the first production PR with the heart enabled lights up saving across every rendered surface simultaneously — browse, featured, detail, seller listings — rather than dev-demo only.
- Task 1 is a standalone prerequisite with no downstream dependency (the `title` field is already in sub-spec 1's DTO; Task 1 surfaces it on existing components, none of which Task 2a+ touch).

**Estimated spec + plan size.** Spec ~900 lines, plan ~5200 lines. Roughly matches Epic 04 / 05 sub-spec 2 density.

---

## §15 — Testing strategy

Mirrors the Epic 04 sub-spec 2 precedent. Vitest + React Testing Library + MSW for API mocking. Playwright deferred per Phase 1 convention.

### §15.1 Test inventory

| Task | Unit | Component (RTL) | Integration |
|---|---|---|---|
| 1 | Zod schema (min/max/trim) | `ListingWizardForm`: counter, validation boundaries, server-error envelope surfacing | — |
| 2a | `canonicalKey(query)` canonicalization; URL codec round-trip; API client error mapping; `deriveStatusChip` across every status / endOutcome combination | `ListingCard` all variants; heart click unauth → toast; heart click auth → mutation; `Pagination` keyboard + aria; `RangeSlider` dual-handle; `BottomSheet` ESC + focus-trap; `ActiveFilterBadge` X click; `StatusChip` rendering | `useAuctionSearch`: SSR-seeded initialData → filter change → refetch with new key |
| 2b | URL ↔ query round-trip edge cases | `BrowseShell` filter change → URL update → fetch; URL change → filter update; every filter input exercised; `ResultsGrid` empty/error/loading; `DistanceSearchBlock` REGION_NOT_FOUND surfacing; **mobile staged filters: open sheet → change filter → close without applying → filters unchanged; open → change → apply → URL updates + fetch fires; sheet remount-on-reopen discards stale staged state** | `/browse` SSR: given searchParams, fetches, renders. `/users/[id]/listings` SSR: fetches profile + pins seller_id |
| 3 | — | `FeaturedRow`: renders cards, View-all link, empty-state fallback | `/` SSR: `Promise.allSettled`, partial-failure isolation (one mocked 5xx still renders other two rows) |
| 4 | SLURL encoding (spaces, apostrophes, unicode, null positions) | `AuctionGallery` click → Lightbox; fallback when photos empty; `Lightbox` keyboard + focus-trap + swipe; `SellerProfileCard` completionRate formatting + null handling + NewSellerBadge trigger; `BreadcrumbNav` sessionStorage fallback + region href encoding | `cache()`-wrapped fetch invoked once per request across `generateMetadata` + page component |
| 5 | `useSavedIds` empty-set when unauth; `useToggleSaved` 409/403/404 error handling; canonical query-key for saved list | `CuratorTrayTrigger` unauth-hidden + count rendering; `CuratorTray` Drawer vs BottomSheet per viewport; optimistic toggle + rollback; toast primitive variants; `refetchType: "active"` invalidation | Full cycle: heart click on browse → tray count increments → drawer opens → card appears → unheart → tray empties |

**Estimated test volume:** ~110 new test methods across Tasks 1–5.

### §15.2 Out of scope (tests)

- Playwright end-to-end (per project convention for Phase 1).
- Real backend integration (MSW mocks the API per existing frontend test pattern).
- Visual regression / screenshot testing.

### §15.3 Dark mode assertions

Every component test asserts correct rendering in both light and dark mode. Matches Epic 04 sub-spec 2 precedent — tests wrap the component under test in `<html className="dark">` for the dark-mode assertion.

### §15.4 Accessibility assertions

Every new primitive (`BottomSheet`, `Drawer`, `Lightbox`, `Pagination`, `RangeSlider`) includes at least one a11y-focused test:
- Keyboard navigation (Tab, Enter, Space, ESC, arrows).
- Focus trap on modal overlays.
- Focus return on close.
- `aria-*` attributes correct per ARIA Authoring Practices.

---

## §16 — Loading / error / empty states

### §16.1 Loading

- Browse page: skeleton grid of 8 `ListingCardSkeleton` on client-side filter change. First SSR render has no skeleton (data already there).
- Featured rows: each rail renders its own 3-card skeleton on in-flight.
- Curator Tray drawer: skeleton list of compact cards.
- Detail page: existing Epic 04 loading states; gallery uses `Image` with no blur (no blur-data generated in Phase 1).
- `/saved` page: same skeleton as drawer.

### §16.2 Error

- **Page-level (Next.js `error.tsx`)**: per-route boundary. "Something went wrong loading this page. Retry." with retry button.
- **Section-level**: `ResultsGrid` on fetch 5xx, `FeaturedRow` on its endpoint 5xx, `CuratorTrayContent` on 5xx. Inline message + retry button. Does not crash the page.
- **Input-level**: `DistanceSearchBlock` REGION_NOT_FOUND inline under the input; `useToggleSaved` errors as toasts.

### §16.3 Empty

All via the existing `EmptyState` primitive:

- Browse zero-filters zero-results: "No active auctions right now. Check back soon." + home CTA.
- Browse filters-applied zero-results: "No auctions match your filters." + Reset CTA.
- `/users/[id]/listings` zero: "This seller has no active listings."
- Curator Tray / `/saved` zero saved: "Save parcels to review them here." + browse CTA.
- Curator Tray / `/saved` unauth: "Sign in to see your saved parcels." + sign-in CTA.

---

## §17 — Accessibility

- **Keyboard-first.** Every new primitive operable via keyboard: Tab, Enter/Space to activate, ESC to dismiss, arrows where sensible.
- **Focus management.** `BottomSheet`, `Drawer`, `Lightbox` each implement focus trap + focus return on close + `inert` on background.
- **ARIA labeling.** Cards have composed `aria-label`; nested heart has its own `aria-label`; status/maturity/verification badges are `aria-hidden` (their content is in the card aria-label).
- **Filter chips** read "Remove filter: Maturity – Adult" etc.
- **Link-only cards.** Entire card is a single anchor; nested interactive elements stop propagation.
- **Color-independent status.** Every chip includes a text label; color is reinforcement, not the only signal.
- **Dark mode.** Every component explicitly tested in both modes.
- **Reduced motion.** Framer Motion or CSS transitions respect `prefers-reduced-motion` — the `ENDING SOON` pulse + card-hover scale both honor the preference.

---

## §18 — Backend gaps (small additive changes bundled into this sub-spec)

Small backend additions discovered during frontend brainstorming. Included in the matching frontend task rather than spun into a separate backend sub-spec.

### §18.1 `endOutcome` field on `AuctionSearchResultDto` (Task 2a)

- Add `endOutcome: String` (nullable) to `AuctionSearchResultDto`.
- Mapper reads from existing `Auction.endOutcome` column (Epic 04 sub-spec 1).
- `null` for ACTIVE auctions (no impact on `/search` since it returns ACTIVE only); populated on `/me/saved/auctions?statusFilter=ended_only`.
- MockMvc regression test: search response includes `endOutcome: null` for active auctions; saved-ended response includes proper `endOutcome` values.

### §18.2 Toast primitive widening (Task 5)

- Existing `ToastProvider` supports only `success` / `error`. Widen to `success | error | warning | info`.
- Payload widened from plain string to `{ title, description?, action? }`.
- Update `OutbidToastProvider` from Epic 04 to use the new shape (drops imperative `scrollIntoView` in favor of action button).
- Resolves `DEFERRED_WORK.md` "Richer outbid toast shape" early.

### §18.3 Verify `/users/{id}` returns `bio` (Task 4)

- Implementer verifies at Task 4 kickoff; if `bio` is not in the current response, add it to the User DTO (small backend commit at the top of Task 4).
- Required for profile-page OG `description`.

### §18.4 No other backend changes

Every other backend concern — search endpoint, featured endpoints, stats endpoint, saved endpoints, extended `/auctions/{id}` — is already in place from sub-spec 1.

---

## §19 — Deferred-work tie-ins

### §19.1 Resolved in this sub-spec (removed at end of resolving task)

- **"Per-user public listings page `/users/{id}/listings`"** — removed at end of Task 2b.
- **"Profile page SEO metadata (OpenGraph)"** — removed at end of Task 4.
- **"Saved / watchlist Curator Tray"** — removed at end of Task 5.
- **"Richer outbid toast shape (warning variant + structured action button)"** — removed at end of Task 5.

### §19.2 New entries added (during the task that introduces the deferral)

- **"Enable public StatsBar on homepage when activity thresholds warrant it"** — added at end of Task 3. Backend `/stats/public` endpoint is ready; the gate is a frontend product decision.
- **"Region autocomplete for `DistanceSearchBlock`"** — added at end of Task 2b. Phase 2 polish; currently validates on submit via server error.
- **"Infinite-scroll on browse grid (if feedback demands)"** — added at end of Task 2b. Consolidated with the existing BidHistory deferral.

### §19.3 Untouched deferred items

Every item in `DEFERRED_WORK.md` not listed above is unrelated to this sub-spec and remains.

---

## §20 — README + CONVENTIONS + FOOTGUNS sweep

At the end of each task, touch `README.md` for surfacing the new routes/features. At the end of the sub-spec:

- **README.md** — add `/browse`, `/users/[id]/listings`, `/saved` to the frontend routes list. Remove any stale "browse coming in Phase 7" wording. Document the search-API + saved-auctions surface briefly in the Phase 1 status section.
- **CONVENTIONS.md** — no updates needed. Existing RSC-first / TanStack-Query / React Hook Form + Zod / design-token conventions all apply.
- **FOOTGUNS.md** — candidates likely to surface during implementation (written on impact, not pre-speculated):
  - Next.js 16 `cache()` pattern for fetch dedup across `generateMetadata` + page component.
  - CSV URL-state round-tripping through Next.js 16 `useSearchParams` (potential surprise).
  - `Lightbox` focus return from nested tabindex elements (a11y-test-flushed case).
  - `BottomSheet` staged vs committed state — remount-on-reopen is the right primitive, not stale-state retention.

---

## §21 — Success criteria

Sub-spec 2 ships when:

- All six tasks in §14 land with CI green.
- ~110 new test methods passing (unit + component + integration).
- `/browse` returns filtered/sorted/paginated results with URL-as-source-of-truth; back/forward navigation works; deep links reproduce the state.
- `/users/[id]/listings` ships seller-scoped listings using the same `BrowseShell`.
- Homepage renders three featured rails via `Promise.allSettled`; one rail's 5xx doesn't fail the page.
- `/auction/[id]` renders photo gallery + enriched seller card + SLURL block + breadcrumb; `generateMetadata` emits OG tags; `cache()` dedup is in place.
- `/users/[id]` emits OG metadata.
- `/saved` + Curator Tray drawer render saved-auctions list; heart wiring works across every `ListingCard` caller; unauth heart click surfaces toast; 500-cap error surfaces toast.
- Listing-wizard accepts and validates the `title` field; `Auction.title` surfaces on every consuming card.
- Every new component renders correctly in both light and dark mode.
- 4 resolved `DEFERRED_WORK.md` entries removed; 3 new entries added.
- Backend touches (§18) land cleanly.
- README + CONVENTIONS + FOOTGUNS swept for staleness introduced or resolved by this sub-spec.
- PR from `task/07-sub-2-browse-search-frontend` merges cleanly to `dev`.
