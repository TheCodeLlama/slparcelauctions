# Epic 05 Sub-Spec 2 — Escrow Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Source spec:** [`docs/superpowers/specs/2026-04-22-epic-05-sub-2-escrow-frontend.md`](../specs/2026-04-22-epic-05-sub-2-escrow-frontend.md) — every task refers to the spec by section number. Subagents receive relevant sections inline in their prompt; they should NOT read the spec cover-to-cover.

**Goal:** Ship the frontend surface that consumes the Epic 05 sub-spec 1 escrow engine — `/auction/[id]/escrow` status page (3-node stepper + 6 role-aware state cards), `/auction/[id]/escrow/dispute` full-page form, dashboard row escrow chip, `AuctionEndedPanel` escrow banner + CTA, and the WebSocket envelope wiring that keeps all three surfaces live.

**Architecture:** RSC shell at each route (session-only auth gate, no data fetching) hands off to a `"use client"` component that does the `useQuery` + `useStompSubscription` dance. Escrow envelopes are pure cache-invalidation signals per sub-spec 1 §8 — no envelope-to-DTO merging. Role is computed server-side and threaded through as a prop so client code never re-derives seller-vs-winner.

**Tech Stack:** Next.js 16 App Router, React 19 (RSC + `"use client"`), TanStack Query v5, React Hook Form + Zod, Tailwind CSS 4 (design-system tokens only), Vitest + React Testing Library + MSW.

---

## Preflight (before Task 1)

- [ ] **P.1: Confirm branch state**

```bash
git rev-parse --abbrev-ref HEAD      # expect: task/05-sub-2-escrow-frontend
git status                           # expect: working tree clean (spec already committed + pushed)
git log --oneline -3
```

- [ ] **P.2: Run existing test suite to establish green baseline**

```bash
cd frontend && npm run test
```

Expected: all tests pass (baseline from Epic 05 sub-spec 1 merge — ~677 frontend tests). If any fail, STOP and fix the baseline before introducing sub-spec 2 changes.

- [ ] **P.3: Read the spec**

Implementer subagents receive inline extracts per task. The controller (you) should skim §2 (Architecture), §3 (Component specs), §4 (Dashboard integration), §5 (AuctionEndedPanel), §6 (Dispute subroute), §7 (WS handling) to answer subagent questions quickly.

- [ ] **P.4: Dev server smoke check**

```bash
cd frontend && npm run dev
```

Navigate to an existing auction page to confirm the app boots. Ctrl-C when satisfied. Subsequent tasks do NOT require the dev server running except for the two manual verification steps explicitly called out in Tasks 5 and 6.

---

## File structure overview

**New files:**

```
frontend/src/
├── app/auction/[id]/escrow/
│   ├── page.tsx                                      (Task 5)
│   ├── EscrowPageClient.tsx                          (Task 5)
│   ├── EscrowPageClient.test.tsx                     (Task 5)
│   └── dispute/
│       ├── page.tsx                                  (Task 6)
│       ├── DisputeFormClient.tsx                     (Task 6)
│       └── DisputeFormClient.test.tsx                (Task 6)
├── components/escrow/
│   ├── EscrowChip.tsx                                (Task 3)
│   ├── EscrowChip.test.tsx                           (Task 3)
│   ├── EscrowDeadlineBadge.tsx                       (Task 3)
│   ├── EscrowDeadlineBadge.test.tsx                  (Task 3)
│   ├── EscrowStepper.tsx                             (Task 3)
│   ├── EscrowStepper.test.tsx                        (Task 3)
│   ├── EscrowStepCard.tsx                            (Task 4)
│   ├── EscrowStepCard.test.tsx                       (Task 4)
│   ├── state/
│   │   ├── PendingStateCard.tsx                      (Task 4)
│   │   ├── PendingStateCard.test.tsx                 (Task 4)
│   │   ├── TransferPendingStateCard.tsx              (Task 4)
│   │   ├── TransferPendingStateCard.test.tsx         (Task 4)
│   │   ├── CompletedStateCard.tsx                    (Task 4)
│   │   ├── CompletedStateCard.test.tsx               (Task 4)
│   │   ├── DisputedStateCard.tsx                     (Task 4)
│   │   ├── DisputedStateCard.test.tsx                (Task 4)
│   │   ├── FrozenStateCard.tsx                       (Task 4)
│   │   ├── FrozenStateCard.test.tsx                  (Task 4)
│   │   ├── ExpiredStateCard.tsx                      (Task 4)
│   │   └── ExpiredStateCard.test.tsx                 (Task 4)
│   ├── EscrowPageLayout.tsx                          (Task 5)
│   ├── EscrowPageHeader.tsx                          (Task 5)
│   ├── EscrowPageSkeleton.tsx                        (Task 5)
│   ├── EscrowPageError.tsx                           (Task 5)
│   ├── EscrowPageEmpty.tsx                           (Task 5)
│   └── escrowBannerCopy.ts                           (Task 8)
├── lib/api/
│   └── escrow.ts                                     (Task 2)
├── types/
│   └── escrow.ts                                     (Task 1)
└── test/fixtures/
    └── escrow.ts                                     (Task 1)
```

**Modified files:**

- `frontend/src/types/auction.ts` — widen `AuctionEnvelope` into `AuctionTopicEnvelope`; add `escrowState?` + `transferConfirmedAt?` fields to `PublicAuctionResponse`, `SellerAuctionResponse`, `MyBidSummary.auction`, `ActiveListingAuctionSummary` (Task 1).
- `frontend/src/components/bids/MyBidSummaryRow.tsx` — render `EscrowChip` + "View escrow →" when `escrowState != null` (Task 7).
- `frontend/src/components/listing/ListingSummaryRow.tsx` — mirror treatment; also drop the `inferEndOutcome` helper (Task 10).
- `frontend/src/components/auction/AuctionEndedPanel.tsx` — add `EscrowBannerForPanel` subcomponent; drop stub seller-overlay line; drop `inferOutcomeFromDto` helper (Tasks 8 + 10).
- `frontend/src/app/auction/[id]/AuctionDetailClient.tsx` — extend envelope handler with escrow branch (Task 9).
- `backend/**` — potentially extend `MyBidSummary.auction`, `ActiveListingAuctionSummary`, `PublicAuctionResponse`, `SellerAuctionResponse` DTOs with `escrowState` + `transferConfirmedAt` if sub-spec 1 didn't already ship them (Task 1 verifies).

**Docs touched in Task 10:**

- `docs/implementation/DEFERRED_WORK.md` — close 3 entries, open 4 entries per spec §10 + §11.
- `docs/implementation/FOOTGUNS.md` — add 4 entries.
- `README.md` — implementation-status sweep.

---

## Conventions all tasks follow

- **No emoji anywhere** — icons come from `@/components/ui/icons.ts` (lucide-react re-exports).
- **Design-system tokens only** — Tailwind classes like `bg-error-container`, `text-on-surface`, `shadow-soft` etc. No hardcoded hex values. See `docs/stitch_generated-design/DESIGN.md`.
- **Dark + light mode** — every component must render correctly in both; `renderWithProviders(ui, { theme: "dark" })` covers this.
- **React Server Components first** — drop to `"use client"` only when state, effects, or browser APIs are needed.
- **TanStack Query v5** — `useQuery({ queryKey, queryFn })` for reads; `useMutation` for writes.
- **React Hook Form + Zod** — forms use `zodResolver(schema)`; error surface via `formState.errors`.
- **TDD** — write failing test → see RED → minimal impl → see GREEN → commit. Each task typically commits 4-10 times.
- **No AI/tool attribution** in commit messages — no `Co-Authored-By`, no Claude/Opus/Anthropic mentions.
- **Commits:** conventional-commits style (`feat(escrow):`, `test(escrow):`, `fix(escrow):`, `docs:`).

---

## Task 1: Backend DTO enrichment + frontend types foundation

**Spec reference:** §1 (scope — types), §4.3 (backend enrichment requirement), §7.1 (envelope types).

**Goal:** Verify that sub-spec 1 backend already exposes `escrowState` + `transferConfirmedAt` on the four DTOs the frontend reads. If any are missing, extend the backend DTOs. Add frontend type definitions for `EscrowStatusResponse`, `EscrowEnvelope` union, and the merged `AuctionTopicEnvelope`. No UI yet.

### Files

- Create: `frontend/src/types/escrow.ts`
- Create: `frontend/src/test/fixtures/escrow.ts`
- Modify: `frontend/src/types/auction.ts` — add optional escrow fields + widen to `AuctionTopicEnvelope`.
- Modify (conditionally, per audit): backend DTO mappers/records for `MyBidSummary.auction`, `ActiveListingAuctionSummary`, `PublicAuctionResponse`, `SellerAuctionResponse`.

### Step 1.1: Audit backend DTOs

- [ ] **1.1: Grep for escrowState in backend**

```bash
cd backend && grep -rn "escrowState\|transferConfirmedAt" src/main/java/com/slparcelauctions/backend --include="*.java" | head -40
```

Inspect output. The four DTOs to verify:
- `MyBidSummary.auction` (likely `com.slparcelauctions.backend.auction.mybids.MyBidAuctionSummary` or similar)
- `ActiveListingAuctionSummary` (likely in `com.slparcelauctions.backend.auction.mylistings` or a seller DTO package)
- `PublicAuctionResponse` (likely `com.slparcelauctions.backend.auction.dto.PublicAuctionResponse`)
- `SellerAuctionResponse` (likely `com.slparcelauctions.backend.auction.dto.SellerAuctionResponse`)

For each: record whether `escrowState` + `transferConfirmedAt` already exist. If yes, skip to 1.2. If any are missing, complete sub-step 1.1a for those DTOs.

- [ ] **1.1a (conditional): Add missing fields to backend DTOs**

For each DTO missing the fields, extend the record + its mapper. Example for a `MyBidAuctionSummary` record:

```java
// Before
public record MyBidAuctionSummary(
        Long id,
        String title,
        AuctionStatus status,
        AuctionEndOutcome endOutcome,
        // ... existing fields
) { }

// After — add two optional fields
public record MyBidAuctionSummary(
        Long id,
        String title,
        AuctionStatus status,
        AuctionEndOutcome endOutcome,
        EscrowState escrowState,              // nullable
        OffsetDateTime transferConfirmedAt,    // nullable
        // ... existing fields
) { }
```

Mapper update (find the corresponding `toDto` method):

```java
public MyBidAuctionSummary toDto(Auction auction, Escrow escrow) {
    return new MyBidAuctionSummary(
            auction.getId(),
            auction.getTitle(),
            auction.getStatus(),
            auction.getEndOutcome(),
            escrow != null ? escrow.getState() : null,
            escrow != null ? escrow.getTransferConfirmedAt() : null,
            // ... existing fields
    );
}
```

The escrow is fetched via `escrowRepo.findByAuctionId(auction.getId()).orElse(null)` in the service layer that builds the DTO. If the caller loads many auctions, batch the escrow load into a `Map<Long, Escrow>` keyed by auction ID to avoid N+1.

Commit each modified DTO + its mapper + its tests separately:

```bash
git add backend/src/main/java/.../MyBidAuctionSummary.java backend/src/main/java/.../MyBidDtoMapper.java backend/src/test/java/.../MyBidDtoMapperTest.java
git commit -m "feat(auction): enrich MyBidAuctionSummary with escrowState + transferConfirmedAt"
```

Repeat for each missing DTO. After all DTOs have the fields, run `cd backend && ./mvnw test` and confirm green.

### Step 1.2: Create `frontend/src/types/escrow.ts`

- [ ] **1.2: Create the escrow types module**

```typescript
// frontend/src/types/escrow.ts

/**
 * Per-auction escrow lifecycle states. Mirror of the backend enum
 * `com.slparcelauctions.backend.escrow.EscrowState`. Terminal states are
 * COMPLETED, DISPUTED, EXPIRED, FROZEN (no transitions out in sub-spec 1).
 * FUNDED is a transient state — sub-spec 1 atomically advances it to
 * TRANSFER_PENDING within the same transaction, so external observers
 * rarely see it. Frontend treats FUNDED the same as TRANSFER_PENDING.
 */
export type EscrowState =
  | "ESCROW_PENDING"
  | "FUNDED"
  | "TRANSFER_PENDING"
  | "COMPLETED"
  | "DISPUTED"
  | "EXPIRED"
  | "FROZEN";

/** Categories surfaced in the dispute form + on the DISPUTED state card. */
export type EscrowDisputeReasonCategory =
  | "SELLER_NOT_RESPONSIVE"
  | "WRONG_PARCEL_TRANSFERRED"
  | "PAYMENT_NOT_CREDITED"
  | "FRAUD_SUSPECTED"
  | "OTHER";

/** Reason a `FROZEN` escrow entered that state. Backend-authoritative. */
export type EscrowFreezeReason =
  | "UNKNOWN_OWNER"
  | "PARCEL_DELETED"
  | "WORLD_API_PERSISTENT_FAILURE";

/**
 * The counterparty — seller sees the winner, winner sees the seller.
 * Fields mirror the backend's `CounterpartyDto` projection on the escrow
 * status response.
 */
export interface EscrowCounterparty {
  userId: number;
  displayName: string;
  slAvatarName: string;
  slAvatarUuid: string;
}

/**
 * Full response shape from `GET /api/v1/auctions/{id}/escrow`.
 * Implementer: this matches the backend `EscrowStatusResponse` record
 * introduced in sub-spec 1 Task 3.
 */
export interface EscrowStatusResponse {
  escrowId: number;
  auctionId: number;
  parcelName: string;
  region: string;
  state: EscrowState;
  finalBidAmount: number;
  commissionAmt: number;
  payoutAmt: number;

  // Deadlines
  paymentDeadline: string;            // ISO-8601
  transferDeadline: string | null;    // null until funded

  // Timestamps (nullable until the respective transition stamps them)
  fundedAt: string | null;
  transferConfirmedAt: string | null;
  completedAt: string | null;
  disputedAt: string | null;
  frozenAt: string | null;
  expiredAt: string | null;

  // Outcome context (nullable unless the state carries them)
  disputeReasonCategory: EscrowDisputeReasonCategory | null;
  disputeDescription: string | null;
  freezeReason: EscrowFreezeReason | null;

  counterparty: EscrowCounterparty;
}

/** Body shape for `POST /api/v1/auctions/{id}/escrow/dispute`. */
export interface EscrowDisputeRequest {
  reasonCategory: EscrowDisputeReasonCategory;
  description: string;
}

/**
 * WebSocket envelope types broadcast on `/topic/auction/{id}`. Per sub-spec 1
 * §8 these are coarse cache-invalidation signals — the frontend does NOT
 * read the variant-specific fields. They're typed here for forward
 * compatibility with Epic 09 notifications.
 */
export type EscrowEnvelopeType =
  | "ESCROW_CREATED"
  | "ESCROW_FUNDED"
  | "ESCROW_TRANSFER_CONFIRMED"
  | "ESCROW_COMPLETED"
  | "ESCROW_DISPUTED"
  | "ESCROW_EXPIRED"
  | "ESCROW_FROZEN"
  | "ESCROW_REFUND_COMPLETED"
  | "ESCROW_PAYOUT_STALLED";

export interface EscrowEnvelopeBase {
  type: EscrowEnvelopeType;
  auctionId: number;
  escrowId: number;
  state: EscrowState;
  serverTime: string;
}

export type EscrowEnvelope =
  | (EscrowEnvelopeBase & { type: "ESCROW_CREATED"; paymentDeadline: string })
  | (EscrowEnvelopeBase & { type: "ESCROW_FUNDED"; transferDeadline: string })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_TRANSFER_CONFIRMED";
      transferConfirmedAt: string;
    })
  | (EscrowEnvelopeBase & { type: "ESCROW_COMPLETED"; completedAt: string })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_DISPUTED";
      reasonCategory: EscrowDisputeReasonCategory;
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_EXPIRED";
      reason: "PAYMENT_TIMEOUT" | "TRANSFER_TIMEOUT";
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_FROZEN";
      reason: EscrowFreezeReason;
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_REFUND_COMPLETED";
      refundAmount: number;
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_PAYOUT_STALLED";
      attemptCount: number;
      lastError?: string;
    });
```

