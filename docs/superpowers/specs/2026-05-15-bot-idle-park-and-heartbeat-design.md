# Bot idle-park + heartbeat — design

Date: 2026-05-15
Status: Approved (brainstorming) — pending implementation plan
Scope: bot-side only (`bot/`). No backend, Flyway, or frontend code changes.

## Summary

Two bounded, bot-side sub-features, tied into one spec because both touch
`IBotSession`, `TaskLoop`, and the bot options pattern:

- **Part A — Idle-park.** When a bot is idle (no task to service) it must be
  somewhere sensible instead of wherever its last task left it. When idle and
  outside a configurable rectangle in a configurable region, it teleports to a
  random point inside that rectangle.
- **Part B — Bot heartbeat.** The admin Bot-pool panel shows `0/0` /
  "No bots registered yet" even while bots are logged in and claiming tasks,
  because the bot never calls the (already-complete) backend heartbeat
  endpoint. Implement the missing heartbeat sender.

### Hard invariant (non-negotiable)

**The heartbeat is observation-only and must never influence idle detection,
the park decision, or `ParkCooldown`.** `IdleParker` has zero dependency on
`BotActivityState` or `HeartbeatLoop`. "Idle" is defined exclusively as
"`TaskLoop.ClaimAsync` returned `null`". A future change must not wire any
heartbeat/activity signal back into the park decision. The heartbeat does not
count against idle time.

---

## Part A — Idle-park

### Problem

Bots log in with `StartLocation = "last"` and, after servicing
verify/monitor tasks, are left on whatever parcel the last task targeted.
There is no defined "home" for an idle bot.

### Trigger & control flow

- **Idle = `ClaimAsync` returned `null`** in `TaskLoop.RunAsync`. While any
  task is in flight, `TaskLoop` is inside `DispatchAsync` and never reaches
  this branch, so a park can never interrupt a verify/monitor. After a task
  finishes, the loop re-claims; empty → park. This is precisely "if not
  currently visiting another parcel/region".
- **Stateless rectangle check, no latch.** Each empty-claim cycle: read the
  bot's current region+position (a local, cached agent value — no network
  round-trip), test it against the rectangle. Inside → do nothing (zero
  teleports, zero rate-limit cost in steady state). Outside → force-teleport
  to a random point inside. Rectangle membership *is* the idempotency; there
  is no "did I already park" state to coordinate.
- **`IdleParker.ParkIfNeededAsync(ct)`** is invoked from the `task is null`
  branch of `TaskLoop.RunAsync`, **before** `SafeDelayAsync(EmptyQueueBackoff)`.
- **Never kills the loop.** `IdleParker` swallows and logs non-cancellation
  exceptions (incl. `SessionLostException`), sets the cooldown, and returns
  normally. `OperationCanceledException` propagates for clean shutdown. The
  loop then re-evaluates session state at the top as it does today.

### Cooldown semantics (one configurable knob)

`ParkCooldownSeconds` (default **180 / 3 minutes**) gates park **teleport
attempts**, not the cheap membership check. It serves two purposes with one
knob: the failed-park backoff, and a general throttle so a future
long-running bot activity is not repeatedly interrupted by re-park attempts.

- Steady-state (parked + idle): in-rectangle check every ~15s costs nothing;
  cooldown never engages.
- Once a park *move* is issued **or fails**, no further park teleport fires
  until `ParkCooldown` elapses.

### `ParkIfNeededAsync` flow

1. Not enabled / blank region → return (warn once).
2. `now < _nextParkUtc` (in cooldown) → return.
3. `IBotSession.CurrentLocation` is `null` → return (retry next cycle; no
   cooldown burned).
4. In the configured region **and** inside `[minX,maxX] x [minY,maxY]` →
   return (no teleport, no cooldown — nothing happened).
5. Otherwise → random point in the rectangle;
   `TeleportAsync(Region, x, y, Z, forceMove: true, ct)`; set
   `_nextParkUtc = now + ParkCooldown` regardless of success/failure; log the
   outcome.
6. Catch non-cancellation exceptions → log, set cooldown, return.

### Configuration — `IdleParkOptions` (section `IdlePark`)

Bound in `Program.cs` alongside `BotOptions`/`RateLimitOptions`.
`appsettings.json` ships the Hadron rectangle as defaults (enabled out of the
box). Every field is independently env-overridable via the ASP.NET `__`
separator, like `Bot__*` / `RateLimit__*`.

| Field | Type | Default | Env override | Notes |
|---|---|---|---|---|
| `Enabled` | bool | `true` | `IdlePark__Enabled` | Master switch; `false` disables without losing coords |
| `Region` | string | `"Hadron"` | `IdlePark__Region` | Blank while enabled → warn + treat as disabled |
| `Corner1X` | double | `44` | `IdlePark__Corner1X` | First SL-map corner |
| `Corner1Y` | double | `73` | `IdlePark__Corner1Y` | |
| `Corner2X` | double | `30` | `IdlePark__Corner2X` | Opposite SL-map corner |
| `Corner2Y` | double | `65` | `IdlePark__Corner2Y` | |
| `Z` | double | `25` | `IdlePark__Z` | Landing altitude (both map URLs share Z=25) |
| `ParkCooldownSeconds` | int | `180` | `IdlePark__ParkCooldownSeconds` | Min interval between park teleport attempts; also the failed-park backoff |

