# Epic 04 Sub-Spec 2: Auction Engine Frontend

> **Related:** Epic 04 task files live in `docs/implementation/epic-04/`. This sub-spec covers tasks 04-06 (auction detail page) and 04-07 frontend (My Bids dashboard tab + My Listings enrichment). Sub-spec 1 shipped the backend (pessimistic-lock bidding, proxy bidding, snipe extension, inline buy-now, 15s auction-end sweeper, `/topic/auction/{id}` WS envelopes, `GET /users/me/bids`).

## 1. Overview

Ship the buyer-facing frontend for Epic 04 — the auction detail page at `/auction/[id]` (the Digital Curator showcase), the My Bids dashboard tab, enriched My Listings rows, the public-profile active-listings section, and a hardened WebSocket re-attach model. Fills real data into three surfaces that previously rendered empty-state placeholders.

**Architecture.** Next.js 16 App Router server component fetches auction state via `GET /api/v1/auctions/{id}` plus the first bid-history page, then hands to a client component that subscribes to `/topic/auction/{id}` via the existing STOMP client (hardened to re-attach on reconnect) and merges `BidSettlementEnvelope` / `AuctionEndedEnvelope` into React Query cache state. The floating `BidPanel` is a single reusable surface with two stacked forms (place bid + set max proxy), both always visible, gated on auth/verification state; mobile collapses it to a sticky compact bar that opens a full bottom sheet. The My Bids dashboard tab consumes `GET /users/me/bids`, renders status-colored rows, and reuses the existing My Listings row rhythm.

**Tech stack.** Next.js 16 App Router, React 19, TypeScript, Tailwind CSS 4, Headless UI (`Dialog`), `@stomp/stompjs` + `sockjs-client` (existing WS client), React Query v5, MSW for tests, Vitest + React Testing Library + happy-dom.

## 2. Scope

**In scope:**
- Task 04-06 — auction detail page `/auction/[id]` with all subcomponents
- Task 04-07 frontend — My Bids dashboard tab + My Listings enrichment
- WS client hardening (`frontend/src/lib/ws/client.ts`) — re-attach list model
- Active-listings section on public profile (`/users/[id]`) — replaces Epic 02 sub-2b deferred placeholder
- "View public listing" links on My Listings rows now functional (Epic 03 sub-2 deferred target met by shipping `/auction/[id]`)
- New backend endpoint `GET /api/v1/users/{userId}/auctions?status=ACTIVE&page=&size=` (public, SUSPENDED always excluded) — 1 controller + 1 repo query + tests

**Out of scope — deferred to later epics:**
- Email / SL IM outbid notifications (Epic 09) — only the in-page toast ships here
- Escrow flow UI (Epic 05) — `AuctionEndedPanel` just shows static outcome
- Bid history infinite scroll (Phase 1 ships page-with-load-more)
- Per-user public listings page `/users/{id}/listings` (Epic 07 Browse territory)
- Saved / watchlist functionality — the "Curator Tray" saved-plots dock (Epic 07)
- WS reconnect telemetry (Epic 10 or Epic 09 ops plumbing)
- `/browse` page — stays a skeleton (Epic 07)

## 3. Success Criteria

1. Viewing `/auction/[id]` as an anonymous user renders full parcel info, live countdown, current bid, bid history, and a "Sign in to bid" CTA — no bid forms.
2. Viewing as a verified non-seller renders the BidPanel with both place-bid and set-max-proxy forms active.
3. Viewing as the seller shows the panel in a read-only "Your auction" state with no bid input.
4. Placing a bid → server commits → WS envelope arrives → BidPanel updates currentBid/bidderCount/countdown in-place without a page reload.
5. Snipe extension event → countdown jumps to the new end time; bid history row shows an `Extended 15m` chip; a transient banner notes the extension for 4s.
6. Buy-it-now bid → page transitions to ENDED state with "Sold for L$X to @winner" within 1s of click.
7. Mobile viewport: sticky bar shows current bid + countdown; tapping "Bid now" opens a bottom sheet with the full panel.
8. WS disconnect: bid inputs disable, "Reconnecting… bids paused" banner appears; on reconnect, inputs re-enable and the page reconciles state via REST re-fetch.
9. Caller outbid by someone else → toast "You've been outbid" fires once per displacement event (guarded by was-winning check against local cache).
10. My Bids tab loads with status filter tabs (All / Active / Won / Lost) and status-colored rows; switching filters updates the URL query and hits the backend with the correct `?status=` param.
11. My Listings rows now show current bid + bidder count + time-remaining when `auction.status === ACTIVE`; ENDED rows show "Sold for L$X to @winner" or outcome-specific text.
12. Public profile page at `/users/[id]` shows an "Active listings" section populated via the new `GET /users/{id}/auctions?status=ACTIVE` endpoint; SUSPENDED always excluded; empty-state when none.
13. WS re-attach: after an `onWebSocketClose → onConnect` cycle with subscribers still mounted, every subscription is restored automatically on the same client instance; test harness verifies no dropped envelopes across a forced reconnect.
14. Subscribe-during-sweep: an `onMessage` handler that synchronously calls `subscribe()` for a new topic has its new subscription attached in the same reconnect pass (Map iteration visits entries added during iteration).
15. Buy-now overspend guard: typing `amount > buyNowPrice` or `maxAmount >= buyNowPrice` fires `ConfirmBidDialog` with explanatory copy before submission.

