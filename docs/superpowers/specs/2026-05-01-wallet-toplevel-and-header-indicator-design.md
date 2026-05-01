# Wallet Top-Level Tab + Header Indicator + Beautify + Ledger UX

**Date:** 2026-05-01
**Branch target:** `feat/wallet-toplevel` off `dev`
**Scope:** Promote the wallet from a tab inside `/dashboard/(verified)/wallet` to a top-level `/wallet` route with its own header NavLink and a persistent header indicator. Beautify the existing `WalletPanel` to match SLPA's design system. Extend the ledger view with full pagination, filters (entry type, date range, amount range), and CSV export. Wire WebSocket live updates so the header indicator and the wallet page reflect server-side balance changes without polling alone.

This is the third PR in the wallet-model series (after the foundation in PR #65/#66/#73/#74 and the in-world LSL rewrite in PR #67/#68). It's purely a UI + UX expansion — no new wallet primitives, no schema changes, no flag changes.

---

## §1 — Scope

**In scope:**

- New top-level route `/wallet` (`frontend/src/app/wallet/page.tsx`) gated on `useCurrentUser().verified === true`. Unverified users redirect to `/dashboard/verify`.
- Remove the existing `/dashboard/(verified)/wallet/page.tsx` file and the `Wallet` tab entry from `frontend/src/app/dashboard/(verified)/layout.tsx`'s `TABS` array.
- Add a `Wallet` NavLink to `frontend/src/components/layout/Header.tsx` (verified-only), and a corresponding row to `frontend/src/components/layout/MobileMenu.tsx`.
- New `<HeaderWalletIndicator>` component placed in `Header.tsx` between `<div id="curator-tray-slot" />` and `<NotificationBell />`. Verified-only. Renders icon + amount on desktop, icon-only on mobile, hover-popover with breakdown on desktop, click/tap routes to `/wallet`.
- Beautify pass on `WalletPanel.tsx`: visual hierarchy (dominant `Available` number), Material-3 design tokens, project's `Input`/`Modal` components, Lucide icons on ledger rows, color-coded amounts, polished empty state, hover row highlight on the activity table, relative-then-absolute date formatting.
- Extend `GET /api/v1/me/wallet/ledger` (currently mentioned in the wallet spec but not implemented) with full pagination, multi-select entry-type filter, date-range filter (`from`, `to`), and amount-range filter (`amountMin`, `amountMax`).
- Add `GET /api/v1/me/wallet/ledger/export.csv` that streams the currently-filtered ledger as CSV.
- Wire WebSocket publish on every wallet mutation point in the backend: `WalletService.deposit`, `withdrawSiteInitiated`, `withdrawTouchInitiated`, `payPenalty`, `swapReservation`, `autoFundEscrow`, `creditEscrowRefund`, `creditListingFeeRefund`, `debitListingFee`, `releaseReservationsForAuction`, `releaseAllReservationsForUser`, `recordWithdrawalSuccess`, `recordWithdrawalReversal`. Single `WALLET_BALANCE_CHANGED` envelope published to per-user STOMP queue `/user/queue/wallet`.
- Frontend subscriber in `<HeaderWalletIndicator>` and `<WalletPanel>` that listens on `/user/queue/wallet` via the existing STOMP infrastructure and merges into the React Query cache so UI updates without polling.
- Polling and on-focus refetch retained as fallback (30s interval) so a momentary WS disconnect doesn't leave stale balances on screen.

**Excluded:**

- Wallet settings page.
- Description/ref-id search on the ledger (only entry type + date range + amount range filters).
- Wallet history report aggregations beyond raw ledger entries (no monthly summaries, no per-auction roll-ups).
- Wallet ledger admin search across all users.
- Per-currency support (single L$).
- Notifications (email / SL IM) on wallet events. Live updates are in-app only.

---

## §2 — Architecture

```
                   ┌─────────────────────────────────────────────┐
                   │  Header (top of every page)                 │
                   │                                             │
                   │  Brand · Browse · Dashboard · Wallet ·      │
                   │  Create Listing · Admin                     │
                   │                                             │
                   │              [theme] [tray] [HEADER         │
                   │                            INDICATOR] [bell]│
                   └────────┬────────────────────────────────────┘
                            │ click/tap
                            ▼
                ┌──────────────────────────────────┐
                │  /wallet  (new top-level route)   │
                │  → renders <WalletPanel>          │
                │     - balance / reserved /        │
                │       available (hierarchy)       │
                │     - withdraw / pay-penalty /    │
                │       accept-terms / deposit-info │
                │       dialogs (project modal)     │
                │     - ledger view:                │
                │       - filter chrome             │
                │         (type, date, amount)      │
                │       - paged table               │
                │       - CSV export button         │
                └──────────────────────────────────┘
                            ▲                 ▲
                            │                 │
                  GET /me/wallet      WS /user/queue/wallet
                  GET /me/wallet/ledger
                  GET /me/wallet/ledger/export.csv
                            │                 │
                            ▼                 │
                ┌──────────────────────────────────┐
                │  Backend (Spring Boot)           │
                │                                  │
                │  WalletService primitives        │
                │   ├─ existing mutation methods   │
                │   └─ NEW: publish WS envelope    │
                │      after every commit          │
                │                                  │
                │  MeWalletController extensions:  │
                │   - GET /me/wallet/ledger        │
                │     (pageable, filters)          │
                │   - GET /me/wallet/ledger/       │
                │     export.csv                   │
                └──────────────────────────────────┘
```

The wallet primitives, schema, and existing endpoints are unchanged — this PR adds a UI surface, a publish step, and two read-side endpoints. Existing tests should not break.

---

## §3 — Routing & navigation

### 3.1 New route

`frontend/src/app/wallet/page.tsx` — `"use client"`. Skeleton:

```tsx
"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useCurrentUser } from "@/lib/user";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { WalletPanel } from "@/components/wallet/WalletPanel";

export default function WalletPage() {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (!user?.verified) router.replace("/dashboard/verify");
  }, [isPending, isError, user?.verified, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading wallet..." />;
  if (!user.verified) return <LoadingSpinner label="Redirecting..." />;

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 flex flex-col gap-6">
      <div>
        <h1 className="text-headline-md font-display font-bold">Wallet</h1>
        <p className="text-sm text-on-surface-variant mt-1">
          Deposit, withdraw, and view your SLPA wallet activity.
        </p>
      </div>
      <WalletPanel />
    </div>
  );
}
```

### 3.2 Removed surfaces

- `frontend/src/app/dashboard/(verified)/wallet/page.tsx` — deleted.
- `frontend/src/app/dashboard/(verified)/layout.tsx` — `Wallet` entry removed from the `TABS` array.

### 3.3 Header NavLink

`frontend/src/components/layout/Header.tsx` — desktop nav becomes:

```tsx
<nav className="hidden md:flex items-center gap-8">
  <NavLink variant="header" href="/browse">Browse</NavLink>
  <NavLink variant="header" href="/dashboard">Dashboard</NavLink>
  {status === "authenticated" && user.verified && (
    <NavLink variant="header" href="/wallet">Wallet</NavLink>
  )}
  <NavLink variant="header" href="/auction/new">Create Listing</NavLink>
  {status === "authenticated" && user.role === "ADMIN" && (
    <NavLink variant="header" href="/admin">Admin</NavLink>
  )}
</nav>
```

`MobileMenu.tsx` mirrors the same condition.

---

## §4 — Header wallet indicator

### 4.1 Component shape

`frontend/src/components/wallet/HeaderWalletIndicator.tsx`:

- Verified-only render — early-returns `null` when `useCurrentUser().data?.verified !== true`.
- Data via `useQuery({ queryKey: ['me','wallet'], queryFn: getWallet, enabled: verified, refetchInterval: 30_000, refetchOnWindowFocus: true })`.
- WS subscription via the existing STOMP client — `useEffect` subscribes to `/user/queue/wallet` while mounted; on receipt, `queryClient.setQueryData(['me','wallet'], envelope.wallet)`.
- Loading: `data === undefined` → render `L$ 0`. Subsequent loads use cached value.

### 4.2 Desktop layout

```tsx
<Link
  href="/wallet"
  className="group relative flex items-center gap-1.5 px-2 py-1 rounded-md
             hover:bg-surface-container-low transition-colors"
  aria-label="Wallet"
>
  <WalletIcon className="h-4 w-4 text-primary" aria-hidden />
  <span className="text-sm font-medium tabular-nums text-on-surface">
    L$ {available.toLocaleString()}
  </span>
  <HoverPopover wallet={data} />
</Link>
```

`<HoverPopover>` renders absolutely-positioned card visible only on `group-hover` (desktop) and on focus (keyboard accessibility). Card contents:

- Header: `Wallet`
- Three rows: `Balance L$ X` / `Reserved L$ Y` / `Available L$ Z`
- If `penaltyOwed > 0`: warning row in `bg-warning-container text-on-warning-container` with AlertTriangle icon, `Penalty owed L$ N`, and `Pay penalty →` link to `/wallet?penalty=open`.
- Footer link: `View activity →` to `/wallet`.

### 4.3 Mobile layout

```tsx
<Link href="/wallet" className="md:hidden p-2" aria-label="Wallet">
  <WalletIcon className="h-5 w-5 text-primary" />
</Link>
```

Icon only. Tap navigates. No popover (touch UX — popovers fight tap-to-navigate).

### 4.4 Mobile menu integration

`MobileMenu.tsx` adds a row when `user.verified`:

```tsx
<MobileMenuLink href="/wallet" icon={<WalletIcon />}>
  Wallet · L$ {available.toLocaleString()}
</MobileMenuLink>
```

Uses the same `useQuery` cache so the value matches the indicator.

### 4.5 `?penalty=open` query param

When the wallet page loads with `?penalty=open` and `penaltyOwed > 0`, the `<WalletPanel>` auto-opens the `<PayPenaltyDialog>`. Implementation: `useSearchParams()` in the panel component, effect reads the param once on mount, sets `showPenalty = true`, and clears the param via `router.replace("/wallet")` so a refresh doesn't re-open it.

---

## §5 — Wallet page beautify

Concrete changes to `frontend/src/components/wallet/WalletPanel.tsx`. Functionality unchanged; chrome refresh.

### 5.1 Visual hierarchy

Replace the three-column grid of equal-weight cells with:

```
┌───────────────────────────────────────────────────────┐
│  Available                                            │
│  L$ 1,234                                  (display-md)│
│                                                       │
│  Balance L$ 5,000          Reserved L$ 3,766          │
│                                  (body-sm, muted)     │
└───────────────────────────────────────────────────────┘
```

`Available` becomes the dominant number — what the user can act on. Balance + Reserved sit below as breakdown.

### 5.2 Tokens replacement

Audit the existing component for non-token Tailwind:

| Current | Replace with |
|---|---|
| `bg-amber-50` / `border-amber-400` | `bg-warning-container` / `border-warning` |
| `text-neutral-500` | `text-on-surface-variant` |
| `text-neutral-700` | `text-on-surface-variant` |
| `text-neutral-600` | `text-on-surface-variant` |
| `border-neutral-200` / `border-neutral-300` | `border-outline-variant` |
| `bg-black/40` (modal scrim) | `bg-scrim/40` |
| `bg-white` (modal surface) | `bg-surface-container` |
| `text-red-600` | `text-error` |
| Plain `<Card>` | Material-3 `bg-surface-container rounded-2xl p-6` |

The verify guards (`npm run verify:no-hex-colors`, `no-dark-variants`, `no-inline-styles`) must pass at commit.

### 5.3 Modal refactor

Replace `WalletPanel.tsx`'s hand-rolled `<SimpleDialog>` with the project's existing modal/drawer. If no shared `<Modal>` component exists yet, add one to `frontend/src/components/ui/Modal.tsx` with the standard chrome (scrim, container, header, body, footer slots, close on Escape, focus trap) and use it in:

- `<WithdrawDialog>`
- `<PayPenaltyDialog>`
- `<AcceptTermsModal>` / `<DepositInstructionsModal>`

Verify guard: no inline styles. The shared modal must use design tokens.

### 5.4 Lucide icons on ledger rows

Use the project's `@/components/ui/icons.ts` conventions (Lucide). Mapping:

| Entry type | Icon | Color |
|---|---|---|
| `DEPOSIT` | `ArrowDownToLine` | `text-success` |
| `WITHDRAW_QUEUED` | `Clock` | `text-on-surface-variant` |
| `WITHDRAW_COMPLETED` | `ArrowUpFromLine` | `text-on-surface` |
| `WITHDRAW_REVERSED` | `Undo2` | `text-warning` |
| `BID_RESERVED` | `Lock` | `text-warning` |
| `BID_RELEASED` | `Unlock` | `text-on-surface-variant` |
| `ESCROW_DEBIT` | `ArrowUpFromLine` | `text-on-surface` |
| `ESCROW_REFUND` | `ArrowDownToLine` | `text-success` |
| `LISTING_FEE_DEBIT` | `Tag` | `text-on-surface` |
| `LISTING_FEE_REFUND` | `ArrowDownToLine` | `text-success` |
| `PENALTY_DEBIT` | `AlertTriangle` | `text-warning` |
| `ADJUSTMENT` | `Pencil` | `text-on-surface-variant` |

Add the necessary lucide-react imports to `@/components/ui/icons.ts` if not already present.

### 5.5 Color-coded amounts

Credits (`+`) in `text-success`. Debits (`-`) in default `text-on-surface`. Reservations stay in `text-warning`.

### 5.6 Empty state

When `recentLedger.length === 0`, render a centered card:

```
[WalletIcon big]
No activity yet
Visit any SLPA Terminal in-world to make your first deposit.
[Locations are at SLPA HQ and partner auction venues.]
```

### 5.7 Penalty card

Replace the existing block with:

```tsx
<div className="rounded-2xl border border-warning bg-warning-container/40 p-4 flex gap-3">
  <AlertTriangle className="h-5 w-5 text-warning shrink-0 mt-0.5" />
  <div className="flex-1">
    <h3 className="font-medium text-on-warning-container">
      Outstanding penalty: {formatLindens(wallet.penaltyOwed)}
    </h3>
    <p className="text-sm text-on-surface-variant mt-1">
      Clear this to publish new listings or place new bids.
    </p>
    {wallet.available < wallet.penaltyOwed && (
      <p className="text-sm text-on-surface-variant mt-1">
        Deposit {formatLindens(wallet.penaltyOwed - wallet.available)} more or wait
        for active bids to resolve.
      </p>
    )}
    <Button variant="primary" size="sm" className="mt-3" onClick={() => setShowPenalty(true)}>
      Pay Penalty
    </Button>
  </div>
</div>
```

### 5.8 Form input

Replace bare `<input>` in `<WithdrawDialog>` and `<PayPenaltyDialog>` with the project's styled `<Input>` component (look in `frontend/src/components/ui/`; if absent, add one). Token-tied border, focus ring, error state.

### 5.9 Recent activity polish

- Hover-row highlight: `hover:bg-surface-container-low`.
- Date format: `formatDistanceToNow` (date-fns) for entries within 24h ("3 hours ago"), absolute `Apr 30, 2026 14:23` beyond.
- Tabular-nums on amount columns.

---

## §6 — Ledger view (full pagination + filters + export)

### 6.1 Endpoint extensions

#### `GET /api/v1/me/wallet/ledger`

Full pageable. Query params:

| Param | Type | Description |
|---|---|---|
| `page` | int (default 0) | Zero-indexed page number. |
| `size` | int (default 25, max 100) | Page size. |
| `entryType` | repeated string | Filter to entries whose `entry_type` is in this set. Example: `?entryType=DEPOSIT&entryType=BID_RESERVED`. Omitted = all types. |
| `from` | ISO-8601 instant | Lower bound on `created_at` (inclusive). |
| `to` | ISO-8601 instant | Upper bound on `created_at` (exclusive). |
| `amountMin` | long | Lower bound on `amount` (inclusive). |
| `amountMax` | long | Upper bound on `amount` (inclusive). |

Response:

```json
{
  "page": 0,
  "size": 25,
  "totalElements": 1234,
  "totalPages": 50,
  "entries": [ { /* LedgerEntry */ }, ... ]
}
```

Sorted `created_at DESC` always. No upper limit on total ledger size.

Implementation: extend `UserLedgerRepository` with a JPA Specification or custom query that builds the predicate from the filters, paginated via `Pageable`. Caller is the JWT-authenticated user; repository scope is hard-coded to `userId`.

#### `GET /api/v1/me/wallet/ledger/export.csv`

Streams the **currently-filtered** ledger as CSV. Same query params as the JSON endpoint, no pagination — returns all matching rows. Response headers:

```
Content-Type: text/csv; charset=utf-8
Content-Disposition: attachment; filename="slpa-wallet-ledger-<userId>-<YYYYMMDD>.csv"
Cache-Control: no-store
```

CSV columns:

```
id,created_at,entry_type,amount,balance_after,reserved_after,ref_type,ref_id,description,sl_transaction_id
```

Streaming via Spring `StreamingResponseBody` so a 100k-row export doesn't hold the whole result in memory. Backed by a streaming repository query (`Stream<UserLedgerEntry>` on the JPA repository, closed in a try-with-resources block).

Server enforces a per-user export rate limit (1 per 60s) via Redis/in-memory bucket — protects against accidental DOS via repeated downloads.

### 6.2 Frontend filter chrome

New section on the wallet page above the activity table:

```
┌─────────────────────────────────────────────────────────────┐
│  Filter activity                                            │
│                                                             │
│  Type:    [DEPOSIT ✗] [WITHDRAW ✗] [+]  (multi-select chips)│
│  Date:    [from: ___] [to: ___]                             │
│  Amount:  [min: ___] [max: ___]                             │
│                                                             │
│                          [Reset]  [Export CSV ⤓]            │
└─────────────────────────────────────────────────────────────┘
```

- Chips are toggle-able. Clicking a chip toggles inclusion in the `entryType` filter.
- Date pickers use the project's `<DatePicker>` if it exists; otherwise plain `<input type="date">`.
- Amount inputs accept positive integers; debounced 300ms on change.
- `Reset` clears all filters and refetches.
- `Export CSV` calls the export endpoint and triggers a browser download via temporary `<a>` tag with `download` attribute.

Filter state stored in URL search params (`useSearchParams`/`router.replace`) so filters survive refresh and are shareable.

### 6.3 Pagination chrome

Standard prev/next + page-size dropdown. Uses existing `<Pagination>` component if present in `@/components/ui`. Total count + current page indicator.

---

## §7 — WebSocket live updates

### 7.1 Backend publish

New envelope:

```java
package com.slparcelauctions.backend.wallet.broadcast;

public record WalletBalanceChangedEnvelope(
    long balance,
    long reserved,
    long available,
    long penaltyOwed,
    String reason,           // "DEPOSIT", "WITHDRAW_QUEUED", etc.
    Long ledgerEntryId,      // pointer to the entry that triggered the publish
    OffsetDateTime occurredAt
) {
    public static WalletBalanceChangedEnvelope of(User u, String reason, Long ledgerEntryId, OffsetDateTime occurredAt) {
        long owed = u.getPenaltyBalanceOwed() == null ? 0L : u.getPenaltyBalanceOwed();
        return new WalletBalanceChangedEnvelope(
            u.getBalanceLindens(), u.getReservedLindens(), u.availableLindens(),
            owed, reason, ledgerEntryId, occurredAt);
    }
}
```

New publisher service `WalletBroadcastPublisher` that wraps Spring's `SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/wallet", envelope)` and registers an `afterCommit` synchronization so subscribers never observe an uncommitted state.

### 7.2 Hook into mutations

Each `WalletService` method that ends in a commit also calls `walletBroadcastPublisher.publish(user, reason, ledgerEntry.getId())` after the ledger row is saved. Reason strings match the `UserLedgerEntryType` of the row that triggered the publish — `DEPOSIT`, `WITHDRAW_QUEUED`, `WITHDRAW_COMPLETED`, `WITHDRAW_REVERSED`, `BID_RESERVED`, `BID_RELEASED`, `ESCROW_DEBIT`, `ESCROW_REFUND`, `LISTING_FEE_DEBIT`, `LISTING_FEE_REFUND`, `PENALTY_DEBIT`, `ADJUSTMENT`.

For `swapReservation` (writes two ledger rows, one per affected user), `releaseReservationsForAuction` and `releaseAllReservationsForUser` (write per-affected-user rows in a loop): publish per affected user. Each user only receives envelopes for their own balance changes.

For `autoFundEscrow` (writes BID_RELEASED + ESCROW_DEBIT for the same user): publish once with reason `ESCROW_DEBIT` — the higher-information event — referencing the ESCROW_DEBIT ledger row id.

### 7.3 Frontend subscription

`useWalletWsSubscription()` hook in `frontend/src/lib/wallet/use-wallet-ws.ts`:

```tsx
export function useWalletWsSubscription(enabled: boolean) {
  const queryClient = useQueryClient();
  const stompClient = useStompClient();

  useEffect(() => {
    if (!enabled || !stompClient) return;
    const sub = stompClient.subscribe("/user/queue/wallet", (frame) => {
      const env = JSON.parse(frame.body) as WalletBalanceChangedEnvelope;
      queryClient.setQueryData<WalletView>(["me","wallet"], (prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          balance: env.balance,
          reserved: env.reserved,
          available: env.available,
          penaltyOwed: env.penaltyOwed,
        };
      });
      // Invalidate ledger so the activity table picks up the new row
      queryClient.invalidateQueries({ queryKey: ["me","wallet","ledger"] });
    });
    return () => sub.unsubscribe();
  }, [enabled, stompClient, queryClient]);
}
```

Used by:
- `<HeaderWalletIndicator>` (verified-gated).
- `<WalletPanel>` (already inside the verified `/wallet` page).

Polling stays in place (30s + on-focus) as a fallback.

---

## §8 — Migration / rollout

### 8.1 Sequencing

```
1. Backend: add WalletBroadcastPublisher + hook into WalletService methods
2. Backend: extend GET /me/wallet/ledger with filters + pagination
3. Backend: add GET /me/wallet/ledger/export.csv
4. Frontend: add /wallet route, header indicator, mobile menu row
5. Frontend: WalletPanel beautify pass
6. Frontend: ledger filter chrome + export button + pagination
7. Frontend: WS subscription wiring
8. Remove /dashboard/(verified)/wallet page + tab entry
9. Tests
```

All additive on the backend except step 8 (which is a frontend file delete). No schema changes. No retired endpoints.

### 8.2 Test surface

- Unit: `WalletBroadcastPublisher` afterCommit registration; one publish per mutation point.
- Slice: `GET /me/wallet/ledger` with each filter dimension and pagination correctness.
- Slice: `GET /me/wallet/ledger/export.csv` returns CSV with right Content-Type, Content-Disposition, and rows; rate limit kicks in on second request inside 60s.
- Integration: deposit → WS envelope received in test STOMP client → balance reflects updated value.
- Integration: bid → reservation → WS envelopes for both the new and prior bidder.
- Frontend: `<HeaderWalletIndicator>` renders L$ 0 on first render, then real value once query resolves; popover content matches the wallet view.
- Frontend: `<WalletPanel>` ledger filter chrome — applying type filter narrows results, applying date range narrows, applying amount range narrows; filters persist via URL params.
- Frontend: CSV export click triggers a download with the right filename.
- Frontend: WS envelope received → cache updates → indicator + panel re-render.
- Existing `npm run verify` guards (no dark variants, no hex colors, no inline styles) all pass.

---

## §9 — Files touched (estimate)

**Backend:**
- New: `wallet/broadcast/WalletBalanceChangedEnvelope.java`, `wallet/broadcast/WalletBroadcastPublisher.java`.
- Modified: `wallet/WalletService.java` (publish hooks on every mutation method).
- Modified: `wallet/UserLedgerRepository.java` (filter spec / pageable query / streaming query).
- Modified: `wallet/me/MeWalletController.java` (extended ledger endpoint + export endpoint).
- Modified: `wallet/me/WalletViewResponse.java` (no shape change; recentLedger remains the first page).
- New: `wallet/me/LedgerFilter.java` (request DTO for the filter params).
- New: `wallet/me/LedgerCsvWriter.java` (StreamingResponseBody body).
- Tests: ~6 new test files.

**Frontend:**
- New: `app/wallet/page.tsx`.
- Removed: `app/dashboard/(verified)/wallet/page.tsx`.
- Modified: `app/dashboard/(verified)/layout.tsx` (drop Wallet tab).
- Modified: `components/layout/Header.tsx` (NavLink + indicator placement).
- Modified: `components/layout/MobileMenu.tsx` (Wallet row).
- New: `components/wallet/HeaderWalletIndicator.tsx`.
- New: `components/wallet/HoverPopover.tsx` (or inline in indicator).
- Modified: `components/wallet/WalletPanel.tsx` (beautify + filter chrome integration + CSV button + pagination).
- New: `components/wallet/LedgerFilterBar.tsx`.
- New: `components/wallet/LedgerTable.tsx` (extracted from current WalletPanel inline).
- New: `components/ui/Modal.tsx` if no shared one exists yet.
- Modified: `components/ui/icons.ts` (add lucide imports for the new ledger row icons).
- Modified: `lib/api/wallet.ts` (typed ledger query + export).
- Modified: `types/wallet.ts` (LedgerFilter type).
- New: `lib/wallet/use-wallet-ws.ts` (WS subscription hook).
- Tests: ~5 new test files.

---

## §10 — Open implementation choices

These are not blocked on user input; recording the call so the implementation is consistent:

1. **Modal component:** if no shared `<Modal>` exists in `@/components/ui/`, build one along the lines of standard Material-3 dialog chrome (scrim, container, focus trap on open via `react-aria` or hand-rolled, Escape to close). Used by all wallet dialogs.
2. **Pagination component:** use existing `<Pagination>` if present; otherwise build a thin `prev / page-N-of-M / next + size dropdown` component co-located with the wallet ledger table.
3. **CSV export rate limit:** 1 per 60s per user. In-memory bucket keyed on user id for the first cut; can be moved to Redis if/when this gets hammered.
4. **`?penalty=open` cleanup:** `router.replace("/wallet")` on dialog open so the param is removed and refresh doesn't re-trigger.
5. **WS subscriber lifetime:** subscribe in `<HeaderWalletIndicator>` for verified users; the wallet page subscribes via the same hook — both subscribers share the React Query cache so no double-handling. The hook is no-op when `enabled === false`.
