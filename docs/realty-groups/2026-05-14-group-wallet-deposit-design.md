# Group-wallet deposit (app + in-world) — design

**Date:** 2026-05-14
**Branch:** `feat/group-wallet-deposit`
**Builds on:** `docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md` (sub-project G, the original group-wallet spec)

## 1. Problem

There is no way today for a member of a realty group to fund the group's
wallet. Credits flow in only through auction activity (`LISTING_PAYOUT`,
`AGENT_FEE_CREDIT`, `LISTING_FEE_REFUND`, `DORMANCY_AUTO_RETURN`) or via
admin manual adjustment (`ADMIN_ADJUSTMENT`). The original group-wallet
spec §6 references a tooltip ("deposit L$Y to publish") that points at
an affordance which was never built.

This spec defines two parallel flows that let a member fund the group
wallet directly:

- **App flow** — transfer from the member's personal SLParcels wallet
  into the group wallet. Pure internal ledger move; no L$ leaves SL.
- **In-world flow** — pay L$ at a Second Life terminal, route the credit
  to a specific group instead of the payer's personal wallet.

Both flows are gated by a new per-member permission
`DEPOSIT_TO_GROUP_WALLET`. Leader holds it implicitly via the existing
`RealtyGroupAuthorizer` short-circuit.

## 2. Goals / non-goals

**Goals.**

- Members with the new permission can fund the group wallet from the app
  (debits their personal SLParcels wallet) or from an in-world terminal
  (debits the L$ they paid at right-click → Pay).
- The new permission grants both flows. Revocation gates both flows.
- Deposits are recorded as a single new ledger entry type
  `MEMBER_DEPOSIT` on the group ledger. The app flow additionally records
  a paired debit on the personal-wallet ledger as
  `GROUP_WALLET_DEPOSIT_DEBIT`. Source distinction (app vs in-world) is
  carried by the row's `actor_user_id` plus a freeform `description`.
- Deposits are final once recorded. No undo. The depositor's recourse for
  a misfire is the existing withdraw flow (with appropriate permission)
  or a social-layer ask.
- The new permission is exposed in the existing per-member Permissions
  modal on the Members tab.

**Non-goals.**

- No new prim type in-world. The existing `slpa-terminal.lsl` gains a
  third touch-menu option.
- No reversal / refund window. No "Undo deposit" button anywhere.
- No notifications. The ledger row IS the receipt — visible on both
  sides.
- No daily / weekly / total deposit cap. Per-call min/max only.
- No support for unlinked-avatar deposits ("public donation jar"
  shape) — the depositor's avatar must resolve to a `User` with a
  member row on the target group.

## 3. Auth model

**New permission:** `DEPOSIT_TO_GROUP_WALLET` added to
`com.slparcelauctions.backend.realty.permission.RealtyGroupPermission`.
Mirrors `WITHDRAW_FROM_GROUP_WALLET` in shape and surfacing. Leader
auto-passes via the existing `RealtyGroupAuthorizer.assertCan`
short-circuit on `user_id == leader_id`.

Agents start without the permission. The leader grants it explicitly
through the existing Permissions modal on the Members tab
(`PermissionsForm.tsx`). No bulk-edit endpoint changes.

**App flow gate.**
`authorizer.assertCan(userId, groupId, DEPOSIT_TO_GROUP_WALLET)` at the
top of the controller. Errors → 403.

**In-world flow gates.**

- `POST /api/v1/sl/wallet/avatar-groups` resolves the SL avatar UUID to
  a `User` and returns only groups where the user is a member **and**
  has `DEPOSIT_TO_GROUP_WALLET` (leader-implicit). Unknown avatar /
  unlinked account / zero eligible groups → empty list (not an error).
- `POST /api/v1/sl/wallet/group-deposit` re-checks the permission at
  credit time. The 60-second window between dialog selection and
  `money()` is bounded but non-zero; permission could have been revoked
  mid-flow. Mid-flow revocation → REFUND with reason
  `PERMISSION_REVOKED`.

