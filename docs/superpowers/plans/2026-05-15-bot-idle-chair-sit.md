# Bot Idle Chair-Sit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When idle, a bot sits on a random chair from a configured UUID list inside the existing idle-park rectangle; if the sit fails it just stays standing in the rectangle.

**Architecture:** Rewrite `IdleParker`'s internals as an explicit resting-state machine (`Adrift` → `InRectangle` → `Seated`) keyed off observable session signals. Add `IBotSession.IsSeated` + `SitAsync` mirroring the proven `TeleportAsync` event-race. `Chairs` empty ⇒ byte-identical to today's rectangle-only behavior. The `IIdleParker` seam and `TaskLoop` wiring are unchanged.

**Tech Stack:** C# / .NET 8, xUnit, FluentAssertions, Moq, LibreMetaverse, Microsoft.Extensions.Hosting/Options.

**Spec:** `docs/superpowers/specs/2026-05-15-bot-idle-chair-sit-design.md`

**Decision recorded (in-scope deviation):** The spec made belief-based `IsSeated` a *contingency decided by smoke*. Because this branch auto-merges to prod (no pre-merge smoke gate) and `Self.SittingOn` is a known headless risk, this plan implements the **belief-based `IsSeated` from the start** — it is deterministic, unit-testable, and the spec pre-approved the mechanism. This avoids a likely second deploy iteration and the chair-hopping failure mode if `SittingOn` is unreliable. The post-deploy smoke step still validates real in-world behavior.

**Conventions:**
- Working dir: the `feat/bot-idle-chair-sit` worktree (branch `worktree-feat+bot-idle-chair-sit`, based on `origin/dev`). All commands/paths are repo-relative from the worktree root.
- Build: `dotnet build bot/Slpa.Bot.sln` · All tests: `dotnet test bot/Slpa.Bot.sln` · One class: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.<ClassName>"`
- Commits: conventional, `bot` scope. No AI/tool attribution, no Co-Authored-By. `git -c commit.gpgsign=false commit`. Stage exact paths only — never `git add -A` (untracked `docs/cacheditems*.md` may exist; leave them).

---

## File Structure

**Create:**
- `bot/src/Slpa.Bot/Sl/SitResult.cs` — `record SitResult` + Ok/Fail factories (mirrors `TeleportResult`)
- `bot/src/Slpa.Bot/Sl/SitFailureKind.cs` — `enum SitFailureKind`

**Modify:**
- `bot/src/Slpa.Bot/Sl/IBotSession.cs` — add `IsSeated`, `SitAsync`
- `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs` — implement `IsSeated` (belief), `SitAsync` (event-race), clear belief on teleport-issue + disconnect
- `bot/src/Slpa.Bot/Options/IdleParkOptions.cs` — add `Chairs : List<string>`
- `bot/src/Slpa.Bot/Tasks/IdleParker.cs` — rewrite internals to the resting-state machine
- `bot/src/Slpa.Bot/appsettings.json` — add `IdlePark:Chairs`
- `bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs` — extend `FakeBotSession`
- `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs` — preserve existing tests via empty `Chairs`; add chair-sit tests
- `bot/README.md`, `README.md` — docs

`IdleRestState` lives inside `IdleParker.cs` (small related type co-located, matching how `AuthConfigException` sits with `IBackendClient`).

---

## Task 1: `SitResult`/`SitFailureKind` + `IBotSession` surface + `FakeBotSession`

**Files:** Create `bot/src/Slpa.Bot/Sl/SitResult.cs`, `bot/src/Slpa.Bot/Sl/SitFailureKind.cs`; Modify `bot/src/Slpa.Bot/Sl/IBotSession.cs`, `bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs`.

- [ ] **Step 1: Add the failing capability test.** In `bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs`, add this test to the `LibreMetaverseBotSessionTests` class:

```csharp
    [Fact]
    public async Task FakeSession_CapturesSit_AndExposesIsSeated()
    {
        var chair = Guid.NewGuid();
        var session = new FakeBotSession { IsSeated = false };

        var result = await session.SitAsync(chair, default);

        result.Success.Should().BeTrue();
        var call = session.SitCalls.Should().ContainSingle().Subject;
        call.ChairUuid.Should().Be(chair);
        session.IsSeated.Should().BeFalse();
        session.IsSeated = true;
        session.IsSeated.Should().BeTrue();
    }
```

- [ ] **Step 2: Run — expect COMPILE failure** (`SitAsync`/`IsSeated`/`SitCall` missing):
`dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.LibreMetaverseBotSessionTests"`

- [ ] **Step 3: Create `bot/src/Slpa.Bot/Sl/SitFailureKind.cs`:**