- **Corner-order independence:** `IdleParker` derives
  `minX = min(C1X, C2X)`, `maxX = max(C1X, C2X)` (same for Y), so the two
  SL world-map corners can be pasted in any order without min/max mistakes.
- **Random landing:** `x in [minX,maxX]`, `y in [minY,maxY]`, `z = Z`, via
  thread-safe `Random.Shared`. An equal-corner (degenerate) rectangle is
  valid — it collapses to a point. Only a blank region disables the feature.
- **Validation:** logged once at first park; if `Enabled` and `Region` blank
  → warn and no-op.

The two source SL map URLs decode to: region `Hadron`,
rectangle `X in [30,44]`, `Y in [65,73]`, `Z = 25`.

### `IBotSession` changes (only new surface on the seam)

- `BotLocation? CurrentLocation { get; }` where
  `record BotLocation(string Region, double X, double Y)` — sourced from
  `_client.Network.CurrentSim.Name` + `_client.Self.SimPosition`. `null` when
  no sim is resolved yet (transient post-login). Z is irrelevant to the
  rectangle test, so it is not carried.
- `TeleportAsync(..., bool forceMove = false)` — one new optional param. The
  existing same-sim shortcut becomes
  `if (!forceMove && alreadyInSim) return Ok()`. Default `false` leaves
  `VerifyHandler`/`MonitorHandler` byte-for-byte unchanged (preserves the
  documented false-`ACCESS_DENIED` fix). `IdleParker` passes `forceMove: true`
  so it can relocate *within* Hadron when the bot is in-region but outside the
  rectangle (the user chose rectangle-precise parking).

### New components

- `IdleParkOptions` — config record above.
- `IIdleParker` + `IdleParker` — interface mirrors the
  `IBotSession`/`IBackendClient` seam style so `TaskLoopTests` injects a
  no-op and stays untouched. `IdleParker` holds options, the cooldown
  timestamp, RNG, and the decision logic. One method:
  `Task ParkIfNeededAsync(CancellationToken ct)`.
- `TaskLoop` — inject `IIdleParker`; in the `task is null` branch,
  `await _idleParker.ParkIfNeededAsync(ct)` before
  `SafeDelayAsync(EmptyQueueBackoff, ct)`. Test-friendly ctor gets the param.
- `Program.cs` — `Configure<IdleParkOptions>` +
  `AddSingleton<IIdleParker, IdleParker>()`.

### Approach rationale

Chosen: idle-park step in `TaskLoop`'s empty-queue branch, decision logic in
a new `IdleParker` class. Reuses the single-threaded loop (a park can never
run concurrently with a task), keeps testable logic out of the LibreMetaverse
seam the test suite deliberately never touches (matches the
`MonitorHandler`/`VerifyHandler` precedent), and adds only a thin mechanical
accessor + a `forceMove` flag to `IBotSession`. Rejected: park behind the
`IBotSession` seam (no unit coverage of geometry/scatter); separate
`BackgroundService` on its own timer (must re-infer idleness, risks
teleporting mid-task — a concurrency problem the single-loop approach avoids).

---

## Part B — Bot heartbeat

### Root cause

The backend is complete: `POST /api/v1/bot/heartbeat` →
`BotHeartbeatService.handle()` upserts the `bot_workers` row (its only
creation path) **and** writes a Redis key with a 180s TTL.
`AdminBotPoolService` reads `bot_workers` (→ `total`) joined with the Redis
heartbeat (→ `alive`). `BotPoolSection.tsx` renders `alive/total`.

The bot never sends a heartbeat: `IBackendClient` has only `ClaimAsync`,
`CompleteVerifyAsync`, `PostMonitorAsync`. No heartbeat method, no heartbeat
loop. So `bot_workers` stays empty and no Redis keys exist → admin shows
`0/0` / "No bots registered yet" even while bots run. The UI subtitle
"Heartbeat 60s · Redis · TTL 180s" describes a bot behavior never built.
This is a missing-implementation bug; **no backend or frontend change is
needed** — once heartbeats flow, the existing admin path just works.

### New bot components

- **`HeartbeatOptions`** (section `Heartbeat`): `IntervalSeconds` (int,
  default `60`), env `Heartbeat__IntervalSeconds`. The 180s TTL is
  backend-owned; the bot only controls send cadence (60s ⇒ 3 beats per TTL
  window, the advertised contract).
- **`BotHeartbeatRequest`** (bot model) mirroring the backend record:
  `WorkerName`, `SlUuid`, `SessionState`, `CurrentRegion`, `CurrentTaskKey`,
  `CurrentTaskType`, `LastClaimAt` — camelCase via the existing `JsonOpts`.
- **`IBackendClient.SendHeartbeatAsync(BotHeartbeatRequest, CancellationToken)`**
  → `POST /api/v1/bot/heartbeat`, reusing `SendWithRetryAsync` (bearer auth +
  5xx retry already present).