- [ ] **1.3: Commit**

```bash
git add frontend/src/types/escrow.ts
git commit -m "feat(escrow): add EscrowState + EscrowStatusResponse + envelope types"
```

### Step 1.3: Extend `frontend/src/types/auction.ts`

- [ ] **1.4: Add escrow fields to existing auction DTOs**

Find and modify the four interfaces:

```typescript
// frontend/src/types/auction.ts — additions within existing interfaces

import type { EscrowState } from "./escrow";  // add to top of file

export interface PublicAuctionResponse {
  // ... existing fields unchanged
  escrowState?: EscrowState | null;          // nullable — only present when the auction has an escrow
  transferConfirmedAt?: string | null;       // nullable ISO-8601
}

export interface SellerAuctionResponse {
  // ... existing fields unchanged
  escrowState?: EscrowState | null;
  transferConfirmedAt?: string | null;
}

export interface MyBidAuctionSummary {
  // ... existing fields unchanged (inside the MyBidSummary.auction field)
  escrowState?: EscrowState | null;
  transferConfirmedAt?: string | null;
}

export interface ActiveListingAuctionSummary {
  // ... existing fields unchanged
  escrowState?: EscrowState | null;
  transferConfirmedAt?: string | null;
}
```

- [ ] **1.5: Widen `AuctionEnvelope` to `AuctionTopicEnvelope`**

At the bottom of `types/auction.ts`, keep the existing `AuctionEnvelope` (still used by some code paths) and add the merged union:

```typescript
// existing — keep
export type AuctionEnvelope = BidSettlementEnvelope | AuctionEndedEnvelope;

// new — import from types/escrow
import type { EscrowEnvelope } from "./escrow";

/**
 * Every envelope type that can arrive on `/topic/auction/{id}`.
 * `AuctionDetailClient` and `EscrowPageClient` both subscribe with this
 * union; handlers discriminate on `type`.
 */
export type AuctionTopicEnvelope = AuctionEnvelope | EscrowEnvelope;
```

- [ ] **1.6: Commit**

```bash
git add frontend/src/types/auction.ts
git commit -m "feat(escrow): widen auction DTOs + envelope union to cover escrow"
```

### Step 1.4: Create test fixture builder

- [ ] **1.7: Create `frontend/src/test/fixtures/escrow.ts`**

```typescript
// frontend/src/test/fixtures/escrow.ts
import type {
  EscrowEnvelope,
  EscrowEnvelopeType,
  EscrowStatusResponse,
} from "@/types/escrow";

export function fakeEscrow(
  overrides: Partial<EscrowStatusResponse> = {},
): EscrowStatusResponse {
  const base: EscrowStatusResponse = {
    escrowId: 1,
    auctionId: 7,
    parcelName: "Obsidian Ridge Estate",
    region: "Sansara",
    state: "ESCROW_PENDING",
    finalBidAmount: 5000,
    commissionAmt: 250,
    payoutAmt: 4750,
    paymentDeadline: new Date(Date.now() + 48 * 3600 * 1000).toISOString(),
    transferDeadline: null,
    fundedAt: null,
    transferConfirmedAt: null,
    completedAt: null,
    disputedAt: null,
    frozenAt: null,
    expiredAt: null,
    disputeReasonCategory: null,
    disputeDescription: null,
    freezeReason: null,
    counterparty: {
      userId: 99,
      displayName: "Kira Swansong",
      slAvatarName: "Kira Swansong",
      slAvatarUuid: "a0b1c2d3-0000-4000-8000-000000000001",
    },
  };
  return { ...base, ...overrides };
}

export function fakeEscrowEnvelope<T extends EscrowEnvelopeType>(
  type: T,
  overrides: Partial<EscrowEnvelope> = {},
): EscrowEnvelope {
  const baseCommon = {
    auctionId: 7,
    escrowId: 1,
    serverTime: new Date().toISOString(),
  };

  switch (type) {
    case "ESCROW_CREATED":
      return {
        type: "ESCROW_CREATED",
        state: "ESCROW_PENDING",
        paymentDeadline: new Date(Date.now() + 48 * 3600 * 1000).toISOString(),
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_FUNDED":
      return {
        type: "ESCROW_FUNDED",
        state: "TRANSFER_PENDING",
        transferDeadline: new Date(Date.now() + 72 * 3600 * 1000).toISOString(),
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_TRANSFER_CONFIRMED":
      return {
        type: "ESCROW_TRANSFER_CONFIRMED",
        state: "TRANSFER_PENDING",
        transferConfirmedAt: new Date().toISOString(),
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_COMPLETED":
      return {
        type: "ESCROW_COMPLETED",
        state: "COMPLETED",
        completedAt: new Date().toISOString(),
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_DISPUTED":
      return {
        type: "ESCROW_DISPUTED",
        state: "DISPUTED",
        reasonCategory: "OTHER",
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_EXPIRED":
      return {
        type: "ESCROW_EXPIRED",
        state: "EXPIRED",
        reason: "PAYMENT_TIMEOUT",
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_FROZEN":
      return {
        type: "ESCROW_FROZEN",
        state: "FROZEN",
        reason: "UNKNOWN_OWNER",
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_REFUND_COMPLETED":
      return {
        type: "ESCROW_REFUND_COMPLETED",
        state: "EXPIRED",
        refundAmount: 5000,
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_PAYOUT_STALLED":
      return {
        type: "ESCROW_PAYOUT_STALLED",
        state: "TRANSFER_PENDING",
        attemptCount: 4,
        lastError: "terminal offline",
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    default: {
      const _exhaustive: never = type;
      throw new Error(`Unhandled envelope type: ${_exhaustive}`);
    }
  }
}
```

- [ ] **1.8: Commit**

```bash
git add frontend/src/test/fixtures/escrow.ts
git commit -m "test(escrow): add fakeEscrow + fakeEscrowEnvelope fixture builders"
```

### Step 1.5: Type-check + full suite

- [ ] **1.9: Run type check**

```bash
cd frontend && npx tsc --noEmit
```

Expected: no new errors. If the merged `AuctionTopicEnvelope` causes type errors in existing call sites (unlikely since it's a strict superset), fix them by widening the generic in `useStompSubscription<T>` calls.

- [ ] **1.10: Run full test suite**

```bash
cd frontend && npm run test
```

Expected: baseline unchanged (no new tests added yet; fixtures aren't imported anywhere).

---

## Task 2: API client — `escrow.ts`

**Spec reference:** §2.4 (cache strategy), §6.4 (submit handler).

**Goal:** Ship the two API client functions that every later task calls.

### Files

- Create: `frontend/src/lib/api/escrow.ts`
- Create: `frontend/src/lib/api/escrow.test.ts`

### Step 2.1: TDD — write the failing test

- [ ] **2.1: Create `frontend/src/lib/api/escrow.test.ts`**

```typescript
import { describe, it, expect, beforeEach } from "vitest";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import { fileDispute, getEscrowStatus } from "./escrow";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("escrow API client", () => {
  describe("getEscrowStatus", () => {
    it("returns EscrowStatusResponse on 200", async () => {
      server.use(
        http.get("*/api/v1/auctions/7/escrow", () =>
          HttpResponse.json(fakeEscrow({ auctionId: 7, escrowId: 1 })),
        ),
      );
      const result = await getEscrowStatus(7);
      expect(result.escrowId).toBe(1);
      expect(result.auctionId).toBe(7);
      expect(result.state).toBe("ESCROW_PENDING");
    });

    it("throws on 404", async () => {
      server.use(
        http.get("*/api/v1/auctions/99/escrow", () =>
          HttpResponse.json(
            { status: 404, code: "ESCROW_NOT_FOUND", detail: "no escrow" },
            { status: 404 },
          ),
        ),
      );
      await expect(getEscrowStatus(99)).rejects.toMatchObject({ status: 404 });
    });

    it("throws on 403", async () => {
      server.use(
        http.get("*/api/v1/auctions/7/escrow", () =>
          HttpResponse.json(
            { status: 403, code: "ESCROW_FORBIDDEN", detail: "not a party" },
            { status: 403 },
          ),
        ),
      );
      await expect(getEscrowStatus(7)).rejects.toMatchObject({ status: 403 });
    });
  });

  describe("fileDispute", () => {
    it("posts the body and returns the new EscrowStatusResponse", async () => {
      server.use(
        http.post("*/api/v1/auctions/7/escrow/dispute", async ({ request }) => {
          const body = (await request.json()) as {
            reasonCategory: string;
            description: string;
          };
          expect(body.reasonCategory).toBe("SELLER_NOT_RESPONSIVE");
          expect(body.description).toBe("Not responding to messages");
          return HttpResponse.json(
            fakeEscrow({
              auctionId: 7,
              state: "DISPUTED",
              disputedAt: new Date().toISOString(),
              disputeReasonCategory: "SELLER_NOT_RESPONSIVE",
              disputeDescription: "Not responding to messages",
            }),
          );
        }),
      );

      const result = await fileDispute(7, {
        reasonCategory: "SELLER_NOT_RESPONSIVE",
        description: "Not responding to messages",
      });

      expect(result.state).toBe("DISPUTED");
      expect(result.disputeReasonCategory).toBe("SELLER_NOT_RESPONSIVE");
    });

    it("throws on 409 ESCROW_INVALID_TRANSITION", async () => {
      server.use(
        http.post("*/api/v1/auctions/7/escrow/dispute", () =>
          HttpResponse.json(
            {
              status: 409,
              code: "ESCROW_INVALID_TRANSITION",
              detail: "too late",
            },
            { status: 409 },
          ),
        ),
      );
      await expect(
        fileDispute(7, { reasonCategory: "OTHER", description: "1234567890" }),
      ).rejects.toMatchObject({ status: 409, code: "ESCROW_INVALID_TRANSITION" });
    });
  });
});
```

- [ ] **2.2: Run test to see RED**

```bash
cd frontend && npx vitest run src/lib/api/escrow.test.ts
```

Expected: FAIL — `getEscrowStatus` and `fileDispute` are not defined.

### Step 2.2: Create the API client

- [ ] **2.3: Create `frontend/src/lib/api/escrow.ts`**

```typescript
import { api } from "@/lib/api";
import type {
  EscrowDisputeRequest,
  EscrowStatusResponse,
} from "@/types/escrow";

/**
 * Fetches the escrow status for an auction. Returns 404 if no escrow exists
 * (auction ended with no winner, or the auction isn't in the ENDED+SOLD
 * state yet). Returns 403 if the caller isn't the seller or the winner.
 */
export function getEscrowStatus(
  auctionId: number | string,
): Promise<EscrowStatusResponse> {
  return api.get<EscrowStatusResponse>(
    `/api/v1/auctions/${auctionId}/escrow`,
  );
}

/**
 * Files a dispute on an escrow. Source states must be ESCROW_PENDING,
 * FUNDED, or TRANSFER_PENDING — backend returns 409 ESCROW_INVALID_TRANSITION
 * for terminal states (COMPLETED / EXPIRED / DISPUTED / FROZEN).
 */
export function fileDispute(
  auctionId: number | string,
  body: EscrowDisputeRequest,
): Promise<EscrowStatusResponse> {
  return api.post<EscrowStatusResponse>(
    `/api/v1/auctions/${auctionId}/escrow/dispute`,
    body,
  );
}
```

- [ ] **2.4: Run test to see GREEN**

```bash
cd frontend && npx vitest run src/lib/api/escrow.test.ts
```

Expected: PASS — all 5 tests.

- [ ] **2.5: Commit**

```bash
git add frontend/src/lib/api/escrow.ts frontend/src/lib/api/escrow.test.ts
git commit -m "feat(escrow): API client — getEscrowStatus + fileDispute"
```

### Step 2.3: Full suite sanity

- [ ] **2.6: Run full suite**

```bash
cd frontend && npm run test
```

Expected: baseline + 5 new = previous total + 5, all green.

---

## Task 3: Primitives — `EscrowChip`, `EscrowDeadlineBadge`, `EscrowStepper`

**Spec reference:** §3.1 (stepper), §3.4 (chip), §3.5 (deadline badge).

**Goal:** Ship the three reusable primitives that Tasks 4, 5, 7, and 8 all consume. Full unit-test coverage.

### Files

- Create: `frontend/src/components/escrow/EscrowChip.tsx`
- Create: `frontend/src/components/escrow/EscrowChip.test.tsx`
- Create: `frontend/src/components/escrow/EscrowDeadlineBadge.tsx`
- Create: `frontend/src/components/escrow/EscrowDeadlineBadge.test.tsx`
- Create: `frontend/src/components/escrow/EscrowStepper.tsx`
- Create: `frontend/src/components/escrow/EscrowStepper.test.tsx`

### Step 3.1: `EscrowChip` — TDD

- [ ] **3.1: Write `EscrowChip.test.tsx` (RED)**

```typescript
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowChip } from "./EscrowChip";

describe("EscrowChip", () => {
  describe("ESCROW_PENDING", () => {
    it("shows PAY ESCROW for winner", () => {
      renderWithProviders(<EscrowChip state="ESCROW_PENDING" role="winner" />);
      expect(screen.getByText(/pay escrow/i)).toBeInTheDocument();
    });
    it("shows AWAITING PAYMENT for seller", () => {
      renderWithProviders(<EscrowChip state="ESCROW_PENDING" role="seller" />);
      expect(screen.getByText(/awaiting payment/i)).toBeInTheDocument();
    });
    it("applies action tone class", () => {
      const { container } = renderWithProviders(
        <EscrowChip state="ESCROW_PENDING" role="winner" />,
      );
      expect(container.firstChild).toHaveAttribute("data-tone", "action");
    });
  });

  describe("TRANSFER_PENDING sub-split", () => {
    it("shows AWAITING TRANSFER for winner pre-confirmation", () => {
      renderWithProviders(
        <EscrowChip
          state="TRANSFER_PENDING"
          role="winner"
          transferConfirmedAt={null}
        />,
      );
      expect(screen.getByText(/awaiting transfer/i)).toBeInTheDocument();
    });
    it("shows TRANSFER LAND for seller pre-confirmation", () => {
      renderWithProviders(
        <EscrowChip
          state="TRANSFER_PENDING"
          role="seller"
          transferConfirmedAt={null}
        />,
      );
      expect(screen.getByText(/transfer land/i)).toBeInTheDocument();
    });
    it("shows PAYOUT PENDING for both roles post-confirmation", () => {
      const { rerender } = renderWithProviders(
        <EscrowChip
          state="TRANSFER_PENDING"
          role="winner"
          transferConfirmedAt={new Date().toISOString()}
        />,
      );
      expect(screen.getByText(/payout pending/i)).toBeInTheDocument();
      rerender(
        <EscrowChip
          state="TRANSFER_PENDING"
          role="seller"
          transferConfirmedAt={new Date().toISOString()}
        />,
      );
      expect(screen.getByText(/payout pending/i)).toBeInTheDocument();
    });
  });

  describe("terminal states", () => {
    it("shows COMPLETED in done tone", () => {
      const { container } = renderWithProviders(
        <EscrowChip state="COMPLETED" role="winner" />,
      );
      expect(screen.getByText(/completed/i)).toBeInTheDocument();
      expect(container.firstChild).toHaveAttribute("data-tone", "done");
    });
    it("shows DISPUTED in problem tone", () => {
      const { container } = renderWithProviders(
        <EscrowChip state="DISPUTED" role="winner" />,
      );
      expect(screen.getByText(/disputed/i)).toBeInTheDocument();
      expect(container.firstChild).toHaveAttribute("data-tone", "problem");
    });
    it("shows FROZEN in problem tone", () => {
      const { container } = renderWithProviders(
        <EscrowChip state="FROZEN" role="winner" />,
      );
      expect(container.firstChild).toHaveAttribute("data-tone", "problem");
    });
    it("shows EXPIRED in muted tone", () => {
      const { container } = renderWithProviders(
        <EscrowChip state="EXPIRED" role="winner" />,
      );
      expect(container.firstChild).toHaveAttribute("data-tone", "muted");
    });
  });

  it("falls back to role-neutral label when role is omitted", () => {
    renderWithProviders(<EscrowChip state="ESCROW_PENDING" />);
    expect(screen.getByText(/escrow pending/i)).toBeInTheDocument();
  });
});
```

Run: `cd frontend && npx vitest run src/components/escrow/EscrowChip.test.tsx` — Expected: COMPILE FAIL.

- [ ] **3.2: Create `EscrowChip.tsx` (GREEN)**

```typescript
import type { EscrowState } from "@/types/escrow";
import { cn } from "@/lib/cn";

export type EscrowChipRole = "seller" | "winner";
export type EscrowChipTone = "action" | "waiting" | "done" | "problem" | "muted";
export type EscrowChipSize = "sm" | "md";

export interface EscrowChipProps {
  state: EscrowState;
  /** Required to disambiguate TRANSFER_PENDING sub-phases. */
  transferConfirmedAt?: string | null;
  /** Omit for role-neutral labels (future admin contexts). */
  role?: EscrowChipRole;
  size?: EscrowChipSize;
  className?: string;
}

/**
 * State → (label, tone) mapping. See spec §3.4.
 *
 * TRANSFER_PENDING splits on transferConfirmedAt — post-confirmation both
 * roles see "Payout pending" (waiting tone) because the backend is
 * finalizing the transaction.
 */
function resolveChip(
  state: EscrowState,
  transferConfirmedAt: string | null | undefined,
  role: EscrowChipRole | undefined,
): { label: string; tone: EscrowChipTone } {
  switch (state) {
    case "ESCROW_PENDING":
      if (role === "winner") return { label: "Pay escrow", tone: "action" };
      if (role === "seller") return { label: "Awaiting payment", tone: "action" };
      return { label: "Escrow pending", tone: "action" };
    case "FUNDED":
    case "TRANSFER_PENDING":
      if (transferConfirmedAt) {
        return { label: "Payout pending", tone: "waiting" };
      }
      if (role === "winner") return { label: "Awaiting transfer", tone: "waiting" };
      if (role === "seller") return { label: "Transfer land", tone: "action" };
      return { label: "Transfer pending", tone: "waiting" };
    case "COMPLETED":
      return { label: "Completed", tone: "done" };
    case "DISPUTED":
      return { label: "Disputed", tone: "problem" };
    case "FROZEN":
      return { label: "Frozen", tone: "problem" };
    case "EXPIRED":
      return { label: "Expired", tone: "muted" };
  }
}

const toneClasses: Record<EscrowChipTone, string> = {
  action: "bg-primary-container text-on-primary-container",
  waiting: "bg-secondary-container text-on-secondary-container",
  done: "bg-tertiary-container text-on-tertiary-container",
  problem: "bg-error-container text-on-error-container",
  muted: "bg-surface-container text-on-surface-variant",
};

const sizeClasses: Record<EscrowChipSize, string> = {
  sm: "px-2 py-0.5 text-label-sm",
  md: "px-3 py-1 text-label-md",
};

export function EscrowChip({
  state,
  transferConfirmedAt,
  role,
  size = "sm",
  className,
}: EscrowChipProps) {
  const { label, tone } = resolveChip(state, transferConfirmedAt, role);
  return (
    <span
      data-tone={tone}
      className={cn(
        "inline-flex items-center rounded-full font-semibold uppercase tracking-wide",
        toneClasses[tone],
        sizeClasses[size],
        className,
      )}
    >
      {label}
    </span>
  );
}
```

Run: `cd frontend && npx vitest run src/components/escrow/EscrowChip.test.tsx` — Expected: PASS (10 tests).

- [ ] **3.3: Commit**

```bash
git add frontend/src/components/escrow/EscrowChip.tsx frontend/src/components/escrow/EscrowChip.test.tsx
git commit -m "feat(escrow): EscrowChip primitive with state+role+transferConfirmedAt resolution"
```

### Step 3.2: `EscrowDeadlineBadge` — TDD

- [ ] **3.4: Write `EscrowDeadlineBadge.test.tsx` (RED)**

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowDeadlineBadge } from "./EscrowDeadlineBadge";

describe("EscrowDeadlineBadge", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-05-01T12:00:00Z"));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("shows neutral tone when > 24h remain", () => {
    const deadline = new Date("2026-05-03T12:00:00Z").toISOString();
    const { container } = renderWithProviders(
      <EscrowDeadlineBadge deadline={deadline} />,
    );
    expect(container.firstChild).toHaveAttribute("data-urgency", "neutral");
    expect(screen.getByText(/left/i)).toBeInTheDocument();
  });

  it("shows warning tone between 6h and 24h", () => {
    const deadline = new Date("2026-05-01T23:00:00Z").toISOString();
    const { container } = renderWithProviders(
      <EscrowDeadlineBadge deadline={deadline} />,
    );
    expect(container.firstChild).toHaveAttribute("data-urgency", "warning");
  });

  it("shows urgent tone under 6h", () => {
    const deadline = new Date("2026-05-01T15:00:00Z").toISOString();
    const { container } = renderWithProviders(
      <EscrowDeadlineBadge deadline={deadline} />,
    );
    expect(container.firstChild).toHaveAttribute("data-urgency", "urgent");
  });

  it("shows past-deadline tone when deadline is in the past", () => {
    const deadline = new Date("2026-05-01T06:00:00Z").toISOString();
    const { container } = renderWithProviders(
      <EscrowDeadlineBadge deadline={deadline} />,
    );
    expect(container.firstChild).toHaveAttribute("data-urgency", "past");
  });

  it("accepts custom label prop", () => {
    const deadline = new Date("2026-05-03T12:00:00Z").toISOString();
    renderWithProviders(
      <EscrowDeadlineBadge deadline={deadline} label="Until payment" />,
    );
    expect(screen.getByText(/until payment/i)).toBeInTheDocument();
  });
});
```

Run — Expected: COMPILE FAIL.

- [ ] **3.5: Create `EscrowDeadlineBadge.tsx` (GREEN)**

```typescript
import { useEffect, useState } from "react";
import { cn } from "@/lib/cn";

