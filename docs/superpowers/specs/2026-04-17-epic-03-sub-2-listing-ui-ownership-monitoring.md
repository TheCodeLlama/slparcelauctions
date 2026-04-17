# Epic 03 Sub-Spec 2 — Listing Creation UI + Ownership Monitoring

**Date:** 2026-04-17
**Epic:** 03 (Parcel Management)
**Covers:** Task 03-05 (listing creation UI), Task 03-06 (ownership monitoring), plus targeted backend tweaks to sub-spec 1.
**Depends on:** Epic 03 sub-spec 1 merged (PR #17 → `dev`). All listed backend contracts are from that sub-spec unless marked otherwise.

---

## 1. Overview & Goals

Sub-spec 1 shipped the backend for parcel verification and auction lifecycle. Sub-spec 2 delivers the seller-facing frontend for creating, editing, activating, and managing listings, plus the backend ongoing-ownership-monitoring scheduled job. It also tweaks sub-spec 1's verification contracts to reflect a cleaner UX model (method chosen at activate time, not at create time).

**Goals:**

1. Sellers can create a listing through a 2-page wizard (Configure → Review), save drafts, edit drafts, and submit.
2. Sellers are guided through a dedicated activate page that handles fee payment, method selection, verification progress, and retry-after-failure without re-entering any listing data.
3. Sellers see all their listings (across every status) in a dashboard tab with status-appropriate actions.
4. The platform continuously monitors ownership of active listings via the SL World API and suspends any whose ownership has changed to an unknown party.
5. Internal consistency: all verification failure paths land in the same retry state with no automatic fee refund; only explicit cancellation refunds.

**Out of scope** (deferred to other epics; see §15):
- Real in-world fee payment (Epic 05).
- Real bot service that processes `SALE_TO_BOT` bot tasks (Epic 06).
- LSL parcel terminal script for Method B (`REZZABLE`) (Epic 11).
- Public listing page / browse (Epic 04/07).
- Bidding (Epic 04).
- Notification delivery on suspension (Epic 09).
- Admin dashboard for `fraud_flag` resolution (Epic 10).

---

## 2. Scope

Sub-spec 2 ships three deliverable tracks in one PR:

1. **Backend tweaks to sub-spec 1** (small, precedes the frontend work):
   - Move `verificationMethod` from `AuctionCreateRequest` to the verify-trigger endpoint.
   - Consolidate verification-failure transitions so every failure path lands in `VERIFICATION_FAILED` (no more split between `DRAFT_PAID` and `VERIFICATION_FAILED` depending on method); no automatic fee refund on failure.
   - Add `currentHighBid: BigDecimal?` and `bidderCount: Long` to `SellerAuctionResponse` and `PublicAuctionResponse` (values return null/0 until Epic 04 populates them).

2. **Frontend listing UI** (Task 03-05): create wizard, edit, activate state machine, My Listings dashboard tab, reusable components.

3. **Backend ownership monitoring** (Task 03-06): queue-driven scheduled job, `SUSPENDED` auction status, `fraud_flag` entity.

All three ship together because (a) the frontend is the forcing function for the backend tweaks, and (b) the monitoring job is independent but small enough that splitting it further would be churn.

---

## 3. Route Map

**Frontend routes (all under `frontend/src/app/`):**

| Route | Purpose | Auth |
| --- | --- | --- |
| `/listings/create` | Create-listing wizard (Configure → Review) | Authenticated + verified |
| `/listings/[id]/edit` | Edit existing listing (DRAFT, DRAFT_PAID only) | Authenticated + seller |
| `/listings/[id]/activate` | Post-creation state machine (fee → method → verify → active) | Authenticated + seller |
| `/dashboard/listings` | My Listings tab (already in the dashboard shell from Epic 02) | Authenticated + verified |

**Backend endpoints** — consumed verbatim from sub-spec 1 unless marked `(new)` or `(changed)`:

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/parcels/lookup` | Resolve parcel UUID to metadata + DB `id`. |
| GET | `/api/v1/parcel-tags` | Category-grouped tag list. |
| POST | `/api/v1/auctions` | Create auction in `DRAFT`. (changed: `verificationMethod` removed from body.) |
| GET | `/api/v1/auctions/{id}` | Fetch as seller (full) or non-seller (public or 404). |
| GET | `/api/v1/users/me/auctions` | Seller's listings. |
| PUT | `/api/v1/auctions/{id}` | Update draft fields. |
| PUT | `/api/v1/auctions/{id}/cancel` | Cancel + auto-refund by status. |
| PUT | `/api/v1/auctions/{id}/verify` | Trigger verification. (changed: now takes body `{ method: "UUID_ENTRY"\|"REZZABLE"\|"SALE_TO_BOT" }`.) |
| POST | `/api/v1/auctions/{id}/photos` | Upload photo. |
| DELETE | `/api/v1/auctions/{id}/photos/{photoId}` | Remove photo. |
| GET | `/api/v1/auctions/{id}/photos/{photoId}/bytes` | Public photo bytes (status-gated). |
| POST | `/api/v1/dev/auctions/{id}/pay` | Dev-only fee-paid flip (testing). |
| POST | `/api/v1/dev/ownership-monitor/run` | (new, dev-only) Trigger one monitoring pass. |

---

## 4. Page: Create / Edit Listing

### 4.1 Page 1 — Configure (progressive disclosure)

Single screen, top-down reveal. Order matters: fields below the parcel card remain hidden until UUID resolves, because the auction settings only make sense once the seller has confirmed which parcel they're listing.

**4.1.1 Parcel UUID field** (required)

- Input type: text, UUID-v4 format validation client-side (regex).
- "Look up" button next to the field (also triggers on Enter).
- On submit, the frontend calls `POST /api/v1/parcels/lookup` with `{ slParcelUuid }`.
- Loading state: spinner on the button, field disabled.
- Success: render a `ParcelLookupCard` (read-only) showing:
  - Parcel name
  - Region name + grid coordinates
  - Continent (from `continentName`)
  - Area in m²
  - Maturity rating badge
  - Snapshot thumbnail (proxied via `snapshotUrl`)
  - "Visit in Second Life" link (`slurl`)
  - Owner UUID from the World API response (displayed so the seller can confirm the parcel matches — for Method C they'll recognise a group UUID; for Method A/B they should see their own avatar UUID)
- After success, the backend has created or refreshed a `parcel` row; the frontend stores `parcelId` in form state.
- On failure:
  - 400 (invalid UUID) → inline message "This doesn't look like a valid Second Life parcel UUID."
  - 404 (not in SL World API) → "We couldn't find this parcel in Second Life. Check the UUID or try again later if you think the World API is down."
  - 422 (not Mainland) → "This parcel isn't on a Mainland continent. Phase 1 supports Mainland parcels only."
  - 504 (World API timeout) → "Second Life's parcel service is slow or down right now. Try again in a moment."
- In **edit mode**, the UUID field renders disabled with the resolved parcel already displayed; seller cannot change which parcel an auction belongs to.

**4.1.2 Auction Settings section** (revealed once UUID resolves)

Fields (all except snipe window in a standard vertical form):

| Field | Type | Required | Validation |
| --- | --- | --- | --- |
| Starting bid | Number (L$) | Yes | Integer ≥ 1 |
| Reserve price | Number (L$) | No | ≥ Starting bid when set |
| Buy-it-now price | Number (L$) | No | ≥ max(Starting bid, Reserve if set) |
| Duration | Dropdown | Yes | One of 24h / 48h / 72h / 7d (168h) / 14d (336h) |
| Snipe protection | Toggle | No (default on) | — |
| Snipe extension window | Dropdown | Required iff snipe toggle on | One of 5 / 10 / 15 / 30 / 60 min |

Tooltips for Reserve and Buy-it-now explain the concepts ("Reserve: minimum price for the auction to close a sale" / "Buy-it-now: any bidder can end the auction immediately at this price").

**4.1.3 Listing Details section** (revealed at the same time as Auction Settings)

- **Description** — textarea, max 5000 chars (backend-enforced), character counter in the corner, plain text (rendered with newline preservation, no markdown).
- **Tags** — `TagSelector` component. Fetches `GET /api/v1/parcel-tags` once and renders category-grouped chips. Seller can pick up to 10 tags (backend enforcement). Selected chips are highlighted; deselected chips are outlined. Categories are rendered as collapsible sections, all expanded by default.
- **Photos** — `PhotoUploader` component. Drag-and-drop zone + file picker. Client-side staging: files are held as `File` objects with object-URL thumbnails. Client validates MIME (`image/jpeg|png|webp`) and size (≤ 2 MB) before accepting into the staging set. Shows "staged" vs "uploaded" state for each photo (staged = client only, uploaded = confirmed via backend). Up to 10 photos (backend enforcement). Users can remove a photo from the set before saving; already-uploaded photos get a DELETE call when the next save happens.

**4.1.4 Footer actions**

Always visible once the parcel resolves; disabled while a save is in flight.

- **Save as Draft** (secondary)
  - If no `auctionId` yet: `POST /api/v1/auctions` with the current field values → store `auctionId` in form state, replace URL with `/listings/[id]/edit` (keep the user on the same page). Then upload all staged photos in sequence with `POST /auctions/{id}/photos`. If any photo fails to upload, surface an inline error per-photo and let the user retry without losing context.
  - If `auctionId` exists: `PUT /api/v1/auctions/{id}` with changed fields. Sync photos — upload newly-staged ones, `DELETE` any that the seller removed since last save.
- **Continue to Review →** (primary)
  - Validates all fields client-side (frontend has the same rules as backend validation for immediate feedback). On success, performs the same save as above, then navigates to page 2.

### 4.2 Page 2 — Review & Submit

Read-only render of everything entered. Reuses `ListingPreviewCard` (which is also reused by the public listing page in Epic 04 — here it's rendered with `isPreview={true}` and wrapped in an explanatory banner: "Here's how your listing will appear to buyers.").

- **Back to edit** (secondary) — returns to page 1 with state preserved.
- **Submit** (primary)
  - Performs one final `PUT /api/v1/auctions/{id}` to catch any last changes, then navigates to `/listings/[id]/activate`.
  - Does **not** trigger fee payment or verification — those happen on the activate page.

### 4.3 State management

`useListingDraft({ id? })` React hook owns the entire form state:

- Stores form values, photo staging set, dirty-tracking flag, last-known-server-state.
- Persists to `sessionStorage` keyed by `auctionId` (or the sentinel `"new"` for an unsaved draft) — protects against accidental tab closes.
- Syncs to server **only** on explicit save/submit (Save as Draft, Continue to Review, Submit). No auto-save.
- On mount: reads from `sessionStorage` first; if empty and `auctionId` provided, fetches `GET /auctions/{id}` and hydrates state.

### 4.4 Edit flow

`/listings/[id]/edit` reuses the same Configure → Review pages with pre-populated state. Differences:

- UUID field is disabled; parcel card renders unchanged.
- Footer button label is "Save changes" instead of "Save as Draft" when `status === DRAFT_PAID` (fee has been paid; "Save as Draft" would be misleading). In `DRAFT` the label is still "Save as Draft".
- Edit is blocked (page responds with 403/redirect to activate) for statuses outside `DRAFT` / `DRAFT_PAID`.

---

## 5. Page: Activate (`/listings/[id]/activate`)

Single page, state-driven. Polls `GET /auctions/{id}` every 5 seconds (React Query `refetchInterval: 5000` + `refetchIntervalInBackground: false` — pauses when the tab is hidden, matching Epic 02's verify flow pattern).

### 5.1 Layout

- **Top strip** — `ActivateStatusStepper` (horizontal, 4 steps: Draft → Paid → Verifying → Active). Current step is highlighted. A regression (verification failure → Paid) animates the indicator back one step.
- **Body** — state-specific panel (renders one of five components below, dispatched on `auction.status`).

### 5.2 `DRAFT` — Awaiting fee payment

- Panel title: **Pay the listing fee**
- Copy:
  > Head to an SLPA terminal in-world and pay **L${listingFeeAmount}** with reference code `LISTING-{shortId}`. Once the platform detects your payment, the page will advance automatically.
  > _In-world payment terminals roll out in a later epic. If you're testing in the dev environment, use the staging endpoint to advance this listing._
- `listingFeeAmount` comes from `slpa.listing-fee.amount-lindens` (default 100) exposed via a public config endpoint or hardcoded in the env config. `shortId` is the first 8 chars of the auction UUID.
- No action button in the panel itself — this is a polling waiting state.
- "Cancel this listing" link at the bottom → `CancelListingModal` (refund amount: L$0 since nothing was paid yet).

### 5.3 `DRAFT_PAID` or `VERIFICATION_FAILED` — Pick verification method

`VerificationMethodPicker` component rendering three method cards.

| Card | Value posted | Copy |
| --- | --- | --- |
| **Manual UUID check** | `UUID_ENTRY` | "Fastest verification. We'll check your avatar UUID against the parcel's owner in the SL World API. Works for individually-owned land only." |
| **Rezzable terminal** | `REZZABLE` | "We'll give you a one-time code. Rez an SLPA parcel terminal on your land and it will verify ownership on your behalf. Works for individually-owned land." |
| **Sale-to-bot** | `SALE_TO_BOT` | "Set your land for sale to the `SLPAEscrow Resident` account at L$999,999,999. Our bot will detect the sale and verify. **Required for group-owned land.**" |

Each card has a "Use this method" button → `PUT /api/v1/auctions/{id}/verify` with body `{ method: "..." }`.

If `status === VERIFICATION_FAILED`, a dismissible banner renders above the cards: "Your last verification attempt failed: {reason}. Pick a method to try again — no additional fee needed." `reason` comes from `auction.verificationNotes` (populated by sub-spec 1 on failure).

"Cancel this listing" link → `CancelListingModal` (refund amount = `auction.listingFeeAmt`, full refund).

### 5.4 `VERIFICATION_PENDING` — Method-specific content

`VerificationInProgressPanel` internally dispatches on `auction.verificationMethod`:

**5.4.1 `UUID_ENTRY`** — Synchronous (usually resolves within the first poll)
- Spinner with copy "Checking ownership with the Second Life World API…"
- After 10 seconds, append "This is taking longer than usual."
- Polling auto-transitions to `ACTIVE` on success or back to `VERIFICATION_FAILED` on failure.

**5.4.2 `REZZABLE`** — Async, PARCEL code
- `CodeDisplay` (existing component from Epic 02) showing the code from `auction.pendingVerification.code` (e.g., `PARCEL-7K3A9X`).
- Step-by-step instructions:
  1. Open your SL inventory and find the "SLPA Parcel Terminal" object.
  2. Rez it on the parcel you're listing.
  3. The terminal will automatically verify ownership and advance your listing.
- Countdown timer using `CountdownTimer` (existing Epic 02 component) against `auction.pendingVerification.codeExpiresAt`. When it hits zero, the `ParcelCodeExpiryJob` in sub-spec 1 reverts to `VERIFICATION_FAILED` (per §7 changes) and the seller can retry.
- "Regenerate code" button rendering only once the code has expired → `PUT /verify` with the same `REZZABLE` method.

**5.4.3 `SALE_TO_BOT`** — Async, bot task
- Instructions panel:
  1. Open your SL Land menu.
  2. Find the parcel you're listing.
  3. Choose "Set Land for Sale…" → enter `SLPAEscrow Resident` as the buyer and **L$999,999,999** as the price.
  4. Click "Sell" to confirm.
  5. Our bot will detect the sale within a few minutes and verify ownership. You do not need to keep this page open.
- Status line from `auction.pendingVerification.instructions` (populated by backend): e.g., "Waiting for bot to pick up your task — this can take several minutes during peak hours."
- No countdown for users (bot task timeout is 48 h — long enough that showing a countdown would be stressful rather than helpful).
- On timeout, `BotTaskTimeoutJob` moves the auction to `VERIFICATION_FAILED` per §7 changes, and the page returns to the method picker with the retry banner.

### 5.5 `ACTIVE` — Success

- Success icon (from `icons.ts` — per project rules no emoji), heading "Your listing is live."
- "View public listing" link → `/auction/[id]` (the public listing page arrives in Epic 04; for now this is a dead link — a banner warns "Public listing page ships in a later epic." This is acceptable because the seller's `DRAFT → ACTIVE` trip still works fully; only the third-party-view step is deferred).
- "Back to My Listings" button → `/dashboard/listings`.
- Subsequent visits to `/listings/[id]/activate` when `status === ACTIVE` redirect to the public listing page (or to dashboard until Epic 04 lands).

### 5.6 Cancellation from any pre-ACTIVE state

`CancelListingModal` (reusable, accepts `auction` as a prop):

- Modal title: "Cancel this listing?"
- Body: parcel name, current status, refund line.
- Refund calculation (derived from status, not from API — the backend creates the `ListingFeeRefund` automatically on cancel):
  - `DRAFT` → "No refund (no fee paid yet)."
  - `DRAFT_PAID` / `VERIFICATION_PENDING` / `VERIFICATION_FAILED` → "Refund: L${auction.listingFeeAmt} (full refund, processed within 24 hours)."
  - `ACTIVE` → "No refund — cancelling an active listing does not refund the fee."
- Destructive red button **"Cancel listing"** → `PUT /api/v1/auctions/{id}/cancel` with `{ reason?: null }` (optional reason textarea shown inside the modal).
- After success: dismiss modal, toast "Listing cancelled.", redirect to `/dashboard/listings`.

### 5.7 Polling hook

`useActivateAuction(id)`:

```ts
useQuery({
  queryKey: ['auction', id, 'activate'],
  queryFn: () => fetchAuction(id),
  refetchInterval: 5000,
  refetchIntervalInBackground: false,
  // Stop polling once terminal
  enabled: true,
  refetchOnWindowFocus: true,
});
```

Terminal states (`ACTIVE`, `CANCELLED`, `SUSPENDED`, `ENDED`, `EXPIRED`, `COMPLETED`, etc.) set `refetchInterval: false` to stop polling. The activate page handles non-terminal states only; if the user somehow lands on `/activate` for a terminal state, redirect to the dashboard with a toast.

---

## 6. My Listings Dashboard Tab (`/dashboard/listings`)

The dashboard shell with tabs is from Epic 02 sub-spec 2b; the My Listings tab currently renders `<EmptyState>`. This sub-spec wires it up.

### 6.1 Data fetch

```ts
useQuery({
  queryKey: ['my-listings', { status, page }],
  queryFn: () => fetchMyListings({ status, page, size: 20 }),
  refetchInterval: 30_000,
  refetchIntervalInBackground: false,
});
```

`fetchMyListings` calls `GET /api/v1/users/me/auctions` (sub-spec 1 endpoint; adds query params for filter and pagination — sub-spec 1 supports pagination via page/size Spring Data params).

### 6.2 Layout

- **Tab header row**
  - Left: title "My Listings" + total count badge (unfiltered count).
  - Right: `[+ Create New Listing]` primary button → `/listings/create`.
- **Filter chips row** — `All | Active | Drafts | Ended | Cancelled | Suspended`.
  - "Active" maps to `status=ACTIVE`.
  - "Drafts" maps to `status=DRAFT,DRAFT_PAID,VERIFICATION_PENDING,VERIFICATION_FAILED`.
  - "Ended" maps to `status=ENDED,ESCROW_PENDING,ESCROW_FUNDED,TRANSFER_PENDING,COMPLETED,EXPIRED`.
  - "Cancelled" maps to `status=CANCELLED`.
  - "Suspended" chip only renders if the seller has at least one `SUSPENDED` listing; maps to `status=SUSPENDED`.
  - "All" is the default.
- **List** — vertical stack of `ListingSummaryRow`, 20 per page.
- **Pagination** — "Load more" button at the bottom (cursor or page-based). Total count shown ("Showing 20 of 47"). Hidden once all rows loaded.
- **Empty state** — when zero listings across all filters: existing `<EmptyState>` with "Create your first listing" CTA → `/listings/create`.

### 6.3 `ListingSummaryRow` layout

```
┌──────────────────────────────────────────────────────────────────────────┐
│ [Thumb 80px] Parcel name                             [Status chip]       │
│              Region · 1024 m²                                            │
│              L$ 500 start · Bid L$ — · 0 bidders                         │
│              Updated 2h ago                  [Edit] [Continue →] [⋯]     │
└──────────────────────────────────────────────────────────────────────────┘
```

- **Thumbnail** — first listing photo (byte-serving endpoint) if any; else `parcel.snapshotUrl`; else icon fallback from `icons.ts`.
- **Status chip** — `ListingStatusBadge` component. Color mapping:

| Status | Tailwind tokens |
| --- | --- |
| `DRAFT` | gray-500 bg, white text |
| `DRAFT_PAID` | amber-500 bg, black text (action needed) |
| `VERIFICATION_PENDING` | blue-500 bg, white text |
| `VERIFICATION_FAILED` | amber-600 bg, white text (retry possible) |
| `ACTIVE` | green-500 bg, white text |
| `ENDED` | purple-500 bg, white text |
| `ESCROW_PENDING` / `ESCROW_FUNDED` / `TRANSFER_PENDING` | purple-600 bg, white text (Epic 05 states — render even though no path to them yet) |
| `COMPLETED` | slate-500 bg, white text |
| `CANCELLED` | rose-500 bg, white text |
| `EXPIRED` | stone-500 bg, white text |
| `DISPUTED` | orange-600 bg, white text |
| `SUSPENDED` | red-600 bg, white text (new — Task 03-06) |

- **Bid line** — shows `currentHighBid` when non-null (formatted as L$), else em dash. Shows `bidderCount` as "N bidder" (singular) or "N bidders".
- **Action buttons** — right-aligned, per-status:

| Status | Visible actions |
| --- | --- |
| `DRAFT`, `DRAFT_PAID` | `[Edit]` `[Continue →]` `[⋯ Cancel]` |
| `VERIFICATION_PENDING` | `[Continue →]` `[⋯ Cancel]` |
| `VERIFICATION_FAILED` | `[Continue →]` `[⋯ Cancel]` |
| `ACTIVE` | `[View listing]` `[⋯ Cancel]` |
| `ENDED`, `ESCROW_*`, `TRANSFER_PENDING`, `COMPLETED`, `EXPIRED` | `[View listing]` |
| `CANCELLED`, `DISPUTED`, `SUSPENDED` | `[View details]` (read-only listing detail page — Epic 04 will supply this; for now disable with tooltip "Details ship in a later epic") |

- **`[Continue →]`** — navigates to `/listings/[id]/activate`. The primary re-entry point for sellers.
- **`[⋯]` overflow menu** — cancel action gated by status (`DRAFT`/`DRAFT_PAID`/`VERIFICATION_PENDING`/`VERIFICATION_FAILED`/`ACTIVE` allowed). Opens `CancelListingModal`.

### 6.4 Suspended row callout

A row with `status === SUSPENDED` renders an inline warning banner beneath the main content:

> **Listing suspended:** {human-readable `fraud_flag.reason`}. Contact support if you believe this is a mistake.

No "View listing" button — suspended listings are hidden from public browse.

### 6.5 Components introduced

- `MyListingsTab` — the page shell.
- `ListingSummaryRow` — single row.
- `ListingStatusBadge` — status-to-chip mapper.
- `FilterChipsRow` — reusable (Epic 07 browse will reuse).

---

## 7. Backend Tweaks to Sub-Spec 1

Small, surgical changes to contracts and behavior established in sub-spec 1.

### 7.1 Remove `verificationMethod` from auction creation

**Rationale (UX):** the verification method is chosen at activate time after fee payment, not at creation time. This lets the seller pick a method in the moment, and retry with a different method on failure without re-entering listing data.

**Changes:**

- `AuctionCreateRequest` — remove `verificationMethod` field. Incoming `POST /api/v1/auctions` no longer requires or accepts it.
- `Auction` entity — `verificationMethod` becomes nullable (it's set by the verify trigger, not at create time). Migration via `ddl-auto: update` handles the column-nullability change; existing sub-spec 1 auctions in dev DBs that have a value remain valid.
- `AuctionService.create(...)` — no longer writes `verificationMethod`; leaves it null until verify.
- Sub-spec 1 tests that create auctions with a method need updating to drop the field from the request.

### 7.2 Add `method` to the verify trigger endpoint

**Change:** `PUT /api/v1/auctions/{id}/verify` now takes body `{ method: "UUID_ENTRY" | "REZZABLE" | "SALE_TO_BOT" }` (required).

- `AuctionVerificationTriggerRequest` (new DTO) holds the method.
- Controller calls `AuctionVerificationService.triggerVerification(auctionId, method, sellerId)`.
- Service sets `auction.verificationMethod = method` then dispatches by method (existing logic).
- Validation: if the `parcel.ownerType == "group"` and `method != SALE_TO_BOT`, throw a new `GroupLandRequiresSaleToBotException` → 422 Unprocessable Entity with a message the UI surfaces as "Group-owned land requires the Sale-to-bot method. Please pick that method."
- Retry from `VERIFICATION_FAILED` allowed (sub-spec 1 already permits this) — the new request body applies on retry too.

### 7.3 Consolidate verification-failure transitions

**Rationale:** sub-spec 1 is inconsistent — `ParcelCodeExpiryJob` reverts to `DRAFT_PAID` on code expiry, while synchronous Method A failures and `BotTaskTimeoutJob` go to `VERIFICATION_FAILED`. UX-wise we want a single retry state so the activate page can always say "your last attempt failed" when appropriate.

**Changes (all server-side):**

- `ParcelCodeExpiryJob` — change the terminal transition from `VERIFICATION_PENDING → DRAFT_PAID` to `VERIFICATION_PENDING → VERIFICATION_FAILED`. Record `verificationNotes = "Method B code expired before the parcel terminal reported back."`
- Method A synchronous failure path in `AuctionVerificationService` — already writes `VERIFICATION_FAILED`; confirm the `verificationNotes` are populated with a user-facing reason ("Ownership check failed: the parcel's owner UUID doesn't match your avatar.").
- `BotTaskTimeoutJob` — already writes `VERIFICATION_FAILED`; confirm `verificationNotes = "Sale-to-bot task timed out after 48 hours without a match."`.
- **No automatic fee refund on any verification failure.** `ListingFeeRefund` is created only by the cancel endpoint. Confirm no failure paths create refunds.

Retry from `VERIFICATION_FAILED` already works in sub-spec 1; the UI simply exposes this as "try again".

### 7.4 Add bid-summary fields to auction responses

**Rationale:** My Listings rows show current high bid and bidder count. Bids don't exist until Epic 04, but the DTO should be stable now so Epic 04 doesn't require a frontend rework.

**Changes:**

- `SellerAuctionResponse` — add `currentHighBid: BigDecimal?` (nullable), `bidderCount: Long` (default 0).
- `PublicAuctionResponse` — same two fields.
- `AuctionDtoMapper` — reads from `auction.currentBid` (the field already exists on the entity per sub-spec 1) for `currentHighBid`, and from `auction.bidCount` (already exists) for `bidderCount`. Mapping is straightforward; both default to 0 / null when no bids have been placed.
- Existing sub-spec 1 tests: assert `bidderCount: 0` and `currentHighBid: null` on every newly-created auction.

### 7.5 SL-header validation consistency

No change. Sub-spec 1's `POST /api/v1/sl/parcel/verify` endpoint is unchanged by this sub-spec — it already validates SL headers and only triggers state changes we keep.

### 7.6 New public config endpoint for listing fee

**Change:** `GET /api/v1/config/listing-fee` → `{ amountLindens: number }` (public, no auth required).

**Rationale:** the activate page's `DRAFT` state displays "Pay L${amount} at an in-world terminal." The amount comes from `slpa.listing-fee.amount-lindens`. The frontend could hardcode the same value, but that's a silent drift risk — if ops bumps the fee the UI would lie. A tiny public config endpoint keeps the frontend honest.

Scope is intentionally narrow: only the listing fee amount, not general config dumping. Epic 10 may add a broader config endpoint later.

---

## 8. Backend — Ongoing Ownership Monitoring (Task 03-06)

### 8.1 Architecture

Event-driven per-auction polling. Each `ACTIVE` auction has a `last_ownership_check_at` timestamp; a scheduler picks up auctions whose timestamp is older than `check-interval-minutes` and dispatches an `@Async` task per listing that calls the World API, handles the result, and updates the timestamp. No batching, no caps — concurrency is bounded by Spring's async executor pool.

### 8.2 Data model changes

**`Auction` entity** — add two columns (via JPA `ddl-auto: update`):

```java
@Column(name = "last_ownership_check_at")
private Instant lastOwnershipCheckAt;

@Column(name = "consecutive_world_api_failures", nullable = false)
@Builder.Default
private int consecutiveWorldApiFailures = 0;
```

**`AuctionStatus` enum** — add `SUSPENDED`. Not in `LOCKING_STATUSES` (the parcel becomes available for a fresh listing once suspended — the suspension surfaces an admin-facing concern, not a permanent lock).

### 8.3 New entity: `FraudFlag`

```java
@Entity
@Table(name = "fraud_flags")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FraudFlag {
  @Id @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "auction_id", nullable = true)
  private Auction auction;               // nullable so future pre-listing flags can attach

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "parcel_id", nullable = false)
  private Parcel parcel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private FraudFlagReason reason;

  @Column(name = "detected_at", nullable = false)
  private Instant detectedAt;

  @Column(name = "evidence_json", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> evidenceJson;

  @Column(nullable = false)
  @Builder.Default
  private boolean resolved = false;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "resolved_by_user_id")
  private User resolvedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}

