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

### Recent reviews section on public profile
- **From:** Epic 02 sub-spec 2b (Task 02-05 public profile)
- **Why:** Review data requires the reviews model from Epic 06. Public profile ships with empty-state placeholder.
- **When:** Epic 06 (Ratings & Reputation)
- **Notes:** `PublicProfileView` renders `<EmptyState icon={MessageSquare}>` for this section.

### PARCEL code generation rate tracking (fraud signal)
- **From:** Epic 03 sub-spec 1 (Method B rezzable callback flow)
- **Why:** Sellers who burn through many PARCEL codes without ever rezzing the object are either confused (needs better instructions) or probing/abusing the system. Not a Phase 1 concern but worth flagging once the flow is live and we have baseline usage data.
- **When:** Epic 10 (Admin & Moderation) — fraud flags
- **Notes:** Metric would be "count of PARCEL codes generated per seller over last N days where no successful callback occurred." Likely lives as a `fraud_signals` table or similar, feeding admin dashboards.

### Bot service authentication — RESOLVED (Epic 06 Task 3, 2026-04-22)
- **From:** Epic 03 sub-spec 1 (Method C SALE_TO_BOT bot queue)
- **Resolution:** `/api/v1/bot/**` is now gated by a bearer-token shared secret. `SecurityConfig` routes the matcher through `BotSharedSecretAuthorizer`, which compares the `Authorization: Bearer` header against `slpa.bot.shared-secret` using `MessageDigest.isEqual` (constant-time, no length-leak via timing). `BotStartupValidator` (`@Profile("!dev")`) fails fast on non-dev profiles if the secret is blank, still the dev placeholder `"dev-bot-shared-secret"`, or shorter than 16 characters. Covered by `BotSharedSecretAuthorizerTest` (5 cases) + `BotStartupValidatorTest` (4 cases) + `BotTaskControllerAuthIntegrationTest` (3 cases).

### Primary escrow UUID production config — RESOLVED (Epic 06 Task 3, 2026-04-22); SLPA trusted-owner-keys still DEFERRED
- **From:** Epic 03 sub-spec 1 (Method C bot task sentinel + SL header trust)
- **Resolution (primary-escrow-uuid):** `BotStartupValidator` (`@Profile("!dev")`) now throws `IllegalStateException` on non-dev profiles if `slpa.bot-task.primary-escrow-uuid` is still the dev placeholder `00000000-0000-0000-0000-000000000099`. This matches the `SlStartupValidator` forcing function for `trusted-owner-keys`.
- **Remaining (SLPA trusted-owner-keys):** `slpa.sl.trusted-owner-keys` must still be overridden via env var / secrets manager for first production deployment. `SlStartupValidator` fails fast on prod boot if the list is empty — that is the forcing function. Track against the pre-launch ops checklist.
- **When:** First production deployment (pre-launch ops checklist).
- **Notes:** A companion startup guard for `slpa.escrow.terminal-shared-secret` ships with Epic 05 sub-spec 1 (`EscrowStartupValidator`). See FOOTGUNS §F.47.

### IN_PROGRESS bot task timeout
- **From:** Epic 03 sub-spec 1 (BotTaskTimeoutJob 48h sweep)
- **Why:** `BotTaskTimeoutJob` only times out PENDING tasks — tasks that were never claimed by a worker. Once Epic 06 workers claim a task and flip it to `IN_PROGRESS`, a crashed worker leaves the task stuck in IN_PROGRESS forever with no cleanup.
- **When:** Epic 06 (SL bot service) — when claim-flow is implemented, extend the timeout job with a separate `IN_PROGRESS`-status query + cutoff (likely shorter than 48h, since "worker picked it up but did not finish" is a different signal than "no worker claimed it").
- **Notes:** The right cutoff for IN_PROGRESS is probably 15-30 minutes (a real verify should take seconds). Failing behavior on timeout is the same: task FAILED with reason `TIMEOUT`, auction flipped to `VERIFICATION_FAILED` only if still `VERIFICATION_PENDING`.

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