```csharp
namespace Slpa.Bot.Sl;

public enum SitFailureKind
{
    Timeout,
    NotSittable,
    Other
}
```

- [ ] **Step 4: Create `bot/src/Slpa.Bot/Sl/SitResult.cs`:**

```csharp
namespace Slpa.Bot.Sl;

public sealed record SitResult(bool Success, SitFailureKind? Failure)
{
    public static SitResult Ok() => new(true, null);
    public static SitResult Fail(SitFailureKind kind) => new(false, kind);
}
```

- [ ] **Step 5: Add to `IBotSession`** (`bot/src/Slpa.Bot/Sl/IBotSession.cs`) — insert after the `CurrentLocation` property (after its closing `;` on the line `BotLocation? CurrentLocation { get; }`):

```csharp

    /// <summary>
    /// True when the bot is seated on a chair it successfully sat on this
    /// rest (belief-based — set by <see cref="SitAsync"/>, cleared by any
    /// teleport or disconnect). Object tracking is off on the headless
    /// client so a localID-&gt;UUID mapping is unreliable; this belief is the
    /// idle-park "still parked?" signal.
    /// </summary>
    bool IsSeated { get; }
```

Then insert after the `TeleportAsync(...)` declaration (after its closing `;`):

```csharp

    /// <summary>
    /// Sits the bot on the object <paramref name="chairUuid"/>. Mirrors
    /// <see cref="TeleportAsync"/>'s event-race: issues RequestSit, awaits
    /// the AvatarSitResponse for that object vs. a ~15s timeout, throws
    /// <see cref="SessionLostException"/> if the session drops mid-sit.
    /// Scripted sit targets seat the avatar region-wide, so being in the
    /// rectangle (guaranteed by the prior teleport) is sufficient.
    /// </summary>
    Task<SitResult> SitAsync(Guid chairUuid, CancellationToken ct);
```

- [ ] **Step 6: Extend `FakeBotSession`** in `bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs`. After the `TeleportCalls` property block:

```csharp
    public BotLocation? CurrentLocation { get; set; }

    /// <summary>Every <see cref="TeleportAsync"/> call, for assertions.</summary>
    public List<TeleportCall> TeleportCalls { get; } = new();
```

add:

```csharp

    public bool IsSeated { get; set; }

    public Func<Guid, SitResult> SitPolicy { get; set; } = _ => SitResult.Ok();

    /// <summary>Every <see cref="SitAsync"/> call, for assertions.</summary>
    public List<SitCall> SitCalls { get; } = new();
```

Add the method (next to `TeleportAsync` in the fake):

```csharp
    public Task<SitResult> SitAsync(Guid chairUuid, CancellationToken ct)
    {
        SitCalls.Add(new SitCall(chairUuid));
        return Task.FromResult(SitPolicy(chairUuid));
    }
```

At the bottom of the file, next to `public sealed record TeleportCall(...)`:

```csharp

/// <summary>Argument capture for <see cref="FakeBotSession.SitAsync"/>.</summary>
public sealed record SitCall(Guid ChairUuid);
```

- [ ] **Step 7: Run — expect PASS:** `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.LibreMetaverseBotSessionTests"`

- [ ] **Step 8: Full suite (no regressions):** `dotnet test bot/Slpa.Bot.sln` — expect 63 passed (62 + 1 new), 0 failed.

- [ ] **Step 9: Commit:**
```
git add bot/src/Slpa.Bot/Sl/SitResult.cs bot/src/Slpa.Bot/Sl/SitFailureKind.cs bot/src/Slpa.Bot/Sl/IBotSession.cs bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs
git -c commit.gpgsign=false commit -m "feat(bot): add IBotSession.IsSeated + SitAsync (SitResult/SitFailureKind)"
```

---

## Task 2: `IdleParkOptions.Chairs`

**Files:** Modify `bot/src/Slpa.Bot/Options/IdleParkOptions.cs`, `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs`.

- [ ] **Step 1: Update the existing defaults test.** In `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs`, find the test `IdleParkOptions_DefaultsToHadronRectangle` and add, immediately before its closing `}`:

```csharp
        o.Chairs.Should().BeEquivalentTo(new[]
        {
            "d28b2fea-8020-b875-777b-6e432a7d9317",
            "65f7f3e4-1a06-0a07-9233-a3f9a44ff88c",
            "273a9a21-9a23-ca63-58e0-fe817f0a524a",
            "02080632-9fcc-1e1f-36b3-8dd54a694f12",
            "6a8106b7-d771-4c5c-ee19-62b4291de07a",
            "0c852666-669a-9670-e663-380e18d748b7",
            "cd2dbb84-8b18-f28e-c19c-f40468036fc6",
            "ca2c885f-d3fd-2368-1ea7-4c57e014ea5a",
        });
```