export interface EscrowDeadlineBadgeProps {
  /** ISO 8601 deadline timestamp. */
  deadline: string;
  /** Text after the countdown. Defaults to "left". */
  label?: string;
  className?: string;
}

type Urgency = "neutral" | "warning" | "urgent" | "past";

const urgencyClasses: Record<Urgency, string> = {
  neutral: "text-on-surface-variant",
  warning: "text-tertiary",
  urgent: "text-error",
  past: "text-error line-through",
};

function urgencyFor(msRemaining: number): Urgency {
  if (msRemaining <= 0) return "past";
  const hours = msRemaining / 3_600_000;
  if (hours < 6) return "urgent";
  if (hours < 24) return "warning";
  return "neutral";
}

function formatRemaining(msRemaining: number): string {
  const abs = Math.abs(msRemaining);
  const hours = Math.floor(abs / 3_600_000);
  const minutes = Math.floor((abs % 3_600_000) / 60_000);
  if (msRemaining <= 0) return "past deadline";
  if (hours >= 24) {
    const days = Math.floor(hours / 24);
    const remHours = hours % 24;
    return `${days}d ${remHours}h`;
  }
  return `${hours}h ${minutes}m`;
}

export function EscrowDeadlineBadge({
  deadline,
  label = "left",
  className,
}: EscrowDeadlineBadgeProps) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const interval = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(interval);
  }, []);

  const msRemaining = new Date(deadline).getTime() - now;
  const urgency = urgencyFor(msRemaining);

  return (
    <span
      data-urgency={urgency}
      className={cn(
        "text-label-md font-medium",
        urgencyClasses[urgency],
        className,
      )}
    >
      {formatRemaining(msRemaining)} {label}
    </span>
  );
}
```

Run — Expected: PASS (5 tests).

- [ ] **3.6: Commit**

```bash
git add frontend/src/components/escrow/EscrowDeadlineBadge.tsx frontend/src/components/escrow/EscrowDeadlineBadge.test.tsx
git commit -m "feat(escrow): EscrowDeadlineBadge with urgency-tier coloring"
```

### Step 3.3: `EscrowStepper` — TDD

- [ ] **3.7: Write `EscrowStepper.test.tsx` (RED)**

```typescript
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowStepper } from "./EscrowStepper";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("EscrowStepper", () => {
  it("renders Payment as active for ESCROW_PENDING", () => {
    renderWithProviders(<EscrowStepper escrow={fakeEscrow({ state: "ESCROW_PENDING" })} />);
    expect(screen.getByText(/payment/i)).toBeInTheDocument();
    expect(screen.getByText(/transfer/i)).toBeInTheDocument();
    expect(screen.getByText(/complete/i)).toBeInTheDocument();
    // Payment node should have aria-current="step"
    const paymentNode = screen.getByText(/payment/i).closest("li");
    expect(paymentNode).toHaveAttribute("aria-current", "step");
  });

  it("renders Payment complete + Transfer active when funded", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "TRANSFER_PENDING",
          fundedAt: "2026-05-01T14:30:00Z",
        })}
      />,
    );
    const transferNode = screen.getByText(/^transfer$/i).closest("li");
    expect(transferNode).toHaveAttribute("aria-current", "step");
    // Payment timestamp visible under the completed node
    expect(screen.getByText(/2:30/i)).toBeInTheDocument();
  });

  it("renders first two complete + Complete active when transferConfirmedAt stamped", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "TRANSFER_PENDING",
          fundedAt: "2026-05-01T14:30:00Z",
          transferConfirmedAt: "2026-05-02T10:00:00Z",
        })}
      />,
    );
    const completeNode = screen.getByText(/^complete$/i).closest("li");
    expect(completeNode).toHaveAttribute("aria-current", "step");
  });

  it("renders all three complete for COMPLETED state", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "COMPLETED",
          fundedAt: "2026-05-01T14:30:00Z",
          transferConfirmedAt: "2026-05-02T10:00:00Z",
          completedAt: "2026-05-02T10:00:05Z",
        })}
      />,
    );
    // No aria-current — everything is complete
    const items = screen.getAllByRole("listitem");
    items.forEach((li) => expect(li).not.toHaveAttribute("aria-current"));
  });

  it("renders interrupt node for DISPUTED state", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "DISPUTED",
          disputedAt: "2026-05-01T15:00:00Z",
        })}
      />,
    );
    expect(screen.getByText(/disputed/i)).toBeInTheDocument();
  });

  it("renders interrupt node for FROZEN state", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "FROZEN",
          fundedAt: "2026-05-01T14:30:00Z",
          frozenAt: "2026-05-02T09:00:00Z",
        })}
      />,
    );
    expect(screen.getByText(/frozen/i)).toBeInTheDocument();
    // Payment stays complete (it happened before the freeze)
    expect(screen.getByText(/payment/i)).toBeInTheDocument();
  });

  it("renders interrupt node for EXPIRED state (payment timeout)", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "EXPIRED",
          fundedAt: null,
          expiredAt: "2026-05-03T12:00:00Z",
        })}
      />,
    );
    expect(screen.getByText(/expired/i)).toBeInTheDocument();
  });
});
```

Run — Expected: COMPILE FAIL.

- [ ] **3.8: Create `EscrowStepper.tsx` (GREEN)**

```typescript
import type { EscrowStatusResponse, EscrowState } from "@/types/escrow";
import { CheckCircle2, AlertCircle } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export interface EscrowStepperProps {
  escrow: EscrowStatusResponse;
  className?: string;
}

type StepState = "complete" | "current" | "upcoming";

interface ResolvedNode {
  label: string;
  state: StepState;
  timestamp: string | null;
}

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, {
    hour: "numeric",
    minute: "2-digit",
  });
}

/**
 * Resolves the three stepper nodes (Payment, Transfer, Complete) for a
 * non-terminal escrow. Terminal non-happy states (DISPUTED, FROZEN, EXPIRED)
 * render via the interrupt path.
 */
function resolveNodes(escrow: EscrowStatusResponse): ResolvedNode[] {
  const { state, fundedAt, transferConfirmedAt, completedAt } = escrow;

  const paymentComplete = fundedAt != null;
  const transferComplete = transferConfirmedAt != null;
  const allComplete = completedAt != null;

  const paymentNode: ResolvedNode = {
    label: "Payment",
    state: paymentComplete ? "complete" : "current",
    timestamp: paymentComplete ? fundedAt : null,
  };

  const transferNode: ResolvedNode = {
    label: "Transfer",
    state: transferComplete
      ? "complete"
      : paymentComplete
        ? "current"
        : "upcoming",
    timestamp: transferComplete ? transferConfirmedAt : null,
  };

  const completeNode: ResolvedNode = {
    label: "Complete",
    state: allComplete
      ? "complete"
      : transferComplete
        ? "current"
        : "upcoming",
    timestamp: allComplete ? completedAt : null,
  };

  return [paymentNode, transferNode, completeNode];
}

type InterruptInfo = {
  label: string;
  timestamp: string | null;
  precedingNodes: ResolvedNode[];
};

function resolveInterrupt(
  escrow: EscrowStatusResponse,
): InterruptInfo | null {
  const { state, fundedAt, transferConfirmedAt } = escrow;
  const paymentNode: ResolvedNode = {
    label: "Payment",
    state: fundedAt ? "complete" : "upcoming",
    timestamp: fundedAt,
  };
  const transferNode: ResolvedNode = {
    label: "Transfer",
    state: transferConfirmedAt ? "complete" : "upcoming",
    timestamp: transferConfirmedAt,
  };
  const preceding = [paymentNode, transferNode].filter(
    (n) => n.state === "complete",
  );

  if (state === "DISPUTED") {
    return {
      label: "Disputed",
      timestamp: escrow.disputedAt,
      precedingNodes: preceding,
    };
  }
  if (state === "FROZEN") {
    return {
      label: "Frozen",
      timestamp: escrow.frozenAt,
      precedingNodes: preceding,
    };
  }
  if (state === "EXPIRED") {
    return {
      label: "Expired",
      timestamp: escrow.expiredAt,
      precedingNodes: preceding,
    };
  }
  return null;
}