### Cancellation WS broadcast on active-auction cancel
- **From:** Epic 04 sub-spec 1 (spec §15)
- **Why:** When a seller cancels an ACTIVE auction with bids (rare — requires explicit confirmation through the sub-spec 2 cancel modal), no `/topic/auction/{id}` envelope is currently published. Bidders watching the auction detail page in real-time see no update until they reload. This is a consistency gap with the bid/end broadcasts that both publish on `afterCommit`.
- **When:** Re-evaluate during Epic 04 sub-spec 2 when the frontend auction detail page lands and the UX for "auction cancelled while you were bidding" is in hand. May turn out that a banner on the next REST read is sufficient UX; may turn out a WS envelope is needed to interrupt mid-bid.
- **Notes:** Currently visible via `GET /api/v1/auctions/{id}` returning `status=CANCELLED` and via the seller's My Listings on next page load. The data surface exists — only the broadcast is missing. `CancellationService.cancel` would register a `TransactionSynchronization.afterCommit` that publishes an `AuctionCancelledEnvelope` (new DTO).

### Per-user public listings page `/users/{id}/listings`
- **From:** Epic 04 sub-spec 2 (Task 9 `ActiveListingsSection` on public profile)
- **Why:** The "View all" link from `ActiveListingsSection` on `/users/{id}` points at `/users/{id}/listings`, which does not exist yet. The active-listings section itself ships with a page-size-limited preview (top N listings returned by `GET /api/v1/users/{userId}/auctions?status=ACTIVE`). A dedicated paginated, filterable, sort-aware "all listings by this seller" page belongs to the Browse surface in Epic 07.
- **When:** Epic 07 (Browse & Search).
- **Notes:** Consider conditionally rendering the "View all" link as disabled / hidden until the route ships, so the anchor doesn't dead-end on a 404. Touchpoint: `frontend/src/components/user/ActiveListingsSection.tsx`. The endpoint `GET /api/v1/users/{userId}/auctions?status=ACTIVE` already exists (SUSPENDED always excluded server-side) and is the data source the Epic 07 page will consume.

### Bid history infinite scroll
- **From:** Epic 04 sub-spec 2 (Task 6 `BidHistory`)
- **Why:** Phase 1 ships bid history as a paginated "load more" (page size 20, click-to-fetch-next-page). Infinite scroll with intersection-observer-driven auto-fetch is a UX upgrade that adds dependencies (scroll-position restore, focus management on SPA navigation, screen-reader announcement of newly-loaded rows) that deserve their own scoped design pass.
- **When:** Indefinite — pull in alongside a broader "list UX polish" sub-spec once a second surface (Browse, search results) demands it.
- **Notes:** Current implementation at `frontend/src/components/auction/BidHistory.tsx`. The React Query layer is already structured for `useInfiniteQuery` if the pattern is adopted — the change is mostly UI + A11y.

### WS reconnect telemetry
- **From:** Epic 04 sub-spec 2 (Task 1 WS client hardening + Task 7 reconnecting banner)
- **Why:** The WS client reconnects automatically on drop, re-attaches subscriptions via the live-Map sweep (F.68), and renders a reconnecting banner + form-disable on disconnect. There is no logging / metrics hook that reports connection drops, average reconnect duration, or subscription re-attach counts to a backend telemetry endpoint. A production deployment deserves a dashboard that shows "median reconnect time" and "reconnect frequency per user" so ops can spot a flaky LB or a regional network issue.
- **When:** Epic 09 (Notifications) or Epic 10 (Admin & Moderation) — whichever ships the first observability surface. The data plane is a new `POST /api/v1/telemetry/ws-events` (authenticated, rate-limited) or equivalent, with the client batching events on `beforeunload`.
- **Notes:** Current reconnect state lives in `frontend/src/lib/ws/client.ts` (`useConnectionState` hook). Adding telemetry is a small addition at the state-transition boundaries — the footwork is the backend storage + aggregation side.

