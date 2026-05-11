# Realty Groups: Listing Integration (Sub-project C)

**Status:** Draft — design phase
**Author:** Heath Barcus + Claude
**Date:** 2026-05-11
**Tracks:** Issue [#237](https://github.com/TheCodeLlama/slparcelauctions/issues/237)
**Builds on:** `docs/superpowers/specs/2026-05-10-realty-groups-core-permissions-design.md` (A+B, shipped)
**Blocks:** D (group wallet, [#238](https://github.com/TheCodeLlama/slparcelauctions/issues/238))
**Related:** E (alternative parcel ownership + delegated manage permissions, [#239](https://github.com/TheCodeLlama/slparcelauctions/issues/239)), F (admin moderation, [#240](https://github.com/TheCodeLlama/slparcelauctions/issues/240))

---

## 1. Goal

Let a member of a realty group list an auction under that group's name, with three downstream consequences: a "Listed by X of Group" badge surfaced everywhere a listing renders, a snapshot of the per-listing agent-fee terms recorded on the auction row at create time and the L$ amount snapshot at close time, and a conflict-of-interest rule that blocks the group's members from bidding on the group's own listings. The dissolution gate also tightens: a leader can't dissolve a group while it has active listings.

C handles **case 1 only** from DESIGN.md §4.4.5 — the agent lists a parcel they personally own. Cases 2 (another member's parcel) and 3 (SL-group-owned parcel) ship in E. The group wallet that absorbs the group's slice of the agent fee ships in D — C calculates and snapshots the amount but does not move any L$.

---

## 2. Architecture

C is a thin band of features cross-cutting four existing domains: `auction`, `escrow`, `realty`, and the listing-create frontend wizard. It introduces no new entities; the data model gains a single column on `auctions`. The behavior changes are:

```
ListingWizardForm  →  List-as picker  →  POST /api/v1/auctions { listAsGroupPublicId? }
                                                      │
                              ┌────────── routed by controller ──────────┐
                              ▼                                          ▼
                       AuctionService.create              RealtyGroupListingService.createGroupListing
                                                                         │
                                          assertCan(CREATE_LISTING) + snapshot rates
                                                                         │
                                                          AuctionService.create (delegated)

BidService.placeBid  →  bidEligibilityService.assertCanBid(auction, bidder)
                            ├──  bidder == seller          → SellerCannotBidException
                            └──  bidder ∈ group members    → GroupMemberCannotBidException

AuctionEndTask.closeOne (SOLD path)  →  snapshot agent_fee_amt onto auction row

RealtyGroupMemberService.leave / removeMember  →  reassign listing_agent_id → group.leader_id
                                                  (for ACTIVE / DRAFT / PENDING_VERIFICATION auctions)

RealtyGroupService.dissolve  →  blocked when active listings exist (leader path only;
                                admin force-dissolve bypasses)
```

Three new code units worth naming:

- **`RealtyGroupListingService`** — entry point for "list under a group." Wraps `AuctionService.create` with permission check + rate snapshotting. Lives in `realty.listing`.
- **`BidEligibilityService`** — consolidates COI rules for the bid path. Replaces the inline seller-cannot-bid check in `BidService.placeBid`. Lives in `auction` next to `BidService`.
- **One Flyway migration** `V25__realty_groups_listing.sql` adding `auctions.agent_fee_split`.

---

## 3. Data Model

### 3.1 Auction column additions

```sql
ALTER TABLE auctions
  ADD COLUMN agent_fee_split DECIMAL(5,4) NULL;
```

That is the only schema change C makes. Existing columns already in place from the initial schema:

| Column | Type | C's usage |
|---|---|---|
| `realty_group_id` | BIGINT NULL FK realty_groups(id) | Populated at listing-create when picker = group. FK constraint already wired by A+B (ON DELETE SET NULL). |
| `listing_agent_id` | BIGINT NULL FK users(id) | Populated at listing-create with the creating user's id. Reassigned to the group leader when the agent leaves or is removed (§9). |
| `agent_fee_rate` | DECIMAL(5,4) NULL | Snapshot of `realty_groups.agent_fee_rate` at listing-create. NULL for individual listings. |
| `agent_fee_amt` | BIGINT NULL | Computed at SOLD close (§7). NULL until close; remains NULL for NO_BIDS / RESERVE_NOT_MET. |
| `agent_fee_split` (new) | DECIMAL(5,4) NULL | Snapshot of `realty_groups.agent_fee_split` at listing-create. NULL for individual listings. Consumed by D, not by C. |

The snapshot fields are NULL for individual listings (`realty_group_id IS NULL`) and DECIMAL-valued for group listings.

### 3.2 Why snapshot rates instead of live-reading

A group can rebalance its `agent_fee_rate` or `agent_fee_split` at any time. Snapshotting at listing-create freezes the fee terms as a contract: the agent who listed a parcel under one set of terms isn't blindsided by mid-listing rebalances. D will read the snapshotted values when it distributes the fee.

### 3.3 Why no entity for the fee distribution itself

The L$ math is `agent_fee_amt = floor(finalBid * agent_fee_rate)`. Storing this on the auction row keeps it in the same transaction as the close. D will read `agent_fee_amt` and split it per `agent_fee_split` into ledger entries — those ledger rows belong to D's group-wallet schema and are not C's concern.

---

## 4. Permissions Framework

### 4.1 Enum extension

```java
public enum RealtyGroupPermission {
    // A+B
    INVITE_AGENTS,
    REMOVE_AGENTS,
    EDIT_GROUP_PROFILE,
    CONFIGURE_FEES,
    // C
    CREATE_LISTING,
    MANAGE_OWN_LISTING,
    MANAGE_ALL_LISTINGS;
}
```

### 4.2 What's wired in C

C wires **only `CREATE_LISTING`**. The two MANAGE permissions are defined in the enum but no caller in C asserts them. E wires both, including the broker-cancel/pause endpoints that consume `MANAGE_ALL_LISTINGS`.

### 4.3 Authorizer integration

`RealtyGroupAuthorizer.canDo(userId, groupId, CREATE_LISTING)` follows the existing resolution from A+B §4.2: leader → true, otherwise membership-lookup + flag check. Dissolved groups are rejected upstream with `GroupDissolvedException` per A+B's pre-existing handling.

### 4.4 Invitation default — `CREATE_LISTING` off

A+B's invitation builder defaults all flags to off. C's three new flags follow the same rule. Leaders explicitly grant `CREATE_LISTING` at invite time (or via edit-permissions afterwards) — there is no automatic grant.

### 4.5 What's leader-only

No additions to A+B's leader-only list (transfer leadership, dissolve group, edit permissions on another member). `CREATE_LISTING` is delegable; the leader has it all-implicit.

---

## 5. API Surface

All endpoints under `/api/v1`. UUID `publicId` in every path; numeric `id` never crosses the wire (BaseEntity convention).

### 5.1 Auction create — request DTO change

`POST /api/v1/auctions` (existing) gains one optional field on the create request:

```jsonc
{
  // ... existing fields (title, parcel ref, starting bid, etc.)
  "listAsGroupPublicId": "uuid|null"
}
```

When `listAsGroupPublicId` is present, the controller routes to `RealtyGroupListingService.createGroupListing` instead of the direct `AuctionService.create`. When absent or null, behavior is identical to today's individual-listing path.

### 5.2 Listing-eligible groups — new read endpoint

```
GET /api/v1/realty/me/listing-eligible-groups
```

Auth: bearer token (verified user).
Returns the caller's groups where they have `CREATE_LISTING` (or are leader), with the fee rate needed for the wizard's preview:

```jsonc
[
  {
    "publicId": "uuid",
    "name": "Sunset Realty",
    "slug": "sunset-realty",
    "logoUrl": "string|null",
    "agentFeeRate": 0.02
  }
]
```

`agentFeeSplit` is not returned here — the wizard's preview math doesn't reference it (it's only consumed by D when distributing the fee).

Empty array when the user is in no eligible groups. Excludes dissolved groups (A+B's `RealtyGroupAuthorizer` rejection rule already handles dissolved-group calls, but we filter here to avoid showing the user a group they can't act on).

### 5.3 Group listings — public read

A+B's `GET /api/v1/realty/groups/{publicId}` already returns the group's profile. C adds a sibling list endpoint for the group's active listings:

```
GET /api/v1/realty/groups/{publicId}/listings?status=ACTIVE&page=0&size=20
```

Auth: public.
Returns paginated `AuctionListCardDto` (existing shape from the search/browse path). Filter on `realty_group_id = group.id AND status IN (:statuses)`. Default `status = ACTIVE`; `status` query param accepts a comma-separated set.

### 5.4 Domain exceptions

| Exception | HTTP | Code | When |
|---|---|---|---|
| `GroupMemberCannotBidException` | 403 | `GROUP_MEMBER_CANNOT_BID` | Bidder is a current member of the auction's realty group. |
| `ActiveListingsBlockDissolveException` | 409 | `GROUP_HAS_ACTIVE_LISTINGS` | Leader tries to dissolve while the group has DRAFT / PENDING_VERIFICATION / ACTIVE auctions. |

Reused from A+B's exception surface:

| Exception | When (in C's context) |
|---|---|
| `RealtyGroupNotFoundException` (404) | `listAsGroupPublicId` doesn't resolve. |
| `RealtyGroupPermissionDeniedException` (403) | Member lacks `CREATE_LISTING`. |
| `GroupDissolvedException` (410) | Member tries to list under a dissolved group. |
| `RealtyGroupMembershipRequiredException` (403) | Non-member tries to list under a group. |

### 5.5 DTO shapes

`AuctionListCardDto` and the auction detail DTO already returned by the auction surface gain three optional fields when the auction has a `realty_group_id`:

```jsonc
{
  // ... existing auction fields
  "realtyGroup": {
    "publicId": "uuid",
    "name": "Sunset Realty",
    "slug": "sunset-realty",
    "logoUrl": "string|null",
    "dissolved": false
  } | null,
  "listingAgent": {
    "publicId": "uuid",
    "displayName": "...",
    "avatarUrl": "..."
  } | null,
  "agentFeeRate": 0.02 | null
}
```

`realtyGroup` is omitted (or null) for individual listings. `dissolved` lets the frontend hide the chip cleanly when the group has been dissolved post-listing.

---

## 6. Frontend Surface

### 6.1 Listing wizard — List-as picker

`ListingWizardForm` is a single-step Configure form today. C adds a new inline section between the parcel-lookup result and the AuctionSettingsForm block:

- Hidden entirely if `GET /api/v1/realty/me/listing-eligible-groups` returns `[]`.
- Otherwise renders a `<RadioGroup>` with "Individual" (default — Q5b answer) + one option per eligible group, showing the group's logo + name.
- When a group is selected, a fee-math preview renders inline:
  > If this lists at L$ {{startingBid}}, you'll receive approximately **L$ {payout}** after platform commission (5%) and group agent fee ({rate}%).
  >
  > `payout = startingBid - floor(startingBid * 0.05) - floor(startingBid * rate)`
- Preview updates reactively on starting-bid change. Math is pure client-side using the returned `agentFeeRate`. Wizard wires the selected `publicId` into the submit payload as `listAsGroupPublicId`.

### 6.2 Listing card

`ListingCard` (and the auction detail header) renders a `<GroupChip group={auction.realtyGroup} />` (component from A+B) above the seller line when `auction.realtyGroup != null && !auction.realtyGroup.dissolved`. When the group is dissolved, the chip is hidden — the seller line stands alone.

Auction detail page additionally renders a "Listed by **{listingAgent.displayName}** of **{realtyGroup.name}**" line with both avatars, linking the group portion to `/group/{slug}`. Mirrors DESIGN.md §4.4.5 wording verbatim.

### 6.3 Bid form COI message

When the set of `publicId`s returned by `GET /api/v1/me/realty-groups` (existing A+B endpoint) contains the auction's `realtyGroup.publicId`, the bid input renders disabled with:

> You're a member of **{realtyGroup.name}** and can't bid on its listings.

This is a client-side preflight — the backend's `GROUP_MEMBER_CANNOT_BID` 403 is the source of truth, but the message saves the user a round trip. If the frontend check misses (race: user joined the group while the page was open), the backend's 403 is mapped to the same disabled-state message via the existing API-error reducer.

### 6.4 SSR safety

- `/group/{slug}/listings` (if rendered as a server-component page) and the group profile page render with `export const dynamic = "force-dynamic"` because the listings count changes per visit. Follows the existing convention from A+B's group profile page.
- `GET /api/v1/realty/groups/{publicId}/listings` is public, so SSR fetches don't require a JWT.

---

## 7. Auction Completion — Agent-Fee Snapshot

### 7.1 Where it lives

`AuctionEndTask.closeOne` — inside the existing `@Transactional` boundary, immediately after the SOLD-branch assignments of `winnerUserId` and `finalBidAmount`:

```java
if (outcome == AuctionEndOutcome.SOLD && auction.getRealtyGroupId() != null) {
    long fee = computeAgentFeeAmt(auction);
    auction.setAgentFeeAmt(fee);
}
// ... existing escrow creation + broadcast registration
```

`computeAgentFeeAmt`:

```java
private long computeAgentFeeAmt(Auction a) {
    RealtyGroup group = realtyGroupRepo.findById(a.getRealtyGroupId()).orElse(null);
    if (group == null || group.getDissolvedAt() != null) {
        return 0L;
    }
    BigDecimal rate = a.getAgentFeeRate();
    if (rate == null || rate.signum() == 0) {
        return 0L;
    }
    return BigDecimal.valueOf(a.getFinalBidAmount())
            .multiply(rate)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact();
}
```

NULL groups can occur if the group was deleted (ON DELETE SET NULL clears the FK first — not currently possible since A+B uses soft-delete, but defensive). Dissolved groups → fee zero per Q3b. Zero-rate groups → fee zero (cheap short-circuit).

### 7.2 Why this transaction

Setting `agent_fee_amt` belongs with the rest of the terminal-outcome writes (`endedAt`, `endOutcome`, `winnerUserId`, `finalBidAmount`) so a single rollback leaves the row in a consistent pre-close state. The amount is read by D when it credits the wallet — D doesn't recompute, it consumes the snapshot.

### 7.3 NO_BIDS / RESERVE_NOT_MET

No write. `agent_fee_amt` stays NULL. Conceptually: no sale → no fee → nothing to distribute. D's consumer can treat NULL as zero.

### 7.4 Why floor rounding

Matches `EscrowCommissionCalculator`'s `Math.floorDiv` convention for the platform commission. Rounding to user-favoring (the agent / group) would mean `commission + agent_fee + payout > finalBid` in some edge cases. Floor on both keeps the books balanced.

---

## 8. Bid Path — `BidEligibilityService`

### 8.1 Service shape

```java
package com.slparcelauctions.backend.auction;

@Service
@RequiredArgsConstructor
public class BidEligibilityService {

    private final RealtyGroupMemberRepository memberRepo;

    /**
     * Throws if the bidder cannot place a bid on this auction. Rules:
     * - Bidder is the auction's seller → SellerCannotBidException
     * - Bidder is a current member of the auction's realty group → GroupMemberCannotBidException
     *
     * Both checks short-circuit; order is seller-first since it's free
     * (no DB hit) and the most common refusal.
     */
    public void assertCanBid(Auction auction, User bidder) {
        if (bidder.getId().equals(auction.getSeller().getId())) {
            throw new SellerCannotBidException();
        }
        Long groupId = auction.getRealtyGroupId();
        if (groupId != null && memberRepo.existsByGroupIdAndUserId(groupId, bidder.getId())) {
            throw new GroupMemberCannotBidException();
        }
    }
}
```

### 8.2 `BidService.placeBid` integration

Replace the inline seller check at `BidService.java:148`:

```java
// before:
if (bidder.getId().equals(auction.getSeller().getId())) {
    throw new SellerCannotBidException();
}

// after:
bidEligibilityService.assertCanBid(auction, bidder);
```

Identical seller semantics; one extra DB query (the member lookup) only on auctions that are group-listed.

### 8.3 Exception → HTTP mapping

`GroupMemberCannotBidException` is added to `AuctionExceptionHandler` (same handler that maps `SellerCannotBidException` → 403). New mapping: 403 `GROUP_MEMBER_CANNOT_BID`. No retry, no remediation path; the bidder is permanently disallowed from bidding on this specific auction while they remain in the group.

### 8.4 Proxy bids

`ProxyBidService.create` follows the same path — call `bidEligibilityService.assertCanBid(auction, bidder)` at the existing seller-check site. Proxy bids by a group member would otherwise sneak past the live-bid check; the eligibility service covers both.

---

## 9. Mid-Auction Listing-Agent Reassignment

### 9.1 When it fires

Two A+B service methods grow a post-mutation hook:

- `RealtyGroupMemberService.leave(userId, groupId)` — after the member row is deleted.
- `RealtyGroupMemberService.removeMember(...)` — after the leader / admin deletes the member row.

Both run inside the existing `@Transactional` boundary. The hook:

```java
auctionRepo.reassignListingAgentForGroup(
    groupId,
    departingUserId,
    group.getLeaderId()
);
```

Repository method (single SQL):

```java
@Modifying
@Query("""
    UPDATE Auction a
       SET a.listingAgentId = :newAgentId
     WHERE a.realtyGroupId = :groupId
       AND a.listingAgentId = :oldAgentId
       AND a.status IN ('DRAFT', 'PENDING_VERIFICATION', 'ACTIVE')
    """)
int reassignListingAgentForGroup(@Param("groupId") Long groupId,
                                  @Param("oldAgentId") Long oldAgentId,
                                  @Param("newAgentId") Long newAgentId);
```

ENDED / CANCELLED / SUSPENDED auctions are not touched — the historical agent attribution stays accurate for completed business.

### 9.2 Why reassign instead of clear

Reassigning to the leader keeps the "Listed by [Agent] of [Group]" badge functional — the leader is the legal face of the group anyway. Clearing `listing_agent_id` would force the badge to fall back to "Listed by [seller]" with no group-side person displayed, which is uglier and removes the group's accountability marker.

### 9.3 What about agent-fee distribution?

D consumes `agent_fee_amt` × `agent_fee_split` and credits the listing agent. With reassignment, the agent's slice goes to the **current** `listing_agent_id` at close time, which means the leader collects what would have been the departed agent's slice. This is a deliberate "you forfeit your commission when you leave the group" semantic. Documented in D's spec when it lands.

### 9.4 Leader transitions

If leadership is transferred via `transferLeadership(...)` while listings are in flight, `listing_agent_id` is **not** updated — the prior leader is still a current member (assuming `oldLeaderAction = STAY`), and the listing's authorship doesn't change. Only the `leader_id` on `realty_groups` is updated. If a future leave/remove targets the (now ex-) leader who happens to be a listing agent, that path runs the reassignment hook as usual.

---

## 10. Dissolution Gate Tightening

### 10.1 New guard in `RealtyGroupService.dissolve(...)`

A+B already checks: caller is leader, no non-leader members exist. C adds:

```java
if (auctionRepo.existsActiveListingsByGroupId(groupId)) {
    throw new ActiveListingsBlockDissolveException();
}
```

Where `existsActiveListingsByGroupId`:

```java
@Query("""
    SELECT COUNT(a) > 0 FROM Auction a
     WHERE a.realtyGroupId = :groupId
       AND a.status IN ('DRAFT', 'PENDING_VERIFICATION', 'ACTIVE')
    """)
boolean existsActiveListingsByGroupId(@Param("groupId") Long groupId);
```

### 10.2 Admin force-dissolve

`AdminRealtyGroupController.forceDissolve(...)` (from A+B) does **not** consult this new guard. Admin force-dissolve is the escape hatch — it leaves the group dissolved with its active listings still pointing at the now-dissolved `realty_group_id`. The agent-fee math in §7 resolves to 0 once the group is dissolved, so closing those auctions is harmless from a money perspective. The badges hide per §6.2.

### 10.3 What ENDED listings do

Auctions in `ENDED` (with or without an open escrow) do **not** block leader-initiated dissolution per Q3c. D's spec will add a sibling guard for in-flight escrows when it lands (because D's wallet shape introduces "money the group is owed" as a real obligation that should block dissolution).

### 10.4 Why pre-ENDED only

A leader who has paid all platform commissions and has no live listings should be able to wind down the group cleanly without waiting on a buyer's escrow timer. The escrow lives in the seller's relationship with the platform, not the group's relationship with the platform.

---

## 11. Cross-Cutting Integration

### 11.1 Search / browse

The existing browse-listings query already filters by status. Group-listed and individual-listed auctions sit in the same result set with no scoring difference. The frontend renders the group chip on individual cards per §6.2.

### 11.2 Notifications

C inherits A+B's notification scheme. No new notification kinds:
- Listing-create under a group → uses the existing AUCTION_CREATED kind. (Future-D may add a `GROUP_LISTING_CREATED` for leader visibility; not in C.)
- Bid refused for COI → no notification; the bidder gets the 403 inline.
- Dissolution blocked → no notification; the leader gets the 409 inline.

### 11.3 WebSocket envelopes

No new STOMP topics. `AuctionEndedEnvelope` is a slim record (`auctionPublicId` + a handful of close-outcome fields) — it doesn't carry the auction's group attribution and doesn't need to. Clients re-fetch the auction via the REST endpoint after receiving AUCTION_ENDED, and the REST DTO now carries `realtyGroup` / `listingAgent` / `agentFeeRate` per §5.5.

### 11.4 Cancellation penalties

Group listings follow the same seller-cancellation penalty rules as individual listings. The cancellation is by the seller (who is also the agent in case 1) — the group does not bear a penalty. If E later introduces case 2 (agent ≠ seller), the question of "does the agent's cancellation create a group-level penalty?" is owned by E.

### 11.5 Listing fees

C does **not** add a separate listing fee for group listings. D will introduce "listing fee paid from group wallet" when the wallet ships; until then, the group-listing path uses the same listing-fee charge as individual listings (which today is L$0 if any).

---

## 12. Testing Strategy

### 12.1 Backend

**`BidEligibilityServiceTest`**
- Bidder is the seller → throws `SellerCannotBidException`.
- Bidder is a current group member of the auction's group → throws `GroupMemberCannotBidException`.
- Bidder is unrelated → no throw.
- Bidder is a member of a *different* group → no throw (filter is per-group, not "any group").
- Bidder was a member but left → no throw (live lookup, not snapshot).
- Auction has null `realty_group_id` (individual listing) → no throw on the COI rule even if bidder is in some group.

**`RealtyGroupListingServiceTest`**
- Happy path: leader creates a listing as their group → auction has `realtyGroupId`, `listingAgentId`, `agentFeeRate`, `agentFeeSplit` snapshotted; create-listing endpoint returns the auction DTO with group attribution.
- Member with `CREATE_LISTING` → happy path.
- Member without `CREATE_LISTING` → 403 `REALTY_GROUP_PERMISSION_DENIED`.
- Non-member → 403 `REALTY_GROUP_MEMBERSHIP_REQUIRED`.
- Dissolved group → 410 `GROUP_DISSOLVED`.
- Snapshot fields: rate / split values copied from the live group row at the moment of create. A subsequent edit to the group's `agent_fee_rate` does not mutate the listing's snapshot (validated by re-reading the auction row).

**`AuctionEndTaskAgentFeeTest`**
- SOLD with group, rate 2%, final L$1000 → `agent_fee_amt = 20`.
- SOLD with group, rate 0%, final L$1000 → `agent_fee_amt = 0`.
- SOLD with dissolved group, rate 2%, final L$1000 → `agent_fee_amt = 0`.
- SOLD with null `realty_group_id` → `agent_fee_amt` remains NULL (no write path entered).
- NO_BIDS → `agent_fee_amt` remains NULL.
- RESERVE_NOT_MET → `agent_fee_amt` remains NULL.
- Floor rounding edge: rate 0.0333, final L$1000 → fee = 33 (not 34).

**`RealtyGroupServiceDissolveTest` (extension of A+B)**
- Leader dissolve blocked by ACTIVE listing → throws `ActiveListingsBlockDissolveException`.
- Leader dissolve blocked by DRAFT listing → same.
- Leader dissolve blocked by PENDING_VERIFICATION listing → same.
- Leader dissolve allowed when only ENDED listings exist → succeeds.
- Leader dissolve allowed when no listings exist → succeeds (unchanged from A+B).
- Admin force-dissolve with ACTIVE listings → succeeds; listings keep `realty_group_id` pointing at the now-dissolved group.

**`RealtyGroupMemberServiceReassignmentTest` (extension of A+B)**
- Member leaves with an ACTIVE listing → `listing_agent_id` reassigned to leader; group_id unchanged.
- Member leaves with an ENDED listing → not touched.
- Leader removes a member with two ACTIVE listings → both reassigned to leader.
- Member leaves a group where they have no listings → no UPDATE issued (verified by `@Modifying` return value = 0).
- Member leaves group A; has listings in group B → group A's reassignment only; group B untouched.

**`BidServicePlaceBidIntegrationTest`**
- Bidder is a group member of the auction's group → 403 `GROUP_MEMBER_CANNOT_BID`.
- Bidder is the seller → 403 `SELLER_CANNOT_BID` (regression, behavior preserved through the eligibility service).
- Bidder is the leader of the auction's group → 403 (leader is also a member; the rule applies).
- Proxy-bid creation by a group member → 403 (same rule via `ProxyBidService`).

### 12.2 Frontend

- `ListingWizardForm`:
  - No picker rendered when `listing-eligible-groups` returns `[]`.
  - Picker rendered with Individual + groups; Individual selected by default.
  - Fee preview appears + updates on starting-bid change when a group is selected.
  - Submit posts `listAsGroupPublicId` when group is chosen; omits it when Individual.
- `ListingCard`:
  - Renders `<GroupChip>` when `auction.realtyGroup` is non-null and not dissolved.
  - Hides chip when group is dissolved.
  - Hides chip when `realtyGroup` is null.
- Auction detail page:
  - "Listed by X of Group" line renders when group attribution exists.
  - Link to `/group/{slug}` resolves correctly.
- Bid form:
  - Disabled with COI message when current user is a member of the auction's group.
  - Enabled when current user is in a different group.
  - Enabled when auction has no group.

### 12.3 Postman

`SLPA` collection gains a new folder **`Realty Groups → List as Group`** with chained requests:

1. `POST /realty/groups` — create a group (leader = current user).
2. `GET /realty/me/listing-eligible-groups` — verifies the new group appears.
3. `POST /auctions` with `listAsGroupPublicId` set — creates a group-listed auction; test script captures `auctionPublicId`.
4. `POST /auctions/{auctionPublicId}/bids` from a fresh bidder user — should succeed.
5. Authenticate as the leader, `POST /auctions/{auctionPublicId}/bids` — should fail with 403 `GROUP_MEMBER_CANNOT_BID`.
6. `POST /dev/auction-end/run-once` (dev profile) — closes the auction.
7. `GET /auctions/{auctionPublicId}` — verifies `agent_fee_amt` is present and non-zero.

A second folder **`Realty Groups → Dissolve gate`** chains: create group → create listing → attempt dissolve → expect 409 `GROUP_HAS_ACTIVE_LISTINGS` → cancel listing → attempt dissolve → expect 204.

### 12.4 Verify guards

No new verify-guard scripts. C respects the existing `no-dark-variants`, `no-hex-colors`, `no-inline-styles`, and coverage guards from the existing frontend setup.

---

## 13. Out of Scope — Sub-projects D, E, F

### 13.1 D — Group wallet

- Group wallet entity + balance/reservation tracking.
- Group ledger (append-only).
- Listing fee paid from group wallet on `CREATE_LISTING`.
- **Absorb the group's slice of `agent_fee_amt`** snapshotted by C. Credit `floor(agent_fee_amt * agent_fee_split)` to the group wallet and `agent_fee_amt - groupSlice` to the listing agent's user wallet.
- Backfill policy for auctions completed between C and D. Default per the issue: go-live-forward.
- Withdrawal flow from group wallet.
- Group dormancy mirror.
- Dissolution gate tightens to "+ no nonzero group wallet balance + no in-flight escrows."

### 13.2 E — Alternative parcel ownership + delegated manage permissions

- Case 2 — agent lists another verified group member's parcel.
- Case 3 — agent lists a parcel owned by an SL group that the realty group has registered.
- New table `realty_group_sl_groups` + SL-group verification flows.
- New permission `REGISTER_SL_GROUP`.
- Wire `MANAGE_OWN_LISTING` and `MANAGE_ALL_LISTINGS` (both defined by C but no-op in C).
- Broker-cancel / broker-pause endpoints.

### 13.3 F — Admin moderation

Unchanged from A+B's §12.4 surface — group suspension, fraud flagging, force-pause all listings (cross-cuts with C), group bans, reports, audit log, reputation.

---

## 14. Migration / Cutover

### 14.1 Flyway migration

`V25__realty_groups_listing.sql`:

```sql
ALTER TABLE auctions
  ADD COLUMN agent_fee_split DECIMAL(5,4) NULL;
```

Single statement. No data migration — all existing auctions are individual listings with NULL `realty_group_id`, so leaving `agent_fee_split` NULL on them is correct.

### 14.2 Backward compatibility

- Existing auctions have NULL `realty_group_id`, `listing_agent_id`, `agent_fee_rate`, `agent_fee_amt`, and (post-migration) `agent_fee_split`. The frontend's `realtyGroup`-null branch already exists from the original schema's column.
- DTOs gain optional fields. Older API consumers that ignore unknown fields keep working.
- No envelope/topic shape changes.

### 14.3 Rollback

- Code rollback only: the migration is forward-additive (one NULL column). Dropping the column on rollback is safe but unnecessary; leaving it idle is fine.
- If C's code is reverted but the migration stays, the column is untouched by any code path and the system reverts cleanly to pre-C behavior.

### 14.4 Deferred items handed off to D

- The L$ never actually leaves the seller's payout in C. D adds the actual wallet credits and the ledger rows.
- The "backfill policy for C-era closed auctions" decision is owned by D's brainstorm. Default is go-live-forward.

---

## 15. Open / Deferred Decisions

| Decision | Status | Owner |
|---|---|---|
| Whether D backfills `agent_fee_amt` for C-era closed auctions | Deferred to D brainstorm; default go-live-forward | D |
| Whether the broker-cancel/pause endpoints should also exist in C (only `MANAGE_ALL_LISTINGS` callers) | Resolved: no, deferred to E | C (resolved) |
| Whether the fee-preview wizard renders for zero-rate groups | Resolved: yes, with the rate displayed as 0% — informed-consent symmetry | C (resolved) |
| Whether `listing_agent_id` reassignment notifies the new leader | Out of scope; existing notification scheme has no `LISTING_REASSIGNED` kind. Deferred to F (audit log surface). | F |
| Per-listing override of group fee terms (one-off discount) | Not in scope. Snapshot at create from group row is the only mechanism. Future feature, not currently issued. | — |

---

End of spec.