public enum FraudFlagReason {
  OWNERSHIP_CHANGED_TO_UNKNOWN,   // World API shows parcel owned by a third party
  PARCEL_DELETED_OR_MERGED,       // World API returns 404
  WORLD_API_FAILURE_THRESHOLD     // Consecutive World API failures exceeded limit (Epic 10 follow-up, not Task 06)
}
```

`FraudFlagRepository extends JpaRepository<FraudFlag, UUID>` — standard CRUD. No special queries needed for sub-spec 2 (Epic 10 admin dashboard will add queries for unresolved flags).

### 8.4 Scheduler + async workers

**`OwnershipMonitorScheduler`**

```java
@Service
@ConditionalOnProperty(value = "slpa.ownership-monitor.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OwnershipMonitorScheduler {

  private final AuctionRepository auctionRepo;
  private final OwnershipCheckTask ownershipCheckTask;

  @Value("${slpa.ownership-monitor.check-interval-minutes:30}")
  private int checkIntervalMinutes;

  @Scheduled(fixedDelayString = "${slpa.ownership-monitor.scheduler-frequency:PT30S}")
  public void dispatchDueChecks() {
    Instant cutoff = Instant.now().minus(Duration.ofMinutes(checkIntervalMinutes));
    List<UUID> dueIds = auctionRepo.findDueForOwnershipCheck(cutoff);
    for (UUID id : dueIds) {
      ownershipCheckTask.checkOne(id);  // @Async, fires and forgets
    }
  }
}
```

**`OwnershipCheckTask`**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OwnershipCheckTask {

  private final AuctionRepository auctionRepo;
  private final SlWorldApiClient worldApi;
  private final SuspensionService suspensionService;

  @Async
  @Transactional
  public void checkOne(UUID auctionId) {
    Auction auction = auctionRepo.findById(auctionId).orElse(null);
    if (auction == null || auction.getStatus() != AuctionStatus.ACTIVE) return;
    try {
      ParcelWorldApiData result = worldApi.fetchParcel(auction.getParcel().getSlParcelUuid());
      if (result.ownerUuid().equals(auction.getSeller().getSlAvatarUuid())) {
        auction.setLastOwnershipCheckAt(Instant.now());
        auction.setConsecutiveWorldApiFailures(0);
        auctionRepo.save(auction);
      } else {
        suspensionService.suspendForOwnershipChange(auction, result);
      }
    } catch (ParcelNotFoundInSlException e) {
      suspensionService.suspendForDeletedParcel(auction);
    } catch (ExternalApiTimeoutException | RegionNotFoundException e) {
      auction.setConsecutiveWorldApiFailures(auction.getConsecutiveWorldApiFailures() + 1);
      auction.setLastOwnershipCheckAt(Instant.now());  // still counts — don't hot-loop
      auctionRepo.save(auction);
      log.warn("World API failure for auction {} (consecutive={})", auctionId, auction.getConsecutiveWorldApiFailures());
    }
  }
}
```