### Saved / watchlist "Curator Tray"
- **From:** Epic 04 sub-spec 2 (spec §19 — design system reference to Curator Tray)
- **Why:** The "Digital Curator" design system docs reference a "Curator Tray" — a pull-out drawer where logged-in users can stash saved / watched listings for later comparison. The auction detail page in sub-spec 2 ships without a "save" / "watchlist" button because the backing model (saved_auctions table, REST endpoints, hydration into the tray) is Browse-surface territory.
- **When:** Epic 07 (Browse & Search) — the tray is cross-surface (any card anywhere in the app can flip its saved state) so it ships alongside the Browse data model.
- **Notes:** Design reference: `docs/stitch_generated-design/DESIGN.md` section on Curator Tray. The auction detail page's `AuctionHeroGallery` and bid panel both have space reserved next to the title for a future heart/bookmark toggle — add the button when the model lands, do not shoehorn it in earlier.

### `BidSheet` swipe-to-dismiss
- **From:** Epic 04 sub-spec 2 (spec §13 — mobile pattern)
- **Why:** Intentionally out of scope. Spec §13 excludes swipe-to-dismiss to keep the dependency surface thin (no gesture library) and the A11y story tight. The drag handle on the sheet is `aria-hidden` and purely decorative.
- **When:** Indefinite — only if swipe-to-dismiss is explicitly demanded by a future UX review, and only with its own scoped design pass (threshold, cancel region, keyboard parity, screen-reader announcement).
- **Notes:** See FOOTGUNS §F.74. Do NOT add this "while you're in there" on an unrelated sheet refactor — the exclusion is deliberate, not accidental.

### Shared integration-test base class for scheduler-enabled property gating
- **From:** Epic 05 sub-spec 1 (Task 6 — ownership monitor)
- **Why:** Each new `@Scheduled` job added to the backend requires every existing `@SpringBootTest` to add a `slpa.<job>.enabled=false` line to its `@TestPropertySource` to prevent races with test seeding. Epic 05 sub-spec 1 alone added 3+ such jobs (ownership monitor, timeout, dispatcher) and each expansion touches 8-12 tests. Shared `@TestPropertySource` on an abstract base class, or an `@IntegrationTestDefaults` meta-annotation, would let future epics add schedulers without N-test sweeps.
- **When:** Indefinite (infrastructure polish) — trigger is when another epic adds a scheduler that requires a new wave of per-test property disables.
- **Notes:** Touchpoint: any `@SpringBootTest` in `backend/src/test/java` with a `slpa.*.enabled=false` entry on its `@TestPropertySource`. Alternative shapes: (a) `@ActiveProfiles("integration-test")` + an `application-integration-test.yml` that disables every scheduler by default; (b) `@Import(SchedulersDisabledConfig.class)` bean-override; (c) a new `@IntegrationTest` meta-annotation that composes `@SpringBootTest` + the common property set.

### Richer outbid toast shape (warning variant + structured action button)
- **From:** Epic 04 sub-spec 2 (Task 7 — `OutbidToastProvider`)
- **Why:** Spec §15 prescribes `toast.warning({ title, description, action: { label: "Place a new bid", onClick: scrollToBidPanel } })`. The current `useToast()` primitive only exposes `success` / `error` variants with a plain string payload, so Task 7 shipped `toast.error("You've been outbid — current bid is L$X.")` plus an automatic `scrollIntoView` side-effect on the bid panel. Functional for Phase 1; loses the distinct warning tone and the explicit "Place a new bid" action button the spec specifies.
- **When:** Epic 09 (Notifications) is the natural pull-in point — notification fan-out will want structured toast actions ("View listing" / "Dismiss") and a warning tone, so widening the Toast primitive becomes load-bearing there. A design-system sweep is an acceptable earlier trigger if one happens first.
- **Notes:** Expansion path: widen `ToastKind` to `success | error | warning | info`, widen `ToastMessage` to accept `{ title, description, action?: { label, onClick } }`, update `ToastProvider` + `Toast` components accordingly. `OutbidToastProvider.maybeFire` then swaps its current single-string `toast.error` call for `toast.warning({ title: "You've been outbid", description: \`Current bid is L$${x}.\`, action: { label: "Place a new bid", onClick: scrollToBidPanel } })` and drops the imperative scroll-on-fire side-effect in favor of the action button. Component lives at `frontend/src/components/auction/OutbidToastProvider.tsx`; toast primitive at `frontend/src/components/ui/toast/` (approximate — confirm at pull-in time).

