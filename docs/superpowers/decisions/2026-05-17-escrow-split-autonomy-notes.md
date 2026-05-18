# Escrow Transfer Split — Autonomy Decisions Log (2026-05-17)

Captures every judgement call made under the full-autonomy grant while
implementing the escrow transfer split + Sell-To verification feature
(spec `docs/superpowers/specs/2026-05-17-escrow-transfer-split-verification-design.md`,
plan `docs/superpowers/plans/2026-05-17-escrow-transfer-split-verification.md`).
Items flagged **[needs user review later?]** are the ones where the
intent was genuinely ambiguous or a reasonable person could have chosen
differently — read those first.

## Decisions

- **1. Deadline-reset interpretation — [needs user review later?]**
  Implemented as: re-stamp `transferDeadline = now + 72h` at escrow
  funding (start of Set Sell To) **and** again when `sellToConfirmedAt`
  is stamped (start of Buy Parcel). **No third reset** — there is no
  seller action after the buyer's Buy-Parcel step, so nothing to reset
  for. The user's parenthetical ("so seller has time to comply after
  buyers steps are completed") was ambiguous: read literally it implies
  a reset *after* the buyer step, but no seller action follows that
  step, so a third reset would have no effect. Flagged for confirmation
  the two-reset model matches intent. (Spec §13.1.)

- **2. Steps doc consolidated into the plan.** No separate redundant
  "steps markdown" file was created; the ordered implementation steps
  live in the writing-plans plan only, to avoid two step lists drifting
  out of sync. (Spec §13.2.)

- **3. Step-3 persistent World-API failure keeps the existing path.**
  A persistent World-API failure during the Buy-Parcel sub-step keeps
  the established 2026-05-16 fraud-freeze → admin path
  (`WORLD_API_PERSISTENT_FAILURE` freeze; a frozen escrow already routes
  to admin). It does **not** route into the new no-fault manual-review
  escalation queue — that would have contradicted the established
  fraud-safety design and double-surfaced the same escrow. (Spec §13.3.)

- **4. Bot is a hard gate + dedicated no-fault escalation.** The escrow
  cannot leave Set-Sell-To until the bot (or admin override) confirms;
  step-3 owner polling is inert until `sellToConfirmedAt IS NOT NULL`.
  The escape hatch is a dedicated no-fault `EscrowManualReview`
  escalation (separate from the dispute flow, does not freeze the
  escrow, does not imply seller fault) with its own admin queue.

- **5. Disputes and manual-review coexist — [needs user review later?]**
  During Phase 8 an implementer briefly removed the existing "File a
  dispute" link from the escrow page; it was restored. Disputes and the
  new no-fault manual-review are intentionally **both** available on the
  escrow page — they are distinct flows (dispute = adversarial / fault;
  manual-review = no-fault "please check this for me"). Confirm both
  surfacing simultaneously is the desired UX.

- **6. Review-resolve broadcasts transitively; DISMISS is a no-op
  state-wise.** The admin escrow-review resolve actions
  (`FORCE_CONFIRM_SELL_TO` / `FORCE_COMPLETE_TRANSFER` /
  `REFUND_WINNER`) reuse the existing escrow mutators
  (`confirmSellTo` / `confirmTransfer` / expire-refund), so the STOMP
  escrow envelope is broadcast transitively via those mutators — the
  admin service does not publish a direct STOMP frame of its own.
  `DISMISS` closes the review with **no escrow state change** (and so no
  escrow envelope).

- **7. Admin "Escrow Reviews" nav has no count badge — [needs user
  review later?]** The admin-nav link ships without a count badge:
  `AdminStatsResponse.QueueStats` has no `openEscrowReviews` field, so
  there is nothing for the frontend to render a badge from. Adding the
  server-side stat for disputes-style badge parity was deliberately left
  as a follow-up (logged in `DEFERRED_WORK.md`). Confirm a badge is/ isn't
  wanted.