**Suspension policy.** `RealtyGroupAuthorizer` already blocks all wallet
operations on suspended groups; deposit inherits this automatically.
Same for dissolved (`410`) and user-frozen.

## 4. Endpoints

### 4.1. App flow — `POST /api/v1/realty/groups/{publicId}/wallet/deposit`

**Auth:** JWT. Permission `DEPOSIT_TO_GROUP_WALLET`.

```jsonc
// Request
{
  "amount":         12345,            // L$ integer, range [1, configMax]
  "memo":           "Reimbursement",  // optional, max 200 chars
  "idempotencyKey": "<uuid>"          // required
}

// 200 OK
{
  "groupLedgerEntryId":    1234,
  "personalLedgerEntryId": 5678,
  "newGroupAvailable":     50000,
  "newPersonalAvailable":  87655
}
```

**Errors.**

| HTTP | Body code              | Cause |
|------|------------------------|-------|
| 400  | `INSUFFICIENT_BALANCE` | depositor's personal available < amount |
| 400  | `AMOUNT_OUT_OF_RANGE`  | amount < 1 or > configured max |
| 403  | `PERMISSION_DENIED`    | viewer lacks `DEPOSIT_TO_GROUP_WALLET` |
| 404  | `GROUP_NOT_FOUND`      | unknown publicId |
| 409  | `GROUP_SUSPENDED`      | suspended-state policy |
| 409  | `USER_FROZEN`          | depositor's wallet frozen |
| 410  | `GROUP_DISSOLVED`      | `dissolvedAt != null` |

**Transaction.** One atomic DB transaction does, in order:

1. Load group with PESSIMISTIC_WRITE on `realty_groups`.
2. Load depositor's `wallet` row with PESSIMISTIC_WRITE.
3. Check `wallet.available >= amount`; throw `INSUFFICIENT_BALANCE` if not.
4. Debit `wallet.balance -= amount`. (Available follows.)
5. Insert `wallet_ledger` row: type `GROUP_WALLET_DEPOSIT_DEBIT`, amount
   `-N`, idempotencyKey, description = `"Deposit to <groupName>" + (memo? " — " + memo : "")`.
6. Credit `realty_groups.balance_lindens += amount`.
7. Insert `realty_group_ledger` row: type `MEMBER_DEPOSIT`, amount
   `+N`, actor_user_id = depositor, idempotencyKey,
   description = `"Deposit from app wallet" + (memo? " — " + memo : "")`.
8. Commit.

**Idempotency.** `idempotencyKey` is unique-indexed on both ledger
tables. A replay with the same key returns the original ledger IDs and
**does not** re-debit / re-credit. The handler detects replay by
checking for an existing personal-ledger row with the key before step 4.

### 4.2. In-world flow — `POST /api/v1/sl/wallet/avatar-groups`

**Auth:** SL headers (`X-SecondLife-Shard`, `X-SecondLife-Owner-Key`) +
`sharedSecret` in body. Same auth shape as the other `/sl/wallet/*`
routes.

```jsonc
// Request
{
  "terminalId":   "...",
  "sharedSecret": "...",
  "avatarUuid":   "<UUID of touching avatar>",
  "after":        null                          // optional, alphabetical cursor for paging
}

// 200 OK
{
  "groups": [
    { "publicId": "...", "name": "Group Display Name" }
    // up to 12 entries per page, ordered alphabetically by name
  ],
  "hasMore":   false,                            // true if more pages remain
  "nextAfter": null                              // pass back as `after` to fetch the next page
}
```

**Behaviour.** Resolves `avatarUuid` to a `User` via
`user.slAvatarUuid`. Returns groups where the user has
`DEPOSIT_TO_GROUP_WALLET` (leader memberships pass implicitly).
Excludes dissolved + suspended groups. **Pages of 12** for the
terminal's `llDialog`; the response carries `hasMore` + `nextAfter`,
and when `hasMore` is true the terminal's last button reads "More…"
and re-POSTs with `after = nextAfter` for the next page. Pagination is
rare in practice (groups-per-avatar is in single digits even for power
users); the contract exists for safety, not as a common path.

