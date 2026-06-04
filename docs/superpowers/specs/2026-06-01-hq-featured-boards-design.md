# In-World HQ Featured Boards

**Date:** 2026-06-01
**Status:** Awaiting user review.

## 1. Goal

Light up the 13 board prims at SLParcels HQ as a paid promotional surface for active listings, wired into the existing `PROMO-01` "Featured listing" SKU. Sellers who buy `PROMO-01` get web Featured carousel placement (existing) **plus** in-world exposure across a wall of HQ boards at no extra cost. Visitors can teleport directly to the parcel or open the listing page in their browser by touching a board.

`PROMO-01`'s price rises from L$300 to L$500 (~US$1.96), held in a configurable backend property. The boards display the listing's hero photo with a "FEATURED" overlay caption and a QR code, rendered via Media-on-Prim — no L$ per update, no SL texture uploads, and the page is plain HTML/CSS served by the existing Next.js frontend.

Only 5 boards are activated in v1; the remaining 8 prims show a "List your parcel here — slparcels.com" placeholder. The active-board count is configurable so the platform can grow into the full 13 (or beyond) without code changes as listing volume scales.

## 2. Product model

`PROMO-01` is the umbrella SKU. Buying it (one-off per listing) confers:

- Web: top-of-browse + homepage Featured carousel placement (existing behavior, unchanged).
- In-world: a slot in the per-board queue at SLParcels HQ for the lifetime of the auction (new in this spec).

No new SKU. No hard cap on `PROMO-01` sales — capacity is elastic because boards partition their queues (see §4).

### 2.1 Pricing

| Config key | Default | Meaning |
|---|---|---|
| `slpa.promotions.featured-price-lindens` | `500` | L$ cost of one `PROMO-01` purchase. |
| `slpa.promotions.featured-slot-count` | `5` | Number of HQ boards currently active. Range supported: 1–13. |
| `slpa.promotions.featured-board-cycle-seconds` | `30` | Cross-fade interval when a board's queue has 2+ listings. |

All three are read at request time (no application restart required for price/cycle changes; slot-count changes require restart so the LSL-side prim URLs can be reconfigured in lockstep).

The redline that bidding stays free and equal still applies — `PROMO-01` sells *visibility*, not bid advantage.

## 3. In-world experience

### 3.1 Board layout

Each board is a square prim with a single Media-on-Prim (MOAP) URL. The rendered page is a full-bleed photo with a gradient caption strip across the bottom containing:

- Listing title (large)
- Region · sqm (small subtitle)
- Current bid (yellow, bold, large)
- Time remaining
- QR code (right side)
- "SLPARCELS" wordmark + "FEATURED" pill (top corners)

Text uses multi-layer text-shadows so legibility survives bright-photo backgrounds.

Layout reference: chosen during brainstorming (option **C2** — full-bleed photo + gradient caption with heavy text shadows + QR). The actual production CSS lives in `frontend/src/app/in-world/board/[boardIndex]/page.tsx`; inline-styled (see §5.3 below).

### 3.2 Touch behavior

`llTouch` on any board fires `llDialog` with three options:

| Button | Action |
|---|---|
| **Teleport** | `llMapDestination(region, position, lookAt)` — teleports the toucher to the parcel. |
| **View listing** | `llLoadURL(touchedAvatarKey, prompt, listingUrl)` — opens the listing page in their default browser. |
| **Cancel** | Closes the dialog. |

The teleport destination + listing URL must match whatever listing the toucher *currently sees* on the board, which is non-trivial because the cycle timer is client-side (see §5.4).

### 3.3 Allow-Always prompt

Standard SL MOAP UX: a first-time visitor sees a one-time "Allow / Allow Always / Deny" prompt for `slparcels.com`. After "Allow Always", every SLParcels MOAP across HQ (and anywhere else SLParcels rents/owns) auto-plays silently from then on for that avatar.

Visitors with media disabled in their viewer see static prims. This is a universal SL constraint, not specific to this design — the audience that walked into HQ to look at land is the audience that has media enabled.

### 3.4 Inactive boards (boards 6–13)

