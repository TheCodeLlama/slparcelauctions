# Epic 04 Sub-Spec 2 — Auction Engine Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Source spec:** [`docs/superpowers/specs/2026-04-21-epic-04-sub-2-auction-engine-frontend.md`](../specs/2026-04-21-epic-04-sub-2-auction-engine-frontend.md) — every task below refers back to the spec by section number. Subagents should receive the relevant spec section inline with their task prompt.

**Goal:** Ship the buyer-facing auction-engine frontend — detail page at `/auction/[id]`, My Bids tab, enriched My Listings rows, public-profile active listings, hardened WS client.

**Architecture:** Next.js 16 server component fetches auction + bids; client component subscribes to `/topic/auction/{id}`, merges envelopes into React Query cache. `BidPanel` with two stacked forms (place bid + proxy). Mobile: CSS-only `hidden lg:block`/`lg:hidden` toggle between sidebar and sticky bar + bottom sheet. WS client rewritten with re-attach list model.

**Tech Stack:** Next.js 16 App Router, React 19, TypeScript, Tailwind CSS 4, Headless UI `Dialog`, `@stomp/stompjs` + `sockjs-client`, React Query v5, Vitest + React Testing Library + MSW + happy-dom.

---

## Preflight (before starting Task 1)

- [ ] **P.1: Confirm branch + baseline**

```bash
git rev-parse --abbrev-ref HEAD       # expect: task/04-sub-2-auction-engine-frontend
git status                            # expect: working tree clean (spec already committed)
cd frontend && npm test               # establish green frontend baseline
cd ../backend && ./mvnw test          # 613 green baseline from sub-spec 1
```

- [ ] **P.2: Verify dev infrastructure is running**

Postgres + Redis + MinIO containers. Dev backend + frontend boot cleanly.

- [ ] **P.3: Read the spec**

Skim spec sections §5 (Real-Time), §6 (WS hardening), §7 (AuctionPage), §9 (BidPanel). Other sections read as needed when dispatching the per-task subagent.

---

## Task 1: WS client re-attach list model

**Spec reference:** §6.

**Files:**
- Modify: `frontend/src/lib/ws/client.ts` — replace current subscribe/attach logic with the registry model
- Modify: `frontend/src/lib/ws/client.test.ts` — expand with 5 new test cases per §6
- Modify: `frontend/src/lib/ws/types.ts` — no public API changes, but may add internal `SubscriptionEntry` type

**Steps:**

- [ ] **1.1 Write failing tests first (TDD)**

Add to `client.test.ts`:
1. `bulkReattach_restoresAllSubscriptionsOnReconnect` — subscribe to 3 topics, force `onWebSocketClose → onConnect`, assert all 3 handles non-null.
2. `racePin_rapidDisconnectReconnectCycle_subscriptionSurvives` — subscribe during `reconnecting`, simulate `connected → close → connected`, assert sub is live.
3. `unsubscribeDuringReconnect_entryRemovedFromRegistry` — subscribe, force close, unsubscribe while reconnecting, then connect; assert sub NOT re-attached.
4. `subscribeDuringSweep_lateAddedEntryAttachedInSamePass` — inside an `onMessage` handler during reconnect, synchronously call `subscribe()`; assert new sub is live after single reconnect cycle.
5. `memoryHygiene_1000CyclesLeavesRegistryEmpty` — 1000 subscribe/unsubscribe pairs across reconnects, assert final registry size === 0.

Use the same `@stomp/stompjs` mock pattern already in the file. Expose an internal `__getRegistrySizeForTests()` to verify #5.

Run: `cd frontend && npm test -- ws/client` — expect FAIL on all 5 new cases.

- [ ] **1.2 Implement the registry model**

Per §6. Key points:
- Module-level `entries: Map<string, SubscriptionEntry>`.
- `subscribe()` creates entry, calls `ensureAttached`, returns unsubscribe closure.
- `onConnect` sweeps the live Map (NOT `Array.from`). Add explicit code comment:
  ```typescript
  // Entries may grow during this sweep — iterate the live Map, not
  // Array.from(entries.values()). Map iteration is spec-defined to visit
  // entries added during iteration if not yet reached, so an onMessage
  // callback that calls subscribe() synchronously has its new entry
  // attached in the same pass.
  ```
