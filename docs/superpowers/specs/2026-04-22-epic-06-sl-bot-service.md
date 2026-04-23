# Epic 06 — SL Bot Service

- **Author:** Heath
- **Status:** Approved
- **Date:** 2026-04-22
- **Branch:** `task/06-bot-service`
- **PR target:** `dev`

---

## 1. Goal & Scope

Ship the SL bot worker service that closes out Method C verification (Epic 03 sub-spec 1) and adds bot-tier monitoring for BOT-tier auctions and escrows, with the backend hardened against unauthenticated callers.

### 1.1 In scope

- C#/.NET 8 bot worker service — new top-level `bot/` directory alongside `backend/` and `frontend/`. One instance per SL account.
- Login, session keepalive, auto-reconnect, rate-limited teleport, `ParcelProperties` read via LibreMetaverse.
- Method C `VERIFY` handler. Wires into the existing `BotTaskService.complete()` SUCCESS/FAILURE callback.
- `MONITOR_AUCTION` (30 min) and `MONITOR_ESCROW` (15 min) recurring task types. Backend creates monitor rows on lifecycle entry and cancels them on lifecycle exit, atomically with the triggering transition.
- Bot service authentication: a static bearer token shared across all workers, validated on `/api/v1/bot/**`.
- Atomic claim endpoint (`POST /api/v1/bot/tasks/claim`) backed by `SELECT ... FOR UPDATE SKIP LOCKED`. `GET /api/v1/bot/tasks/pending` is kept read-only for debug.
- `IN_PROGRESS` timeout sweep on `BotTaskTimeoutJob` (closes an existing deferred item).
- `bot_tasks` schema extended with monitor + denormalized expected-value columns (Hibernate `ddl-auto: update`; no Flyway migration).
- Docker Compose wiring with per-bot service blocks reading `SLPA_BOT_USERNAME` / `SLPA_BOT_PASSWORD` from env. Dev ships 1 block (`bot-1`); production scales by adding blocks as SL accounts (SLPABot1..5) are provisioned.
- Backend startup guards for `slpa.bot.shared-secret` and `slpa.bot-task.primary-escrow-uuid`.

### 1.2 Out of scope (explicit deferrals)

- HMAC-SHA256 per-request auth (Phase 2 hardening; already deferred).
- Per-worker auth tokens + a `bot_workers` provisioning table (indefinite — Phase 1 has no audit requirement).
- Smart regional routing / cross-worker task batching (operational; already deferred).
- Admin dashboard for bot pool health (Epic 10).
- Notifications on monitor-detected fraud (Epic 09).
- Parcel layout map generation — DESIGN.md §5.5 flagged as needing further design.

### 1.3 User-visible impact

- Sellers using Method C get end-to-end verification in production (the queue surface was already there, the worker was missing).
- Active BOT-tier auctions detect seller circumvention (AuthBuyerID revoke, SalePrice drift, ownership transfer) within 30 min.
- BOT-tier escrows detect transfer completion (or fraud) within 15 min. Winners see a "Seller has set land for sale to you" indicator on the escrow page when the bot observes `TRANSFER_READY`.
- No change to NON-bot-tier flows (Methods A and B are World-API-monitored as before).

---

## 2. Architecture

Two processes, HTTP between them. The bot worker is an independent `.NET 8` service. It executes SL operations and reports observations. The Java backend owns task lifecycle, authorization, and all business-logic interpretation of those observations.

```
bot worker (one per SL account)            Java backend
                                            
  Login + session loop                       BotTaskController
  Rate-limited teleport (6/min)                POST /tasks/claim
  TaskLoop                        ───────►    PUT  /tasks/{id}/verify
    claim → teleport → read →                 POST /tasks/{id}/monitor
    dispatch handler → callback               GET  /tasks/pending (debug)
                                            
  VERIFY handler                             BotTaskService
  MONITOR handler (mechanical                  claim / verify / monitor
    classifier only)                           timeout sweep
                                               lifecycle hooks →
  GET /health (unauth)                            EscrowService.confirmTransfer
                                                 EscrowService.freezeForFraud
                                                 SuspensionService.suspend
                                                 (all idempotent)
```

### 2.1 State ownership split

| Concern                                       | Owner                                        |
|-----------------------------------------------|----------------------------------------------|
| Task lifecycle (PENDING/IN_PROGRESS/...)      | Java backend (`bot_tasks` row)               |
| Per-worker SL session state                   | Worker process (in-memory `GridClient`)      |
| Auction / escrow state transitions            | Existing Java services (`EscrowService`, `SuspensionService`, `AuctionService`) |
| Which SL account is logged in                 | Worker env (`SLPA_BOT_USERNAME`)             |
| What to check (region + parcel + expectations)| Task row payload (denormalized at creation)  |

### 2.2 Pull model on a loop

The worker's main loop is: claim a task (or 204 → sleep + retry), execute it, post the result, repeat. One in-flight task at a time per worker. The backend is the only coordinator — workers never talk to each other.

### 2.3 Failure domains

- Worker crash → container restart → new SL session → claims next PENDING task. Any in-flight IN_PROGRESS task is cleaned up by the backend's timeout sweep (re-armed for monitors, failed for verifies).
- Backend down → worker's claim call fails → worker backs off exponentially, up to ~60 s.
- SL grid blip → LibreMetaverse disconnect → worker reconnects with backoff; current task is abandoned, backend sweep handles it.
- Worker crash mid-teleport → task stays IN_PROGRESS → sweep fires after `slpa.bot-task.in-progress-timeout` (default PT20M).

### 2.4 Redundancy with World API monitors

Existing World API monitors (Epic 03 sub-spec 2's `OwnershipCheckTask` for active auctions every 15 min, Epic 05 sub-spec 1's `EscrowOwnershipMonitor` for FUNDED/TRANSFER_PENDING every 15 min) keep running for all verification tiers. The bot monitor runs **additionally** for BOT-tier entities only.

- World API gives ownership changes.
- Bot gives sale-status signals (AuthBuyerID, SalePrice) World API cannot see.
- Both can trigger the same downstream service calls. Service calls are idempotent — they no-op if the entity is already in the target state, so overlap is harmless.
- If the bot pool is down, BOT-tier entities still get World API coverage as a floor.

### 2.5 Business-logic split