Unknown avatar / unlinked account / zero eligible groups → `200 OK`
with `"groups": []`. The script handles the "no groups" UI message.
Auth failures (bad shard / owner / shared-secret) → `ERROR` with reason
(same shape as other `/sl/wallet/*` routes).

### 4.3. In-world flow — `POST /api/v1/sl/wallet/group-deposit`

**Auth:** SL headers + `sharedSecret`.

```jsonc
// Request
{
  "terminalId":       "...",
  "sharedSecret":     "...",
  "payerUuid":        "<UUID who paid via right-click Pay>",
  "groupPublicId":    "<UUID of target group>",
  "amount":           1000,
  "slTransactionKey": "<SL transaction key from money() event>"
}

// 200 OK | REFUND <reason> | ERROR <reason>
```

**Refund discipline.** L$ is in the script's hands by the time this
endpoint is called. Per `CLAUDE.md`, every post-auth failure path
returns `REFUND` (not `ERROR`) so the LSL script bounces the L$ back
to the payer. Reasons:

| Reason                | Cause |
|-----------------------|-------|
| `UNKNOWN_PAYER`       | `payerUuid` not linked to any SLParcels user |
| `UNKNOWN_GROUP`       | `groupPublicId` does not exist |
| `GROUP_DISSOLVED`     | `dissolvedAt != null` |
| `GROUP_SUSPENDED`     | suspended-state policy |
| `PERMISSION_REVOKED`  | user is no longer a member or permission was revoked since dialog |
| `USER_FROZEN`         | wallet-frozen status |
| `AMOUNT_OUT_OF_RANGE` | amount outside `[1, configMax]` |

`ERROR` is reserved for upstream auth rejection (bad shard / owner /
shared-secret / unknown terminal / unparseable UUIDs). The LSL script
refunds defensively on `ERROR` too, per the established
"always-refund-on-deposit-error" rule.

**Transaction.** Same shape as §4.1 steps 1, 6, 7, plus

- No personal-wallet side (the L$ came from outside SLParcels, same as
  `/sl/wallet/deposit`).
- `actor_user_id` on the group ledger row is the resolved depositor.
- Description = `"Deposit at terminal in <regionName>"` (region pulled
  from the terminal row).
- Idempotency key on the group ledger row is `slTransactionKey`.

## 5. Ledger entry types

### 5.1. Group ledger — new `MEMBER_DEPOSIT`

```java
public enum RealtyGroupLedgerEntryType {
    LISTING_FEE_DEBIT,
    LISTING_FEE_REFUND,
    AGENT_FEE_CREDIT,
    LISTING_PAYOUT,
    WITHDRAW_QUEUED,
    WITHDRAW_COMPLETED,
    WITHDRAW_REVERSED,
    DORMANCY_AUTO_RETURN,
    ADJUSTMENT,
    ADMIN_ADJUSTMENT,
    /**
     * Sub-project H -- member-initiated deposit into the group wallet.
     * Covers both the app flow (personal SLParcels wallet -> group
     * wallet) and the in-world flow (avatar pays L$ at a terminal,
     * routed to a chosen group). actor_user_id carries the depositing
     * member; description distinguishes "Deposit from app wallet" vs
     * "Deposit at terminal in <region>" and may carry an optional
     * 200-char user memo.
     */
    MEMBER_DEPOSIT
}
```

Flyway migration extends the `realty_group_ledger.entry_type` CHECK
constraint to include `'MEMBER_DEPOSIT'`. Migration follows the
existing additive pattern in `db/migration/`.

### 5.2. Personal-wallet ledger — new `GROUP_WALLET_DEPOSIT_DEBIT`

App flow only — the in-world flow has no personal-wallet side. Mirrors
`WITHDRAW_QUEUED` shape: negative amount, idempotency-keyed, description
carries the group name (and optional memo).

`WalletLedgerEntryType` already exists; add the new value plus a Flyway
migration to extend its CHECK constraint.

### 5.3. Pairing

