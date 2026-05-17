# Wallet-Only Escrow Funding (Design)

**Date:** 2026-05-16
**Status:** Draft — pending review
**Author:** Heath / Claude
**Scope:** Backend (escrow funding, bid reservation, terminal callback), Frontend (escrow page, AUCTION_WON notification copy, bid form), LSL (terminal scripts), Docs.

## 1. Goal

Make the auction winner's escrow payment **automatic** at auction end, debited from their SLParcels wallet. Retire the in-world terminal as a payment channel for escrow funding. The terminal stays alive for wallet top-ups, listing-fee payments, group-wallet deposits, and withdrawal receipts — escrow is the only flow that leaves it.

After this change:
- Bidder places a bid → wallet hard-reserves the L$ amount (existing `BidReservation` mechanism).
- Outbid → reservation swaps to the new high bidder, prior bidder's funds release back to `available`.
- Win → escrow auto-funds from the reservation in the same transaction as `createForEndedAuction`. No buyer action required.
- Notification reads "L$X transferred from your wallet to escrow," not "pay within 24 hours."
- `/auction/[publicId]/escrow` is a read-only status surface — no Pay button.
- Bidding a parcel requires the L$ to already be in the wallet. Top-ups happen out-of-band via the terminal deposit flow (unchanged).

## 2. Why now

Three real problems with today's hybrid model:

1. **The flow is broken in prod.** The frontend's `AUCTION_WON` notification copy says "Pay L$X into escrow" and routes to `/auction/[publicId]/escrow`, but the page renders no terminal instructions — the user has no idea how to pay. Today's only working path is "walk to a SLParcels terminal in-world and pay." That's invisible to web-only users.
2. **The architecture is already half-built for wallet-only.** `EscrowService.createForEndedAuction` lines 174-229 implement the exact wallet auto-fund flow described above, gated behind `slpa.wallet.enforcement-enabled` (defaults `false`, not set in prod). `BidService` mirrors the gate at the bid path. The code is dormant.
3. **It matches the stated wallet policy.** Per memory `feedback_always_refund_on_deposit_error` and the broader wallet-model directive: L$ should flow through the SLParcels wallet, and only specific outbound paths (withdrawals, admin disbursements, no-activity gates, escrow payouts to sellers) hit avatar accounts. Bidder→escrow is an inbound path that belongs in the wallet, not in-world.

## 3. Architecture (after)

### Bid placement
Unchanged in shape, enabled in fact:
1. `BidService.placeBid` validates `available >= amount` on the bidder's wallet (already gated on `walletEnforcementEnabled` at line 152 — that gate just becomes always-true).
2. Hard reservation swap: prior bidder's reservation on this auction released, new bidder's reservation written for the new high bid.
3. On outbid, the prior bidder's `available` rises again the moment a new high bid commits.

### Auction end
Unchanged in shape, enabled in fact:
1. `EscrowService.createForEndedAuction` (called from `AuctionEndTask`, `BidService` buy-now path, `AdminAuctionService.forceEnd`) creates the `Escrow` row in `ESCROW_PENDING`.
2. Inside the same transaction, if the auction has a winner, calls `walletService.autoFundEscrow(...)` which consumes the winner's `BidReservation` and writes a `user_ledger` AUCTION_ESCROW_PAYMENT debit.
3. `Escrow` transitions `ESCROW_PENDING → FUNDED → TRANSFER_PENDING`. External observers see only `TRANSFER_PENDING`.
4. `escrow_transactions` ledger row written, `notificationPublisher.escrowFunded(seller)` fires, broadcast envelope emitted.

`ESCROW_PENDING` becomes essentially a transactional intermediate — it exists only for the few milliseconds inside `createForEndedAuction` before the auto-fund. Nothing externally observable ever sees it.

### Notifications
- `AUCTION_WON` body changes from `"Pay L$%,d into escrow within 24 hours to claim the parcel."` to `"L$%,d has been transferred from your wallet to escrow. The seller will transfer the parcel next."`
- The deeplink stays at `/auction/[publicId]/escrow`, but the destination is now a status view.
- Seller's `ESCROW_FUNDED` notification copy unchanged — "L$X has been funded; transfer the parcel within 24 hours" still describes the state correctly.

