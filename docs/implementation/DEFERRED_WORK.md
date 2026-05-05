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

### WebSocket push for verification completion
- **From:** Epic 02 sub-spec 2b (Task 02-04 dashboard verify flow)
- **Why:** Considered during brainstorm. Deferred because the backend publisher needs to know when a real SL verification call succeeds — that signal source only exists once Phase 11 LSL work is real. Polling (5s, visibility-aware) is the right tool until then.
- **When:** Phase 11
- **Notes:** Replace the `useCurrentUser({ refetchInterval: 5000 })` polling with a STOMP subscription on `/topic/user/{userId}/verification`.

### Email change flow
- **From:** Epic 02 sub-spec 2b (Task 02-04 profile edit)
- **Why:** Requires a re-verification flow (new email → confirmation link → swap). Out of scope for the profile edit shipped in 2b. Not a browse/discovery concern — does not belong in Epic 07.
- **When:** Epic 09 Task 02 (email notifications) — the re-verification flow reuses the same transactional-email plumbing Task 02 stands up (templates, SMTP client, signed-token links). Ship the email-change flow as a follow-on within Epic 09 once that plumbing exists.
- **Notes:** `ProfileEditForm` currently only covers `displayName` and `bio`. Adding a new email column + verification token table can wait for the same migration pass that lands email-notification persistence.

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

### Drag-drop animation polish on ProfilePictureUploader
- **From:** Epic 02 sub-spec 2b (Task 02-04 profile picture upload)
- **Why:** Current drop zone uses a static border highlight. Polished version would animate border-color transition and a scale effect on drop.
- **When:** Indefinite (cosmetic)
- **Notes:** `frontend/src/components/user/ProfilePictureUploader.tsx`.

### PARCEL code generation rate tracking (fraud signal)
- **From:** Epic 03 sub-spec 1 (Method B rezzable callback flow)
- **Why:** Sellers who burn through many PARCEL codes without ever rezzing the object are either confused (needs better instructions) or probing/abusing the system. Not a Phase 1 concern but worth flagging once the flow is live and we have baseline usage data.
- **When:** Epic 10 (Admin & Moderation) — fraud flags
- **Notes:** Metric would be "count of PARCEL codes generated per seller over last N days where no successful callback occurred." Likely lives as a `fraud_signals` table or similar, feeding admin dashboards.

### SLParcels trusted-owner-keys production config
- **From:** Epic 03 sub-spec 1 (SL header trust)
- **Why:** `slpa.sl.trusted-owner-keys` is empty in `application.yml` (dev
  override in `application-dev.yml`). Production must override via env
  var / secrets manager.
- **When:** First production deployment (pre-launch ops checklist).
- **Notes:** `SlStartupValidator` already fails fast on prod boot if
  `trusted-owner-keys` is still empty — that is the forcing function.
  The bot half of this item (primary-escrow-uuid) landed in Epic 06 Task 3
  via `BotStartupValidator` and is no longer deferred. See FOOTGUNS §F.47.


### Destructive-variant copy polish
- **From:** Epic 03 sub-spec 2 (Task 9 review follow-up + Task 10 Button variant)
- **Why:** The Button `destructive` variant landed in Task 10 with `bg-error text-on-error`, sufficient for the `CancelListingModal` use case. Future destructive surfaces (delete-account, bulk cancel, fraud-flag un-suspend) may want a richer treatment — e.g. an icon-left convention, a "are you sure" two-step gesture, or a reduced-emphasis destructive outline variant for less consequential destructive actions.
- **When:** Indefinite — upgrade when a second destructive use case arrives and the current shape pinches.
- **Notes:** The current token mapping (`bg-error` / `text-on-error`) is the load-bearing part. Any polish should NOT switch to raw Tailwind palette classes (`bg-red-500`) — keep it on the M3 token system.


### Region autocomplete for DistanceSearchBlock
- **From:** Epic 07 sub-spec 2 (Task 2b)
- **Why:** Phase 1 ships a free-form region text input with server-side validation on submit (REGION_NOT_FOUND surfaces inline under the input). Client-side autocomplete needs a new lightweight `/sl/regions/search?q=` endpoint, debounced input, keyboard nav, and a popover primitive — scope for its own design pass.
- **When:** Phase 2 polish.
- **Notes:** Touchpoint: `DistanceSearchBlock.tsx`.