While `featured-slot-count = 5`, the remaining 8 prims point their MOAP URL at a static placeholder route (`/in-world/board/placeholder`) showing SLParcels branding and a "List your parcel here — slparcels.com" call-to-action. Same visual template as a featured board — placeholder text where the photo would be, no QR.

Raising the slot count later requires (a) bumping the config, (b) restarting the backend, (c) `llSetLinkMedia` on the affected prims to point at their new index URL.

## 4. Slot allocation model

### 4.1 Per-board queues, least-loaded assignment

Each active board (1 .. `featured-slot-count`) owns an independent queue of listings. When a seller buys `PROMO-01`:

1. The purchase handler counts active rows per board.
2. Assigns the new listing to the board with the **fewest** active rows. Tiebreak: lowest `boardIndex`.
3. Appends the listing to that board's queue (next `position`).

The assignment sticks for the lifetime of the auction. There is no auto-rebalancing as auctions end — the next *new* purchase fills whichever board most needs a tenant.

### 4.2 Display rules

For board `N` at any moment, the live queue is computed as: all `featured_board_slot` rows where `boardIndex = N` AND `releasedAt IS NULL`, ordered by `position` ascending.

| Queue size | Behavior |
|---|---|
| 0 (board has no PROMO-01 tenants) | Fall back to the algorithmic featured pool (existing `FeaturedRepository.featured()`). Each board with an empty queue picks index `(boardIndex - 1) mod algorithmicPool.length` so multiple empty boards don't all show the same algo pick. Single-listing fallback per board — no cycling animation. |
| 1 | Static — that single listing displays, no cross-fade animation. |
| 2+ | Cross-fade every `featured-board-cycle-seconds` (default 30s), cycling through the queue in `position` order, looping. |

When both the PROMO-01 queue *and* the algorithmic pool are empty for a board, the placeholder card (§3.4) renders in-place.

### 4.3 Release triggers

A row's `releasedAt` is set to `now()` (board immediately drops the listing on its next poll) when:

| Trigger | Source |
|---|---|
| Auction transitions to ENDED / CANCELLED / WITHDRAWN | Existing auction state listener — extended to also notify `FeaturedBoardSlotService`. |
| Seller cancels their listing | Existing cancellation flow. |
| Admin force-release | `/admin/featured-boards` action, see §6. |

Release does **not** refund the seller. Refunds (when warranted) go through the existing wallet adjustment / coupon path.

## 5. Backend + frontend architecture

### 5.1 Data model

Flyway migration `V46__featured_board_slots.sql`:

```sql
CREATE TABLE featured_board_slots (
    id           BIGSERIAL PRIMARY KEY,
    public_id    UUID NOT NULL UNIQUE,
    board_index  INTEGER NOT NULL,
    auction_id   BIGINT NOT NULL REFERENCES auctions(id),
    position     INTEGER NOT NULL,
    assigned_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT featured_board_slots_board_index_range
        CHECK (board_index BETWEEN 1 AND 13)
);

-- Live queue lookup: rows for a specific active board, in display order.
CREATE INDEX featured_board_slots_live_queue_idx
    ON featured_board_slots (board_index, position)
    WHERE released_at IS NULL;

-- One active row per auction (an auction is on at most one board at a time).
CREATE UNIQUE INDEX featured_board_slots_active_per_auction_idx
    ON featured_board_slots (auction_id)
    WHERE released_at IS NULL;
```

The table follows the project's `BaseMutableEntity` convention (`id`, `publicId`, `createdAt`, `updatedAt`, `version`). DTOs expose `publicId`; bot/internal/admin paths may use `id`.

### 5.2 Endpoints

**Public (anonymous):**

```
GET /api/v1/in-world/featured-board/{boardIndex}
```

Returns the live payload for a given board:

```json
{
  "boardIndex": 3,
  "cycleSeconds": 30,
  "listings": [
    {
      "publicId": "...",
      "title": "Lakeside Mainland Estate",
      "region": "Heterocera",
      "sqm": 1024,
      "photoUrl": "/api/v1/photos/...",
      "currentBid": 45000,
      "endsAt": "2026-06-04T12:00:00Z",
      "listingUrl": "/auction/...",
      "slurl": "secondlife://Heterocera/128/64/22"
    }
  ],
  "source": "PROMO_01" | "ALGORITHMIC" | "PLACEHOLDER"
}
```