- [ ] **Step 2: Run — expect COMPILE failure** (`Chairs` missing):
`dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.IdleParkerTests"`

- [ ] **Step 3: Add `Chairs` to `IdleParkOptions`** (`bot/src/Slpa.Bot/Options/IdleParkOptions.cs`) — insert before the closing `}` of the class, after the `ParkCooldownSeconds` property:

```csharp

    /// <summary>
    /// Object UUIDs of chairs inside the rectangle (region = <see cref="Region"/>).
    /// When idle the bot sits on one at random. Empty ⇒ rectangle-only
    /// behavior (no sitting). Unparseable entries are skipped + warned once.
    /// </summary>
    public List<string> Chairs { get; set; } = new()
    {
        "d28b2fea-8020-b875-777b-6e432a7d9317",
        "65f7f3e4-1a06-0a07-9233-a3f9a44ff88c",
        "273a9a21-9a23-ca63-58e0-fe817f0a524a",
        "02080632-9fcc-1e1f-36b3-8dd54a694f12",
        "6a8106b7-d771-4c5c-ee19-62b4291de07a",
        "0c852666-669a-9670-e663-380e18d748b7",
        "cd2dbb84-8b18-f28e-c19c-f40468036fc6",
        "ca2c885f-d3fd-2368-1ea7-4c57e014ea5a",
    };
```

- [ ] **Step 4: Run — expect PASS:** `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.IdleParkerTests"` (the defaults test passes; other IdleParker tests may now behave differently — Task 3 fixes them).

- [ ] **Step 5: Commit:**
```
git add bot/src/Slpa.Bot/Options/IdleParkOptions.cs bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs
git -c commit.gpgsign=false commit -m "feat(bot): add IdleParkOptions.Chairs (8 Hadron chairs default)"
```

---

## Task 3: `IdleParker` resting-state machine rewrite

**Files:** Modify `bot/src/Slpa.Bot/Tasks/IdleParker.cs`, `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs`.

The "Chairs empty ⇒ byte-identical to today" guarantee is encoded by making the existing rectangle tests pass with `Chairs = new()` (empty), and adding new chair tests.

- [ ] **Step 1: Update `IdleParkerTests.cs`.** Replace the `Opts()` helper:

```csharp
    private static IdleParkOptions Opts() => new();
```