The worker is observation-only. It reports what it saw (`AUTH_BUYER_CHANGED`, `OWNER_CHANGED`, `PRICE_MISMATCH`, `ACCESS_DENIED`, `TRANSFER_COMPLETE`, `TRANSFER_READY`, `STILL_WAITING`, `ALL_GOOD`). It does **not** decide whether an observation constitutes fraud — the backend's `BotMonitorDispatcher` owns that decision.

The one exception is the mechanical outcome classifier: comparing observed values to expected values denormalized on the task row. That is not business logic; it is "did the observed value equal the expected value." The backend decides what the outcome means.

---

## 3. Backend Changes

All changes land in the existing `bot` and `config` packages, plus small hooks into `auction`, `escrow`, and `suspension`.

### 3.1 Schema extensions on `bot_tasks`

Hibernate `ddl-auto: update` applies these. All additions are nullable so no backfill is needed.

| Column                              | Type            | Notes                                               |
|-------------------------------------|-----------------|-----------------------------------------------------|
| `id`                                | bigserial       | existing                                             |
| `task_type`                         | varchar(20)     | existing; CHECK widens to include `MONITOR_AUCTION`, `MONITOR_ESCROW` (via existing `EnumCheckConstraintSync`) |
| `status`                            | varchar(20)     | existing; adds `CANCELLED` value                     |
| `auction_id`                        | bigint FK       | existing, not null                                   |
| `escrow_id`                         | bigint FK       | **new**, nullable (set for `MONITOR_ESCROW`)         |
| `parcel_uuid`                       | uuid            | existing                                             |
| `region_name`                       | varchar(100)    | existing                                             |
| `position_x`/`position_y`/`position_z` | double       | **new**, nullable; denormalized from `Parcel`        |
| `sentinel_price`                    | bigint          | existing (VERIFY payload)                            |
| `expected_owner_uuid`               | uuid            | **new**, nullable (monitor tasks)                    |
| `expected_auth_buyer_uuid`          | uuid            | **new**, nullable                                    |
| `expected_sale_price_lindens`       | bigint          | **new**, nullable                                    |
| `expected_winner_uuid`              | uuid            | **new**, nullable (`MONITOR_ESCROW` only)            |
| `expected_seller_uuid`              | uuid            | **new**, nullable (`MONITOR_ESCROW` only)            |
| `expected_max_sale_price_lindens`   | bigint          | **new**, nullable (`MONITOR_ESCROW` only; default 1) |
| `next_run_at`                       | timestamptz     | **new**, nullable (`NULL` for VERIFY)                |
| `recurrence_interval_seconds`       | int             | **new**, nullable (`NULL` for VERIFY)                |
| `assigned_bot_uuid`                 | uuid            | existing                                             |
| `result_data`                       | jsonb           | existing; last monitor check result blob + streak counters |
| `last_check_at`                     | timestamptz     | **new**, nullable (stamped on every monitor callback)|
| `failure_reason`                    | varchar(500)    | existing                                             |
| `created_at`                        | timestamptz     | existing                                             |
| `completed_at`                      | timestamptz     | existing                                             |
| `last_updated_at`                   | timestamptz     | existing                                             |

Soft invariants not expressible cleanly in JPA (e.g., "monitor rows must populate `expected_*` fields") are documented in FOOTGUNS.

### 3.2 Enum additions

```java
public enum BotTaskType {
    VERIFY,             // one-shot; terminal on callback
    MONITOR_AUCTION,    // recurring, slpa.bot.monitor-auction-interval (default PT30M)
    MONITOR_ESCROW      // recurring, slpa.bot.monitor-escrow-interval (default PT15M)
}

public enum BotTaskStatus {
    PENDING,       // claimable (VERIFY) or due (MONITOR_* where next_run_at <= now)
    IN_PROGRESS,   // claimed; times out after slpa.bot-task.in-progress-timeout (PT20M)
    COMPLETED,     // VERIFY SUCCESS terminal; MONITOR_* never reaches this
    FAILED,        // VERIFY FAILURE / timeout / superseded
    CANCELLED      // MONITOR_* terminal — lifecycle hook cancelled
}
```

Four new `FraudFlagReason` values:

```
BOT_AUTH_BUYER_REVOKED
BOT_PRICE_DRIFT
BOT_OWNERSHIP_CHANGED
BOT_ACCESS_REVOKED
```

The existing CHECK constraint on `fraud_flags.reason` widens via Hibernate `ddl-auto: update` + `EnumCheckConstraintSync`.

### 3.3 Atomic claim endpoint

```java
// BotTaskController
@PostMapping("/claim")
public ResponseEntity<BotTaskResponse> claim(@Valid @RequestBody BotTaskClaimRequest body) {
    return service.claim(body.botUuid())
        .map(task -> ResponseEntity.ok(BotTaskResponse.from(task)))
        .orElseGet(() -> ResponseEntity.noContent().build());
}
```

```java
// BotTaskRepository — native query, SKIP LOCKED only exists at SQL level
@Query(value = """
    SELECT * FROM bot_tasks
    WHERE status = 'PENDING'
      AND (next_run_at IS NULL OR next_run_at <= :now)
    ORDER BY created_at ASC
    FOR UPDATE SKIP LOCKED
    LIMIT 1
    """, nativeQuery = true)
Optional<BotTask> claimNext(@Param("now") OffsetDateTime now);
```

`BotTaskService.claim(botUuid)` wraps it in `@Transactional`, stamps `status=IN_PROGRESS`, `assignedBotUuid`, and persists. Returns `Optional<BotTask>`. Two parallel claims on the same PENDING row will serialize via row locks, the first wins; `SKIP LOCKED` means a third claim sees the next row immediately instead of blocking. Covered by an integration test with two barrier-synced threads.

### 3.4 Split callback endpoints

```
PUT  /api/v1/bot/tasks/{id}/verify   body = BotTaskCompleteRequest   (existing shape, renamed path)
POST /api/v1/bot/tasks/{id}/monitor  body = BotMonitorResultRequest  (new)
```

The existing `PUT /api/v1/bot/tasks/{id}` path is kept for one release as a deprecated shim that forwards to `/verify`. Task 12 removes the shim.

### 3.5 Bearer-token auth

A new `BotSharedSecretAuthorizer` component does:

```java
MessageDigest.isEqual(
    expectedSecret.getBytes(StandardCharsets.UTF_8),
    presentedSecret.getBytes(StandardCharsets.UTF_8))
```

