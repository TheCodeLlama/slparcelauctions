# Epic 05 sub-spec 2 — Escrow Frontend

**Date:** 2026-04-22
**Branch target:** `task/05-sub-2-escrow-frontend` off `dev`
**Scope:** Frontend surface for the Epic 05 sub-spec 1 backend escrow engine. Escrow status page at `/auction/[id]/escrow`, dispute subroute at `/auction/[id]/escrow/dispute`, dashboard row escrow chip on `MyBidSummaryRow` + `ListingSummaryRow`, escrow banner + CTA on `AuctionEndedPanel`. Plus retirement of the defensive `inferEndOutcome` / `inferOutcomeFromDto` fallback helpers that are no longer needed.

Backend (Epic 05 sub-spec 1) shipped all 9 WebSocket envelope types on `/topic/auction/{id}` plus the `GET /api/v1/auctions/{id}/escrow` + `POST /api/v1/auctions/{id}/escrow/dispute` endpoints. Sub-spec 2 consumes them.

---

## §1 — Scope

**In scope:**

- Server-rendered escrow status page at `/auction/[id]/escrow` (seller + winner only; server-side auth gate redirects others to `/auction/[id]`)
- Dispute full-page form at `/auction/[id]/escrow/dispute` (same auth gate; form-level state gate on the client)
- `EscrowChip` primitive reused across three surfaces (escrow page, dashboard rows, `AuctionEndedPanel`)
- `EscrowStepper` 3-node progress tracker with completed-stage timestamps
- `EscrowStepCard` state dispatcher with 6 per-state card variants (role-aware seller/winner content)
- `EscrowStatusResponse` DTO + `EscrowEnvelope` union added to frontend types
- `escrow.ts` API client (`getEscrowStatus`, `fileDispute`)
- `MyBidSummaryRow` + `ListingSummaryRow` gain the secondary chip + "View escrow →" link when `escrowState` is present
- `AuctionEndedPanel` gains the compact inline banner + "View escrow" CTA on `SOLD` / `BOUGHT_NOW` outcomes when the viewer is the seller or winner
- WS envelope handler extension: all 9 escrow envelope types route through cache invalidation on `["escrow", auctionId]` (+ `["auction", auctionId]` for the auction detail page so the banner's `escrowState` stays fresh)
- `inferEndOutcome` / `inferOutcomeFromDto` fallback helpers retired from `AuctionEndedPanel.tsx` + `ListingSummaryRow.tsx` — backend always projects `endOutcome` post sub-spec 1; defensive fallback is dead code
- DEFERRED_WORK openers: dispute evidence attachments, `PAYMENT_NOT_CREDITED` dispute reconciliation

**Out of scope (explicit):**

- `FeePaymentInstructions` — no changes. Existing copy is terminal-agnostic; the terminal address is not hardcoded there. When a future spec deploys a real in-world listing-fee terminal address, a targeted copy update on that component will ship separately.
- Shared frontend fixture seeder — cross-epic polish, noted in DEFERRED_WORK. Not blocking.
- Notifications / email fan-out on escrow transitions — Epic 09
- Dispute evidence attachments (file uploads, SL transaction keys) — Epic 10 alongside admin resolution tooling
- Admin resolution UI for DISPUTED / FROZEN escrows — Epic 10
- Terminal locator / map link on `PAY ESCROW` state — deferred; inert placeholder href for now

---

## §2 — Architecture

### 2.1 Routing

```
app/auction/[id]/
├── page.tsx                       (unchanged — parent auction detail page)
├── AuctionDetailClient.tsx        (modified — add escrow envelope branch in handler)
└── escrow/
    ├── page.tsx                   NEW — RSC shell; auth gate only
    ├── EscrowPageClient.tsx       NEW — React Query + WS subscription
    └── dispute/
        ├── page.tsx               NEW — RSC shell; auth gate only
        └── DisputeFormClient.tsx  NEW — React Hook Form + Zod
```

- Both RSC shells use Next 16's `{ params: Promise<{ id: string }> }` shape (matches existing `app/auction/[id]/page.tsx`).
- RSC shells perform **session-based auth gate only**: anonymous → `/login?returnTo=...`, non-seller-non-winner → `/auction/[id]`. No data fetching beyond the auction DTO for the auth check.
- Client components own all data fetching via React Query.

### 2.2 Component tree

```
frontend/src/components/escrow/
├── EscrowStepper.tsx          3-node progress tracker with completed timestamps
├── EscrowStepCard.tsx         state dispatcher (reads state, renders the right card)
├── EscrowChip.tsx             primitive reused by 3 surfaces
├── EscrowDeadlineBadge.tsx    countdown + urgency coloring (uses existing useServerTimeOffset)
├── EscrowPageLayout.tsx       shared page chrome (header, back-to-auction link)
├── EscrowPageHeader.tsx       parcel summary + role label
├── EscrowPageSkeleton.tsx     loading state
├── EscrowPageError.tsx        error state
├── EscrowPageEmpty.tsx        404 / "no escrow for this auction yet"
└── state/
    ├── PendingStateCard.tsx          ESCROW_PENDING × {seller, winner}
    ├── TransferPendingStateCard.tsx  TRANSFER_PENDING × {seller, winner}, sub-split by transferConfirmedAt
    ├── CompletedStateCard.tsx
    ├── DisputedStateCard.tsx
    ├── FrozenStateCard.tsx
    └── ExpiredStateCard.tsx          branches on fundedAt (pre-fund vs post-fund)
```

### 2.3 Data flow

```
RSC auth gate → EscrowPageClient
                 ├── useQuery(["escrow", auctionId], getEscrowStatus)
                 ├── useStompSubscription<AuctionTopicEnvelope>(`/topic/auction/${id}`, handler)
                 │     └── ESCROW_* envelopes → invalidateQueries(["escrow", auctionId])
                 │         BID_SETTLEMENT / AUCTION_ENDED → ignored (not this page's concern)
                 └── useEffect on connected edge → invalidateQueries(["escrow", auctionId])
```

Single query key: `["escrow", auctionId]`. No envelope-to-DTO merging per §8 of sub-spec 1 ("coarse, cache-invalidation-flavored") — the GET endpoint is the source of truth for state + timeline + deadline stamps.

### 2.4 Cache strategy

| Query key | When invalidated |
|-----------|------------------|
| `["escrow", auctionId]` | Any `ESCROW_*` envelope; reconnect edge; dispute submit success |
| `["auction", auctionId]` | Any `ESCROW_*` envelope (so `escrowState` on the auction DTO stays fresh for the `AuctionEndedPanel` banner); existing bid/end invalidations unchanged |
| `["myBids"]` / `["myListings"]` | NOT invalidated on escrow envelope. Uses existing `refetchOnWindowFocus: true`. Dashboard rows lag live state by up to ~30s on a stale tab — acceptable since the user must be actively viewing the dashboard to see the rows at all. Implementation-time escape hatch noted: a named cross-page eventbus signal is available if this lag feels too sluggish in practice. |

---

## §3 — Component specs

### 3.1 `EscrowStepper`

3-node horizontal stepper. Extends the existing `frontend/src/components/ui/Stepper.tsx` primitive to support completed-stage timestamps under the step label + terminal-state interrupt rendering.

**Nodes:**

| Node | Label | Active when | Complete when (timestamp source) |
|------|-------|-------------|----------------------------------|
| 1 | Payment | `state === "ESCROW_PENDING"` | `fundedAt` |
| 2 | Transfer | `state ∈ {"FUNDED", "TRANSFER_PENDING"}` AND `transferConfirmedAt == null` | `transferConfirmedAt` |
| 3 | Complete | `transferConfirmedAt != null` AND `completedAt == null` | `completedAt` |

The brief window between `transferConfirmedAt` and `completedAt` (payout dispatch + callback — typically sub-second wall-clock) is where node 3 is "active." Users who refresh during this window see node 3 highlighted; it's rare by design.

**Terminal non-happy states** (`DISPUTED` / `EXPIRED` / `FROZEN`): completed nodes up to the last-stamped one render as checked with timestamps. The tail collapses into a single terminal-state interrupt node with the relevant stamp (`disputedAt` / `expiredAt` / `frozenAt`) and the state label in problem/muted tone. Example:

```
[✓ Payment 2:14p] ── [⚠ FROZEN 4:30p]        (node 3 hidden)
[✓ Payment 2:14p] ── [✓ Transfer 4:30p] ── [⚠ DISPUTED 5:15p]
```

Props:

```tsx
type EscrowStepperProps = {
  escrow: EscrowStatusResponse;
};
```

Pulls all stamps + state from the DTO. No internal state.

### 3.2 `EscrowStepCard` dispatcher

```tsx
function EscrowStepCard({ escrow, role }: { escrow: EscrowStatusResponse; role: "seller" | "winner" }) {
  switch (escrow.state) {
    case "ESCROW_PENDING":     return <PendingStateCard escrow={escrow} role={role} />;
    case "FUNDED":
    case "TRANSFER_PENDING":   return <TransferPendingStateCard escrow={escrow} role={role} />;
    case "COMPLETED":          return <CompletedStateCard escrow={escrow} role={role} />;
    case "DISPUTED":           return <DisputedStateCard escrow={escrow} role={role} />;
    case "FROZEN":             return <FrozenStateCard escrow={escrow} role={role} />;
    case "EXPIRED":            return <ExpiredStateCard escrow={escrow} role={role} />;
  }
}
```

The `FUNDED` case is defensive; sub-spec 1's atomic transition means this state is effectively unobservable externally, but the switch handles it gracefully by routing to `TransferPendingStateCard`.

### 3.3 Per-state card content

Each card takes `(escrow, role)`. Copy table:

| State | Seller view | Winner view |
|-------|-------------|-------------|
| **ESCROW_PENDING** | "Awaiting payment from `{counterparty.displayName}`. Payment deadline `{paymentDeadline}`. If they don't pay you'll be able to re-list once the escrow expires." + `<DisputeLinkButton />` + `<EscrowDeadlineBadge deadline={paymentDeadline} />` | "Pay L$ `{finalBidAmount}` at an SLPA terminal in-world. Your winning bid needs to land by `{paymentDeadline}`." + inert "Find a terminal" link (noted as DEFERRED_WORK — terminal locator feature) + `<DisputeLinkButton />` + `<EscrowDeadlineBadge />` |
| **TRANSFER_PENDING** (`transferConfirmedAt == null`) | Numbered 5-step SL-viewer recipe: (1) Right-click parcel → About Land, (2) Click Sell Land, (3) Set "Sell to:" to `{counterparty.slAvatarName}`, (4) Set price L$0, (5) Confirm. "Copy winner name" button copies `counterparty.slAvatarName` to clipboard. + `<DisputeLinkButton />` + `<EscrowDeadlineBadge deadline={transferDeadline} />` | "Waiting for seller to transfer the parcel. Typical completion is under 24 hours. You'll see this flip to Complete automatically within 5 min of the transfer." + guidance card listing "what you can do" thresholds (Wait / Message seller if stalled >24h / Dispute if >48h). + "Message `{counterparty.displayName}`" button (inert placeholder — no real messaging yet) + `<DisputeLinkButton />` + `<EscrowDeadlineBadge />` |
| **TRANSFER_PENDING** (`transferConfirmedAt != null`) | Both see: "Ownership transferred to the winner at `{transferConfirmedAt}`. Finalizing the transaction — payout is dispatching now." No CTAs. No dispute link (per sub-spec 1, dispute from TRANSFER_PENDING is still allowed source-state-wise, but the UX framing avoids inviting it at a point where the backend is about to flip to COMPLETED). | Same copy both roles. |
| **COMPLETED** | "Payout of L$ `{payoutAmt}` sent. Commission L$ `{commissionAmt}`. Completed `{completedAt}`." Optional link to a "review the buyer" action (Epic 06 scope — inert placeholder or omit entirely if the /reviews surface doesn't exist yet). | "Parcel transferred. You're the owner of `{parcelName}` in `{region}`. Completed `{completedAt}`." Link to `/parcels/{parcelId}` if that route exists; otherwise just the confirmation copy. |
| **DISPUTED** | "Dispute filed `{disputedAt}`. Category: `{disputeReasonCategory}`. Reason: `{disputeDescription}`. SLPA is reviewing this transaction. Expect a response within 48 hours." (Deliberately vague since admin tooling is Epic 10.) Both roles see the same copy. | Same copy. |
| **FROZEN** | "Escrow frozen `{frozenAt}`: `{freezeReason}`. Your L$ will be refunded automatically. SLPA has flagged this auction for review." Copy softens on `WORLD_API_PERSISTENT_FAILURE`: "We couldn't verify parcel ownership repeatedly; this is likely a transient issue and SLPA will re-check manually." Both roles see the same copy. | Same copy. |
| **EXPIRED** (`fundedAt == null`) | Payment-timeout branch: "Escrow expired because the winner didn't pay by the deadline." | Blunt branch: "You didn't pay by the deadline. The auction has expired." (Sub-spec 3 can soften the tone; not blocking.) |
| **EXPIRED** (`fundedAt != null`) | Transfer-timeout branch: "Escrow expired because the transfer wasn't completed by the deadline. Refund of L$ `{finalBidAmount}` has been queued to the winner." | "Seller didn't complete the transfer. Your L$ `{finalBidAmount}` refund has been queued and should land in your SL wallet shortly." |

**`EXPIRED` branching note for implementer:** the backend sends a single `EXPIRED` state; the frontend branches on `fundedAt` (nullable) to decide which copy to show. If `fundedAt === null` it's a pre-fund payment timeout (no refund). If `fundedAt !== null` it's a post-fund transfer timeout (refund queued). `EscrowStatusResponse` always includes `fundedAt` (nullable) per sub-spec 1 §3.1.

### 3.4 `EscrowChip` primitive

```tsx
type EscrowChipProps = {
  state: EscrowState;
  transferConfirmedAt?: string | null;
  role?: "seller" | "winner";
  size?: "sm" | "md";
};
```

State → label + tone:

| State | Winner label | Seller label | Tone |
|-------|--------------|--------------|------|
| `ESCROW_PENDING` | `PAY ESCROW` | `AWAITING PAYMENT` | `action` (gold) |
| `TRANSFER_PENDING`, `transferConfirmedAt == null` | `AWAITING TRANSFER` | `TRANSFER LAND` | `action` (seller) / `waiting` (winner) |
| `TRANSFER_PENDING`, `transferConfirmedAt != null` | `PAYOUT PENDING` | `PAYOUT PENDING` | `waiting` |
| `COMPLETED` | `COMPLETED` | `COMPLETED` | `done` (green) |
| `DISPUTED` | `DISPUTED` | `DISPUTED` | `problem` (red) |
| `FROZEN` | `FROZEN` | `FROZEN` | `problem` (red) |
| `EXPIRED` | `EXPIRED` | `EXPIRED` | `muted` (gray) |

Tones reuse existing status-chip palette tokens from the design system (no new Tailwind colors).

**Prop contract note:** `transferConfirmedAt` is needed to disambiguate the two sub-phases of `TRANSFER_PENDING`. Callers that only have `state` can omit it — the chip falls back to the role-aware `AWAITING TRANSFER` / `TRANSFER LAND` label. Dashboard rows and the escrow page always have access to `transferConfirmedAt` via their DTOs, so the split renders correctly on those surfaces. If `role` is also omitted, labels fall back to role-neutral (`ESCROW PENDING`, `TRANSFER PENDING`, etc.) — used by future admin contexts.

### 3.5 `EscrowDeadlineBadge`

Extracted from the existing countdown pattern used on the auction detail page (`useServerTimeOffset`). Props:

```tsx
type EscrowDeadlineBadgeProps = {
  deadline: string;  // ISO 8601
  label?: string;    // defaults to "Left"
};
```

Urgency coloring:
- `> 24h` left: neutral text
- `6h - 24h` left: gold (warning)
- `< 6h` left: red (urgent)
- Past deadline: red with strikethrough (should not happen if timeout job is healthy; defensive)

---

## §4 — Dashboard integration

### 4.1 `MyBidSummaryRow`

File: `frontend/src/components/bids/MyBidSummaryRow.tsx` (modified).

`MyBidSummary.auction` gains `escrowState?: EscrowState` + `transferConfirmedAt?: string | null`. Both nullable — only populated when the auction has an escrow row.

When `escrowState != null`:
- Render `<EscrowChip state={escrowState} transferConfirmedAt={transferConfirmedAt} role="winner" />` next to the existing bid-status chip.
- Right-side action link changes to "View escrow →" pointing at `/auction/{auctionId}/escrow`.

Left-border urgency encoding: unchanged (keeps existing bid-status urgency). Two parallel signals coexist.

### 4.2 `ListingSummaryRow`

File: `frontend/src/components/listing/ListingSummaryRow.tsx` (modified).

Mirror treatment. `ActiveListingAuctionSummary` gains `escrowState?: EscrowState` + `transferConfirmedAt?: string | null`.

When `escrowState != null`:
- Render `<EscrowChip state={escrowState} transferConfirmedAt={transferConfirmedAt} role="seller" />` next to the listing-status chip.
- Action column: "View escrow →".

### 4.3 Backend DTO enrichment requirement

Sub-spec 2 assumes the backend enriches these existing DTOs with two new nullable fields. If sub-spec 1 shipped `escrowState` enrichment (per its §10 for dashboard rows) but missed `transferConfirmedAt`, sub-spec 2 Task 1 extends the backend enrichment to include both fields. Confirm during implementation:

- `MyBidSummary.auction` — contains `escrowState` + `transferConfirmedAt` (nullable)
- `ActiveListingAuctionSummary` — contains `escrowState` + `transferConfirmedAt` (nullable)
- `PublicAuctionResponse` + `SellerAuctionResponse` (consumed by `/auction/[id]`) — contain `escrowState` + `transferConfirmedAt` (nullable) for the `AuctionEndedPanel` banner

If any of these are missing, sub-spec 2's first task is a backend touch to add them. Small change; same enrichment pattern sub-spec 1 already established.

### 4.4 Retire `inferEndOutcome` / `inferOutcomeFromDto` fallback helpers

Delete:
- `inferOutcomeFromDto` function at `frontend/src/components/auction/AuctionEndedPanel.tsx:295-321`
- `inferEndOutcome` function at `frontend/src/components/listing/ListingSummaryRow.tsx:277-289`

Replace call sites:

```tsx
// before
const outcome = ended.endOutcome ?? inferOutcomeFromDto(auction, ended);

// after
const outcome = ended.endOutcome;
// Backend always projects endOutcome post Epic 05 sub-spec 1 (ENDED
// auctions always carry SOLD / BOUGHT_NOW / RESERVE_NOT_MET / NO_BIDS).
// If this ever fails a runtime type-guard, it's a backend invariant
// violation — let it surface.
```

Remove the corresponding TypeScript type-narrowing that accommodated the `null` possibility on `endOutcome`. Tests that exercised the fallback behavior specifically are deleted; tests that used the fallback transparently now depend on explicit `endOutcome` in their fixtures (trivial update).

---

## §5 — AuctionEndedPanel escrow banner

File: `frontend/src/components/auction/AuctionEndedPanel.tsx` (modified).

### 5.1 New subcomponent `EscrowBannerForPanel`

Renders only when all four conditions hold:
- `auction.status === "ENDED"`
- `auction.endOutcome ∈ {"SOLD", "BOUGHT_NOW"}` (only outcomes that produce an escrow)
- `currentUser` is seller or winner (public viewer sees only the outcome headline, unchanged)
- `auction.escrowState != null` (defensive — if the enrichment hasn't caught up the banner just doesn't render)

### 5.2 Banner copy table

| State | Role | Headline | Detail |
|-------|------|----------|--------|
| `ESCROW_PENDING` | winner | `Pay escrow` | "at an SLPA terminal in-world. `{countdown}` left." |
| `ESCROW_PENDING` | seller | `Escrow pending` | "waiting for buyer to pay. `{countdown}` left." |
| `TRANSFER_PENDING`, `transferConfirmedAt == null` | winner | `Awaiting transfer` | "seller is transferring the parcel." |
| `TRANSFER_PENDING`, `transferConfirmedAt == null` | seller | `Transfer parcel` | "set the land for sale to `{winner}` at L$0. `{countdown}` left." |
| `TRANSFER_PENDING`, `transferConfirmedAt != null` | both | `Payout pending` | "finalizing the transaction." |
| `COMPLETED` | both | `Escrow complete` | (headline alone) |
| `DISPUTED` | both | `Escrow disputed` | "SLPA staff is reviewing." |
| `FROZEN` | both | `Escrow frozen` | "SLPA staff is investigating." |
| `EXPIRED` | both | `Escrow expired` | (full detail on the escrow page; banner keeps it short) |

### 5.3 Banner shape

```tsx
<div className={`mt-3 flex items-center gap-2 rounded-md p-2 ${toneClasses[tone]}`}>
  <span className="flex-1 text-sm">
    <strong>{copy.headline}</strong> {copy.detail}
  </span>
  <Link href={`/auction/${auction.id}/escrow`} className="btn btn-primary btn-sm">
    View escrow
  </Link>
</div>
```

### 5.4 Drop the stub seller-overlay line

`AuctionEndedPanel.tsx`'s existing seller overlay has a stub line "Escrow flow opens in Epic 05." Remove it — the new banner carries real state + CTA.

---

## §6 — Dispute subroute

### 6.1 Routing

- RSC shell `app/auction/[id]/escrow/dispute/page.tsx` performs auth gate only (seller or winner). Redirects anonymous to login, non-parties to `/auction/[id]`.
- Client `DisputeFormClient.tsx` reads `useQuery(["escrow", auctionId])` from cache (populated by prior escrow-page visit; otherwise triggers the fetch).

### 6.2 Client state branches

1. **Loading** → skeleton
2. **404 from API** (no escrow row exists) → "No escrow exists for this auction" panel + back link
3. **Non-disputable state** (`state ∈ {COMPLETED, EXPIRED, DISPUTED, FROZEN}`) → context-specific "can no longer be disputed" panel:
   - `DISPUTED`: "A dispute was filed on `{disputedAt}`. SLPA is reviewing."
   - `COMPLETED`: "Escrow completed on `{completedAt}`. If you have a concern, contact support." (Support routing is Epic 10; sub-spec 2 uses an inert mailto placeholder or `/support` link if that route exists — neither is blocking.)
   - `EXPIRED` / `FROZEN`: "This escrow is in a `{state}` state and is no longer active."
   - Always shows `<Link href={`/auction/${auctionId}/escrow`}>← Back to escrow</Link>`
4. **Disputable** (`state ∈ {ESCROW_PENDING, FUNDED, TRANSFER_PENDING}`) → the form

### 6.3 Form

React Hook Form + Zod. Schema mirrors backend `EscrowDisputeRequest`:

```tsx
const disputeSchema = z.object({
  reasonCategory: z.enum([
    "SELLER_NOT_RESPONSIVE",
    "WRONG_PARCEL_TRANSFERRED",
    "PAYMENT_NOT_CREDITED",
    "FRAUD_SUSPECTED",
    "OTHER",
  ]),
  description: z.string()
    .min(10, "Please describe the issue (at least 10 characters)")
    .max(2000, "Description is too long (max 2000 characters)"),
});
```

User-facing option labels on the `reasonCategory` select:
- `SELLER_NOT_RESPONSIVE`: "Seller isn't responding"
- `WRONG_PARCEL_TRANSFERRED`: "Wrong parcel transferred to me"
- `PAYMENT_NOT_CREDITED`: "I paid but the escrow didn't move to funded"
- `FRAUD_SUSPECTED`: "I suspect fraud"
- `OTHER`: "Other / something else"

Form components:
- Read-only context block above the form summarizing the escrow ("You're disputing escrow for `{parcelName}` — currently `{state}`, you are the `{role}`")
- Select dropdown for `reasonCategory`
- Textarea for `description` with live character count "X / 2000"
- Submit button + Cancel link

### 6.4 Submit handler

```tsx
const mutation = useMutation({
  mutationFn: (body: EscrowDisputeRequest) => fileDispute(auctionId, body),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ["escrow", auctionId] });
    toast.success("Dispute filed. SLPA staff will review.");
    router.push(`/auction/${auctionId}/escrow`);
  },
  onError: (err) => {
    if (err.code === "ESCROW_INVALID_TRANSITION") {
      // State changed between form load and submit (e.g., fraud-freeze fired).
      toast.error("This escrow's state changed while you were filing. Please review.");
      router.push(`/auction/${auctionId}/escrow`);
    } else if (err.code === "ESCROW_FORBIDDEN") {
      toast.error("You don't have permission to dispute this escrow.");
      router.push(`/auction/${auctionId}`);
    } else {
      toast.error("Failed to file dispute: " + err.message);
      // Leave form in place so the user can retry.
    }
  },
});
```

The 409 `ESCROW_INVALID_TRANSITION` path is load-bearing: a concurrent system transition (fraud-freeze, timeout) could invalidate the dispute mid-submit. Backend's 409 is authoritative.

### 6.5 Double-pay risk on `PAYMENT_NOT_CREDITED` (spec callout)

This reason category specifically claims "I paid but the escrow didn't advance." Frontend submits it like any other category — handling is entirely server-side. A naive backend implementation would flip `ESCROW_PENDING → DISPUTED` and queue a refund. The risk: if the winner actually paid (L$ already in the SLPAEscrow SL account) and the escrow state didn't flip only because of a transient backend or terminal outage, a later successful `POST /api/v1/sl/escrow/payment` retry (idempotent via `slTransactionKey`) could find the escrow in `DISPUTED` — the happy-path completion is now blocked, but the L$ is already held. If admin tooling then triggers a refund on the disputed escrow without reconciling against the SLPAEscrow balance, the winner ends up double-paid.

Sub-spec 2 frontend does nothing special here. The spec flags this for Epic 10 admin tooling so the backend never blindly auto-refunds `PAYMENT_NOT_CREDITED` disputes — these always warrant manual balance reconciliation against the SLPA terminal ledger before any L$ movement. Until Epic 10 lands, `PAYMENT_NOT_CREDITED` disputes transition to `DISPUTED` and sit awaiting manual review like every other category. See §11 DEFERRED_WORK opener.

---

## §7 — WebSocket envelope handling

### 7.1 Types

`frontend/src/types/escrow.ts` (new file):

```tsx
export type EscrowState =
  | "ESCROW_PENDING" | "FUNDED" | "TRANSFER_PENDING"
  | "COMPLETED" | "DISPUTED" | "EXPIRED" | "FROZEN";

export type EscrowEnvelopeType =
  | "ESCROW_CREATED" | "ESCROW_FUNDED" | "ESCROW_TRANSFER_CONFIRMED"
  | "ESCROW_COMPLETED" | "ESCROW_DISPUTED" | "ESCROW_EXPIRED"
  | "ESCROW_FROZEN" | "ESCROW_REFUND_COMPLETED" | "ESCROW_PAYOUT_STALLED";

export interface EscrowEnvelopeBase {
  type: EscrowEnvelopeType;
  auctionId: number;
  escrowId: number;
  state: EscrowState;
  serverTime: string;
}

// Per-variant refinements carry type-specific extra fields (e.g.,
// paymentDeadline on ESCROW_CREATED, transferDeadline on ESCROW_FUNDED,
// reason on ESCROW_DISPUTED/FROZEN). Frontend does NOT read them — the
// envelope is purely a cache-invalidation signal per sub-spec 1 §8.
// Types are defined for forward compatibility (e.g., Epic 09 notifications
// consumer could read them) but sub-spec 2 ignores the extras.

export type EscrowEnvelope =
  | (EscrowEnvelopeBase & { type: "ESCROW_CREATED"; paymentDeadline: string })
  | (EscrowEnvelopeBase & { type: "ESCROW_FUNDED"; transferDeadline: string })
  | (EscrowEnvelopeBase & { type: "ESCROW_TRANSFER_CONFIRMED"; transferConfirmedAt: string })
  | (EscrowEnvelopeBase & { type: "ESCROW_COMPLETED"; completedAt: string })
  | (EscrowEnvelopeBase & { type: "ESCROW_DISPUTED"; reasonCategory: string })
  | (EscrowEnvelopeBase & { type: "ESCROW_EXPIRED"; reason: "PAYMENT_TIMEOUT" | "TRANSFER_TIMEOUT" })
  | (EscrowEnvelopeBase & { type: "ESCROW_FROZEN"; reason: string })
  | (EscrowEnvelopeBase & { type: "ESCROW_REFUND_COMPLETED"; refundAmount: number })
  | (EscrowEnvelopeBase & { type: "ESCROW_PAYOUT_STALLED"; attemptCount: number; lastError?: string });
```

Merged topic union at `frontend/src/types/auction.ts` (modified):

```tsx
export type AuctionTopicEnvelope = BidSettlementEnvelope | AuctionEndedEnvelope | EscrowEnvelope;
```

### 7.2 Handlers

**Auction detail page** (`AuctionDetailClient.tsx`, modified):

```tsx
useStompSubscription<AuctionTopicEnvelope>(
  `/topic/auction/${auctionId}`,
  useCallback((env) => {
    if (env.type === "BID_SETTLEMENT") { /* existing — unchanged */ }
    else if (env.type === "AUCTION_ENDED") { /* existing — unchanged */ }
    else if (env.type.startsWith("ESCROW_")) {
      queryClient.invalidateQueries({ queryKey: ["escrow", auctionId] });
      queryClient.invalidateQueries({ queryKey: ["auction", auctionId] });
    }
  }, [auctionId, queryClient])
);
```

**Escrow page** (`EscrowPageClient.tsx`, new):

```tsx
useStompSubscription<AuctionTopicEnvelope>(
  `/topic/auction/${auctionId}`,
  useCallback((env) => {
    if (env.type.startsWith("ESCROW_")) {
      queryClient.invalidateQueries({ queryKey: ["escrow", auctionId] });
    }
    // BID_SETTLEMENT / AUCTION_ENDED arrive on the same topic but are
    // irrelevant on the escrow page — filtered out by the type guard.
  }, [auctionId, queryClient])
);
```

Use the merged `AuctionTopicEnvelope` type on both pages for strict type safety (the STOMP frames include all types on the topic).

### 7.3 Reconnect reconcile

```tsx
const connected = useStompConnectionState();
useEffect(() => {
  if (connected === "connected") {
    queryClient.invalidateQueries({ queryKey: ["escrow", auctionId] });
  }
}, [connected, auctionId, queryClient]);
```

Mirror of Epic 04 sub-2's pattern. `ReconnectingBanner` reused unchanged on the escrow page.

### 7.4 Dashboard row freshness

Dashboard lists (`MyBidsTab`, `MyListingsTab`) do NOT subscribe to topics themselves. Row `escrowState` freshness relies on the existing `refetchOnWindowFocus: true` + invalidation on navigation. The dashboard row lags live state by up to ~30s on a stale tab — acceptable per §2.4.

Implementation-time escape hatch: a named cross-page eventbus channel (e.g., `"escrow-state-changed"` via a shared event emitter) could push envelope-triggered invalidations of `["myBids"]` / `["myListings"]` from the escrow handler. Not in scope for sub-spec 2 unless implementation reveals the lag feels wrong.

---

## §8 — Testing strategy

Per CONVENTIONS.md: Vitest + React Testing Library.

### 8.1 Unit tests

- `EscrowChip.test.tsx` — 10 scenarios covering every state × role combination, including the `TRANSFER_PENDING` sub-split on `transferConfirmedAt`. Verify label + tone class.
- `EscrowStepper.test.tsx` — 7 scenarios: each state + `EXPIRED` pre-fund and post-fund. Verify node count, active node, completed timestamps, interrupt rendering.
- `escrowBannerCopy.test.ts` — pure function exhaustive table test over (state, role, transferConfirmedAt, fundedAt).
- `inferEndOutcome retirement` regex tests — confirm no remaining call sites in `AuctionEndedPanel.test.tsx` + `ListingSummaryRow.test.tsx`.

### 8.2 Component-with-mocks

- `EscrowStepCard.test.tsx` — dispatch correctness. Per-state content tests live in the individual state card test files.
- `PendingStateCard`, `TransferPendingStateCard`, `CompletedStateCard`, `DisputedStateCard`, `FrozenStateCard`, `ExpiredStateCard` — one test file each. Assertions: copy per role, CTAs visible, deadline badge shows for active states, dispute link renders only in disputable states, `EXPIRED` branches on `fundedAt`.
- `DisputeFormClient.test.tsx` — Zod validation, all 5 categories selectable, submit calls mutation with correct payload, 409/403/generic error handling.
- `MyBidSummaryRow`, `ListingSummaryRow` — new test cases verifying escrow chip renders when `escrowState` is set, "View escrow →" link replaces default action.
- `AuctionEndedPanel` — new test case verifying escrow banner renders for SOLD/BOUGHT_NOW + seller-or-winner viewer, correct state-based copy. Public viewer sees no banner.

### 8.3 Integration (MSW-backed page-level tests)

- `escrow-page.integration.test.tsx`:
  1. Happy path: render page as winner with `ESCROW_PENDING` escrow → stepper + pending card + PAY ESCROW chip.
  2. Envelope-invalidates-cache: fire `ESCROW_FUNDED` via MSW's STOMP hook → page re-renders `TRANSFER_PENDING`.
  3. Auth gate: current user not seller/winner → server-side redirect to `/auction/[id]` (exercise via Next route handler harness).
  4. Reconnect reconcile: simulate disconnect → reconnect → verify `["escrow"]` invalidation.
- `dispute-page.integration.test.tsx`:
  1. `ESCROW_PENDING` → form visible.
  2. `DISPUTED` → "can no longer be disputed" panel, no form.
  3. Submit valid form → routes to escrow page, success toast, `["escrow"]` invalidated.
  4. Submit with 409 `ESCROW_INVALID_TRANSITION` → error toast, routes to escrow page.

### 8.4 Test infrastructure

- Reuse Epic 04 sub-2's MSW-based STOMP broker.
- Reuse `renderWithProviders` helper (React Query + Toast + Router context).
- New fixture builders in `frontend/src/test/fixtures/escrow.ts`:
  - `fakeEscrow(overrides?)` → fully-populated `EscrowStatusResponse`
  - `fakeEscrowEnvelope(type, overrides?)` → typed envelope for MSW

### 8.5 Coverage expectations

- Every `EscrowState` hit by ≥1 component test (7 states × 2 roles = 14 paths)
- Every `EscrowEnvelopeType` hit by ≥1 integration test (9 types)
- Dispute form: all 5 reason categories + 3 non-disputable branches + 3 error codes

Estimated new tests: ~60-80 frontend tests. Baseline from Epic 04 sub-2 is ~677; expect ~740-760 after sub-spec 2.

---

## §9 — Task breakdown (preview; full plan to follow)

Plan will cover ~8-10 tasks:

1. **Backend DTO enrichment + types foundation** — confirm (or add) `escrowState` + `transferConfirmedAt` on `MyBidSummary.auction`, `ActiveListingAuctionSummary`, `PublicAuctionResponse`, `SellerAuctionResponse`. Add `EscrowStatusResponse`, `EscrowEnvelope` union, merged `AuctionTopicEnvelope` to frontend types. No UI yet.
2. **API client** — `lib/api/escrow.ts` with `getEscrowStatus` + `fileDispute`.
3. **Primitives** — `EscrowChip`, `EscrowDeadlineBadge`, `EscrowStepper` (extend existing `Stepper`). Full unit test coverage.
4. **Per-state cards** — all 6 state cards + `EscrowStepCard` dispatcher. Per-state test files.
5. **Escrow page shell** — RSC auth gate + `EscrowPageClient` + WS subscription + cache invalidation. Integration test.
6. **Dispute subroute** — RSC auth gate + `DisputeFormClient` + form submit mutation. Integration test.
7. **Dashboard integration** — `MyBidSummaryRow` + `ListingSummaryRow` gain the chip. Tests.
8. **`AuctionEndedPanel` banner** — new subcomponent + copy table + DTO wiring. Tests. Drop stub seller-overlay line.
9. **WS handler extension** — `AuctionDetailClient` gains escrow envelope branch. Integration test.
10. **Cleanup + docs sweep** — retire `inferEndOutcome` / `inferOutcomeFromDto` helpers, DEFERRED_WORK closures + openers (§11), FOOTGUNS entries, README sweep.

---

## §10 — DEFERRED_WORK closures (on merge)

Remove these entries from `docs/implementation/DEFERRED_WORK.md`:

- **"Ended-auction escrow flow UI"** (from Epic 04 sub-spec 2) — shipped by the `AuctionEndedPanel` banner + CTA + escrow page destination.
- **"AuctionEndedPanel / ListingSummaryRow enrichment DTO field nullability"** (from Epic 04 sub-spec 2) — `endOutcome` fallback helpers retired in §4.4; backend always projects the field.
- **"AuctionEndedPanel / My Bids / My Listings escrow CTAs"** (from Epic 05 sub-spec 1) — shipped as the banner, the chip integrations, and the "View escrow →" dashboard links.

Update entries (reword — do not remove):

- **"Per-user public listings page `/users/{id}/listings`"** — unchanged scope; flag still points at Epic 07.

---

## §11 — DEFERRED_WORK openers (on merge)

Add these entries to `docs/implementation/DEFERRED_WORK.md`:

### Dispute evidence attachments

- **From:** Epic 05 sub-spec 2
- **Why:** Sub-spec 2 ships a minimal dispute form (reasonCategory + 10-2000-char description). A real dispute workflow benefits from file uploads (screenshots), SL transaction references, a linked in-world chat log, or a timeline of prior attempts. The dispute route was deliberately scoped as a full page (rather than a modal) so these additions can land without re-architecting.
- **When:** Epic 10 (Admin & Moderation) — at the same time the admin dispute-resolution tooling lands so both sides mature together.
- **Notes:** Additions likely include: file uploads (reuse Epic 02 avatar-upload's S3 path), optional `slTransactionKey` field for `PAYMENT_NOT_CREDITED` claims, evidence timeline. DTO expansion on `EscrowDisputeRequest` + new evidence entity on the backend.

### `PAYMENT_NOT_CREDITED` dispute reconciliation

- **From:** Epic 05 sub-spec 2 (design review)
- **Why:** The reason category claims "I paid but escrow didn't advance," which is the class of claim that indicates a happy-path failure (L$ may have already left the winner's wallet). Automatic refund on this dispute category risks double-paying the winner if the original payment callback later lands via idempotent retry.
- **When:** Epic 10 (Admin & Moderation) — alongside admin dispute-resolution tooling. The admin workflow must pull the SLPA terminal ledger balance, the winner's claimed `slTransactionKey` (which would be an optional field on `EscrowDisputeRequest` per the dispute-evidence opener above), and reconcile against the backend's `EscrowTransaction` ledger before any refund.
- **Notes:** Until Epic 10, `PAYMENT_NOT_CREDITED` disputes transition to `DISPUTED` and sit awaiting manual review like every other category. Sub-spec 2 does NOT add the optional `slTransactionKey` field yet — grouped with evidence attachments to avoid a one-off DTO change.

### Terminal locator on PAY ESCROW state

- **From:** Epic 05 sub-spec 2 (`PendingStateCard` winner view)
- **Why:** The winner's `ESCROW_PENDING` card includes a "Find a terminal" link; sub-spec 2 renders it as an inert placeholder because no in-world terminal locator exists yet. A real implementation would map registered `Terminal` rows (sub-spec 1 §7.5) to their SL region names + SLURL links.
- **When:** Epic 11 (LSL scripting) — when real in-world terminals are deployed. Pre-launch ops checklist item.
- **Notes:** The backend already has the data (`Terminal.region_name` column + `http_in_url`). A public endpoint `GET /api/v1/sl/terminals/public` returning `[{ terminalId, regionName, slUrl }, ...]` would feed the locator. Keep Phase 1 placeholder copy ("Find an SLPA terminal in-world") until the locator ships.

### Cross-page eventbus for dashboard row escrow freshness

- **From:** Epic 05 sub-spec 2 (§2.4)
- **Why:** Dashboard rows (`MyBidSummaryRow` / `ListingSummaryRow`) pick up `escrowState` changes via `refetchOnWindowFocus` + navigation — NOT via envelope-driven invalidation. Lags live state by up to ~30s on a stale dashboard tab. Acceptable for Phase 1 since the user must be actively viewing the dashboard to see rows; an eventbus that forwards envelope signals to invalidate `["myBids"]` / `["myListings"]` would make the dashboard fully live.
- **When:** Indefinite — only pull in if user feedback shows the lag feels wrong. Implementation is ~30 LoC (named emitter + two subscriber hooks).

---

## §12 — Cross-cutting concerns

- **No emoji anywhere.** Icons come from `@/components/ui/icons.ts` (lucide-react) per memory rule.
- **Dark + light mode parity.** Every new component must render correctly in both modes per `docs/stitch_generated-design/DESIGN.md`. `EscrowChip` tone tokens map to the existing palette (no new hex values).
- **Mobile pattern.** Plain stack per Q8 — no sticky affordances. Stepper can horizontal-scroll on narrow viewports if needed; step card fills width.
- **No AI/tool attribution** in commits or PR body (per standing user preference).
- **Work off `dev` branch**, branch `task/05-sub-2-escrow-frontend`, PR targets `dev`.
- **README sweep** at task-sequence end per standing user preference.
- **FOOTGUNS entries** opened during implementation for non-obvious gotchas (EXPIRED branching, TRANSFER_PENDING sub-split, envelope-invalidation strategy, inferEndOutcome retirement rationale).