### Shared-secret version rotation provenance on TerminalCommand
- **From:** Epic 05 sub-spec 1 (Task 7)
- **Why:** The `terminal_commands.shared_secret_version` column is reserved but no code populates or reads it. Used to stamp which secret version was in force at dispatch so admin tooling can reason about rotated-secret audit trails.
- **When:** Epic 10 (Admin & Moderation) — wire alongside the admin secret-rotation endpoint already deferred.
- **Notes:** Column is nullable today; no data loss. Touchpoint: `TerminalCommandService.queue(...)` + a future rotation endpoint that stamps the new version on in-flight commands.

### FAILED ledger row on transport-failure stall
- **From:** Epic 05 sub-spec 1 (Task 7 code review, M6)
- **Why:** Terminal-reported failures write a FAILED `EscrowTransaction` row per attempt (audit trail). Transport-level failures (HTTP 5xx, connection refused, timeout) only set `last_error` on the command + bump `attemptCount`; the dispute timeline lacks visibility into transport failures that exhaust the retry budget. On the stall path (attempt 4), consider writing a FAILED ledger row so the dispute timeline records the stall uniformly.
- **When:** Opportunistic — pull in during the next Epic 05 maintenance task, or alongside Epic 10 admin tooling when the dispute-timeline UI surfaces this asymmetry.
- **Notes:** Touchpoint: `TerminalCommandDispatcherTask.dispatchOne` + `TerminalCommandService.applyCallback` (need to factor the FAILED ledger row build into a shared helper).

### HMAC-SHA256 terminal auth
- **From:** Epic 05 sub-spec 1
- **Why:** Sub-spec 1 ships static shared secret + rotation via config + redeploy. HMAC-SHA256 adds per-request replay protection but requires SHA256 implementation in LSL (~50-100 line library). Premature to ship until a working LSL terminal exists to dogfood against.
- **When:** Phase 2 hardening — after Epic 11 LSL terminals are stable and SHA256-in-LSL is validated.
- **Notes:** Body + timestamp HMAC, per-request nonce, backend nonce-replay window (~60s). `TerminalCommand.shared_secret_version` column already reserved for rotation bookkeeping.