## 4. Route Map

| Path | Server / Client | Changes in this sub-spec |
|---|---|---|
| `/auction/[id]` | server wraps client | **Replace skeleton** — full auction detail page |
| `/dashboard/(verified)/bids` | client | **Replace skeleton** — My Bids tab |
| `/dashboard/(verified)/listings` | client | **Modify** — enrich `ListingSummaryRow` with bid data |
| `/users/[id]` | server + client | **Modify** — add `ActiveListingsSection` |

**New backend endpoint:** `GET /api/v1/users/{userId}/auctions?status=ACTIVE&page=&size=` — public, returns `Page<PublicAuctionResponse>`, SUSPENDED always excluded, pre-ACTIVE statuses excluded.

**WebSocket subscriptions (unchanged topology):** `/topic/auction/{auctionId}` (public, from sub-spec 1).

## 5. Real-Time Strategy

WS-first with REST reconcile on reconnect. No polling.

**Initial load:** server component fetches `GET /api/v1/auctions/{id}` + `GET /api/v1/auctions/{id}/bids?page=0&size=20`, seeds React Query cache via `initialData`.

**Live updates:** client subscribes to `/topic/auction/{id}` via `useStompSubscription`. Each envelope is merged into the cache:

```typescript
// Inside AuctionDetailClient component body
const queryClient = useQueryClient();
const { id: currentUserId } = useCurrentUser();

const handleEnvelope = useCallback((env: BidSettlementEnvelope | AuctionEndedEnvelope) => {
  const prevAuction = queryClient.getQueryData<AuctionResponse>(["auction", id]);

  queryClient.setQueryData(["auction", id], (prev: AuctionResponse | undefined) => {
    if (!prev) return prev;
    if (env.type === "BID_SETTLEMENT") {
      return { ...prev,
        currentHighBid: env.currentBid,
        bidderCount: env.bidCount,
        endsAt: env.endsAt,
        originalEndsAt: env.originalEndsAt };
    }
    return { ...prev,
      status: "ENDED",
      endsAt: env.endsAt,
      endOutcome: env.endOutcome,
      finalBidAmount: env.finalBid,
      winnerUserId: env.winnerUserId,
      bidderCount: env.bidCount };
  });

  if (env.type === "BID_SETTLEMENT") {
    queryClient.setQueryData(["auction", id, "bids", 0], (prev: Page<BidHistoryEntry> | undefined) => {
      if (!prev) return prev;
      const merged = dedupeByBidId([...env.newBids, ...prev.content]).slice(0, prev.size);
      return { ...prev, content: merged, totalElements: prev.totalElements + env.newBids.length };
    });
    queryClient.invalidateQueries({ queryKey: ["auction", id, "my-proxy"] });
    OutbidToastProvider.maybeFire(prevAuction, env, currentUserId);
  }
  if (env.type === "AUCTION_ENDED") {
    queryClient.invalidateQueries({ queryKey: ["auction", id, "my-proxy"] });
  }
}, [queryClient, id, currentUserId]);

useStompSubscription(`/topic/auction/${id}`, handleEnvelope);
```

**Reconnect reconcile:**

```typescript
useEffect(() => {
  if (connectionState.status === "connected" && previousStateWasReconnecting) {
    queryClient.invalidateQueries({ queryKey: ["auction", id] });
    queryClient.invalidateQueries({ queryKey: ["auction", id, "bids", 0] });
    queryClient.invalidateQueries({ queryKey: ["auction", id, "my-proxy"] });
  }
}, [connectionState.status]);
```

**Server-time correction:** envelopes carry `serverTime`. Client computes `serverTimeOffset = envelope.serverTime - clientNow()` at message arrival, stores in a module-level ref. `CountdownTimer` uses `endsAt - (clientNow() - serverTimeOffset)` to render remaining. Fallback on initial load: server component uses the response `Date` header.

**Disconnect UX:** when `connectionState.status !== "connected"`, bid form submit buttons disable; `ReconnectingBanner` renders inside the panel with state-specific copy (see §9).

**Outbid toast:** fires only when `prevAuction.currentBidderId === currentUserId` AND `env.currentBidderId !== currentUserId` (was-winning guard). Derived from public envelope — no user-targeted queue. Epic 09 delivers the canonical email / SL IM notification later; this is a page-level signal only.

## 6. WebSocket Client — Re-Attach List Model

Replaces the current `client.ts:140-146` race. Existing public API (`useStompSubscription`, `useConnectionState`, `subscribe`, `subscribeToConnectionState`) preserved.

### Model

```typescript
interface SubscriptionEntry<T> {
  id: string;                         // internal uuid
  destination: string;
  onMessage: (payload: T) => void;
  handle: StompSubscription | null;   // null when not currently attached
}

const entries = new Map<string, SubscriptionEntry<unknown>>();
```

### Lifecycle