on the `Authorization: Bearer <secret>` header. Rejects with 401 on missing/mismatch. Pattern mirrors Epic 05's `EscrowSharedSecretAuthorizer`.

Wired in `SecurityConfig` with a dedicated filter chain ordered before the main chain, matching `/api/v1/bot/**`. The future `/api/v1/bot/health` (if ever added on the backend side — currently out of scope) would stay `permitAll`.

### 3.6 Startup guards

New `BotStartupValidator` (`@Component`, `@Profile("!test")`) fails fast on boot if:

- `slpa.bot.shared-secret` is blank or equals the dev placeholder literal `dev-bot-shared-secret`, OR
- `slpa.bot-task.primary-escrow-uuid` equals the dev placeholder `00000000-0000-0000-0000-000000000099`.

Mirrors `EscrowStartupValidator` and `SlStartupValidator`. Closes two deferred items (the bot-auth half of `Bot service authentication` and the bot half of `Primary escrow UUID + SLPA trusted-owner-keys production config`).

### 3.7 IN_PROGRESS timeout (closes a deferred item)

`BotTaskTimeoutJob` gains a second `@Scheduled` method that sweeps IN_PROGRESS rows older than `slpa.bot-task.in-progress-timeout` (default `PT20M`). Divergent behavior by task type:

- `VERIFY` IN_PROGRESS timeout → FAILED with reason `TIMEOUT (IN_PROGRESS)`; auction flipped to `VERIFICATION_FAILED` (same as the PENDING sweep).
- `MONITOR_*` IN_PROGRESS timeout → **re-armed**: `status = PENDING`, `nextRunAt = now + recurrenceIntervalSeconds`. A different worker (or the same one, next loop) will retry on the next interval.

### 3.8 Lifecycle hooks

New `BotMonitorLifecycleService` invoked by the triggering service inside the same transaction:

| Hook method                              | Call site                                         | Effect                                               |
|------------------------------------------|---------------------------------------------------|------------------------------------------------------|
| `onAuctionActivatedBot(auction)`         | `BotTaskService.complete()` SUCCESS path          | Insert `MONITOR_AUCTION` row, `next_run_at = now + 30m` |
| `onAuctionEnded(auction)`                | `AuctionEndTask.end()`                            | Cancel live `MONITOR_AUCTION` rows for this auction  |
| `onAuctionSuspended(auction)`            | `SuspensionService.suspend()`                     | Cancel live `MONITOR_AUCTION` rows                   |
| `onAuctionCancelled(auction)`            | `CancellationService.cancel()`                    | Cancel live `MONITOR_AUCTION` rows                   |
| `onEscrowCreatedBot(escrow)`             | `EscrowService.create()` when `auction.verificationTier == BOT` | Insert `MONITOR_ESCROW` row, `next_run_at = now + 15m` |
| `onEscrowTerminal(escrow)`               | `EscrowService.complete` / `expire` / `dispute` / `freezeForFraud` | Cancel live `MONITOR_ESCROW` rows                    |

Each method is a no-op if no matching row exists, so callers can invoke unconditionally.

Bulk-cancel repository methods include `lastUpdatedAt` in the SET clause because `@Modifying` bypasses `@UpdateTimestamp`:

```java
@Modifying
@Query("""
    UPDATE BotTask t
       SET t.status = 'CANCELLED',
           t.completedAt = :now,
           t.lastUpdatedAt = :now
     WHERE t.auction.id = :auctionId
       AND t.taskType IN :types
       AND t.status IN ('PENDING', 'IN_PROGRESS')
    """)
int cancelLiveByAuctionIdAndTypes(
    @Param("auctionId") Long auctionId,
    @Param("types") Collection<BotTaskType> types,
    @Param("now") OffsetDateTime now);

@Modifying
@Query("""
    UPDATE BotTask t
       SET t.status = 'CANCELLED',
           t.completedAt = :now,
           t.lastUpdatedAt = :now
     WHERE t.escrow.id = :escrowId
       AND t.taskType = 'MONITOR_ESCROW'
       AND t.status IN ('PENDING', 'IN_PROGRESS')
    """)
int cancelLiveByEscrowId(
    @Param("escrowId") Long escrowId,
    @Param("now") OffsetDateTime now);
```

### 3.9 New DTOs

```java
public record BotTaskClaimRequest(@NotNull UUID botUuid) {}

public record BotMonitorResultRequest(
    @NotNull MonitorOutcome outcome,
    UUID observedOwner,
    UUID observedAuthBuyer,
    Long observedSalePrice,
    @Size(max = 500) String note) {}

public enum MonitorOutcome {
    ALL_GOOD,
    AUTH_BUYER_CHANGED,
    PRICE_MISMATCH,
    OWNER_CHANGED,
    ACCESS_DENIED,
    TRANSFER_COMPLETE,
    TRANSFER_READY,
    STILL_WAITING
}
```

`MonitorOutcome` is deliberately observation-only. `FRAUD_DETECTED` is **not** a value — fraud interpretation lives in the backend dispatcher (§6).

---

## 4. Bot Service (C# / .NET 8)

### 4.1 Project layout

```
bot/
├── Slpa.Bot.sln
├── Dockerfile
├── .env.example
├── README.md
├── src/
│   └── Slpa.Bot/
│       ├── Slpa.Bot.csproj                   .NET 8, LibreMetaverse 1.9.x
│       ├── Program.cs                         Host bootstrap + DI
│       ├── appsettings.json                   Defaults; env overrides
│       ├── Options/
│       │   ├── BotOptions.cs                  Username, Password, BotUuid, StartLocation
│       │   ├── BackendOptions.cs              BaseUrl, SharedSecret, PrimaryEscrowUuid
│       │   └── RateLimitOptions.cs            TeleportsPerMinute (default 6)
│       ├── Sl/
│       │   ├── IBotSession.cs                 Login, TeleportAsync, ReadParcelAsync, Dispose
│       │   ├── LibreMetaverseBotSession.cs    Real impl wrapping GridClient
│       │   ├── TeleportRateLimiter.cs         Token bucket, 6/min
│       │   ├── ParcelReader.cs                ParcelPropertiesRequest + await event
│       │   └── SessionState.cs                enum: STARTING/ONLINE/RECONNECTING/ERROR
│       ├── Backend/
│       │   ├── IBackendClient.cs              ClaimAsync, CompleteVerifyAsync, PostMonitorAsync
│       │   ├── HttpBackendClient.cs           Typed HttpClient w/ bearer + retry policy
│       │   └── Models/                        Request/response records mirroring backend DTOs
│       ├── Tasks/
│       │   ├── TaskLoop.cs                    BackgroundService — main loop
│       │   ├── VerifyHandler.cs               VERIFY
│       │   └── MonitorHandler.cs              MONITOR_AUCTION + MONITOR_ESCROW
│       └── Health/
│           └── HealthEndpoint.cs              GET /health — SessionState
└── tests/
    └── Slpa.Bot.Tests/
        ├── Slpa.Bot.Tests.csproj              xUnit + FluentAssertions + WireMock.Net
        ├── TeleportRateLimiterTests.cs
        ├── VerifyHandlerTests.cs              fake IBotSession, fake IBackendClient
        ├── MonitorHandlerTests.cs
        └── TaskLoopTests.cs
```

