# Deferred Work Ledger

Running list of work that was scoped out of a completed epic/sub-spec/task, deferred intentionally, or left partially implemented. Every entry must name:

1. **What** — the specific feature, endpoint, UI element, or behavior not delivered
2. **Where it came from** — the originating epic/sub-spec/task
3. **Why deferred** — blocker, phase dependency, or explicit out-of-scope decision
4. **When to complete** — target epic/phase, or "indefinite" if no target yet

**Read this file at the start of every brainstorming session for a new epic/sub-spec, alongside [CONVENTIONS.md](./CONVENTIONS.md).** When starting a new phase, sweep for items whose "when" matches — those are candidates to pull into the current work.

When finishing a sub-spec that completes a deferred item, remove the entry.

---

## Format

```markdown
### [short title]
- **From:** Epic NN sub-spec X / Task NN-NN
- **Why:** [reason]
- **When:** [target phase/epic, or "indefinite"]
- **Notes:** [any extra context, links to spec sections]
```

---

## Current Deferred Items

### LSL script for in-world verification terminal
- **From:** Epic 02 sub-spec 1 (Task 02-02)
- **Why:** Phase 11 is the dedicated LSL scripting phase. Sub-spec 1 shipped the backend `POST /api/v1/sl/verify` endpoint; the script that calls it is a separate work track.
- **When:** Phase 11
- **Notes:** Endpoint is testable via Postman `Dev/Simulate SL verify` helper today. The dev-profile `POST /api/v1/dev/sl/simulate-verify` stands in for the real LSL call during development.

### WebSocket push for verification completion
- **From:** Epic 02 sub-spec 2b (Task 02-04 dashboard verify flow)
- **Why:** Considered during brainstorm. Deferred because the backend publisher needs to know when a real SL verification call succeeds — that signal source only exists once Phase 11 LSL work is real. Polling (5s, visibility-aware) is the right tool until then.
- **When:** Phase 11
- **Notes:** Replace the `useCurrentUser({ refetchInterval: 5000 })` polling with a STOMP subscription on `/topic/user/{userId}/verification`.

### Partial-star rendering for ReputationStars
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Phase 1 ships a simpler numeric "4.7 ★" display. Partial-star SVG rendering is polish that only matters when review counts are non-trivial.
- **When:** Epic 06 (Ratings & Reputation) when real review data exists
- **Notes:** Current `ReputationStars.tsx` at `frontend/src/components/user/ReputationStars.tsx`.

### Email change flow
- **From:** Epic 02 sub-spec 2b (Task 02-04 profile edit)
- **Why:** Requires a re-verification flow (new email → confirmation link → swap). Out of scope for the profile edit shipped in 2b.
- **When:** Epic 07 (user settings expansion)
- **Notes:** `ProfileEditForm` currently only covers `displayName` and `bio`.

### Account deletion UI
- **From:** Epic 02 sub-spec 2a (Task 02-03 user profile backend)
- **Why:** Backend `DELETE /me` returns 501 Not Implemented. Needs a GDPR-compliant deletion flow (cascade rules, data retention, soft-delete vs hard-delete decisions) that was out of scope for 2a.
- **When:** Future Epic 02 GDPR sub-spec, or Epic 07
- **Notes:** Dashboard has no delete button. Backend endpoint returns 501.

### Notification preferences editor
- **From:** Epic 02 sub-spec 2b (Task 02-04 dashboard)
- **Why:** `CurrentUser.notifyEmail` and `notifySlIm` are returned by `/me` but no UI exposes them for editing. Editor design blocked on the notifications system coming online.
- **When:** Epic 07 (settings expansion) or Epic 09 (notifications)
- **Notes:** Shape of the JSON objects is defined — just needs a form.

### Realty group badge on public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Task 02-05 mentions "Realty group badge (if applicable, Phase 2 — just leave space)." Phase 2 feature, group membership data source not defined.
- **When:** Phase 2 (post-MVP)
- **Notes:** `PublicProfileView` has no placeholder space reserved; add when requirements firm up.

### Follow/unfollow from public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Social features out of scope for Phase 1.
- **When:** Indefinite
- **Notes:** Not in the Phase 1 design document.