1. **`subscribe(destination, onMessage)`** — create entry, push into registry, call `ensureAttached(entry)`, return unsubscribe closure.
2. **`ensureAttached(entry)`** — if `client.connected && entry.handle === null`, call `client.subscribe(...)` and store handle. If not connected, no-op — `onConnect` sweep will pick up.
3. **`onConnect` fires** → iterate the live Map (NOT a snapshot) and call `ensureAttached(entry)` for every entry with `handle === null`. Idempotent bulk sweep.
4. **`onWebSocketClose` fires** → iterate the live Map and null out every `entry.handle`. Don't call unsubscribe on dead handles.
5. **`unsubscribe()` closure** — remove entry from registry. If entry has a live handle AND client still connected, call `handle.unsubscribe()` first. Reference-count decrement + grace teardown unchanged.

### Race fixes

- **Bulk re-attach:** the `onConnect` sweep restores all subscribers atomically — no per-subscription retry timing.
- **Mid-sweep subscribe (spec invariant):** entries may grow during sweep — iterate the live Map, not `Array.from(entries.values())`. Map iteration is spec-defined to visit entries added during iteration if not yet reached. **Code comment explicitly documents this constraint.**
- **Unsubscribe during reconnect:** removing an entry during `reconnecting` state leaves the entry out of the next sweep; not re-attached on the next `onConnect`.
- **Memory hygiene:** every unsubscribe removes the entry from the Map; reference count + grace teardown preserved.

### Test cases (`client.test.ts` additions)

1. Bulk re-attach — subscribe to 3 topics, force `onWebSocketClose → onConnect`, assert all 3 handles re-attached.
2. Race pin — subscribe during `reconnecting`, rapid `connected → close → connected`, assert subscription live on final `connected`.
3. Unsub-during-reconnect — subscribe, force close, unsubscribe while reconnecting, then connect; assert removed entry NOT re-attached.
4. **Subscribe-during-sweep — inside an `onMessage` handler during reconnect, synchronously call `subscribe()` for a new topic; assert new subscription is live after a single reconnect cycle.**
5. Memory hygiene — 1000 subscribe/unsubscribe cycles across reconnects → final registry size === 0.

### FOOTGUNS updates

- Update existing F.19 ("STOMP subscribe before connect") to point at the new model.
- Add new entry: "Re-attach registry is the source of truth — iterate live Map, not a snapshot. Subscribing inside an `onMessage` callback during the sweep relies on Map iteration visiting late-added entries."

## 7. AuctionPage — Server / Client Composition

### Route (`frontend/src/app/auction/[id]/page.tsx`)

```typescript
export default async function AuctionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isFinite(auctionId) || auctionId <= 0) notFound();

  const [auction, firstBidPage] = await Promise.all([
    getAuction(auctionId),
    getBidHistory(auctionId, { page: 0, size: 20 }),
  ]);
  if (!auction) notFound();

  return <AuctionDetailClient initialAuction={auction} initialBidPage={firstBidPage} />;
}
```

`notFound()` → Next.js 404. SUSPENDED hidden from non-sellers enforced at backend DTO layer (Epic 03 sub-2) — a non-seller viewer sees `status: "ENDED"` on the public DTO.

### Client shell (`AuctionDetailClient.tsx`)

- Receives `initialAuction` + `initialBidPage` as props; seeds React Query cache via `initialData`.
- Query keys: `["auction", id]`, `["auction", id, "bids", page]`, `["auction", id, "my-proxy"]`.
- Subscribes to `/topic/auction/{id}` via `useStompSubscription(destination, handleEnvelope)`.
- Uses `useConnectionState()` to drive the reconnecting banner.
- Renders page composition via CSS-only responsive toggle (see §8).
- `handleEnvelope` defined inline as `useCallback` so `queryClient` and `currentUserId` are in scope.

### Envelope → cache merger

See §5. One helper: `dedupeByBidId(bids: BidHistoryEntry[])` — O(n), preserves first occurrence.

### Reconnect reconcile

See §5. `previousStateWasReconnecting` tracked via a `useRef<boolean>`; set to `true` on `reconnecting`/`error`, compared to `connected` on the next transition.

### Server-time offset

```typescript
const serverTimeOffsetRef = useRef<number>(0);

// Initial value from server component's fetch response Date header
useEffect(() => {
  serverTimeOffsetRef.current = new Date(initialServerTime).getTime() - Date.now();
}, [initialServerTime]);

// Refine on every envelope
const handleEnvelope = useCallback((env) => {
  serverTimeOffsetRef.current = new Date(env.serverTime).getTime() - Date.now();
  // ...cache merge...
}, [...]);
```

`CountdownTimer` accepts `endsAt` + `serverTimeOffset` and renders `max(0, endsAt - (Date.now() + serverTimeOffset))` every tick.

## 8. Component Tree + CSS-Only Responsive Toggle

### Layout composition

```tsx
// AuctionDetailClient.tsx (excerpt)
<>
  <main className="max-w-7xl mx-auto px-4 lg:px-8 pt-8 lg:pt-24 pb-24 lg:pb-12">
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-12">
      <div className="lg:col-span-8 space-y-8 lg:space-y-12">
        <AuctionHero photos={auction.photos} parcel={auction.parcel} />
        <ParcelInfoPanel auction={auction} />
        <BidHistorySection auction={auction} />
        <SellerProfileCard seller={auction.seller} />
      </div>
      <aside className="hidden lg:block lg:col-span-4">
        <div className="sticky top-24">
          <BidPanel auction={auction} currentUser={currentUser} existingProxy={proxy} />
        </div>
      </aside>
    </div>
  </main>
  <div className="lg:hidden">
    <StickyBidBar auction={auction} currentUser={currentUser} onOpenSheet={openSheet} />
    <BidSheet isOpen={sheetOpen} onClose={closeSheet}>
      <BidPanel auction={auction} currentUser={currentUser} existingProxy={proxy} />
    </BidSheet>
  </div>
</>
```