- `onWebSocketClose` nulls all handles; doesn't call unsubscribe on dead handles.
- Unsubscribe closure removes entry from the Map and calls `handle.unsubscribe()` only if handle is live AND client connected.

Preserve existing public API: `subscribe`, `subscribeToConnectionState`, `getConnectionState`, `__devForceDisconnect`, `__devForceReconnect`, `__resetWsClientForTests`. Add `__getRegistrySizeForTests()` for test #5.

Run: all 5 new tests PASS. Existing tests still pass.

- [ ] **1.3 Verify existing hooks still work**

`lib/ws/hooks.ts` (useConnectionState, useStompSubscription) need no changes. Run `hooks.test.tsx` — expect PASS.

- [ ] **1.4 Commit**

```bash
cd frontend && npm test
git add frontend/src/lib/ws/
git commit -m "feat(ws): re-attach list model with idempotent sweep on onConnect"
git push
```

---

## Task 2: API client + types

**Spec reference:** §4, §9, §12, §13, §14.

**Files:**
- Modify: `frontend/src/lib/api/auctions.ts` — add `getAuction`, `getBidHistory`, `placeBid`, `createProxy`, `updateProxy`, `cancelProxy`, `getMyProxy`
- Create: `frontend/src/lib/api/myBids.ts` — `getMyBids(status, page)`
- Modify: `frontend/src/types/auction.ts` — add `BidSettlementEnvelope`, `AuctionEndedEnvelope`, `BidHistoryEntry`, `MyBidSummary`, `MyBidStatus`, `AuctionEndOutcome`, `BidType`

**Steps:**

- [ ] **2.1 Add DTO types to `types/auction.ts`**

Match sub-spec 1's Java DTOs field-for-field:

```typescript
export type BidType = "MANUAL" | "PROXY_AUTO" | "BUY_NOW";
export type AuctionEndOutcome = "SOLD" | "RESERVE_NOT_MET" | "NO_BIDS" | "BOUGHT_NOW";
export type MyBidStatus = "WINNING" | "OUTBID" | "WON" | "LOST" | "RESERVE_NOT_MET" | "CANCELLED" | "SUSPENDED";

export interface BidHistoryEntry {
  bidId: number;
  userId: number;
  bidderDisplayName: string;
  amount: number;
  bidType: BidType;
  snipeExtensionMinutes: number | null;
  newEndsAt: string | null;
  createdAt: string;
}

export interface BidSettlementEnvelope {
  type: "BID_SETTLEMENT";
  auctionId: number;
  serverTime: string;
  currentBid: number;
  currentBidderId: number;
  currentBidderDisplayName: string;
  bidCount: number;
  endsAt: string;
  originalEndsAt: string;
  newBids: BidHistoryEntry[];
}

export interface AuctionEndedEnvelope {
  type: "AUCTION_ENDED";
  auctionId: number;
  serverTime: string;
  endsAt: string;
  endOutcome: AuctionEndOutcome;
  finalBid: number | null;
  winnerUserId: number | null;
  winnerDisplayName: string | null;
  bidCount: number;
}

export interface BidResponse {
  bidId: number;
  auctionId: number;
  amount: number;
  bidType: BidType;
  bidCount: number;
  endsAt: string;
  originalEndsAt: string;
  snipeExtensionMinutes: number | null;
  newEndsAt: string | null;
  buyNowTriggered: boolean;
}

export interface ProxyBidResponse {
  proxyBidId: number;
  auctionId: number;
  maxAmount: number;
  status: "ACTIVE" | "EXHAUSTED" | "CANCELLED";
  createdAt: string;
  updatedAt: string;
}

export interface MyBidSummary {
  auction: AuctionSummaryForMyBids;
  myHighestBidAmount: number;
  myHighestBidAt: string;
  myProxyMaxAmount: number | null;
  myBidStatus: MyBidStatus;
}

export interface AuctionSummaryForMyBids {
  id: number;
  status: string;  // AuctionStatus
  endOutcome: AuctionEndOutcome | null;
  parcelName: string;
  parcelRegion: string;
  parcelAreaSqm: number;
  snapshotUrl: string | null;
  endsAt: string | null;
  endedAt: string | null;
  currentBid: number | null;
  bidderCount: number;
  sellerUserId: number;
  sellerDisplayName: string;
}
```

- [ ] **2.2 Add API methods**