### Terminal callback (`/api/v1/sl/wallet/deposit`)
- Listing-fee deposit path: **unchanged**.
- Group-wallet deposit path: **unchanged**.
- Wallet top-up path: **unchanged**.
- Escrow-funding branch (currently `EscrowService.applyCallback`): **removed**. If a winner somehow walks to a terminal and tries to pay the auction, the terminal validation finds no `Escrow` whose `ESCROW_PENDING` state is open for terminal payment (escrows now skip that state externally) and refunds with `ESCROW_EXPIRED`. Defensive — should never fire in practice once we update the LSL terminal copy.

### LSL terminals
- Remove the "pay escrow for auction X" dialog branch from `slpa-terminal/slpa-terminal.lsl`.
- Update `slpa-terminal/README.md` to reflect the removed affordance.

### Escrow page (`/auction/[publicId]/escrow`)
- Drop the Pay button + amount form for winners.
- Render the same status surface that's shown today after funding completes: escrow state badge, payment-confirmed timestamp, transfer-pending deadline countdown, seller transfer status. For COMPLETED, show the receipt.

### Bid form (winner-side UX)
- `PlaceBidForm` already surfaces wallet-balance errors when enforcement is on. Verify the copy reads naturally for users encountering the gate for the first time. Add a "Top up wallet" link to the error state.

## 4. Pre-deploy migration

The auto-fund path requires every winning bid to have a matching `BidReservation` row. Bids placed BEFORE enforcement was on don't have one, so an escrow created from such a bid would throw `BidReservationAmountMismatchException` and freeze.

Per the spec author (Heath), all current production listings have been cancelled. Pre-deploy:
1. `SELECT COUNT(*) FROM auctions WHERE status IN ('ACTIVE', 'ESCROW_PENDING', 'ESCROW_FUNDED', 'TRANSFER_PENDING', 'DISPUTED', 'FROZEN');` — must be 0.
2. `SELECT COUNT(*) FROM bid_reservations WHERE released_at IS NULL;` — must be 0 (or `released_at` filled for every row that ties to a cancelled auction).

If either is non-zero, manually cancel + release before flipping the flag. No code-level migration is built into this PR.

## 5. Configuration

- Add `SLPA_WALLET_ENFORCEMENT_ENABLED=true` to the prod backend task definition.
- Update `application.yml` default to `true` so dev / test profiles match prod. The `@Value("${slpa.wallet.enforcement-enabled:false}")` defaults invert to `true` in this PR.
- Tests that previously asserted the disabled path (if any) either flip to assert the enabled path or get deleted.

## 6. Code surface (delta)

### Backend

**Edit:**
- `BidService` — drop `walletEnforcementEnabled` field + flag check. Reservation logic at lines 152, 245 becomes unconditional.
- `EscrowService` — drop `walletEnforcementEnabled` field + flag check at line 178. Auto-fund branch becomes unconditional. Drop the entire `applyCallback` method's escrow-funding branch (steps 7-12 of the lines 540-680 block); keep terminal validation steps 1-6 only as a defensive refund path that returns `ESCROW_EXPIRED` for any caller mistakenly hitting the deposit endpoint with an `auctionId` payload.
- `application.yml` — `slpa.wallet.enforcement-enabled: true` (or drop the key entirely since the default flips).
- `NotificationPublisherImpl.auctionWon` — body copy update.
- LSL endpoint at `/api/v1/sl/wallet/deposit`: the escrow-funding branch deletion above. The endpoint stays for top-ups + listing fees + group-wallet deposits.

**Remove (if cleanly dead):**
- `EscrowCallbackResponseReason` values that only apply to terminal-funded escrow (`ALREADY_FUNDED` survives, `WRONG_PAYER` survives — these still gate listing-fee + top-up paths). Audit per-value before deleting.
- The `paymentDeadline` column on `Escrow` if no consumer reads it post-change. The 48h transfer deadline (`transferDeadline`) is independent and stays.

### Frontend

