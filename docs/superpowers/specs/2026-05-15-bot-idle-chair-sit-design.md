# Bot idle chair-sit — design

Date: 2026-05-15
Status: Approved (brainstorming) — proceeding straight to implementation plan
(user waived the spec-review gate)
Scope: bot-side only (`bot/`). No backend, Flyway, or frontend changes.
Builds on: `docs/superpowers/specs/2026-05-15-bot-idle-park-and-heartbeat-design.md`
(idle-park) — this extends that feature.

## Summary

When idle, a bot should sit on a random chair from a configured list instead
of just standing at a random rectangle point. The 8 chairs are inside the
existing `IdlePark` rectangle/region (Hadron). Chair-sitting is **primary**;
the existing rectangle teleport becomes the positioning step that doubles as
the fallback resting place. Exactly **one** randomly chosen chair is attempted
per idle cycle — no iterating the list; on failure the bot just stays standing
in the rectangle.

This is a deliberately small, config-gated experiment ("give A a test"):
**`Chairs` empty ⇒ byte-identical to today's rectangle-only behavior**, so the
whole feature reverts by clearing one config list.

## Architecture decision

`IdleParker`'s internals are **rewritten as an explicit resting-state machine**
(brainstorm option A3). Sitting introduces genuine state (standing → in-region
→ seated, with task-driven unseat transitions); bolting that onto the current
stateless rectangle-membership check would muddy a component whose elegance was
its statelessness. The rewrite is config-revertible and the unit is small, so
this is a clean-code call, not gold-plating. The `IIdleParker.ParkIfNeededAsync`
seam is unchanged — `TaskLoop` wiring and its tests are untouched.

## State machine

`ParkIfNeededAsync` derives the resting state each idle cycle from observable
session signals (never stored — so a task teleport that unseats the bot is
handled automatically next cycle):

- **`Adrift`** — not seated and `CurrentLocation` is outside the configured
  region/rectangle (fresh login, or a task moved the bot).
- **`InRectangle`** — not seated, but standing inside the rectangle.
- **`Seated`** — `IBotSession.IsSeated` is true *and* a successful sit was
  recorded this rest (`_seatedChair != null`).

