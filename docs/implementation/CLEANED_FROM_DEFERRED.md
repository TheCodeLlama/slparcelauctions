# Cleaned From DEFERRED_WORK.md

Audit date: **2026-04-28**, branch: `dev`.

This report records entries removed from `DEFERRED_WORK.md` after a sweep against the current codebase confirmed they are implemented. Each row points at the code that satisfies the original deferral so you can audit the decision later.

The remaining entries in `DEFERRED_WORK.md` are still genuinely deferred (Phase 2 work, indefinite cosmetic polish, intentionally-excluded items, ops-checklist items, or partial implementations awaiting their full home).

---

## Removed entries (13)

### 1. LSL script for in-world verification terminal
- **Originating epic/task:** Epic 02 sub-spec 1 (Task 02-02)
- **Implementation:**
  - `lsl-scripts/verification-terminal/verification-terminal.lsl` — touch handler, notecard config loader (`config` notecard, `VERIFY_URL` + `DEBUG_OWNER_SAY` keys), avatar-data buffers, async POST to the backend (`postVerifyRequest()` at line 122), grid-guard check on `llGetEnv("sim_channel") == "Second Life Server"`
  - `lsl-scripts/verification-terminal/config.notecard.example` — template config
  - `lsl-scripts/verification-terminal/README.md` — deployment / config / operations doc
- **Notes:** The dev-profile `POST /api/v1/dev/sl/simulate-verify` and Postman `Dev/Simulate SL verify` helper that stood in during development still exist and are useful for browser-driven testing.

---

### 4. Account deletion UI
- **Originating epic/task:** Epic 02 sub-spec 2a (Task 02-03 user profile backend)
- **Implementation:**
  - Backend: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java:87-93` — `@DeleteMapping("/me")` returns 204 NO_CONTENT and delegates to `userDeletionService.deleteSelf(principal.userId(), body.password())`
  - Backend: `backend/src/main/java/com/slparcelauctions/backend/user/deletion/UserDeletionService.java:70-78` — `deleteSelf` runs password re-auth (`InvalidPasswordException`), the four-precondition check (`ActiveAuctionsException`, `OpenEscrowsException`, `ActiveHighBidsException`, `ActiveProxyBidsException`), and the cascade (`UserDeletionService.java:164-175` — null PII, set `deletedAt`, bump `tokenVersion` to invalidate JWTs)
  - Backend: `UserDeletionService.deleteByAdmin` (line 94) provides the admin counterpart, recording an `AdminAction` audit row
  - Frontend: `frontend/src/components/settings/DeleteAccountSection.tsx:1-107` — collapsible danger-zone section with password input, error mapping for 401/403/409/410, post-delete cache clear + `/goodbye` redirect
  - Frontend: `frontend/src/app/settings/notifications/page.tsx` — renders the section
- **Notes:** Replaces the original 501 stub. Cascade-matrix design (active auctions, open escrows, proxy bids, high bids) backed by exception classes under `user/deletion/exception/`.

---

### 5. Notification preferences editor
- **Originating epic/task:** Epic 02 sub-spec 2b (Task 02-04 dashboard)
- **Implementation:**
  - `frontend/src/app/settings/notifications/page.tsx` — route renders `NotificationPreferencesPage`
  - `frontend/src/components/notifications/preferences/NotificationPreferencesPage.tsx:1-71` — `MasterMuteRow` toggle for `slImMuted`, per-group `GroupToggleRow` for the editable groups in `EDITABLE_GROUPS`, optimistic UI seeded from `useNotificationPreferences()` and persisted via `useUpdateNotificationPreferences()`
- **Notes:** Editor lives under `/settings/notifications` rather than inside `ProfileEditForm.tsx`, which the deferral predicted as the touchpoint.

---

### 11. Non-dev admin endpoint for ownership-monitor trigger
- **Originating epic/task:** Epic 03 sub-spec 2 (DevOwnershipMonitorController)
- **Implementation:**
  - `backend/src/main/java/com/slparcelauctions/backend/admin/ownership/AdminOwnershipRecheckController.java:11-24` — `POST /api/v1/admin/auctions/{id}/recheck-ownership` (no class-level `@PreAuthorize` here, but admin gating is enforced by the JWT principal flow + admin-package security rules), delegates to `AdminOwnershipRecheckService.recheck`
- **Notes:** The dev-profile `POST /api/v1/dev/ownership-monitor/run` is preserved per the deferral note — separate auth and response shape, used by tests and local verification.

---

### 19. Shared-secret version rotation provenance on TerminalCommand
- **Originating epic/task:** Epic 05 sub-spec 1 (Task 7)
- **Implementation:**
  - `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java:147-151` — every `queue(...)` call asks `terminalSecretService.current()` for the active secret version and stamps it on the new `TerminalCommand` row before save (`cmd.setSharedSecretVersion(String.valueOf(currentVersion))`)
  - `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommand.java:111-112` — column definition (`@Column(name = "shared_secret_version", length = 20)`)
- **Notes:** The deferral specifically said "no code populates or reads it" — `TerminalCommandService` now populates on dispatch. Pairs with item #22 (admin secret-rotation endpoint), which the deferral grouped together.

---

### 22. Admin tooling for DISPUTED / FROZEN resolution + secret rotation
- **Originating epic/task:** Epic 05 sub-spec 1
- **Implementation:**
  - **Resolve dispute / unfreeze (combined):** `backend/src/main/java/com/slparcelauctions/backend/admin/disputes/AdminDisputeController.java:41-47` — `POST /api/v1/admin/disputes/{escrowId}/resolve` with `@PreAuthorize("hasRole('ADMIN')")`, delegates to `AdminDisputeService.resolve`
  - **Action enum:** `backend/src/main/java/com/slparcelauctions/backend/admin/disputes/AdminDisputeAction.java:3-8` — exact four state transitions the deferral asked for: `RECOGNIZE_PAYMENT` (DISPUTED→TRANSFER_PENDING), `RESET_TO_FUNDED` (DISPUTED→FUNDED or EXPIRED), `RESUME_TRANSFER` (FROZEN→TRANSFER_PENDING), `MARK_EXPIRED` (FROZEN→EXPIRED)
  - **Secret rotation:** `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/terminals/AdminTerminalRotationController.java:11-23` — `POST /api/v1/admin/terminals/rotate-secret` with `@PreAuthorize("hasRole('ADMIN')")`, delegates to `AdminTerminalRotationService.rotate(adminUserId)`
- **Notes:** "Unfreeze" is implemented as the `RESUME_TRANSFER` and `MARK_EXPIRED` actions on the unified resolve endpoint rather than a separate `unfreeze` endpoint — same operational outcome, cleaner state machine.

---

### 23. Daily escrow balance reconciliation
- **Originating epic/task:** Epic 05 sub-spec 1
- **Implementation:**
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationService.java:46-80` — `@Scheduled(cron = "0 0 3 * * *", zone = "UTC")` daily 03:00 UTC, sums `EscrowState.{FUNDED, TRANSFER_PENDING, DISPUTED, FROZEN}` rows via `sumLocked()`, fetches the freshest terminal balance reading (≤ 2h staleness window), retries once on a 30-second wait if drift detected, persists a `ReconciliationRun` row with `BALANCED` / `ERROR` status, alerts admins via `NotificationPublisher` on mismatch
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationRun.java` — persisted ledger row
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/AdminReconciliationController.java` — admin read surface for run history
- **Notes:** `@ConditionalOnProperty(prefix = "slpa.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)` so test profiles can disable.

