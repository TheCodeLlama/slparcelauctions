# Listing + auction UX: Sell Price projection, Buy Now button, real seller stats in preview

Date: 2026-05-18
Status: Approved (brainstorming)

## Problem

Three independent listing/auction UX gaps, bundled into one spec + one PR:

1. The create-listing fee/earnings preview shows a static, read-only
   "List price" row equal to the starting bid. Sellers cannot see what
   they would net if the parcel sells for a different amount.
2. Buy-it-now is only reachable implicitly: a bidder must type an amount
   greater than or equal to the buy-now price into the bid field. There
   is no discoverable "Buy Now" action.
3. When a seller previews their own draft listing, the seller profile
   card shows a hardcoded placeholder (`"You"`, 0 completed sales, no
   rating, no completion rate) instead of their real reputation, so the
   preview does not reflect what buyers will actually see.

## Decisions

1. **Sell Price is a pure what-if projection.** Relabel and make the
   value editable; recompute only the displayed projection. Never change
   what is actually charged, stored, or gated. No backend change for
   this area.
2. **Default Sell Price priority: Buy It Now, then Reserve, then
   Starting Bid** (`buyNowPrice ?? reservePrice ?? startingBid`).
3. **The Buy Now button reuses the existing buy-now flow verbatim.** It
   sets the existing `confirm` state to the buy-now branch at exactly the
   buy-now price; no new mutation, dialog, or backend.
4. **Real seller stats come from the existing server computation, not a
   second fetch.** The public auction response already builds a full
   seller summary (including the server-computed `completionRate`). The
   fix is to populate the same summary on the seller-facing auction
   response, which the draft/activate preview already knows how to
   render. This guarantees the preview is byte-identical to the
   buyer-facing card with zero fidelity gap. It is a DTO enrichment with
   no schema/Flyway change and no new endpoint.
5. **Packaging:** one spec, one implementation plan, one PR
   `feature/listing-auction-ux` into `dev`, then `dev` to `main`.
   Areas 1 and 2 are frontend-only; Area 3 touches the backend response
   DTO + mapper + their tests + Postman. The `dev` to `main` promotion
   triggers the backend deploy pipeline and an Amplify rebuild.

## Area 1: Sell Price editable projection

### Files

- Modify: `frontend/src/components/listing/AgentCommissionPreview.tsx`
- Modify: `frontend/src/components/listing/ListingWizardForm.tsx`
  (the `<AgentCommissionPreview ... />` render site, ~line 367, passes
  two new props)
- Test: `frontend/src/components/listing/AgentCommissionPreview.test.tsx`
- Test: `frontend/src/components/listing/ListingWizardForm.test.tsx`

### Behaviour

`AgentCommissionPreview` is the single fee/earnings preview rendered
unconditionally by the wizard post Realty-Groups-G (case-1 `AgentFeePreview`
was removed). Today every row is computed from the `startingBid` prop and
labelled "...at list price".

Props change (`AgentCommissionPreviewProps`): add
`reservePrice: number | null` and `buyNowPrice: number | null`.
`startingBid: number` stays. `groupName`, `groupPublicId`,
`agentCommissionRate`, `onInsufficient` stay.