The two ledger rows for an app-flow deposit share their
`idempotencyKey`. There's no foreign key linking the rows — that's
deliberate (the two ledgers live in different services and we don't
want cross-table FKs). The shared key lets admins and tests reconcile
the pair when they need to.

## 6. Limits

- **Minimum per call:** L$1. Anything less than L$1 is meaningless and
  the LSL `money()` event can't fire for a sub-L$1 amount anyway.
- **Maximum per call:** configurable. Property
  `slpa.realty.group-deposit-max-l`, default **L$500,000**. Mirrors the
  existing fat-finger ceiling on personal-wallet operations. Configurable
  by ops; admin can lift for known venues without a redeploy.
- **No daily / weekly / total cap.** The depositor's personal wallet
  balance (app flow) and their walking-around SL L$ (in-world flow) are
  the natural ceilings.

## 7. Frontend UX

### 7.1. `/groups/[slug]/manage/wallet` — "Add funds" button

The existing Wallet tab on the manage subtree renders the balance card,
the **Withdraw** button, and the ledger table. We add a primary **Add
funds** button next to **Withdraw**, before it visually.

- Disabled with tooltip "Requires Deposit to group wallet permission"
  when the viewer lacks `DEPOSIT_TO_GROUP_WALLET`.
- Click → opens an "Add funds" modal:

```
Add funds to <Group name>
─────────────────────────────────
  Amount (L$):  [          ]
  Available:    L$87,655 (your wallet)
  Memo (optional): [                ]
                   200 chars max

  This transfer is final. Use Withdraw to retrieve funds.

  [Cancel]                [Deposit L$N]
```

- Amount input is integer L$, clamped client-side to
  `[1, min(personalAvailable, configMax)]`. Inline error below the
  field on out-of-range.
- Memo is optional, 200-char limit. Mirrors the existing withdraw
  "reason" pattern.
- Submit → `POST .../wallet/deposit` with a client-generated
  idempotency UUID (single submission per modal open; resubmitting the
  same modal returns the same idempotency key).
- On success: close modal, invalidate `realtyQueryKeys.all` (broad
  invalidation to catch wallet + ledger + balance card; matches the
  bulk-edit pattern), invalidate the personal-wallet query, toast
  `"Deposited L$N to <group name>."`.
- Error mapping:
  - `INSUFFICIENT_BALANCE` → inline under amount: "Not enough funds in
    your wallet."
  - `AMOUNT_OUT_OF_RANGE` → inline under amount with the configured max.
  - `GROUP_DISSOLVED` / `GROUP_SUSPENDED` → close modal, toast the
    reason.
  - `PERMISSION_DENIED` (rare race) → close modal, toast.

### 7.2. Permissions modal on the Members tab

The existing `PermissionsForm` already iterates
`RealtyGroupPermission` values from the permission registry. Adding
`DEPOSIT_TO_GROUP_WALLET` to the enum + `permissionLabel()` map
("Deposit to group wallet") wires it through with no other component
changes.

### 7.3. Ledger row labels

**No separate `memo` column.** The user-supplied memo is concatenated
into the existing freeform `description` column with a `" — "`
separator on write (§4.1 step 5 and step 7, §4.3 description). The
frontend renders `description` verbatim — no parsing, no second-line
treatment. This keeps the schema additive-only and matches how
existing ledger types use `description`.