---

### 24. Dispute evidence attachments
- **Originating epic/task:** Epic 05 sub-spec 2
- **Implementation:**
  - `backend/src/main/java/com/slparcelauctions/backend/escrow/dispute/DisputeEvidenceUploadService.java:23-60` — accepts up to 5 images per side (`MAX_IMAGES_PER_SIDE = 5`), 5 MiB max per image, content-type allowlist `image/png|jpeg|webp`, persists under MinIO key `dispute-evidence/{escrowId}/{role}/{uuid}.{ext}`, returns `EvidenceImage` records
  - `backend/src/main/java/com/slparcelauctions/backend/escrow/dispute/exception/` — `EvidenceImageContentTypeException`, `EvidenceImageTooLargeException`, `EvidenceTooManyImagesException`
  - `backend/src/main/java/com/slparcelauctions/backend/admin/disputes/DisputeEvidenceImageDto.java` — admin-side DTO
  - `backend/src/main/java/com/slparcelauctions/backend/admin/disputes/AdminDisputeQueryService.java` + `AdminDisputeDetail.java` — surface evidence to the admin UI
  - `backend/src/main/java/com/slparcelauctions/backend/escrow/dto/EscrowDisputeRequest.java:14` — `slTransactionKey` field, validated as required for `PAYMENT_NOT_CREDITED` disputes
- **Notes:** The deferral predicted "additions likely include file uploads (reuse Epic 02 avatar-upload's S3 path), optional `slTransactionKey` field" — both landed.

---