### Infinite-scroll on browse grid
- **From:** Epic 07 sub-spec 2 (Task 2b)
- **Why:** Phase 1 ships numbered pagination — shareable URLs, SSR-friendly, back-button sane. Infinite scroll introduces scroll-position restore, focus management, SR announcements that deserve their own scoped design pass.
- **When:** Indefinite — trigger is user feedback demanding it. Consolidates with the existing BidHistory infinite-scroll deferral.
- **Notes:** Touchpoint: `BrowseShell.tsx` + `useAuctionSearch`. React Query already supports `useInfiniteQuery`.

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

### Per-worker auth tokens (`bot_workers` table)
- **From:** Epic 06 brainstorm
- **Why:** Phase 1 ships a single shared bearer secret across all workers.
  Per-worker tokens give admin auditing (which worker made which call) but
  require a new provisioning surface. Premature at Phase 1 volume.
- **When:** Indefinite — trigger is an audit-trail requirement.
- **Notes:** New `bot_workers` table: `(id, name, sl_uuid, token_hash,
  created_at, revoked_at)`. Authorizer switches from single-secret
  compare to token-hash lookup. Rotation endpoint under `/api/v1/admin/`.

### HMAC-SHA256 per-request bot auth
- **From:** Epic 06 brainstorm
- **Why:** Bearer token is replay-vulnerable between request and response;
  HMAC-SHA256 over `(method, path, body, timestamp, nonce)` with a
  replay window prevents replay. Deferred because the same improvement
  is already deferred for the escrow terminal auth, and both should
  land together.
- **When:** Phase 2 hardening — same timeline as the escrow terminal
  HMAC rollout.
- **Notes:** Backend nonce-replay window ~60 s, stored in Redis.
  `slpa.bot.shared-secret` stays as the HMAC key; rotation via config
  + redeploy.

### Parcel layout map generation
- **From:** Epic 06 spec §1.2
- **Why:** DESIGN.md §5.5 flags this as needing further design. Four
  possible implementation routes (LSL scan, bot scan, wearable scanner,
  seller-run scanner) with no decision yet.
- **When:** Indefinite — pending a dedicated design pass.
- **Notes:** The bot scan variant would live in
  `bot/src/Slpa.Bot/Tasks/LayoutMapHandler.cs` alongside verify/monitor,
  driven by a new `BotTaskType.LAYOUT_MAP`. Do not scaffold until the
  design lands.

### TRANSFER_READY_OBSERVED envelope shape
- **From:** Epic 06 Task 5 (`BotMonitorDispatcher` MONITOR_ESCROW
  TRANSFER_READY branch)
- **Why:** `EscrowService.publishTransferReadyObserved` is a stub
  (logs + no-op publisher) awaiting the real escrow WS envelope shape.
  The dispatcher's first-transition log + call already lands; only the
  outbound envelope payload is deferred.
- **When:** Epic 05 follow-up — fold in when a second escrow WS
  envelope needs extending so the shape can be designed once across the
  family (status change + transfer-ready-observed together).
- **Notes:** Sub-spec 1's escrow envelopes use cache-invalidation-only
  semantics (FOOTGUNS §F.82). The TRANSFER_READY_OBSERVED envelope
  should follow that convention — carry only `{ auctionId, escrowId,
  observedAt }`, not the bot's raw observation.

### BotMonitorDispatcher strategy split
- **From:** Epic 06 Task 5 (`BotMonitorDispatcher` size)
- **Why:** The dispatcher is already ~300 LoC handling 8 `MonitorOutcome`
  values across MONITOR_AUCTION + MONITOR_ESCROW. If a third MONITOR_*
  type is added or the branching grows further, splitting into per-type
  strategy classes (`MonitorAuctionStrategy` + `MonitorEscrowStrategy`
  behind a `MonitorDispatchStrategy` interface) would keep the single
  class manageable.
- **When:** Opportunistic — pull in on the next touch that grows the
  branching beyond the current 13 dispatch-table entries.
- **Notes:** Tests are already structured per-outcome in
  `BotMonitorDispatcherTest` so a strategy-split refactor can rehome
  tests without rewriting assertions.