**Goal:** `Chairs` non-empty ⇒ goal is `Seated`, with `InRectangle` the
accepted fallback. `Chairs` empty ⇒ goal is `InRectangle` (today's behavior).

**Action (one cheap, idempotent step toward goal per cycle):**

| Derived state | Action |
|---|---|
| Not enabled / blank region | none (unchanged) |
| `Seated` | none (goal reached) |
| `Adrift` | forced teleport to a random rectangle point (existing logic, cooldown-gated) → next cycle becomes `InRectangle` |
| `InRectangle`, no chairs | none (goal reached — today's behavior) |
| `InRectangle`, chairs, cooldown active | none (wait) |
| `InRectangle`, chairs, cooldown elapsed | pick **one** uniformly-random chair, `SitAsync`; success → record `_seatedChair`; failure → set cooldown, stay standing (fallback). Never iterate chairs. |

Whenever `IBotSession.IsSeated` is false, `_seatedChair` is cleared — that is
the sole unseat-detection mechanism (a task teleport ⇒ not seated next cycle ⇒
re-park). Idempotency keys off `IsSeated`, never a localID→UUID mapping
(object tracking is off on the headless client).

**Clean-code shape:** an internal `enum IdleRestState { Adrift, InRectangle,
Seated }`; a pure `DeriveState(isSeated, seatedChair, location, opts)`
function (unit-testable in isolation); an action dispatcher keyed off
`(state, goal, cooldown)`. Retained fields shrink to `_seatedChair` (Guid?)
and `_nextParkUtc` (existing).

## `IBotSession` primitives

Mirror the proven `TeleportAsync` event-race pattern.

- **`bool IsSeated { get; }`** — wraps LibreMetaverse `Self.SittingOn != 0`.
  Fake: settable.
- **`Task<SitResult> SitAsync(Guid chairUuid, CancellationToken ct)`** — issue
  `Self.RequestSit(uuid, Vector3.Zero)` + `Self.Sit()`; await `AvatarSitResponse`
  (for our object id) vs. a ~15s timeout; throw `SessionLostException` on
  `Disconnected` mid-sit. Scripted sit targets seat the avatar region-wide, so
  being in the rectangle (guaranteed by the prior teleport) suffices — no
  walking/adjacency. `SitResult` mirrors `TeleportResult`:
  `record SitResult(bool Success, SitFailureKind? Failure)` with
  `enum SitFailureKind { Timeout, NotSittable, Other }`. Session loss is
  thrown, not returned (matches teleport).

## Headless-client risk + contingency (the real unknown)

`CreateHeadlessClient()` sets `OBJECT_TRACKING=false`,
`ALWAYS_DECODE_OBJECTS=false`. Reading code cannot settle whether a headless
client reliably reflects a successful sit; it is empirical:

- *Issuing* the sit (`RequestSit` by bare UUID) should work — it is a
  sim-resolved packet, independent of client-side object decoding.
- *Confirming* via `Self.SittingOn` is uncertain — `SittingOn` is set when the
  agent is reparented to the seat; object tracking being off **may** suppress
  that update.

**Pre-approved contingency (not a redesign):** `IsSeated` / the `Seated` state
derive from `Self.SittingOn` *if it proves reliable*. If smoke-testing shows
it does not update headless, degrade to a **belief-based** signal — a
successful `AvatarSitResponse` for our chair sets an in-process "seated"
belief, invalidated on any teleport or disconnect; `IsSeated` then reflects
that belief. This contingency lives inside `SitAsync`/the session impl and the
state machine reads `IsSeated` either way — no change to the state machine or
the GridClient headless settings. Validation is **manual smoke on one bot
first**, then roll the pool — consistent with the project convention that
LibreMetaverse internals are smoke-tested, and the fact that (unlike teleport)
sit is an unproven primitive in this codebase.

## Configuration

- Add `Chairs : List<string>` to `IdleParkOptions`. Chairs live in the
  existing `IdlePark.Region` (no separate region — they are in the rectangle).
  **Empty list ⇒ byte-identical to today's rectangle-only behavior** (the
  config-only revert).
- Reuse the existing `ParkCooldownSeconds` as the sit-retry backoff (one knob;
  no new config).
- `appsettings.json` ships the 8 chair UUIDs under `IdlePark:Chairs` (baked in
  for the prod test, like the Hadron rectangle was). Env override
  `IdlePark__Chairs__0 … IdlePark__Chairs__7`.
- Defensive parse: unparseable UUID entries are skipped and warned once at
  first park; never crash the loop. If every entry is unparseable the list is
  treated as empty (pure rectangle behavior).

The 8 chairs (Hadron, inside the existing rectangle):

```
d28b2fea-8020-b875-777b-6e432a7d9317
65f7f3e4-1a06-0a07-9233-a3f9a44ff88c
273a9a21-9a23-ca63-58e0-fe817f0a524a
02080632-9fcc-1e1f-36b3-8dd54a694f12
6a8106b7-d771-4c5c-ee19-62b4291de07a
0c852666-669a-9670-e663-380e18d748b7
cd2dbb84-8b18-f28e-c19c-f40468036fc6
ca2c885f-d3fd-2368-1ea7-4c57e014ea5a
```

## Testing strategy

**Unit (faked `IBotSession`):** extend `FakeBotSession` with a settable
`IsSeated`, a `SitAsync` policy (`Func<Guid, SitResult>`), and a `SitCalls`
capture list (mirrors the existing `TeleportCalls` pattern).

- Pure `DeriveState` tests across all input combinations (Adrift /
  InRectangle / Seated for the relevant `isSeated` × location × seatedChair
  permutations).
- `Chairs` empty ⇒ behavior identical to current rectangle-only idle-park
  (no `SitAsync` call, rectangle teleport only).
- Random pick is within the configured set; one attempt only (exactly one
  `SitCalls` entry per acting cycle, never iterates).
- Sit success ⇒ `Seated`; idempotent (no re-sit, no re-teleport while
  `IsSeated`).
- Sit failure ⇒ stays standing, `_nextParkUtc` set, no chair iteration.
- `IsSeated` false ⇒ `_seatedChair` cleared and the bot re-parks.
- Cooldown gates the next sit attempt.
- Cancellation: a sit `OperationCanceledException` propagates (clean
  shutdown), matching the teleport/park OCE handling already wired in
  `TaskLoop`.
- Unparseable UUID entries skipped + warned; all-unparseable ⇒ rectangle
  behavior.

**Manual smoke (GridClient `SitAsync`/`IsSeated`, per convention + headless
risk):** roll to **one bot first** — confirm it teleports into the rectangle
and sits on a configured chair; after a task teleports it away it re-sits the
next idle cycle. Decide `SittingOn`-vs-belief based on observed behavior, then
roll the pool.

`TaskLoop` wiring is unchanged; existing `TaskLoop`/`IdleParker` tests are
updated only where the internal rewrite changes them (the public seam and its
TaskLoop tests stay green).

## Out of scope

- Discovering chairs by object name (the harder Variant B). Chairs are
  configured by UUID.
- Iterating multiple chairs / occupied-chair fallback chains (explicitly: one
  random attempt, then stand).
- Any backend, Flyway, or frontend change.
- Relaxing the headless `GridClient` settings (the belief-based contingency
  avoids needing to).