Permitted to anonymous callers (`permitAll` in `SecurityConfig`). Cached in Redis for 15s per board under `featured-board:{N}` to absorb concurrent viewer hits.

```
GET /api/v1/in-world/featured-board/{boardIndex}/touch
```

Returns a single listing — the one currently on-screen at `now()`, computed as `queue[floor(epochSeconds / cycleSeconds) mod queue.length]` where `epochSeconds` is Unix time in seconds. The board's LSL script hits this endpoint on touch (not on every visitor render) to get the exact listing the toucher saw. The same formula runs in the browser cycle timer so both sides agree on the index without any explicit synchronization.

```
GET /api/v1/in-world/board/placeholder
```

Static placeholder payload (boards 6–13 in v1). Cached for 5 minutes.

**Admin (ROLE_ADMIN):**

```
GET    /api/v1/admin/featured-boards               # all boards + queues
POST   /api/v1/admin/featured-boards/{slotId}/release   # force-release a slot
PATCH  /api/v1/admin/featured-boards/{slotId}/move      # body: { boardIndex, position }
```

### 5.3 Frontend

One new dynamic route, `frontend/src/app/in-world/board/[boardIndex]/page.tsx`, with `export const dynamic = "force-dynamic"` per the SSR caveats in CLAUDE.md. Renders the C2 layout using inline `style` props (the page is intentionally exempt from the `verify-no-inline-styles` guard — see §7.2).

Client-side timer cross-fades every `cycleSeconds` when `listings.length >= 2`. Polls the backend endpoint every 60s to pick up new tenants, ended auctions, and admin swaps. Cross-fade uses CSS `transition: opacity` on a stacked two-layer photo arrangement.

A second route, `frontend/src/app/in-world/board/placeholder/page.tsx`, renders the static "List your parcel here" card with the same outer chrome.

### 5.4 Touch / cycle synchronization

LSL has no idea which listing the page is currently rendering (the cycle timer is JS, not SL). When a visitor touches a board, the LSL script:

1. Hits `/api/v1/in-world/featured-board/{boardIndex}/touch`.
2. Receives the single "current" listing as computed server-side from `now()`.
3. Builds the `llDialog` choices with that listing's slurl + listing URL.

Both the server's touch handler and the browser's cycle timer compute the index identically as `floor(epochSeconds / cycleSeconds) mod queue.length`. There is no explicit clock sync — the two clocks need only agree within `cycleSeconds`, and drift between the SL sim's clock, the backend's clock, and the viewer's clock is bounded to a few seconds in practice, well under the 30s default. Single-tenant boards bypass the index math entirely (queue length 1).

### 5.5 New backend components

| Component | Purpose |
|---|---|
| `FeaturedBoardSlot` entity (`BaseMutableEntity`) | Row in `featured_board_slots`. |
| `FeaturedBoardSlotRepository` | Live-queue queries, least-loaded board count. |
| `FeaturedBoardAssignmentService` | Pure assignment logic: given current per-board counts, pick the least-loaded board. Unit-testable without DB. |
| `FeaturedBoardSlotService` | Transactional: create slot rows on PROMO-01 purchase; release on auction-state listener events; admin operations. |
| `BoardContentResolver` | Given a `boardIndex`, returns the live payload (PROMO_01 queue + algorithmic fallback + placeholder fallback). |
| `InWorldFeaturedBoardController` | Public endpoints. |
| `AdminFeaturedBoardController` | Admin endpoints, ROLE_ADMIN-gated. |

