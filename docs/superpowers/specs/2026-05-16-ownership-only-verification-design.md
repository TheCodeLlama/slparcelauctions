# Ownership-Only Verification (Design)

**Date:** 2026-05-16
**Status:** Draft — pending review
**Author:** Heath / Claude
**Scope:** Backend (verification flow, bot task lifecycle, fraud-flag emissions), Bot (delete dead paths), Frontend (verification UI), Docs (FOOTGUNS, README).

## 1. Goal

Replace the three-method verification flow (UUID_ENTRY / REZZABLE / SALE_TO_BOT) with a single owner-UUID check against the SL World API. Stop the bot's auction-active monitor entirely — the World-API-based `OwnershipCheckTask` already covers that role, and the bot's variant adds price/auth-buyer checks that produce false-positive suspensions.

After this change:

- Verification is a single backend action: read parcel via World API, compare owner UUID to expected (seller's avatar for individual, registered SL group for case-3).
- ACTIVE auctions are monitored only for owner change, via the existing `OwnershipCheckTask` (World API).
- The bot has zero involvement in the listing-phase or active-phase verification surfaces.
- Sellers do nothing in-world to verify. No "Set Land for Sale to SLPAEscrow at L$999,999,999" step.

The bot continues to serve its other roles unchanged: SL IM dispatch, idle presence, and (separately decided in §6) escrow-phase ownership detection.

## 2. Why now

Today's flow has three real problems:

1. **False-positive suspensions.** `BOT_PRICE_DRIFT` and `BOT_AUTH_BUYER_REVOKED` fire on a single bot observation. The user has experienced a `BOT_PRICE_DRIFT` suspension without having changed anything in-world; the most plausible cause is a stale or transient SL read.
2. **Duplicate monitoring.** `OwnershipCheckTask` (World API) and `BotMonitorDispatcher.dispatchAuction` (bot teleport) both monitor ACTIVE auctions. The bot variant adds price/authBuyer checks that aren't load-bearing on the actual escrow flow.
3. **Seller setup friction.** SALE_TO_BOT requires a multi-step in-world setup (set land for sale, set buyer to SLPAEscrow Resident, set price to L$999,999,999). It's confusing and serves only as a proof-of-ownership handshake — not an actual transaction. The L$999,999,999 sentinel is so high specifically because nobody is meant to buy it; the sale-listing exists only so the bot can read `AuthBuyerID == SLPAEscrow`.

The third point is the key insight: the sale-to-bot is purely a verification trick, not a transfer mechanism. The actual end-of-auction transfer is seller-to-winner directly, detected by `EscrowOwnershipCheckTask` via World API (`backend/.../escrow/scheduler/EscrowOwnershipCheckTask.java:108-121`). The bot never buys land — it has no `BuyLand`/`ParcelBuyPacket` capability in `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs`. So the sale-to-bot handshake is doing one job (proving ownership) that the World API can do for free, via a fix already shipped in commit `1ebf55fb` (parcel owner UUID extraction from World API body link when meta is in its loading state).

## 3. Architecture (after)

### Listing creation
1. Seller picks a parcel (existing flow).
2. Seller pays listing fee (existing flow).
3. Seller clicks **Verify ownership**. Backend immediately calls `SlWorldApiClient.fetchParcelPage(parcelUuid)`.
4. Backend compares parcel owner UUID to the expected UUID:
   - Case-1 (individual): `parcel.ownerUuid == seller.slAvatarUuid` AND `parcel.ownerType == "agent"`.
   - Case-3 (group): `parcel.ownerUuid == realtyGroupSlGroup.slGroupUuid` AND `parcel.ownerType == "group"`.
5. Match → auction transitions DRAFT_PAID → ACTIVE.
6. No match → auction transitions DRAFT_PAID → VERIFICATION_FAILED with a copy that reflects the observed owner (e.g. "the parcel is owned by a different group" / "by a different avatar").

No `VERIFICATION_PENDING` intermediate state — the World API call is synchronous from the user's POV (one HTTP request, sub-second). Errors (timeout, 404 from World API) surface as VERIFICATION_FAILED with retryable copy.

### ACTIVE-state monitoring
- `OwnershipCheckTask.checkOne` runs on its existing schedule (already implemented). On owner mismatch → `SuspensionService.suspendForOwnershipChange`.
- **New: streak requirement.** Today the task suspends on a single observation. We add `consecutiveOwnerMismatches` to `Auction` (defaults to 0), increment on mismatch, reset on match. Suspend only when the counter crosses `slpa.ownership-monitor.mismatch-streak-threshold` (default **2**). This defends against transient World API blips without measurably weakening the fraud signal.
- The bot's `MONITOR_AUCTION` task type stops being created. The dispatcher code path for auction monitoring is removed; the bot worker's `ClassifyAuction` is removed.

### Escrow-phase monitoring
Unchanged. `EscrowOwnershipCheckTask` already uses World API and is the source of truth for transfer confirmation. See §6 for a separate decision on whether to keep the bot's `MONITOR_ESCROW` path or retire it too.

## 4. Code surface (delta)

### Backend

**Remove or no-op:**
- `VerificationMethod` enum stays as a column for historical rows but new auctions use a single internal kind; UI no longer exposes the choice.
- `BotMonitorDispatcher.dispatchAuction(...)` — entire method, plus the `BOT_PRICE_DRIFT`, `BOT_AUTH_BUYER_REVOKED`, `BOT_ACCESS_REVOKED` (auction variant) branches.
- `BotTaskService` paths that create `MONITOR_AUCTION` tasks.
- `Auction.expectedAuthBuyerUuid`, `Auction.expectedSalePriceLindens` — drop columns (or keep nullable for a release before removing).

**Keep but rename / extend:**
- `OwnershipCheckTask` — add the streak field and threshold. No structural change.
- `SuspensionService.suspendForOwnershipChange` — unchanged.
- `FraudFlagReason.BOT_PRICE_DRIFT`, `BOT_AUTH_BUYER_REVOKED`, `BOT_OWNERSHIP_CHANGED`, `BOT_ACCESS_REVOKED` enum values stay (historical rows reference them); no new emissions.

**Add:**
- `VerifyOwnershipService` (or fold into `AuctionService.verify`) — synchronous World-API ownership check, encoded above.

### Bot

**Remove:**
- `MonitorHandler.ClassifyAuction` and the `AUTH_BUYER_CHANGED` / `PRICE_MISMATCH` enum values from `MonitorOutcome`.
- `BotTaskResponse.ExpectedAuthBuyerUuid`, `ExpectedSalePriceLindens` — drop from the bot wire shape too.
- `VerifyHandler` — entire class, plus the `BotTaskType.VERIFY` enum value.

**Keep:**
- `MonitorHandler.ClassifyEscrow` — used by `MONITOR_ESCROW` (subject to §6).
- IM dispatch, idle parking, withdraw-group handling.

### Frontend

**Remove:**
- `VerificationMethodPicker` (just shipped — yes, it's now dead code, but we ship it knowing that's coming).
- `SaleToBotSetupPanel`, `VerificationMethodSaleToBot`, `VerificationMethodRezzable`, `VerificationMethodUuidEntry`.
- `VerificationInProgressPanel` — collapses to a brief spinner that the new synchronous flow probably doesn't even need.
- All copy referencing "Set Land for Sale", "SLPAEscrow Resident", "L$999,999,999".

**Add / change:**
- `ActivateClient` DRAFT_PAID branch renders a single **Verify ownership** button. Click fires `PUT /api/v1/auctions/{publicId}/verify` with no body. Success → router refresh, status flips to ACTIVE. Failure → render the reason inline with a Retry button.
- `ActivateStatusStepper` collapses to 3 steps (Draft, Paid, Active) — verifying is no longer a user-visible state.

### LSL

**Remove:**
- Any setup-instruction copy in the in-world terminals that references SALE_TO_BOT setup.

(Likely none — the terminals don't serve listing-creation copy. Sweep anyway.)

### Docs

- `CLAUDE.md` "in-world payment terminals" section — verify no SALE_TO_BOT references remain.
- `docs/implementation/FOOTGUNS.md` — drop any captured gotchas about the sentinel sale.
- `README.md` — verification-method section sweep.

## 5. State machine (verification)

```
DRAFT
  └── pay listing fee ──► DRAFT_PAID
                              └── click Verify ownership ──► [World API call]
                                                              ├── match  ──► ACTIVE
                                                              └── miss   ──► VERIFICATION_FAILED
                                                                              └── click Retry ──► DRAFT_PAID
```

No `VERIFICATION_PENDING` in the new flow.

## 6. Open question: bot `MONITOR_ESCROW`

`EscrowOwnershipCheckTask` uses the World API to detect transfer completion. The bot's `MONITOR_ESCROW` also runs in parallel (via `BotMonitorDispatcher.dispatchEscrow`), and reports `TRANSFER_COMPLETE`, `TRANSFER_READY`, etc. Two paths watching the same thing.

Options:
- **(a) Retire bot escrow monitor too.** World API is the source of truth; the bot version is redundant. Simplifies the bot to two roles: IM dispatch + idle. Cleaner architecture.
- **(b) Keep both.** Bot has a different failure mode than World API; running both catches different transient outages. Costs ~one bot session for the escrow watch task.

**Recommendation: (a),** assuming `EscrowOwnershipCheckTask`'s cadence is fast enough that buyers don't notice the difference. Confirm before shipping.

## 7. Migration

Two failure modes to manage:

1. **Listings in DRAFT_PAID waiting for the seller to pick a verification method.** On deploy, they automatically see the new single-button UI. Backend simply doesn't read `verificationMethod` for the new path.
2. **Listings in VERIFICATION_PENDING.** A bot task may be in flight (UUID_ENTRY callback awaited, REZZABLE terminal hit awaited, or SALE_TO_BOT bot polling). One-time backfill:
   - On deploy, scan for `status = VERIFICATION_PENDING` auctions; run the new World-API check immediately and transition each auction to ACTIVE (match) or VERIFICATION_FAILED (miss).
   - Cancel any in-flight bot `VERIFY` tasks for these auctions.

This is a one-shot batch executed by a deploy-gated job (single-tx, idempotent, logged per-auction). No long-tail "old code path" support beyond the deploy.

## 8. Rollout

Single PR. The change spans backend / bot / frontend and tests, but every part is mechanically straightforward, and we've already established (in this session) that:
- The bot has zero buy capability — the L$999M sale-to-bot was never a transfer mechanism, only a verification handshake.
- The World API exposes group owner UUIDs reliably (commit `1ebf55fb`).
- `OwnershipCheckTask` already handles case-1 and case-3 in production.

No phased rollout / feature flag. Memory `feedback_production_not_mvp` says "every design decision targets production shape" — split rollouts here would mean carrying both paths in code longer than necessary.

## 9. Risks & mitigations

| Risk | Mitigation |
|---|---|
| World API outage stalls verification | Surface a clear error, let the seller retry. No worse than today's UUID_ENTRY path, which also depends on World API. |
| Streak threshold of 2 still produces false positives | Configurable property; can ratchet to 3 if observed in prod. |
| In-flight VERIFY tasks get orphaned on deploy | Backfill job (§7) closes the loop deterministically before flipping any user-facing routing. |
| Sellers complain about losing the "set up the sale early" affordance | Out-of-band notification at auction end is unchanged. The seller still gets a "your auction sold, transfer to winner" message via existing notification infrastructure. |

## 10. Non-goals

- Changing the end-of-auction transfer mechanism. Seller still transfers directly to winner; bot still doesn't buy land. The transfer detection (World API) already works.
- Changing the bot's IM-dispatch role.
- Adding a brand-new monitoring mechanism. Reusing `OwnershipCheckTask` exactly as it stands plus a streak field.

## 11. Open questions for review

1. **§6 — escrow monitor:** retire bot `MONITOR_ESCROW` (option a) or keep both paths (option b)?
2. **§3 — streak threshold:** is `2` the right default, or do you want `3`?
3. **§7 — migration:** are you OK with a one-shot backfill at deploy time, or would you rather drain in-flight listings under the old rules?
4. **VerificationMethod column:** drop it now (clean break) or keep nullable for the next release and remove later?