**`AuctionRepository`** — new query method:

```java
@Query("""
  SELECT a.id FROM Auction a
  WHERE a.status = 'ACTIVE'
    AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt <= :cutoff)
  ORDER BY a.lastOwnershipCheckAt ASC NULLS FIRST
""")
List<UUID> findDueForOwnershipCheck(@Param("cutoff") Instant cutoff);
```

### 8.5 `SuspensionService`

```java
@Service
@RequiredArgsConstructor
public class SuspensionService {

  private final AuctionRepository auctionRepo;
  private final FraudFlagRepository fraudFlagRepo;

  @Transactional
  public void suspendForOwnershipChange(Auction auction, ParcelWorldApiData evidence) {
    auction.setStatus(AuctionStatus.SUSPENDED);
    auction.setLastOwnershipCheckAt(Instant.now());
    auctionRepo.save(auction);

    fraudFlagRepo.save(FraudFlag.builder()
      .auction(auction)
      .parcel(auction.getParcel())
      .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
      .detectedAt(Instant.now())
      .evidenceJson(Map.of(
        "expected_owner", auction.getSeller().getSlAvatarUuid().toString(),
        "detected_owner", evidence.ownerUuid().toString(),
        "parcel_uuid", auction.getParcel().getSlParcelUuid().toString()
      ))
      .createdAt(Instant.now())
      .build());
  }

  @Transactional
  public void suspendForDeletedParcel(Auction auction) {
    auction.setStatus(AuctionStatus.SUSPENDED);
    auction.setLastOwnershipCheckAt(Instant.now());
    auctionRepo.save(auction);

    fraudFlagRepo.save(FraudFlag.builder()
      .auction(auction)
      .parcel(auction.getParcel())
      .reason(FraudFlagReason.PARCEL_DELETED_OR_MERGED)
      .detectedAt(Instant.now())
      .evidenceJson(Map.of(
        "parcel_uuid", auction.getParcel().getSlParcelUuid().toString()
      ))
      .createdAt(Instant.now())
      .build());
  }
}
```