### Profile page SEO metadata (OpenGraph)
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Nice-to-have polish. Next.js 16 `generateMetadata` could emit OpenGraph tags for social sharing.
- **When:** Epic 07 or later
- **Notes:** Touchpoint is `frontend/src/app/users/[id]/page.tsx`.

### Drag-drop animation polish on ProfilePictureUploader
- **From:** Epic 02 sub-spec 2b (Task 02-04 profile picture upload)
- **Why:** Current drop zone uses a static border highlight. Polished version would animate border-color transition and a scale effect on drop.
- **When:** Indefinite (cosmetic)
- **Notes:** `frontend/src/components/user/ProfilePictureUploader.tsx`.

### My Bids dashboard frontend (consume the backend endpoint)
- **From:** Epic 02 sub-spec 2b (Task 02-04 dashboard)
- **Why:** Tab skeleton ships with empty-state placeholder. Backend `GET /api/v1/users/me/bids` landed in Epic 04 sub-spec 1 Task 8 with derived status across all 7 buckets; the frontend consumer (My Bids tab UI, paginated list, status filter chips) is out-of-scope for sub-spec 1 and lands in sub-spec 2 per its scope split.
- **When:** Epic 04 sub-spec 2
- **Notes:** `frontend/src/app/dashboard/(verified)/bids/page.tsx` currently renders `<EmptyState>`. Backend DTOs live in `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/` (`MyBidSummary`, `AuctionSummaryForMyBids`, `MyBidStatus`).

### Recent reviews section on public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Review data requires the reviews model from Epic 06. Public profile ships with empty-state placeholder.
- **When:** Epic 06 (Ratings & Reputation)
- **Notes:** `PublicProfileView` renders `<EmptyState icon={MessageSquare}>` for this section.

### Active listings section on public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Listing data requires auction/listing model from Epic 03/04. Public profile ships with empty-state placeholder.
- **When:** Epic 04 (Auction Engine)
- **Notes:** `PublicProfileView` renders `<EmptyState icon={Gavel}>` for this section.

### PARCEL code generation rate tracking (fraud signal)
- **From:** Epic 03 sub-spec 1 (Method B rezzable callback flow)
- **Why:** Sellers who burn through many PARCEL codes without ever rezzing the object are either confused (needs better instructions) or probing/abusing the system. Not a Phase 1 concern but worth flagging once the flow is live and we have baseline usage data.
- **When:** Epic 10 (Admin & Moderation) — fraud flags
- **Notes:** Metric would be "count of PARCEL codes generated per seller over last N days where no successful callback occurred." Likely lives as a `fraud_signals` table or similar, feeding admin dashboards.

### Bot service authentication
- **From:** Epic 03 sub-spec 1 (Method C SALE_TO_BOT bot queue)
- **Why:** `GET /api/v1/bot/tasks/pending` and `PUT /api/v1/bot/tasks/{taskId}` ship with `permitAll` in `SecurityConfig` because the real bot worker does not exist yet. Shipping a placeholder auth scheme without a worker implementation to validate against would be premature. Body-level validation (`authBuyerId == primary-escrow-uuid`, `salePrice == sentinelPrice`) mitigates arbitrary calls but does not prevent a malicious actor from racing the real worker or flipping auction states via FAILURE callbacks.
- **When:** Epic 06 (SL bot service) — MUST land before the real worker deploys.
- **Notes:** See FOOTGUNS §F.46. Pick mTLS or bearer token in the Epic 06 spec. Until done, the bot endpoint surface is a locally-trusted attack surface — deploy the worker on localhost or a private network only.

### Listing fee refund processor
- **From:** Epic 03 sub-spec 1 (cancellation refund row creation)
- **Why:** `CancellationService` writes `listing_fee_refunds` rows with `status=PENDING` when a paid auction is cancelled before verification, but nothing processes them yet. Real L$ refunds require the in-world escrow terminal integration from Epic 05.
- **When:** Epic 05 (Escrow Manager) — refund processor polls `listing_fee_refunds` WHERE `status=PENDING` and issues L$ refunds via the escrow terminal, stamping `processed_at` + `txn_ref` + flipping to `PROCESSED`.
- **Notes:** Rows accumulate indefinitely until the processor ships. Operationally this is fine for Phase 1 (no real money is moving yet) but the backlog will need a one-time batch processor on the day Epic 05 ships to drain pre-existing PENDING rows.

