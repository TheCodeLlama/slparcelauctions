# Realty Groups: SL-Group-Owned Listings (Sub-project E)

**Status:** Draft — design phase
**Author:** Heath Barcus + Claude
**Date:** 2026-05-12
**Tracks:** Issue [#239](https://github.com/TheCodeLlama/slparcelauctions/issues/239)
**Builds on:** A+B (`2026-05-10-realty-groups-core-permissions-design.md`), C (`2026-05-11-realty-groups-listing-integration-design.md`), D (`2026-05-11-realty-groups-group-wallet-design.md`)
**Blocks:** F (admin moderation, [#240](https://github.com/TheCodeLlama/slparcelauctions/issues/240))
**Spawns:** "Realty Groups: G" cleanup issue (case-1 code/column removal)

---

## 1. Goal

Build the realty-group case-3 listing flow end-to-end. A leader registers an SL group via about-text or founder-via-terminal verification; group members with `CREATE_LISTING` then list parcels deeded to that SL group; the realty group is the seller-of-record with payout to the group wallet; the listing agent earns a per-listing commission rate snapshotted from their member row; and brokers with `MANAGE_ALL_LISTINGS` can cancel any of the group's listings without penalizing the original lister.

C-era case-1 ("list personal land under group") is closed off through wizard logic and backend validation; the deeper code cleanup tracks in a follow-up "Realty Groups: G" issue.

### 1.1 Conceptual Reframe From Earlier Sub-projects

DESIGN.md §4.4.5 originally described three parcel-ownership cases for listings under a realty group: case 1 (agent owns the parcel), case 2 (another member owns it), case 3 (an SL group owns it). E narrows this to **case 3 only**: realty groups exist to sell land deeded to an SL group, not to broker individual member land. Individual land sells through SLPA directly via the "Individual" listing path. The C-era case-1 plumbing is left in place as deprecated legacy and cleaned up in G.

The economic model also shifts. Under C, `agent_fee_rate` was a fee on top of the platform commission, split by `agent_fee_split` between the listing agent and the group wallet. Under E, the listing agent's commission is a **per-listing percentage of earnings** (final bid minus platform commission), and the remainder goes to the group wallet. The C-era group-level rate/split fields are deprecated for case-3 listings; per-member rates replace them.

---

## 2. Architecture

```
Listing wizard (parcel-driven)  →  parcel lookup result  →  show "Individual" OR "List as [Realty Group]"
                                                            (never both for the same parcel)
                                                            backend validates listAsGroupPublicId matches an SL group
                                                            registered+verified to that realty group

SL group registration page  →  POST /api/v1/realty/groups/{publicId}/sl-groups  →  RealtyGroupSlGroupService
                                  ├─  about-text flow: temp code → poll world.secondlife.com/group/{uuid} → verified
                                  └─  founder-terminal flow: temp code → leader hands code to founder → founder
                                      types it on slpa-terminal → LSL POSTs to /sl/sl-group/verify → verified

AuctionVerificationService.Method C  →  expand parcelOwner acceptance: matches a verified realty_group_sl_groups row
                                        whose realty_group_id == auction.realty_group_id

AuctionEndTask.closeOne (SOLD path)  →  if case 3: agent_slice → listing agent's wallet
                                                   group_slice → group wallet
                                        (new AgentCommissionDistributor; case-1 legacy path retained until G)

CancellationService.brokerCancel (new)  →  authorize via MANAGE_ALL_LISTINGS  →  skip penalty ladder
                                            refund listing fee to group wallet (case 3) or seller (legacy case 1)

RealtyGroupMembershipService.leave / removeMember  →  case 3: reassign seller_id → leader
                                                                listing_agent_id stays stable (commission attribution)
                                                       case 1 legacy: existing reassignment of listing_agent_id
```

Three new code units worth naming:

- **`realty.slgroup`** sub-package — `RealtyGroupSlGroup` entity, repository, service, controller, about-text polling job, terminal callback handler.
- **`CancellationService.brokerCancel(...)`** — sibling to `cancel(...)`, takes the broker's userId, authorizes via permission, records a `BROKER_CANCEL` `CancellationOffenseKind` that bypasses the seller's penalty ladder.
- **`AgentCommissionDistributor`** — case-3 payout splitter beside D's existing `AgentFeeDistributor` (case-1 legacy).

One Flyway migration: `V27__realty_group_sl_groups.sql`.

---

## 3. Data Model

### 3.1 New table — `realty_group_sl_groups`

```sql
CREATE TABLE realty_group_sl_groups (
    id                              BIGSERIAL PRIMARY KEY,
    public_id                       UUID NOT NULL UNIQUE,
    realty_group_id                 BIGINT NOT NULL REFERENCES realty_groups(id),
    sl_group_uuid                   UUID NOT NULL,
    sl_group_name                   VARCHAR(255),
    verified                        BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at                     TIMESTAMPTZ,
    verified_via                    VARCHAR(20),                  -- ABOUT_TEXT | FOUNDER_TERMINAL
    verification_code               VARCHAR(32),                  -- nulled after verification
    verification_code_expires_at    TIMESTAMPTZ,
    last_polled_at                  TIMESTAMPTZ,                  -- about-text poll bookkeeping
    poll_attempts                   INTEGER NOT NULL DEFAULT 0,
    founder_avatar_uuid             UUID,                         -- recorded on FOUNDER_TERMINAL
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                         BIGINT NOT NULL DEFAULT 0,
    UNIQUE(sl_group_uuid)
);
CREATE INDEX ix_rg_sl_groups_realty_group ON realty_group_sl_groups(realty_group_id);
CREATE INDEX ix_rg_sl_groups_pending_poll
  ON realty_group_sl_groups(last_polled_at)
  WHERE verified = false AND verified_via IS NULL;
```

`UNIQUE(sl_group_uuid)` covers both pending and verified rows — at any moment an SL group has at most one row across the entire system. Pending rows expire via `verification_code_expires_at` and a sweeper deletes them, freeing the UUID for re-registration so squatting cannot persist indefinitely.

### 3.2 Column additions

```sql
ALTER TABLE realty_group_members
  ADD COLUMN agent_commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0;

ALTER TABLE auctions
  ADD COLUMN agent_commission_rate DECIMAL(5,4) NULL,
  ADD COLUMN realty_group_sl_group_id BIGINT NULL
    REFERENCES realty_group_sl_groups(id);
```

`auction.realty_group_sl_group_id` is the **case-3 discriminator**. NULL for individual listings and for legacy case-1 rows. `agent_commission_rate` on the auction is NULL for everything except case-3 auctions; it carries the snapshot of the member's rate at create-time.

### 3.3 Reuse without new columns

- `auction.listing_agent_id` — already exists. Set to the creator at create-time. **Stops reassigning on departure** in E (commission attribution).
- `auction.seller_id` — already exists. **Starts reassigning to leader on departure** in E for case-3 auctions (current responsible party).
- `auction.realty_group_id` — already exists from C. Set at create-time.

### 3.4 Deprecated by E (retained until G)

`auctions.agent_fee_rate`, `auctions.agent_fee_split`, `auctions.agent_fee_amt`, `realty_groups.agent_fee_rate`, `realty_groups.agent_fee_split`. None populated for new case-3 auctions; existing case-1 rows continue to use them through D's existing distributor.

### 3.5 Validation

`realty_group_members.agent_commission_rate >= 0`. No upper bound beyond `DECIMAL(5,4)` storage (range `[-9.9999, 9.9999]`, constrained to non-negative by application validation). Leader chooses what value is appropriate for the group; SLPA does not constrain.

---

## 4. Permissions Framework

### 4.1 Enum changes

```java
public enum RealtyGroupPermission {
    // A+B
    INVITE_AGENTS, REMOVE_AGENTS, EDIT_GROUP_PROFILE, CONFIGURE_FEES,
    // C (CREATE_LISTING unchanged; MANAGE_OWN_LISTING removed in E)
    CREATE_LISTING,
    MANAGE_ALL_LISTINGS,
    // D
    SPEND_FROM_GROUP_WALLET, WITHDRAW_FROM_GROUP_WALLET, VIEW_GROUP_TRANSACTIONS,
    // E (new)
    REGISTER_SL_GROUP;
}
```

`MANAGE_OWN_LISTING` is removed. Its C-era purpose (case 2 wiring) no longer exists. A `V27` step strips it from any existing `realty_group_members.permissions` arrays.

### 4.2 New wirings

| Permission | Endpoint(s) | Behavior |
|---|---|---|
| `REGISTER_SL_GROUP` | `POST /realty/groups/{publicId}/sl-groups` (register), `DELETE /realty/groups/{publicId}/sl-groups/{slGroupPublicId}` (unregister), `POST .../sl-groups/{slGroupPublicId}/recheck` (re-poll) | Leader-implicit. Delegable via existing edit-permissions surface. Defaults off on invitation. Holder can register/unregister and trigger manual rechecks. |
| `MANAGE_ALL_LISTINGS` | `POST /auctions/{publicId}/broker-cancel` | Leader-implicit. Delegable. Holder can cancel any active case-3 listing where `auction.realty_group_id` matches. Skips seller's penalty ladder (§11). |

### 4.3 Invitation defaults

New members get `REGISTER_SL_GROUP` and `MANAGE_ALL_LISTINGS` **off** by default — both are broker/leader-class flags. The leader explicitly grants them at invite time or afterwards. Matches A+B's invitation-default convention.

### 4.4 Leader-only stays leader-only

Transfer leadership, dissolve group, edit other members' permissions, edit other members' `agent_commission_rate`. No additions.

---

## 5. API Surface

All paths under `/api/v1`. UUID `publicId` in every path; `Long id` never crosses the wire.

### 5.1 SL group registration (new)

| Endpoint | Auth | Behavior |
|---|---|---|
| `POST /realty/groups/{publicId}/sl-groups` | `REGISTER_SL_GROUP` | Body: `{ "slGroupUuid": "uuid" }`. Backend fetches `sl_group_name` via `SlWorldApiClient.fetchGroupPage(uuid)`. Creates pending row + 12-char base32 verification code (`SLPA-XXXXXXXXXXXX`, no `0/O/1/l`). Returns `{publicId, slGroupUuid, slGroupName, verificationCode, verificationCodeExpiresAt, instructions}`. |
| `GET /realty/groups/{publicId}/sl-groups` | Group member | Lists registered SL groups (verified + pending), with verification code visible only to members of the realty group. |
| `DELETE /realty/groups/{publicId}/sl-groups/{slGroupPublicId}` | `REGISTER_SL_GROUP` | Unregister (hard delete the row). 409 if any non-terminal auctions reference this registration. |
| `POST /realty/groups/{publicId}/sl-groups/{slGroupPublicId}/recheck` | `REGISTER_SL_GROUP` | Manual trigger to re-poll about-text immediately (bypasses scheduler throttle). |
| `POST /sl/sl-group/verify` | LSL shared-secret + `X-SecondLife-Owner-Key` | Founder-terminal callback. Body: `{ "verificationCode", "founderAvatarUuid" }`. Backend looks up the pending row by code, fetches `world.secondlife.com/group/{row.sl_group_uuid}` to cross-check the founder, flips `verified=true`, `verified_via=FOUNDER_TERMINAL`. |

### 5.2 Broker cancel (new)

| Endpoint | Auth | Behavior |
|---|---|---|
| `POST /auctions/{publicId}/broker-cancel` | `MANAGE_ALL_LISTINGS` for `auction.realty_group_id` | Body: `{ "reason": "string" }`. Auction must be case 3 (`realty_group_sl_group_id IS NOT NULL`). Skips seller's penalty ladder. Refunds listing fee per origin (group wallet for case 3). |

### 5.3 Modified from C

- `POST /auctions` — when `listAsGroupPublicId` is set, validates the parcel is SL-group-owned AND that SL group is registered+verified to the named realty group. Mismatch → `ParcelNotOwnedByRegisteredSlGroupException` (422).
- `GET /realty/me/listing-eligible-groups` — gains required `slParcelUuid` query parameter. Filters to realty groups where the user has `CREATE_LISTING` AND a verified `realty_group_sl_groups` row matches the parcel's SL owner UUID. Personal-land parcels → empty array.

### 5.4 Modified from A+B

- Invitation create endpoint — request gains `agentCommissionRate: <decimal>`. Persisted onto the member row on invitation acceptance.
- Member edit-permissions endpoint — body gains `agentCommissionRate`. Leader-only setter on non-leader members (matches the existing edit-permissions auth rule).
- Member detail response — gains `agentCommissionRate`.

### 5.5 Domain exceptions added

| Exception | HTTP | Code |
|---|---|---|
| `SlGroupAlreadyRegisteredException` | 409 | `SL_GROUP_ALREADY_REGISTERED` |
| `SlGroupNotVerifiedException` | 422 | `SL_GROUP_NOT_VERIFIED` |
| `SlGroupVerificationExpiredException` | 410 | `SL_GROUP_VERIFICATION_EXPIRED` |
| `SlGroupFounderMismatchException` | 422 | `SL_GROUP_FOUNDER_MISMATCH` |
| `ParcelNotOwnedByRegisteredSlGroupException` | 422 | `PARCEL_NOT_OWNED_BY_REGISTERED_SL_GROUP` |
| `RegisteredSlGroupHasListingsException` | 409 | `REGISTERED_SL_GROUP_HAS_LISTINGS` |
| `SlGroupRegisteredBlocksDissolveException` | 409 | `SL_GROUPS_BLOCK_DISSOLVE` |
| `BrokerCancelNotApplicableException` | 422 | `BROKER_CANCEL_NOT_APPLICABLE` |

### 5.6 DTO shapes added

```jsonc
// RealtyGroupSlGroupDto (members of the realty group)
{
  "publicId": "uuid",
  "slGroupUuid": "uuid",
  "slGroupName": "string|null",
  "verified": true,
  "verifiedAt": "ISO-8601|null",
  "verifiedVia": "ABOUT_TEXT|FOUNDER_TERMINAL|null",
  "pending": {
    "verificationCode": "SLPA-...",
    "verificationCodeExpiresAt": "ISO-8601",
    "lastPolledAt": "ISO-8601|null",
    "pollAttempts": 0
  } | null,
  "founderAvatarUuid": "uuid|null"
}
```

```jsonc
// RealtyGroupMemberDto (extension)
{
  // ... existing fields
  "agentCommissionRate": 0.10
}
```

---

## 6. Frontend Surface

### 6.1 Listing wizard

After parcel lookup completes, call `GET /realty/me/listing-eligible-groups?slParcelUuid=<uuid>`:

- Empty + parcel is agent-owned by the user → no picker. Listing creates as Individual.
- Non-empty + parcel is SL-group-owned → picker shows one option per matching realty group. No Individual option (you cannot personally own group-owned land).
- The picker selection wires `listAsGroupPublicId` into the submit payload.

Fee-math preview reframes for case 3:

> If this lists at L$ {{startingBid}}, you'll receive **L$ {agentSlice}** (your {commissionRate}% commission of earnings after platform commission). **{Realty Group Name}** earns **L$ {groupSlice}**.
>
> `earnings = startingBid - floor(startingBid * 0.05)`
> `agentSlice = floor(earnings * agentCommissionRate)`
> `groupSlice = earnings - agentSlice`

### 6.2 SL group registration page (new)

Path: `/realty/groups/{slug}/sl-groups`. Visible to members with `REGISTER_SL_GROUP`.

- Table of registered SL groups: status (Pending / Verified), SL group name, verified-via label, last poll time, expiry countdown for pending rows, unregister button.
- "Register new SL group" → modal with SL group UUID input.
- On submit: page shows the verification code + dual instructions:
  - **About text:** "Set your SL group's About text to include `SLPA-XXXXXXXX`. Backend rechecks every 5 minutes; or use the Check now button below."
  - **Founder via terminal:** "Hand `SLPA-XXXXXXXX` to your SL group's founder. They step onto any SLPA terminal, choose 'SL Group Verify', and type the code."
- Pending rows display an expiry countdown.
- Manual "Check now" button posts to `.../recheck`.
- Unregister button requires confirmation; backend 409s if active listings reference the row.

### 6.3 Member edit drawer

- New row: `Agent commission rate` percentage input (e.g., "10.00 %"). Leader-edit-only on non-leader members. Members see their own rate read-only.
- Invite modal: same field below the permissions checklist.

### 6.4 Listing detail / card display

- Case-3 auction (`realty_group_id` and `realty_group_sl_group_id` both set): primary line reads "Sold by **{Realty Group Name}**" with sub-line "Listed by **{listingAgent.displayName}** of **{Realty Group Name}**." Group avatar + agent avatar both render.
- Individual / legacy case-1: existing display unchanged.

### 6.5 Broker cancel UI

- In the listing detail's actions strip, a "Broker cancel" button appears for users holding `MANAGE_ALL_LISTINGS` for the auction's realty group who are not the seller themselves. Hidden for the seller (uses regular cancel path).
- Confirmation modal shows auction title, active-bid state (bid count + current highest bid if any), listing-fee refund destination, and a required reason field. Submit disabled until reason is non-empty.

### 6.6 SSR

- SL group registration page: `export const dynamic = "force-dynamic"`. Pending state changes per visit.
- Listing wizard: already dynamic.

---

## 7. SL Group Verification Flow

### 7.1 Registration entry (shared)

Leader posts an SL group UUID. Backend:

1. Calls new `SlWorldApiClient.fetchGroupPage(uuid)` — mirrors existing `fetchParcelPage`. World API failures → 422 with diagnostic.
2. Creates `realty_group_sl_groups` row: `sl_group_uuid`, `sl_group_name` from page, `verified=false`, `verified_via=null`, `verification_code = "SLPA-" + 12 base32 chars (no 0/O/1/l)`, `verification_code_expires_at = now + 7 days`.
3. Returns the code + dual instructions to the caller.

Either verification path below flips the same row. First to arrive wins.

### 7.2 About-text method

Leader sets the SL group's About / Charter text to include `SLPA-XXXX...`. `SlGroupAboutTextPollTask` runs every 5 minutes via `@Scheduled`:

- Selects rows where `verified = false AND verified_via IS NULL AND verification_code_expires_at > now AND (last_polled_at IS NULL OR last_polled_at < now - 5 min)`.
- For each: fetch group page, scan for the verification code (case-sensitive substring match).
- Match → flip `verified=true`, `verified_at=now`, `verified_via='ABOUT_TEXT'`, `verification_code=null`, stamp `updated_at`.
- No match → increment `poll_attempts`, update `last_polled_at`.

Manual `.../recheck` runs one immediate poll for one row, ignoring the throttle.

### 7.3 Founder-via-terminal method

Leader hands the verification code to the SL group's founder out-of-band. Founder steps onto any `slpa-terminal` in-world, picks "SL Group Verify" from the menu, and types the code via `llTextBox`. The LSL script POSTs to `/api/v1/sl/sl-group/verify` with:

- Shared-secret HMAC + `X-SecondLife-Owner-Key` (existing LSL auth pattern).
- Body: `{ "verificationCode": "SLPA-...", "founderAvatarUuid": "<uuid from llDetectedKey>" }`.

Backend (`SlGroupVerificationService.handleTerminalCallback`):

1. Look up the pending row by `verification_code` AND `verification_code_expires_at > now`. 404/410 if missing/expired.
2. Fetch `world.secondlife.com/group/{row.sl_group_uuid}` via the same client.
3. Scrape the founder's avatar UUID from the page; compare to `founderAvatarUuid` from the terminal payload.
4. Match → flip `verified=true`, `verified_via='FOUNDER_TERMINAL'`, `founder_avatar_uuid=<reported>`, `verification_code=null`.
5. No match → 422 (`SlGroupFounderMismatchException`); terminal owner-says "Avatar is not the founder of the registered SL group."

The `slpa-terminal` LSL script gains the new "SL Group Verify" menu item in the same PR; deployment instructions in `lsl-scripts/slpa-terminal/README.md` updated.

### 7.4 Pending row cleanup

`SlGroupRegistrationExpiryTask` runs hourly via `@Scheduled`; deletes rows where `verified=false AND verification_code_expires_at < now`. Frees the SL group UUID for re-registration.

### 7.5 Re-verification

If a verified registration drifts (e.g., SL changes the founder), the row remains `verified=true` until an admin acts. Admin re-verify and force-unregister are **deferred to F (admin moderation)**. Day-to-day operators do not get re-verify in E.

---

## 8. Case-3 Listing Flow + Method C Changes

### 8.1 `RealtyGroupListingService.createGroupListing` (extension)

1. Load realty group + assert `CREATE_LISTING` (unchanged from C).
2. Look up parcel data via `ParcelLookupService` (already invoked by `AuctionService.create`).
3. **New:** if `parcel.owner_type != "group"` → `ParcelNotOwnedByRegisteredSlGroupException`. Personal land cannot list under a group in the new model.
4. **New:** find `realty_group_sl_groups` where `realty_group_id = group.id AND sl_group_uuid = parcel.owner_uuid AND verified = true`. Missing → `ParcelNotOwnedByRegisteredSlGroupException` (diagnostic: "The SL group that owns this parcel is not registered/verified to {Realty Group Name}").
5. Snapshot the caller's `agent_commission_rate` from their `realty_group_members` row.
6. Delegate to `AuctionService.create`; on the returned auction set:
   - `realty_group_id = group.id`
   - `realty_group_sl_group_id = sl_group.id`
   - `listing_agent_id = callerUserId`
   - `seller_id = callerUserId`
   - `agent_commission_rate = snapshot`
   - C-era `agent_fee_rate` / `agent_fee_split` stay NULL.

### 8.2 Method gate

The existing `GroupLandRequiresSaleToBotException` already forces group-owned parcels onto Method C. Methods A (`UUID_ENTRY`) and B (`REZZABLE`) already reject `ownertype=group`. No change here — case 3 inherits the existing gate.

### 8.3 `BotTaskService.complete` SUCCESS branch — case 3 extension

After the existing `authBuyerId` + `salePrice` checks and before the parcel-lock pre-check:

```java
if (auction.getRealtyGroupSlGroupId() != null) {
    RealtyGroupSlGroup reg = slGroupRepo.findById(auction.getRealtyGroupSlGroupId())
        .orElseThrow();
    UUID reported = body.parcelOwner();
    if (reported == null || !reported.equals(reg.getSlGroupUuid())) {
        task.setStatus(FAILED);
        task.setFailureReason("SL_GROUP_OWNERSHIP_MISMATCH");
        task.setCompletedAt(now);
        botTaskRepo.save(task);
        auction.setStatus(VERIFICATION_FAILED);
        auction.setVerificationNotes("Bot: SL group ownership mismatch. The parcel is not owned by the registered SL group.");
        auctionRepo.save(auction);
        return task;
    }
}
```

Guards against: the realty group registered SL group A but the parcel is owned by SL group B that is also set-for-sale to the escrow bot.

### 8.4 `OwnershipCheckTask` — case 3 expected-owner

For case-3 auctions, the expected owner becomes `realty_group_sl_groups.sl_group_uuid` (joined via `auction.realty_group_sl_group_id`), not `auction.seller.slAvatarUuid`. If the SL group sells the parcel off-platform, the existing flag-and-suspend flow fires the same way as for individual listings. Only the comparison logic needs the case-3 branch; the World API probe call is unchanged.

### 8.5 At SOLD close (`AuctionEndTask.closeOne`)

For case-3 auctions (`realty_group_sl_group_id IS NOT NULL`):

```
earnings        = final_bid - platform_commission
agent_slice     = floor(earnings * agent_commission_rate)  → listing_agent's wallet
group_slice     = earnings - agent_slice                   → group wallet
```

Implemented by new `AgentCommissionDistributor` invoked alongside D's existing `AgentFeeDistributor` for case-1 legacy. The escrow payout target routes to the group wallet (instead of seller's personal wallet) by branching on `realty_group_sl_group_id` in `EscrowService.createForEndedAuction` — same shape as D's branching for case-1 group listings, but the destination is the group wallet for the residual payout slice.

---

## 9. Per-Listing Commission Economics

### 9.1 Snapshot semantics

When a case-3 listing is created, `realty_group_members.agent_commission_rate` is read and written to `auction.agent_commission_rate`. The auction owns its own copy from that point — a leader rebalancing a member's default rate afterwards does not retroactively move money on already-created listings.

### 9.2 Validation

`agent_commission_rate >= 0`. No upper bound beyond `DECIMAL(5,4)` storage.

### 9.3 Computation at SOLD close (case-3 only)

Inside `AuctionEndTask.closeOne`'s transaction, after the existing terminal-outcome writes:

```
final_bid               = X
platform_commission     = floor(X * 0.05)
earnings                = X - platform_commission
agent_slice             = floor(earnings * a.agent_commission_rate)
group_slice             = earnings - agent_slice
```

Floor rounding matches D's existing convention. `agent_slice + group_slice == earnings` exactly (no L$ lost to rounding).

### 9.4 Snapshot persistence

`auction.agent_commission_rate` is written at create-time and never touched again. `agent_slice` and `group_slice` are computed on demand (not stored on the auction row) — the ledger entries record the actual L$ values that moved.

### 9.5 Commission persists across departure

Because `listing_agent_id` is stable (§10), the commission credit lands in the original creator's wallet even if they have left the group between listing creation and SOLD close.

### 9.6 Distributor wiring

For case 3, `payoutAmt = 0`. The agent slice and the group slice both flow
through `AgentCommissionDistributor` inside the payout-success path -- see
`TerminalCommandService.runZeroPayoutSuccessInline` (sub-project G §8.1) for
the post-G flow. The terminal round-trip is skipped because no L$ leaves
SLPA in-world; both wallet credits happen entirely inside backend.

```java
\ Sub-project G §8.1, inside TerminalCommandService.runZeroPayoutSuccessInline:
if (auction.getRealtyGroupSlGroupId() != null) {
    agentCommissionDistributor.distribute(
        auction, finalBidAmount, commissionAmt);  // credits agent_slice + group_slice
}
```

Case-1 has been removed by sub-project G §4 (`AgentFeeDistributor` deleted; the
group-listed-not-SL-group-owned branch no longer exists). All realty-group
listings post-G are case 3.

`EscrowService.createForEndedAuction` sets `payoutAmt = 0` for case 3 at escrow
creation time (unchanged from E). The success-path inline branch in
`TerminalCommandService.queuePayout` is what triggers
`AgentCommissionDistributor`; the terminal callback path is reserved for
non-case-3 escrows.

---

## 10. Member Departure / Reassignment

### 10.1 Revised C-era behavior

Today `RealtyGroupMembershipService.leave / removeMember` reassigns `auction.listing_agent_id → leader.id` for active/draft/pending-verification auctions. E splits this into two branches:

**Case-3 auctions** (`realty_group_sl_group_id IS NOT NULL`):

- `listing_agent_id` — **stays stable**. The departing member retains commission attribution.
- `seller_id` — **reassigns to leader**. Management/notification surface now shows the leader; listing display still reads "Listed by [original agent] of [Realty Group]".

**Case-1 legacy auctions** (`realty_group_id IS NOT NULL AND realty_group_sl_group_id IS NULL`):

- Unchanged from C. `listing_agent_id` reassigns to leader. Deprecated path; only matters until G migrates these rows.

**Individual auctions** (`realty_group_id IS NULL`):

- Unaffected.

### 10.2 Implementation

`RealtyGroupMembershipService` calls a new `AuctionRepository.reassignSellerToLeaderForCase3(oldUserId, groupId, leaderId)` and the existing `reassignListingAgentForCase1(...)` queries side by side, filtered by `realty_group_sl_group_id IS NOT NULL` vs `IS NULL`. Single transaction with the membership delete.

### 10.3 Leader departures

A+B forbids the leader from leaving without transferring leadership first. After transfer, the old leader is a normal member and can leave; their departure triggers the case-3 reassignment for any auctions where they were the listing agent.

### 10.4 Management auth after departure

For case-3, the listing remains manageable by:
- The new `seller_id` (the leader).
- Anyone with `MANAGE_ALL_LISTINGS` in the realty group.

The departed `listing_agent_id` user cannot manage it anymore (no longer a group member). Their commission attribution survives via the stable column; their permission to act on the listing does not.

---

## 11. Broker Cancel

### 11.1 Endpoint

`POST /api/v1/auctions/{publicId}/broker-cancel` — adjacent to the existing `PUT /auctions/{publicId}/cancel`.

### 11.2 Authorization

- Caller holds `MANAGE_ALL_LISTINGS` for `auction.realty_group_id`.
- Auction must be case 3 (`realty_group_sl_group_id IS NOT NULL`). Attempts on individual or legacy case-1 auctions return 422 (`BrokerCancelNotApplicableException`).

### 11.3 Service: `CancellationService.brokerCancel(brokerUserId, auctionId, reason, ip)`

Sibling to `cancel(...)`:

1. Pessimistic-lock the auction row (`findByIdForUpdate`) — same race-safety as seller cancel.
2. Status guard: same allowed-from set as `cancel(...)` (DRAFT through ACTIVE; not ENDED+).
3. Cancel-after-end guard: 422 if `endsAt` has passed.
4. Authorize via `RealtyGroupAuthorizer.assertCan(brokerUserId, group.id, MANAGE_ALL_LISTINGS)`.
5. Auction-must-be-case-3 guard: 422 otherwise.
6. **Skip the penalty ladder.** No seller-row lock, no `countPriorOffensesWithBids`, no ladder-index switch. Record a `CancellationLog` row with the new kind `BROKER_CANCEL` and `actor_user_id = brokerUserId`, `realty_group_id = group.id`. `amountL = null` (no L$ penalty).
7. Refund routing: if `listingFeePaid = true`, create a `ListingFeeRefund` row. D's existing `ListingFeeRefundProcessorTask` already routes by originating ledger row; for case-3 the listing fee was debited from the group wallet, so the refund credits back there.
8. Active-with-bids cancellation: bidders get the existing `AuctionCancelledEnvelope` broadcast.

### 11.4 `CancellationOffenseKind` extension

```java
public enum CancellationOffenseKind {
    WARNING, PENALTY, PENALTY_AND_30D, PERMANENT_BAN,
    // E (new)
    BROKER_CANCEL;          // not counted in countPriorOffensesWithBids
}
```

`countPriorOffensesWithBids` filters to seller-initiated kinds (`WARNING / PENALTY / PENALTY_AND_30D / PERMANENT_BAN`). `BROKER_CANCEL` rows are recorded for audit but never penalize the seller-of-record.

### 11.5 Notifications

- Original `listing_agent_id` user: "[Broker display name] cancelled your listing [title]. Reason: [reason]."
- Current `seller_id` user (= leader for departed members; same person for active members): the standard seller-side cancellation notification (existing path, no change to message).

### 11.6 UI confirmation

Broker-cancel modal shows: auction title, active-bid state (bid count + current highest bid if any), listing-fee refund destination, required reason field. Submit disabled until reason is non-empty.

---

## 12. Dissolution Gate Extension

Existing gates:
- C — `ActiveListingsBlockDissolveException` (DRAFT / PENDING_VERIFICATION / ACTIVE auctions exist).
- D — `GroupHasNonzeroBalanceException`; `GroupHasInFlightEscrowsException`.

E adds:
- **`SlGroupRegisteredBlocksDissolveException`** — 409 — fires if any `realty_group_sl_groups` row exists for the group (verified or pending). Leader must unregister all SL groups before dissolving.

### 12.1 Why hold pending rows accountable

A pending row squats on the `sl_group_uuid` unique index. Allowing dissolve while pending rows remain orphans UUIDs against any cleanup we would want later.

### 12.2 Unregister gate (sibling rule)

`DELETE /sl-groups/{publicId}` blocks with `RegisteredSlGroupHasListingsException` (409) when any case-3 auction references the registration in a non-terminal state. Leader cancels / waits for terminal, then unregisters.

### 12.3 Order of operations for winding down

1. Cancel / wait for case-3 listings to reach terminal status.
2. Unregister each SL group.
3. Drain group wallet (D's withdraw flow).
4. Dissolve.

---

## 13. Migration / C Cleanup Boundary

### 13.1 Flyway `V27__realty_group_sl_groups.sql`

1. `CREATE TABLE realty_group_sl_groups` + indexes (§3.1).
2. `ALTER TABLE realty_group_members ADD COLUMN agent_commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0`.
3. `ALTER TABLE auctions ADD COLUMN agent_commission_rate DECIMAL(5,4) NULL`.
4. `ALTER TABLE auctions ADD COLUMN realty_group_sl_group_id BIGINT NULL REFERENCES realty_group_sl_groups(id)`.
5. Strip `MANAGE_OWN_LISTING` from existing permissions arrays (precautionary; no production rows today):
   ```sql
   UPDATE realty_group_members
     SET permissions = array_remove(permissions, 'MANAGE_OWN_LISTING')
     WHERE 'MANAGE_OWN_LISTING' = ANY(permissions);
   ```

### 13.2 Case-1 boundary after E ships

| Surface | Behavior |
|---|---|
| Listing wizard | Never offers "List as Group" for personal-land parcels (parcel-driven picker). |
| `RealtyGroupListingService.createGroupListing` | Validates parcel is SL-group-owned at a verified registration. Personal land → 422. |
| Pre-existing case-1 auction rows | Keep functioning. D's `AgentFeeDistributor` handles their payout per existing snapshot. Case-1 reassignment of `listing_agent_id` on member departure remains. |
| New case-1 creation | Impossible. |

The case-1 code paths remain in the codebase as legacy but become unreachable from the API surface.

### 13.3 LSL changes in E

`slpa-terminal` gains an "SL Group Verify" menu item that prompts for the verification code via `llTextBox` and POSTs to `/api/v1/sl/sl-group/verify` with the toucher's avatar UUID and the code. The script + its README update land in the same E PR; deployment instructions per `lsl-scripts/` convention.

---

## 14. Out of Scope (Deferred)

Tracked in the new "Realty Groups: G" issue or in F (admin moderation). None of these block E from shipping a coherent case-3 experience.

| Item | Where it goes |
|---|---|
| Delete `AgentFeeDistributor`, drop `auctions.agent_fee_rate / agent_fee_split / agent_fee_amt`, drop `realty_groups.agent_fee_rate / agent_fee_split`, simplify `RealtyGroupListingService` | Realty Groups: G |
| Admin re-verify of an SL group registration (after drift) | Realty Groups: F |
| Admin force-unregister of an SL group (compliance action) | Realty Groups: F |
| Re-verify cadence for verified SL groups | Realty Groups: F |
| Bulk per-member commission-rate edit | UX polish; later |
| Per-member commission-rate analytics dashboard | F or later |
| Cross-realty-group SL-group transfer (one realty group hands an SL group to another without an unregister/re-register dance) | Out of scope indefinitely |

---

## 15. Acceptance Criteria

A leader registers an SL group via about-text; the row flips to verified within one poll cycle once the About text matches. The same leader unregisters and re-registers via founder-via-terminal; the founder steps onto `slpa-terminal`, types the code, and the row flips verified.

A member with `CREATE_LISTING` and a non-zero `agent_commission_rate` lists a parcel owned by the registered SL group. The wizard shows only "List as [Realty Group]" for that parcel — no Individual option. The fee preview displays the agent slice and group slice. On SOLD close, `agent_slice` credits the listing agent's wallet and `group_slice` credits the group wallet.

The same member departs; their old listing's `listing_agent_id` stays them, `seller_id` reassigns to the leader, and the listing's eventual commission still credits the departed member.

A second member with `MANAGE_ALL_LISTINGS` broker-cancels a case-3 listing. The listing fee refunds to the group wallet. The seller (= leader) receives no penalty-ladder hit. The `BROKER_CANCEL` row is recorded.

A leader attempts to dissolve the group while a verified SL registration exists. Dissolve is rejected with `SL_GROUPS_BLOCK_DISSOLVE`. After unregistering, dissolve succeeds (subject to existing wallet/balance gates).

A listing wizard request submits `listAsGroupPublicId` for a personal parcel. Backend rejects with `PARCEL_NOT_OWNED_BY_REGISTERED_SL_GROUP` (422).