export function EscrowStepper({ escrow, className }: EscrowStepperProps) {
  const interrupt = resolveInterrupt(escrow);

  if (interrupt) {
    return (
      <ol className={cn("flex items-center gap-3 overflow-x-auto", className)}>
        {interrupt.precedingNodes.map((n, idx) => (
          <StepperNode key={n.label} node={n} index={idx} />
        ))}
        <li
          data-state="interrupt"
          className="flex flex-col items-center gap-1 text-error"
        >
          <span className="inline-flex h-6 w-6 items-center justify-center rounded-full border border-error bg-error-container text-on-error-container">
            <AlertCircle className="size-3.5" aria-hidden="true" />
          </span>
          <span className="text-label-md font-semibold">{interrupt.label}</span>
          {interrupt.timestamp && (
            <span className="text-label-sm text-on-surface-variant">
              {formatTimestamp(interrupt.timestamp)}
            </span>
          )}
        </li>
      </ol>
    );
  }

  const nodes = resolveNodes(escrow);
  return (
    <ol className={cn("flex items-center gap-3 overflow-x-auto", className)}>
      {nodes.map((n, idx) => (
        <StepperNode key={n.label} node={n} index={idx} last={idx === nodes.length - 1} />
      ))}
    </ol>
  );
}

function StepperNode({
  node,
  index,
  last,
}: {
  node: ResolvedNode;
  index: number;
  last?: boolean;
}) {
  return (
    <>
      <li
        data-state={node.state}
        aria-current={node.state === "current" ? "step" : undefined}
        className={cn(
          "flex flex-col items-center gap-1",
          node.state === "complete" && "text-on-tertiary-container",
          node.state === "current" && "text-primary",
          node.state === "upcoming" && "text-on-surface-variant",
        )}
      >
        <span
          className={cn(
            "inline-flex h-6 w-6 items-center justify-center rounded-full border text-label-md",
            node.state === "complete" &&
              "border-tertiary bg-tertiary text-on-tertiary",
            node.state === "current" && "border-primary text-primary",
            node.state === "upcoming" &&
              "border-outline-variant text-on-surface-variant",
          )}
        >
          {node.state === "complete" ? (
            <CheckCircle2 className="size-3.5" aria-hidden="true" />
          ) : (
            index + 1
          )}
        </span>
        <span className="text-label-md font-semibold">{node.label}</span>
        {node.timestamp && (
          <span className="text-label-sm text-on-surface-variant">
            {formatTimestamp(node.timestamp)}
          </span>
        )}
      </li>
      {!last && (
        <span
          className="mx-1 h-px w-6 bg-outline-variant"
          aria-hidden="true"
        />
      )}
    </>
  );
}
```

**Important note for implementer:** `@/components/ui/icons` re-exports lucide-react icons. Verify `CheckCircle2` + `AlertCircle` are already exported; if not, add them to `icons.ts`. Grep: `grep "CheckCircle2\|AlertCircle" frontend/src/components/ui/icons.ts`.

Run — Expected: PASS (7 tests).

- [ ] **3.9: Commit**

```bash
git add frontend/src/components/escrow/EscrowStepper.tsx frontend/src/components/escrow/EscrowStepper.test.tsx frontend/src/components/ui/icons.ts
git commit -m "feat(escrow): EscrowStepper with 3-node progress + terminal-state interrupt"
```

### Step 3.4: Full suite sanity

- [ ] **3.10: Run full suite**

```bash
cd frontend && npm run test
```

Expected: baseline + Task 1/2 + 22 new (10 chip + 5 deadline + 7 stepper), all green.

---

## Task 4: Per-state cards + `EscrowStepCard` dispatcher

**Spec reference:** §3.2 (dispatcher), §3.3 (per-state copy table).

**Goal:** Ship all 6 per-state cards and the dispatcher that routes to the right one. Each card renders role-aware content per the spec's §3.3 copy table. Per-state test files verify copy, CTAs, and role branches.

### Files

- Create: `frontend/src/components/escrow/EscrowStepCard.tsx` + `.test.tsx`
- Create: `frontend/src/components/escrow/state/PendingStateCard.tsx` + `.test.tsx`
- Create: `frontend/src/components/escrow/state/TransferPendingStateCard.tsx` + `.test.tsx`
- Create: `frontend/src/components/escrow/state/CompletedStateCard.tsx` + `.test.tsx`
- Create: `frontend/src/components/escrow/state/DisputedStateCard.tsx` + `.test.tsx`
- Create: `frontend/src/components/escrow/state/FrozenStateCard.tsx` + `.test.tsx`
- Create: `frontend/src/components/escrow/state/ExpiredStateCard.tsx` + `.test.tsx`

### Shared types used by every state card

Add the following shared type to the top of each state-card file (or extract to a shared barrel file if the implementer prefers — both are acceptable):

```typescript
import type { EscrowStatusResponse } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";

export interface StateCardProps {
  escrow: EscrowStatusResponse;
  role: EscrowChipRole;
}
```

### Step 4.1: `PendingStateCard`

- [ ] **4.1: Write `PendingStateCard.test.tsx` (RED)**

```typescript
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { PendingStateCard } from "./PendingStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("PendingStateCard", () => {
  it("shows seller copy + dispute link", () => {
    renderWithProviders(
      <PendingStateCard escrow={fakeEscrow({ state: "ESCROW_PENDING" })} role="seller" />,
    );
    expect(screen.getByText(/awaiting payment from kira swansong/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /file a dispute/i })).toHaveAttribute(
      "href",
      "/auction/7/escrow/dispute",
    );
  });
  it("shows winner pay-escrow copy + find-terminal link", () => {
    renderWithProviders(
      <PendingStateCard escrow={fakeEscrow({ state: "ESCROW_PENDING", finalBidAmount: 5000 })} role="winner" />,
    );
    expect(screen.getByText(/pay l\$ 5,000/i)).toBeInTheDocument();
    expect(screen.getByText(/at an slpa terminal in-world/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /file a dispute/i })).toBeInTheDocument();
  });
  it("renders deadline badge", () => {
    renderWithProviders(
      <PendingStateCard escrow={fakeEscrow({ state: "ESCROW_PENDING" })} role="winner" />,
    );
    // Badge is data-urgency — just assert presence
    expect(document.querySelector("[data-urgency]")).toBeInTheDocument();
  });
});
```

- [ ] **4.2: Create `PendingStateCard.tsx` (GREEN)**

```typescript
import Link from "next/link";
import type { StateCardProps } from "./types";
import { EscrowDeadlineBadge } from "@/components/escrow/EscrowDeadlineBadge";
import { Button } from "@/components/ui/Button";

export function PendingStateCard({ escrow, role }: StateCardProps) {
  const { auctionId, finalBidAmount, paymentDeadline, counterparty } = escrow;
  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft">
      <div className="text-label-sm uppercase tracking-wide text-on-surface-variant">
        {role === "seller" ? "Seller" : "Winner"}
      </div>
      {role === "seller" ? (
        <>
          <h2 className="mt-1 text-title-md text-on-surface">
            Awaiting payment from {counterparty.displayName}
          </h2>
          <p className="mt-2 text-body-md text-on-surface-variant">
            Payment deadline {new Date(paymentDeadline).toLocaleString()}. If
            they don't pay by the deadline you'll be able to re-list the
            auction once the escrow expires.
          </p>
        </>
      ) : (
        <>
          <h2 className="mt-1 text-title-md text-on-surface">
            Pay L$ {finalBidAmount.toLocaleString()}
          </h2>
          <p className="mt-2 text-body-md text-on-surface-variant">
            at an SLPA terminal in-world. Your winning bid needs to land by{" "}
            {new Date(paymentDeadline).toLocaleString()}.
          </p>
        </>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-3">
        {role === "winner" && (
          <Button variant="primary" size="md" disabled>
            Find a terminal
          </Button>
          /* DEFERRED_WORK entry covers the real locator. Disabled for now. */
        )}
        <Link
          href={`/auction/${auctionId}/escrow/dispute`}
          className="text-label-lg text-primary hover:underline"
        >
          File a dispute
        </Link>
      </div>

      <div className="mt-3">
        <EscrowDeadlineBadge deadline={paymentDeadline} />
      </div>
    </div>
  );
}
```

Create `types.ts` in the state/ dir if barrel-extracting:

```typescript
// frontend/src/components/escrow/state/types.ts
import type { EscrowStatusResponse } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";

export interface StateCardProps {
  escrow: EscrowStatusResponse;
  role: EscrowChipRole;
}
```

- [ ] **4.3: Commit**

```bash
git add frontend/src/components/escrow/state/PendingStateCard.tsx \
        frontend/src/components/escrow/state/PendingStateCard.test.tsx \
        frontend/src/components/escrow/state/types.ts
git commit -m "feat(escrow): PendingStateCard with seller/winner copy + dispute link"
```

### Step 4.2: `TransferPendingStateCard`

- [ ] **4.4: Write `TransferPendingStateCard.test.tsx` (RED)**

Cover: seller numbered recipe renders (5 `<ol>` items), copy-winner-name button present, winner waiting guidance + "what you can do" block renders, post-confirmation (transferConfirmedAt set) shows "Payout pending" text for both roles and hides dispute link per spec §3.3.

```typescript
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import userEvent from "@testing-library/user-event";
import { TransferPendingStateCard } from "./TransferPendingStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("TransferPendingStateCard", () => {
  it("shows 5-step recipe for seller pre-confirmation", () => {
    renderWithProviders(
      <TransferPendingStateCard
        escrow={fakeEscrow({ state: "TRANSFER_PENDING", transferConfirmedAt: null })}
        role="seller"
      />,
    );
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(5);
    expect(items[0]).toHaveTextContent(/about land/i);
    expect(items[1]).toHaveTextContent(/sell land/i);
    expect(items[2]).toHaveTextContent(/kira swansong/i);
    expect(items[3]).toHaveTextContent(/l\$0/i);
    expect(items[4]).toHaveTextContent(/confirm/i);
  });

  it("seller copy-winner-name button copies slAvatarName to clipboard", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const user = userEvent.setup();
    renderWithProviders(
      <TransferPendingStateCard
        escrow={fakeEscrow({ state: "TRANSFER_PENDING", transferConfirmedAt: null })}
        role="seller"
      />,
    );
    await user.click(screen.getByRole("button", { name: /copy winner name/i }));
    expect(writeText).toHaveBeenCalledWith("Kira Swansong");
  });

  it("shows winner guidance card pre-confirmation", () => {
    renderWithProviders(
      <TransferPendingStateCard
        escrow={fakeEscrow({ state: "TRANSFER_PENDING", transferConfirmedAt: null })}
        role="winner"
      />,
    );
    expect(screen.getByText(/waiting for seller to transfer/i)).toBeInTheDocument();
    expect(screen.getByText(/what you can do/i)).toBeInTheDocument();
  });

  it("shows payout-pending copy post-confirmation for both roles", () => {
    const escrow = fakeEscrow({
      state: "TRANSFER_PENDING",
      transferConfirmedAt: "2026-05-02T10:00:00Z",
    });
    const { rerender } = renderWithProviders(
      <TransferPendingStateCard escrow={escrow} role="seller" />,
    );
    expect(screen.getByText(/ownership transferred/i)).toBeInTheDocument();
    expect(screen.getByText(/finalizing the transaction/i)).toBeInTheDocument();
    // Dispute link hidden post-confirmation per spec §3.3
    expect(screen.queryByRole("link", { name: /file a dispute/i })).not.toBeInTheDocument();

    rerender(<TransferPendingStateCard escrow={escrow} role="winner" />);
    expect(screen.getByText(/finalizing the transaction/i)).toBeInTheDocument();
  });
});
```

- [ ] **4.5: Create `TransferPendingStateCard.tsx` (GREEN)**

```typescript
"use client";

import Link from "next/link";
import { useState } from "react";
import type { StateCardProps } from "./types";
import { EscrowDeadlineBadge } from "@/components/escrow/EscrowDeadlineBadge";
import { Button } from "@/components/ui/Button";

export function TransferPendingStateCard({ escrow, role }: StateCardProps) {
  const { auctionId, counterparty, transferConfirmedAt, transferDeadline } = escrow;

  // Post-confirmation sub-state — both roles see the same copy
  if (transferConfirmedAt) {
    return (
      <div className="rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft">
        <div className="text-label-sm uppercase tracking-wide text-on-surface-variant">
          Payout pending
        </div>
        <h2 className="mt-1 text-title-md text-on-surface">
          Ownership transferred to the winner at{" "}
          {new Date(transferConfirmedAt).toLocaleString()}
        </h2>
        <p className="mt-2 text-body-md text-on-surface-variant">
          Finalizing the transaction — payout is dispatching now.
        </p>
      </div>
    );
  }

  // Pre-confirmation — role-aware
  if (role === "seller") {
    return <SellerRecipeCard escrow={escrow} />;
  }
  return <WinnerWaitingCard escrow={escrow} />;
}

function SellerRecipeCard({ escrow }: { escrow: StateCardProps["escrow"] }) {
  const { auctionId, counterparty, transferDeadline } = escrow;
  const [copied, setCopied] = useState(false);

  const onCopy = async () => {
    await navigator.clipboard.writeText(counterparty.slAvatarName);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft">
      <div className="text-label-sm uppercase tracking-wide text-on-surface-variant">Seller</div>
      <h2 className="mt-1 text-title-md text-on-surface">
        Transfer parcel to {counterparty.displayName}
      </h2>
      <ol className="mt-3 list-decimal space-y-2 pl-5 text-body-md text-on-surface-variant">
        <li>Right-click the parcel in-world → <strong>About Land</strong></li>
        <li>Click <strong>Sell Land</strong></li>
        <li>
          Set "Sell to:" → <strong>{counterparty.slAvatarName}</strong>
        </li>
        <li>Set price: <strong>L$0</strong></li>
        <li>Confirm. The backend detects the ownership change within 5 minutes.</li>
      </ol>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button variant="primary" size="md" onClick={onCopy}>
          {copied ? "Copied!" : "Copy winner name"}
        </Button>
        <Link
          href={`/auction/${auctionId}/escrow/dispute`}
          className="text-label-lg text-primary hover:underline"
        >
          File a dispute
        </Link>
      </div>
      {transferDeadline && (
        <div className="mt-3">
          <EscrowDeadlineBadge deadline={transferDeadline} />
        </div>
      )}
    </div>
  );
}