### 4.2 Login / session management

`LibreMetaverseBotSession : IBotSession` wraps `GridClient`. On `LoginAsync`:

- `Settings.LOGIN_SERVER = "https://login.agni.lindenlab.com/cgi-bin/login.cgi"` (main grid).
- Disables asset-heavy subsystems: no textures, no mesh downloads, no sounds, no inventory fetch, no avatar rendering — protocol layer only.
- Subscribes `Network.LoginProgress` to drive `SessionState` transitions (`STARTING` → `ONLINE` on `LoginStatus.Success`; `ERROR` on `LoginStatus.Failed`).
- Subscribes `Network.Disconnected`. On any disconnect, transitions to `RECONNECTING` and schedules a reconnect with exponential backoff (1s, 2s, 4s, 8s, … capped at 60s).

`SessionState` is observable; `TaskLoop` and `HealthEndpoint` both read it.

### 4.3 Teleport and parcel read

`TeleportRateLimiter` is a token bucket with `TeleportsPerMinute = 6` (configurable via `slpa.bot.teleports-per-minute`). Bucket refills at `1 token / 10s`. `TeleportAsync` awaits bucket availability via a `SemaphoreSlim` + async timer before issuing the teleport. SL's 6/min is a hard grid-side cap — the limiter just makes sure we never ask faster than that.

`TeleportAsync(regionName, localPos)` calls `Self.Teleport(regionName, localPos)`, awaits the race between `TeleportFinished` and `TeleportFailed` events with a 30 s timeout, and returns a typed result:

```csharp
public record TeleportResult(bool Success, TeleportFailureKind? Failure);
public enum TeleportFailureKind { AccessDenied, RegionNotFound, Timeout, Other }
```

`ReadParcelAsync(x, y)` issues `Parcels.RequestParcelProperties(sim, x, y, sequenceId)` and awaits the `Parcels.ParcelProperties` event matching that sequence ID with a 10 s timeout. Returns `ParcelSnapshot`:

```csharp
public record ParcelSnapshot(
    UUID OwnerID, UUID GroupID, bool IsGroupOwned,
    UUID AuthBuyerID, long SalePrice,
    string Name, string Description,
    int AreaSqm, int MaxPrims, int Category,
    UUID SnapshotID, uint Flags);
```

### 4.4 TaskLoop

`TaskLoop : BackgroundService` is the main driver:

```csharp
protected override async Task ExecuteAsync(CancellationToken ct)
{
    while (!ct.IsCancellationRequested)
    {
        if (session.State != SessionState.Online)
        {
            await Task.Delay(TimeSpan.FromSeconds(5), ct);
            continue;
        }
        var task = await backend.ClaimAsync(session.BotUuid, ct);
        if (task is null)
        {
            await Task.Delay(TimeSpan.FromSeconds(15), ct);      // empty-queue backoff
            continue;
        }
        try
        {
            var handler = handlerFor(task.TaskType);
            await handler.HandleAsync(task, ct);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Task {id} handler crashed", task.Id);
            // Do NOT callback. Backend's IN_PROGRESS timeout sweep cleans up.
        }
    }
}
```

Dual backoff: 5 s when the session isn't online, 15 s when the queue is empty. Handler crashes deliberately do not callback — partial state would force the backend to guess.

### 4.5 VerifyHandler

1. `TeleportAsync(task.RegionName, task.PositionX/Y/Z)`. On failure, `POST /verify` with `result=FAILURE`, `failureReason=<FailureKind>`. Done.
2. `ReadParcelAsync(task.PositionX, task.PositionY)`. On timeout, `POST /verify` with `FAILURE, failureReason=PARCEL_READ_TIMEOUT`. Done.
3. Build `BotTaskCompleteRequest` with the parcel snapshot. Set `result=SUCCESS`. `POST /verify`. Done.

The worker does **not** validate `authBuyerId == primary escrow UUID` or `salePrice == sentinel` itself. The backend validates. The worker only reports what it observed.

### 4.6 MonitorHandler

1. Teleport + read (same as verify). On teleport failure, `POST /monitor` with `outcome=ACCESS_DENIED`, observations null. Backend re-arms `next_run_at`.
2. On parcel-read success, classify the outcome mechanically against task payload expected values:

```text
MONITOR_AUCTION:
  if observed.OwnerID != task.ExpectedOwnerUuid            → OWNER_CHANGED
  else if observed.AuthBuyerID != task.ExpectedAuthBuyer   → AUTH_BUYER_CHANGED
  else if observed.SalePrice != task.ExpectedSalePrice     → PRICE_MISMATCH
  else                                                       → ALL_GOOD

MONITOR_ESCROW:
  if observed.OwnerID == task.ExpectedWinnerUuid                 → TRANSFER_COMPLETE
  else if observed.OwnerID != task.ExpectedSellerUuid            → OWNER_CHANGED
  else if observed.AuthBuyerID == task.ExpectedWinnerUuid
       && observed.SalePrice <= task.ExpectedMaxSalePriceLindens → TRANSFER_READY
  else                                                             → STILL_WAITING
```

The `TRANSFER_READY` sale-price threshold uses `<=` against `ExpectedMaxSalePriceLindens` (default 1) to tolerate sellers who set L$1 by habit. `TRANSFER_COMPLETE` is ownership-only.

3. `POST /monitor` with the `BotMonitorResultRequest`. Backend re-arms or cancels the row (see §6).

The classifier is the worker's only logic. It is mechanical, not interpretive — it never decides "this is fraud"; it just reports what it saw compared to what it was told to expect.