### 8.6 Jitter on first activation

When an auction first enters `ACTIVE` (in the existing `AuctionVerificationService` for Method A sync success, and for Method B/C callbacks), set `lastOwnershipCheckAt` to `Instant.now().minus(Duration.ofMinutes(random.nextInt(jitterMaxMinutes)))`. This prevents bulk-activated listings from all hitting the `cutoff` boundary at the same moment 30 minutes later.

Implementation: a small `OwnershipCheckTimestampInitializer` utility called from the "transition to ACTIVE" paths in `AuctionVerificationService` and `SlParcelVerifyService` and `BotTaskService` (whichever sub-spec 1 paths mint `ACTIVE`).

### 8.7 Dev admin endpoint

```java
@RestController
@RequestMapping("/api/v1/dev/ownership-monitor")
@Profile("dev")
@RequiredArgsConstructor
public class DevOwnershipMonitorController {

  private final OwnershipMonitorScheduler scheduler;

  @PostMapping("/run")
  public ResponseEntity<Void> runNow() {
    scheduler.dispatchDueChecks();
    return ResponseEntity.accepted().build();
  }
}
```

Used for integration testing and dev verification. Epic 10 will ship a non-dev admin endpoint with role-guarded access.

### 8.8 Configuration

```yaml
slpa:
  ownership-monitor:
    enabled: true
    check-interval-minutes: 30
    scheduler-frequency: PT30S
    jitter-max-minutes: 5
```