In `lib/api/auctions.ts` (keep existing methods):
```typescript
export async function placeBid(auctionId: number, amount: number): Promise<BidResponse> {
  return api<BidResponse>(`/auctions/${auctionId}/bids`, { method: "POST", body: JSON.stringify({ amount }) });
}

export async function getBidHistory(auctionId: number, params: { page: number; size: number }): Promise<Page<BidHistoryEntry>> {
  return api<Page<BidHistoryEntry>>(`/auctions/${auctionId}/bids?page=${params.page}&size=${params.size}`);
}

export async function createProxy(auctionId: number, maxAmount: number): Promise<ProxyBidResponse> {
  return api<ProxyBidResponse>(`/auctions/${auctionId}/proxy-bid`, { method: "POST", body: JSON.stringify({ maxAmount }) });
}

export async function updateProxy(auctionId: number, maxAmount: number): Promise<ProxyBidResponse> {
  return api<ProxyBidResponse>(`/auctions/${auctionId}/proxy-bid`, { method: "PUT", body: JSON.stringify({ maxAmount }) });
}

export async function cancelProxy(auctionId: number): Promise<void> {
  await api(`/auctions/${auctionId}/proxy-bid`, { method: "DELETE" });
}

export async function getMyProxy(auctionId: number): Promise<ProxyBidResponse | null> {
  try {
    return await api<ProxyBidResponse>(`/auctions/${auctionId}/proxy-bid`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export async function getActiveListingsForUser(userId: number, params: { page?: number; size?: number } = {}): Promise<Page<PublicAuctionResponse>> {
  const page = params.page ?? 0;
  const size = params.size ?? 6;
  return api<Page<PublicAuctionResponse>>(`/users/${userId}/auctions?status=ACTIVE&page=${page}&size=${size}`);
}
```

Create `lib/api/myBids.ts`:
```typescript
export async function getMyBids(params: { status?: "active" | "won" | "lost" | "all"; page?: number; size?: number } = {}): Promise<Page<MyBidSummary>> {
  const qs = new URLSearchParams();
  if (params.status) qs.set("status", params.status);
  qs.set("page", String(params.page ?? 0));
  qs.set("size", String(params.size ?? 20));
  return api<Page<MyBidSummary>>(`/users/me/bids?${qs}`);
}
```

- [ ] **2.3 Tests**

Existing `api.test.ts` may not cover these new endpoints — add minimal test per new method using MSW handler. Ensure `getMyProxy` returns `null` on 404 (swallow behavior).

- [ ] **2.4 Commit**

```bash
git add frontend/src/lib/api frontend/src/types/auction.ts
git commit -m "feat(api): add bid, proxy-bid, my-bids, and active-listings API clients"
git push
```

---

## Task 3: AuctionPage server shell + client skeleton

**Spec reference:** §7, §5.

**Files:**
- Replace: `frontend/src/app/auction/[id]/page.tsx`
- Create: `frontend/src/app/auction/[id]/AuctionDetailClient.tsx`
- Create: `frontend/src/hooks/useAuction.ts`, `useBidHistory.ts`, `useMyProxy.ts`
- Create: integration test `frontend/src/app/auction/[id]/page.integration.test.tsx` (or sibling to the client)

**Steps:**

- [ ] **3.1 Write failing integration test**

`@testing-library/react`-level test using MSW. Seeds a `PublicAuctionResponse` + first bid page; asserts page renders auction title + current bid + placeholder slots for BidPanel / AuctionHero / BidHistoryList.

- [ ] **3.2 Implement the server component**

Per §7:
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

- [ ] **3.3 Implement `AuctionDetailClient.tsx`**

Per §7. Stub subcomponents with `<div className="hidden">Placeholder: AuctionHero</div>` etc. — later tasks replace them. Wire:
- React Query seeds from `initialAuction` / `initialBidPage`
- `useStompSubscription` for the auction topic
- `handleEnvelope` as `useCallback` (per §5)
- `useConnectionState` → reconnecting ref
- Reconnect reconcile useEffect
- Server-time offset ref

- [ ] **3.4 Implement React Query hooks**

`useAuction(id, initialData)`, `useBidHistory(id, page)`, `useMyProxy(id)`. Each is a thin `useQuery` wrapper with the right query key from §5.

- [ ] **3.5 Verify test passes + commit**