---

## 5. Data Model Details

### 5.1 Monitor row lifecycle

```
claim(MONITOR_*)            PENDING → IN_PROGRESS, assignedBotUuid stamped
monitor callback (re-arm)   IN_PROGRESS → PENDING, nextRunAt = now + interval,
                            lastCheckAt = now, resultData stamped with observation
timeout sweep (MONITOR_*)   IN_PROGRESS → PENDING, nextRunAt = now + interval
lifecycle cancel            any non-terminal → CANCELLED
```

`VERIFY` keeps its existing transitions: `PENDING → IN_PROGRESS → COMPLETED | FAILED`, with FAILED + reason `TIMEOUT` on sweep.

### 5.2 Denormalized task payload

The backend denormalizes expected values at task-creation time so the worker never has to look anything up. `BotMonitorLifecycleService.onAuctionActivatedBot`:

```java
BotTask.builder()
    .taskType(BotTaskType.MONITOR_AUCTION)
    .status(BotTaskStatus.PENDING)
    .auction(auction)
    .parcelUuid(parcel.getSlParcelUuid())
    .regionName(parcel.getRegionName())
    .positionX(parcel.getPositionX())
    .positionY(parcel.getPositionY())
    .positionZ(parcel.getPositionZ())
    .expectedOwnerUuid(parcel.getOwnerUuid())
    .expectedAuthBuyerUuid(primaryEscrowUuid)
    .expectedSalePriceLindens(sentinelPrice)
    .nextRunAt(now.plus(monitorAuctionInterval))
    .recurrenceIntervalSeconds((int) monitorAuctionInterval.getSeconds())
    .build();
```

`BotMonitorLifecycleService.onEscrowCreatedBot`:

```java
BotTask.builder()
    .taskType(BotTaskType.MONITOR_ESCROW)
    .status(BotTaskStatus.PENDING)
    .auction(escrow.getAuction())
    .escrow(escrow)
    .parcelUuid(parcel.getSlParcelUuid())
    .regionName(parcel.getRegionName())
    .positionX(parcel.getPositionX())
    .positionY(parcel.getPositionY())
    .positionZ(parcel.getPositionZ())
    .expectedSellerUuid(parcel.getOwnerUuid())
    .expectedWinnerUuid(escrow.getWinnerSlUuid())
    .expectedMaxSalePriceLindens(1L)
    .nextRunAt(now.plus(monitorEscrowInterval))
    .recurrenceIntervalSeconds((int) monitorEscrowInterval.getSeconds())
    .build();
```

### 5.3 Soft invariants (documented in FOOTGUNS)

- `MONITOR_AUCTION` rows must populate `expected_owner_uuid`, `expected_auth_buyer_uuid`, `expected_sale_price_lindens`.
- `MONITOR_ESCROW` rows must populate `expected_seller_uuid`, `expected_winner_uuid`, `expected_max_sale_price_lindens`, `escrow_id`.
- `VERIFY` rows must **not** populate `next_run_at` or `recurrence_interval_seconds`.