function WinnerWaitingCard({ escrow }: { escrow: StateCardProps["escrow"] }) {
  const { auctionId, counterparty, transferDeadline } = escrow;
  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft">
      <div className="text-label-sm uppercase tracking-wide text-on-surface-variant">Winner</div>
      <h2 className="mt-1 text-title-md text-on-surface">
        Waiting for seller to transfer the parcel
      </h2>
      <p className="mt-2 text-body-md text-on-surface-variant">
        Typical completion is under 24 hours. You'll see this flip to Complete
        automatically within 5 min of the transfer.
      </p>
      <div className="mt-4 rounded-md bg-surface-container p-4 text-body-sm text-on-surface-variant">
        <div className="font-semibold text-on-surface">What you can do:</div>
        <ul className="mt-2 list-disc space-y-1 pl-5">
          <li>Wait — most transfers finish in under 24 hours</li>
          <li>Message {counterparty.displayName} in-world if stalled {">"}24h</li>
          <li>File a dispute if it's been more than 48 hours</li>
        </ul>
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button variant="secondary" size="md" disabled>
          Message {counterparty.displayName}
        </Button>
        {/* DEFERRED — Epic 09 notifications. */}
        <Link
          href={`/auction/${auctionId}/escrow/dispute`}
          className="text-label-lg text-primary hover:underline"
        >
          File a dispute
        </Link>
      </div>
      {transferDeadline && (
        <div className="mt-3">
          <EscrowDeadlineBadge deadline={transferDeadline} />
        </div>
      )}
    </div>
  );
}
```

- [ ] **4.6: Run test + commit**

Run: `npx vitest run src/components/escrow/state/TransferPendingStateCard.test.tsx` — Expected: PASS (4 tests).

```bash
git add frontend/src/components/escrow/state/TransferPendingStateCard.tsx frontend/src/components/escrow/state/TransferPendingStateCard.test.tsx
git commit -m "feat(escrow): TransferPendingStateCard with seller recipe + winner guidance + post-confirm branch"
```

### Step 4.3: Remaining state cards (`Completed`, `Disputed`, `Frozen`, `Expired`)

Follow the same TDD pattern for each. Each card is ~50-100 LoC — copy from the spec's §3.3 table verbatim.

- [ ] **4.7: `CompletedStateCard`**

**Seller copy:** "Payout of L$ `{payoutAmt}` sent. Commission L$ `{commissionAmt}`. Completed `{completedAt}`."
**Winner copy:** "Parcel transferred. You're the owner of `{parcelName}` in `{region}`. Completed `{completedAt}`."

Both variants show `completedAt` formatted as a locale datetime. No CTAs. Commit: `feat(escrow): CompletedStateCard`.

- [ ] **4.8: `DisputedStateCard`**

Both roles see the same copy: "Dispute filed `{disputedAt}`. Category: `{disputeReasonCategory}`. Reason: `{disputeDescription}`. SLPA is reviewing this transaction. Expect a response within 48 hours."

No CTAs. Commit: `feat(escrow): DisputedStateCard`.

- [ ] **4.9: `FrozenStateCard`**

Default copy: "Escrow frozen `{frozenAt}`: `{freezeReason}`. Your L$ will be refunded automatically. SLPA has flagged this auction for review."

Softer copy when `freezeReason === "WORLD_API_PERSISTENT_FAILURE"`: "We couldn't verify parcel ownership repeatedly; this is likely a transient issue and SLPA will re-check manually."

No CTAs. Commit: `feat(escrow): FrozenStateCard with WORLD_API_PERSISTENT_FAILURE softer copy`.

- [ ] **4.10: `ExpiredStateCard` — branches on `fundedAt`**

```typescript
export function ExpiredStateCard({ escrow, role }: StateCardProps) {
  const { fundedAt, finalBidAmount } = escrow;
  if (fundedAt == null) {
    // Payment timeout — winner never paid
    return role === "seller" ? <SellerPaymentTimeout /> : <WinnerPaymentTimeout />;
  }
  // Transfer timeout — seller never transferred, refund queued
  return role === "seller" ? (
    <SellerTransferTimeout amount={finalBidAmount} />
  ) : (
    <WinnerTransferTimeout amount={finalBidAmount} />
  );
}
```

Copy table (from spec §3.3):

- Seller, `fundedAt == null`: "Escrow expired because the winner didn't pay by the deadline."
- Seller, `fundedAt != null`: "Escrow expired because the transfer wasn't completed by the deadline. Refund of L$ `{finalBidAmount}` has been queued to the winner."
- Winner, `fundedAt == null`: "You didn't pay by the deadline. The auction has expired."
- Winner, `fundedAt != null`: "Seller didn't complete the transfer. Your L$ `{finalBidAmount}` refund has been queued and should land in your SL wallet shortly."

Test file covers all 4 permutations. Commit: `feat(escrow): ExpiredStateCard with fundedAt branching for payment vs transfer timeout`.

### Step 4.4: `EscrowStepCard` dispatcher

- [ ] **4.11: Write `EscrowStepCard.test.tsx` (RED)**

```typescript
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowStepCard } from "./EscrowStepCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("EscrowStepCard dispatcher", () => {
  it("routes ESCROW_PENDING to PendingStateCard", () => {
    renderWithProviders(
      <EscrowStepCard escrow={fakeEscrow({ state: "ESCROW_PENDING" })} role="winner" />,
    );
    expect(screen.getByText(/pay l\$/i)).toBeInTheDocument();
  });
  it("routes TRANSFER_PENDING to TransferPendingStateCard", () => {
    renderWithProviders(
      <EscrowStepCard escrow={fakeEscrow({ state: "TRANSFER_PENDING" })} role="seller" />,
    );
    expect(screen.getByText(/transfer parcel/i)).toBeInTheDocument();
  });
  it("routes FUNDED to TransferPendingStateCard (transient)", () => {
    renderWithProviders(
      <EscrowStepCard escrow={fakeEscrow({ state: "FUNDED" })} role="seller" />,
    );
    expect(screen.getByText(/transfer parcel/i)).toBeInTheDocument();
  });
  it("routes COMPLETED to CompletedStateCard", () => {
    renderWithProviders(
      <EscrowStepCard escrow={fakeEscrow({ state: "COMPLETED", completedAt: new Date().toISOString() })} role="winner" />,
    );
    expect(screen.getByText(/parcel transferred/i)).toBeInTheDocument();
  });
  it("routes DISPUTED, FROZEN, EXPIRED to their cards", () => {
    const { rerender } = renderWithProviders(
      <EscrowStepCard escrow={fakeEscrow({ state: "DISPUTED", disputedAt: new Date().toISOString() })} role="seller" />,
    );
    expect(screen.getByText(/dispute filed/i)).toBeInTheDocument();
    rerender(<EscrowStepCard escrow={fakeEscrow({ state: "FROZEN", frozenAt: new Date().toISOString() })} role="seller" />);
    expect(screen.getByText(/escrow frozen/i)).toBeInTheDocument();
    rerender(<EscrowStepCard escrow={fakeEscrow({ state: "EXPIRED", expiredAt: new Date().toISOString() })} role="seller" />);
    expect(screen.getByText(/escrow expired/i)).toBeInTheDocument();
  });
});
```

- [ ] **4.12: Create `EscrowStepCard.tsx` (GREEN)**

```typescript
import type { EscrowStatusResponse } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";
import { PendingStateCard } from "./state/PendingStateCard";
import { TransferPendingStateCard } from "./state/TransferPendingStateCard";
import { CompletedStateCard } from "./state/CompletedStateCard";
import { DisputedStateCard } from "./state/DisputedStateCard";
import { FrozenStateCard } from "./state/FrozenStateCard";
import { ExpiredStateCard } from "./state/ExpiredStateCard";

export interface EscrowStepCardProps {
  escrow: EscrowStatusResponse;
  role: EscrowChipRole;
}

export function EscrowStepCard({ escrow, role }: EscrowStepCardProps) {
  switch (escrow.state) {
    case "ESCROW_PENDING":
      return <PendingStateCard escrow={escrow} role={role} />;
    case "FUNDED":
    case "TRANSFER_PENDING":
      return <TransferPendingStateCard escrow={escrow} role={role} />;
    case "COMPLETED":
      return <CompletedStateCard escrow={escrow} role={role} />;
    case "DISPUTED":
      return <DisputedStateCard escrow={escrow} role={role} />;
    case "FROZEN":
      return <FrozenStateCard escrow={escrow} role={role} />;
    case "EXPIRED":
      return <ExpiredStateCard escrow={escrow} role={role} />;
  }
}
```

- [ ] **4.13: Run test + commit**

Commit: `feat(escrow): EscrowStepCard state dispatcher routing to 6 per-state cards`.

### Step 4.5: Full suite sanity

- [ ] **4.14: Run full suite**

```bash
cd frontend && npm run test
```

Expected: all prior tests + ~25-35 new (varies by per-state card test count).

---

## Task 5: Escrow page shell — RSC + client + WS

**Spec reference:** §2 (architecture), §7 (WS handling).

**Goal:** Ship the `/auction/[id]/escrow` route end-to-end. RSC shell with auth gate. Client component with React Query + WS subscription + reconnect reconcile. Integration test exercises the full page render + envelope-to-invalidate flow.

### Files

- Create: `frontend/src/app/auction/[id]/escrow/page.tsx`
- Create: `frontend/src/app/auction/[id]/escrow/EscrowPageClient.tsx`
- Create: `frontend/src/app/auction/[id]/escrow/EscrowPageClient.test.tsx`
- Create: `frontend/src/app/auction/[id]/escrow/page.integration.test.tsx`
- Create: `frontend/src/components/escrow/EscrowPageLayout.tsx`
- Create: `frontend/src/components/escrow/EscrowPageHeader.tsx`
- Create: `frontend/src/components/escrow/EscrowPageSkeleton.tsx`
- Create: `frontend/src/components/escrow/EscrowPageError.tsx`
- Create: `frontend/src/components/escrow/EscrowPageEmpty.tsx`

### Step 5.1: Page-chrome primitives

- [ ] **5.1: Create `EscrowPageLayout.tsx`**

```typescript
import type { ReactNode } from "react";
import Link from "next/link";
import { ChevronLeft } from "@/components/ui/icons";

export interface EscrowPageLayoutProps {
  auctionId: number;
  children: ReactNode;
}

export function EscrowPageLayout({ auctionId, children }: EscrowPageLayoutProps) {
  return (
    <main className="mx-auto max-w-2xl px-4 py-8">
      <Link
        href={`/auction/${auctionId}`}
        className="inline-flex items-center gap-1 text-label-md text-on-surface-variant hover:text-on-surface"
      >
        <ChevronLeft className="size-4" aria-hidden="true" />
        Back to auction
      </Link>
      <div className="mt-6 space-y-6">{children}</div>
    </main>
  );
}
```

- [ ] **5.2: Create `EscrowPageHeader.tsx`**

```typescript
import type { EscrowStatusResponse } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";
import { EscrowChip } from "@/components/escrow/EscrowChip";

export interface EscrowPageHeaderProps {
  escrow: EscrowStatusResponse;
  role: EscrowChipRole;
}

export function EscrowPageHeader({ escrow, role }: EscrowPageHeaderProps) {
  return (
    <header className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <div className="text-label-sm uppercase tracking-wide text-on-surface-variant">
          Escrow · {role}
        </div>
        <h1 className="mt-1 text-headline-sm text-on-surface">{escrow.parcelName}</h1>
        <div className="text-body-sm text-on-surface-variant">
          {escrow.region} · L$ {escrow.finalBidAmount.toLocaleString()} final
        </div>
      </div>
      <EscrowChip
        state={escrow.state}
        transferConfirmedAt={escrow.transferConfirmedAt}
        role={role}
        size="md"
      />
    </header>
  );
}
```

- [ ] **5.3: Create the three UI-state primitives**

```typescript
// EscrowPageSkeleton.tsx
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";

export function EscrowPageSkeleton() {
  return <LoadingSpinner label="Loading escrow status..." />;
}

// EscrowPageError.tsx
import { AlertCircle } from "@/components/ui/icons";
import { EmptyState } from "@/components/ui/EmptyState";

export interface EscrowPageErrorProps {
  error: Error;
}

export function EscrowPageError({ error }: EscrowPageErrorProps) {
  return (
    <EmptyState
      icon={AlertCircle}
      headline="Could not load escrow status"
      description={error.message}
    />
  );
}

// EscrowPageEmpty.tsx
import Link from "next/link";
import { FileX } from "@/components/ui/icons";
import { EmptyState } from "@/components/ui/EmptyState";

export interface EscrowPageEmptyProps {
  auctionId: number;
}

export function EscrowPageEmpty({ auctionId }: EscrowPageEmptyProps) {
  return (
    <EmptyState
      icon={FileX}
      headline="No escrow for this auction"
      description="Either this auction hasn't ended with a winner yet, or no escrow was created."
    >
      <Link href={`/auction/${auctionId}`} className="text-primary hover:underline">
        Back to auction
      </Link>
    </EmptyState>
  );
}
```

Commit: `feat(escrow): escrow page chrome + three UI state primitives`.

### Step 5.2: `EscrowPageClient`

- [ ] **5.4: Create `EscrowPageClient.tsx`**

```typescript
"use client";

import { useCallback, useEffect, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";
import type { AuctionTopicEnvelope } from "@/types/auction";
import { getEscrowStatus } from "@/lib/api/escrow";
import { isApiError } from "@/lib/api";
import {
  useConnectionState,
  useStompSubscription,
} from "@/lib/ws/hooks";
import { EscrowPageLayout } from "@/components/escrow/EscrowPageLayout";
import { EscrowPageHeader } from "@/components/escrow/EscrowPageHeader";
import { EscrowPageSkeleton } from "@/components/escrow/EscrowPageSkeleton";
import { EscrowPageError } from "@/components/escrow/EscrowPageError";
import { EscrowPageEmpty } from "@/components/escrow/EscrowPageEmpty";
import { EscrowStepper } from "@/components/escrow/EscrowStepper";
import { EscrowStepCard } from "@/components/escrow/EscrowStepCard";
import { ReconnectingBanner } from "@/components/auction/ReconnectingBanner";

export interface EscrowPageClientProps {
  auctionId: number;
  role: EscrowChipRole;
}

export const escrowKey = (id: number) => ["escrow", id] as const;

export function EscrowPageClient({ auctionId, role }: EscrowPageClientProps) {
  const queryClient = useQueryClient();
  const connectionState = useConnectionState();
  const wasReconnectingRef = useRef(false);

  const { data: escrow, isLoading, error } = useQuery({
    queryKey: escrowKey(auctionId),
    queryFn: () => getEscrowStatus(auctionId),
    refetchOnWindowFocus: true,
  });

  useStompSubscription<AuctionTopicEnvelope>(
    `/topic/auction/${auctionId}`,
    useCallback(
      (env) => {
        if (env.type.startsWith("ESCROW_")) {
          queryClient.invalidateQueries({ queryKey: escrowKey(auctionId) });
        }
      },
      [auctionId, queryClient],
    ),
  );

  // Reconnect reconcile — mirror of AuctionDetailClient pattern
  useEffect(() => {
    const status = connectionState.status;
    if (status === "connected" && wasReconnectingRef.current) {
      queryClient.invalidateQueries({ queryKey: escrowKey(auctionId) });
      wasReconnectingRef.current = false;
    }
    if (status === "reconnecting" || status === "error") {
      wasReconnectingRef.current = true;
    }
  }, [connectionState.status, queryClient, auctionId]);

  return (
    <EscrowPageLayout auctionId={auctionId}>
      {isLoading && <EscrowPageSkeleton />}
      {error && !isApiErrorNotFound(error) && <EscrowPageError error={error as Error} />}
      {error && isApiErrorNotFound(error) && <EscrowPageEmpty auctionId={auctionId} />}
      {escrow && (
        <>
          <EscrowPageHeader escrow={escrow} role={role} />
          <EscrowStepper escrow={escrow} />
          <EscrowStepCard escrow={escrow} role={role} />
        </>
      )}
      <ReconnectingBanner />
    </EscrowPageLayout>
  );
}

function isApiErrorNotFound(err: unknown): boolean {
  return isApiError(err) && err.status === 404;
}
```

- [ ] **5.5: Commit**

```bash
git add frontend/src/app/auction/[id]/escrow/EscrowPageClient.tsx
git commit -m "feat(escrow): EscrowPageClient with useQuery + WS subscription + reconnect reconcile"
```

### Step 5.3: RSC shell

- [ ] **5.6: Create `page.tsx`**

```typescript
import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getCurrentUser } from "@/lib/auth/session";
import { getAuction } from "@/lib/api/auctions";
import { isApiError } from "@/lib/api";
import { EscrowPageClient } from "./EscrowPageClient";