- **Group ledger** (Wallet tab ledger table + admin detail page):
  `MEMBER_DEPOSIT` renders the row's `description` directly. The label
  prefix the service writes is either `"Deposit from <depositor display
  name>"` (admin view, which can resolve `actor_user_id`) or `"Deposit
  from app wallet"` / `"Deposit at terminal in <region>"` (member-side
  view, which preserves the service's source-tag prefix).
- **Personal wallet ledger** (the depositor's own `/wallet` page):
  `GROUP_WALLET_DEPOSIT_DEBIT` renders the row's `description`
  directly (the service writes `"Deposit to <group name>"` plus the
  optional memo suffix). The row gets a link to
  `/groups/<slug>/manage/wallet` when the user is still a member,
  plain text otherwise — link wrapping is a render-time decision based
  on the user's current membership, independent of `description`.

### 7.4. Notifications

None. The ledger row IS the receipt — both the depositor and group see
it in their respective wallet views. Aligns with the original
group-wallet spec §10 ("wallet motion does not generate notifications
by itself").

## 8. In-world LSL flow

**Terminal:** the existing `slpa-terminal.lsl`. No new prim.

### 8.1. Touch-menu addition

Existing menu: `[Deposit, Withdraw]`. New menu: `[Deposit, Pay to group, Withdraw]`.

The "Pay to group" button is only present when both
`AVATAR_GROUPS_URL` and `GROUP_DEPOSIT_URL` are set in the config
notecard. If either is empty, the button is hidden — backwards
compatibility for not-yet-updated terminals, which keep working as
Deposit/Withdraw-only.

### 8.2. State machine

```
TOUCH "Pay to group"
   |
   v
POST /sl/wallet/avatar-groups (avatarUuid)
   |
   +---- groups = []  ----> llRegionSayTo "No eligible groups."
   |
   v
llDialog [groupName1, groupName2, ..., groupName12, "More...", Cancel]
   |
   +---- "More..." ----> POST /avatar-groups again with `after` cursor
   |
   v
Avatar picks <groupName>
   |
   v
Store pending-deposit-context slot:
   (avatarKey, groupPublicId, expiresAt = now + 60s)
llRegionSayTo "You have 60 seconds to right-click -> Pay -> enter L$ amount."
   |
   v
money(payer, amount) event
   |
   +---- slot exists & not expired ----> POST /sl/wallet/group-deposit
   |                                       |
   |                                       +---- OK    -> clear slot, owner-say success
   |                                       +---- REFUND -> clear slot, llTransferLindenDollars, owner-say reason
   |                                       +---- ERROR  -> clear slot, llTransferLindenDollars (defensive),
   |                                                       owner-say reason
   |
   +---- no slot or expired slot ----> existing /sl/wallet/deposit flow (personal credit)
```

The fall-through to personal deposit on no/expired slot preserves the
existing right-click → Pay behaviour exactly — terminals that never
update would continue to function (and the addition is opt-in via the
notecard URL keys).

### 8.3. Slot management

- New strided list at script-global scope:
  `pendingGroupDeposits = [avatarKey, groupPublicIdStr, expiresAt, ...]`.
- Per-avatar dedup — repeat "Pay to group" by the same avatar
  overwrites the existing slot. Last-write-wins; no surprise.
- Up to 4 concurrent slots (mirrors the existing withdraw-session cap
  for consistency). If 4 slots are occupied and a 5th avatar starts
  the flow, the oldest slot is evicted.
- The existing 10-second sweeper (running for withdraw sessions) is
  extended to also evict expired pending-deposit slots.
- One existing `llListen` handles dialog responses — no new listen.

### 8.4. Retry shape

Identical to the existing personal-wallet `/sl/wallet/deposit` retry
discipline: 10s / 30s / 90s / 5m / 15m, total ~22 minutes of trying.
Idempotency by `slTransactionKey`. After exhaustion: log CRITICAL and
refund.

### 8.5. Notecard config

Two new keys:

| Key                  | Description                                              |
|----------------------|----------------------------------------------------------|
| `AVATAR_GROUPS_URL`  | Full URL of `/api/v1/sl/wallet/avatar-groups`. Optional. |
| `GROUP_DEPOSIT_URL`  | Full URL of `/api/v1/sl/wallet/group-deposit`. Optional. |

Both optional — if either is empty, "Pay to group" is hidden from the
touch menu (backwards-compat).

### 8.6. README updates

`lsl-scripts/slpa-terminal/README.md` adds:

- Architecture section: a "Group-deposit flow" subsection mirroring the
  existing "Deposit flow" / "Touch flow" descriptions.
- Config table: the two new URL keys.
- Operations section: new log lines —
  - `SLParcels Terminal: group deposit ok L$<N> to <groupName>`
  - `SLParcels Terminal: group deposit refunded (<REASON>) L$<N> to <payer>`
  - `SLParcels Terminal: group deposit retry N/5: status=...`
- Smoke test additions:
  - Member-with-permission happy path.
  - Member-without-permission shows "no eligible groups".
  - Race: pick group, wait > 60s, pay → falls back to personal deposit.

### 8.7. Edge cases

| Case | Behaviour |
|------|-----------|
| Avatar picks group A, then picks "Pay to group" again and selects B | Slot overwritten in place. Pays into B on next `money()`. |
| Avatar picks Cancel on the group `llDialog` | No slot is set; no state change. Existing right-click → Pay behaviour (personal-wallet deposit) is unaffected. |
| Avatar starts on terminal A, pays at terminal B | Terminal B has no slot → personal deposit. Acceptable. |
| Group dissolved between selection and `money()` | REFUND `GROUP_DISSOLVED`. |
| Permission revoked between selection and `money()` | REFUND `PERMISSION_REVOKED`. |
| Avatar selects group with no matching active membership row | Backend returned the group in `/avatar-groups`, so this can only happen via revocation race → REFUND. |
| Backend unreachable during `/avatar-groups` POST | `llRegionSayTo` "unable to fetch groups, try again". No L$ at risk. |
| Backend unreachable during `/group-deposit` POST | Standard retry chain. If all retries fail, refund and log CRITICAL. |

## 9. Test surface

### Backend

- `RealtyGroupWalletControllerTest`:
  - App deposit happy path (leader, agent-with-permission).
  - Forbidden without permission (agent-without-permission).
  - Insufficient balance.
  - Amount out of range (min, max).
  - Group dissolved / suspended.
  - Idempotent replay returns same IDs.
- `SlWalletAvatarGroupsControllerTest`:
  - Unknown avatar → empty list.
  - Avatar member of N>0 groups but no permission → empty list.
  - Pagination — > 36 eligible groups (synthetic), `after` cursor flow.
  - Bad shared-secret → ERROR.
- `SlWalletGroupDepositControllerTest`:
  - Happy path, OK response, ledger row written.
  - Mid-flow permission revocation → REFUND `PERMISSION_REVOKED`.
  - Group dissolved → REFUND `GROUP_DISSOLVED`.
  - Idempotent replay on `slTransactionKey`.
  - Bad shared-secret → ERROR (no refund).
- `RealtyGroupWalletServiceTest`:
  - Atomic-pair invariant — if the personal debit fails, the group
    credit must not be visible.
  - Concurrent deposit + withdraw on the same wallet doesn't double-
    spend (PESSIMISTIC_WRITE coverage).

### Frontend

- `WalletTab.test.tsx`: Add-funds button disabled without permission,
  enabled with it.
- `AddFundsModal.test.tsx`: submit happy path, insufficient balance
  inline error, amount-out-of-range inline error, group-dissolved
  toast.
- `useGroupDeposit.test.tsx`: invalidates the broad realty key and the
  personal-wallet key on success; doesn't on error.
- `PermissionsForm.test.tsx`: new permission rendered with the right
  label.
- Personal-wallet ledger row test: new `GROUP_WALLET_DEPOSIT_DEBIT`
  type renders with group-link.

### LSL

No automated tests — covered in the README smoke-test section §8.6.

## 10. Migration / deploy order

The DB migrations and backend changes can land before the LSL terminal
deploys. Sequence:

1. Backend: add the new permission enum value, the two new entry-type
   enum values, the Flyway migration, the three new endpoints, the
   service layer. Behind no flag — the new code is dormant until
   anyone uses it.
2. Frontend: the new permission auto-appears in the Permissions modal
   from the registry; ship the "Add funds" modal + Wallet-tab button.
   At this point, leaders can already use the app flow.
3. LSL terminal: roll the script update + add the two URL keys to each
   deployed terminal's config notecard. Until this lands, the in-world
   flow is unavailable but the app flow works fine.

No clean-break URLs. No data backfill. Single deploy sequence.

## 11. Open questions

None. All design decisions confirmed in brainstorm.