These aren't enforced by CHECK constraints because JPA doesn't express them cleanly (they'd require multi-column conditional CHECKs). Enforcement is runtime at the builder/service layer and in the dispatch logic, with defensive `requireNonNull`-style guards that throw on violation.

---

## 6. Monitor Callback Dispatch (Backend Interpretation)

`POST /api/v1/bot/tasks/{id}/monitor` delegates to `BotTaskService.recordMonitorResult(taskId, request)`, which calls `BotMonitorDispatcher.dispatch(task, request)` and applies the result:

```java
public record DispatchOutcome(boolean shouldReArm, String logAction) {}

// BotTaskService.recordMonitorResult (pseudocode)
var task = botTaskRepo.findById(taskId).orElseThrow();
assertInProgressMonitor(task);
task.setLastCheckAt(now);
task.setResultData(merge(task.getResultData(), observation));
DispatchOutcome outcome = dispatcher.dispatch(task, request);
log.info("bot monitor {} auction={} outcome={} action={}",
         taskId, task.getAuction().getId(), request.outcome(), outcome.logAction());
if (outcome.shouldReArm()) {
    task.setStatus(BotTaskStatus.PENDING);
    task.setNextRunAt(now.plusSeconds(task.getRecurrenceIntervalSeconds()));
}
// else: the dispatcher triggered a lifecycle hook that already cancelled the row.
botTaskRepo.save(task);
```

### 6.1 MONITOR_AUCTION dispatch

| Outcome               | Backend action                                                                 | shouldReArm |
|-----------------------|--------------------------------------------------------------------------------|-------------|
| `ALL_GOOD`            | Stamp `lastCheckAt`, `resultData`. No state changes.                           | `true`      |
| `AUTH_BUYER_CHANGED`  | `SuspensionService.suspend(auction, BOT_AUTH_BUYER_REVOKED, evidence=observation)` | `false`     |
| `PRICE_MISMATCH`      | `SuspensionService.suspend(auction, BOT_PRICE_DRIFT, evidence=observation)`    | `false`     |
| `OWNER_CHANGED`       | `SuspensionService.suspend(auction, BOT_OWNERSHIP_CHANGED, evidence=observation)` — a sold parcel cannot be re-sold. | `false`     |
| `ACCESS_DENIED`       | Stamp `resultData.accessDeniedStreak++`. If streak ≥ `slpa.bot.access-denied-streak-threshold` (default 3), `SuspensionService.suspend(auction, BOT_ACCESS_REVOKED)`. | `true` unless threshold hit |
| `TRANSFER_COMPLETE` / `TRANSFER_READY` / `STILL_WAITING` | Log warn ("escrow outcome on auction task"). No-op.     | `true`      |

### 6.2 MONITOR_ESCROW dispatch

| Outcome               | Backend action                                                                 | shouldReArm |
|-----------------------|--------------------------------------------------------------------------------|-------------|
| `STILL_WAITING`       | Stamp `lastCheckAt`.                                                           | `true`      |
| `TRANSFER_READY`      | Stamp `lastCheckAt` + `resultData.transferReady=true`. First-time transition: `EscrowBroadcastPublisher.publish(escrow, TRANSFER_READY_OBSERVED)`. | `true`      |
| `TRANSFER_COMPLETE`   | `EscrowService.confirmTransfer(escrow)` (idempotent, see sub-spec 1).          | `false`     |
| `OWNER_CHANGED`       | If `observedOwner == escrow.winnerSlUuid` → treat as `TRANSFER_COMPLETE`. Otherwise `EscrowService.freezeForFraud(escrow, BOT_OWNERSHIP_CHANGED, evidence=observation)`. | `false`     |
| `AUTH_BUYER_CHANGED`  | If `observedAuthBuyer == escrow.winnerSlUuid` → treat as `TRANSFER_READY`. Otherwise log + stamp; seller may be re-configuring, not fraud. | `true`      |
| `PRICE_MISMATCH`      | Log info (seller adjusting).                                                   | `true`      |
| `ACCESS_DENIED`       | Streak logic. Threshold action: `EscrowService.markReviewRequired(escrow)`. Denial during escrow does not prove fraud. | `true` unless threshold hit |
| `ALL_GOOD`            | Log warn ("auction outcome on escrow task"). No-op.                            | `true`      |

### 6.3 New escrow service method

```java
/** Flag an escrow for admin review without changing lifecycle state. */
public void markReviewRequired(Escrow escrow) { ... }
```

Sets `reviewRequired = true` on the entity and publishes `EscrowEventType.REVIEW_REQUIRED`. Idempotent. Admin UI treatment is Epic 10 — the backend just records the flag.

### 6.4 Streak tracking

`accessDeniedStreak` lives in `result_data` JSONB (not a column — observability-only, resets on any non-denial outcome). The dispatcher increments on `ACCESS_DENIED`, resets to 0 on any other outcome. Threshold is `slpa.bot.access-denied-streak-threshold` (default 3).

### 6.5 Broadcasts

No new broadcast envelope types for monitor results. Downstream service calls (`suspend`, `confirmTransfer`, `freezeForFraud`, `markReviewRequired`) already publish their own envelopes via the existing `EscrowBroadcastPublisher` / auction broadcaster. `TRANSFER_READY_OBSERVED` is the one new envelope (on the existing escrow topic), fired only on first-time transition.

---

## 7. Rate Limiting, Backoff, and Failure Handling

### 7.1 Worker-side teleport token bucket

`TeleportRateLimiter` holds 6 tokens, refills at 1 token / 10 s. `TeleportAsync` awaits a token via `SemaphoreSlim`. If the bucket is empty, the worker just waits — SL's cap is grid-side; there is no way around it.

### 7.2 Worker-side backend-call backoff

`HttpBackendClient` uses hand-rolled exponential retry (no Polly dependency):

| Response                                | Behavior                                        |
|-----------------------------------------|-------------------------------------------------|
| HTTP 401                                | Log error + crash. Operator must fix the secret.|
| HTTP 4xx (other)                        | Log + give up on this task. No callback repeat. |
| HTTP 5xx / connection refused / timeout | Retry: 1s, 2s, 4s, 8s, 15s (max 5 attempts), then log + give up; backend sweep cleans up. |
| Claim endpoint returns 204              | Normal empty-queue path. Sleep 15s, loop.       |

### 7.3 SL disconnect mid-task

If `Network.Disconnected` fires during a handler, the handler's `await` throws `SessionLostException`. The task loop catches it and:

1. Logs the failure.
2. Does **not** callback. The task stays IN_PROGRESS until the backend's sweep reclaims it.
3. Sets `SessionState = RECONNECTING`; the login loop takes over.

Deliberately no "partial-result callback" — partial state would push interpretation onto the backend, which contradicts the observation-only split.

### 7.4 Backend-side error responses

`/claim`:

| Request shape                    | Response                                       |
|----------------------------------|------------------------------------------------|
| Missing/invalid bearer           | 401 (handled by `BotSharedSecretAuthorizer`).  |
| Malformed body (missing botUuid) | 400 with standard `ApiError` problem shape.   |
| Valid, queue empty               | 204 No Content.                                |
| Valid, claim succeeded           | 200 + `BotTaskResponse`.                       |

`/verify` and `/monitor`:

| Condition                                         | Response                                          |
|---------------------------------------------------|---------------------------------------------------|
| Task ID not found                                 | 404 `ApiError(code=BOT_TASK_NOT_FOUND)`.          |
| Task not in `IN_PROGRESS`                         | 409 `ApiError(code=BOT_TASK_NOT_CLAIMED)`.        |
| `/verify` on a `MONITOR_*` task or vice versa     | 409 `ApiError(code=BOT_TASK_WRONG_TYPE)`.         |
| `/verify` SUCCESS with wrong authBuyer/salePrice  | 400. Task flips to FAILED server-side.            |
| `/verify` on auction not in `VERIFICATION_PENDING`| 409 (existing behavior).                          |
| `/monitor` on terminal escrow                     | 409 `ApiError(code=BOT_ESCROW_TERMINAL)`.         |

### 7.5 Restart mid-IN_PROGRESS

If a worker crashes between "teleport succeeded" and "parcel read succeeded," the bot is in a different region than its DB-stamped home. This is fine: every task starts with a teleport, so there's no starting-region assumption. The 6/min rate limit still applies to the next teleport.

---

## 8. Configuration, Docker, Environments

### 8.1 Bot env mapping

ASP.NET Core standard `__` section delimiter.

| Env var                            | `appsettings.json` path              | Notes                                       |
|------------------------------------|--------------------------------------|---------------------------------------------|
| `SLPA_BOT_USERNAME`                | `Bot:Username`                       | `"SLPABot1 Resident"` (first.last form)     |
| `SLPA_BOT_PASSWORD`                | `Bot:Password`                       | Secret. Never committed.                    |
| `SLPA_BOT_UUID`                    | `Bot:BotUuid`                        | The account's UUID.                         |
| `SLPA_BOT_START_LOCATION`          | `Bot:StartLocation`                  | Default `"last"`.                           |
| `SLPA_BACKEND_BASE_URL`            | `Backend:BaseUrl`                    | e.g. `http://backend:8080`.                 |
| `SLPA_BOT_SHARED_SECRET`           | `Backend:SharedSecret`               | Must match `slpa.bot.shared-secret`.        |
| `SLPA_PRIMARY_ESCROW_UUID`         | `Backend:PrimaryEscrowUuid`          | Sanity ref; backend is authoritative.       |
| `SLPA_TELEPORTS_PER_MINUTE`        | `RateLimit:TeleportsPerMinute`       | Default `6`.                                |
| `SLPA_BOT_LOG_LEVEL`               | `Logging:LogLevel:Default`           | Default `Information`.                      |

### 8.2 Dockerfile

```dockerfile
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY src/Slpa.Bot/Slpa.Bot.csproj src/Slpa.Bot/
RUN dotnet restore src/Slpa.Bot/Slpa.Bot.csproj
COPY . .
RUN dotnet publish src/Slpa.Bot/Slpa.Bot.csproj -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:8.0
WORKDIR /app
COPY --from=build /app/publish .
ENV ASPNETCORE_HTTP_PORTS=8081
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O- http://localhost:8081/health || exit 1
ENTRYPOINT ["dotnet", "Slpa.Bot.dll"]
```

`ENV ASPNETCORE_HTTP_PORTS=8081` is mandatory — .NET 8 defaults to 8080 and the healthcheck would silently fail. FOOTGUNS entry documents the default-port gotcha.

### 8.3 Docker Compose additions

One service block per bot. Dev ships `bot-1` only; prod adds up to `bot-5`.

```yaml
services:
  # ... existing backend / frontend / postgres / redis

  bot-1:
    build:
      context: ./bot
      dockerfile: Dockerfile
    env_file:
      - ./bot/.env.bot-1
    environment:
      SLPA_BACKEND_BASE_URL: http://backend:8080
      SLPA_BOT_START_LOCATION: last
      SLPA_TELEPORTS_PER_MINUTE: "6"
    depends_on:
      backend:
        condition: service_healthy
    restart: unless-stopped
```

Adding `bot-2` … `bot-5` is a copy-paste of the block with a different `env_file`. Code is identical across all instances — only credentials vary.

### 8.4 Backend config additions (`application.yml`)

```yaml
slpa:
  bot:
    shared-secret: ${SLPA_BOT_SHARED_SECRET:}               # blank default; BotStartupValidator fails fast
    monitor-auction-interval: PT30M
    monitor-escrow-interval: PT15M
    access-denied-streak-threshold: 3
  bot-task:
    primary-escrow-uuid: ${SLPA_PRIMARY_ESCROW_UUID:00000000-0000-0000-0000-000000000099}
    sentinel-price-lindens: 999999999
    in-progress-timeout: PT20M                                # new — closes deferred item
    timeout: PT48H                                             # existing PENDING timeout
```

`application-dev.yml` sets `slpa.bot.shared-secret: dev-bot-shared-secret` and keeps the placeholder primary-escrow-uuid — both trip `BotStartupValidator` outside the `dev` profile.

### 8.5 Environment matrix

| Env        | Bot workers         | SL account(s)        | Notes                                  |
|------------|---------------------|----------------------|----------------------------------------|
| Local dev  | 1 (`bot-1`)         | `SLPABot1`           | Real SL login per project decision.    |
| CI         | None                | N/A                  | C# tests run via `dotnet test` only.   |
| Staging    | 1-2                 | Dedicated staging bots (not prod) | Separate SL accounts.        |
| Prod       | Up to 5             | `SLPABot1..5`        | Scale by adding compose blocks.        |

### 8.6 Startup ordering

`depends_on: backend: service_healthy` means Compose waits for the backend's `/actuator/health` green signal before starting bots. Bots retry their first claim call with the same exponential backoff if the backend is slow to come up.

---

## 9. Testing Strategy

### 9.1 Backend (Java)

| Layer        | Coverage                                                                                                                            |
|--------------|-------------------------------------------------------------------------------------------------------------------------------------|
| Unit         | `BotMonitorDispatcher` dispatch table per `(taskType, outcome)`. `BotMonitorLifecycleService` hook methods (create + cancel). `BotTaskService.claim()` empty + success paths. `BotTaskTimeoutJob` IN_PROGRESS branches (VERIFY-fail + MONITOR-rearm). `BotSharedSecretAuthorizer` constant-time compare. |
| Slice        | `BotTaskController` `@WebMvcTest`: `POST /claim` (200 + 204 + 401 + 400), `PUT /verify` (200 + 404 + 409), `POST /monitor` (200 + 404 + 409). |
| Integration  | End-to-end hook test: `BotTaskService.complete()` SUCCESS creates MONITOR_AUCTION row; `AuctionEndTask` cancels it. Escrow `TRANSFER_COMPLETE` idempotency. SKIP LOCKED race with two barrier-synced threads. `@SpringBootTest` + Testcontainers Postgres. |

Integration test fixtures extend the existing `AuctionFactory` / `EscrowFactory` with `botVerifiedActiveAuction()` and `botTierFundedEscrow()` helpers.

### 9.2 Bot worker (C#)

| Layer                     | Coverage                                                                                           |
|---------------------------|----------------------------------------------------------------------------------------------------|
| Unit                      | `TeleportRateLimiter` bucket math. `VerifyHandler` happy + TIMEOUT + ACCESS_DENIED. `MonitorHandler` classifier matrix (8 outcomes × 2 task types). `HttpBackendClient` 401-fail + 5xx-retry + 400-nofail paths. |
| Behavioural (fast)        | `TaskLoop` with scripted fake `IBackendClient` + fake `IBotSession`: claim→handle→callback cadence, handler crash → no callback, session-offline → 5s backoff, empty-queue → 15s backoff. |

No LibreMetaverse in tests. The `IBotSession` interface is the test boundary — tests fake `IBotSession` directly, never `GridClient`. WireMock.Net is used to fake the backend.

### 9.3 CI wiring

A minimal GitHub Actions job runs `dotnet test bot/` on any PR touching `bot/**`. The existing Java pipeline is untouched. No bot container runs in CI — manual smoke testing with real `SLPABot1` credentials is documented in `bot/README.md` and executed before PR merge.

### 9.4 Done bar

Every task ships with passing tests at every layer it touches. Final code-review agent checks the matrix before PR merge.

---

## 10. Task Decomposition

Single PR on `task/06-bot-service`, 12 tasks. Linear execution.

| #   | Task                                               | Summary                                                                                                                  |
|-----|----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| 1   | Backend — schema + enum extensions                 | Add nullable columns to `bot_tasks`, `MONITOR_AUCTION`/`MONITOR_ESCROW`/`CANCELLED` enum values, 4 new fraud-flag reasons. Hibernate DDL; `EnumCheckConstraintSync`. Round-trip tests. |
| 2   | Backend — atomic claim endpoint + SKIP LOCKED      | `POST /api/v1/bot/tasks/claim`, native query + `BotTaskService.claim()`. Two-thread race integration test. Demote `GET /pending` to debug-only in javadoc. |
| 3   | Backend — bearer auth + startup validator          | `BotSharedSecretAuthorizer`, `BotStartupValidator`. `SecurityConfig` filter chain on `/api/v1/bot/**`. Constant-time compare. 401 slice tests. |
| 4   | Backend — VERIFY callback split + IN_PROGRESS sweep | Rename `PUT /api/v1/bot/tasks/{id}` → `/verify` (keep old path as deprecated 308). `BotTaskTimeoutJob` IN_PROGRESS sweep with divergent VERIFY-fail / MONITOR-rearm behavior. |
| 5   | Backend — MONITOR callback + dispatcher            | `POST /api/v1/bot/tasks/{id}/monitor`, `BotMonitorDispatcher`, `DispatchOutcome`, `recordMonitorResult`. Per-outcome integration tests. Streak counter in `result_data`. `EscrowService.markReviewRequired`. |
| 6   | Backend — lifecycle hooks create/cancel monitors   | `BotMonitorLifecycleService` + integration with `BotTaskService.complete`, `AuctionEndTask`, `SuspensionService`, `CancellationService`, `EscrowService.create/complete/expire/dispute/freezeForFraud`. `cancelLiveBy*` with `lastUpdatedAt` in SET. End-to-end Method C flow test. |
| 7   | C# — project scaffold + login loop                 | New `.NET 8` solution. `Program.cs` DI. `LibreMetaverseBotSession` with login + keepalive + reconnect backoff. `GET /health`. Unit tests on state machine with a fake `IBotSession` (never `GridClient`). `bot/README.md` first-time setup docs. |
| 8   | C# — teleport + ParcelProperties read              | `TeleportRateLimiter`, `TeleportAsync` → `TeleportResult`, `ReadParcelAsync` → `ParcelSnapshot`, `SessionLostException`. Event-racing against timeout. Unit tests. |
| 9   | C# — backend client + auth                         | `HttpBackendClient` with bearer + hand-rolled retry policy (1s/2s/4s/8s/15s, max 5). Hard-fail on 401. `ClaimAsync`/`CompleteVerifyAsync`/`PostMonitorAsync`. WireMock.Net unit tests covering 204/200/401/500-retry/400-no-retry. |
| 10  | C# — VerifyHandler + TaskLoop + MonitorHandler     | `BackgroundService` loop with dual backoff. `VerifyHandler` (observation-only). `MonitorHandler` with mechanical classifier using `expectedMaxSalePriceLindens`. Unit tests: happy paths + all failure modes + handler-crash → no-callback. |
| 11  | Ops — Dockerfile + compose + CI job                | Multi-stage Dockerfile with `ASPNETCORE_HTTP_PORTS=8081`. `bot-1` service block in compose + `.env.example`. Minimal GitHub Actions job running `dotnet test` on `bot/**` changes. README bot-service section. |
| 12  | Docs — README / DEFERRED_WORK / FOOTGUNS + cleanup | Root README sweep. Close deferred: `Bot service authentication`, `IN_PROGRESS bot task timeout`, bot half of `Primary escrow UUID startup guard`. Open new deferred entries. FOOTGUNS for .NET 8 default port, SKIP LOCKED JPA pattern, bulk `@Modifying` + `lastUpdatedAt`, Hibernate enum CHECK widening. Remove deprecated `PUT /api/v1/bot/tasks/{id}` shim. |

### 10.1 Dependency order

```
1 → 2 → 3 → 4 → 5 → 6  (backend chain)
                    ↓
                    7 → 8 → 9 → 10  (C# chain)
                                   ↓
                                   11 → 12  (ops + docs)
```

Tasks 2/3 could parallelize with 4/5/6, but the plan assumes linear for simpler review.

### 10.2 Deferred items closed

- `Bot service authentication` (Task 3).
- `IN_PROGRESS bot task timeout` (Task 4).
- Bot-auth half of `Primary escrow UUID + SLPA trusted-owner-keys production config` (Task 3). The SL trusted-owner-keys half stays deferred — that's a Phase 11 LSL scope item.

### 10.3 Deferred items opened

- Admin pool health dashboard (Epic 10).
- Notifications for bot-detected fraud (Epic 09).
- Per-worker auth tokens + `bot_workers` table (indefinite — trigger is admin auditing need).
- HMAC-SHA256 per-request auth (Phase 2 hardening).
- Parcel layout map generation — DESIGN.md §5.5 already flagged as needing a design pass.

---

## 11. Open Questions

None at spec-approval time. Design decisions settled during brainstorming:

- Scope: one spec, 12 tasks (not split).
- Auth: static bearer, mirrors escrow terminal pattern.
- Claim: `SELECT ... FOR UPDATE SKIP LOCKED` atomic endpoint.
- Monitor model: one long-lived row with `next_run_at` re-arm.
- Monitor overlap with World API: both run, transitions idempotent.
- Process model: one worker per container, code reads creds from env.
- Mock mode: none — real SL creds in all environments.
- Monitor creation: atomic with lifecycle transitions.
- `MonitorOutcome`: observation-only (no `FRAUD_DETECTED`).
- `TRANSFER_READY` threshold: `observed.SalePrice <= expectedMaxSalePriceLindens` (default 1).

---

## 12. Success Criteria

- Every Method C verification that would have failed for lack of a worker now succeeds (or fails cleanly with a specific reason).
- BOT-tier active auctions have monitoring rows created on activation and cancelled on termination, visible in `bot_tasks`.
- BOT-tier escrows have monitoring rows created on `EscrowService.create` and cancelled on terminal transitions.
- Backend rejects calls to `/api/v1/bot/**` without a valid bearer token.
- A worker crash leaves no orphaned IN_PROGRESS rows after the sweep interval.
- A deliberate AuthBuyerID revoke on an active BOT-tier auction in SL triggers a `SUSPENDED` transition within 30 min.
- A successful ownership transfer on a BOT-tier escrow triggers `confirmTransfer` within 15 min.
- All backend tests pass, including the two-thread SKIP LOCKED race test.
- `dotnet test bot/` passes in CI on every PR touching `bot/**`.
- `docker compose up` brings the full stack including `bot-1` to a healthy state with real SL credentials.