export const metadata: Metadata = { title: "Escrow" };

interface Props {
  params: Promise<{ id: string }>;
}

export default async function EscrowStatusPage({ params }: Props) {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isInteger(auctionId) || auctionId <= 0) notFound();

  const user = await getCurrentUser();
  if (!user) redirect(`/login?returnTo=/auction/${auctionId}/escrow`);

  let auction;
  try {
    auction = await getAuction(auctionId);
  } catch (err) {
    if (isApiError(err) && err.status === 404) notFound();
    throw err;
  }

  const isSeller = auction.sellerId === user.id;
  const isWinner = auction.winnerUserId === user.id;
  if (!isSeller && !isWinner) redirect(`/auction/${auctionId}`);

  const role = isSeller ? "seller" : "winner";
  return <EscrowPageClient auctionId={auctionId} role={role} />;
}
```

**Note for implementer:** verify `getCurrentUser()` exists at `@/lib/auth/session` or wherever the existing server-side user-fetch helper lives. The auction RSC at `frontend/src/app/auction/[id]/page.tsx` doesn't auth-gate today because that page is public; the escrow page needs the session helper. If `getCurrentUser()` doesn't exist server-side, check `frontend/src/lib/auth/` for the server-compatible fetch (look for something exporting from a file that doesn't have `"use client"`).

- [ ] **5.7: Commit**

```bash
git add frontend/src/app/auction/[id]/escrow/page.tsx
git commit -m "feat(escrow): RSC shell at /auction/[id]/escrow with seller+winner auth gate"
```

### Step 5.4: Integration test

- [ ] **5.8: Create `EscrowPageClient.test.tsx`**

Full envelope-triggered invalidation test. Use the `vi.hoisted` + `vi.mock("@/lib/ws/client")` pattern from `frontend/src/app/auction/[id]/page.integration.test.tsx`. Test scenarios:

1. Happy path ESCROW_PENDING for winner → stepper + pending card renders with "Pay L$..." copy.
2. Loading state shows spinner.
3. 404 shows `EscrowPageEmpty`.
4. 403 shows `EscrowPageError`.
5. `ESCROW_FUNDED` envelope invalidates cache → page re-fetches and renders TRANSFER_PENDING state.
6. Reconnect edge invalidates cache.

Template based on `AuctionDetailClient` integration test pattern:

```typescript
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor, act } from "@/test/render";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import { EscrowPageClient } from "./EscrowPageClient";
import { fakeEscrow, fakeEscrowEnvelope } from "@/test/fixtures/escrow";

const { subscribeMock, subscribeToConnectionStateMock, getConnectionStateMock } =
  vi.hoisted(() => ({
    subscribeMock: vi.fn(),
    subscribeToConnectionStateMock: vi.fn(),
    getConnectionStateMock: vi.fn(() => ({ status: "connected" })),
  }));

vi.mock("@/lib/ws/client", () => ({
  subscribe: (...args: unknown[]) => subscribeMock(...args),
  subscribeToConnectionState: (listener: (s: { status: string }) => void) =>
    subscribeToConnectionStateMock(listener),
  getConnectionState: getConnectionStateMock,
}));

describe("EscrowPageClient", () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    subscribeMock.mockReturnValue(() => {});
    subscribeToConnectionStateMock.mockReset();
    subscribeToConnectionStateMock.mockReturnValue(() => {});
  });

  it("renders pending state for winner", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionId: 7, state: "ESCROW_PENDING" })),
      ),
    );
    renderWithProviders(<EscrowPageClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });
    expect(await screen.findByText(/pay l\$/i)).toBeInTheDocument();
  });

  it("invalidates cache on ESCROW_FUNDED envelope", async () => {
    let pendingResponse = fakeEscrow({ auctionId: 7, state: "ESCROW_PENDING" });
    let fundedResponse = fakeEscrow({
      auctionId: 7,
      state: "TRANSFER_PENDING",
      fundedAt: new Date().toISOString(),
      transferDeadline: new Date(Date.now() + 72 * 3600 * 1000).toISOString(),
    });
    let callCount = 0;
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () => {
        callCount += 1;
        return HttpResponse.json(callCount === 1 ? pendingResponse : fundedResponse);
      }),
    );

    renderWithProviders(<EscrowPageClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });

    await screen.findByText(/pay l\$/i);

    // Extract handler from mock
    const [, handler] = subscribeMock.mock.calls[0];
    act(() => {
      handler(fakeEscrowEnvelope("ESCROW_FUNDED", { auctionId: 7 }));
    });

    await waitFor(() => {
      expect(screen.getByText(/waiting for seller/i)).toBeInTheDocument();
    });
  });

  it("shows empty state on 404", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json({ status: 404, code: "ESCROW_NOT_FOUND" }, { status: 404 }),
      ),
    );
    renderWithProviders(<EscrowPageClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });
    expect(await screen.findByText(/no escrow for this auction/i)).toBeInTheDocument();
  });

  it("shows error state on 403", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json({ status: 403, code: "ESCROW_FORBIDDEN" }, { status: 403 }),
      ),
    );
    renderWithProviders(<EscrowPageClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });
    expect(await screen.findByText(/could not load escrow status/i)).toBeInTheDocument();
  });
});
```

- [ ] **5.9: Run + commit**

```bash
git add frontend/src/app/auction/[id]/escrow/EscrowPageClient.test.tsx
git commit -m "test(escrow): integration test for EscrowPageClient — render + envelope invalidation + auth paths"
```

### Step 5.5: Manual verification

- [ ] **5.10: Manual smoke test** (optional; integration test covers functional correctness)

```bash
cd frontend && npm run dev
```

With a seeded escrow in dev (use sub-spec 1's `POST /api/v1/dev/escrow/{id}/simulate-payment` helper as needed), navigate to `http://localhost:3000/auction/{id}/escrow` as both seller and winner. Confirm:
- Stepper renders with Payment/Transfer/Complete.
- Step card matches the state.
- Refreshing or triggering a state change on the backend updates the page via WS.
- Non-party user is redirected to `/auction/{id}`.

Ctrl-C when satisfied.

### Step 5.6: Full suite

- [ ] **5.11: Run full suite**

```bash
cd frontend && npm run test
```

Expected all new tests passing.

---

## Task 6: Dispute subroute

**Spec reference:** §6 (entire section).

**Goal:** Ship `/auction/[id]/escrow/dispute` as a full-page form. RSC auth gate. Client-side state gate (redirect to `/auction/[id]/escrow` if escrow is in a terminal state). React Hook Form + Zod. Handle 409 `ESCROW_INVALID_TRANSITION` gracefully.

### Files

- Create: `frontend/src/app/auction/[id]/escrow/dispute/page.tsx`
- Create: `frontend/src/app/auction/[id]/escrow/dispute/DisputeFormClient.tsx`
- Create: `frontend/src/app/auction/[id]/escrow/dispute/DisputeFormClient.test.tsx`

### Step 6.1: RSC shell

- [ ] **6.1: Create `page.tsx`**

Mirror of the escrow RSC shell; only difference is the routing target on auth failure.

```typescript
import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getCurrentUser } from "@/lib/auth/session";
import { getAuction } from "@/lib/api/auctions";
import { isApiError } from "@/lib/api";
import { DisputeFormClient } from "./DisputeFormClient";

export const metadata: Metadata = { title: "File a dispute" };

interface Props {
  params: Promise<{ id: string }>;
}

export default async function DisputePage({ params }: Props) {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isInteger(auctionId) || auctionId <= 0) notFound();

  const user = await getCurrentUser();
  if (!user) redirect(`/login?returnTo=/auction/${auctionId}/escrow/dispute`);

  let auction;
  try {
    auction = await getAuction(auctionId);
  } catch (err) {
    if (isApiError(err) && err.status === 404) notFound();
    throw err;
  }

  const isSeller = auction.sellerId === user.id;
  const isWinner = auction.winnerUserId === user.id;
  if (!isSeller && !isWinner) redirect(`/auction/${auctionId}`);

  const role = isSeller ? "seller" : "winner";
  return <DisputeFormClient auctionId={auctionId} role={role} />;
}
```

### Step 6.2: `DisputeFormClient` TDD

- [ ] **6.2: Write `DisputeFormClient.test.tsx` (RED)**

```typescript
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import { DisputeFormClient } from "./DisputeFormClient";
import { fakeEscrow } from "@/test/fixtures/escrow";

const push = vi.fn();
vi.mock("next/navigation", async (orig) => ({
  ...(await orig<typeof import("next/navigation")>()),
  useRouter: () => ({ push }),
}));

describe("DisputeFormClient", () => {
  beforeEach(() => {
    push.mockReset();
  });

  it("renders form for ESCROW_PENDING state", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ state: "ESCROW_PENDING" })),
      ),
    );
    renderWithProviders(<DisputeFormClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });
    expect(await screen.findByRole("combobox", { name: /reason/i })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /description/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /file dispute/i })).toBeInTheDocument();
  });

  it("shows 'can no longer be disputed' for COMPLETED state", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ state: "COMPLETED" })),
      ),
    );
    renderWithProviders(<DisputeFormClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });
    expect(await screen.findByText(/can no longer be disputed/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /file dispute/i })).not.toBeInTheDocument();
  });

  it("validates description min length", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ state: "ESCROW_PENDING" })),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DisputeFormClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });
    await screen.findByRole("combobox", { name: /reason/i });

    await user.selectOptions(screen.getByRole("combobox", { name: /reason/i }), "OTHER");
    await user.type(screen.getByRole("textbox", { name: /description/i }), "too short");
    await user.click(screen.getByRole("button", { name: /file dispute/i }));

    expect(await screen.findByText(/at least 10 characters/i)).toBeInTheDocument();
  });

  it("submits valid form + routes back on success", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ state: "ESCROW_PENDING" })),
      ),
      http.post("*/api/v1/auctions/7/escrow/dispute", () =>
        HttpResponse.json(fakeEscrow({ state: "DISPUTED" })),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DisputeFormClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });
    await screen.findByRole("combobox", { name: /reason/i });

    await user.selectOptions(
      screen.getByRole("combobox", { name: /reason/i }),
      "SELLER_NOT_RESPONSIVE",
    );
    await user.type(
      screen.getByRole("textbox", { name: /description/i }),
      "The seller has not responded for three days",
    );
    await user.click(screen.getByRole("button", { name: /file dispute/i }));

    await waitFor(() => {
      expect(push).toHaveBeenCalledWith("/auction/7/escrow");
    });
  });

  it("routes back to escrow page on 409 ESCROW_INVALID_TRANSITION", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ state: "ESCROW_PENDING" })),
      ),
      http.post("*/api/v1/auctions/7/escrow/dispute", () =>
        HttpResponse.json(
          { status: 409, code: "ESCROW_INVALID_TRANSITION", detail: "too late" },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DisputeFormClient auctionId={7} role="winner" />, {
      auth: "authenticated",
    });
    await screen.findByRole("combobox", { name: /reason/i });

    await user.selectOptions(screen.getByRole("combobox", { name: /reason/i }), "OTHER");
    await user.type(
      screen.getByRole("textbox", { name: /description/i }),
      "0123456789 long enough description",
    );
    await user.click(screen.getByRole("button", { name: /file dispute/i }));

    await waitFor(() => {
      expect(push).toHaveBeenCalledWith("/auction/7/escrow");
    });
  });
});
```

- [ ] **6.3: Create `DisputeFormClient.tsx` (GREEN)**

