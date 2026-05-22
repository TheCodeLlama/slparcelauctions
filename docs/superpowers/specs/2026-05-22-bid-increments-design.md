# Per-Auction Bid Increments

**Date:** 2026-05-22
**Issue:** [#396](https://github.com/TheCodeLlama/slparcelauctions/issues/396)
**Status:** Awaiting user review.

## 1. Goal

Let the auction creator set the minimum bid increment for their auction at creation time, replacing the fixed code-level tiered ladder (`BidIncrementTable`, the L$50/100/500/1000-by-price-band table from DESIGN.md section 4.7). Every bid on an auction must clear `currentBid + bidIncrement`, where `bidIncrement` is a single flat L$ value chosen by the creator.

The old tiered table is not deleted — it is repurposed as the *suggestion source* that pre-fills the increment field on the create form. It stops being a runtime bid-validation rule.

## 2. Data model

Flyway migration `V44__auction_bid_increment.sql` (V43 = theme image variants is the latest on disk).

```sql
ALTER TABLE auctions
  ADD COLUMN bid_increment BIGINT NOT NULL DEFAULT 50;
```

The `DEFAULT 50` exists solely to satisfy the `NOT NULL` constraint for any pre-existing non-live auction rows (ended / cancelled). There are no live auctions, so no live behavior is affected. Every new auction carries the creator's chosen value, written explicitly by `AuctionService` at create time; the column default is never the operative value for a real auction.

`Auction` entity gains:

```java
@Column(name = "bid_increment", nullable = false)
private Long bidIncrement;
```

No `@Builder.Default` — the create service always sets it explicitly (see section 3).

## 3. Apply / lifecycle logic

### Create

`AuctionCreateRequest` gains an optional field:

```java
@Min(value = 1, message = "bidIncrement must be at least L$1")
Long bidIncrement
```

Optional (nullable). `@Min(1)` fires only when non-null. No upper cap — a large increment is a legitimate "serious bidders only" choice and the creator owns the consequence.

`AuctionService.create`:
- If `request.bidIncrement()` is non-null, use it.
- If null, apply `BidIncrementSuggester.suggestedIncrement(startingBid)` (the reframed tier table, see section 4).
- Write the resolved value onto `Auction.bidIncrement`.

Both the individual-listing path and the realty-group listing path (`RealtyGroupListingService`) route through the same resolution — the realty path delegates auction construction to `AuctionService`, so this is one code path.

### Edit (pre-active)

`bidIncrement` is editable via the existing auction-update path (`PUT /api/v1/auctions/{publicId}`) under the same edit-window rule that already governs `startingBid` — editable while the auction is pre-active (DRAFT / DRAFT_PAID), frozen once ACTIVE. The implementation mirrors whatever the existing update path does for `startingBid`: if `startingBid` is editable pre-active, `bidIncrement` is editable pre-active alongside it; if `startingBid` is frozen at create, `bidIncrement` is frozen too. The two follow the same rule. `AuctionUpdateRequest` gains the `@Min(1) Long bidIncrement` field.

### Bid validation (`BidService`)

The minimum next bid:
- First bid (no bids yet, `currentBid == 0`): must clear `startingBid` (unchanged).
- Subsequent bids: must clear `currentBid + auction.getBidIncrement()`.

Every `BidIncrementTable.minIncrement(currentBid)` call site in `BidService` becomes `auction.getBidIncrement()`. This includes the post-bid "next minimum bid" hint (the value surfaced back to clients after a successful bid) — `amount + auction.getBidIncrement()`.

### Proxy resolution (`ProxyBidService`)

All `BidIncrementTable.minIncrement(...)` call sites in `ProxyBidService` become `auction.getBidIncrement()`:
- The current-bid step-up (`currentBid + increment`).
- Proxy-versus-proxy resolution: when two proxy bids contend, the winner's committed bid is `loser.maxAmount + increment`. The increment is now the auction's flat value.
- The max-amount step-up checks on existing and incoming proxies.

The auction is already loaded in every one of these paths, so `auction.getBidIncrement()` is a field read with no extra query.

## 4. The suggestion helper

`BidIncrementTable` is renamed `BidIncrementSuggester` and its method is reframed:

```java
/**
 * Suggested starting value for a creator's bid increment, derived from the
 * auction's starting bid. This is a CREATE-TIME SUGGESTION ONLY - it pre-fills
 * the create form and is the fallback when the create request omits an
 * increment. It is NOT a runtime bid-validation rule; once an auction exists,
 * its bid_increment column is the sole authority.
 */
public static long suggestedIncrement(long startingBid) {
    if (startingBid < 1_000L)    return 50L;
    if (startingBid < 10_000L)   return 100L;
    if (startingBid < 100_000L)  return 500L;
    return 1_000L;
}
```

The tier breakpoints are unchanged from the old table; only the framing (suggestion vs rule) and the input (`startingBid` instead of `currentBid`) change. Keying the suggestion off `startingBid` is correct — at create time there is no current bid, and the creator is choosing the increment for the whole auction.

## 5. Backend endpoints

No new endpoints. Existing endpoints change shape:

- `POST /api/v1/auctions` — `AuctionCreateRequest` carries optional `bidIncrement`.
- `PUT /api/v1/auctions/{publicId}` — `AuctionUpdateRequest` carries optional `bidIncrement` (pre-active edit window).
- `GET /api/v1/auctions/{publicId}` — both `PublicAuctionResponse` and `SellerAuctionResponse` expose `bidIncrement: Long` so the bid panel can render the correct "next minimum bid" and validate client-side.
- The bid-placement response (the next-min-bid hint) already returns a computed minimum; its value now derives from the per-auction increment.

## 6. Frontend

### Auction creation form

`ListingWizardForm` (auction-settings section) gains a "Minimum bid increment" L$ input. It is pre-filled from the starting-bid field via a TS replication of the suggestion tiers:

```ts
export function suggestedBidIncrement(startingBid: number): number {
  if (startingBid < 1_000) return 50;
  if (startingBid < 10_000) return 100;
  if (startingBid < 100_000) return 500;
  return 1_000;
}
```

Behavior: when the creator enters or changes the starting bid and has not manually touched the increment field, the increment field tracks the suggestion. Once the creator edits the increment field directly, it stops auto-tracking (a standard "dirty" flag). The field is always submitted with the request (never omitted from the form), so the backend's null-fallback is a defense-in-depth path, not the normal flow.

Validation: client-side `>= 1`, integer L$. Mirrors the backend `@Min(1)`.

### Auction detail / bid panel

The bid panel reads `auction.bidIncrement` to display "Minimum bid increment: L$X" and to compute the next-minimum-bid hint shown in the bid input. Any place the frontend currently computes a next-min-bid from a hardcoded or tiered assumption switches to `currentBid + auction.bidIncrement` (first bid: `startingBid`).

## 7. Testing

### Backend

- `BidIncrementSuggesterTest` (renamed from `BidIncrementTableTest`) — the four tier boundaries asserted explicitly against `suggestedIncrement(startingBid)`, same coverage as today.
- `BidService` tests — a bid below `currentBid + auction.bidIncrement` is rejected; a bid exactly at the threshold is accepted; the first-bid-clears-startingBid path is unchanged. Use auctions with a non-default increment (e.g. L$250) so the test proves the per-auction value is honored, not a constant.
- `ProxyBidService` tests — proxy-versus-proxy resolution with a non-default increment yields a winning committed bid of `loser.maxAmount + auction.bidIncrement`.
- `AuctionService` create tests — omitted `bidIncrement` resolves to `suggestedIncrement(startingBid)`; an explicit value is used as-is; `@Min(1)` rejects 0 and negatives with a 400.
- `AuctionService` update test — `bidIncrement` editable in the pre-active window, frozen once ACTIVE (matching the `startingBid` rule).
- DTO mapper tests — `bidIncrement` present on `PublicAuctionResponse` and `SellerAuctionResponse`.

### Frontend

- `ListingWizardForm` test — the increment field renders; it tracks the starting-bid-derived suggestion until the creator edits it directly; after a manual edit it stops auto-tracking; the submitted request carries the field.
- `suggestedBidIncrement` unit test — the four tier boundaries.
- Bid-panel test — next-minimum-bid hint uses `auction.bidIncrement`.

### Postman

The SLPA collection's create-auction and update-auction requests gain the `bidIncrement` field; a follow-up bid request asserts the rejection of a sub-increment bid.

## 8. Out of scope

- Percentage-based increments. Flat L$ only (chosen 2026-05-22).
- Per-auction customizable tier ladders. Single flat value only (chosen 2026-05-22).
- An upper cap on the increment. None — the creator owns the consequence of an unbiddably-large increment.
- Backfill logic / runtime fallback for existing live auctions. There are none; the migration's `DEFAULT 50` covers stray ended/cancelled rows and no fallback code path is built.
- Editing the increment after an auction goes ACTIVE.

## 9. Decision log

Captured 2026-05-22:

- **Increment model** = single flat per-auction value. Rejected creator-editable tier ladders (heavy create form for a niche need).
- **Field default** = optional in the request, pre-filled in the form from the starting-bid suggestion. Rejected required-with-no-default (needless friction).
- **Existing auctions** = not a concern; there are no live auctions. Migration adds `NOT NULL DEFAULT 50` purely to satisfy the constraint for stray non-live rows; no fallback code path.
- **Upper cap** = none. A large increment is a legitimate creator choice.
- **`BidIncrementTable`** = kept, renamed `BidIncrementSuggester`, reframed as a create-time suggestion source rather than a runtime rule.