### Primary escrow UUID + SLPA trusted-owner-keys production config
- **From:** Epic 03 sub-spec 1 (Method C bot task sentinel + SL header trust)
- **Why:** `slpa.bot-task.primary-escrow-uuid` defaults to the dev placeholder `00000000-0000-0000-0000-000000000099`, and `slpa.sl.trusted-owner-keys` is empty in `application.yml` (overridden to the dev placeholder in `application-dev.yml`). Production deployment must override both via env var / secrets manager.
- **When:** First production deployment (pre-launch ops checklist).
- **Notes:** `SlStartupValidator` fails fast on prod boot if `trusted-owner-keys` is still empty — that is the forcing function. The primary-escrow-uuid has no equivalent startup guard yet; add one when the real SLPAEscrow Resident account is provisioned (same Epic 05 / Epic 06 timeline as the escrow integration). See FOOTGUNS §F.47.

### IN_PROGRESS bot task timeout
- **From:** Epic 03 sub-spec 1 (BotTaskTimeoutJob 48h sweep)
- **Why:** `BotTaskTimeoutJob` only times out PENDING tasks — tasks that were never claimed by a worker. Once Epic 06 workers claim a task and flip it to `IN_PROGRESS`, a crashed worker leaves the task stuck in IN_PROGRESS forever with no cleanup.
- **When:** Epic 06 (SL bot service) — when claim-flow is implemented, extend the timeout job with a separate `IN_PROGRESS`-status query + cutoff (likely shorter than 48h, since "worker picked it up but did not finish" is a different signal than "no worker claimed it").
- **Notes:** The right cutoff for IN_PROGRESS is probably 15-30 minutes (a real verify should take seconds). Failing behavior on timeout is the same: task FAILED with reason `TIMEOUT`, auction flipped to `VERIFICATION_FAILED` only if still `VERIFICATION_PENDING`.

### Public listing page target for "View public listing" links
- **From:** Epic 03 sub-spec 2 (Task 10 My Listings row actions)
- **Why:** `ListingSummaryRow`'s "View listing" (for ACTIVE / ENDED / escrow / completed / expired) and "View details" (for CANCELLED / DISPUTED / SUSPENDED) links both target `/auction/[id]`. The dynamic auction route exists today and serves the public DTO, but the polished buyer-facing listing page (photo gallery, bid ladder, snipe-protection messaging, seller profile block, watch button) is scoped to Epic 04.
- **When:** Epic 04 (Auction Engine — public listing page).
- **Notes:** The spec §6.3 footnote acknowledges these links may be "dead" (i.e., render a sparse placeholder page) until Epic 04 lands. Do not re-home the links to a different route when the full page ships — `/auction/[id]` is the canonical URL.

### Real in-world listing-fee terminal
- **From:** Epic 03 sub-spec 2 (activate page fee payment)
- **Why:** `FeePaymentInstructions` copy + the activate-flow state machine both assume an in-world rezzed escrow terminal that posts a callback to transition `DRAFT → DRAFT_PAID` with a real L$ transaction reference. Today the only payment path is `POST /api/v1/dev/auctions/{id}/pay` (dev-profile-only) which stamps a `dev-mock-<uuid>` txnRef. Production deployment MUST replace this with the real terminal callback before the app ships to real sellers.
- **When:** Epic 05 (Escrow Manager) — the listing-fee terminal is the first in-world object that the escrow LSL scripting phase will produce.
- **Notes:** The shape of the real callback is intentionally left TBD — the dev endpoint's `{amount?, txnRef?}` body is not the binding contract. Epic 05's spec will define the SL-header-gated callback endpoint and its body schema; the frontend's `FeePaymentInstructions` component will update to show the real terminal location + region when the UUID is provisioned.