`hidden lg:block` on the sidebar; `lg:hidden` on the sticky bar + sheet. No `useMediaQuery`, no hydration flash. `BidPanel` renders twice in the DOM but form logic only instantiates once per variant (the sheet is closed by default).

### Components (new this sub-spec)

**`frontend/src/components/auction/`:**

- **`AuctionHero`** — 4-cell asymmetric grid on `lg:` (1 large + 2 small + "view all photos" overlay); stacks on mobile. Photo fallback: first uploaded photo → parcel.snapshotUrl → gradient placeholder.
- **`ParcelInfoPanel`** — parcel name, region, area (sqm), maturity badge, `VerificationTierBadge`, tags row, seller description.
- **`BidPanel`** — single panel renders one of 6 variants (see §9).
- **`PlaceBidForm`** — amount input + "Place bid" button.
- **`ProxyBidSection`** — max-amount input + state-keyed buttons.
- **`ConfirmBidDialog`** — `Dialog` wrapper for large-bid / buy-now confirms.
- **`BidHistoryList`** — paginated list, WS-driven page 0.
- **`BidHistoryRow`** — single entry (avatar, display name, amount, type chip, snipe chip, timestamp).
- **`AuctionEndedPanel`** — 4 outcome variants (see §10).
- **`AuctionEndedRow`** — standalone component pinned above `BidHistoryList`; NOT in the bid cache.
- **`SellerProfileCard`** — reuses `ReputationStars` + `NewSellerBadge` from Epic 02.
- **`VisitInSecondLifeButton`** — dropdown: Open in Viewer + View on Map.
- **`ReserveStatusIndicator`** — `Chip` variant (met / not met / no reserve).
- **`SnipeProtectionBadge`** — `Chip` with `Shield` icon + window duration text.
- **`VerificationTierBadge`** — `Chip` with tier-specific icon + color (script/bot/human).
- **`StickyBidBar`** — glass bottom bar (mobile).
- **`BidSheet`** — `Dialog` bottom-sheet (mobile).
- **`ReconnectingBanner`** — state-keyed inline banner.
- **`OutbidToastProvider`** — module with `maybeFire(prevAuction, env, currentUserId)` method.
- **`AuthGateMessage`** — variant renderer for unauth / unverified / seller states.

**`frontend/src/components/bids/` (new directory):**

- **`MyBidsTab`** — container + filter tabs + list.
- **`MyBidSummaryRow`** — 7-color-keyed row per Q6 design.
- **`MyBidStatusBadge`** — `StatusBadge` with 7-status→tone map.
- **`MyBidsFilterTabs`** — All / Active / Won / Lost, updates URL `?status=`.

**Modifications:**

- **`components/listing/ListingSummaryRow.tsx`** — add status-conditional bid-data sub-line.
- **`components/user/PublicProfileView.tsx`** — replace `EmptyState` placeholder with `ActiveListingsSection`.
- **`components/user/ActiveListingsSection.tsx`** (new) — grid of active-listing cards.

## 9. BidPanel — Variants + Forms + Error Handling

### The 6 variants

| Variant | Condition | Renders |
|---|---|---|
| Unauth | no session | `AuthGateMessage` — "Sign in to bid" → `/sign-in?returnTo=/auction/{id}`. Panel still shows currentBid / countdown / bid history for context. |
| Unverified | session + `user.verified === false` | `AuthGateMessage` — "Verify your SL avatar to bid" → `/dashboard/overview`. |
| Seller | `currentUser.id === auction.seller.id` | Read-only "Your auction" callout; no forms. |
| Bidder-default | verified non-seller, no existing ACTIVE proxy | `PlaceBidForm` + `ProxyBidSection` (empty state). |
| Bidder-with-proxy | verified non-seller + existing ACTIVE proxy | Same as above + "You have a proxy at L$X max" callout at top of `ProxyBidSection`. |
| Ended | `auction.status === ENDED` | `AuctionEndedPanel` replaces `BidPanel` entirely. |

### `PlaceBidForm`

- Amount input — numeric, right-aligned, `L$` prefix, placeholder = `minRequired`.
- "Place bid" primary button — disabled when `amount < minRequired` OR `connectionState !== connected` OR mutation in-flight.
- Client validation: `amount >= minRequired`. Server 400 `BID_TOO_LOW` surfaces `minRequired` inline via `FormError`.
- `useMutation` calls `POST /api/v1/auctions/{id}/bids`. Success → clear input; WS envelope drives UI update (no optimistic update — the lock may reject).
- **Buy-now overspend guard:** if `amount > buyNowPrice`, submit still allowed but `ConfirmBidDialog` intercepts: *"This will trigger buy-now at L${buyNowPrice}. You won't pay more than the buy-now price regardless of what you enter."*
- Large-bid safety: `amount > 10000` triggers generic `ConfirmBidDialog` — "Confirm you want to bid L$X." Session-scoped "don't show again" checkbox.
- Typing `amount === buyNowPrice` → button label flips to "Buy now · L$X" (same confirm dialog fires).