State: an editable integer L$ input whose default is
`buyNowPrice ?? reservePrice ?? startingBid`. The input is controlled
text (mirrors `PlaceBidForm`'s `useState<string>` numeric-input pattern).
A `touched` flag tracks whether the seller has edited it:

- While `!touched`, the input value tracks the computed default. If the
  seller changes starting bid / reserve / buy-now upstream, the still
  untouched input re-seeds to the new default (a `useEffect` on the
  computed default, guarded by `!touched`). This is the project's
  established intentional post-mount `setState` in effect; if ESLint
  flags `react-hooks/set-state-in-effect`, add the bare
  `// eslint-disable-next-line react-hooks/set-state-in-effect` comment
  exactly as `WalletPanel.tsx` / `WalletTermsBanner.tsx` do. Do not
  restructure to avoid the lint; the re-seed is the desired behaviour.
- On the first `onChange`, set `touched = true`; from then on the input
  is fully seller-controlled and is never re-seeded.

Projection number used for the displayed rows:

```
const defaultSell = buyNowPrice ?? reservePrice ?? startingBid;
const parsed = raw.trim() === "" ? defaultSell : Math.floor(Number(raw));
const sellPrice = Number.isFinite(parsed) && parsed > 0 ? parsed : defaultSell;
```

Empty or non-numeric input falls back to `defaultSell` for the
projection math (the text box still shows whatever the seller typed; only
the computed rows fall back). The existing `if (startingBid <= 0) return
null;` guard is unchanged, so the component is hidden until a starting
bid exists and `defaultSell` is always well-defined when shown.

Displayed rows recompute from `sellPrice` (not `startingBid`):

```
platformCommission = floor(sellPrice * 0.05)
earnings           = sellPrice - platformCommission
agentSlice         = floor(earnings * agentCommissionRate)
groupSlice         = earnings - agentSlice
```

Labels:

- "List price" becomes "Sell Price" and its value cell becomes the
  editable input.
- "Platform commission at list price" becomes "Platform commission at
  sell price" (keep the `(5%)` annotation).
- "Your earnings at list price" becomes "Your earnings at sell price"
  (keep the `({ratePct}% of remaining)` annotation).
- "{groupName} earnings at list price" becomes "{groupName} earnings at
  sell price" (keep the `(remaining)` annotation).

Decoupled and unchanged (must NOT use `sellPrice`):

- `listingFee` stays `floorLindens(startingBid, PLATFORM_COMMISSION_RATE)`.
- `insufficient`, `shortfall`, and the `onInsufficient(insufficient)`
  callback stay computed from the `startingBid`-derived `listingFee`.
- The "Listing fee paid from {groupName} wallet. Current balance L$..."
  line and the "Group wallet has L$...; deposit L$... to publish."
  danger line are unchanged. Editing Sell Price never enables/disables
  the publish button and never changes the projected platform commission
  used for the fee.

Accessibility / styling / rules:

- The value cell becomes a native numeric input (`type="number"`,
  `inputMode="numeric"`, `step={1}`, `min={1}`) with an accessible name
  (`aria-label="Sell price (L$)"` or a visually-hidden label) since the
  surrounding `<dl>`/`<dt>` is not a `<label>`. Keep the existing
  `tabular-nums` numeric styling and the `bg-bg-subtle` block.
- `data-testid="agent-commission-preview"` stays; add
  `data-testid="sell-price-input"` on the input.
- No emoji, no em-dashes or connector en-dashes (project rules).

`ListingWizardForm.tsx`: the existing `<AgentCommissionPreview>` render
site passes `startingBid` from the wizard's auction-settings state; add
`reservePrice={settings.reservePrice}` and
`buyNowPrice={settings.buyNowPrice}` from the same state object
(`AuctionSettingsValue` already carries both as `number | null`).

## Area 2: Buy Now button under Place bid

### Files

- Modify: `frontend/src/components/auction/PlaceBidForm.tsx`
- Test: `frontend/src/components/auction/PlaceBidForm.test.tsx`

### Behaviour

Buy-now is `placeBid(auction.publicId, amount)` where `amount` is greater
than or equal to `buyNowPrice`; the backend interprets it as a buy-now.
`PlaceBidForm` already owns the `placeBid` mutation, the `confirm` state
machine, and the `confirm.kind === "buy-now"` `ConfirmBidDialog` branch
(which uses `submitBid(confirm.amount)`).

Add a secondary button immediately after the existing "Place bid"
submit `<Button>` and before the connection helper / dialog blocks:

- Rendered only when `buyNow != null` (i.e. `auction.buyNowPrice` set).
- `type="button"` (must not submit the form).
- Label: `Buy now · L$${buyNow.toLocaleString()}` (middle dot
  separator, exactly matching the existing typed-amount buy-now
  submit-button label casing in `PlaceBidForm` line ~154). No em-dash.
- `variant="secondary"` (or the project's closest non-primary full-width
  button variant), `fullWidth`.
- `onClick`: `setConfirm({ kind: "buy-now", amount: buyNow })`. This
  reuses the existing buy-now `ConfirmBidDialog` branch unchanged; its
  message ("This will trigger buy-now at L$X...") is accurate because
  `amount === buyNow`.
- `disabled` when `!isConnected || mutation.isPending` (mirrors the
  submit button). It deliberately does NOT depend on `hasValidAmount`
  or the typed amount; it always uses `buyNow` exactly.
- `data-testid="place-bid-buy-now"`.

Visibility/gating already handled upstream: the group-COI early return
(lines 160-171) short-circuits before the main form return, so members
of the auction's realty group never see the button; the `BidPanel`
variant dispatcher already prevents unauth/unverified/seller/ended
viewers from rendering `PlaceBidForm` at all.

The passive "Buy now for L$X" callout in `BidPanel.tsx` (`BidderPanel`
and `BidPanelAuthLoading`) is kept as-is. Removing it is explicitly out
of scope (noted in Out of scope) to keep this change minimal.

## Area 3: real seller stats in the create-listing preview

### Files

- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`
  (`toSellerResponse(Auction, Escrow, MapperBatchContext)` constructor call)
- Modify: every other construction site of `new SellerAuctionResponse(...)`
  (record arity change) including backend test fixtures/builders
- Test: backend `AuctionDtoMapper` seller-response test (assert the
  seller summary block is populated; mirror the existing public-response
  seller-summary assertion)
- Modify: Postman SLPA collection, the seller `GET /api/v1/auctions/{id}`
  saved example/response (now includes the `seller` block)
- Verify (likely no code change): `frontend/src/types/auction.ts`
  (`SellerAuctionResponse.seller?` already exists),
  `frontend/src/app/listings/(verified)/[publicId]/activate/DraftEditorClient.tsx`
  (already branches on `auction.seller`)
- Test: `frontend/src/app/listings/(verified)/[publicId]/activate/ActivateClient.test.tsx`
  and any draft-editor test/fixture asserting the placeholder; update
  `frontend/src/test/fixtures/auction.ts` seller-auction fixture to
  include a `seller` block

### Behaviour

Backend:

- `PublicAuctionResponse` already has a nested `SellerSummary` record;
  `AuctionDtoMapper` already has the private `sellerSummary(User s)`
  helper that builds it including the server-computed `completionRate`
  via `SellerCompletionRateMapper.compute(...)`. `toPublicResponse`
  passes `sellerSummary(a.getSeller())`. `toSellerResponse` currently
  passes nothing for it.
- Add a `PublicAuctionResponse.SellerSummary seller` component to the
  `SellerAuctionResponse` record. Position: immediately after
  `sellerPublicId` (keeps seller-identity fields together and reads
  naturally; the exact ordinal is an implementation choice as long as
  every call site updates consistently). Document on the component that
  it mirrors `PublicAuctionResponse.SellerSummary` and is populated for
  the auction owner's own seller-facing view.
- In `toSellerResponse(Auction a, Escrow escrow, MapperBatchContext ctx)`,
  pass `sellerSummary(a.getSeller())` for the new component, reusing the
  identical computation `toPublicResponse` uses. No new query: the
  `User` is already loaded via `a.getSeller()`.
- Update all other `new SellerAuctionResponse(...)` call sites for the
  arity change (production + tests/fixtures). Grep
  `new SellerAuctionResponse(` across `backend/src`.

Frontend:

- `frontend/src/types/auction.ts` already declares
  `SellerAuctionResponse.seller?: AuctionSellerSummaryDto | null`
  (it `Mirrors PublicAuctionResponse.SellerSummary`), so no type change
  is expected; confirm the shape matches and tighten only if needed.
- `DraftEditorClient.tsx` already maps `sellerCardData` from
  `auction.seller` when present and falls back to the
  `{ displayName: "You", completedSales: 0 }` placeholder otherwise.
  With the backend now populating `seller`, the real branch is taken and
  `completionRate` flows through unchanged. The placeholder becomes a
  defensive-only path (seller association theoretically null). No code
  change required beyond confirming this; do not delete the defensive
  fallback.
- Update frontend tests/fixtures that previously relied on the
  placeholder so they assert the real seller summary instead.

New sellers still render correctly: `SellerProfileCard` /
`NewSellerBadge` already handle null rating, zero sales, and null
`completionRate` ("Too new to calculate" + New Seller badge). The
preview will now match the buyer-facing card exactly, including those
zero states.

## Behaviour rules

- Area 1 Sell Price is display-only. It must never feed `listingFee`,
  `onInsufficient`, publish-gating, or any persisted/charged value.
- Area 1 default re-seeds only while untouched; sticky after the first
  edit; empty/NaN falls back to the default for the projection only.
- Area 2 Buy Now always uses exactly `buyNowPrice`, reuses the existing
  confirm dialog and `submitBid`, and respects connection + pending
  state. It is excluded for group-COI members and non-bidder variants by
  existing gating.
- Area 3 the seller-facing auction response now carries the same seller
  summary (incl. `completionRate`) the public response carries; the
  draft/activate preview is byte-identical to the buyer-facing card.

## Testing

Frontend (Vitest + Testing Library):

- `AgentCommissionPreview.test.tsx`: relabelled strings ("Sell Price",
  "Platform commission at sell price", "Your earnings at sell price",
  "{group} earnings at sell price"); default value priority
  (buyNow > reserve > starting, and each fallback); editing the input
  recomputes the three projected rows; empty/non-numeric input falls
  back to the default for the projection; the `onInsufficient` callback
  and the listing-fee line are unaffected by input changes (gating still
  keyed off starting bid); untouched re-seed when starting bid changes;
  sticky after edit.
- `ListingWizardForm.test.tsx`: update the AgentCommissionPreview render
  assertions for the new props and relabelled text.
- `PlaceBidForm.test.tsx`: Buy Now button renders only when
  `buyNowPrice` is set; click opens the buy-now `ConfirmBidDialog`;
  confirm calls `placeBid` with exactly `buyNowPrice`; disabled when
  disconnected or a bid is pending; not rendered for a group-COI member
  (covered by the early return).
- Draft-editor / `ActivateClient.test.tsx` + `test/fixtures/auction.ts`:
  seller-auction fixture includes a `seller` block; preview renders the
  real seller summary (rating, reviews, completed sales, completion
  rate, member since) instead of the "You / 0 sales" placeholder.

Backend (`./mvnw test`):

- `AuctionDtoMapper` seller-response test: assert the `seller` block is
  populated and equals the public-response computation (rating, reviews,
  completed sales, `completionRate`, member since, avatar URL).
- All `new SellerAuctionResponse(...)` construction sites compile and
  pass after the arity change.
- Run the full backend suite locally (no backend CI; CI is main-only).

Postman: update the seller `GET /api/v1/auctions/{id}` saved example to
include the `seller` block.

Pre-push (CI is main-only): `npm run lint`, `npm test`,
`npm run build`, `npm run verify`, and `./mvnw test` all locally.

## Out of scope

- Removing the passive "Buy now for L$X" callout in `BidPanel.tsx`
  (kept; removal is an optional future cleanup).
- Any change to actual listing-fee / commission economics or to what is
  charged, stored, or gated.
- Any new endpoint, schema change, or Flyway migration.
- The create-wizard grid `ListingPreviewCard` (a different component);
  the seller-profile placeholder bug is specifically the draft-editor
  `SellerProfileCard` in `DraftEditorClient.tsx`.
- A Sell Price basis selector / segmented control / custom-named bases
  (explicitly rejected during brainstorming; it is a single editable
  integer input with a computed default).

## Files

New:

- `docs/superpowers/specs/2026-05-18-listing-auction-ux-design.md` (this
  spec)

Modified:

- `frontend/src/components/listing/AgentCommissionPreview.tsx`
- `frontend/src/components/listing/AgentCommissionPreview.test.tsx`
- `frontend/src/components/listing/ListingWizardForm.tsx`
- `frontend/src/components/listing/ListingWizardForm.test.tsx`
- `frontend/src/components/auction/PlaceBidForm.tsx`
- `frontend/src/components/auction/PlaceBidForm.test.tsx`
- `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`
- Other backend `new SellerAuctionResponse(...)` call sites + tests/fixtures
- `frontend/src/app/listings/(verified)/[publicId]/activate/DraftEditorClient.tsx`
  (confirm only; defensive fallback retained)
- `frontend/src/app/listings/(verified)/[publicId]/activate/ActivateClient.test.tsx`
- `frontend/src/test/fixtures/auction.ts`
- Postman SLPA collection (seller `GET /api/v1/auctions/{id}` example)
- Root `README.md` sweep at task end (project convention)