### Smart regional routing for TerminalCommand dispatch
- **From:** Epic 05 sub-spec 1
- **Why:** Phase 1 dispatcher picks any active terminal for any command (pooled, non-sticky). If terminal deployment spreads across >5 regions and regional rate limits start to bite, smart routing (prefer terminals in the recipient's current region, fall back to pool) becomes useful.
- **When:** Indefinite — trigger is operational, not feature-driven.
- **Notes:** `Terminal.region_name` column reserved. Router pluggable behind a `TerminalSelector` interface.

### Notifications for escrow lifecycle events
- **From:** Epic 05 sub-spec 1
- **Why:** State transitions (FUNDED, TRANSFER_CONFIRMED, COMPLETED, EXPIRED, DISPUTED, FROZEN) and the 24h seller-transfer reminder log at INFO but fire no email / SL IM. Consistent with Epic 04's deferral.
- **When:** Epic 09 (Notifications) — hook a subscriber on the escrow broadcast envelope stream.

### Admin tooling for DISPUTED / FROZEN resolution + secret rotation
- **From:** Epic 05 sub-spec 1
- **Why:** No resume path from terminal states (DISPUTED, FROZEN) in sub-spec 1. Admin also has no in-app way to rotate `slpa.escrow.terminal-shared-secret` — rotation requires config edit + redeploy.
- **When:** Epic 10 (Admin & Moderation).
- **Notes:** Admin endpoints `POST /api/v1/admin/escrow/{id}/resolve-dispute`, `POST /api/v1/admin/escrow/{id}/unfreeze`, `POST /api/v1/admin/terminal/rotate-secret`. State machine gains `DISPUTED → FUNDED | TRANSFER_PENDING` and `FROZEN → TRANSFER_PENDING | EXPIRED` at admin's discretion.

### Daily escrow balance reconciliation
- **From:** Epic 05 sub-spec 1
- **Why:** DESIGN.md §5.2 suggests "sum of pending escrow amounts should match the expected SL account balance." Sub-spec 1 writes every L$ movement to the `EscrowTransaction` ledger, so the data is there — just no job reconciles it against SL grid queries.
- **When:** Epic 10 (Admin & Moderation).
- **Notes:** Daily job that sums `EscrowTransaction` rows by type, queries SLPAEscrow account balance via World API, alerts on mismatch.

### Retrofit existing Epic 03/04 code to Clock injection
- **From:** Epic 05 sub-spec 1
- **Why:** Sub-spec 1 code injects `Clock` and calls `OffsetDateTime.now(clock)` throughout. Existing Epic 03/04 services that use raw `OffsetDateTime.now()` are unaffected but can't be cleanly tested with a frozen clock. Out of scope for this sub-spec; retrofit when touched.
- **When:** Opportunistic — pull in during the next maintenance pass that touches the affected services.

### Dispute evidence attachments
- **From:** Epic 05 sub-spec 2
- **Why:** Sub-spec 2 ships a minimal dispute form (reasonCategory + 10-2000-char description). A real dispute workflow benefits from file uploads (screenshots), SL transaction references, an optional linked in-world chat log, and a timeline of prior attempts. The dispute route was deliberately scoped as a full page so these additions can land without re-architecting.
- **When:** Epic 10 (Admin & Moderation) — at the same time the admin dispute-resolution tooling lands so both sides mature together.
- **Notes:** Additions likely include file uploads (reuse Epic 02 avatar-upload's S3 path), optional `slTransactionKey` field for `PAYMENT_NOT_CREDITED` claims, evidence timeline. DTO expansion on `EscrowDisputeRequest` + new evidence entity on the backend.

### `PAYMENT_NOT_CREDITED` dispute reconciliation
- **From:** Epic 05 sub-spec 2 (design review)
- **Why:** The reason category claims "I paid but escrow didn't advance," which is the class of claim that indicates a happy-path failure (L$ may have already left the winner's wallet). Automatic refund on this dispute category risks double-paying the winner if the original payment callback later lands via idempotent retry.
- **When:** Epic 10 (Admin & Moderation) — alongside admin dispute-resolution tooling. The admin workflow must pull the SLPA terminal ledger balance, the winner's claimed `slTransactionKey` (see evidence-attachments opener above), and reconcile against the backend's `EscrowTransaction` ledger before any refund.
- **Notes:** Until Epic 10, `PAYMENT_NOT_CREDITED` disputes transition to `DISPUTED` and sit awaiting manual review like every other category.

### Terminal locator on PAY ESCROW state
- **From:** Epic 05 sub-spec 2 (`PendingStateCard` winner view)
- **Why:** The winner's `ESCROW_PENDING` card includes a "Find a terminal" button rendered disabled because no in-world terminal locator exists yet. A real implementation maps registered `Terminal` rows (sub-spec 1 §7.5) to their SL region names + SLURL links.
- **When:** Epic 11 (LSL scripting) — when real in-world terminals are deployed. Pre-launch ops checklist.
- **Notes:** The backend already has the data (`Terminal.region_name` + `http_in_url`). Add a public endpoint `GET /api/v1/sl/terminals/public` returning `[{ terminalId, regionName, slUrl }]` to feed the locator.

### Cross-page eventbus for dashboard row escrow freshness
- **From:** Epic 05 sub-spec 2 (§2.4)
- **Why:** Dashboard rows pick up `escrowState` changes via `refetchOnWindowFocus` + navigation — not via envelope-driven invalidation. Lags live state by up to ~30s on a stale tab. Acceptable for Phase 1.
- **When:** Indefinite — only pull in if user feedback shows the lag feels wrong.
- **Notes:** Implementation is ~30 LoC (named emitter + two subscriber hooks).

---

## Removal Criteria

An entry is removed from this list when:
- The work lands in a merged PR, OR
- The decision is made to permanently not do the work (in which case, record that decision in a FOOTGUNS entry or elsewhere so the rationale survives)

Do not remove entries as a shortcut. If an item was "mostly done" but some specific piece is still missing, leave the entry and narrow its scope to what remains.