### 25. `PAYMENT_NOT_CREDITED` dispute reconciliation
- **Originating epic/task:** Epic 05 sub-spec 2 (design review)
- **Implementation:**
  - `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java:199-204` — service-layer guard: `slTransactionKey` is required when `reasonCategory == PAYMENT_NOT_CREDITED` (else `IllegalArgumentException`)
  - `AdminDisputeAction.RECOGNIZE_PAYMENT` (DISPUTED → TRANSFER_PENDING) is the admin reconciliation action that closes a `PAYMENT_NOT_CREDITED` dispute after manual ledger reconciliation
  - Admin can pull the SLPA terminal ledger via the reconciliation read surface (item #23) and the disputant's `slTransactionKey` from the dispute detail (item #24), satisfying the "reconcile against the backend's `EscrowTransaction` ledger before any refund" workflow the deferral required
- **Notes:** The deferral was specifically about the admin reconciliation tooling, not a per-category code path. With the resolve action available and the evidence/transaction-key plumbing in place, the workflow is operable end-to-end.

---

### 28. Admin pool health dashboard
- **Originating epic/task:** Epic 06 (spec §1.2)
- **Implementation:**
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/bots/AdminBotPoolController.java:11-23` — `GET /api/v1/admin/bot-pool/health` with `@PreAuthorize("hasRole('ADMIN')")` returning `List<BotPoolHealthRow>`
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/bots/AdminBotPoolService.java:32-65` — joins the durable `bot_workers` rows with the Redis-backed live state at key `bot:heartbeat:{slUuid}` (TTL-backed); falls back to `null` state fields when Redis returns no row
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/bots/BotHeartbeatController.java:11-23` — `POST /api/v1/bot/heartbeat` (gated by `BotSharedSecretAuthorizer` in `SecurityConfig`)
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/bots/BotHeartbeatService.java` — handler that upserts the worker row + writes Redis state
- **Notes:** The same heartbeat shape was reused for terminal health (`TerminalHeartbeatController.java` / `TerminalHeartbeatService.java`).

---

### 37. REVIEW_RESPONSE_WINDOW_CLOSING notification + scheduler
- **Originating epic/task:** Epic 09 sub-spec 1
- **Implementation:**
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reminders/ReviewResponseWindowClosingScheduler.java:1-40` — daily 09:05 UTC scheduler, fires once per review when its response deadline (`revealedAt + 14 days`) is 24–48h away, only for reviews without an existing `ReviewResponse`
  - Stamps `responseClosingReminderSentAt` on the `Review` to prevent duplicate reminders
  - `@ConditionalOnProperty(prefix = "slpa.review-response-reminder", name = "enabled", matchIfMissing = true)` for test-profile gating
  - `backend/src/test/java/com/slparcelauctions/backend/admin/infrastructure/reminders/ReviewResponseWindowClosingSchedulerTest.java` — sibling test suite
  - `NotificationCategory` + `NotificationPublisher` + `NotificationDataBuilder` carry the `REVIEW_RESPONSE_WINDOW_CLOSING` category end-to-end
- **Notes:** Lands in the same package as item #38 — both timing-shaped reminder schedulers cohabit `admin/infrastructure/reminders/`.

---

### 38. ESCROW_TRANSFER_REMINDER scheduler
- **Originating epic/task:** Epic 09 sub-spec 1
- **Implementation:**
  - `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reminders/EscrowTransferReminderScheduler.java:1-71` — `@Scheduled(cron = "0 0 9 * * *", zone = "UTC")` daily 09:00 UTC, queries escrows with `transferDeadline ∈ [now+12h, now+36h]` via `EscrowRepository.findEscrowsApproachingTransferDeadline`, fires `publisher.escrowTransferReminder(...)` per row, stamps `Escrow.reminderSentAt`
  - `backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java:99-101` — `reminderSentAt` column for the once-per-escrow guarantee
- **Notes:** Implementation matches the "implementation sketch" from the deferral exactly (column + scheduler + publisher call + once-per-entity guard).

---

### 35. Auction.title NOT NULL backfill on first production deploy
- **Originating epic/task:** Epic 07 sub-spec 1 (Task 2)
- **Implementation:**
  - Resolved by Flyway baseline migration `backend/src/main/resources/db/migration/V1__initial_schema.sql`, which captures the `title VARCHAR(120) NOT NULL` column definition. First prod deploy applies V1 against an empty DB; no manual `ALTER TABLE` required.
  - `application.yml` switched from `ddl-auto: update` to `ddl-auto: validate`, with `spring.flyway.enabled: true` + `baseline-on-migrate: true`.
  - `application-dev.yml` switched to `validate` to keep dev/prod schema management aligned.
  - `AuctionTitleDevTouchUp.java` deleted — the V1 baseline already includes the column definition, so the dev-side workaround is dead code.
  - `MaturityRatingDevTouchUp.java` deleted for the same reason (the maturity_rating canonicalization is captured in V1's `auctions_maturity_rating_check` constraint).
- **Notes:** Pre-deploy snapshot via `aws rds create-db-snapshot` still required per AWS deployment design §4.5 (CI step), but the schema migration itself is now version-controlled. Future schema changes go through `V<N>__<description>.sql` migrations under `backend/src/main/resources/db/migration/` per `CONVENTIONS.md`.

---

## Items audited and confirmed still deferred

| # | Title | Status | Verified evidence |
|---|---|---|---|
| 2 | WebSocket push for verification completion | NOT_DONE | `frontend/src/components/user/UnverifiedVerifyFlow.tsx:11` — `useCurrentUser({ refetchInterval: 5000 })`, no STOMP subscription |
| 3 | Email change flow | NOT_DONE | `frontend/src/components/user/ProfileEditForm.tsx:12-23` — schema only validates `displayName` + `bio`; line 67-70 renders email as read-only `<p>` |
| 6 | Realty group badge on public profile | NOT_DONE | `frontend/src/components/user/PublicProfileView.tsx:49-142` — no group badge in the header block |
| 7 | Follow/unfollow from public profile | NOT_DONE | Same file — no follow button or `useFollow` hook anywhere |
| 8 | Drag-drop animation polish | PARTIAL | `frontend/src/components/user/ProfilePictureUploader.tsx:140-144` — has `transition-colors` only; no scale animation as the deferral describes |
| 9 | PARCEL code generation rate tracking (fraud signal) | NOT_DONE | `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagReason.java` — 12 reasons, none for parcel-code burn rate |
| 10 | SLPA trusted-owner-keys production config | OPS-PENDING | `backend/src/main/resources/application-prod.yml:22` — `trusted-owner-keys: []` with comment "deploy pipeline injects the real UUIDs via env"; `SlStartupValidator` is the forcing function |
| 12 | Destructive-variant copy polish | NOT_DONE (cosmetic) | Indefinite — no second destructive use case to trigger the upgrade |
| 13 | Region autocomplete for DistanceSearchBlock | NOT_DONE | `frontend/src/components/browse/DistanceSearchBlock.tsx:36-38` — explicit deferral comment in the file's docstring |
| 14 | Infinite-scroll on browse grid | NOT_DONE | `frontend/src/components/browse/BrowseShell.tsx:5` imports `Pagination`, not `useInfiniteQuery` |
| 15 | Bid history infinite scroll | NOT_DONE | `frontend/src/components/auction/BidHistoryList.tsx:32-33` — explicit deferral comment "Infinite scroll is deferred per spec §19" |
| 16 | WS reconnect telemetry | NOT_DONE | No `/api/v1/telemetry/ws-events` endpoint; grep for `telemetry`/`wsReconnect` in `frontend/src` returns nothing |
| 17 | `BidSheet` swipe-to-dismiss | NOT_DONE (intentional) | `frontend/src/components/auction/BidSheet.tsx:21-26` — explicit comment "Swipe-to-dismiss is intentionally absent per spec §11" |
| 18 | Shared integration-test base class | NOT_DONE | No `@IntegrationTest` meta-annotation, no `application-integration-test.yml`, no `SchedulersDisabledConfig` |
| 20 | HMAC-SHA256 terminal auth | NOT_DONE | Phase 2 hardening — `TerminalCommand` carries the secret-version stamp (item #19) but auth is still bearer/static |
| 21 | Smart regional routing for TerminalCommand dispatch | NOT_DONE | No `TerminalSelector` interface; dispatcher picks any active terminal |
| 26 | Terminal locator on PAY ESCROW state | NOT_DONE | `frontend/src/components/escrow/state/PendingStateCard.tsx:46-50` — `<Button disabled>Find a terminal</Button>` with explicit deferral comment; no `GET /api/v1/sl/terminals/public` endpoint |
| 27 | Cross-page eventbus for dashboard row escrow freshness | NOT_DONE | No `escrowEventBus` / `EscrowEventEmitter` in `frontend/src` |
| 29 | Per-worker auth tokens (`bot_workers` table) | NOT_DONE | `BotWorker.java` exists at `admin/infrastructure/bots/BotWorker.java:9-35` — but is the heartbeat-tracking entity (id, name, slUuid, firstSeenAt, lastSeenAt). The deferral asks for `token_hash` / `revoked_at` columns and a per-worker authorizer; current auth still uses single shared bearer secret in `BotSharedSecretAuthorizer` |
| 30 | HMAC-SHA256 per-request bot auth | NOT_DONE | `BotSharedSecretAuthorizer` uses `MessageDigest.isEqual` constant-time bearer compare; no HMAC, no nonce, no timestamp |
| 31 | Parcel layout map generation | NOT_DONE | `frontend/src/components/auction/ParcelLayoutMapPlaceholder.tsx` is the placeholder the deferral hints at; no `BotTaskType.LAYOUT_MAP` or `LayoutMapHandler.cs` |
| 32 | TRANSFER_READY_OBSERVED envelope shape | NOT_DONE (stub) | `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java:952-955` — `publishTransferReadyObserved` is log-only; `BotMonitorDispatcher.java:119, 160` already calls it but no envelope leaves the JVM |
| 33 | BotMonitorDispatcher strategy split | NOT_DONE (opportunistic) | `BotMonitorDispatcher.java:38-50` — still single class with `switch(task.getTaskType())`. Deferred until next branching expansion. |
| 34 | Public StatsBar on homepage (activity-threshold gated) | NOT_DONE (product) | `frontend/src/app/page.tsx:20-24` — explicit comment "StatsBar is intentionally omitted. Per product decision, launching with low activity numbers reads as a liability" |
| 35 | Auction.title NOT NULL backfill on first prod deploy | OPS-PENDING | `backend/src/main/java/com/slparcelauctions/backend/auction/dev/AuctionTitleDevTouchUp.java` is the dev workaround; no Flyway migration; first-prod deploy still requires manual `ALTER TABLE` |
| 36 | Email channel for notifications | REMOVED | Marked "Removed from roadmap" on the entry itself — kept as a record of the explicit decision |
| 39 | Quiet hours UI for SL IM | NOT_DONE | `backend/src/main/java/com/slparcelauctions/backend/user/User.java:217-221` — `slImQuietStart` + `slImQuietEnd` columns dormant; preferences page (item #5) does not surface them |
| 40 | HTTP-in push from backend to dispatcher for urgency | NOT_DONE | No `notificationDispatcherUrl` / push-on-urgency mechanism; `notification/slim/SlImChannelDispatcher.java` is the dispatcher contact and remains poll-driven |
| 41 | Sub-day SL IM dispatcher health monitoring | NOT_DONE | No `dispatcher_health` row, no `last_polled_at` column |
| 42 | `REPORT_THRESHOLD_REACHED` admin-targeted notification | NOT_DONE | `AdminReportListingRowDto.java:10` carries `openReportCount` for queue sorting but no `REPORT_THRESHOLD_REACHED` notification category exists |
| 43 | Admin "Send notification to user" surface | NOT_DONE | No `POST /api/v1/admin/users/{id}/notify` endpoint |
| 44 | Frivolous-reporter automatic privilege revocation | PARTIAL | `User.java:193` carries `dismissedReportsCount`, surfaced on `AdminUserDetailDto.java:22`. No `reportingPrivilegeRevoked` flag, no threshold trigger in `ListingReportService` |
| 45 | Realtime ban broadcast / forced-logout WebSocket | NOT_DONE | `BanCacheInvalidator.java:18-40` invalidates the Redis cache only; no STOMP broadcast on `/topic/user/{userId}/account-status`; no `BanBroadcastService`; no `AccountStatusWatcher` hook |
| 46 | ProxyBid bidder fan-out from admin-cancel | NOT_DONE (conditional) | `CancellationService.java:259-261` (admin-cancel branch) — the fan-out queries `bidRepo.findDistinctBidderUserIdsByAuctionId`, which only returns users with placed bids; users with proxy bids that never became leading bids are not fanned-out. The deferral is conditional on "when proxy bidding ships and operational data shows proxy-bidder notification gaps" — proxy bidding shipped in Epic 04 but no operational data has demanded the expansion. |

---

## Methodology

1. Pulled every entry in `DEFERRED_WORK.md` and grouped by target phase / epic.
2. For each entry, opened the relevant source file(s) at the locations the deferral predicted (or, where the deferral did not predict a location, grepped for the implementation marker named in its notes — endpoint path, class name, column, hook, etc.).
3. For DONE candidates, read the file at the cited line ranges to confirm the implementation actually matched the deferral's spec, not just a name collision (especially important for `BotWorker` / item #29, where the entity name matches but the schema does not).
4. Removed the confirmed DONE entries from `DEFERRED_WORK.md` per its own removal rule (line 12: "When finishing a sub-spec that completes a deferred item, remove the entry.").