```bash
cd frontend && npm test -- auction/\\[id\\]
git add frontend/
git commit -m "feat(auction): server shell + client skeleton with react query seeds and ws handler"
git push
```

---

## Task 4: Read-only content components

**Spec reference:** §7, §8, §14 (reused seller card).

**Files:**
- Create: `frontend/src/components/auction/AuctionHero.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/ParcelInfoPanel.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/SellerProfileCard.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/VisitInSecondLifeButton.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/VerificationTierBadge.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/SnipeProtectionBadge.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/ReserveStatusIndicator.{tsx,test.tsx}`

**Steps per component (TDD):**

- [ ] **4.1 `AuctionHero`** — 4-cell asymmetric grid (1 large + 2 small + "view all photos"). Photo fallback: first uploaded photo → parcel.snapshotUrl → gradient placeholder. Responsive: stacks below `md:`.

- [ ] **4.2 `ParcelInfoPanel`** — parcel name, region, area, maturity badge, `VerificationTierBadge`, tags, seller description.

- [ ] **4.3 `SellerProfileCard`** — reuses Epic 02's `ReputationStars` + `NewSellerBadge`. Avatar, displayName, rating, completed sales, link to `/users/{id}`.

- [ ] **4.4 `VisitInSecondLifeButton`** — `Dropdown` with "Open in Viewer" (`secondlife:///`) and "View on Map" (`https://maps.secondlife.com/secondlife/{region}/x/y/z`).

- [ ] **4.5 `VerificationTierBadge`** — `Chip` with tier-specific icon (from `icons.ts`) + color: SCRIPT=blue, BOT=green, HUMAN=yellow/orange.

- [ ] **4.6 `SnipeProtectionBadge`** — `Chip` with `Shield` icon + window duration ("Snipe 15m"). Omits when `snipeProtect === false`.

- [ ] **4.7 `ReserveStatusIndicator`** — `Chip` variant: `reservePrice === null` → no chip / `currentBid >= reservePrice` → "Reserve met" (green) / otherwise → "Reserve not met" (orange). Amount hidden per DESIGN.md.

- [ ] **4.8 Wire into `AuctionDetailClient`**

Replace the stub placeholders with real components. Run full frontend test suite.

- [ ] **4.9 Commit**

```bash
git add frontend/src/components/auction
git commit -m "feat(auction): hero gallery, parcel info, seller card, and reusable badges"
git push
```

---

## Task 5: BidPanel + forms

**Spec reference:** §9.

**Files:**
- Create: `frontend/src/components/auction/BidPanel.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/PlaceBidForm.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/ProxyBidSection.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/ConfirmBidDialog.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/AuthGateMessage.{tsx,test.tsx}`

**Steps:**

- [ ] **5.1 `BidPanel` variant dispatcher**

6 variants per §9. Tests: render each variant under its condition, assert correct content. Use a `variant` prop derived in the parent `AuctionDetailClient` so the panel itself stays dumb.

- [ ] **5.2 `AuthGateMessage`**

Variants: `unauth` (sign-in link with `returnTo` param), `unverified` (verify link), `seller` (read-only callout), `ended` (redirect to the `AuctionEndedPanel` — but Task 6 ships that; for Task 5 just render a placeholder).

- [ ] **5.3 `PlaceBidForm`**

Amount input + primary button. Client validation. `useMutation` calls `placeBid`. Success: clear input. Error mapping per §9 error table.

Buy-now confirm when `amount >= buyNowPrice`: `ConfirmBidDialog` with specific copy. Overspend confirm when `amount > buyNowPrice`: same dialog with explanatory copy.

Large-bid confirm when `amount > 10000`: generic "Confirm L$X" dialog with session-scoped "don't show again" checkbox.

Connection-state disable.

- [ ] **5.4 `ProxyBidSection`**

3 branches: `null | CANCELLED → create`, `ACTIVE → update / cancel`, `EXHAUSTED → resurrect`.

`createProxy`, `updateProxy`, `cancelProxy` mutations, each with error mapping.

Cancel button disabled when caller is `currentBidderId`.

Buy-now confirm when `maxAmount >= buyNowPrice`.

Callout "You have a proxy at L$X max" rendered above the section when `existingProxy?.status === "ACTIVE"`.

- [ ] **5.5 `ConfirmBidDialog`**