### Public StatsBar on homepage (activity-threshold gated)
- **From:** Epic 07 sub-spec 2 (Task 3)
- **Why:** Backend `GET /api/v1/stats/public` is live from sub-spec 1 but the homepage deliberately does not render a stats bar. Launching with low numbers ("2 active bidders") reads as a liability, not social proof. Re-enable once activity is strong enough that the numbers flatter the product.
- **When:** Product decision — trigger is an activity threshold, not a technical readiness gate.
- **Notes:** Touchpoint: `app/page.tsx`. Component to add: `StatsBar` in `components/marketing/`. `/stats/public` response shape already documented in sub-spec 1 §5.3.

### Email channel for notifications
- **Status:** Removed from roadmap. Re-add only on explicit user request.
- **Reasoning:** SL natively forwards offline IMs to the user's registered email,
  so the SL IM channel from Epic 09 sub-spec 3 covers the email use case at zero
  additional infrastructure cost.
- **If re-instated:** per-category templates (HTML + plain text), signed-token
  unsubscribe, debounce/dedupe matching the coalesce pattern, email-change flow
  (originally pending from Epic 02 sub-spec 2b).

### Quiet hours UI for SL IM
- **From:** Epic 09 sub-spec 3
- **Why:** Columns `slImQuietStart` and `slImQuietEnd` exist on User entity
  from Epic 02 sub-spec 2b; no UI consumes them and the dispatcher gate
  ignores them. May tie to a future timezone/account-settings sub-spec; no
  committed home yet. If unused for >12 months, drop the columns in a
  dedicated cleanup sub-spec.
- **When:** No committed phase.

### HTTP-in push from backend to dispatcher for urgency
- **From:** Epic 09 sub-spec 3
- **Why:** Current design polls every 60 seconds — fine for the events
  shipping today (worst case 60 s latency for outbid/won). If outbid latency
  becomes a UX concern, register the dispatcher's HTTP-in URL with the
  backend on startup and have the backend `llHTTPRequest` to it on
  high-priority categories to wake an early poll.
- **When:** Post-launch enhancement; needs the channel to have real traffic
  and a real complaint before the complexity earns its keep.

### Sub-day SL IM dispatcher health monitoring
- **From:** Epic 09 sub-spec 3
- **Why:** The expiry job's INFO log catches a dark dispatcher within 48 h. If
  sub-day signal becomes important, options include: a `last_polled_at`
  timestamp on a singleton `dispatcher_health` row written on each successful
  poll, with an alarm scheduler that pages on `now - last_polled_at > 5 min`.
- **When:** No committed phase. Out of scope until operational data shows the
  48 h canary is insufficient.

### `REPORT_THRESHOLD_REACHED` admin-targeted notification
- **From:** Epic 10 sub-spec 2 brainstorm
- **Why:** Sub-spec 2 ships the admin reports queue sorted by `reportCount DESC`, which surfaces high-report listings passively. If admins miss a listing that crosses 3+ open reports between queue refreshes, there's no proactive signal. A fan-out notification when a listing crosses the threshold would close that gap.
- **When:** Indefinite — pull in once operational data shows admins are missing high-report listings. Gate on a configurable threshold property (e.g. `slpa.reports.alert-threshold=3`).
- **Notes:** Implementation sketch: `AdminReportService.submitReport` increments `openReportCount`; if it crosses the threshold and no prior threshold notification exists for this listing, call `NotificationPublisher.reportThresholdReached(listing, adminIds)`. Needs a new `REPORT_THRESHOLD_REACHED` notification category.

### Admin "Send notification to user" surface
- **From:** Epic 10 sub-spec 2 (admin user-detail page design)
- **Why:** Admins sometimes need to send a custom SL IM to a user outside of the automated notification categories (e.g., "your account was flagged for review"). No freeform message surface exists.
- **When:** Indefinite — no committed phase. Add when a real operational need is demonstrated.
- **Notes:** Implementation would be a new `POST /api/v1/admin/users/{id}/notify` endpoint + modal on the user-detail page. Requires rate limiting per target user to prevent admin harassment.

### Frivolous-reporter automatic privilege revocation
- **From:** Epic 10 sub-spec 2 brainstorm
- **Why:** Sub-spec 2 ships `User.dismissedReportsCount` — the counter increments each time an admin dismisses one of a user's reports as frivolous. The counter is visible on the user-detail Moderation tab but no automatic threshold revocation is wired.
- **When:** Indefinite — pull in once operational data shows a threshold is justified.
- **Notes:** Counter is in place. Automatic revoke would be a flag (`User.reportingPrivilegeRevoked`) set when `dismissedReportsCount` crosses a configurable threshold, checked in `ListingReportService.submit`. Until then, admin can revoke manually via a future "ban from reporting" action.