**`PROMO-01` purchase flow dependency**: `PROMO-01` is currently a catalog entry in `docs/monetization/monetization-options.md` — it is not implemented yet. The `Auction.isFeatured` / `featuredUntil` columns already exist and are set by admin curation today. This spec assumes a `PROMO-01` purchase flow exists or is co-built; the implementation plan should decide whether to (a) ship the purchase flow inside this spec's work, (b) split it into a sibling spec built first, or (c) gate the boards behind admin-only `is_featured` flips for v1 and add the L$ purchase path later. The slot-mechanics design in this spec is independent of which path is chosen — the integration point is a single call to `FeaturedBoardSlotService.assign(auction)` triggered whenever an auction becomes Featured, inside whatever transaction sets `is_featured = true`. If the assignment fails or the wallet debit fails, the whole transaction rolls back.

### 5.6 LSL script

New `lsl-scripts/featured-board/`:

- `featured-board.lsl` — the script.
- `config.notecard.example` — `board_index = 1`, `base_url = https://slparcels.com`.
- `README.md` — deployment, ops, limits per the project's per-script convention.

Script behaviour:

- `state_entry`: read notecard, `llSetLinkMedia` to set the prim's MOAP URL to `{base_url}/in-world/board/{board_index}`, whitelist `slparcels.com`.
- `touch_start(integer n)`: `llHTTPRequest` to `/api/v1/in-world/featured-board/{board_index}/touch`, parse the response, `llDialog` with `[Teleport] [View listing] [Cancel]` to the toucher.
- Dialog handler: `llMapDestination` (for Teleport) or `llLoadURL` (for View listing).

No HTTP-in URL is required — the script is outbound-only.

## 6. Admin curation

A new page at `/admin/featured-boards`, gated on `ROLE_ADMIN`. Lists every configured board with its live queue. Per-row actions:

| Action | Effect |
|---|---|
| Force-release | Sets `releasedAt = now()` on the row; the board's next poll drops the listing. Does **not** refund. |
| Reorder | Drag within a board's queue (mutates `position`), promoting/demoting a tenant in the cycle order. |
| Move to another board | Pops the row from its current board, appends to the target board's queue. |

No "pin" feature in v1.

## 7. Testing strategy

### 7.1 Backend (`./mvnw test`)

**Unit tests**:

- `FeaturedBoardAssignmentServiceTest` — least-loaded selection across pool shapes: empty, single-tenant, balanced, unbalanced, fully saturated.
- `BoardContentResolverTest` — queue size 0/1/2+ behaviors; algorithmic-fallback per-board slicing determinism; placeholder fallback when algo is also empty.

**Integration tests** (Testcontainers Postgres):

- PROMO-01 purchase happy path: writes a slot row, flips `is_featured`, debits wallet, all in one atomic transaction. Wallet debit failure rolls back the slot row.
- Auction state transitions (ENDED / CANCELLED / WITHDRAWN) propagate to `releasedAt`.
- Two concurrent purchases under row lock land on different boards (use `CountDownLatch` to force interleaving, assert deterministic assignment).
- `GET /in-world/featured-board/{N}` returns correct queue + `source` label for each of the four pool shapes (queue, single, algo-fallback, placeholder).
- Redis cache hit/miss is observable (verify second call within 15s does not re-query DB).

**Slice tests**:

- `AdminFeaturedBoardControllerSliceTest` — anonymous and `ROLE_USER` get 403; `ROLE_ADMIN` gets 200. Follows the existing admin-slice-test pattern.
- `InWorldFeaturedBoardControllerSliceTest` — anonymous gets 200 (permitAll), invalid `boardIndex` (>13, <1, non-integer) gets 400.

### 7.2 Frontend (`npm test`)

- Integration test for `board/[boardIndex]/page.tsx`:
  - Renders C2 layout for a 1-listing payload (static, no cross-fade).
  - Renders cross-fade timer for a 3-listing payload (advance fake timers, assert opacity transitions).
  - Renders algorithmic-fallback payload with the visual cue resolved at impl time (see §8 open questions).
  - Renders placeholder payload with the call-to-action.
- Visual snapshot guard on the page's outer container so future Tailwind variant sweeps don't accidentally rewrite the inline-styled board markup.
- The `verify-no-inline-styles` guard's exclusion list needs `app/in-world/board/**/page.tsx` added — surface this in the migration PR.

### 7.3 LSL (manual)

Per the per-script `README.md` convention — no automated LSL test harness:

- Drop the script onto a board prim, set notecard, rez at HQ; confirm MOAP URL renders.
- Touch fires the dialog; both menu options work end-to-end (teleport lands at the parcel, View listing opens the page).
- Verify `state_entry` reset behavior on script update.

### 7.4 Postman (SLPA collection)

New folder "In-World Boards" under `SLPA Dev` env:

- Anonymous GET `/in-world/featured-board/{1,3,5}`.
- Anonymous GET `/in-world/featured-board/{N}/touch`.
- Anonymous GET `/in-world/board/placeholder`.
- Admin auth GET `/admin/featured-boards`.
- Admin auth POST `.../{slotId}/release`.
- Admin auth PATCH `.../{slotId}/move`.

Test scripts chain `boardIndex`, `slotId`, and active `auctionId` into the existing environment threading.

### 7.5 Manual end-to-end at HQ

- Fresh viewer account lands at HQ; confirm one-time "Allow Always slparcels.com" prompt, then silent auto-play on subsequent boards.
- Buy `PROMO-01` from a test seller account; confirm the new listing appears on the least-loaded board within ≤60s (next poll cycle).
- End the auction via the existing `dev/auction-end/run-once` helper; confirm the board cross-fades away from that listing within ≤60s.
- Bump `featured-slot-count` from 5 to 6, restart backend, `llSetLinkMedia` board 6's prim, confirm new board activates and starts receiving assignments.

## 8. Edge cases and non-goals

**Edge cases handled**:

- Concurrent `PROMO-01` buys at near-zero pool: row lock guarantees deterministic distribution.
- Auction cancelled while board is showing it: state listener fires `releasedAt`, board's next poll drops it from the queue, page cross-fades to whatever's next.
- All `PROMO_01` queues empty AND algorithmic pool empty: serve placeholder.
- Slot count reduced (5 → 4): existing rows on deactivated boards aren't auto-rebalanced. Admin manually moves orphans or waits for attrition.
- Listing's photo missing/404s: page falls back to a gradient-only card with title + bid.
- Same seller buys `PROMO-01` on two different auctions: each gets its own row; may land on the same board's queue (acceptable).

**Non-goals (explicitly out of scope for v1)**:

- Pin / sticky-board feature.
- Auto-promote-from-waitlist when a slot frees.
- Different pricing tiers (premium board placement at a higher price).
- Auctioning the boards themselves.
- Bot involvement (no SL texture uploads, no in-world avatar interaction — pure MOAP and outbound LSL HTTP).
- Refund automation for force-released slots (handled via existing wallet/coupon paths).
- Click-through analytics on board touches.

**Open questions for impl time, not design time**:

- Algorithmic-fallback visual indicator (does the FEATURED pill disappear, or change copy to "FEATURED PARCEL" vs "POPULAR" / "ENDING SOON")? Recommend deciding during the frontend task.
- Whether the placeholder card needs its own touch behavior (e.g., touch → llLoadURL to slparcels.com listing-create page). Recommend yes; trivial addition.

## 9. References

- `docs/monetization/monetization-options.md` — `PROMO-01` definition.
- `frontend/src/components/marketing/HeroFeaturedStack.tsx` — existing Featured carousel implementation.

## 10. Implementation notes / deviations

- Config key renamed from `featured-board-cycle-seconds` (int) to `featured-board-cycle` (Duration) for consistency with other Duration tunables in the application configuration.
- LSL `Teleport` action uses `llLoadURL` with the SLURL rather than `llMapDestination`, because parsing SLURL components in LSL is fragile and `llLoadURL` produces the same UX (a browser dialog that drops the user in the right region). Revisit if this becomes a UX concern.
- The section 4.3 `WITHDRAWN` trigger is unimplemented because no production code sets `AuctionStatus.WITHDRAWN` today. The cancel paths cover the same lifecycle exit.
- `backend/src/main/java/com/slparcelauctions/backend/auction/featured/` — existing featured infrastructure.
- `lsl-scripts/*/README.md` — per-script README convention.
- CLAUDE.md — Frontend SSR caveats (`force-dynamic`, `apiUrl()` wrap, defensive coercion).