Headless UI `Dialog`. Props: `title`, `message`, `confirmLabel`, `onConfirm`, `onCancel`, optional `showDontAskAgainCheckbox`. Session-scoped `sessionStorage` key for "don't show again" per-dialog-kind.

- [ ] **5.6 Wire `BidPanel` + forms into `AuctionDetailClient`**

Pass `existingProxy` from `useMyProxy` to `BidPanel`. The panel variant is derived inside based on `currentUser`, `auction.status`, `auction.seller.id`, `existingProxy`.

- [ ] **5.7 Full suite + commit**

```bash
cd frontend && npm test
git add frontend/src/components/auction
git commit -m "feat(auction): bid panel with place-bid and proxy-bid forms plus confirm dialogs"
git push
```

---

## Task 6: Bid history + ended state

**Spec reference:** §10.

**Files:**
- Create: `frontend/src/components/auction/BidHistoryList.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/BidHistoryRow.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/AuctionEndedPanel.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/AuctionEndedRow.{tsx,test.tsx}`

**Steps:**

- [ ] **6.1 `BidHistoryRow`**

Avatar + linked display name + amount + type chip + snipe chip + relative timestamp with absolute tooltip. Tests for each type variant.

- [ ] **6.2 `BidHistoryList`**

Paginated via `useBidHistory(id, page)`. Page 0 reactive to WS merges. "Load more" button. Prefetch next page on scroll to last row. Animation on new rows (fade-in + pulse ~2s).

- [ ] **6.3 `AuctionEndedPanel`**

4 variants per §10. Viewer overlays: winner / seller / other.

- [ ] **6.4 `AuctionEndedRow`**

Standalone component pinned above `BidHistoryList` (NOT in bid cache). Conditional on `auction.status === "ENDED"`.

- [ ] **6.5 Wire into `AuctionDetailClient`**

Replace BidPanel with AuctionEndedPanel when status === ENDED. Mount AuctionEndedRow above BidHistoryList conditionally.

- [ ] **6.6 Snipe extension banner**

A dismissable banner that appears inside `BidPanel` for 4s when a `BID_SETTLEMENT` envelope carries a `newBids[].snipeExtensionMinutes != null`. Updates in-place on rapid consecutive extensions.

- [ ] **6.7 Commit**

```bash
git add frontend/src/components/auction
git commit -m "feat(auction): bid history list with snipe chip and ended state panel"
git push
```

---

## Task 7: Real-time reactions

**Spec reference:** §5, §15.

**Files:**
- Create: `frontend/src/components/auction/OutbidToastProvider.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/ReconnectingBanner.{tsx,test.tsx}`
- Modify: `AuctionDetailClient.tsx` — wire toast + banner

**Steps:**

- [ ] **7.1 `OutbidToastProvider`**

Module with `maybeFire(prevAuction, env, currentUserId, toast)` static method. Was-winning guard per §15. Tests: fires on displacement, doesn't fire when caller wasn't winning, doesn't fire twice on back-to-back envelopes while still-outbid.

- [ ] **7.2 `ReconnectingBanner`**

5 states per §15. Existing `useConnectionState` hook. Reload button on `disconnected`. Sign-in link on `error` when detail mentions session expiry.

- [ ] **7.3 Connection-state form disable**

Wire through `BidPanel` → `PlaceBidForm` + `ProxyBidSection`: when `connectionState.status !== "connected"`, disable submit buttons with "Waiting for connection…" helper.

- [ ] **7.4 Commit**

```bash
git add frontend/src/components/auction
git commit -m "feat(auction): outbid toast and reconnecting banner with form disable"
git push
```

---

## Task 8: Mobile pattern

**Spec reference:** §8, §11.

**Files:**
- Create: `frontend/src/components/auction/StickyBidBar.{tsx,test.tsx}`
- Create: `frontend/src/components/auction/BidSheet.{tsx,test.tsx}`
- Modify: `AuctionDetailClient.tsx` — add the mobile chrome with `lg:hidden` wrapper

**Steps:**

- [ ] **8.1 `StickyBidBar`**

Fixed-bottom glass bar. 5 variants (unauth / unverified / seller / bidder / ended). Button opens `BidSheet` via prop callback. Disabled with spinner when not connected.

- [ ] **8.2 `BidSheet`**