- **8. Escrow notification SLURL surfaced via deeplink + SL IM, not a
  direct in-app maps link.** Every `NotificationGroup.ESCROW`
  notification carries `parcelMapUrl` / `parcelViewerUrl` in its data
  blob. In-app it is reached via the existing deeplink → escrow page
  (which itself shows the SLURL); the SL IM body appends a clickable
  `Parcel: <viewerUrl>` line. The in-app notification renderer
  (`NotificationDropdownRow`) has **no external-link slot**, so there is
  no direct in-app maps link. Adding a renderer slot is a logged
  follow-up. (See DEFERRED_WORK.md; FOOTGUNS §F.116.)

- **9. Minor review nits intentionally left.** A small number of
  reviewer nits were consciously not actioned because they are
  cosmetic / near-unreachable and not worth churn in this PR:
  - Phase-7: `forSale != true` readability (a `Boolean` three-state
    null-safe compare; correct, just terse).
  - Phase-6: the near-unreachable 404-vs-500 ordering on the admin
    review detail path (the precondition that would trigger it is
    practically unreachable given the controller guards).

- **10. ParcelOwnershipClassifier + partial-index follow-ups not done
  here.** Two cleanups were explicitly scoped out and logged in
  `DEFERRED_WORK.md`: (a) extract a shared `ParcelOwnershipClassifier`
  so `EscrowManualActionService.verifyTransfer` and
  `EscrowOwnershipCheckTask.checkOne` stop duplicating the World-API
  winner/seller/group/UNKNOWN_OWNER/PARCEL_DELETED matrix (this also
  fixes the shared unguarded `auction.getSeller()` deref); (b) an
  optional partial covering index on the `escrows` Buy-phase due query
  if `TRANSFER_PENDING` cardinality grows. Both are backlog, not
  regressions.

- **11. Postman mirroring status.** See the "Postman" section below —
  recorded here so the autonomy log is self-contained.

## Postman mirroring status

The 7 new/changed endpoints to mirror into the `SLPA` collection
(workspace `SLPA`, collection id
`8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`):

- `POST {{baseUrl}}/api/v1/auctions/{{auctionPublicId}}/escrow/verify-sell-to` (202, JWT seller)
- `POST .../escrow/verify-transfer` (200, JWT seller or winner)
- `POST .../escrow/manual-review` (200, JWT seller or winner; optional body `{"note":"..."}`)
- `POST {{baseUrl}}/api/v1/bot/tasks/{{botTaskId}}/result` (204, bearer bot secret)
- `GET {{baseUrl}}/api/v1/admin/escrow-reviews?status=OPEN&page=0&size=20` (admin)
- `GET {{baseUrl}}/api/v1/admin/escrow-reviews/{{escrowReviewPublicId}}` (admin)
- `POST {{baseUrl}}/api/v1/admin/escrow-reviews/{{escrowReviewPublicId}}/resolve` (admin; body `{"action":"FORCE_CONFIRM_SELL_TO","adminNote":"..."}`)

Status: **Done** (mirrored 2026-05-17 via the Postman MCP). All 7
requests created in the existing `SLPA` collection
(`8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`):

- The 3 escrow seller/winner endpoints (`verify-sell-to`,
  `verify-transfer`, `manual-review`) were added to the existing
  **Escrow** folder, bearer `{{accessToken}}`, URL using the
  collection's existing `{{auctionId}}` path variable (the collection's
  `Get escrow status` sibling uses `{{auctionId}}`, not a separate
  `auctionPublicId` var — matched that convention for chaining).
- `POST /bot/tasks/{{botTaskId}}/result` was added to the existing
  **Bot** folder, bearer literal `dev-bot-shared-secret` (matching the
  sibling `Complete verify` request), with the `SELL_TO_OK` sample body.
- The 3 admin endpoints were added to a new **Admin / Escrow Reviews**
  folder (matching the existing `Admin / Listings` / `Admin / Ledger` /
  `Admin / Realty Groups` naming), bearer `{{accessToken}}`. "List
  escrow reviews" stamps a new `escrowReviewPublicId` collection
  variable (mirroring how `Admin / Listings → List listings` stamps
  `auctionPublicId`) so the detail + resolve requests chain off it.

No repo files changed for Postman — the collection lives server-side.
