# Realty Groups: G — Final Cleanup Pass — Design

**Date:** 2026-05-12
**Issue:** [#246](https://github.com/TheCodeLlama/slparcelauctions/issues/246)
**Branch:** `feat/realty-groups-final-cleanup`
**Loads after:** F (admin moderation, #240) merged into `dev`.
**Goal posture:** Close every realty-groups deferred item so the realty-groups track has no outstanding TODOs once this ships.

---

## 1. Goal

Single PR into `dev` that does all of:

1. Removes the C-era case-1 code path + drops the five deprecated columns.
2. Lands every item that D and E silently deferred to F but F shipped without (admin wallet ops, SL-group withdraw, leader-terms banner gating, escrow case-3 PAYOUT fix, stale spec §9.6).
3. Lands the F-shipped polish items (`SystemUserResolver` field, reverify batch-size, `@Transactional` boundary cleanup).
4. Lands the in-scope items from F's "Out of scope — tracked separately" list (group reports threshold fan-out, dedicated reviews page, reverse-search ban gate at registration, `AdminActionType` UI label map). Drops the dead `SPEND_FROM_GROUP_WALLET` permission.
5. Adds the manual-test Postman folders that were skipped during C, D, E.

Items deliberately left deferred and the rationale for each are listed in §18.

## 2. Scope summary

**In:** Sections 4–14 below — corresponds to A–G + pulled-in OOS items in [issue #246](https://github.com/TheCodeLlama/slparcelauctions/issues/246).

**Out:** The four OOS items in §18 (user-side `WalletDormancyJob`, realtime ban broadcast, per-listing admin-suspend timer, Phase 8 reputation-driven bid gating, empty-due audit row).

**Pinned decisions** (from issue #246 brainstorm):

1. **Single PR**, all sections in `dev`. No soak between code-removal and schema-drop. Dev has no real case-1 rows.
2. **LSL touch in scope.** The escrow LSL graceful $0 PAYOUT handler ships in this PR. In-world deployment of the updated script is a separate manual ops step called out in the PR body.
3. **Admin wallet ops shape**: one new `RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT` value + freeform `reason` string. Direction is carried by the `amount` sign on the ledger row. Audit row uses a new `AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT`.

## 3. Architecture impact summary

| Layer | Touchpoints |
|---|---|
| Schema (Flyway V29) | Drops 5 columns + adds widened CHECK on `realty_group_ledger.entry_type` (for `ADMIN_ADJUSTMENT`) + 1 new column on `realty_groups` (`reports_threshold_notified` boolean) + `array_remove` scrub for the deleted `SPEND_FROM_GROUP_WALLET` permission. No new column for leader-terms — §7.5 reads the existing `User.realtyGroupLeaderTermsAcceptedAt`. |
| Java entities | `RealtyGroupLedgerEntryType`, `AdminActionType`, `RealtyGroupPermission`, `TerminalCommandAction`. |
| Backend services | `TerminalCommandService` (PAYOUT skip-for-case-3), `RealtyGroupWalletService` (admin adjust + SL-group withdraw destination), `RealtyGroupSlGroupService` (reverse-search gate), `RealtyGroupReportService` (threshold notification), `AuctionDtoMapper` (three N+1 fixes), `RealtyGroupListingService.listingEligibleGroups` (per-member rate in DTO), `RealtyGroupService` (delete case-1 paths). |
| Backend controllers | `AdminRealtyGroupWalletController` (new), `RealtyGroupWalletController` (withdraw extended), `RealtyGroupReviewsController` (new). `@Transactional` boundary moved from F's two moderation controllers down to their services. |
| Backend notifications | New `NotificationCategory.GROUP_REPORT_THRESHOLD_REACHED` + admin fan-out builder; case-3-aware tweak in seller payout notification body. |
| Bot worker | `WithdrawGroupHandler` (new) using `Self.GiveGroupMoney`. |
| LSL | `slpa-terminal.lsl` graceful $0 PAYOUT handler. |
| Frontend pages | `app/realty/groups/[publicId]/reviews/page.tsx` (new); admin wallet adjust modal (new); withdraw modal extended (recipient picker); `AgentFeePreview` replaced with `AgentCommissionPreview`; group profile page agent-fee-rate block removed. |
| Frontend types/clients | `AdminActionType` label map (new); group-reviews fetcher; admin-wallet-adjust API client; withdraw API client extended. |
| Postman | Three folder additions in the `SLPA` collection. |
| Docs | `DEFERRED_WORK.md` sweep (remove the C/D/E/F sections this G closes); `README.md` updates; `FOOTGUNS.md` carry-forwards; spec §9.6 in 2026-05-12-realty-groups-sl-group-listing-design.md synced. |

---

## 4. Section A — C-era code & schema removal

### 4.1 Code removal (commit before schema-drop)

Delete:

- `backend/src/main/java/com/slparcelauctions/backend/realty/AgentFeeDistributor.java` (and its unit tests). All call sites are unreachable since E reframed all new auctions as case-3.
- `RealtyGroupListingService` — the case-1 snapshot path that wrote `auctions.agent_fee_rate` + `auctions.agent_fee_split` + `auctions.agent_fee_amt` at create-time. Remove the snapshot logic; tighten the listing create method to its case-3 shape only. Update Javadoc.
- `RealtyGroupMembershipService.reassignListingAgentForCase1` — the case-1 reassignment branch and its tests. The method becomes either deleted entirely (if no other branches call it) or trimmed to the case-3-only path.
- `RealtyGroupService.touchesFees` + the `agentFeeRate` / `agentFeeSplit` branches in `updateRealtyGroup` (currently sets `group.setAgentFeeRate(req.agentFeeRate())`). Remove the fields from `UpdateRealtyGroupRequest`.
- The `agentFeeRate` field on `RealtyGroup` entity + `agent_fee_rate` / `agent_fee_split` columns from `@Column` annotations. (`@Column(name = "agent_fee_rate")` on `RealtyGroup.java` line 83-84 — and the matching `agentFeeSplit` field nearby.)
- The `agentFeeRate` field on `RealtyGroupPublicDto` (line 38). Frontend group profile page already only shows it via the C-era block we're deleting.
- Any C-era listing-integration tests that exercise the case-1 path. Refactor those that also assert case-3 shape to keep only the case-3 assertions.

### 4.2 Schema drop (single Flyway migration)

`backend/src/main/resources/db/migration/V29__drop_c_era_agent_fee_columns_and_widen_admin_adjust.sql`:

```sql
-- C-era column drops (sub-project G §4)
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_rate;
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_split;
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_amt;
ALTER TABLE realty_groups DROP COLUMN IF EXISTS agent_fee_rate;
ALTER TABLE realty_groups DROP COLUMN IF EXISTS agent_fee_split;

-- Widen entry_type CHECK to allow ADMIN_ADJUSTMENT (§7.2)
ALTER TABLE realty_group_ledger DROP CONSTRAINT IF EXISTS realty_group_ledger_entry_type_check;
ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_entry_type_check
    CHECK (entry_type IN (
        'LISTING_FEE_DEBIT','LISTING_FEE_REFUND','AGENT_FEE_CREDIT','LISTING_PAYOUT',
        'WITHDRAW_QUEUED','WITHDRAW_COMPLETED','WITHDRAW_REVERSED','DORMANCY_AUTO_RETURN',
        'ADJUSTMENT','ADMIN_ADJUSTMENT'));

-- Threshold-notification flag (§11)
ALTER TABLE realty_groups ADD COLUMN IF NOT EXISTS reports_threshold_notified BOOLEAN NOT NULL DEFAULT FALSE;
```

The migration runs **after** every Java reference to the dropped columns is gone in the same PR (commit-order discipline; see §15.2).

Project convention (mirrored by V28) is to widen CHECKs manually in the migration. `EnumCheckConstraintSync` (`backend/src/main/java/com/slparcelauctions/backend/common/EnumCheckConstraintSync.java`) runs at startup and is idempotent against an already-correct CHECK — both layers running is safe.

### 4.3 Acceptance for §4

- `git grep -E 'agent_fee_(rate|split|amt)'` returns zero hits across `backend/src/main/java`.
- `git grep AgentFeeDistributor` returns zero hits.
- `./mvnw test` passes including the case-3-only tests retained from the C-era removal.
- The migration applies cleanly against a freshly seeded dev database.

---

## 5. Section B — Frontend cleanup

### 5.1 Component replacement

Replace remaining `AgentFeePreview` usages with `AgentCommissionPreview` from E. Touchpoints (verify via `git grep AgentFeePreview` at task time — the listing wizard's "list as group" step is the known consumer).

### 5.2 Group profile page

Remove the C-era `agent_fee_rate` block on the public group profile page. Per-member commission rates already surface in the member-detail rows E added; the public-profile summary becomes "group description + member list" with no aggregate fee number.

Delete any frontend types fields that mirrored `agentFeeRate` on `RealtyGroupPublicDto`. Update Vitest fixtures.

### 5.3 Acceptance for §5

- `git grep AgentFeePreview` in `frontend/src/` returns zero hits.
- Group profile page renders without the C-era block; no console warnings.
- `npm run verify` passes.

---

## 6. Section C — DTO / wire cleanup

### 6.1 `AuctionDtoMapper` N+1 fixes (per Q5: all three)

Today the batch overloads (`listMine`, `search`) call `resolveGroupAttribution`, `resolvePrimaryPhoto`, and `resolveWinnerPublicId` once per row — three N+1s.

Refactor: add a private `MapperBatchContext` that pre-loads the three relations in three batch queries on entry to each batch overload, then passes the context down so each per-row resolve becomes a map lookup.

```java
private record MapperBatchContext(
        Map<Long, RealtyGroup> groupsById,
        Map<Long, AuctionPhoto> primaryPhotoByAuctionId,
        Map<Long, UUID> winnerPublicIdByAuctionId) {

    static MapperBatchContext build(List<Auction> auctions,
            RealtyGroupRepository groupRepo,
            AuctionPhotoRepository photoRepo,
            UserRepository userRepo) {
        Set<Long> groupIds = auctions.stream()
            .map(Auction::getRealtyGroupId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Set<Long> auctionIds = auctions.stream().map(Auction::getId).collect(Collectors.toSet());
        Set<Long> winnerIds = auctions.stream()
            .map(Auction::getWinnerUserId).filter(Objects::nonNull)
            .collect(Collectors.toSet());

        return new MapperBatchContext(
            groupRepo.findAllById(groupIds).stream()
                .collect(Collectors.toMap(RealtyGroup::getId, Function.identity())),
            photoRepo.findPrimaryForAuctions(auctionIds).stream()
                .collect(Collectors.toMap(AuctionPhoto::getAuctionId, Function.identity())),
            userRepo.findPublicIdsByIds(winnerIds));
    }
}
```

The single-DTO `toDto(Auction)` path keeps its existing per-row lookup shape (no behavior change for non-batch callers).

`AuctionPhotoRepository.findPrimaryForAuctions(Set<Long>)` and `UserRepository.findPublicIdsByIds(Set<Long>)` are added if they don't already exist. Match existing repo naming conventions.

### 6.2 `ListingEligibleGroupDto` — swap `agentFeeRate` for `agentCommissionRate`

`agentFeeRate` (the C-era group-level rate) becomes obsolete with the §4 column drops. Replace with `agentCommissionRate: BigDecimal` carrying the **current user's per-member** commission rate in that group.

```java
public record ListingEligibleGroupDto(
        UUID publicId,
        String name,
        String slug,
        String logoUrl,
        BigDecimal agentCommissionRate) {
}
```

The `listingEligibleGroups` query joins `realty_group_members.agent_commission_rate` for the requesting user; the per-row projection picks up the new field. The wizard removes its `useRealtyGroup` round-trip per parcel.

Update frontend type + the wizard's preview hook.

### 6.3 `AuctionGroupAttributionDto.realtyGroupSlGroupId` symmetric marker — **not adding** (per Q6).

Frontend keeps the current "non-null `realtyGroup` ⇒ case-3" rule. Documented in the `AuctionGroupAttributionDto` Javadoc as the deliberate post-E shape.

### 6.4 Acceptance for §6

- Backend `@SpringBootTest`-level repository test asserts that `mapper.toBatchDto(List<Auction>)` issues exactly 3 SELECT queries regardless of list size (verify with `@Sql` + `EntityManager.unwrap(Session.class).getStatistics()` or Hibernate stats interceptor).
- `ListingEligibleGroupDto.agentCommissionRate` returns the requesting user's per-member rate. Verified in `RealtyGroupListingServiceTest` with a fixture of (group A: user X has 0.10, user Y has 0.15) — calls as user X return 0.10, as user Y return 0.15.
- Frontend Vitest verifies the wizard no longer triggers `useRealtyGroup` when picking a group from the eligible list.

---

## 7. Section D — Admin wallet ops + SL-group withdraw + leader-terms banner

### 7.1 New enum values

`RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT` — added at the end of the enum (after `ADJUSTMENT`). CHECK constraint widened in V29 (§4.2).

`AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT` — added at the end. Existing CHECK on `admin_actions.action_type` is widened via the runtime `EnumCheckConstraintSync` at startup; verify that startup widening path catches this enum addition (if not, add a migration line for it in V29).

### 7.2 Admin wallet adjust endpoint (one endpoint, signed amount — per Q3 / Decision 3)

New controller `AdminRealtyGroupWalletController` and new service `AdminRealtyGroupWalletService`:

```
POST /api/v1/admin/realty-groups/{publicId}/wallet/adjust
Body:
{
  "amount": -2500,
  "reason": "Reverted accidental escrow double-payout per ticket SLPA-1234"
}
```

Validation:

- `amount != 0`
- `|amount|` <= a configurable ceiling (`slpa.realty.admin-wallet-adjust-max-l = 10_000_000` by default — sanity gate, not a real policy lever)
- `reason` non-blank, max 500 chars
- Caller has admin role (existing `@PreAuthorize` pattern in the moderation controllers)
- Group exists and is not deleted

Behavior:

- Writes one `RealtyGroupLedgerEntry` with `entryType=ADMIN_ADJUSTMENT`, `amount=<signed>`, `description=<reason>`, `actorAdminUserId=<admin>`.
- Adjusts `realty_groups.balance` by `amount` (atomic SQL `UPDATE … SET balance = balance + :amt`).
- If `amount > 0` (credit) and balance was below zero before, the credit clears the negative balance first.
- If `amount < 0` (debit) and the resulting balance would go below zero, returns 422 `INSUFFICIENT_GROUP_BALANCE` (reuses the existing leader-side exception, mapped to 422 by the wallet exception handler).
- Writes one `admin_actions` audit row with `actionType=REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT`, `entityType=REALTY_GROUP`, `entityId=<group.id>`, `details=<JSON {"amount": …, "reason": …}>`.
- Publishes a `GroupWalletBalanceChangedEnvelope` to the group's WS topic (reuse existing publisher).

Response: the updated `GroupWalletDto`.

Frontend: new admin tab card on the existing admin realty-group detail page with a `+ / -` toggle, an L$ amount input, and a required `reason` textarea. Disabled when balance lock is in place (e.g., group dissolved).

### 7.3 SL-group destination withdrawals

Extend `GroupWithdrawRequest`:

```java
public record GroupWithdrawRequest(
        @Positive long amount,
        @NotNull UUID idempotencyKey,
        @NotNull GroupWithdrawRecipient recipient) {
}

public enum GroupWithdrawRecipient { AVATAR, SL_GROUP }
```

No backward-compat default. The field is required (`@NotNull`). Slpa is pre-launch with no production clients, so all callers update atomically with this PR. Frontend wraps the existing withdraw modal with the picker; bot side doesn't read this field (server already resolves the destination before enqueuing the `TerminalCommandAction.WITHDRAW_GROUP`).

Backend behavior in `RealtyGroupWalletService.withdraw`:

- `AVATAR` path: existing flow, unchanged.
- `SL_GROUP` path:
  - Resolve the realty group's currently-registered `RealtyGroupSlGroup`. If none (or registration was force-unregistered), return 422 `SL_GROUP_NOT_REGISTERED`.
  - **If the registration row is `SUSPENDED` → return 422 `SL_GROUP_REGISTRATION_SUSPENDED`** (per Q7). Withdraw to the leader's avatar is still available.
  - **If the registration row has `drift_reason != null` but is not suspended → allow** (Q7).
  - Enqueue a new `TerminalCommandAction.WITHDRAW_GROUP` with `recipientUuid = <sl group uuid>`, `purpose = ADMIN_WITHDRAWAL` (reuse — the bot side knows it's a group destination from the action type).
  - Ledger writes the same `WITHDRAW_QUEUED` row pattern; `description` carries `"to SL group <name>"`.

`TerminalCommandAction` enum gains `WITHDRAW_GROUP`. CHECK constraint widened by runtime sync (verify) or explicit V29 line.

### 7.4 Bot worker — `WithdrawGroupHandler`

New `bot/src/Slpa.Bot/Tasks/WithdrawGroupHandler.cs`:

```csharp
public sealed class WithdrawGroupHandler : ITaskHandler {
    public async Task<TaskOutcome> Handle(BotTask task, IBotSession session, CancellationToken ct) {
        var slGroupUuid = UUID.Parse(task.RecipientUuid);
        var amountL = (int)task.AmountL;
        var memo = $"SLPA group wallet withdraw — ref {task.Id}";

        session.Client.Self.GiveGroupMoney(slGroupUuid, amountL, memo);

        // Same /payout-result POST shape as WithdrawHandler; the backend ledger
        // path is identical aside from the "to SL group" description.
        return await session.PostPayoutResult(task.Id, success: true, slTxnHint: null, ct);
    }
}
```

Wire the handler into the task-type dispatch table next to the existing `WithdrawHandler`. Tests use the existing `IBotSession` fake.

### 7.5 `leaderTermsAcceptedAt` on `GroupWalletDto`

`GroupWalletDto` gains `leaderTermsAcceptedAt: Instant?` populated from `User.walletTermsAcceptedAt` (the field already on the `User` entity from D's wallet plumbing — `User.java` line 347, column `wallet_terms_accepted_at`). The wallet DTO surfaces the leader's value by resolving the group's `leaderUserId` to the corresponding `User` row.

`GroupWalletDtoMapper` (or wherever `GroupWalletDto` is built — verify in `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWalletDtoMapper.java`) takes a `User leader` parameter and copies the timestamp.

Frontend `LeaderTermsBlockBanner` condition flips from "always render" to `wallet.leaderTermsAcceptedAt == null`. The banner now correctly hides for leaders who have accepted terms.

### 7.6 Acceptance for §7

- Admin can credit + debit any group wallet; both flows write the right ledger entry type, audit row, and WS broadcast.
- Withdraw modal shows the recipient picker; SL-group option disables with tooltip when registration is SUSPENDED; allows when only `drift_reason` is set.
- Bot test fakes `GiveGroupMoney`; integration test verifies the call shape.
- `LeaderTermsBlockBanner` renders for leaders without acceptance, hides for those with it. Vitest.

---

## 8. Section E — Escrow / notification / LSL (case-3 zero-payout)

### 8.1 Skip-terminal early-return in `TerminalCommandService.queuePayout`

Today (`backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java` line 82-88) every escrow payout enqueues a `TerminalCommandAction.PAYOUT` with `escrow.getPayoutAmt()`. For case-3, `payoutAmt = 0` and the round-trip to the terminal is meaningless.

Refactor: at the top of `queuePayout`, branch on `escrow.getPayoutAmt() == 0`:

```java
if (escrow.getPayoutAmt() == 0L) {
    log.info("Case-3 zero-payout escrow {} — skipping terminal round-trip; "
             + "running success path inline", escrow.getId());
    runZeroPayoutSuccessInline(escrow, OffsetDateTime.now(clock));
    return null; // no command enqueued
}
```

`runZeroPayoutSuccessInline` mirrors `handleEscrowPayoutSuccess`'s post-payout work (ledger write of `AUCTION_ESCROW_PAYOUT` with amount=0, escrow state transition to COMPLETED, seller notification, downstream `AgentCommissionDistributor` invocation), minus the bot/LSL interactions. Idempotency: gate on the escrow's current state — if `escrow.getState() == COMPLETED` already, no-op. This replaces the TerminalCommand-row idempotency check used on the normal callback path.

`queuePayout`'s return type changes from `TerminalCommand` to `Optional<TerminalCommand>` (empty for the case-3 zero-payout branch, present otherwise). The single caller (`EscrowPayoutService`) updates to handle `Optional`.

### 8.2 LSL defensive fallback

`lsl-scripts/slpa-terminal/slpa-terminal.lsl` already enforces SHARED_SECRET + header trust on inbound PAYOUT commands. Add a leading guard inside the PAYOUT command handler:

```lsl
if (amount <= 0) {
    // SLPA backend should never emit amount=0 after sub-project G,
    // but a stale command from before the deploy could still arrive.
    // Ack as success without attempting a 0-L$ transfer.
    llOwnerSay("SLParcels Terminal: ignoring 0-L$ PAYOUT for txKey=" + txKey);
    postPayoutResult(txKey, TRUE, "skipped-zero-amount");
    return;
}
```

(Exact placement near the existing `payoutResultReqId = llHTTPRequest(PAYOUT_RESULT_URL, ...)` site at line 711. The reply path stays the existing `postPayoutResult`.)

Update `lsl-scripts/slpa-terminal/README.md` with a one-liner under "Operations notes" pointing at this graceful-skip behavior.

### 8.3 Seller payout notification copy

Today the seller's `ESCROW_PAYOUT_COMPLETED` notification body reads "L$<payoutAmt> payout received". For case-3, `payoutAmt = 0` produces "L$0 payout received" — misleading.

Update `EscrowPayoutNotificationBuilder` (or wherever the body string is composed; verify by grepping for the existing body template):

```java
String body;
if (auction.getRealtyGroupId() != null) {
    // Case-3: meaningful events are the commission credit + group wallet credit.
    body = String.format(
        "Your auction payout is complete. L$%d agent commission paid; L$%d credited to %s group wallet.",
        commissionAmt, groupSliceAmt, groupName);
} else {
    body = String.format("L$%d payout received for %s.", payoutAmt, auctionTitle);
}
```

Subject stays "Auction payout processed" for both cases.

### 8.4 Spec §9.6 staleness sync

In `docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md` §9.6, the text reads "subtracts agent_slice" from `payoutAmt`. Update to match the implementation:

> For case 3, `payoutAmt = 0`. The agent slice and the group slice both flow through `AgentCommissionDistributor` inside the payout-success path — see `TerminalCommandService.runZeroPayoutSuccessInline` (sub-project G §8.1) for the post-G flow.

### 8.5 Acceptance for §8

- A case-3 auction reaches escrow COMPLETED without an enqueued `TerminalCommand` row. Integration test asserts `terminal_commands` count = 0 for that escrow's ID.
- The seller notification body for the same case-3 auction does NOT contain "L$0".
- The LSL `slpa-terminal.lsl` graceful-skip behavior is verified by manual deploy (PR body checklist) — automated test surface for LSL is not part of this PR.
- Spec §9.6 in the E design doc reads the post-G shape; diff is committed alongside the code.

---

## 9. Section F — Admin moderation polish

### 9.1 `SystemUserResolver` field on `BulkSuspendedListingExpiryTask`

`backend/src/main/java/com/slparcelauctions/backend/realty/moderation/BulkSuspendedListingExpiryTask.java` — drop the field, its constructor parameter, and any test fixture that injects a `SystemUserResolver` mock. The resolver lookup happens inside `AdminActionService.recordSystemAction`, so the per-task injection is dead.

### 9.2 `slpa.realty.sl-group.reverify-batch-size` property

Add to `RealtyGroupModerationProperties` (or wherever the reverify cadence config lives — verify):

```java
@Min(1)
private int reverifyBatchSize = Integer.MAX_VALUE;
```

`SlGroupReverifyTask.runOnce` caps its per-tick fetch to `props.getReverifyBatchSize()` via `PageRequest.of(0, batchSize)` (or an equivalent limit in the repository query if it returns a list directly).

Default value = `Integer.MAX_VALUE` (effectively unbounded, matching today's behavior). Operators can dial down via config + redeploy without code change.

### 9.3 `@Transactional` boundary on F's two admin moderation controllers (per Q8 / option A with C fallback)

Audit:

- `AdminRealtyGroupSuspensionController` (issue, lift, list-history methods)
- `AdminRealtyGroupBulkListingsController` (suspend, reinstate methods)

For each `@Transactional`-annotated controller method:

1. Identify the service method it calls.
2. If the controller invokes exactly one service method (1:1) → move `@Transactional` to the service entrypoint, drop from the controller, and verify with `./mvnw test`. Most methods will fall here.
3. If the controller composes multiple service calls in one transaction → keep `@Transactional` on the controller and add a `// tx spans <svcA>.<methodA> + <svcB>.<methodB>` comment explaining why. Document the exception class + method in the spec deviation list at PR-time.

Update `docs/implementation/CONVENTIONS.md` once with the resulting rule: *"Default transaction boundary is the service entrypoint. Controllers carrying `@Transactional` must add a single-line comment naming the composition. Reviewers reject controller-level `@Transactional` without that comment."*

### 9.4 Acceptance for §9

- `./mvnw test` passes after the move; no test reaches the controller layer for transaction-rollback semantics that were relying on the controller-level boundary.
- `git grep -E '@Transactional' backend/src/main/java/com/slparcelauctions/backend/realty/moderation/` only matches service files (or, where it matches a controller, the immediately-adjacent line carries the explaining comment).
- CONVENTIONS.md has the new rule paragraph; commit comment cross-links the spec.

---

## 10. Section G — Postman collection additions

Three folder additions under the `SLPA` workspace's collection (`8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`) + the `SLPA Dev` environment. Each new request chains variables using the existing `accessToken`, `userId`, `realtyGroupId`, etc. pattern.

### 10.1 `Realty Groups → List as Group`

- `POST /realty/groups/{publicId}/listings` — create a case-3 listing on behalf of the group.
- `GET /realty/me/listing-eligible-groups` — listing-wizard data source.

### 10.2 `Realty Groups → Dissolve gate`

- `GET /realty/groups/{publicId}/can-dissolve` — surfaces blockers (active listings, registered SL group, non-zero balance).
- `DELETE /realty/groups/{publicId}` — dissolve attempt; should fail with each blocker variant.

### 10.3 `Realty Groups → Wallet`

- `GET /realty/groups/{publicId}/wallet`
- `GET /realty/groups/{publicId}/wallet/ledger`
- `POST /realty/groups/{publicId}/wallet/withdraw` (avatar variant)
- `POST /realty/groups/{publicId}/wallet/withdraw` (SL-group variant — separate request with `recipient: SL_GROUP`)
- `POST /api/v1/admin/realty-groups/{publicId}/wallet/adjust` (admin credit + debit variants — two requests for the +/- shapes since the body shape is the same but easier to one-click)
- `POST /realty/groups/{publicId}/listings/{auctionId}/pay-listing-fee` (group-path listing fee)

### 10.4 SL → SL Group → Founder Terminal Verify

Audit `SlTerminalAuthFilter` to find the actual header name it reads (NOT `X-Slpa-Terminal-Auth` — that was a guess at E ship). Fix the request to match.

### 10.5 Acceptance for §10

- All new requests run successfully end-to-end against a freshly seeded dev backend via the Postman runner.
- The collection is exported and committed under the same git-tracked path the existing collection uses, if any (verify; if the collection lives only in Postman cloud, the PR body lists the additions for the user to reconcile).

---

## 11. Pulled-in #1 — Remove `SPEND_FROM_GROUP_WALLET`

Delete the enum value from `RealtyGroupPermission` (line 24 of the current file). Audit:

- No endpoint checks for it (verified — `git grep SPEND_FROM_GROUP_WALLET` only hits the enum + Javadoc).
- Any test fixture that assigns it to a member needs to drop the assignment (or replace with `WITHDRAW_FROM_GROUP_WALLET` if the test intent was "discretionary wallet access").
- Migration concern: `realty_group_members.permissions` is a Postgres `TEXT[]` of enum names. Existing rows that happen to contain `SPEND_FROM_GROUP_WALLET` (from earlier dev data) need cleanup or the enum-decode path will fail.

V29 adds:

```sql
-- §11 — drop dead SPEND_FROM_GROUP_WALLET permission
UPDATE realty_group_members
   SET permissions = array_remove(permissions, 'SPEND_FROM_GROUP_WALLET')
 WHERE 'SPEND_FROM_GROUP_WALLET' = ANY(permissions);
```

Acceptance: `git grep SPEND_FROM_GROUP_WALLET` returns zero hits; dev DB query of `permissions` columns returns no rows containing the value.

---

## 12. Pulled-in #4 — Group report-threshold notification fan-out

### 12.1 New notification category

`NotificationCategory.GROUP_REPORT_THRESHOLD_REACHED` — appended to the existing enum.

### 12.2 Configuration

```yaml
slpa:
  reports:
    group-alert-threshold: 3
```

Bound by a `@Min(1)` validated property. Tests use `@TestPropertySource(properties = "slpa.reports.group-alert-threshold=2")` to keep fixtures small.

### 12.3 Behavior (one-shot per cycle — per Q12)

`RealtyGroupReportService.submit` calls a new `tryFireThresholdNotification(group)` after incrementing `openReportCount`:

```java
private void tryFireThresholdNotification(RealtyGroup group) {
    if (group.isReportsThresholdNotified()) {
        return;
    }
    long openReports = reportRepo.countByGroupIdAndStatus(group.getId(),
        RealtyGroupReportStatus.OPEN);
    if (openReports < props.getGroupAlertThreshold()) {
        return;
    }
    group.setReportsThresholdNotified(true);
    groupRepo.save(group); // idempotent within the @Transactional submit boundary
    notificationPublisher.groupReportThresholdReached(group);
}
```

When the *last* open report for a group is resolved or dismissed (transitions take `openReportCount` to 0), `RealtyGroupReportService.resolve` / `.dismiss` resets the flag:

```java
if (openReportCount == 0) {
    group.setReportsThresholdNotified(false);
    groupRepo.save(group);
}
```

Re-arming happens automatically: next time the group crosses the threshold again, the fan-out fires once more.

### 12.4 Fan-out target

`NotificationPublisher.groupReportThresholdReached(group)` resolves the set of currently-active admin users via the existing admin-resolver (the same one F's report queue uses) and fan-outs an SL-IM-channel notification to each:

> Subject: Realty group "{group.name}" reached {threshold} open reports
> Body: Review the queue at /admin/realty-groups/{publicId}/reports

### 12.5 Storage

Column added in V29: `realty_groups.reports_threshold_notified BOOLEAN NOT NULL DEFAULT FALSE`.

### 12.6 Acceptance for §12

- Integration test: submit 3 reports against the same group (with `slpa.reports.group-alert-threshold=3`) — exactly 1 notification fires. Submit a 4th — no additional notification. Resolve all 3, then submit 3 more — second notification fires.
- Notification body contains the group name and the queue path.

---

## 13. Pulled-in #6 — Dedicated group reviews page

### 13.1 Backend endpoint

```
GET /api/v1/realty/groups/{publicId}/reviews?page=0&size=20
```

Anonymous-accessible (mirrors the existing user-side public reviews endpoint's auth posture).

Response: `PagedResponse<GroupReviewRowDto>` where:

```java
public record GroupReviewRowDto(
        UUID reviewerPublicId,
        String reviewerDisplayName,
        int rating,                    // 1-5
        String comment,                // nullable
        UUID auctionPublicId,
        String auctionTitle,
        Instant createdAt) {
}
```

Query (JPQL):

```java
SELECT new GroupReviewRowDto(...)
  FROM Review r
  JOIN r.auction a
  JOIN r.reviewer u
 WHERE a.realtyGroupId = :groupId
 ORDER BY r.createdAt DESC
```

New controller `RealtyGroupReviewsController` + new service method on (or new) `GroupRatingService.listReviews(Long groupId, Pageable)`. Reuse `GroupRatingService` if it already exists (verify; F added it for the badge).

### 13.2 Frontend page

`frontend/src/app/realty/groups/[publicId]/reviews/page.tsx`:

- Header: group name + `GroupRatingBadge` inline.
- Paginated list: reviewer avatar (link to user profile) + rating stars + comment + auction title (link to auction) + relative date.
- Empty state: "This group has no reviews yet."
- `export const dynamic = "force-dynamic"` per the SSR caveat in CLAUDE.md.

### 13.3 `GroupRatingBadge` updated link

`GroupRatingBadge` (from F) currently links to `/profile/{leaderSlug}/reviews` as a stopgap. Update to `/realty/groups/{publicId}/reviews`.

### 13.4 Acceptance for §13

- Backend test verifies the endpoint returns only reviews on auctions where the realty group is the attribution (does NOT include user-side reviews where the leader was a buyer/seller outside the group).
- Frontend Vitest renders the page from MSW fixtures.
- `GroupRatingBadge` link assertion updated.

---

## 14. Pulled-in #8 — Reverse-search at registration (ban-evasion hard block — per Q10)

### 14.1 Backend gate

`RealtyGroupSlGroupService.register(...)` (or wherever the SL group registration entry-point lives — verify) gains a precondition check:

```java
boolean conflictingSuspended = slGroupRepo
    .existsBySlGroupUuidAndRealtyGroupSuspended(slGroupUuid);
if (conflictingSuspended) {
    throw new SlGroupRegisteredToSuspendedGroupException(slGroupUuid);
}
```

The "is suspended" state of a realty group is determined by an active row in `realty_group_suspensions` (F's pattern: `WHERE lifted_at IS NULL`), not a boolean column on `realty_groups`. New repository method on `RealtyGroupSlGroupRepository`:

```java
@Query("""
    SELECT CASE WHEN COUNT(s) > 0 THEN TRUE ELSE FALSE END
      FROM RealtyGroupSlGroup s
     WHERE s.slGroupUuid = :slGroupUuid
       AND EXISTS (
           SELECT 1 FROM RealtyGroupSuspension sus
            WHERE sus.realtyGroup = s.realtyGroup
              AND sus.liftedAt IS NULL
       )
    """)
boolean existsForSuspendedRealtyGroup(UUID slGroupUuid);
```

### 14.2 New exception + handler

`SlGroupRegisteredToSuspendedGroupException` extends `RuntimeException`. `RealtyExceptionHandler` maps it to 409 Conflict with body `{ code: "SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP", message: "This SL group is registered to a suspended SLPA realty group. Contact support." }`.

### 14.3 Frontend translation

The SL-group registration form surfaces the 409 with the message verbatim and a "Contact support" mailto/link to wherever the support address lives in env config.

### 14.4 Acceptance for §14

- Integration test: create a realty group, suspend it via `RealtyGroupSuspensionService`, register an SL group UUID `X` to it (the suspension shouldn't auto-unregister), then attempt to register the same UUID `X` to a different fresh realty group — the second registration returns 409 with the right code.
- Unsuspending the first group does NOT auto-allow the second registration (it requires a fresh attempt; that's fine — error message tells them to retry).
- Frontend Vitest: the form renders the error inline.

---

## 15. Pulled-in #9 — `AdminActionType` label map

Frontend-only. New file `frontend/src/lib/admin/action-type-labels.ts`:

```ts
import type { AdminActionType } from "@/types/admin";

export const ACTION_TYPE_LABELS: Record<AdminActionType, string> = {
  DISMISS_REPORT: "Dismiss report",
  WARN_SELLER_FROM_REPORT: "Warn seller (from report)",
  // … one entry per enum value …
  REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT: "Realty group wallet adjustment",
  REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER: "Force-unregister SL group",
  // …
};

export function labelFor(action: AdminActionType): string {
  return ACTION_TYPE_LABELS[action] ?? toTitleCase(action);
}

function toTitleCase(raw: string): string {
  return raw.toLowerCase().split("_").map(w =>
    w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}
```

Audit-log row renderer consumes `labelFor(row.actionType)`. The fallback handles any future enum value that lands without a label-map update.

No backend change; no i18n setup. Acceptance: Vitest snapshot of the audit-log row component renders human labels.

---

## 16. PR shape

Single PR into `dev` per Decision 1. Commit ordering matters for `dev`-pipeline safety:

1. **Code removal commits** (Section A code, Sections B/C/D/E/F/G/§11/§12/§13/§14/§15 changes) — multiple commits, one per slice.
2. **Schema migration commit** (V29) — runs last so every reference to dropped columns is already gone.
3. **Docs commits** — DEFERRED_WORK sweep, README, FOOTGUNS carry-forwards, spec §9.6 sync.

PR body must include:

- Manual ops steps for in-world LSL deployment of `slpa-terminal.lsl` (Section 8.2).
- Postman collection additions if the collection isn't git-tracked.
- An acknowledgement that the `slpa.realty.admin-wallet-adjust-max-l` ceiling is a sanity gate, not a policy lever — operators tune via config if a legitimate large adjustment is ever blocked.

## 17. Migration concerns

`dev` and `prod` have **no real case-1 auction rows** at G ship time (E closed case-1 wizard before any landed; prod has no users yet anyway). If a case-1 row were to exist in some legacy environment, the V29 column drops would silently lose the snapshotted fee data — fine because case-1 economic distribution is unreachable from the post-E code.

`realty_group_members.permissions` rows containing `SPEND_FROM_GROUP_WALLET` are scrubbed by the V29 `array_remove` statement (§11). If any non-dev environment had such rows they're cleaned in place.

`realty_groups.reports_threshold_notified` column lands with `DEFAULT FALSE` so all existing groups start with the flag unset — correct: any group currently at-or-above the threshold will fire its notification on the very next report submission. A one-time post-deploy scan could pre-fire notifications for groups currently at-or-above the threshold, but the count is small enough at G ship that letting natural traffic re-arm them is fine.

## 18. Out of scope (left deferred, tracked separately)

The following items from issue #246's "Out of scope" block stay deferred. Each entry in `DEFERRED_WORK.md` either keeps its current text or is rewritten to point at the post-G clean-shape rationale.

1. **User-side `WalletDormancyJob`** — user-wallet concern, not realty-group. Stays in the wallet-track DEFERRED_WORK section, not the realty-group section.
2. **Realtime ban broadcast / forced-logout WebSocket** (groups) — same posture as the user-side deferred broadcast (FOOTGUNS §F.106). When user-side ships, the group analog lands with it so the WS topic/payload shape stays unified.
3. **Per-listing admin-suspend timer** — kept indefinite by prior direction.
4. **Phase 8 reputation-driven bid-eligibility gating** — applies to all bidders, not realty-group-specific. Stays under Phase 8.
5. **Empty-due audit row** for `BulkSuspendedListingExpiryTask` / `GroupSuspensionExpiryTask` — deliberate behavior; revisit only if compliance reporting requires it.

After G ships, `DEFERRED_WORK.md`'s sub-project C, D, E, F sections are pruned to only these five remaining items (renormalized to their natural owners — wallet track for #1, user-track for #2, audit-track for #5, indefinite for #3 and #4).

## 19. Acceptance criteria (top-level)

The PR is mergeable into `dev` when all of:

- `./mvnw test` passes (backend + integration).
- `cd frontend && npm run verify` passes (lint, guards, coverage).
- `cd bot && dotnet test` passes.
- V29 migration applies cleanly against a freshly seeded dev DB.
- Per-section acceptance lists in §§4.3, 5.3, 6.4, 7.6, 8.5, 9.4, 10.5, 11, 12.6, 13.4, 14.4, 15 all pass.
- Postman collection additions run green via the runner against a fresh dev backend.
- README.md and `DEFERRED_WORK.md` sweeps reflect the new shape.
- `git grep -E '(AgentFeeDistributor|agent_fee_(rate|split|amt)|SPEND_FROM_GROUP_WALLET|reassignListingAgentForCase1)'` returns zero hits in `backend/src/main/java`, `frontend/src/`, `bot/src/`.

## 20. References

- Issue: [#246](https://github.com/TheCodeLlama/slparcelauctions/issues/246).
- E spec (case-3 reframe): `docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md` §9.6, §13–14.
- F spec (admin moderation): `docs/superpowers/specs/2026-05-12-realty-groups-admin-moderation-design.md` §22.
- D spec (wallet): `docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md`.
- C spec (case-1 plumbing being removed): `docs/superpowers/specs/2026-05-11-realty-groups-listing-integration-design.md`.
- DEFERRED_WORK ledger: `docs/implementation/DEFERRED_WORK.md` (sub-project C, D, E, F sections — the items closed by G are removed when G ships).
- FOOTGUNS: `docs/implementation/FOOTGUNS.md` §F.106 (user-side ban-broadcast deferral that informs §18 item 2).