Headless UI `Dialog` with `rounded-t-xl`, `max-height: 85vh`. No swipe-to-dismiss. Visual drag handle. Closes via backdrop / Escape / close button. Contains the full `BidPanel`.

- [ ] **8.3 Wire into `AuctionDetailClient`**

Per §8:
```tsx
<aside className="hidden lg:block lg:col-span-4">
  <div className="sticky top-24"><BidPanel ... /></div>
</aside>

<div className="lg:hidden">
  <StickyBidBar ... onOpenSheet={() => setSheetOpen(true)} />
  <BidSheet isOpen={sheetOpen} onClose={() => setSheetOpen(false)}>
    <BidPanel ... />
  </BidSheet>
</div>
```

- [ ] **8.4 Responsive test**

Integration test: assert CSS classes present (`hidden lg:block`, `lg:hidden`) without needing viewport resize.

- [ ] **8.5 Commit**

```bash
git add frontend/src/components/auction
git commit -m "feat(auction): mobile sticky bar + bottom sheet with CSS-only breakpoint toggle"
git push
```

---

## Task 9: My Bids tab + My Listings enrichment + public-profile active listings + backend endpoint

**Spec reference:** §12, §13, §14.

### Backend endpoint

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java` — add `GET /users/{userId}/auctions`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java` — add `findBySellerIdAndStatusOrderByEndsAtAsc`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/PublicUserAuctionsService.java` — optional, or inline into existing service
- Create: `backend/src/test/java/.../auction/PublicUserAuctionsServiceTest.java` + slice test

- [ ] **9.1 Write failing backend tests**

- Integration test: public user with 2 ACTIVE + 1 SUSPENDED auctions → GET returns 2 (SUSPENDED excluded).
- Pre-ACTIVE auctions excluded.
- Pagination works.
- Returns `PublicAuctionResponse` DTO shape.

- [ ] **9.2 Implement endpoint**

Per §14. Public — no `@PreAuthorize`. Method filters by `seller.id = :userId` and `status = 'ACTIVE'` (hardcoded for Phase 1).

- [ ] **9.3 Run backend tests**

```bash
cd backend && ./mvnw test
# Expect 613 baseline + ~3 new = ~616
```

### Frontend surfaces

**Files:**
- Create: `frontend/src/components/bids/MyBidsTab.{tsx,test.tsx}`
- Create: `frontend/src/components/bids/MyBidSummaryRow.{tsx,test.tsx}`
- Create: `frontend/src/components/bids/MyBidStatusBadge.{tsx,test.tsx}`
- Create: `frontend/src/components/bids/MyBidsFilterTabs.{tsx,test.tsx}`
- Create: `frontend/src/hooks/useMyBids.ts`
- Create: `frontend/src/hooks/useActiveListings.ts`
- Create: `frontend/src/components/user/ActiveListingsSection.{tsx,test.tsx}`
- Modify: `frontend/src/app/dashboard/(verified)/bids/page.tsx` — replace EmptyState with `<MyBidsTab />`
- Modify: `frontend/src/components/listing/ListingSummaryRow.tsx` — add sub-line per §13
- Modify: `frontend/src/components/user/PublicProfileView.tsx` — replace placeholder with `<ActiveListingsSection />`

- [ ] **9.4 My Bids tab**

`MyBidsFilterTabs` with URL sync via `router.replace({ query: { status } })`. `MyBidSummaryRow` with 7-status border colors per §12. Load-more pagination.

- [ ] **9.5 My Listings enrichment**

Modify `ListingSummaryRow.tsx` to add status-conditional sub-line per §13. Update sibling test.

- [ ] **9.6 Public-profile `ActiveListingsSection`**

Grid of up to 6 cards. Empty state when 0. "View all" conditional on `totalElements > 6` (deferred target). Replace the existing `EmptyState icon={Gavel}` in `PublicProfileView.tsx`.

- [ ] **9.7 Frontend tests**

`cd frontend && npm test` — all green.

- [ ] **9.8 Commit**

```bash
git add backend/ frontend/
git commit -m "feat(auction): my bids tab, my listings enrichment, and public profile active listings"
git push
```

---

## Task 10: Documentation sweep

**Spec reference:** §19 (deferred work), general project conventions.

**Files:**
- Modify: `README.md`
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `docs/implementation/DEFERRED_WORK.md`

**Steps:**

- [ ] **10.1 README update**

Add Epic 04 sub-spec 2 summary paragraph covering:
- Auction detail page at `/auction/[id]` with live WS bidding
- My Bids tab, My Listings enrichment
- Public-profile active listings section
- WS client hardened with re-attach list model
- Final test counts — run `cd frontend && npm test -- --run | tail -5` and `cd backend && ./mvnw test | grep "Tests run:"` for real numbers

- [ ] **10.2 FOOTGUNS additions**

Next numbers after the current highest. Add:
- WS re-attach registry — iterate live Map, NOT snapshot (subscribe-during-sweep behavior)
- CSS-only responsive toggle to avoid hydration flash — render both, toggle with `hidden lg:block` / `lg:hidden`
- Buy-now overspend guard — both `PlaceBidForm` and `ProxyBidSection` need their own confirms
- Server-time offset correction — countdown uses `envelope.serverTime - clientNow()` diff to avoid client-clock drift
- React Query cache + WS envelope merge — `setQueryData` must dedupe by `bidId` to avoid duplicate rows from reconnect re-fetches

- [ ] **10.3 DEFERRED_WORK updates**

Remove delivered items:
- "Active listings section on public profile" (delivered Task 9)
- "Public listing page target for 'View public listing' links" (delivered Task 3+)
- "WS race on subscribe-during-reconnect" (delivered Task 1)

Add new deferred items per §19:
- Per-user public listings page `/users/{id}/listings`
- Bid history infinite scroll
- Ended-auction escrow flow UI (Epic 05)
- WS reconnect telemetry (Epic 09 or Epic 10)
- Saved / watchlist "Curator Tray"
- `BidSheet` swipe-to-dismiss (intentionally out of scope)

- [ ] **10.4 Final test run**

```bash
cd frontend && npm test
cd ../backend && ./mvnw test
```

Both suites green.

- [ ] **10.5 Commit**

```bash
git add README.md docs/implementation/FOOTGUNS.md docs/implementation/DEFERRED_WORK.md
git commit -m "docs: README sweep + FOOTGUNS + DEFERRED_WORK for Epic 04 sub-spec 2"
git push
```

---

## Wrap-up

- [ ] **Final full-suite run**

```bash
cd frontend && npm test
cd ../backend && ./mvnw test
```

- [ ] **Verify clean state**

```bash
git status     # expect: clean
git log origin/task/04-sub-2-auction-engine-frontend..HEAD   # expect: empty
```

- [ ] **Open PR**

```bash
gh pr create --base dev --title "Epic 04 sub-spec 2: auction engine frontend" --body "$(cat <<'EOF'
## Summary