with (existing rectangle tests must test the *no-chairs* path = today's behavior):

```csharp
    private static IdleParkOptions Opts() => new() { Chairs = new() };

    private static IdleParkOptions OptsWithChairs(params string[] chairs) =>
        new() { Chairs = chairs.ToList() };
```

Then **append** these new tests to the `IdleParkerTests` class (the existing rectangle tests are unchanged in body — they now use empty `Chairs` via the updated `Opts()` and must still pass identically):

```csharp
    private static readonly string ChairA = "11111111-1111-1111-1111-111111111111";
    private static readonly string ChairB = "22222222-2222-2222-2222-222222222222";

    [Fact]
    public void DeriveState_Seated_WhenSeatedWithRecordedChair()
    {
        var s = IdleParker.DeriveState(
            isSeated: true, seatedChair: Guid.NewGuid(),
            loc: new BotLocation("Hadron", 200, 200), opts: Opts());
        s.Should().Be(IdleParker.IdleRestState.Seated);
    }

    [Fact]
    public void DeriveState_InRectangle_WhenInsideAndNotSeated()
    {
        var s = IdleParker.DeriveState(
            isSeated: false, seatedChair: null,
            loc: new BotLocation("Hadron", 37, 69), opts: Opts());
        s.Should().Be(IdleParker.IdleRestState.InRectangle);
    }

    [Fact]
    public void DeriveState_Adrift_WhenOutsideRectOrRegion()
    {
        IdleParker.DeriveState(false, null, new BotLocation("Hadron", 200, 200), Opts())
            .Should().Be(IdleParker.IdleRestState.Adrift);
        IdleParker.DeriveState(false, null, new BotLocation("Ahern", 37, 69), Opts())
            .Should().Be(IdleParker.IdleRestState.Adrift);
    }

    [Fact]
    public void DeriveState_Adrift_WhenSeatedFlagButNoRecordedChair()
    {
        IdleParker.DeriveState(true, null, new BotLocation("Hadron", 37, 69), Opts())
            .Should().Be(IdleParker.IdleRestState.InRectangle);
    }

    [Fact]
    public async Task InRectangle_WithChairs_SitsOnRandomChair()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Hadron", 37, 69),
            IsSeated = false,
        };
        var parker = Make(session, OptsWithChairs(ChairA, ChairB),
            () => DateTimeOffset.UnixEpoch, () => 0.0);

        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().BeEmpty();
        var call = session.SitCalls.Should().ContainSingle().Subject;
        call.ChairUuid.Should().Be(Guid.Parse(ChairA)); // rng 0.0 -> index 0
    }

    [Fact]
    public async Task InRectangle_WithChairs_RngPicksLastChair()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Hadron", 37, 69),
        };
        var parker = Make(session, OptsWithChairs(ChairA, ChairB),
            () => DateTimeOffset.UnixEpoch, () => 0.99);

        await parker.ParkIfNeededAsync(default);

        session.SitCalls.Should().ContainSingle()
            .Which.ChairUuid.Should().Be(Guid.Parse(ChairB));
    }

    [Fact]
    public async Task Seated_NoFurtherActions()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Hadron", 37, 69),
            IsSeated = false,
        };
        var parker = Make(session, OptsWithChairs(ChairA),
            () => DateTimeOffset.UnixEpoch, () => 0.0);

        await parker.ParkIfNeededAsync(default);          // sits
        session.IsSeated = true;                          // now seated
        await parker.ParkIfNeededAsync(default);          // should noop

        session.SitCalls.Should().HaveCount(1);
        session.TeleportCalls.Should().BeEmpty();
    }

    [Fact]
    public async Task Unseated_ClearsRecordAndReSits()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Hadron", 37, 69),
        };
        var now = DateTimeOffset.UnixEpoch;
        var parker = Make(session, OptsWithChairs(ChairA),
            () => now, () => 0.0);

        await parker.ParkIfNeededAsync(default);          // sit #1
        session.IsSeated = true;
        await parker.ParkIfNeededAsync(default);          // seated -> noop
        session.IsSeated = false;                         // a task teleported it away
        now = DateTimeOffset.UnixEpoch.AddSeconds(999);   // past cooldown
        await parker.ParkIfNeededAsync(default);          // re-sit

        session.SitCalls.Should().HaveCount(2);
    }

    [Fact]
    public async Task SitFails_StaysStanding_NoChairIteration_SetsCooldown()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Hadron", 37, 69),
            SitPolicy = _ => SitResult.Fail(SitFailureKind.Timeout),
        };
        var now = DateTimeOffset.UnixEpoch;
        var parker = Make(session, OptsWithChairs(ChairA, ChairB),
            () => now, () => 0.0);

        await parker.ParkIfNeededAsync(default);          // one attempt, fails
        await parker.ParkIfNeededAsync(default);          // same clock -> cooldown blocks

        session.SitCalls.Should().HaveCount(1);           // exactly one, no iteration
        session.TeleportCalls.Should().BeEmpty();         // already in rect
    }

    [Fact]
    public async Task Adrift_WithChairs_TeleportsFirst_NoSitSameCycle()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Ahern", 50, 50),
        };
        var parker = Make(session, OptsWithChairs(ChairA),
            () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().ContainSingle();   // positioned this cycle
        session.SitCalls.Should().BeEmpty();              // sit is next cycle's action
    }

    [Fact]
    public async Task Chairs_AllUnparseable_BehavesAsRectangleOnly()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Hadron", 37, 69),
        };
        var parker = Make(session, OptsWithChairs("not-a-uuid", "also-bad"),
            () => DateTimeOffset.UnixEpoch, () => 0.0);

        await parker.ParkIfNeededAsync(default);

        session.SitCalls.Should().BeEmpty();
        session.TeleportCalls.Should().BeEmpty();         // in rect, no chairs -> parked
    }
```

- [ ] **Step 2: Run — expect FAIL** (new tests fail: `IdleParker.DeriveState`/`IdleRestState` don't exist, sit not wired):
`dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.IdleParkerTests"`

- [ ] **Step 3: Rewrite `bot/src/Slpa.Bot/Tasks/IdleParker.cs`** — replace the entire file with:

```csharp
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Test seam for idle-parking. <see cref="IdleParker"/> is the only
/// implementation; <c>TaskLoop</c> tests inject a no-op.
/// </summary>
public interface IIdleParker
{
    Task ParkIfNeededAsync(CancellationToken ct);
}

/// <summary>
/// Drives an idle bot toward its resting state. Resting state is derived each
/// cycle from observable session signals (never stored), so a task teleport
/// that unseats the bot is handled automatically next cycle:
/// <list type="bullet">
/// <item>Adrift — not seated, outside the rectangle/region → teleport in.</item>
/// <item>InRectangle — standing in the rectangle → sit on a random chair
/// (or, with no chairs configured, this IS the goal: today's behavior).</item>
/// <item>Seated — believed seated on a recorded chair → done.</item>
/// </list>
/// Observation-only state (heartbeat/activity) is deliberately NOT consulted —
/// "idle" is solely "TaskLoop.ClaimAsync returned null".
/// </summary>
public sealed class IdleParker : IIdleParker
{
    internal enum IdleRestState { Adrift, InRectangle, Seated }

    private readonly IBotSession _session;
    private readonly IdleParkOptions _opts;
    private readonly ILogger<IdleParker> _log;
    private readonly Func<DateTimeOffset> _now;
    private readonly Func<double> _rng;

    private DateTimeOffset _nextParkUtc = DateTimeOffset.MinValue;
    private bool _warnedDisabled;
    private bool _warnedBadChair;
    private Guid? _seatedChair;

    public IdleParker(
        IBotSession session,
        IOptions<IdleParkOptions> opts,
        ILogger<IdleParker> log)
        : this(session, opts.Value, log,
            () => DateTimeOffset.UtcNow, () => Random.Shared.NextDouble())
    {
    }

    internal IdleParker(
        IBotSession session,
        IdleParkOptions opts,
        ILogger<IdleParker> log,
        Func<DateTimeOffset> now,
        Func<double> rng)
    {
        _session = session;
        _opts = opts;
        _log = log;
        _now = now;
        _rng = rng;
    }

    /// <summary>
    /// Pure resting-state classifier. <paramref name="loc"/> must be non-null
    /// (the caller handles the no-location case before calling this).
    /// </summary>
    internal static IdleRestState DeriveState(
        bool isSeated, Guid? seatedChair, BotLocation loc, IdleParkOptions opts)
    {
        if (isSeated && seatedChair is not null)
            return IdleRestState.Seated;

        double minX = Math.Min(opts.Corner1X, opts.Corner2X);
        double maxX = Math.Max(opts.Corner1X, opts.Corner2X);
        double minY = Math.Min(opts.Corner1Y, opts.Corner2Y);
        double maxY = Math.Max(opts.Corner1Y, opts.Corner2Y);

        bool inRect = string.Equals(
                loc.Region, opts.Region, StringComparison.OrdinalIgnoreCase)
            && loc.X >= minX && loc.X <= maxX
            && loc.Y >= minY && loc.Y <= maxY;

        return inRect ? IdleRestState.InRectangle : IdleRestState.Adrift;
    }

    public async Task ParkIfNeededAsync(CancellationToken ct)
    {
        try
        {
            if (!_opts.Enabled) return;

            if (string.IsNullOrWhiteSpace(_opts.Region))
            {
                if (!_warnedDisabled)
                {
                    _log.LogWarning(
                        "IdlePark enabled but Region is blank; idle-parking disabled.");
                    _warnedDisabled = true;
                }
                return;
            }

            var now = _now();
            if (now < _nextParkUtc) return;

            bool isSeated = _session.IsSeated;
            if (!isSeated) _seatedChair = null; // sole unseat-detection

            var loc = _session.CurrentLocation;
            if (!isSeated && loc is null) return; // can't position yet, no cooldown

            var state = isSeated && _seatedChair is not null
                ? IdleRestState.Seated
                : DeriveState(isSeated, _seatedChair, loc!, _opts);

            switch (state)
            {
                case IdleRestState.Seated:
                    return; // goal reached

                case IdleRestState.Adrift:
                {
                    double minX = Math.Min(_opts.Corner1X, _opts.Corner2X);
                    double maxX = Math.Max(_opts.Corner1X, _opts.Corner2X);
                    double minY = Math.Min(_opts.Corner1Y, _opts.Corner2Y);
                    double maxY = Math.Max(_opts.Corner1Y, _opts.Corner2Y);

                    double x = minX + _rng() * (maxX - minX);
                    double y = minY + _rng() * (maxY - minY);
                    double z = _opts.Z;

                    _nextParkUtc = now + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);

                    var tp = await _session
                        .TeleportAsync(_opts.Region, x, y, z, ct, forceMove: true)
                        .ConfigureAwait(false);

                    if (tp.Success)
                        _log.LogInformation(
                            "Idle-parked to {Region} ({X:F1},{Y:F1},{Z:F1})",
                            _opts.Region, x, y, z);
                    else
                        _log.LogWarning(
                            "Idle-park teleport to {Region} failed: {Failure}; "
                            + "backing off {Cooldown}s",
                            _opts.Region, tp.Failure, _opts.ParkCooldownSeconds);
                    return;
                }

                case IdleRestState.InRectangle:
                {
                    var chairs = ParseChairs();
                    if (chairs.Count == 0) return; // no chairs -> this IS the goal

                    var chair = chairs[(int)(_rng() * chairs.Count)];
                    _nextParkUtc = now + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);

                    var sr = await _session.SitAsync(chair, ct).ConfigureAwait(false);
                    if (sr.Success)
                    {
                        _seatedChair = chair;
                        _log.LogInformation("Idle-sat on chair {Chair}", chair);
                    }
                    else
                    {
                        _log.LogWarning(
                            "Idle-sit on {Chair} failed: {Failure}; staying "
                            + "standing, backing off {Cooldown}s",
                            chair, sr.Failure, _opts.ParkCooldownSeconds);
                    }
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            // Re-read the clock: the awaited call may have thrown after an
            // unpredictable delay, so the pre-await `now` is a stale anchor.
            _nextParkUtc = _now()
                + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);
            _log.LogWarning(ex,
                "Idle-park attempt threw; backing off {Cooldown}s",
                _opts.ParkCooldownSeconds);
        }
    }

    private List<Guid> ParseChairs()
    {
        var list = new List<Guid>(_opts.Chairs.Count);
        foreach (var s in _opts.Chairs)
        {
            if (Guid.TryParse(s, out var g))
            {
                list.Add(g);
            }
            else if (!_warnedBadChair)
            {
                _log.LogWarning(
                    "IdlePark.Chairs has unparseable entry '{Entry}'; skipping.", s);
                _warnedBadChair = true;
            }
        }
        return list;
    }
}
```

- [ ] **Step 4: Run — expect PASS:** `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.IdleParkerTests"`. The 12 pre-existing rectangle tests pass unchanged (they use empty `Chairs` via `Opts()` ⇒ today's behavior); the new chair/state tests pass.

- [ ] **Step 5: Full suite:** `dotnet test bot/Slpa.Bot.sln` — expect all green (no `TaskLoop`/seam changes).

- [ ] **Step 6: Commit:**
```
git add bot/src/Slpa.Bot/Tasks/IdleParker.cs bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs
git -c commit.gpgsign=false commit -m "feat(bot): IdleParker resting-state machine with chair-sit"
```

---

## Task 4: `LibreMetaverseBotSession` — `IsSeated` (belief) + `SitAsync`

No unit tests (GridClient is manual-smoke per project convention); the gate is "compiles + full suite still green + the documented smoke step". Mirror the existing `TeleportAsync` event-race exactly.

**Files:** Modify `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs`.

- [ ] **Step 1: Add the seated-belief field.** In `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs`, find the field block (near `private int _stateValue = (int)SessionState.Starting;`) and add after it:

```csharp
    // Belief-based seated signal. SittingOn is unreliable on a headless
    // client (object tracking off), so a successful SitAsync sets this; any
    // teleport-issue or disconnect clears it. Volatile: read off the task loop.
    private volatile bool _seatedBelief;
```

- [ ] **Step 2: Implement `IsSeated`.** Add this property right after the existing `CurrentLocation` property block (the one ending with the `BotLocation` return):

```csharp
    public bool IsSeated => _seatedBelief && State == SessionState.Online;
```

- [ ] **Step 3: Clear the belief when an actual teleport is issued.** In `TeleportAsync`, locate the line `_client.Self.Teleport(regionName, new Vector3((float)x, (float)y, (float)z));` and insert immediately **before** it:

```csharp
            _seatedBelief = false; // a real teleport stands the avatar up
```

(This is after the same-sim shortcut early-return, so a skipped same-sim teleport does NOT clear the belief — the bot didn't move.)

- [ ] **Step 4: Clear the belief on disconnect.** In `RunLoop`, find the block that transitions to reconnecting:

```csharp
            TransitionTo(SessionState.Reconnecting);
            _log.LogWarning("Bot {Uuid} disconnected; reconnecting", BotUuid);
```

and insert before `TransitionTo(SessionState.Reconnecting);`:

```csharp
            _seatedBelief = false; // disconnect un-seats the avatar in-world
```

- [ ] **Step 5: Implement `SitAsync`.** Add this method immediately after the `TeleportAsync` method's closing brace:

```csharp
    public async Task<SitResult> SitAsync(Guid chairUuid, CancellationToken ct)
    {
        if (State != SessionState.Online)
        {
            throw new SessionLostException($"Cannot sit in state {State}");
        }

        var target = new UUID(chairUuid.ToString());
        var tcs = new TaskCompletionSource<SitResult>(
            TaskCreationOptions.RunContinuationsAsynchronously);

        EventHandler<AvatarSitResponseEventArgs>? handler = null;
        handler = (_, e) =>
        {
            if (e.ObjectID != target) return;
            _client.Self.AvatarSitResponse -= handler!;
            // The sim acknowledged the sit target for our object; commit the
            // sit. We treat the acknowledged response as success (belief) —
            // SittingOn is unreliable headless. ForceMouselook/Autopilot
            // don't matter; any acknowledged sit target seats the avatar.
            _client.Self.Sit();
            tcs.TrySetResult(SitResult.Ok());
        };
        _client.Self.AvatarSitResponse += handler;

        EventHandler<DisconnectedEventArgs>? disc = null;
        disc = (_, _) => tcs.TrySetException(
            new SessionLostException("Disconnected mid-sit"));
        _client.Network.Disconnected += disc;

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(15));
        var registration = timeoutCts.Token.Register(() =>
            tcs.TrySetResult(SitResult.Fail(SitFailureKind.Timeout)));
        try
        {
            _client.Self.RequestSit(target, Vector3.Zero);
            var result = await tcs.Task.ConfigureAwait(false);
            if (result.Success) _seatedBelief = true;
            return result;
        }
        finally
        {
            registration.Dispose();
            _client.Self.AvatarSitResponse -= handler;
            _client.Network.Disconnected -= disc!;
        }
    }
```

- [ ] **Step 6: Build — expect success:** `dotnet build bot/Slpa.Bot.sln`. If `AvatarSitResponseEventArgs`/`RequestSit`/`AvatarSitResponse`/`Sit` resolve under a different name in this LibreMetaverse version, fix the symbol names to match the referenced `LibreMetaverse` assembly (the `OpenMetaverse` namespace is already imported in this file) — do NOT change the event-race structure. If a symbol genuinely does not exist, STOP and report BLOCKED with the exact compiler error.

- [ ] **Step 7: Full suite (no regressions):** `dotnet test bot/Slpa.Bot.sln` — all green.

- [ ] **Step 8: Commit:**
```
git add bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs
git -c commit.gpgsign=false commit -m "feat(bot): LibreMetaverseBotSession SitAsync + belief-based IsSeated"
```

---

## Task 5: `appsettings.json` Chairs

**Files:** Modify `bot/src/Slpa.Bot/appsettings.json`.

- [ ] **Step 1: Add the `Chairs` array.** In `bot/src/Slpa.Bot/appsettings.json`, the `IdlePark` block currently ends:

```json
    "Z": 25,
    "ParkCooldownSeconds": 180
  },
```

Replace that with:

```json
    "Z": 25,
    "ParkCooldownSeconds": 180,
    "Chairs": [
      "d28b2fea-8020-b875-777b-6e432a7d9317",
      "65f7f3e4-1a06-0a07-9233-a3f9a44ff88c",
      "273a9a21-9a23-ca63-58e0-fe817f0a524a",
      "02080632-9fcc-1e1f-36b3-8dd54a694f12",
      "6a8106b7-d771-4c5c-ee19-62b4291de07a",
      "0c852666-669a-9670-e663-380e18d748b7",
      "cd2dbb84-8b18-f28e-c19c-f40468036fc6",
      "ca2c885f-d3fd-2368-1ea7-4c57e014ea5a"
    ]
  },
```

(Keep all other `IdlePark` keys and the trailing `,` after `}` if `Heartbeat` follows. Verify the file is valid JSON — the `]` then `}` then `,` ordering matters.)

- [ ] **Step 2: Build (config binds at runtime; just confirm JSON valid):** `dotnet build bot/Slpa.Bot.sln` then `dotnet test bot/Slpa.Bot.sln` — all green.

- [ ] **Step 3: Commit:**
```
git add bot/src/Slpa.Bot/appsettings.json
git -c commit.gpgsign=false commit -m "feat(bot): ship the 8 idle chairs in appsettings IdlePark.Chairs"
```

---

## Task 6: Docs

**Files:** Modify `bot/README.md`, `README.md`.

- [ ] **Step 1: `bot/README.md` env table.** Add a row immediately after the `IdlePark__ParkCooldownSeconds` row, matching the existing column alignment:

```
| `IdlePark__Chairs__0..N`        | no       | 8 Hadron chairs                        | idle bot sits on a random one; empty = stand only |
```

- [ ] **Step 2: `bot/README.md` behavior note.** Find the paragraph that begins "Idle-parking is on by default with the Hadron rectangle baked in." and append to that paragraph:

```
When `IdlePark.Chairs` is non-empty (8 chairs ship by default), an idle bot in
the rectangle sits on one chair chosen at random; one attempt per idle cycle,
and if it fails the bot just stays standing in the rectangle. Clear the list
to revert to stand-only parking.
```

- [ ] **Step 3: `bot/README.md` smoke step.** In the "Manual smoke test" numbered list, append the next number:

```
10. With chairs configured, leave a bot idle ~1 min: confirm it teleports into
    the rectangle then sits (log line `Idle-sat on chair <uuid>`). Confirm it
    does NOT re-sit every cooldown while seated, and that after a task
    teleports it away it re-sits next idle cycle. If the bot visibly sits but
    keeps re-sitting/hopping chairs, `IsSeated`'s belief is being cleared
    wrongly — check the teleport/disconnect clears; file a follow-up.
```

- [ ] **Step 4: Root `README.md` sweep.** Find the bot `Tasks/` line that lists `IdleParker (teleports to configured rectangle when idle)` and change that parenthetical to:

```
IdleParker (resting-state machine: sits on a random configured chair when idle, else parks in the rectangle)
```

- [ ] **Step 5: Commit (docs only, no build):**
```
git add bot/README.md README.md
git -c commit.gpgsign=false commit -m "docs(bot): document idle chair-sit config + behavior + smoke step"
```

---

## Final Verification

- [ ] **Step 1:** `dotnet build bot/Slpa.Bot.sln` then `dotnet test bot/Slpa.Bot.sln` — Build succeeded; ALL tests pass (62 baseline + new; 0 failed).
- [ ] **Step 2:** `git status --porcelain` — clean except any pre-existing untracked `docs/cacheditems*.md`. Nothing else stray.
- [ ] **Step 3:** Re-read the spec; confirm every requirement maps to a committed task (see Self-Review below).

---

## Self-Review (plan vs spec)

**Spec coverage:**
- State machine (Adrift/InRectangle/Seated, derived per cycle) → Task 3 (`DeriveState` + switch; `DeriveState_*` tests).
- Goal depends on Chairs (empty ⇒ InRectangle goal = today's behavior) → Task 3 (`InRectangle` + `chairs.Count == 0` returns; existing rectangle tests preserved via empty `Opts()`).
- One random chair, one attempt, no iteration, stay standing on fail → Task 3 (`SitFails_StaysStanding_NoChairIteration_SetsCooldown`, single `_rng()` pick).
- Unseat detection via `IsSeated`, `_seatedChair` cleared → Task 3 (`Unseated_ClearsRecordAndReSits`).
- Cooldown reuse (`ParkCooldownSeconds`) gates sit + teleport attempts; in-rect-no-chairs & Seated cost no cooldown → Task 3.
- `IBotSession.IsSeated` + `SitAsync` mirroring TeleportAsync → Tasks 1 (surface) & 4 (impl).
- Headless contingency = belief-based `IsSeated` → Task 4 (chosen upfront; deviation recorded in header), cleared on teleport-issue/disconnect, smoke step in Task 6.
- `SitResult`/`SitFailureKind` mirror `TeleportResult`/`TeleportFailureKind` → Task 1.
- Config `Chairs : List<string>`, region = `IdlePark.Region`, defensive parse + warn-once, appsettings ships 8 → Tasks 2, 3 (`ParseChairs`, `Chairs_AllUnparseable_*`), 5.
- `IIdleParker` seam + `TaskLoop` unchanged → Task 3 keeps the interface + both ctors identical (no `TaskLoop`/`TaskLoopTests` edits).
- Testing strategy (pure DeriveState tests, FakeBotSession IsSeated+SitPolicy+SitCalls, smoke one bot first) → Tasks 1, 3, 6.
- Docs → Task 6.

**Placeholder scan:** none — every step has concrete code/commands.

**Type consistency:** `SitResult(bool Success, SitFailureKind? Failure)` + `Ok()/Fail()`; `SitFailureKind {Timeout,NotSittable,Other}`; `IBotSession.IsSeated`/`SitAsync(Guid,CancellationToken)`; `FakeBotSession.IsSeated`/`SitPolicy:Func<Guid,SitResult>`/`SitCalls:List<SitCall>`/`SitCall(Guid ChairUuid)`; `IdleParker.IdleRestState{Adrift,InRectangle,Seated}` + `DeriveState(bool,Guid?,BotLocation,IdleParkOptions)`; `IdleParkOptions.Chairs:List<string>` — consistent across Tasks 1–5. `IdleParker` public + internal ctor signatures unchanged ⇒ `IdleParkerTests.Make` and `TaskLoop` callers stay valid.