**Edit:**
- `app/auction/[publicId]/escrow/page.tsx` + `EscrowPageClient.tsx` — drop the Pay form, render status-only.
- `components/notifications/...` `AUCTION_WON` row template — passive copy ("transferred from your wallet" not "pay now").
- `lib/notifications/categoryMap.ts` — `AUCTION_WON.action` label changes from "Pay escrow" to "View escrow status."
- `components/auction/PlaceBidForm.tsx` — verify insufficient-balance copy, add "Top up wallet" CTA link.

### LSL

- `lsl-scripts/slpa-terminal/slpa-terminal.lsl` — remove the escrow-payment dialog branch.
- `lsl-scripts/slpa-terminal/README.md` — remove the corresponding instructions.

### Docs

- `CLAUDE.md` "In-world payment terminals" section — note that escrow funding moved to the wallet, terminals only top up.
- `README.md` — update the buy-flow paragraph.
- `docs/implementation/FOOTGUNS.md` — drop any captured gotchas about the terminal escrow-funding path. Add a note about the bid-time wallet gate.

## 7. State machine (escrow, after)

```
auction ends with winner
  └── createForEndedAuction (in single tx)
        ├── insert Escrow(ESCROW_PENDING)        // transient — never observed externally
        ├── walletService.autoFundEscrow(...)    // consumes BidReservation, debits wallet
        ├── Escrow → FUNDED                       // transient
        └── Escrow → TRANSFER_PENDING             // first externally observable state

seller transfers parcel in-world
  └── EscrowOwnershipMonitorJob (existing) detects owner=winner
        └── confirmTransfer → COMPLETED
              └── seller wallet credited via existing payout path
```

If `autoFundEscrow` throws (insufficient balance, missing reservation, amount mismatch): escrow lands at FROZEN with audit fields, admin queue picks it up. No 24-hour "pay later" window — buyer should never have been able to bid without sufficient balance, so this branch should be ~impossible in practice.

## 8. Rollout

Single PR. Backend + frontend + LSL + docs land together. The flag flip + terminal-path removal + UI copy update are coupled — shipping any piece in isolation would leave the system in a confusing half-state.

No phased rollout / feature flag (the flag IS what's flipping). Per memory `feedback_production_not_mvp` — production shape, not staged.

## 9. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Pre-existing bid without a reservation reaches auction-end after flag flip | Pre-deploy assertion (§4) verifies zero in-flight auctions. Manually drain anything that slips in between assertion and deploy. |
| Buyer bids without enough balance, surprised by the gate | `PlaceBidForm` error includes a "Top up wallet" CTA linking to `/wallet`. Bid validation happens before the form submits, not after. |
| Seller expects escrow auto-funding but auction-end transitions stall on some downstream path | Existing `EscrowOwnershipMonitorJob` + admin dispute resolution surfaces remain unchanged. The change here is "escrow always lands at TRANSFER_PENDING" — everything downstream of TRANSFER_PENDING is unchanged. |
| LSL terminal user tries to pay an escrow during the rollout window | Terminal validation refunds with `ESCROW_EXPIRED`. Refund happens in-world, no L$ lost. |

## 10. Non-goals

- Wallet top-up via terminal — stays unchanged. Buyers still deposit L$ → SLParcels wallet via the existing terminal flow.
- Listing fee payment via terminal — stays unchanged.
- Group wallet deposit via terminal — stays unchanged.
- Withdrawal flow (wallet → avatar via terminal payout) — stays unchanged.
- Escrow seller payout (wallet → avatar via terminal) — stays unchanged.
- Admin disbursement — stays unchanged.

## 11. Open questions

1. **`paymentDeadline` column.** Today set to `endedAt + PAYMENT_DEADLINE_HOURS` (24h). Post-change, every escrow funds immediately at creation, so the field is set-then-immediately-irrelevant. Drop the column or keep for audit?
2. **`ESCROW_PENDING` enum value.** Externally never observable post-change. Keep for the transactional intermediate (preserves a clear state machine), or collapse?
3. **Terminal-side LSL response copy.** When a user tries to pay escrow via terminal during the rollout window, the terminal refunds + says... what? "Escrow payments now happen automatically — bid via web; SLParcels has refunded your L$"? Or keep it terse?