`spring.task.execution.pool.*` stays at Spring defaults. If throttling becomes needed in production, ops tunes the pool size — not part of sub-spec 2.

---

## 9. Configuration Additions

Cumulative additions to `application.yml` in sub-spec 2:

```yaml
slpa:
  ownership-monitor:
    enabled: true
    check-interval-minutes: 30
    scheduler-frequency: PT30S
    jitter-max-minutes: 5
```

No other config changes; fee amount and sentinel bot prices are inherited from sub-spec 1.

---

## 10. Error Handling

**Frontend:**
- Parcel lookup errors surface inline (see §4.1.1).
- Save/submit errors toast + inline banner: "Couldn't save: {backend message}". Form state preserved, user can retry.
- Photo upload errors per-file: row highlights, "Retry" link next to the failed thumbnail.
- Verify trigger errors (e.g., 422 for group-owned + non-SALE_TO_BOT) inline under the method picker.
- Polling errors (activate page): silent first 2 failures, then banner "Having trouble reaching the server — retrying…"

**Backend:**
- `GroupLandRequiresSaleToBotException` → 422 (new, handled by `AuctionExceptionHandler`).
- `ParcelNotFoundInSlException` → 404 (existing).
- World API failures in the monitoring job → increment counter, log, skip this cycle's fraud-flag creation (handled by `OwnershipCheckTask`).