### Realtime ban broadcast / forced-logout WebSocket
- **From:** Epic 10 sub-spec 2 design
- **Why:** Sub-spec 2 ships ban enforcement on the next API call — a banned user is blocked on their next bid/list/etc. request after the Redis cache (5-min TTL) flushes. There's no forced-logout WebSocket push that immediately disconnects the user's session.
- **When:** Indefinite — revisit if forced-logout latency becomes a user complaint (e.g., banned users continue to bid-spam within the 5-min cache window).
- **Notes:** Implementation shape: `BanCacheInvalidator` publishes a `BAN_IMPOSED` event on create; a new `BanBroadcastService` sends a `tv-bump` to `/topic/user/{userId}/account-status`; the frontend's `AccountStatusWatcher` hook redirects on receipt. See FOOTGUNS §F.106 for the current TTL reasoning.

### ProxyBid bidder fan-out from admin-cancel
- **From:** Epic 10 sub-spec 2 brainstorm
- **Why:** Sub-spec 2 ships admin-cancel with cause-neutral bidder fan-out via `listingCancelledBySellerFanout` for regular (manual) bidders. If proxy bidders exist, the current fan-out path also notifies them via the same `LISTING_CANCELLED_BY_SELLER` category reused for admin-cancel. If proxy-bid semantics are added in a future sub-spec, confirm the fan-out covers proxy-bidder edge cases (e.g., multiple proxy bids from the same user at different max amounts).
- **When:** Indefinite — pull in when proxy bidding ships and operational data shows proxy-bidder notification gaps.
- **Notes:** `NotificationPublisher.listingCancelledBySellerFanout` is the current fan-out method. Body strings are cause-neutral per FOOTGUNS §F.104.

### Wallet ledger cursor pagination
- **From:** Wallet top-level Phase 2 review (sub-spec `2026-05-01-wallet-toplevel-and-header-indicator-design.md` §6.1)
- **Why:** `GET /me/wallet/ledger` currently uses offset pagination via `Page<UserLedgerEntry>`, which forces a `count(*)` against `user_ledger` on every request. For long-lived users with many thousands of rows the `totalElements` cost dominates the request time. Reviewer M1 minor item — deferred because the JSON contract is locked to `PagedResponse<T>` (which exposes `totalElements`/`totalPages`) and the frontend pager UI in Phase 7 is built around numbered pages, not opaque cursors.
- **When:** Indefinite — revisit only if production traces show `count(*)` on `user_ledger` becoming a hot spot. Likely solution is a `seek`-style cursor (last `(createdAt, id)` tuple) plus a `hasMore` flag, with the frontend swapped to "load more" instead of numbered pages.
- **Notes:** Affects `MeWalletController#ledger` and `LedgerSpecifications`. The CSV export endpoint (Phase 3) already streams without pagination so it's unaffected.

### NAT instance CPU CloudWatch alarm
- **From:** AWS deployment design §4.12 (12 spec'd alarms; 11 shipped in `infra/observability/alarms.tf`)
- **Why:** The fck-nat module's output schema for the underlying EC2 instance ID was not verified at the time observability was wired. Adding the alarm without the right `module.fck_nat[0].<output>` reference would have either failed `terraform plan` or pointed at a wrong resource. Lower priority than the 11 alarms shipped (NAT egress failure first manifests as backend external-call timeouts, which the 5xx-rate alarm catches).
- **When:** Next infra touch — verify `module.fck_nat[0]` outputs (likely `instance_id` per the RaJiska/fck-nat module contract), add `aws_cloudwatch_metric_alarm.nat_instance_cpu` keyed on namespace=`AWS/EC2`, dimension `InstanceId`. Conditional on `var.nat_type == "instance"` to no-op when NAT Gateway is in use.
- **Notes:** Spec table row `slpa-nat-instance-cpu` > 80% over 5 min, alert-only.

---

## Removal Criteria

An entry is removed from this list when:
- The work lands in a merged PR, OR
- The decision is made to permanently not do the work (in which case, record that decision in a FOOTGUNS entry or elsewhere so the rationale survives)

Do not remove entries as a shortcut. If an item was "mostly done" but some specific piece is still missing, leave the entry and narrow its scope to what remains.