### `ProxyBidSection`

Mode branches on `existingProxy?.status`:

- **`null` or `CANCELLED`** — `maxAmount` input + "Set max bid" button → `POST /proxy-bid`.
- **`ACTIVE`** — current max shown + "Update max" (input enabled) + "Cancel proxy" secondary button. Update → `PUT /proxy-bid`. Cancel → `DELETE /proxy-bid` with confirm dialog.
- **`EXHAUSTED`** — "You were outbid at L$X" + "Increase your max" input → `PUT /proxy-bid` (reactivates to ACTIVE per sub-spec 1 §7).

**Cancel button disabled** when `currentBidderId === currentUserId` (caller is winning — backend would 409; UI prevents the round-trip).

**Buy-now overspend guard:** if `maxAmount >= buyNowPrice`, `ConfirmBidDialog` fires: *"This max will trigger an immediate buy-now at L${buyNowPrice}."* Per sub-spec 1 §6 step 6, backend emits one bid at `buyNowPrice` and ends the auction atomically.

Success → invalidate `["auction", id, "my-proxy"]`; incoming `BID_SETTLEMENT` envelope updates current state.

### Error payload mapping

| `error` code | UI treatment |
|---|---|
| `BID_TOO_LOW` | Inline `FormError` with "Minimum bid is L${minRequired}"; auto-fills the input. |
| `SELLER_CANNOT_BID` | Unreachable (UI prevents submission); fallback: generic error toast. |
| `NOT_VERIFIED` | Unreachable (UI routes to verify); fallback: generic error toast. |
| `AUCTION_NOT_ACTIVE` / `AUCTION_ALREADY_ENDED` | Invalidate `["auction", id]` (full re-fetch); re-render page. |
| `PROXY_BID_ALREADY_EXISTS` | Invalidate `["auction", id, "my-proxy"]`; retry. |
| `INVALID_PROXY_MAX` / `INVALID_PROXY_STATE` | Inline `FormError` with returned `reason`. |
| `CANNOT_CANCEL_WINNING_PROXY` | Toast + UI state update (shouldn't reach — button is disabled). |
| Network / 5xx | Generic toast "Something went wrong. Please try again." |

### Connection-state disable

When `connectionState.status !== "connected"`, both forms' submit buttons disable with helper text: *"Waiting for connection…"*. Matches the banner in §10.

## 10. Bid History + Ended State

### `BidHistoryList`

- Paginated (20/page), `["auction", id, "bids", page]` query keys.
- Page 0 kept fresh by WS envelope merge; pages 1+ static until paged into.
- Row: avatar + display name (link to `/users/{userId}`) + amount + type chip (MANUAL = none / PROXY_AUTO = gray "proxy" / BUY_NOW = gold "buy now") + snipe chip (`Shield` icon + "Extended 15m") + relative timestamp.
- "Load more" at bottom fetches next page. On scroll to last row, React Query prefetches next.
- New bid arrival animates: fade + slight downward shift + pulse on the row for ~2s.

### Snipe extension toast on BidPanel

When an incoming `BID_SETTLEMENT` has an entry in `newBids` with `snipeExtensionMinutes != null`, a transient banner at the top of the BidPanel shows *"Auction extended by 15m — 2h 14m now remaining."* for 4 seconds. Updates in-place if multiple extensions land rapidly (doesn't stack).

### `AuctionEndedPanel`

4 outcome variants:

| Outcome | Display |
|---|---|
| `SOLD` | "Sold for L${finalBidAmount}" + winner avatar + display name + link + "Ended 2h ago" |
| `BOUGHT_NOW` | "Sold at buy-now price L${finalBidAmount}" + winner details |
| `RESERVE_NOT_MET` | "Reserve not met — auction ended without a sale" + "Highest bid was L${currentBid}" |
| `NO_BIDS` | "Ended with no bids" + "Starting bid was L${startingBid}" |

Viewer-specific overlays:
- `currentUser.id === winnerUserId` → green banner "You won this auction" + link to `/dashboard/bids`
- `currentUser.id === auction.seller.id` → seller banner + note "Escrow flow opens in Epic 05"
- Otherwise: neutral display

**No viewport yank on `AUCTION_ENDED`.** Mount `AuctionEndedPanel` in place of `BidPanel` without scroll change. Mobile `StickyBidBar` transforms into a read-only banner.

### `AuctionEndedRow` (separate component, not in bid cache)

```tsx
{auction.status === "ENDED" && <AuctionEndedRow auction={auction} />}
<BidHistoryList auctionId={auction.id} />
```

Keeps the bid cache holding only real bid rows; deduper logic simple (all entries have numeric `bidId`).

## 11. Mobile Pattern

### `StickyBidBar`

Fixed to viewport bottom, backdrop blur `rgba(255,255,255,0.92)` light / `rgba(25,28,30,0.85)` dark, `z-index: 40`. Height ~72px with `pb-safe` for iOS home indicator.

Layout:
```
┌──────────────────────────────────────────────┐
│ L$ 42,500            [   Bid now   ]          │
│ Ends in 2h 14m · Reserve met                   │
└──────────────────────────────────────────────┘
```

Variants (5 states matching BidPanel variants):
- Unauth: "Sign in to bid" → `/sign-in?returnTo=/auction/{id}`
- Unverified: "Verify to bid" → `/dashboard/overview`
- Seller: "Your auction — L$42,500" (no button)
- Bidder: "Bid now" button opens `BidSheet`
- Ended: "Sold for L$X to @winner" (no button)

Connection state: `reconnecting / error` → button text → "Reconnecting…" with spinner, button disabled. Tap → optional transient toast "Waiting for connection."

### `BidSheet`

Headless UI `Dialog` with `rounded-t-xl`, `max-height: 85vh`, `overflow-y: auto`. Slides up from bottom. **No swipe-to-dismiss.** Closes via backdrop-click, Escape, or close button.

Contents: the **full `BidPanel` content** — same variants, same `PlaceBidForm` + `ProxyBidSection` + `ConfirmBidDialog`. Single-instance form logic (the sheet only mounts on tap).

Visual drag handle at top as affordance (not functional).

Keyboard UX: input focus triggers `scrollIntoView({ block: "center" })` as belt-and-suspenders so the primary button stays visible above the keyboard.

### Responsive toggle

CSS-only via `hidden lg:block` / `lg:hidden`. No `useMediaQuery`. No hydration flash. `BidPanel` + `StickyBidBar` both in DOM; visibility toggled by breakpoint.

## 12. My Bids Tab

### `MyBidsTab` (`/dashboard/(verified)/bids`)

Replaces current `EmptyState` skeleton.

- Filter tabs: All · Active · Won · Lost (via existing `Tabs` primitive).
- Tab switch updates URL `?status=` via `router.replace`.
- `<ul>` of `MyBidSummaryRow` components.
- Pagination: 20/page, "Load more" button at bottom. No infinite scroll.
- Empty state: `EmptyState` with `Gavel` icon, per-filter copy.

**React Query:** `["my-bids", { status, page }]` calling `GET /api/v1/users/me/bids?status=...&page=...`. 30s stale time. **Not WS-driven** — refetch on window focus is sufficient.

### `MyBidSummaryRow`

4px colored left border keyed to `myBidStatus`:

| Status | Border | Chip tone |
|---|---|---|
| `WINNING` | tertiary-container blue | blue |
| `OUTBID` | error red | red |
| `WON` | primary gold | gold |
| `LOST` | on-surface-variant gray | gray |
| `RESERVE_NOT_MET` | warning orange | orange |
| `CANCELLED` | gray + strikethrough parcel name | gray |
| `SUSPENDED` | error red + strikethrough | red |

Layout: thumbnail + parcel info (name, region, area, time-remaining inline) + status chip + bid numbers right-aligned (Your bid / Current / Proxy max if set).

Row click → `/auction/{auctionId}`.

## 13. My Listings Enrichment

Modify existing `components/listing/ListingSummaryRow.tsx` — add status-conditional sub-line:

| Auction status | Sub-line |
|---|---|
| `ACTIVE` | `L$42,500 current · 12 bids · 2h 14m left` |
| `ENDED` + `SOLD` / `BOUGHT_NOW` | `Sold for L$48,000 to @winner` |
| `ENDED` + `RESERVE_NOT_MET` | `Ended — reserve not met (highest bid L$12,000)` |
| `ENDED` + `NO_BIDS` | `Ended with no bids` |
| Other | no sub-line (existing behavior) |

Derived from `auction.currentHighBid`, `auction.bidderCount`, `auction.endsAt`, `auction.endOutcome`, `auction.finalBidAmount`, `auction.winnerUserId`, `auction.winnerDisplayName` — fields already on the DTO (Epic 03 sub-2 added placeholders; Epic 04 sub-1 populates).

**No WS on listings tab** — React Query window-focus refetch is sufficient.

## 14. Public-Profile Active Listings

### `ActiveListingsSection`

Replaces existing `<EmptyState icon={Gavel}>` placeholder in `components/user/PublicProfileView.tsx`.

- Fetches `GET /api/v1/users/{userId}/auctions?status=ACTIVE&page=0&size=6`.
- Grid of up to 6 auction cards (`md:grid-cols-2 lg:grid-cols-3`, stacked on mobile).
- Card: thumbnail + parcel name + current bid + countdown + "View listing" link → `/auction/{id}`.
- Empty state: subtle `EmptyState` — "No active listings" (matches existing placeholder style).
- "View all" link at bottom when `totalElements > 6` → `/users/{id}/listings` (deferred; Epic 07).

### New backend endpoint

**`GET /api/v1/users/{userId}/auctions`** (public, no auth required):
- Query params: `status=ACTIVE` (only valid value for Phase 1), `page=0`, `size=6` (or configurable).
- Returns: `Page<PublicAuctionResponse>`.
- **Filter:** `status = 'ACTIVE' AND seller.id = :userId`.
- **SUSPENDED always excluded** regardless of requester. Pre-ACTIVE statuses excluded.
- New `AuctionRepository` method: `findBySellerIdAndStatusOrderByEndsAtAsc(Long sellerId, AuctionStatus status, Pageable pageable)`.
- New service method on existing `AuctionService` or a small new `PublicUserAuctionsService` — spec author preference; either fits the existing structure.
- Endpoint wired in `UserController` at `GET /api/v1/users/{userId}/auctions`.
- Tests: controller slice + service unit + integration covering empty / populated / SUSPENDED-excluded cases.

## 15. Outbid Toast + Connection Banner

### Outbid toast

Fires in `handleEnvelope` when `BID_SETTLEMENT` arrives, guarded by was-winning check:

```typescript
function maybeFireOutbid(prevAuction, env, currentUserId, toast) {
  const wasWinning = prevAuction?.currentBidderId === currentUserId;
  const nowLosing = env.currentBidderId !== currentUserId;
  if (wasWinning && nowLosing) {
    toast({
      variant: "warning",
      title: "You've been outbid",
      description: `Current bid is L$${formatAmount(env.currentBid)}.`,
      action: { label: "Place a new bid", onClick: () => scrollToBidPanel() },
    });
  }
}
```

Fires once per displacement. Subsequent envelopes while still-outbid do not re-fire (derived from cache before merge).

Position: `top-center` (existing Toast default).

**Scope split:** this is an in-page signal only. Epic 09 delivers canonical email / SL IM "you were outbid" notifications; this toast fires only when the caller is actively viewing the auction page.

### `ReconnectingBanner`

Mounted inside `BidPanel` body (desktop) and inside `BidSheet` body (mobile, when sheet is open).

| Connection state | Banner |
|---|---|
| `connected` | no banner |
| `connecting` (initial) | no banner — skeleton inside BidPanel |
| `reconnecting` | "Reconnecting… bids paused." + subtle spinner |
| `disconnected` (post-grace) | "Connection lost. Reload to see live updates." + reload button |
| `error` | `connectionState.detail` text + sign-in link |

Styling: `rounded-default`, `bg-error-container` for non-connected states, `text-on-error-container`. Full-width inside panel, pushes form content below (does not overlay).

Mobile `StickyBidBar`: no banner (no room). Button text swaps to "Reconnecting…" with spinner; disabled.

## 16. Testing

### Unit tests (Vitest + RTL + happy-dom, sibling `.test.tsx`)

- `ws/client.test.ts` expanded: bulk re-attach, race pin, unsub-during-reconnect, subscribe-during-sweep, memory hygiene.
- `ws/hooks.test.tsx` — callback ref pattern survives re-renders.
- `auction/BidPanel.test.tsx` — 6 variants render correctly; forms validate client-side; confirm dialog fires for L$10k+ and buy-now overspend.
- `auction/PlaceBidForm.test.tsx` — submit correct payload; surface `BID_TOO_LOW` inline; auto-fill input on error.
- `auction/ProxyBidSection.test.tsx` — all 3 branches; buy-now overspend confirm fires when `maxAmount >= buyNowPrice`.
- `auction/BidHistoryList.test.tsx` — paginated load; snipe chip; WS merge prepends with dedupe.
- `auction/AuctionEndedPanel.test.tsx` — 4 outcome variants + viewer-specific overlays.
- `auction/OutbidToastProvider.test.tsx` — was-winning guard; fires once per displacement; not fired when caller wasn't winning.
- `auction/CountdownTimer.test.tsx` — add `serverTimeOffset` correction case (existing file).
- `bids/MyBidSummaryRow.test.tsx` — 7 status variants render correct border + chip + sub-text.
- `bids/MyBidsTab.test.tsx` — filter tab switch updates URL + query; per-filter empty state.
- `listing/ListingSummaryRow.test.tsx` — enrichment sub-lines for each auction status.
- `user/ActiveListingsSection.test.tsx` — fetch, render, empty state, "View all" conditional.

### Integration tests (MSW)

- `auction/[id]/page.integration.test.tsx` — full page load with seeded DTOs; BidPanel renders for each of 6 viewer states.
- WS envelope flow — simulated STOMP server → cache merges → BidPanel updates → outbid toast.
- Reconnect reconcile — simulate `onWebSocketClose → onConnect` → assert `GET /auctions/{id}` re-fires.
- Buy-now flow — click "Buy now" → confirm dialog → submit → `AUCTION_ENDED` envelope → `AuctionEndedPanel` mounts.
- CSS-only responsive toggle — JSDOM assertion on class presence (`hidden lg:block` / `lg:hidden`); no viewport resize needed.

### Coverage target

100% on new components; `lib/ws/` hardening gets full branch coverage including race cases.

## 17. Task Breakdown

10 bite-sized tasks:

1. **WS hardening** — `lib/ws/client.ts` re-attach list model + 5 new test cases. API-preserving.
2. **API client + types** — `lib/api/auctions.ts` (bid/proxy methods), `lib/api/myBids.ts`, `types/auction.ts` envelope types.
3. **AuctionPage server shell + client skeleton** — server `page.tsx`, `AuctionDetailClient.tsx` with React Query seeds, envelope handler, stub subcomponents.
4. **`AuctionHero` + `ParcelInfoPanel` + `SellerProfileCard` + `VisitInSecondLifeButton`** — read-only content.
5. **`BidPanel` + `PlaceBidForm` + `ProxyBidSection` + `ConfirmBidDialog`** — 6 variants, both forms, error mapping, buy-now guards.
6. **`BidHistoryList` + `AuctionEndedPanel` + `AuctionEndedRow` + snipe chip + snipe toast banner**.
7. **`OutbidToastProvider` + `ReconnectingBanner` + connection-driven form disable**.
8. **Mobile — `StickyBidBar` + `BidSheet` + CSS-only `lg:` toggle**.
9. **My Bids tab + My Listings enrichment + public-profile `ActiveListingsSection` + backend `GET /users/{id}/auctions` endpoint**.
10. **Documentation sweep** — README update, FOOTGUNS (WS re-attach, CSS-only toggle, buy-now overspend, server-time offset), DEFERRED_WORK updates.

## 18. File Structure

```
frontend/src/
├── app/
│   └── auction/[id]/
│       ├── page.tsx                                (replace skeleton)
│       └── AuctionDetailClient.tsx                 (NEW)
├── components/auction/                             (NEW directory)
│   ├── AuctionHero.{tsx,test.tsx}
│   ├── ParcelInfoPanel.{tsx,test.tsx}
│   ├── BidPanel.{tsx,test.tsx}
│   ├── PlaceBidForm.{tsx,test.tsx}
│   ├── ProxyBidSection.{tsx,test.tsx}
│   ├── ConfirmBidDialog.{tsx,test.tsx}
│   ├── BidHistoryList.{tsx,test.tsx}
│   ├── BidHistoryRow.{tsx,test.tsx}
│   ├── AuctionEndedPanel.{tsx,test.tsx}
│   ├── AuctionEndedRow.{tsx,test.tsx}
│   ├── SellerProfileCard.{tsx,test.tsx}
│   ├── VisitInSecondLifeButton.{tsx,test.tsx}
│   ├── ReserveStatusIndicator.{tsx,test.tsx}
│   ├── SnipeProtectionBadge.{tsx,test.tsx}
│   ├── VerificationTierBadge.{tsx,test.tsx}
│   ├── StickyBidBar.{tsx,test.tsx}
│   ├── BidSheet.{tsx,test.tsx}
│   ├── ReconnectingBanner.{tsx,test.tsx}
│   ├── OutbidToastProvider.{tsx,test.tsx}
│   └── AuthGateMessage.{tsx,test.tsx}
├── components/bids/                                (NEW directory)
│   ├── MyBidsTab.{tsx,test.tsx}
│   ├── MyBidSummaryRow.{tsx,test.tsx}
│   ├── MyBidStatusBadge.{tsx,test.tsx}
│   └── MyBidsFilterTabs.{tsx,test.tsx}
├── components/user/
│   ├── ActiveListingsSection.{tsx,test.tsx}        (NEW)
│   └── PublicProfileView.tsx                       (modify)
├── components/listing/
│   └── ListingSummaryRow.{tsx,test.tsx}            (modify)
├── lib/
│   ├── ws/
│   │   ├── client.ts                               (re-attach model)
│   │   └── client.test.ts                          (+ 5 cases)
│   └── api/
│       ├── auctions.ts                             (modify — bid + proxy)
│       └── myBids.ts                               (NEW)
├── types/
│   └── auction.ts                                  (add envelope types)
├── hooks/
│   ├── useAuction.ts                               (NEW)
│   ├── useBidHistory.ts                            (NEW)
│   ├── useMyProxy.ts                               (NEW)
│   ├── useMyBids.ts                                (NEW)
│   └── useActiveListings.ts                        (NEW)
└── pages layout (app/dashboard/(verified)/bids/page.tsx — modify)

backend/src/main/java/com/slparcelauctions/backend/
├── user/UserController.java                        (modify — add GET /users/{id}/auctions)
├── auction/AuctionRepository.java                  (modify — add findBySellerIdAndStatusOrderByEndsAtAsc)
└── auction/PublicUserAuctionsService.java          (NEW)
```

## 19. Deferred Work (to add during Task 10 doc sweep)

- **Per-user public listings page `/users/{id}/listings`** — dedicated per-seller listing page; Epic 07 Browse territory.
- **Bid history infinite scroll** — Phase 1 ships page-with-load-more. Intersection-observer polish later.
- **Ended-auction escrow flow UI** — current `AuctionEndedPanel` shows static outcome; Epic 05 adds "Pay now" / "Transfer land" flow.
- **WS reconnect telemetry** — no metrics on reconnect frequency / duration. Epic 09 ops or Epic 10 admin.
- **Saved / watchlist "Curator Tray"** — DESIGN.md §5's saved-plots dock. Epic 07.
- **Swipe-to-dismiss on `BidSheet`** — intentionally out of scope for sub-spec 2. Close via backdrop / Escape / close button.

## 20. Open Questions

None at spec time. All Q1-Q7 resolved during brainstorm:
- **Q1 Scope** — core + deferred items (active listings, My Listings view links) + WS hardening
- **Q2 Real-time** — WS-first with REST reconcile; no polling; disconnect disables bid; outbid toast from envelope
- **Q3 Detail page layout** — 8/4 split, sticky sidebar BidPanel
- **Q4 BidPanel structure** — two stacked forms (place bid + set max proxy)
- **Q5 Mobile** — sticky compact bar + tap-to-expand bottom sheet
- **Q6 My Bids rows** — status-first with colored left border
- **Q7 WS hardening** — re-attach list model