---

## 11. Component Inventory

### 11.1 New components

Under `frontend/src/components/listing/`:

- `ParcelLookupField.tsx`
- `ParcelLookupCard.tsx`
- `AuctionSettingsForm.tsx`
- `TagSelector.tsx`  — reusable (also targeted by Epic 07 browse filters)
- `PhotoUploader.tsx`  — reusable
- `ListingPreviewCard.tsx`  — reusable (Epic 04 public listing page)
- `ListingWizardLayout.tsx`
- `ActivateStatusStepper.tsx`
- `FeePaymentInstructions.tsx`
- `VerificationMethodPicker.tsx`
- `VerificationInProgressPanel.tsx`
- `CancelListingModal.tsx`
- `ListingStatusBadge.tsx`
- `ListingSummaryRow.tsx`
- `MyListingsTab.tsx`  — the dashboard tab body
- `FilterChipsRow.tsx`

### 11.2 New UI primitives

Under `frontend/src/components/ui/`:

- `Stepper.tsx` — horizontal step indicator with highlight + transitions. Takes `steps: string[]`, `currentIndex: number`.
- `DropZone.tsx` — generic drag-and-drop file surface, used by `PhotoUploader`.

### 11.3 Hooks

Under `frontend/src/hooks/`:

- `useListingDraft({ id? })` — form state + sessionStorage + sync.
- `useActivateAuction(id)` — polling + terminal-state detection.
- `useMyListings({ filter, page })` — paginated list query.