- Auction detail page at `/auction/[id]` — 8/4 split, sticky sidebar BidPanel, asymmetric hero gallery
- Two-stacked-form BidPanel (place bid + set max proxy), both always visible for verified non-seller
- WS-first real-time with REST reconcile on reconnect; server-time offset correction on countdown
- Mobile: CSS-only `lg:hidden`/`hidden lg:block` toggle to sticky compact bar + bottom sheet
- My Bids dashboard tab with status-first colored-border rows across 7 buckets
- My Listings rows enriched with live bid data (current bid / bidder count / time remaining / ended outcome)
- Public-profile active-listings section (new public endpoint `GET /users/{id}/auctions?status=ACTIVE`)
- WS client hardened — re-attach list model with idempotent onConnect sweep, subscribe-during-sweep safe
- Scope: frontend + 1 small backend endpoint. Sub-spec 2 ends Epic 04.

## Test plan

- [ ] Frontend: `cd frontend && npm test` green
- [ ] Backend: `cd backend && ./mvnw test` green
- [ ] Manual: place a bid via UI → WS envelope arrives → BidPanel updates without reload
- [ ] Manual: set proxy max → get outbid by another user → proxy auto-counters → history shows both rows
- [ ] Manual: trigger buy-now bid → page transitions to ENDED within 1s
- [ ] Manual: mobile viewport — sticky bar shows current bid, tap opens sheet
- [ ] Manual: kill backend mid-session → ReconnectingBanner appears, bid input disables → restart backend → reconcile re-fetches
EOF
)"
```

- [ ] **Monitor mergeability + merge**

```bash
gh pr view <PR#> --json mergeable,mergeStateStatus,state
gh pr merge <PR#> --merge --delete-branch
git checkout dev && git pull --ff-only
```