```typescript
"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  EscrowDisputeReasonCategory,
  EscrowDisputeRequest,
  EscrowStatusResponse,
} from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";
import { fileDispute, getEscrowStatus } from "@/lib/api/escrow";
import { isApiError } from "@/lib/api";
import { escrowKey } from "@/app/auction/[id]/escrow/EscrowPageClient";
import { EscrowPageLayout } from "@/components/escrow/EscrowPageLayout";
import { EscrowPageSkeleton } from "@/components/escrow/EscrowPageSkeleton";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { useToast } from "@/components/ui/Toast";

const disputeSchema = z.object({
  reasonCategory: z.enum([
    "SELLER_NOT_RESPONSIVE",
    "WRONG_PARCEL_TRANSFERRED",
    "PAYMENT_NOT_CREDITED",
    "FRAUD_SUSPECTED",
    "OTHER",
  ]),
  description: z
    .string()
    .min(10, "Please describe the issue (at least 10 characters)")
    .max(2000, "Description is too long (max 2000 characters)"),
});

type DisputeFormValues = z.infer<typeof disputeSchema>;

const REASON_LABELS: Record<EscrowDisputeReasonCategory, string> = {
  SELLER_NOT_RESPONSIVE: "Seller isn't responding",
  WRONG_PARCEL_TRANSFERRED: "Wrong parcel transferred to me",
  PAYMENT_NOT_CREDITED: "I paid but the escrow didn't move to funded",
  FRAUD_SUSPECTED: "I suspect fraud",
  OTHER: "Other / something else",
};

const TERMINAL_STATES = new Set(["COMPLETED", "EXPIRED", "DISPUTED", "FROZEN"]);

export interface DisputeFormClientProps {
  auctionId: number;
  role: EscrowChipRole;
}

export function DisputeFormClient({ auctionId, role }: DisputeFormClientProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const toast = useToast();

  const { data: escrow, isLoading, error } = useQuery({
    queryKey: escrowKey(auctionId),
    queryFn: () => getEscrowStatus(auctionId),
  });

  if (isLoading) {
    return (
      <EscrowPageLayout auctionId={auctionId}>
        <EscrowPageSkeleton />
      </EscrowPageLayout>
    );
  }

  if (error && isApiError(error) && error.status === 404) {
    return (
      <EscrowPageLayout auctionId={auctionId}>
        <NoEscrowPanel auctionId={auctionId} />
      </EscrowPageLayout>
    );
  }

  if (!escrow) {
    return (
      <EscrowPageLayout auctionId={auctionId}>
        <NoEscrowPanel auctionId={auctionId} />
      </EscrowPageLayout>
    );
  }

  if (TERMINAL_STATES.has(escrow.state)) {
    return (
      <EscrowPageLayout auctionId={auctionId}>
        <TerminalStatePanel escrow={escrow} auctionId={auctionId} />
      </EscrowPageLayout>
    );
  }

  return (
    <EscrowPageLayout auctionId={auctionId}>
      <DisputeFormBody
        escrow={escrow}
        auctionId={auctionId}
        role={role}
        onSuccess={() => {
          queryClient.invalidateQueries({ queryKey: escrowKey(auctionId) });
          toast.success("Dispute filed. SLPA staff will review.");
          router.push(`/auction/${auctionId}/escrow`);
        }}
        on409={() => {
          toast.error("This escrow's state changed while you were filing. Please review.");
          router.push(`/auction/${auctionId}/escrow`);
        }}
        onGenericError={(message) => toast.error(`Failed to file dispute: ${message}`)}
      />
    </EscrowPageLayout>
  );
}

function NoEscrowPanel({ auctionId }: { auctionId: number }) {
  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-lowest p-6 text-center">
      <h2 className="text-title-md text-on-surface">No escrow exists for this auction</h2>
      <Link
        href={`/auction/${auctionId}/escrow`}
        className="mt-3 inline-block text-primary hover:underline"
      >
        ← Back to escrow
      </Link>
    </div>
  );
}

function TerminalStatePanel({
  escrow,
  auctionId,
}: {
  escrow: EscrowStatusResponse;
  auctionId: number;
}) {
  const messages: Record<string, string> = {
    DISPUTED: `A dispute was filed on ${escrow.disputedAt ? new Date(escrow.disputedAt).toLocaleString() : "-"}. SLPA is reviewing.`,
    COMPLETED: `Escrow completed on ${escrow.completedAt ? new Date(escrow.completedAt).toLocaleString() : "-"}. If you have a concern, contact support.`,
    EXPIRED: `This escrow is in an EXPIRED state and is no longer active.`,
    FROZEN: `This escrow is in a FROZEN state and is no longer active.`,
  };
  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-lowest p-6">
      <h2 className="text-title-md text-on-surface">
        This escrow can no longer be disputed
      </h2>
      <p className="mt-2 text-body-md text-on-surface-variant">
        {messages[escrow.state]}
      </p>
      <Link
        href={`/auction/${auctionId}/escrow`}
        className="mt-4 inline-block text-primary hover:underline"
      >
        ← Back to escrow
      </Link>
    </div>
  );
}

function DisputeFormBody({
  escrow,
  auctionId,
  role,
  onSuccess,
  on409,
  onGenericError,
}: {
  escrow: EscrowStatusResponse;
  auctionId: number;
  role: EscrowChipRole;
  onSuccess: () => void;
  on409: () => void;
  onGenericError: (message: string) => void;
}) {
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<DisputeFormValues>({
    resolver: zodResolver(disputeSchema),
    defaultValues: { reasonCategory: "OTHER", description: "" },
  });

  const description = watch("description");

  const mutation = useMutation({
    mutationFn: (body: EscrowDisputeRequest) => fileDispute(auctionId, body),
    onSuccess,
    onError: (err) => {
      if (isApiError(err) && err.status === 409 && err.code === "ESCROW_INVALID_TRANSITION") {
        on409();
      } else if (isApiError(err) && err.status === 403) {
        onGenericError("You don't have permission to dispute this escrow.");
      } else {
        onGenericError(err instanceof Error ? err.message : "Unknown error");
      }
    },
  });

  const onSubmit = handleSubmit((values) => {
    mutation.mutate({
      reasonCategory: values.reasonCategory,
      description: values.description,
    });
  });

  return (
    <form onSubmit={onSubmit} className="space-y-5">
      <div className="rounded-md bg-surface-container-low p-4 text-body-sm text-on-surface-variant">
        You're disputing escrow for <strong>{escrow.parcelName}</strong> — currently{" "}
        <strong>{escrow.state}</strong>, you are the <strong>{role}</strong>.
      </div>

      <div>
        <label htmlFor="reasonCategory" className="text-label-lg font-semibold text-on-surface">
          Reason
        </label>
        <select
          id="reasonCategory"
          {...register("reasonCategory")}
          className="mt-2 w-full rounded-md border border-outline-variant bg-surface-container-lowest px-3 py-2 text-body-md"
        >
          {Object.entries(REASON_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {errors.reasonCategory && (
          <FormError message={errors.reasonCategory.message ?? ""} />
        )}
      </div>

      <div>
        <label htmlFor="description" className="text-label-lg font-semibold text-on-surface">
          Description
        </label>
        <textarea
          id="description"
          rows={4}
          {...register("description")}
          className="mt-2 w-full rounded-md border border-outline-variant bg-surface-container-lowest px-3 py-2 text-body-md"
        />
        <div className="mt-1 flex items-center justify-between">
          <span className="text-label-sm text-on-surface-variant">
            {(description ?? "").length} / 2000
          </span>
          {errors.description && (
            <span className="text-label-sm text-error">{errors.description.message}</span>
          )}
        </div>
      </div>

      <div className="flex items-center justify-between">
        <Link
          href={`/auction/${auctionId}/escrow`}
          className="text-label-lg text-on-surface-variant hover:text-on-surface"
        >
          Cancel
        </Link>
        <Button type="submit" variant="primary" size="md" loading={isSubmitting}>
          File dispute
        </Button>
      </div>
    </form>
  );
}
```

- [ ] **6.4: Run tests + commit**

```bash
git add frontend/src/app/auction/[id]/escrow/dispute/
git commit -m "feat(escrow): dispute subroute — RSC shell + form client with state gate + 409 handling"
```

---

## Task 7: Dashboard integration

**Spec reference:** §4.1, §4.2.

**Goal:** `MyBidSummaryRow` + `ListingSummaryRow` render `EscrowChip` + "View escrow →" link when `escrowState` is present on the DTO.

### Files

- Modify: `frontend/src/components/bids/MyBidSummaryRow.tsx` (+ test)
- Modify: `frontend/src/components/listing/ListingSummaryRow.tsx` (+ test)

### Step 7.1: `MyBidSummaryRow`

- [ ] **7.1: Write a new test case in `MyBidSummaryRow.test.tsx`**

Find the existing test file. Add:

```typescript
it("renders escrow chip + view-escrow link when escrowState is set", () => {
  const bid = fakeMyBidSummary({
    auction: {
      ...baseAuction,
      escrowState: "ESCROW_PENDING",
      transferConfirmedAt: null,
    },
  });
  renderWithProviders(<MyBidSummaryRow bid={bid} />);
  expect(screen.getByText(/pay escrow/i)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /view escrow/i })).toHaveAttribute(
    "href",
    `/auction/${bid.auction.id}/escrow`,
  );
});

it("keeps existing view-auction link when escrowState is null", () => {
  const bid = fakeMyBidSummary({
    auction: { ...baseAuction, escrowState: null, transferConfirmedAt: null },
  });
  renderWithProviders(<MyBidSummaryRow bid={bid} />);
  expect(screen.queryByText(/pay escrow/i)).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: /view auction/i })).toBeInTheDocument();
});
```

- [ ] **7.2: Modify `MyBidSummaryRow.tsx`**

Inside the existing component, conditionally render the escrow chip + change the link target:

```typescript
import { EscrowChip } from "@/components/escrow/EscrowChip";

// Inside the row's JSX, next to the existing status chip:
{bid.auction.escrowState != null && (
  <EscrowChip
    state={bid.auction.escrowState}
    transferConfirmedAt={bid.auction.transferConfirmedAt}
    role="winner"
    size="sm"
  />
)}

// Replace the existing "View auction →" link target:
<Link href={
  bid.auction.escrowState != null
    ? `/auction/${bid.auction.id}/escrow`
    : `/auction/${bid.auction.id}`
}>
  {bid.auction.escrowState != null ? "View escrow →" : "View auction →"}
</Link>
```

- [ ] **7.3: Commit**

```bash
git add frontend/src/components/bids/MyBidSummaryRow.tsx frontend/src/components/bids/MyBidSummaryRow.test.tsx
git commit -m "feat(escrow): MyBidSummaryRow shows EscrowChip + view-escrow link when present"
```

### Step 7.2: `ListingSummaryRow`

- [ ] **7.4: Repeat for the seller row**

Same additions to `ListingSummaryRow.tsx`. Chip uses `role="seller"`. Link same pattern.

```bash
git add frontend/src/components/listing/ListingSummaryRow.tsx frontend/src/components/listing/ListingSummaryRow.test.tsx
git commit -m "feat(escrow): ListingSummaryRow shows EscrowChip + view-escrow link when present"
```

### Step 7.3: Full suite

- [ ] **7.5: Run full suite**

```bash
cd frontend && npm run test
```

---

## Task 8: AuctionEndedPanel escrow banner

**Spec reference:** §5 (entire section).

**Goal:** Add the `EscrowBannerForPanel` subcomponent to `AuctionEndedPanel`. Drop the stub "Escrow flow opens in Epic 05" seller-overlay line.

### Files

- Create: `frontend/src/components/escrow/escrowBannerCopy.ts`
- Create: `frontend/src/components/escrow/escrowBannerCopy.test.ts`
- Modify: `frontend/src/components/auction/AuctionEndedPanel.tsx` (+ test additions)

### Step 8.1: `escrowBannerCopy` pure helper — TDD

- [ ] **8.1: Write `escrowBannerCopy.test.ts` (RED)**

```typescript
import { describe, it, expect } from "vitest";
import { escrowBannerCopy, type BannerTone } from "./escrowBannerCopy";

describe("escrowBannerCopy", () => {
  it("ESCROW_PENDING winner = Pay escrow + action tone", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "ESCROW_PENDING",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: null,
    });
    expect(headline).toBe("Pay escrow");
    expect(tone).toBe<BannerTone>("action");
  });

  it("ESCROW_PENDING seller = Escrow pending + waiting tone", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "ESCROW_PENDING",
      role: "seller",
      transferConfirmedAt: null,
      fundedAt: null,
    });
    expect(headline).toBe("Escrow pending");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("TRANSFER_PENDING pre-confirmation winner = Awaiting transfer", () => {
    const { headline } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: "2026-05-01T14:30:00Z",
    });
    expect(headline).toBe("Awaiting transfer");
  });

  it("TRANSFER_PENDING pre-confirmation seller = Transfer parcel", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "seller",
      transferConfirmedAt: null,
      fundedAt: "2026-05-01T14:30:00Z",
    });
    expect(headline).toBe("Transfer parcel");
    expect(tone).toBe<BannerTone>("action");
  });

  it("TRANSFER_PENDING post-confirmation both roles = Payout pending", () => {
    const result = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "seller",
      transferConfirmedAt: "2026-05-02T10:00:00Z",
      fundedAt: "2026-05-01T14:30:00Z",
    });
    expect(result.headline).toBe("Payout pending");
    expect(result.tone).toBe<BannerTone>("waiting");
  });

  it("COMPLETED = Escrow complete + done tone", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "COMPLETED",
      role: "winner",
      transferConfirmedAt: "2026-05-02T10:00:00Z",
      fundedAt: "2026-05-01T14:30:00Z",
    });
    expect(headline).toBe("Escrow complete");
    expect(tone).toBe<BannerTone>("done");
  });

  it("DISPUTED = problem tone", () => {
    const { tone } = escrowBannerCopy({
      state: "DISPUTED",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: null,
    });
    expect(tone).toBe<BannerTone>("problem");
  });

  it("FROZEN = problem tone", () => {
    const { tone } = escrowBannerCopy({
      state: "FROZEN",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: null,
    });
    expect(tone).toBe<BannerTone>("problem");
  });

  it("EXPIRED = muted tone", () => {
    const { tone } = escrowBannerCopy({
      state: "EXPIRED",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: null,
    });
    expect(tone).toBe<BannerTone>("muted");
  });
});
```

- [ ] **8.2: Create `escrowBannerCopy.ts` (GREEN)**

```typescript
import type { EscrowState } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";

export type BannerTone = "action" | "waiting" | "done" | "problem" | "muted";

export interface BannerCopyInput {
  state: EscrowState;
  role: EscrowChipRole;
  transferConfirmedAt: string | null;
  fundedAt: string | null;
}

export interface BannerCopyResult {
  headline: string;
  detail: string;
  tone: BannerTone;
}

/**
 * Produces the role-aware + sub-state-aware copy + tone for the
 * AuctionEndedPanel escrow banner. See spec §5.2 for the copy table.
 */
export function escrowBannerCopy(input: BannerCopyInput): BannerCopyResult {
  const { state, role, transferConfirmedAt } = input;

  switch (state) {
    case "ESCROW_PENDING":
      if (role === "winner") {
        return {
          headline: "Pay escrow",
          detail: "at an SLPA terminal in-world.",
          tone: "action",
        };
      }
      return {
        headline: "Escrow pending",
        detail: "waiting for buyer to pay.",
        tone: "waiting",
      };

    case "FUNDED":
    case "TRANSFER_PENDING":
      if (transferConfirmedAt) {
        return {
          headline: "Payout pending",
          detail: "finalizing the transaction.",
          tone: "waiting",
        };
      }
      if (role === "winner") {
        return {
          headline: "Awaiting transfer",
          detail: "seller is transferring the parcel.",
          tone: "waiting",
        };
      }
      return {
        headline: "Transfer parcel",
        detail: "set the land for sale to the winner at L$0.",
        tone: "action",
      };

    case "COMPLETED":
      return { headline: "Escrow complete", detail: "", tone: "done" };

    case "DISPUTED":
      return {
        headline: "Escrow disputed",
        detail: "SLPA staff is reviewing.",
        tone: "problem",
      };

    case "FROZEN":
      return {
        headline: "Escrow frozen",
        detail: "SLPA staff is investigating.",
        tone: "problem",
      };

    case "EXPIRED":
      return {
        headline: "Escrow expired",
        detail: "",
        tone: "muted",
      };
  }
}
```

- [ ] **8.3: Commit**

```bash
git add frontend/src/components/escrow/escrowBannerCopy.ts frontend/src/components/escrow/escrowBannerCopy.test.ts
git commit -m "feat(escrow): escrowBannerCopy pure helper + exhaustive tests"
```

### Step 8.2: Integrate banner into `AuctionEndedPanel`

- [ ] **8.4: Modify `AuctionEndedPanel.tsx`**

Locate the seller-overlay block. Remove the stub "Escrow flow opens in Epic 05" line. Add the banner:

```typescript
import Link from "next/link";
import { escrowBannerCopy, type BannerTone } from "@/components/escrow/escrowBannerCopy";

const bannerToneClasses: Record<BannerTone, string> = {
  action: "bg-primary-container text-on-primary-container",
  waiting: "bg-secondary-container text-on-secondary-container",
  done: "bg-tertiary-container text-on-tertiary-container",
  problem: "bg-error-container text-on-error-container",
  muted: "bg-surface-container text-on-surface-variant",
};

interface EscrowBannerForPanelProps {
  auctionId: number;
  escrowState: EscrowState;
  transferConfirmedAt: string | null;
  fundedAt: string | null;
  role: EscrowChipRole;
}

function EscrowBannerForPanel({
  auctionId,
  escrowState,
  transferConfirmedAt,
  fundedAt,
  role,
}: EscrowBannerForPanelProps) {
  const { headline, detail, tone } = escrowBannerCopy({
    state: escrowState,
    role,
    transferConfirmedAt,
    fundedAt,
  });
  return (
    <div
      className={`mt-3 flex items-center gap-3 rounded-md px-3 py-2 ${bannerToneClasses[tone]}`}
    >
      <span className="flex-1 text-body-md">
        <strong>{headline}</strong>
        {detail && <> {detail}</>}
      </span>
      <Link
        href={`/auction/${auctionId}/escrow`}
        className="rounded-full bg-primary px-3 py-1 text-label-md font-semibold text-on-primary"
      >
        View escrow
      </Link>
    </div>
  );
}
```

Inside `AuctionEndedPanel`'s existing JSX, within the seller overlay block and the winner overlay block, conditionally render the banner when:

```typescript
{auction.escrowState != null &&
  (auction.endOutcome === "SOLD" || auction.endOutcome === "BOUGHT_NOW") && (
    <EscrowBannerForPanel
      auctionId={auction.id}
      escrowState={auction.escrowState}
      transferConfirmedAt={auction.transferConfirmedAt ?? null}
      fundedAt={auction.fundedAt ?? null /* see note below */}
      role={isSeller ? "seller" : "winner"}
    />
  )}
```

**Note for implementer on `fundedAt`:** the `AuctionEndedPanel` receives the auction DTO, not the full escrow DTO. If `auction.fundedAt` isn't already on the DTO (sub-spec 1 may have projected `escrowState` without `fundedAt`), you have two options:

1. Add `fundedAt` to the auction DTO enrichment (prefer this — small backend touch).
2. Read from the `useQuery(["escrow", auctionId])` cache if it's populated. The cache isn't guaranteed to be warm when `AuctionEndedPanel` renders — users landing directly on `/auction/[id]` won't have visited `/auction/[id]/escrow` yet. So this fallback is unreliable.

Go with option 1 — extend the auction DTO enrichment. Escrow-related fields (`escrowState`, `transferConfirmedAt`, `fundedAt`) are a small coherent set that should always appear together on enriched auction DTOs.

- [ ] **8.5: Drop the stub seller-overlay line**

Find the line that reads something like `"Escrow flow opens in Epic 05."` and delete it.

- [ ] **8.6: Write AuctionEndedPanel test additions**

Add to the existing test file:

```typescript
it("renders escrow banner for SOLD auction when viewer is seller", () => {
  renderWithProviders(
    <AuctionEndedPanel
      auction={fakeSellerAuction({
        endOutcome: "SOLD",
        escrowState: "ESCROW_PENDING",
        transferConfirmedAt: null,
      })}
      currentUser={{ id: 42 /* matches seller.id in fixture */ }}
    />,
  );
  expect(screen.getByText(/escrow pending/i)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /view escrow/i })).toHaveAttribute(
    "href",
    expect.stringContaining("/escrow"),
  );
});

it("does not render escrow banner for public viewer", () => {
  renderWithProviders(
    <AuctionEndedPanel
      auction={fakeSellerAuction({
        endOutcome: "SOLD",
        escrowState: "ESCROW_PENDING",
      })}
      currentUser={null}
    />,
  );
  expect(screen.queryByRole("link", { name: /view escrow/i })).not.toBeInTheDocument();
});

it("does not render escrow banner for NO_BIDS outcome", () => {
  renderWithProviders(
    <AuctionEndedPanel
      auction={fakeSellerAuction({ endOutcome: "NO_BIDS", escrowState: null })}
      currentUser={{ id: 42 }}
    />,
  );
  expect(screen.queryByRole("link", { name: /view escrow/i })).not.toBeInTheDocument();
});
```

- [ ] **8.7: Commit**

```bash
git add frontend/src/components/auction/AuctionEndedPanel.tsx frontend/src/components/auction/AuctionEndedPanel.test.tsx
git commit -m "feat(escrow): AuctionEndedPanel gains EscrowBannerForPanel + drops stub seller overlay line"
```

---

## Task 9: WS handler extension in `AuctionDetailClient`

**Spec reference:** §7.2.

**Goal:** Widen the auction detail page's envelope subscription to `AuctionTopicEnvelope`. Add a branch that invalidates `["escrow", auctionId]` + `["auction", auctionId]` on any `ESCROW_*` envelope.

### Files

- Modify: `frontend/src/app/auction/[id]/AuctionDetailClient.tsx`
- Modify: `frontend/src/app/auction/[id]/AuctionDetailClient.test.tsx` (or the page integration test file)

### Step 9.1: TDD — add envelope test

- [ ] **9.1: Add new test case in existing `AuctionDetailClient` tests**

```typescript
it("invalidates escrow + auction cache on ESCROW_FUNDED envelope", async () => {
  const invalidateSpy = vi.fn();
  // ... render with queryClient mock that exposes invalidateQueries

  const [, handler] = subscribeMock.mock.calls[0];
  act(() => {
    handler(fakeEscrowEnvelope("ESCROW_FUNDED", { auctionId: 7 }));
  });

  expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["escrow", 7] });
  expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: expect.arrayContaining(["auction", 7]) });
});
```

- [ ] **9.2: Modify `AuctionDetailClient.tsx` envelope handler**

```typescript
import type { AuctionTopicEnvelope } from "@/types/auction";

// Change the subscription generic:
useStompSubscription<AuctionTopicEnvelope>(
  `/topic/auction/${id}`,
  handleEnvelope,
);

// Extend handleEnvelope:
const handleEnvelope = useCallback(
  (env: AuctionTopicEnvelope) => {
    serverTimeOffsetRef.current =
      new Date(env.serverTime).getTime() - Date.now();

    // Escrow envelopes: invalidate only — no merging, per spec §7.2
    if (env.type.startsWith("ESCROW_")) {
      queryClient.invalidateQueries({ queryKey: ["escrow", id] });
      queryClient.invalidateQueries({ queryKey: auctionKey(id) });
      return;
    }

    // Existing BID_SETTLEMENT and AUCTION_ENDED handling unchanged.
    // ... existing code ...
  },
  [queryClient, id, currentUserId, toast],
);
```

- [ ] **9.3: Commit**

```bash
git add frontend/src/app/auction/[id]/AuctionDetailClient.tsx frontend/src/app/auction/[id]/AuctionDetailClient.test.tsx
git commit -m "feat(escrow): AuctionDetailClient handles ESCROW_* envelopes (invalidate-only)"
```

### Step 9.2: Full suite

- [ ] **9.4: Run full suite**

```bash
cd frontend && npm run test
```

Expected: all prior tests + 1 new integration case, all green.

---

## Task 10: Cleanup, docs sweep, retire legacy helpers

**Spec reference:** §4.4 (helper retirement), §10 (DEFERRED_WORK closures), §11 (DEFERRED_WORK openers), §12 (cross-cutting).

**Goal:** Delete `inferOutcomeFromDto` + `inferEndOutcome`, close 3 DEFERRED_WORK items, open 4 new ones, add 4 FOOTGUNS entries, sweep README.

### Files

- Modify: `frontend/src/components/auction/AuctionEndedPanel.tsx` — remove `inferOutcomeFromDto` + call sites
- Modify: `frontend/src/components/listing/ListingSummaryRow.tsx` — remove `inferEndOutcome` + call sites
- Modify: `docs/implementation/DEFERRED_WORK.md`
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `README.md`

### Step 10.1: Retire `inferOutcomeFromDto`

- [ ] **10.1: Locate + delete `inferOutcomeFromDto` from `AuctionEndedPanel.tsx`**

Search for the function definition (should be at ~line 295-321 per the Explore findings). Replace all call sites:

```typescript
// Before
const outcome = ended.endOutcome ?? inferOutcomeFromDto(auction, ended);

// After
const outcome = ended.endOutcome;
// Backend always projects endOutcome post Epic 05 sub-spec 1. If this
// field is ever null on an ENDED auction, it's a backend invariant
// violation — let the type-guard surface it.
```

Delete the `inferOutcomeFromDto` function definition entirely. Delete any tests that specifically asserted the fallback behavior (they're now testing dead code).

- [ ] **10.2: Locate + delete `inferEndOutcome` from `ListingSummaryRow.tsx`**

Same pattern. Delete the function (~line 277-289) and replace call sites with direct `auction.endOutcome` reads.

- [ ] **10.3: Run full test suite**

```bash
cd frontend && npm run test
```

Expected: all tests still passing. If any test fails because it relied on the fallback, its fixture needs an explicit `endOutcome` set — fix the fixture, not the helper.

- [ ] **10.4: Commit**

```bash
git add frontend/src/components/auction/AuctionEndedPanel.tsx \
        frontend/src/components/auction/AuctionEndedPanel.test.tsx \
        frontend/src/components/listing/ListingSummaryRow.tsx \
        frontend/src/components/listing/ListingSummaryRow.test.tsx
git commit -m "refactor(auction): retire inferEndOutcome and inferOutcomeFromDto defensive fallbacks"
```

### Step 10.2: `DEFERRED_WORK.md` closures

- [ ] **10.5: Remove 3 closed entries**

Edit `docs/implementation/DEFERRED_WORK.md`. Delete entirely:

- "Ended-auction escrow flow UI" (from Epic 04 sub-spec 2)
- "AuctionEndedPanel / ListingSummaryRow enrichment DTO field nullability" (from Epic 04 sub-spec 2)
- "AuctionEndedPanel / My Bids / My Listings escrow CTAs" (from Epic 05 sub-spec 1)

### Step 10.3: `DEFERRED_WORK.md` openers

- [ ] **10.6: Add 4 new entries**

Append under the "Current Deferred Items" header:

```markdown
### Dispute evidence attachments
- **From:** Epic 05 sub-spec 2
- **Why:** Sub-spec 2 ships a minimal dispute form (reasonCategory + 10-2000-char description). A real dispute workflow benefits from file uploads (screenshots), SL transaction references, an optional linked in-world chat log, and a timeline of prior attempts. The dispute route was deliberately scoped as a full page so these additions can land without re-architecting.
- **When:** Epic 10 (Admin & Moderation) — at the same time the admin dispute-resolution tooling lands so both sides mature together.
- **Notes:** Additions likely include file uploads (reuse Epic 02 avatar-upload's S3 path), optional `slTransactionKey` field for `PAYMENT_NOT_CREDITED` claims, evidence timeline. DTO expansion on `EscrowDisputeRequest` + new evidence entity on the backend.

### `PAYMENT_NOT_CREDITED` dispute reconciliation
- **From:** Epic 05 sub-spec 2 (design review)
- **Why:** The reason category claims "I paid but escrow didn't advance," which is the class of claim that indicates a happy-path failure (L$ may have already left the winner's wallet). Automatic refund on this dispute category risks double-paying the winner if the original payment callback later lands via idempotent retry.
- **When:** Epic 10 (Admin & Moderation) — alongside admin dispute-resolution tooling. The admin workflow must pull the SLPA terminal ledger balance, the winner's claimed `slTransactionKey` (see evidence-attachments opener above), and reconcile against the backend's `EscrowTransaction` ledger before any refund.
- **Notes:** Until Epic 10, `PAYMENT_NOT_CREDITED` disputes transition to `DISPUTED` and sit awaiting manual review like every other category.

### Terminal locator on PAY ESCROW state
- **From:** Epic 05 sub-spec 2 (`PendingStateCard` winner view)
- **Why:** The winner's `ESCROW_PENDING` card includes a "Find a terminal" button rendered disabled because no in-world terminal locator exists yet. A real implementation maps registered `Terminal` rows (sub-spec 1 §7.5) to their SL region names + SLURL links.
- **When:** Epic 11 (LSL scripting) — when real in-world terminals are deployed. Pre-launch ops checklist.
- **Notes:** The backend already has the data (`Terminal.region_name` + `http_in_url`). Add a public endpoint `GET /api/v1/sl/terminals/public` returning `[{ terminalId, regionName, slUrl }]` to feed the locator.

### Cross-page eventbus for dashboard row escrow freshness
- **From:** Epic 05 sub-spec 2 (§2.4)
- **Why:** Dashboard rows pick up `escrowState` changes via `refetchOnWindowFocus` + navigation — not via envelope-driven invalidation. Lags live state by up to ~30s on a stale tab. Acceptable for Phase 1.
- **When:** Indefinite — only pull in if user feedback shows the lag feels wrong.
- **Notes:** Implementation is ~30 LoC (named emitter + two subscriber hooks).
```

### Step 10.4: `FOOTGUNS.md` entries

- [ ] **10.7: Add 4 new entries**

Append to `docs/implementation/FOOTGUNS.md` at the next available number (check current numbering):

```markdown
### F.NN — Escrow WS envelopes are invalidation-only

The 9 `ESCROW_*` envelope types on `/topic/auction/{id}` are coarse cache-invalidation signals per spec §7.2. DO NOT add envelope-to-DTO merge logic on the frontend. Per-variant refinements (paymentDeadline on CREATED, reason on DISPUTED, etc.) are there for Epic 09 notifications consumers — the escrow page refetches the full DTO via GET after any envelope and that's the canonical source of truth. Merging 9 different envelope shapes into a cached response with a computed timeline array defeats the backend's "coarse" design intent for zero UX win.

### F.NN — EscrowChip needs transferConfirmedAt to render correctly

`EscrowChip` has a state→label map that sub-splits `TRANSFER_PENDING` based on whether `transferConfirmedAt` is set. Callers with only the `state` can omit it and fall back to a generic label, but the escrow page, dashboard rows, and AuctionEndedPanel all have access to the full field via their DTO enrichment — they MUST pass it. If you see "Transfer pending" on the chip when you expected "Awaiting transfer" or "Payout pending", you forgot to thread `transferConfirmedAt`.

### F.NN — EXPIRED state branches on fundedAt

The backend sends a single `EXPIRED` state that covers two semantically distinct scenarios: winner-never-paid (payment timeout) and seller-never-transferred (transfer timeout, refund queued). The frontend branches on `fundedAt` to choose copy. `fundedAt == null` → payment timeout. `fundedAt != null` → transfer timeout. Don't collapse the copy into a single message — the refund behavior and the responsibility attribution differ.

### F.NN — inferEndOutcome / inferOutcomeFromDto helpers retired in sub-spec 2

The defensive `endOutcome` fallback helpers that previously lived in `AuctionEndedPanel` + `ListingSummaryRow` are gone. Sub-spec 1 backend guarantees `endOutcome` is always projected on ENDED auctions. If you're tempted to bring the helpers back "just in case," don't — the backend is authoritative and a null `endOutcome` on ENDED should surface as a bug, not be silently papered over with heuristics.
```

### Step 10.5: `README.md` sweep

- [ ] **10.8: Update implementation status line**

```bash
grep -n "Implementation Status\|Phase [0-9]" README.md | head -10
```

Find the implementation-status section. Add Epic 05 sub-spec 2 to the list of merged sub-specs. Mention the frontend escrow engine in the frontend services summary.

- [ ] **10.9: Commit all docs updates**

```bash
git add docs/implementation/DEFERRED_WORK.md docs/implementation/FOOTGUNS.md README.md
git commit -m "docs: Epic 05 sub-spec 2 — DEFERRED_WORK closures + openers, FOOTGUNS, README sweep"
```

### Step 10.6: Final full-suite green

- [ ] **10.10: Run full suite**

```bash
cd frontend && npm run test
cd ../backend && ./mvnw test
```

Expected: frontend baseline + ~60-80 new = ~740-760 total, all green. Backend suite still green (no backend changes in sub-spec 2 beyond optional DTO enrichment in Task 1).

---

## Completion checklist

Before opening the PR, confirm:

- [ ] All 10 tasks passed two-stage review (spec compliance + code quality) per subagent-driven-development.
- [ ] Frontend full suite green (`npm run test`).
- [ ] Backend full suite green (`./mvnw test`) — unchanged baseline if Task 1 didn't require DTO additions; otherwise confirm the ~100+ backend tests still pass after DTO enrichment.
- [ ] `docs/implementation/DEFERRED_WORK.md` swept (3 closed + 4 opened).
- [ ] `docs/implementation/FOOTGUNS.md` augmented (4 entries).
- [ ] `README.md` updated.
- [ ] No AI/tool attribution in any commit message or the PR body.
- [ ] Branch pushed; PR opened against `dev`; squash-merge when green.