### 11.4 Backend packages (new or extended)

```
backend/src/main/java/com/slparcelauctions/backend/
  auction/
    monitoring/                        (new)
      OwnershipMonitorScheduler.java
      OwnershipCheckTask.java
      OwnershipCheckTimestampInitializer.java
      SuspensionService.java
      (reuses the return type of SlWorldApiClient.fetchParcel from sub-spec 1 — no new DTO needed)
      config/
        OwnershipMonitorConfig.java     (optional, wraps @Value into a typed record)
    fraud/                             (new)
      FraudFlag.java
      FraudFlagReason.java
      FraudFlagRepository.java
```

`dev/DevOwnershipMonitorController.java` under the existing `dev/` package.

---

## 12. Testing Strategy

### 12.1 Frontend

**Component tests** (Vitest + React Testing Library) for every new component: `ParcelLookupField`, `AuctionSettingsForm` validation, `TagSelector` selection behavior, `PhotoUploader` staging/removal, `VerificationMethodPicker` POST, `CancelListingModal` refund-amount derivation, `ListingStatusBadge` color mapping, `ListingSummaryRow` per-status actions.

**Integration tests** (MSW-mocked backend):
- Create flow happy path: lookup → reveal fields → save as draft → edit → continue to review → submit → navigate to activate.
- Edit flow for DRAFT_PAID: UUID locked, save changes succeeds.
- Validation errors: starting bid = 0, reserve < starting, buy-now < reserve, etc.
- Photo upload: staging, partial upload failure, retry.
- Activate state transitions: DRAFT → DRAFT_PAID (via dev endpoint mock) → method picker → VERIFICATION_PENDING (each of three methods) → ACTIVE. Plus VERIFICATION_FAILED retry with a different method.
- Cancellation from each pre-ACTIVE state; refund-amount display correctness.
- My Listings filter chips; status-specific action buttons.