- **`BotActivityState`** — thread-safe singleton holding an immutable
  snapshot (`CurrentTaskId`, `CurrentTaskType`, `LastClaimAt`) swapped
  atomically via a `volatile` field: lock-free read on the heartbeat path,
  single-writer from the task loop. `RecordClaim(task)` sets `LastClaimAt`
  and, if `task` non-null, the task id/type (else clears them); `Clear()`
  nulls the task id/type but keeps `LastClaimAt`.
  **Written by `TaskLoop` for reporting only; never read by `IdleParker`.**
- **`HeartbeatLoop : BackgroundService`** — every `IntervalSeconds`: builds
  the request from `IBotSession` (`State`→sessionState,
  `CurrentLocation?.Region`→currentRegion, `BotUuid`→slUuid),
  `BotOptions.Username`→workerName, `BotActivityState`→`CurrentTaskKey`
  (task `Id` as string) / `CurrentTaskType` / `LastClaimAt`; POSTs;
  **catches and logs every failure including `AuthConfigException` and never
  crashes** (the claim path remains the authoritative 401-kill handler).
  Stops only on host shutdown.

### Key behavior — heartbeat runs regardless of session state

Unlike `TaskLoop` (gated on `State == Online`), `HeartbeatLoop` sends even
while `Starting`/`Reconnecting`/`Error`. The backend's `isAlive` is driven by
Redis-key presence (TTL), not the state string — so a reconnecting bot
correctly shows **alive + "Reconnecting"** (process up, SL link flapping) and
only goes red when the process is truly dead and the 180s TTL lapses. A
separate-from-the-task-loop heartbeat is the point.

### `TaskLoop` touch

Inject `BotActivityState`; after `ClaimAsync` call `RecordClaim(task)`
(task may be `null` → updates `LastClaimAt`, clears task); in a `finally`
around `DispatchAsync`, call `Clear()`. `Program.cs`:
`Configure<HeartbeatOptions>` + `AddSingleton<BotActivityState>()` +
`AddHostedService<HeartbeatLoop>()`.

`CurrentTaskKey` = bot task `Id` stringified; `CurrentTaskType` = `TaskType`
enum name (admin renders `${currentTaskType} ${currentTaskKey}`).
`LastClaimAt` is stored in Redis state but not rendered in the row — kept for
logs/debug.

---

## Testing strategy (TDD in implementation)

Part A:
- `IdleParkerTests` (pure, faked `IBotSession`): disabled via
  `Enabled=false`; disabled via blank region; in-rectangle → no teleport, no
  cooldown set; outside in a different region → forced teleport, point
  provably within bounds; in `Region` but outside rectangle →
  `forceMove:true` teleport; corner-order independence; cooldown blocks
  re-attempt then allows after expiry; failed teleport sets cooldown;
  `CurrentLocation == null` → graceful skip; cancellation propagates.

Part B:
- `BotActivityStateTests`: `RecordClaim(task)` sets fields;
  `RecordClaim(null)` updates `LastClaimAt` + clears task; `Clear()` nulls
  task keeps `LastClaimAt`; concurrent read/write returns a consistent
  snapshot.
- `HeartbeatLoopTests`: request mapping incl. null region when
  `CurrentLocation` null, state string, task fields; sends on interval;
  backend throw is swallowed (loop continues, no crash); sends even when
  `State != Online`; clean cancellation.
- `HttpBackendClientTests`: +heartbeat POST case (bearer; 5xx retried;
  401 → `AuthConfigException`, which the loop swallows).

Shared:
- `TaskLoopTests`: existing tests pass with a no-op `IIdleParker` and a real
  `BotActivityState` injected; new assertions — empty-claim invokes
  `ParkIfNeededAsync` and a claimed task does not; claim records activity;
  dispatch-completion clears it.
- `LibreMetaverseBotSession` (`CurrentLocation`, `forceMove`): covered by the
  manual smoke test per the project's GridClient-not-unit-tested convention.

No new backend tests (no backend change; `BotHeartbeatServiceTest` /
`AdminBotPoolServiceTest` already cover the receiving side).

## Docs to update (same task)

- `bot/README.md`: env table (+8 `IdlePark__*` rows, note "on by default,
  Hadron"; +`Heartbeat__IntervalSeconds`, note backend TTL 180s); manual
  smoke steps — idle bot relocates into the rectangle and an in-rectangle
  idle bot stays put; bots show `N/N` healthy in admin within ~60s and a
  killed bot flips red after ~180s.
- Root `README.md`: staleness sweep for the new bot behaviors.
- SLPA Postman collection: ensure the `POST /api/v1/bot/heartbeat` request is
  represented for the manual-test surface (add if missing).

## Out of scope

- Any backend, Flyway, or frontend change (none required).
- Per-bot config overrides (all bots share env-driven config, like today).
- Wiring any heartbeat/activity signal into the park decision (explicitly
  forbidden by the hard invariant above).
- Filling the current-task admin column from anything other than the
  `BotActivityState` written by `TaskLoop`.