### Notifications for suspension events
- **From:** Epic 03 sub-spec 2 (ownership monitor SUSPENDED transition)
- **Why:** When `SuspensionService.suspend()` flips an auction to SUSPENDED and writes a `FraudFlag` row, the seller learns about it only via the My Listings tab (`ListingSummaryRow`'s inline red callout). No email / SL IM / in-app notification is fired. This is fine for Phase 1 (the dashboard is the only communication surface) but a production launch deserves a real "your listing was suspended — contact support" email with the fraud reason summarized.
- **When:** Epic 09 (Notifications) — hook `SuspensionService.suspend()` into the notification publisher once the email + SL IM channels exist.
- **Notes:** The notification template should NOT include the full FraudFlag `evidence_json` payload (that's admin-only — leaking it could help an attacker calibrate their next attempt). Stick to a human-readable reason string from `FraudFlagReason`.

### Admin dashboard for fraud_flag resolution
- **From:** Epic 03 sub-spec 2 (FraudFlag entity + SuspensionService)
- **Why:** `FraudFlag` has `resolved` / `resolvedAt` / `resolvedBy` columns and a `jsonb evidence_json` payload ready for admin review, but no UI reads or writes them. Ownership-check suspensions accumulate silently until an admin exists to triage them.
- **When:** Epic 10 (Admin & Moderation) — build a `/admin/fraud-flags` page that lists open flags, shows the evidence blob formatted, and lets an admin resolve or un-suspend (flip the auction back to ACTIVE and mark the flag resolved).
- **Notes:** Un-suspend is a sensitive action — only a user with an admin role (tracked in a separate `User.role` or similar) should see the button. The admin role model itself is also Epic 10 scope.

### Non-dev admin endpoint for ownership-monitor trigger
- **From:** Epic 03 sub-spec 2 (DevOwnershipMonitorController)
- **Why:** `POST /api/v1/dev/ownership-monitor/run` is `@Profile("dev")` — the only way to force an ownership sweep in prod is to wait for the next scheduled tick (default 15 minutes). Admins triaging a suspected fraud report need a "re-check this listing now" button that runs a single-auction check and returns the result synchronously.
- **When:** Epic 10 (Admin & Moderation) — add `POST /api/v1/admin/auctions/{id}/recheck-ownership` gated on the admin role, delegating to `OwnershipCheckTask` for a single auction with the result summarized in the response body.
- **Notes:** Keep the dev endpoint unchanged — it's useful for test suites and local verification (see the manual-test plan in the Task 10 PR). The admin endpoint is a separate surface with different auth and a different response shape.

### Destructive-variant copy polish
- **From:** Epic 03 sub-spec 2 (Task 9 review follow-up + Task 10 Button variant)
- **Why:** The Button `destructive` variant landed in Task 10 with `bg-error text-on-error`, sufficient for the `CancelListingModal` use case. Future destructive surfaces (delete-account, bulk cancel, fraud-flag un-suspend) may want a richer treatment — e.g. an icon-left convention, a "are you sure" two-step gesture, or a reduced-emphasis destructive outline variant for less consequential destructive actions.
- **When:** Indefinite — upgrade when a second destructive use case arrives and the current shape pinches.
- **Notes:** The current token mapping (`bg-error` / `text-on-error`) is the load-bearing part. Any polish should NOT switch to raw Tailwind palette classes (`bg-red-500`) — keep it on the M3 token system.

### N+1 on My Bids auction loading
- **From:** Epic 04 sub-spec 1 (Task 8)
- **Why:** `MyBidsService.findAuctionPage` loads auctions one-by-one via `AuctionRepository.findById` to trip the `@EntityGraph` on parcel + tags. `Auction.seller` is not in the EntityGraph and lazy-loads per-row, producing ~2 extra queries per auction on a page of 20 (up to ~43 queries total per page). At Phase 1 volumes this is acceptable (bounded by page size = 20) but is worth fixing before scale.
- **When:** Near-term cleanup after sub-spec 2 frontend lands — likely the frontend will reveal whether seller display name is needed for every card.
- **Notes:** Fix options: (a) add `seller` to the existing `@EntityGraph` on `findById` (affects all callers); (b) add a dedicated `findAllByIdWithParcelAndSeller(Collection<Long>)` query that `MyBidsService` uses instead of the per-id loop.

### My Bids non-ACTIVE sort key deviation
- **From:** Epic 04 sub-spec 1 (Task 8)
- **Why:** Spec §10 specifies conditional `ORDER BY` using `CASE WHEN a.status = 'ACTIVE' THEN a.ends_at END DESC, CASE WHEN a.status != 'ACTIVE' THEN a.ended_at END DESC`. The actual query uses unconditional `a.endsAt DESC, a.endedAt DESC`, which means non-ACTIVE auctions are ordered by `endsAt` first. At Phase 1 volumes the drift is small (snipe-extended auctions diverge by at most 2-60 minutes) but is a documented spec deviation.
- **When:** Fix when sub-spec 2 frontend surfaces sort-order concerns, or during an Epic 04 cleanup pass.
- **Notes:** Replace `a.endsAt DESC, a.endedAt DESC` with `CASE WHEN a.status = 'ACTIVE' THEN a.endsAt END DESC, CASE WHEN a.status != 'ACTIVE' THEN a.endedAt END DESC` (Postgres tolerates NULLs in CASE-based ORDER BY).

### Migrate Epic 02/03 write paths onto NotVerifiedException
- **From:** Epic 04 sub-spec 1 (Task 2 — bid placement)
- **Why:** `NotVerifiedException` was introduced to give the bid path a clean `NOT_VERIFIED` (403) error code. Pre-existing verification checks in `AuctionController.requireVerified`, parcel controllers, and other write paths still throw raw `AccessDeniedException` with inline message strings, producing `ACCESS_DENIED` instead of `NOT_VERIFIED`. Frontend UX distinguishing the two codes will only see `NOT_VERIFIED` from the bid path until the migration lands.
- **When:** Near-term — trivially, a one-line swap at each existing call site. Deferred from Task 2 to keep the scope tight; pick up as a standalone cleanup task or roll into the Epic 04 sub-spec 2 frontend work when the UX for verification prompts is wired.
- **Notes:** Search for `AccessDeniedException` call sites that include the string "verification required" (or equivalent) — those are the migration targets. `NotVerifiedException` already exists at `backend/src/main/java/com/slparcelauctions/backend/auction/exception/NotVerifiedException.java` and has an existing handler in `AuctionExceptionHandler.java`.

### DESIGN.md §554 stale wording cleanup
- **From:** Epic 04 sub-spec 1 (spec §15 follow-up)
- **Why:** DESIGN.md §554 says "Bid history (anonymized or public - configurable)" — leftover wording from an earlier iteration of the spec. §1589-1591 explicitly resolves bidder identity visibility as public (display name + avatar, no anonymization toggle) and Epic 04 sub-spec 1 ships the public-identity behavior. Anyone grepping DESIGN.md for "anonymized" will hit a contradiction.
- **When:** Next doc sweep / any epic that reopens DESIGN.md for structural edits.
- **Notes:** One-sentence replacement — "Bid history (public — displayName + avatar only, no IP or full name)" lines up with the implemented behavior.

### Outbid / won / reserve-not-met / auction-ended notifications
- **From:** Epic 04 sub-spec 1 (spec §15)
- **Why:** Epic 04 sub-spec 1 publishes the data sources — `Bid` rows carry `OUTBID` / `WON` / `LOST` derivable state, `Auction.endOutcome` carries `SOLD` / `RESERVE_NOT_MET` / `NO_BIDS` / `BOUGHT_NOW`, and the WS settlement + auction-ended envelopes broadcast the transitions. No email / SL IM fan-out exists yet. Bidders learn they've been outbid only by reloading the auction page or watching the live WS update; sellers learn the auction ended only by reloading My Listings.
- **When:** Epic 09 (Notifications) — hook a `BidSettlementEnvelope` / `AuctionEndedEnvelope` listener into the notification publisher once email + SL IM channels exist.
- **Notes:** The data sources are stable — `BidType`, `Auction.endOutcome`, `Auction.winnerUserId`, and the existing WS envelopes carry everything Epic 09 needs. No schema changes required on the Epic 04 side.

### User-targeted WebSocket queues (`/user/{id}/queue/*`)
- **From:** Epic 04 sub-spec 1 (spec §15)
- **Why:** Phase 1 broadcasts use only the public `/topic/auction/{id}` destination. Personal events (e.g., "you were outbid on auction X" toast) are derived on the frontend by comparing the public envelope's `currentBidderId` against the logged-in user. Per-user STOMP queues (`/user/{id}/queue/outbid`, `/user/{id}/queue/won`) would let the backend push targeted events without a public broadcast and would integrate with Epic 09's notification fan-out.
- **When:** Epic 09 (Notifications) — when email + SL IM + in-app push unify on a single publisher, add the user-queue destination at the same time for consistency.
- **Notes:** The `JwtChannelInterceptor` already understands principal-gated destinations — adding `/user/**` to the gate is a small change. The frontend's `useStompSubscription` hook would grow a `/user/queue/*` variant.

### Escrow handoff from ENDED + SOLD
- **From:** Epic 04 sub-spec 1 (spec §15)
- **Why:** `AuctionEndTask.closeOne` flips an auction to `ENDED` with `endOutcome=SOLD` + `winnerUserId` + `finalBidAmount`, but nothing downstream picks it up to drive the L$ handoff from buyer to seller or the in-world parcel transfer. The auction sits in `ENDED+SOLD` awaiting Epic 05's escrow pipeline.
- **When:** Epic 05 (Escrow Manager) — poll `auctions WHERE status='ENDED' AND end_outcome='SOLD' AND escrow_status IS NULL` (new column) and drive the buyer-charge + seller-payout + parcel-transfer sequence.
- **Notes:** The Epic 05 spec will define the `escrow_status` column (or equivalent lifecycle table) and the retry semantics for buyer-payment-failure. Do not repurpose `auction.status` for escrow states — keep status at `ENDED` so the public DTO collapses to `ENDED` per spec §7.

### Cancellation WS broadcast on active-auction cancel
- **From:** Epic 04 sub-spec 1 (spec §15)
- **Why:** When a seller cancels an ACTIVE auction with bids (rare — requires explicit confirmation through the sub-spec 2 cancel modal), no `/topic/auction/{id}` envelope is currently published. Bidders watching the auction detail page in real-time see no update until they reload. This is a consistency gap with the bid/end broadcasts that both publish on `afterCommit`.
- **When:** Re-evaluate during Epic 04 sub-spec 2 when the frontend auction detail page lands and the UX for "auction cancelled while you were bidding" is in hand. May turn out that a banner on the next REST read is sufficient UX; may turn out a WS envelope is needed to interrupt mid-bid.
- **Notes:** Currently visible via `GET /api/v1/auctions/{id}` returning `status=CANCELLED` and via the seller's My Listings on next page load. The data surface exists — only the broadcast is missing. `CancellationService.cancel` would register a `TransactionSynchronization.afterCommit` that publishes an `AuctionCancelledEnvelope` (new DTO).

### Richer outbid toast shape (warning variant + structured action button)
- **From:** Epic 04 sub-spec 2 (Task 7 — `OutbidToastProvider`)
- **Why:** Spec §15 prescribes `toast.warning({ title, description, action: { label: "Place a new bid", onClick: scrollToBidPanel } })`. The current `useToast()` primitive only exposes `success` / `error` variants with a plain string payload, so Task 7 shipped `toast.error("You've been outbid — current bid is L$X.")` plus an automatic `scrollIntoView` side-effect on the bid panel. Functional for Phase 1; loses the distinct warning tone and the explicit "Place a new bid" action button the spec specifies.
- **When:** Epic 09 (Notifications) is the natural pull-in point — notification fan-out will want structured toast actions ("View listing" / "Dismiss") and a warning tone, so widening the Toast primitive becomes load-bearing there. A design-system sweep is an acceptable earlier trigger if one happens first.
- **Notes:** Expansion path: widen `ToastKind` to `success | error | warning | info`, widen `ToastMessage` to accept `{ title, description, action?: { label, onClick } }`, update `ToastProvider` + `Toast` components accordingly. `OutbidToastProvider.maybeFire` then swaps its current single-string `toast.error` call for `toast.warning({ title: "You've been outbid", description: \`Current bid is L$${x}.\`, action: { label: "Place a new bid", onClick: scrollToBidPanel } })` and drops the imperative scroll-on-fire side-effect in favor of the action button. Component lives at `frontend/src/components/auction/OutbidToastProvider.tsx`; toast primitive at `frontend/src/components/ui/toast/` (approximate — confirm at pull-in time).

---

## Removal Criteria

An entry is removed from this list when:
- The work lands in a merged PR, OR
- The decision is made to permanently not do the work (in which case, record that decision in a FOOTGUNS entry or elsewhere so the rationale survives)

Do not remove entries as a shortcut. If an item was "mostly done" but some specific piece is still missing, leave the entry and narrow its scope to what remains.