**Playwright E2E** (non-blocking for this sub-spec — plan includes wiring but doesn't block merge on CI Playwright green):
- Create a Method A listing end-to-end against a dev backend with World API mocked via WireMock.

### 12.2 Backend

**Unit tests**:
- `AuctionVerificationService.triggerVerification` with new method parameter, each branch (UUID_ENTRY / REZZABLE / SALE_TO_BOT), including `GroupLandRequiresSaleToBotException` emission.
- `ParcelCodeExpiryJob` transitions to `VERIFICATION_FAILED` (not `DRAFT_PAID`) with `verificationNotes` populated.
- `BotTaskTimeoutJob` `verificationNotes` population.
- `OwnershipMonitorScheduler.dispatchDueChecks` with a stubbed clock and mocked repo + task; verifies the query cutoff and that each due auction gets dispatched.
- `OwnershipCheckTask.checkOne` each branch (match, mismatch, 404, timeout); verifies timestamp updates and fraud-flag creation.
- `SuspensionService` both methods; verifies auction status transition and fraud-flag rows.
- `FraudFlag` entity persistence (JSON column round-trip).

**Integration tests (Spring Boot test, WireMock for SL World API)**:
- End-to-end ownership change detection: seed an ACTIVE auction → WireMock returns a different `ownerid` → scheduler run → auction becomes `SUSPENDED` + fraud-flag row exists + `consecutive_world_api_failures == 0`.
- World API down for one cycle: WireMock returns 504 → auction stays `ACTIVE` with `consecutive_world_api_failures = 1` and `lastOwnershipCheckAt` updated.
- Jitter on first activation: transition 100 auctions to ACTIVE in one tight loop, verify `lastOwnershipCheckAt` distribution spans the jitter window.
- Dev admin endpoint `POST /api/v1/dev/ownership-monitor/run` triggers one pass end-to-end.

**Sub-spec 1 test updates**:
- Remove `verificationMethod` from `AuctionCreateRequest` fixtures.
- Add `method` to `PUT /verify` request body in existing verify tests.
- Assert `bidderCount: 0`, `currentHighBid: null` on freshly-created auction responses.
- Adjust `ParcelCodeExpiryJob` test to expect `VERIFICATION_FAILED` + notes.

---

## 13. File Structure

**Frontend (new/modified under `frontend/src/`):**

```
app/
  listings/
    create/
      page.tsx                       (wizard host)
      review/page.tsx                (review step host, optional nested route; alternative is intra-page step index)
    [id]/
      edit/page.tsx
      activate/page.tsx
  dashboard/(verified)/listings/
    page.tsx                         (was <EmptyState>; now <MyListingsTab />)
components/listing/
  ...(all listed in §11.1)
components/ui/
  Stepper.tsx
  DropZone.tsx
hooks/
  useListingDraft.ts
  useActivateAuction.ts
  useMyListings.ts
```

**Backend (new under `backend/src/main/java/com/slparcelauctions/backend/`):**

```
auction/
  monitoring/
    ...(all listed in §11.4)
  fraud/
    ...(all listed in §11.4)
dev/
  DevOwnershipMonitorController.java
```

Plus modifications to existing sub-spec 1 classes (listed in §7).

---

## 14. Out of Scope / Deferred Work

Captured in `DEFERRED_WORK.md` at merge time. High-level items from this sub-spec:

- **Real in-world fee payment flow** — Epic 05. The activate page shows production-accurate "pay at a terminal" instructions; the dev-only `POST /api/v1/dev/auctions/{id}/pay` endpoint stands in for testing.
- **Real bot service for SALE_TO_BOT** — Epic 06. The activate page's Method C flow is production-shaped; dev testing uses `POST /api/v1/dev/bot/tasks/{id}/complete` from sub-spec 1.
- **LSL parcel terminal script for REZZABLE** — Epic 11. The activate page's Method B flow is production-shaped; dev testing uses `POST /api/v1/sl/parcel/verify` via Postman.
- **Public listing page** — Epic 04. The "View public listing" links on ACTIVE listings and the Review page's preview currently render the preview card only; Epic 04 ships the full public page.
- **Real bids** — Epic 04. `currentHighBid` and `bidderCount` return null/0 until then.
- **Notification delivery on suspension** — Epic 09. Task 06 logs events but doesn't push notifications.
- **Admin dashboard for `fraud_flag` resolution** — Epic 10. Task 06 writes rows; resolution/closure is manual via DB until Epic 10.
- **Playwright CI integration** — not blocking; tests are authored but CI green is not required for this PR.

---

## 15. Production-Shape Notes

Per project rule: no "MVP" framing, every design targets production.

- The activate page's fee-payment state is production-shape: real users see "pay at an in-world terminal" with correct instructions. When Epic 05 ships, the only change is that real terminals exist — the UI doesn't need rework.
- The method picker is production-shape: real users pick A/B/C and the backend dispatches correctly. When Epic 06 (bot) and Epic 11 (LSL) ship, only the dependencies they fulfill become real; the frontend doesn't change.
- The My Listings tab shows `currentHighBid: null` and `bidderCount: 0` for ACTIVE listings until Epic 04. This is *real state* (no bids exist yet) rendered as "—" — not a stub.
- The ownership monitoring job is production-shape: runs the real scheduler, hits the real World API, writes real fraud flags. The only deferred piece is Epic 09 notification delivery.
- `SUSPENDED` is a fully wired status: the auction is excluded from public browse (Epic 07 will confirm this in its query filters), the seller sees it in their dashboard with the callout, and admin actions (Epic 10) build on top.

---

## 16. Task Breakdown for the Plan

The implementation plan (written next) will break this into ~10 tasks:

1. Backend tweak: remove `verificationMethod` from create, add to verify trigger; `GroupLandRequiresSaleToBotException`; new `GET /api/v1/config/listing-fee` public endpoint; update sub-spec 1 tests.
2. Backend tweak: consolidate failure transitions → `VERIFICATION_FAILED` with `verificationNotes`; assert no auto-refund paths.
3. Backend tweak: add `currentHighBid` and `bidderCount` to both DTOs + mapper + tests.
4. Backend Task 06 foundation: `SUSPENDED` status, `Auction.lastOwnershipCheckAt` + `consecutiveWorldApiFailures`, `FraudFlag` entity + repo, config keys.
5. Backend Task 06 workers: `OwnershipMonitorScheduler`, `OwnershipCheckTask`, `SuspensionService`, `OwnershipCheckTimestampInitializer`, `DevOwnershipMonitorController`, integration tests with WireMock.
6. Frontend UI primitives: `Stepper`, `DropZone`, any missing chip variants; component tests.
7. Frontend listing components: `ParcelLookupField`, `ParcelLookupCard`, `AuctionSettingsForm`, `TagSelector`, `PhotoUploader`, `ListingPreviewCard`, `ListingWizardLayout`, `ListingStatusBadge`; component tests.
8. Frontend create/edit pages: `/listings/create`, `/listings/[id]/edit`, Configure + Review steps, `useListingDraft` hook; integration tests with MSW.
9. Frontend activate page: `/listings/[id]/activate`, `ActivateStatusStepper`, `FeePaymentInstructions`, `VerificationMethodPicker`, `VerificationInProgressPanel`, `CancelListingModal`, `useActivateAuction`; integration tests.
10. Frontend My Listings tab + polish: `MyListingsTab`, `ListingSummaryRow`, `FilterChipsRow`, `useMyListings`, wiring into the existing dashboard shell; full-flow smoke test (create → activate → appears in My Listings); README + FOOTGUNS + DEFERRED_WORK updates; PR.

---

## 17. Assumptions & Open Questions

- **PR #17 (sub-spec 1 backend) merges to `dev` before this sub-spec's implementation begins.** Brainstorm, spec, and plan can proceed now; task 1 of the plan assumes sub-spec 1 code is on `dev`.
- **Fee amount display on the activate page** reads from `auction.listingFeeAmt` when `DRAFT_PAID`+ (the value is set by the pay endpoint). For the `DRAFT` state, the fee amount shown comes from the new `GET /api/v1/config/listing-fee` endpoint (see §7.6).
- **"View public listing" links are dead** until Epic 04 ships the public listing route. The button renders with a tooltip; clicking it navigates to `/auction/[id]` which 404s. Acceptable because the seller flow doesn't depend on the public view.
- **`SUSPENDED` filter chip visibility** — the filter chip renders only if the seller has at least one suspended listing. Implementation: the initial `GET /users/me/auctions?status=SUSPENDED&size=1` runs on mount to decide whether to show the chip. Alternative is to always show it; chose conditional render to keep the chip row uncluttered for the common case (zero suspensions).
