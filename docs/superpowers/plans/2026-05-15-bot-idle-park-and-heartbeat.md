# Bot Idle-Park + Heartbeat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Idle bots teleport to a configurable rectangle in a configurable region, and bots send heartbeats so the admin Bot-pool panel stops showing 0/0.

**Architecture:** Bot-side only (`bot/`, .NET 8). Idle-park is a stateless rectangle check invoked from `TaskLoop`'s empty-claim branch via a new `IIdleParker`. Heartbeat is a separate `BackgroundService` (`HeartbeatLoop`) that POSTs the already-built backend endpoint, reading session state + a `BotActivityState` written by `TaskLoop`. The heartbeat is observation-only and never feeds idle detection.

**Tech Stack:** C# / .NET 8, xUnit, FluentAssertions, Moq, WireMock.Net, Microsoft.Extensions.Hosting/Options.

**Spec:** `docs/superpowers/specs/2026-05-15-bot-idle-park-and-heartbeat-design.md`

**Conventions:**
- Build: `dotnet build bot/Slpa.Bot.sln`
- All tests: `dotnet test bot/Slpa.Bot.sln`
- One class: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.<ClassName>"`
- Commit messages: conventional, `bot` scope (e.g. `feat(bot): ...`). No AI/tool attribution, no Co-Authored-By trailers.
- Branch off `dev`. Never `git add -A` (untracked `docs/cacheditems*.md` and `.scratch/` must stay out). Stage exact paths only.

---

## File Structure

**Create:**
- `bot/src/Slpa.Bot/Sl/BotLocation.cs` — `record BotLocation(string Region, double X, double Y)`
- `bot/src/Slpa.Bot/Options/IdleParkOptions.cs` — idle-park config
- `bot/src/Slpa.Bot/Options/HeartbeatOptions.cs` — heartbeat cadence config
- `bot/src/Slpa.Bot/Tasks/IdleParker.cs` — `IIdleParker` + `IdleParker` (decision logic)
- `bot/src/Slpa.Bot/Tasks/BotActivityState.cs` — thread-safe current-activity snapshot
- `bot/src/Slpa.Bot/Tasks/HeartbeatLoop.cs` — `BackgroundService` heartbeat sender
- `bot/src/Slpa.Bot/Backend/Models/BotHeartbeatRequest.cs` — heartbeat request DTO
- `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs`
- `bot/tests/Slpa.Bot.Tests/BotActivityStateTests.cs`
- `bot/tests/Slpa.Bot.Tests/HeartbeatLoopTests.cs`

**Modify:**
- `bot/src/Slpa.Bot/Sl/IBotSession.cs` — add `CurrentLocation`, add `forceMove` param
- `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs` — implement both
- `bot/src/Slpa.Bot/Backend/IBackendClient.cs` — add `SendHeartbeatAsync`
- `bot/src/Slpa.Bot/Backend/HttpBackendClient.cs` — implement it
- `bot/src/Slpa.Bot/Tasks/TaskLoop.cs` — inject `IIdleParker` + `BotActivityState`; park on idle; record/clear activity
- `bot/src/Slpa.Bot/Program.cs` — DI + options binding
- `bot/src/Slpa.Bot/appsettings.json` — `IdlePark` + `Heartbeat` sections
- `bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs` — extend `FakeBotSession`
- `bot/tests/Slpa.Bot.Tests/TaskLoopTests.cs` — update ctor calls + new tests
- `bot/tests/Slpa.Bot.Tests/HttpBackendClientTests.cs` — heartbeat cases
- `bot/README.md` — env table + smoke steps
- `README.md` — staleness sweep
- SLPA Postman collection — heartbeat request

---

## Task 1: Extend `IBotSession` — `CurrentLocation` + `forceMove`

**Files:**
- Create: `bot/src/Slpa.Bot/Sl/BotLocation.cs`
- Modify: `bot/src/Slpa.Bot/Sl/IBotSession.cs`
- Modify: `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs`
- Modify: `bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs` (extend `FakeBotSession`)

- [ ] **Step 1: Create `BotLocation`**

`bot/src/Slpa.Bot/Sl/BotLocation.cs`:

```csharp
namespace Slpa.Bot.Sl;

/// <summary>
/// The bot's current region + (x, y) within that region. Z is irrelevant to
/// the idle-park rectangle test, so it is not carried.
/// </summary>
public sealed record BotLocation(string Region, double X, double Y);
```

- [ ] **Step 2: Extend `FakeBotSession` and add the failing test**

In `bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs`, replace the `FakeBotSession` class's teleport member and add capture + location. Replace this block:

```csharp
    public Func<string, TeleportResult> TeleportPolicy { get; set; } =
        _ => TeleportResult.Ok();
```

with:

```csharp
    public Func<string, TeleportResult> TeleportPolicy { get; set; } =
        _ => TeleportResult.Ok();

    public BotLocation? CurrentLocation { get; set; }

    /// <summary>Every <see cref="TeleportAsync"/> call, for assertions.</summary>
    public List<TeleportCall> TeleportCalls { get; } = new();
```

Replace this method:

```csharp
    public Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z, CancellationToken ct)
        => Task.FromResult(TeleportPolicy(regionName));
```

with:

```csharp
    public Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z,
        CancellationToken ct, bool forceMove = false)
    {
        TeleportCalls.Add(new TeleportCall(regionName, x, y, z, forceMove));
        return Task.FromResult(TeleportPolicy(regionName));
    }
```

At the bottom of the file, next to `GiveGroupMoneyCall`, add:

```csharp
/// <summary>Argument capture for <see cref="FakeBotSession.TeleportAsync"/>.</summary>
public sealed record TeleportCall(string Region, double X, double Y, double Z, bool ForceMove);
```

Add this test to the `LibreMetaverseBotSessionTests` class:

```csharp
    [Fact]
    public async Task FakeSession_CapturesForcedTeleport_AndExposesLocation()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Hadron", 37, 69)
        };

        await session.TeleportAsync("Hadron", 31, 66, 25, default, forceMove: true);

        session.CurrentLocation!.Region.Should().Be("Hadron");
        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.ForceMove.Should().BeTrue();
        call.Region.Should().Be("Hadron");
    }
```

- [ ] **Step 3: Run the test — expect a COMPILE failure**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.LibreMetaverseBotSessionTests"`
Expected: build error — `IBotSession` does not define `CurrentLocation`, and the new `TeleportAsync` signature does not match the interface.

- [ ] **Step 4: Update the `IBotSession` interface**

In `bot/src/Slpa.Bot/Sl/IBotSession.cs`, add inside the interface (after `Guid BotUuid { get; }`):

```csharp
    /// <summary>
    /// The bot's current region + (x, y), or null when no sim is resolved
    /// yet (transient post-login). Used by idle-park; never blocks.
    /// </summary>
    BotLocation? CurrentLocation { get; }
```

Replace the `TeleportAsync` declaration with:

```csharp
    /// <summary>
    /// Teleports to <paramref name="regionName"/> at (x, y, z). Awaits the
    /// LibreMetaverse TeleportFinished / TeleportFailed race with a 30s
    /// timeout. Rate-limited per SL's 6/min cap. Throws
    /// <see cref="SessionLostException"/> if the session drops mid-teleport.
    /// When <paramref name="forceMove"/> is false (default) and the bot is
    /// already in the target sim, the teleport is skipped (returns Ok) — this
    /// preserves the documented false-ACCESS_DENIED fix for monitor/verify.
    /// Idle-park passes <c>forceMove: true</c> to relocate within a sim.
    /// </summary>
    Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z,
        CancellationToken ct, bool forceMove = false);
```

- [ ] **Step 5: Update `LibreMetaverseBotSession`**

In `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs`, add after the `BotUuid` property (`public Guid BotUuid => _opts.BotUuid;`):

```csharp
    public BotLocation? CurrentLocation
    {
        get
        {
            var sim = _client.Network.CurrentSim;
            if (sim is null) return null;
            var p = _client.Self.SimPosition;
            return new BotLocation(sim.Name, p.X, p.Y);
        }
    }
```

Change the `TeleportAsync` signature line from:

```csharp
    public async Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z, CancellationToken ct)
    {
```

to:

```csharp
    public async Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z,
        CancellationToken ct, bool forceMove = false)
    {
```

Then change the same-sim shortcut condition from:

```csharp
        var currentSim = _client.Network.CurrentSim;
        if (currentSim is not null
            && string.Equals(currentSim.Name, regionName,
                StringComparison.OrdinalIgnoreCase))
        {
```

to:

```csharp
        var currentSim = _client.Network.CurrentSim;
        if (!forceMove
            && currentSim is not null
            && string.Equals(currentSim.Name, regionName,
                StringComparison.OrdinalIgnoreCase))
        {
```

- [ ] **Step 6: Run the test — expect PASS**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.LibreMetaverseBotSessionTests"`
Expected: PASS (5 tests).

- [ ] **Step 7: Run the full suite — expect PASS (no regressions)**

Run: `dotnet test bot/Slpa.Bot.sln`
Expected: PASS. Existing `MonitorHandler`/`VerifyHandler` calls compile unchanged because `forceMove` is optional.

- [ ] **Step 8: Commit**

```bash
git add bot/src/Slpa.Bot/Sl/BotLocation.cs bot/src/Slpa.Bot/Sl/IBotSession.cs bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs
git commit -m "feat(bot): add IBotSession.CurrentLocation and TeleportAsync forceMove"
```

---

## Task 2: `IdleParkOptions`

**Files:**
- Create: `bot/src/Slpa.Bot/Options/IdleParkOptions.cs`
- Test: `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs` (defaults test only, this task)

- [ ] **Step 1: Write the failing test**

Create `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs`:

```csharp
using FluentAssertions;
using Slpa.Bot.Options;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class IdleParkerTests
{
    [Fact]
    public void IdleParkOptions_DefaultsToHadronRectangle()
    {
        var o = new IdleParkOptions();
        o.Enabled.Should().BeTrue();
        o.Region.Should().Be("Hadron");
        o.Corner1X.Should().Be(44);
        o.Corner1Y.Should().Be(73);
        o.Corner2X.Should().Be(30);
        o.Corner2Y.Should().Be(65);
        o.Z.Should().Be(25);
        o.ParkCooldownSeconds.Should().Be(180);
    }
}
```

- [ ] **Step 2: Run — expect COMPILE failure**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.IdleParkerTests"`
Expected: build error — `IdleParkOptions` does not exist.

- [ ] **Step 3: Create `IdleParkOptions`**

`bot/src/Slpa.Bot/Options/IdleParkOptions.cs`:

```csharp
namespace Slpa.Bot.Options;

public sealed class IdleParkOptions
{
    public const string SectionName = "IdlePark";

    /// <summary>Master switch; false disables without losing coords.</summary>
    public bool Enabled { get; set; } = true;

    /// <summary>Target region; blank while enabled disables (with a warning).</summary>
    public string Region { get; set; } = "Hadron";

    public double Corner1X { get; set; } = 44;
    public double Corner1Y { get; set; } = 73;
    public double Corner2X { get; set; } = 30;
    public double Corner2Y { get; set; } = 65;

    /// <summary>Landing altitude.</summary>
    public double Z { get; set; } = 25;

    /// <summary>
    /// Minimum interval between park teleport attempts; also the failed-park
    /// backoff. Default 3 minutes.
    /// </summary>
    public int ParkCooldownSeconds { get; set; } = 180;
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.IdleParkerTests"`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add bot/src/Slpa.Bot/Options/IdleParkOptions.cs bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs
git commit -m "feat(bot): add IdleParkOptions config"
```

---

## Task 3: `IdleParker` decision logic (TDD)

**Files:**
- Create: `bot/src/Slpa.Bot/Tasks/IdleParker.cs`
- Modify: `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs`

- [ ] **Step 1: Add the failing behaviour tests**

Replace the entire body of `bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs` with (keeps the defaults test, adds behaviour):

```csharp
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class IdleParkerTests
{
    private static IdleParkOptions Opts() => new();

    private static Func<double> Seq(params double[] xs)
    {
        var i = 0;
        return () => xs[Math.Min(i++, xs.Length - 1)];
    }

    private static IdleParker Make(
        FakeBotSession session, IdleParkOptions opts,
        Func<DateTimeOffset> now, Func<double> rng) =>
        new(session, opts, NullLogger<IdleParker>.Instance, now, rng);

    [Fact]
    public void IdleParkOptions_DefaultsToHadronRectangle()
    {
        var o = new IdleParkOptions();
        o.Enabled.Should().BeTrue();
        o.Region.Should().Be("Hadron");
        o.Corner1X.Should().Be(44);
        o.Corner1Y.Should().Be(73);
        o.Corner2X.Should().Be(30);
        o.Corner2Y.Should().Be(65);
        o.Z.Should().Be(25);
        o.ParkCooldownSeconds.Should().Be(180);
    }

    [Fact]
    public async Task Disabled_WhenEnabledFalse_NoTeleport()
    {
        var opts = Opts();
        opts.Enabled = false;
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Elsewhere", 1, 1) };
        var parker = Make(session, opts, () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().BeEmpty();
    }

    [Fact]
    public async Task Disabled_WhenRegionBlank_NoTeleport()
    {
        var opts = Opts();
        opts.Region = "  ";
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Elsewhere", 1, 1) };
        var parker = Make(session, opts, () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().BeEmpty();
    }

    [Fact]
    public async Task InRectangle_NoTeleport()
    {
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Hadron", 37, 69) };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().BeEmpty();
    }

    [Fact]
    public async Task DifferentRegion_TeleportsIntoRectangle_Forced()
    {
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Ahern", 50, 50) };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.Region.Should().Be("Hadron");
        call.ForceMove.Should().BeTrue();
        call.X.Should().BeInRange(30, 44);
        call.Y.Should().BeInRange(65, 73);
        call.Z.Should().Be(25);
    }

    [Fact]
    public async Task SameRegionOutsideRectangle_TeleportsForced()
    {
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Hadron", 200, 200) };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.ForceMove.Should().BeTrue();
        call.X.Should().BeInRange(30, 44);
        call.Y.Should().BeInRange(65, 73);
    }

    [Fact]
    public async Task CornerOrderIndependent_StillParksWithinBounds()
    {
        var opts = Opts();
        (opts.Corner1X, opts.Corner2X) = (opts.Corner2X, opts.Corner1X);
        (opts.Corner1Y, opts.Corner2Y) = (opts.Corner2Y, opts.Corner1Y);
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Ahern", 1, 1) };
        var parker = Make(session, opts, () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.X.Should().BeInRange(30, 44);
        call.Y.Should().BeInRange(65, 73);
    }

    [Fact]
    public async Task RandomPoint_AtExtremes_WithinBounds()
    {
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Ahern", 1, 1) };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, Seq(0.0, 1.0));

        await parker.ParkIfNeededAsync(default);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.X.Should().Be(30);   // minX + 0.0*(maxX-minX)
        call.Y.Should().Be(73);   // minY + 1.0*(maxY-minY)
    }

    [Fact]
    public async Task Cooldown_BlocksReattempt_ThenAllowsAfterExpiry()
    {
        var now = DateTimeOffset.UnixEpoch;
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Ahern", 1, 1) };
        var parker = Make(session, Opts(), () => now, () => 0.5);

        await parker.ParkIfNeededAsync(default);          // attempt 1
        session.TeleportCalls.Should().HaveCount(1);

        now = DateTimeOffset.UnixEpoch.AddSeconds(60);     // < 180s cooldown
        await parker.ParkIfNeededAsync(default);
        session.TeleportCalls.Should().HaveCount(1);       // blocked

        now = DateTimeOffset.UnixEpoch.AddSeconds(181);    // cooldown expired
        await parker.ParkIfNeededAsync(default);
        session.TeleportCalls.Should().HaveCount(2);       // allowed
    }

    [Fact]
    public async Task FailedTeleport_SetsCooldown()
    {
        var now = DateTimeOffset.UnixEpoch;
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Ahern", 1, 1),
            TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.RegionNotFound)
        };
        var parker = Make(session, Opts(), () => now, () => 0.5);

        await parker.ParkIfNeededAsync(default);
        await parker.ParkIfNeededAsync(default);           // same clock — in cooldown

        session.TeleportCalls.Should().HaveCount(1);
    }

    [Fact]
    public async Task CurrentLocationNull_SkipsGracefully()
    {
        var now = DateTimeOffset.UnixEpoch;
        var session = new FakeBotSession { CurrentLocation = null };
        var parker = Make(session, Opts(), () => now, () => 0.5);

        await parker.ParkIfNeededAsync(default);
        session.TeleportCalls.Should().BeEmpty();

        // No cooldown burned — once location appears it parks immediately.
        session.CurrentLocation = new BotLocation("Ahern", 1, 1);
        await parker.ParkIfNeededAsync(default);
        session.TeleportCalls.Should().HaveCount(1);
    }

    [Fact]
    public async Task Cancellation_Propagates()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Ahern", 1, 1),
            TeleportPolicy = _ => throw new OperationCanceledException()
        };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, () => 0.5);

        var act = async () => await parker.ParkIfNeededAsync(default);
        await act.Should().ThrowAsync<OperationCanceledException>();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE failure**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.IdleParkerTests"`
Expected: build error — `IdleParker` / `IIdleParker` do not exist.

- [ ] **Step 3: Implement `IdleParker`**

`bot/src/Slpa.Bot/Tasks/IdleParker.cs`:

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
/// Relocates an idle bot into the configured rectangle. Stateless except for
/// a cooldown timestamp: each idle cycle re-checks rectangle membership, so
/// being inside the rectangle is its own idempotency. Observation-only state
/// (heartbeat/activity) is deliberately NOT consulted here — "idle" is solely
/// "TaskLoop.ClaimAsync returned null".
/// </summary>
public sealed class IdleParker : IIdleParker
{
    private readonly IBotSession _session;
    private readonly IdleParkOptions _opts;
    private readonly ILogger<IdleParker> _log;
    private readonly Func<DateTimeOffset> _now;
    private readonly Func<double> _rng;

    private DateTimeOffset _nextParkUtc = DateTimeOffset.MinValue;
    private bool _warnedDisabled;

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

            var loc = _session.CurrentLocation;
            if (loc is null) return;

            double minX = Math.Min(_opts.Corner1X, _opts.Corner2X);
            double maxX = Math.Max(_opts.Corner1X, _opts.Corner2X);
            double minY = Math.Min(_opts.Corner1Y, _opts.Corner2Y);
            double maxY = Math.Max(_opts.Corner1Y, _opts.Corner2Y);

            bool inRegion = string.Equals(
                loc.Region, _opts.Region, StringComparison.OrdinalIgnoreCase);
            bool inRect = inRegion
                && loc.X >= minX && loc.X <= maxX
                && loc.Y >= minY && loc.Y <= maxY;
            if (inRect) return; // already parked — no teleport, no cooldown

            double x = minX + _rng() * (maxX - minX);
            double y = minY + _rng() * (maxY - minY);
            double z = _opts.Z;

            _nextParkUtc = now + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);

            var result = await _session
                .TeleportAsync(_opts.Region, x, y, z, ct, forceMove: true)
                .ConfigureAwait(false);

            if (result.Success)
            {
                _log.LogInformation(
                    "Idle-parked to {Region} ({X:F1},{Y:F1},{Z:F1})",
                    _opts.Region, x, y, z);
            }
            else
            {
                _log.LogWarning(
                    "Idle-park teleport to {Region} failed: {Failure}; " +
                    "backing off {Cooldown}s",
                    _opts.Region, result.Failure, _opts.ParkCooldownSeconds);
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _nextParkUtc = _now()
                + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);
            _log.LogWarning(ex,
                "Idle-park attempt threw; backing off {Cooldown}s",
                _opts.ParkCooldownSeconds);
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.IdleParkerTests"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add bot/src/Slpa.Bot/Tasks/IdleParker.cs bot/tests/Slpa.Bot.Tests/IdleParkerTests.cs
git commit -m "feat(bot): IdleParker rectangle-precise idle relocation"
```

---

## Task 4: `BotActivityState` (TDD)

**Files:**
- Create: `bot/src/Slpa.Bot/Tasks/BotActivityState.cs`
- Create: `bot/tests/Slpa.Bot.Tests/BotActivityStateTests.cs`

- [ ] **Step 1: Write the failing test**

`bot/tests/Slpa.Bot.Tests/BotActivityStateTests.cs`:

```csharp
using FluentAssertions;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class BotActivityStateTests
{
    private static BotTaskResponse Task1() => new(
        7, BotTaskType.MONITOR_AUCTION, BotTaskStatus.IN_PROGRESS,
        42, null, Guid.NewGuid(), "Hadron", 1, 2, 3,
        999, null, null, null, null, null, null,
        Guid.NewGuid(), null, null, null,
        DateTimeOffset.UnixEpoch, null);

    [Fact]
    public void RecordClaim_WithTask_SetsIdTypeAndLastClaim()
    {
        var s = new BotActivityState();
        var t = DateTimeOffset.UnixEpoch.AddMinutes(5);

        s.RecordClaim(Task1(), t);

        var snap = s.Current;
        snap.CurrentTaskId.Should().Be(7);
        snap.CurrentTaskType.Should().Be("MONITOR_AUCTION");
        snap.LastClaimAt.Should().Be(t);
    }

    [Fact]
    public void RecordClaim_WithNull_SetsLastClaim_ClearsTask()
    {
        var s = new BotActivityState();
        s.RecordClaim(Task1(), DateTimeOffset.UnixEpoch);

        var t = DateTimeOffset.UnixEpoch.AddMinutes(9);
        s.RecordClaim(null, t);

        var snap = s.Current;
        snap.CurrentTaskId.Should().BeNull();
        snap.CurrentTaskType.Should().BeNull();
        snap.LastClaimAt.Should().Be(t);
    }

    [Fact]
    public void Clear_NullsTask_KeepsLastClaim()
    {
        var s = new BotActivityState();
        var t = DateTimeOffset.UnixEpoch.AddMinutes(3);
        s.RecordClaim(Task1(), t);

        s.Clear();

        var snap = s.Current;
        snap.CurrentTaskId.Should().BeNull();
        snap.CurrentTaskType.Should().BeNull();
        snap.LastClaimAt.Should().Be(t);
    }

    [Fact]
    public async Task ConcurrentReadWrite_DoesNotThrow_AndEndsConsistent()
    {
        var s = new BotActivityState();
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));

        var writer = Task.Run(() =>
        {
            var n = 0;
            while (!cts.Token.IsCancellationRequested)
            {
                s.RecordClaim(Task1(), DateTimeOffset.UnixEpoch.AddSeconds(n++));
                s.Clear();
            }
        });
        var reader = Task.Run(() =>
        {
            while (!cts.Token.IsCancellationRequested)
            {
                _ = s.Current;
            }
        });

        await Task.WhenAll(writer, reader);
        s.Current.Should().NotBeNull();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE failure**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.BotActivityStateTests"`
Expected: build error — `BotActivityState` does not exist.

- [ ] **Step 3: Implement `BotActivityState`**

`bot/src/Slpa.Bot/Tasks/BotActivityState.cs`:

```csharp
using Slpa.Bot.Backend.Models;

namespace Slpa.Bot.Tasks;

/// <summary>
/// What the bot is currently doing, for the heartbeat. Single-writer
/// (TaskLoop), many-reader (HeartbeatLoop). Lock-free: an immutable snapshot
/// swapped atomically through a volatile reference.
///
/// REPORTING ONLY. Never read by IdleParker — the heartbeat must never
/// influence idle detection or the park decision (spec hard invariant).
/// </summary>
public sealed class BotActivityState
{
    public sealed record Snapshot(
        long? CurrentTaskId,
        string? CurrentTaskType,
        DateTimeOffset? LastClaimAt);

    private volatile Snapshot _snap = new(null, null, null);

    public Snapshot Current => _snap;

    /// <summary>
    /// Records a claim round. <paramref name="task"/> null = empty queue:
    /// updates LastClaimAt and clears the current task.
    /// </summary>
    public void RecordClaim(BotTaskResponse? task, DateTimeOffset now)
        => _snap = new Snapshot(task?.Id, task?.TaskType.ToString(), now);

    /// <summary>Task finished: clear task fields, keep LastClaimAt.</summary>
    public void Clear()
    {
        var s = _snap;
        _snap = new Snapshot(null, null, s.LastClaimAt);
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.BotActivityStateTests"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add bot/src/Slpa.Bot/Tasks/BotActivityState.cs bot/tests/Slpa.Bot.Tests/BotActivityStateTests.cs
git commit -m "feat(bot): BotActivityState snapshot for heartbeat"
```

---

## Task 5: `BotHeartbeatRequest` + `IBackendClient.SendHeartbeatAsync` (TDD)

**Files:**
- Create: `bot/src/Slpa.Bot/Backend/Models/BotHeartbeatRequest.cs`
- Modify: `bot/src/Slpa.Bot/Backend/IBackendClient.cs`
- Modify: `bot/src/Slpa.Bot/Backend/HttpBackendClient.cs`
- Modify: `bot/tests/Slpa.Bot.Tests/HttpBackendClientTests.cs`

- [ ] **Step 1: Write the failing tests**

In `bot/tests/Slpa.Bot.Tests/HttpBackendClientTests.cs`, add these two tests to the class:

```csharp
    [Fact]
    public async Task Heartbeat_200_ReturnsWithoutThrow()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/heartbeat").UsingPost()
                .WithHeader("Authorization", "Bearer test-secret-xxxxxxxx"))
            .RespondWith(Response.Create().WithStatusCode(200));

        await _client.SendHeartbeatAsync(
            new BotHeartbeatRequest(
                "SLPABot1 Resident",
                Guid.NewGuid().ToString(),
                "Online",
                "Hadron",
                "7",
                "MONITOR_AUCTION",
                DateTimeOffset.UnixEpoch),
            default);
    }

    [Fact]
    public async Task Heartbeat_401_ThrowsAuthConfigException()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/heartbeat").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(401));

        var act = async () => await _client.SendHeartbeatAsync(
            new BotHeartbeatRequest("w", "u", "Online", null, null, null, null),
            default);
        await act.Should().ThrowAsync<AuthConfigException>();
    }
```

- [ ] **Step 2: Run — expect COMPILE failure**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.HttpBackendClientTests"`
Expected: build error — `BotHeartbeatRequest` / `SendHeartbeatAsync` do not exist.

- [ ] **Step 3: Create `BotHeartbeatRequest`**

`bot/src/Slpa.Bot/Backend/Models/BotHeartbeatRequest.cs`:

```csharp
namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Mirrors the backend's BotHeartbeatRequest record. Serialized camelCase via
/// the shared JsonOpts. Backend requires workerName/slUuid/sessionState
/// non-blank; the rest are nullable.
/// </summary>
public sealed record BotHeartbeatRequest(
    string WorkerName,
    string SlUuid,
    string SessionState,
    string? CurrentRegion,
    string? CurrentTaskKey,
    string? CurrentTaskType,
    DateTimeOffset? LastClaimAt);
```

- [ ] **Step 4: Add to `IBackendClient`**

In `bot/src/Slpa.Bot/Backend/IBackendClient.cs`, add inside the interface after `PostMonitorAsync`:

```csharp
    /// <summary>
    /// Fire-and-forget heartbeat. Reuses the shared bearer auth + 5xx retry.
    /// Throws <see cref="AuthConfigException"/> on 401 (the caller swallows
    /// it — the claim path is the authoritative 401 handler).
    /// </summary>
    Task SendHeartbeatAsync(BotHeartbeatRequest body, CancellationToken ct);
```

- [ ] **Step 5: Implement in `HttpBackendClient`**

In `bot/src/Slpa.Bot/Backend/HttpBackendClient.cs`, add after `PostMonitorAsync`:

```csharp
    public async Task SendHeartbeatAsync(
        BotHeartbeatRequest body, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bot/heartbeat")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
    }
```

- [ ] **Step 6: Run — expect PASS**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.HttpBackendClientTests"`
Expected: PASS (existing + 2 new).

- [ ] **Step 7: Commit**

```bash
git add bot/src/Slpa.Bot/Backend/Models/BotHeartbeatRequest.cs bot/src/Slpa.Bot/Backend/IBackendClient.cs bot/src/Slpa.Bot/Backend/HttpBackendClient.cs bot/tests/Slpa.Bot.Tests/HttpBackendClientTests.cs
git commit -m "feat(bot): SendHeartbeatAsync backend client method"
```

---

## Task 6: `HeartbeatOptions`

**Files:**
- Create: `bot/src/Slpa.Bot/Options/HeartbeatOptions.cs`

- [ ] **Step 1: Create `HeartbeatOptions`** (trivial config; covered indirectly by Task 7 tests)

`bot/src/Slpa.Bot/Options/HeartbeatOptions.cs`:

```csharp
namespace Slpa.Bot.Options;

public sealed class HeartbeatOptions
{
    public const string SectionName = "Heartbeat";

    /// <summary>
    /// Send cadence. Backend TTL is 180s, so keep this well under 60s to
    /// guarantee 3 beats per TTL window.
    /// </summary>
    public int IntervalSeconds { get; set; } = 60;
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `dotnet build bot/Slpa.Bot.sln`
Expected: Build succeeded.

- [ ] **Step 3: Commit**

```bash
git add bot/src/Slpa.Bot/Options/HeartbeatOptions.cs
git commit -m "feat(bot): add HeartbeatOptions config"
```

---

## Task 7: `HeartbeatLoop` (TDD)

**Files:**
- Create: `bot/src/Slpa.Bot/Tasks/HeartbeatLoop.cs`
- Create: `bot/tests/Slpa.Bot.Tests/HeartbeatLoopTests.cs`

- [ ] **Step 1: Write the failing tests**

`bot/tests/Slpa.Bot.Tests/HeartbeatLoopTests.cs`:

```csharp
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class HeartbeatLoopTests
{
    private static HeartbeatLoop Make(
        FakeBotSession session, IBackendClient backend,
        BotActivityState activity, int intervalSeconds = 0)
    {
        var botOpts = Options.Create(new BotOptions { Username = "SLPABot1 Resident" });
        var hbOpts = Options.Create(new HeartbeatOptions { IntervalSeconds = intervalSeconds });
        return new HeartbeatLoop(session, backend, activity, botOpts, hbOpts,
            NullLogger<HeartbeatLoop>.Instance);
    }

    [Fact]
    public async Task SendOnce_BuildsRequestFromSessionAndActivity()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Hadron", 31, 66)
        };
        session.SimulateLoginSuccess(); // Online
        var activity = new BotActivityState();
        activity.RecordClaim(
            new BotTaskResponse(
                7, BotTaskType.MONITOR_AUCTION, BotTaskStatus.IN_PROGRESS,
                42, null, Guid.NewGuid(), "Hadron", 1, 2, 3, 999,
                null, null, null, null, null, null, Guid.NewGuid(),
                null, null, null, DateTimeOffset.UnixEpoch, null),
            DateTimeOffset.UnixEpoch.AddMinutes(2));

        BotHeartbeatRequest? captured = null;
        var backend = new Mock<IBackendClient>();
        backend.Setup(b => b.SendHeartbeatAsync(
                It.IsAny<BotHeartbeatRequest>(), It.IsAny<CancellationToken>()))
            .Callback<BotHeartbeatRequest, CancellationToken>((r, _) => captured = r)
            .Returns(Task.CompletedTask);

        var loop = Make(session, backend.Object, activity);
        await loop.SendOnceAsync(default);

        captured.Should().NotBeNull();
        captured!.WorkerName.Should().Be("SLPABot1 Resident");
        captured.SlUuid.Should().Be(session.BotUuid.ToString());
        captured.SessionState.Should().Be("Online");
        captured.CurrentRegion.Should().Be("Hadron");
        captured.CurrentTaskKey.Should().Be("7");
        captured.CurrentTaskType.Should().Be("MONITOR_AUCTION");
        captured.LastClaimAt.Should().Be(DateTimeOffset.UnixEpoch.AddMinutes(2));
    }

    [Fact]
    public async Task SendOnce_WhenNotOnline_StillSends_WithState()
    {
        var session = new FakeBotSession(); // Starting, no location
        var backend = new Mock<IBackendClient>();
        BotHeartbeatRequest? captured = null;
        backend.Setup(b => b.SendHeartbeatAsync(
                It.IsAny<BotHeartbeatRequest>(), It.IsAny<CancellationToken>()))
            .Callback<BotHeartbeatRequest, CancellationToken>((r, _) => captured = r)
            .Returns(Task.CompletedTask);

        var loop = Make(session, backend.Object, new BotActivityState());
        await loop.SendOnceAsync(default);

        captured!.SessionState.Should().Be("Starting");
        captured.CurrentRegion.Should().BeNull();
    }

    [Fact]
    public async Task Run_SendsHeartbeat_AtLeastOnce()
    {
        var session = new FakeBotSession();
        var backend = new Mock<IBackendClient>();
        backend.Setup(b => b.SendHeartbeatAsync(
                It.IsAny<BotHeartbeatRequest>(), It.IsAny<CancellationToken>()))
            .Returns(Task.CompletedTask);

        var loop = Make(session, backend.Object, new BotActivityState());
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));
        await loop.RunAsync(cts.Token);

        backend.Verify(b => b.SendHeartbeatAsync(
            It.IsAny<BotHeartbeatRequest>(), It.IsAny<CancellationToken>()),
            Times.AtLeastOnce);
    }

    [Fact]
    public async Task Run_SwallowsBackendException_ContinuesLooping()
    {
        var session = new FakeBotSession();
        var backend = new Mock<IBackendClient>();
        backend.Setup(b => b.SendHeartbeatAsync(
                It.IsAny<BotHeartbeatRequest>(), It.IsAny<CancellationToken>()))
            .ThrowsAsync(new AuthConfigException("401"));

        var loop = Make(session, backend.Object, new BotActivityState());
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));

        var act = async () => await loop.RunAsync(cts.Token);
        await act.Should().NotThrowAsync();
        backend.Verify(b => b.SendHeartbeatAsync(
            It.IsAny<BotHeartbeatRequest>(), It.IsAny<CancellationToken>()),
            Times.AtLeastOnce);
    }

    [Fact]
    public async Task Run_PreCancelled_StopsCleanly()
    {
        var session = new FakeBotSession();
        var backend = new Mock<IBackendClient>();
        var loop = Make(session, backend.Object, new BotActivityState());
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        var act = async () => await loop.RunAsync(cts.Token);
        await act.Should().NotThrowAsync();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE failure**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.HeartbeatLoopTests"`
Expected: build error — `HeartbeatLoop` does not exist.

- [ ] **Step 3: Implement `HeartbeatLoop`**

`bot/src/Slpa.Bot/Tasks/HeartbeatLoop.cs`:

```csharp
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Sends a heartbeat every <see cref="HeartbeatOptions.IntervalSeconds"/>.
/// Runs regardless of session state (so the admin can tell "alive but
/// Reconnecting" from "process dead = TTL expired"). Never crashes — every
/// failure incl. <see cref="AuthConfigException"/> is logged and swallowed;
/// the claim path remains the authoritative 401 handler.
/// </summary>
public sealed class HeartbeatLoop : BackgroundService
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly BotActivityState _activity;
    private readonly BotOptions _botOpts;
    private readonly HeartbeatOptions _hbOpts;
    private readonly ILogger<HeartbeatLoop> _log;

    public HeartbeatLoop(
        IBotSession session,
        IBackendClient backend,
        BotActivityState activity,
        IOptions<BotOptions> botOpts,
        IOptions<HeartbeatOptions> hbOpts,
        ILogger<HeartbeatLoop> log)
    {
        _session = session;
        _backend = backend;
        _activity = activity;
        _botOpts = botOpts.Value;
        _hbOpts = hbOpts.Value;
        _log = log;
    }

    protected override Task ExecuteAsync(CancellationToken ct) => RunAsync(ct);

    internal async Task RunAsync(CancellationToken ct)
    {
        var interval = TimeSpan.FromSeconds(_hbOpts.IntervalSeconds);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await SendOnceAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex,
                    "Heartbeat send failed; retrying next interval");
            }

            try
            {
                await Task.Delay(interval, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    internal async Task SendOnceAsync(CancellationToken ct)
    {
        var snap = _activity.Current;
        var loc = _session.CurrentLocation;
        var req = new BotHeartbeatRequest(
            WorkerName: _botOpts.Username,
            SlUuid: _session.BotUuid.ToString(),
            SessionState: _session.State.ToString(),
            CurrentRegion: loc?.Region,
            CurrentTaskKey: snap.CurrentTaskId?.ToString(),
            CurrentTaskType: snap.CurrentTaskType,
            LastClaimAt: snap.LastClaimAt);
        await _backend.SendHeartbeatAsync(req, ct).ConfigureAwait(false);
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.HeartbeatLoopTests"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add bot/src/Slpa.Bot/Tasks/HeartbeatLoop.cs bot/tests/Slpa.Bot.Tests/HeartbeatLoopTests.cs
git commit -m "feat(bot): HeartbeatLoop background service"
```

---

## Task 8: Wire into `TaskLoop` (park on idle + activity tracking)

**Files:**
- Modify: `bot/src/Slpa.Bot/Tasks/TaskLoop.cs`
- Modify: `bot/tests/Slpa.Bot.Tests/TaskLoopTests.cs`

- [ ] **Step 1: Update existing tests for the new ctor + add new tests**

In `bot/tests/Slpa.Bot.Tests/TaskLoopTests.cs`, add usings at the top (after existing usings):

```csharp
using Microsoft.Extensions.Logging.Abstractions;
```

(If already present, skip.) The three existing `new TaskLoop(...)` calls use the test-friendly ctor. Update each of the three call sites — change:

```csharp
        var loop = new TaskLoop(session, backend.Object,
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);
```

to (note the two new args after `backend.Object`):

```csharp
        var loop = new TaskLoop(session, backend.Object,
            Mock.Of<IIdleParker>(), new BotActivityState(),
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);
```

Add these two new tests to the `TaskLoopTests` class:

```csharp
    [Fact]
    public async Task EmptyQueue_InvokesIdleParker()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        var backend = new Mock<IBackendClient>();
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync((BotTaskResponse?)null);
        var parker = new Mock<IIdleParker>();

        var loop = new TaskLoop(session, backend.Object,
            parker.Object, new BotActivityState(),
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        parker.Verify(p => p.ParkIfNeededAsync(It.IsAny<CancellationToken>()),
            Times.AtLeastOnce);
    }

    [Fact]
    public async Task ClaimedTask_RecordsActivity_ClearedAfterDispatch()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        var backend = new Mock<IBackendClient>();
        var claims = 0;
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync(() =>
               {
                   claims++;
                   return claims == 1 ? MakeVerifyTask() : null;
               });
        var activity = new BotActivityState();

        var loop = new TaskLoop(session, backend.Object,
            Mock.Of<IIdleParker>(), activity,
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        var snap = activity.Current;
        snap.LastClaimAt.Should().NotBeNull();   // a claim happened
        snap.CurrentTaskId.Should().BeNull();    // cleared in finally
    }
```

- [ ] **Step 2: Run — expect COMPILE failure**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.TaskLoopTests"`
Expected: build error — the test-friendly `TaskLoop` ctor has no `IIdleParker`/`BotActivityState` params.

- [ ] **Step 3: Update `TaskLoop`**

In `bot/src/Slpa.Bot/Tasks/TaskLoop.cs`:

Add two fields after `private readonly IBackendClient _backend;`:

```csharp
    private readonly IIdleParker _idleParker;
    private readonly BotActivityState _activity;
```

Replace the production ctor:

```csharp
    public TaskLoop(
        IBotSession session,
        IBackendClient backend,
        VerifyHandler verify,
        MonitorHandler monitor,
        WithdrawGroupHandler withdrawGroup,
        ILogger<TaskLoop> log)
        : this(session, backend, () => verify, () => monitor, () => withdrawGroup, log)
    {
    }
```

with:

```csharp
    public TaskLoop(
        IBotSession session,
        IBackendClient backend,
        IIdleParker idleParker,
        BotActivityState activity,
        VerifyHandler verify,
        MonitorHandler monitor,
        WithdrawGroupHandler withdrawGroup,
        ILogger<TaskLoop> log)
        : this(session, backend, idleParker, activity,
            () => verify, () => monitor, () => withdrawGroup, log)
    {
    }
```

Replace the test-friendly ctor signature + body:

```csharp
    internal TaskLoop(
        IBotSession session,
        IBackendClient backend,
        Func<VerifyHandler> verify,
        Func<MonitorHandler> monitor,
        Func<WithdrawGroupHandler> withdrawGroup,
        ILogger<TaskLoop> log)
    {
        _session = session;
        _backend = backend;
        _verify = verify;
        _monitor = monitor;
        _withdrawGroup = withdrawGroup;
        _log = log;
    }
```

with:

```csharp
    internal TaskLoop(
        IBotSession session,
        IBackendClient backend,
        IIdleParker idleParker,
        BotActivityState activity,
        Func<VerifyHandler> verify,
        Func<MonitorHandler> monitor,
        Func<WithdrawGroupHandler> withdrawGroup,
        ILogger<TaskLoop> log)
    {
        _session = session;
        _backend = backend;
        _idleParker = idleParker;
        _activity = activity;
        _verify = verify;
        _monitor = monitor;
        _withdrawGroup = withdrawGroup;
        _log = log;
    }
```

In `RunAsync`, replace this block:

```csharp
            if (task is null)
            {
                await SafeDelayAsync(EmptyQueueBackoff, ct).ConfigureAwait(false);
                continue;
            }

            try
            {
                await DispatchAsync(task, ct).ConfigureAwait(false);
            }
```

with:

```csharp
            _activity.RecordClaim(task, DateTimeOffset.UtcNow);

            if (task is null)
            {
                await _idleParker.ParkIfNeededAsync(ct).ConfigureAwait(false);
                await SafeDelayAsync(EmptyQueueBackoff, ct).ConfigureAwait(false);
                continue;
            }

            try
            {
                await DispatchAsync(task, ct).ConfigureAwait(false);
            }
            finally
            {
                _activity.Clear();
            }
```

Note: `RecordClaim` is called for both the null and non-null cases (it updates `LastClaimAt` and sets/clears the task). `_idleParker.ParkIfNeededAsync` may throw `OperationCanceledException` on shutdown — it propagates to the existing `catch (OperationCanceledException) { return; }` in `RunAsync`, which is correct. The existing `catch (SessionLostException)` / `catch (Exception)` blocks around `DispatchAsync` remain; the new `finally` runs before they handle the exception, so activity is always cleared.

- [ ] **Step 4: Run — expect PASS**

Run: `dotnet test bot/Slpa.Bot.sln --filter "FullyQualifiedName~Slpa.Bot.Tests.TaskLoopTests"`
Expected: PASS (existing 3 + 2 new = 5).

- [ ] **Step 5: Run the full suite**

Run: `dotnet test bot/Slpa.Bot.sln`
Expected: PASS (all).

- [ ] **Step 6: Commit**

```bash
git add bot/src/Slpa.Bot/Tasks/TaskLoop.cs bot/tests/Slpa.Bot.Tests/TaskLoopTests.cs
git commit -m "feat(bot): park on idle + record activity in TaskLoop"
```

---

## Task 9: DI wiring + appsettings

**Files:**
- Modify: `bot/src/Slpa.Bot/Program.cs`
- Modify: `bot/src/Slpa.Bot/appsettings.json`

- [ ] **Step 1: Update `Program.cs`**

In `bot/src/Slpa.Bot/Program.cs`, after the existing `Configure<RateLimitOptions>` line, add:

```csharp
builder.Services.Configure<IdleParkOptions>(
    builder.Configuration.GetSection(IdleParkOptions.SectionName));
builder.Services.Configure<HeartbeatOptions>(
    builder.Configuration.GetSection(HeartbeatOptions.SectionName));
```

After `builder.Services.AddSingleton<IBotSession, LibreMetaverseBotSession>();` add:

```csharp
builder.Services.AddSingleton<IIdleParker, IdleParker>();
builder.Services.AddSingleton<BotActivityState>();
```

After `builder.Services.AddHostedService<TaskLoop>();` add:

```csharp
builder.Services.AddHostedService<HeartbeatLoop>();
```

Ensure the `using Slpa.Bot.Tasks;` and `using Slpa.Bot.Options;` directives are present (they already are — `TaskLoop` and `BotOptions` are used). No new using needed (`IIdleParker`, `IdleParker`, `BotActivityState`, `HeartbeatLoop` are all in `Slpa.Bot.Tasks`; `IdleParkOptions`, `HeartbeatOptions` in `Slpa.Bot.Options`).

- [ ] **Step 2: Update `appsettings.json`**

In `bot/src/Slpa.Bot/appsettings.json`, replace:

```json
  "RateLimit": {
    "TeleportsPerMinute": 6
  }
}
```

with:

```json
  "RateLimit": {
    "TeleportsPerMinute": 6
  },
  "IdlePark": {
    "Enabled": true,
    "Region": "Hadron",
    "Corner1X": 44,
    "Corner1Y": 73,
    "Corner2X": 30,
    "Corner2Y": 65,
    "Z": 25,
    "ParkCooldownSeconds": 180
  },
  "Heartbeat": {
    "IntervalSeconds": 60
  }
}
```

- [ ] **Step 3: Build + full suite**

Run: `dotnet build bot/Slpa.Bot.sln` then `dotnet test bot/Slpa.Bot.sln`
Expected: Build succeeded; all tests PASS.

- [ ] **Step 4: Commit**

```bash
git add bot/src/Slpa.Bot/Program.cs bot/src/Slpa.Bot/appsettings.json
git commit -m "feat(bot): wire idle-park + heartbeat into DI and appsettings"
```

---

## Task 10: Docs — `bot/README.md` + root `README.md`

**Files:**
- Modify: `bot/README.md`
- Modify: `README.md`

- [ ] **Step 1: Update the `bot/README.md` env table**

In `bot/README.md`, the env table currently ends with the `RateLimit__TeleportsPerMinute` row. Add these rows immediately after it (keep the existing column alignment style):

```
| `IdlePark__Enabled`             | no       | `true`                                 | master switch; `false` disables, keeps coords |
| `IdlePark__Region`              | no       | `Hadron`                               | idle home region; blank disables (warns)   |
| `IdlePark__Corner1X`            | no       | `44`                                   | rectangle corner 1 X                       |
| `IdlePark__Corner1Y`            | no       | `73`                                   | rectangle corner 1 Y                       |
| `IdlePark__Corner2X`            | no       | `30`                                   | opposite corner X                          |
| `IdlePark__Corner2Y`            | no       | `65`                                   | opposite corner Y                          |
| `IdlePark__Z`                   | no       | `25`                                   | landing altitude                           |
| `IdlePark__ParkCooldownSeconds` | no       | `180`                                  | min interval between park attempts         |
| `Heartbeat__IntervalSeconds`    | no       | `60`                                   | heartbeat cadence; backend TTL is 180s     |
```

Then, immediately after the table (before the "ASP.NET uses `__`..." paragraph), add a short note:

```
Idle-parking is on by default with the Hadron rectangle baked in. When a bot
has no task to service and is outside the configured rectangle, it teleports
to a random point inside it. The heartbeat runs independently of the task
loop and of session state, so a logged-in bot always appears in the admin
Bot-pool panel.
```

- [ ] **Step 2: Add manual smoke-test steps to `bot/README.md`**

In the "Manual smoke test" numbered list, append:

```
8. Leave the bot with an empty task queue for ~1 min. Confirm it teleports
   into the configured rectangle (region `Hadron`, X 30-44, Y 65-73). An
   idle bot already inside the rectangle stays put (no teleport churn).
9. Open the admin Infrastructure page → Bot pool. Within ~60 s the bot shows
   as `1/1 healthy` with its region. Kill the bot process; after ~180 s the
   row flips red (Redis TTL lapsed).
```

- [ ] **Step 3: Sweep root `README.md`**

Run: `grep -n -i "bot" README.md` to locate the bot section. In the section that describes the bot worker's responsibilities (it lists "Method C verification, BOT-tier ownership/escrow monitoring" — same wording as `CLAUDE.md`), append idle-park + heartbeat to that capability sentence so it reads, e.g.:

> ... services backend tasks (Method C verification, BOT-tier ownership/escrow monitoring), parks at a configurable idle location when no task is due, and sends heartbeats so the admin Bot-pool panel reflects logged-in bots.

Match the existing sentence structure exactly; only extend the capability list. If the README has no such bot sentence, add a one-line bullet under the bot/worker description.

- [ ] **Step 4: Verify docs build nothing — just commit**

```bash
git add bot/README.md README.md
git commit -m "docs(bot): document idle-park + heartbeat config and smoke steps"
```

---

## Task 11: Mirror heartbeat into the SLPA Postman collection

**Files:** none in-repo (Postman MCP).

The manual-test surface rule (`CLAUDE.md`) requires backend endpoints in the
SLPA Postman collection. `POST /api/v1/bot/heartbeat` pre-exists on the
backend but the bot never called it, so it may be absent from the collection.

- [ ] **Step 1: Check whether the request already exists**

Use the postman MCP to fetch the SLPA collection (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`) and search its items for a request whose path is `/api/v1/bot/heartbeat`.

- [ ] **Step 2: If missing, add it**

Add a request to the same folder as the other `Bot` requests (alongside `bot/tasks/claim`):
- Method: `POST`
- URL: `{{baseUrl}}/api/v1/bot/heartbeat`
- Auth/header: `Authorization: Bearer {{botSharedSecret}}` (match the header variable the existing `bot/tasks/claim` request uses — reuse that exact variable name, do not invent one)
- Body (raw JSON):

```json
{
  "workerName": "SLPABot1 Resident",
  "slUuid": "{{botUuid}}",
  "sessionState": "Online",
  "currentRegion": "Hadron",
  "currentTaskKey": null,
  "currentTaskType": null,
  "lastClaimAt": null
}
```

Use whatever environment variable the collection already defines for the bot
UUID (inspect the `bot/tasks/claim` request body — it threads a bot UUID
variable; reuse that exact name). Do not add new environment variables.

- [ ] **Step 3: No commit** (Postman is external state). Note completion in the task summary.

---

## Final Verification

- [ ] **Step 1: Full build + test**

Run: `dotnet build bot/Slpa.Bot.sln` then `dotnet test bot/Slpa.Bot.sln`
Expected: Build succeeded; ALL tests PASS (existing + new across all classes).

- [ ] **Step 2: Confirm no stray files staged across the work**

Run: `git status --porcelain`
Expected: clean tree (everything committed). `docs/cacheditems*.md` remain untracked and unstaged. Nothing under `.scratch/` staged.

- [ ] **Step 3: Spec cross-check**

Re-read `docs/superpowers/specs/2026-05-15-bot-idle-park-and-heartbeat-design.md` and confirm every requirement maps to a committed task (use the Self-Review mapping below).

---

## Self-Review (plan vs spec)

**Spec coverage:**
- Part A trigger/control flow → Task 8 (`TaskLoop` empty-claim branch + park before backoff; OCE propagates).
- Part A stateless rectangle check, no latch → Task 3 (`InRectangle_NoTeleport`, no cooldown on in-rect).
- Part A cooldown semantics (gates attempts, not the membership check; failure backoff) → Task 3 (`Cooldown_*`, `FailedTeleport_SetsCooldown`).
- Part A config + corner-order independence + defaults + disable → Tasks 2, 3 (`CornerOrderIndependent`, `Disabled_*`), Task 9 (appsettings).
- Part A `IBotSession` `CurrentLocation` + `forceMove` → Task 1.
- Part A components (`IIdleParker`/`IdleParker`, `Program.cs`) → Tasks 3, 9.
- Part B root cause / `SendHeartbeatAsync` → Task 5.
- Part B `HeartbeatOptions` → Task 6.
- Part B `BotActivityState` → Task 4.
- Part B `HeartbeatLoop` runs regardless of session state, swallows all failures → Task 7 (`SendOnce_WhenNotOnline_StillSends`, `Run_SwallowsBackendException`).
- Part B `TaskLoop` touch (RecordClaim/Clear) → Task 8.
- Part B DI → Task 9.
- Hard invariant (heartbeat never feeds idle/park/cooldown) → enforced by construction: `IdleParker` (Task 3) has no `BotActivityState`/heartbeat dependency; documented in both class XML docs (Tasks 3, 4).
- Testing strategy → Tasks 1,3,4,5,7,8 cover every listed test.
- Docs (bot/README, root README, Postman) → Tasks 10, 11.

**Placeholder scan:** none — every code/step is concrete.

**Type consistency check:** `IIdleParker.ParkIfNeededAsync(CancellationToken)` (Tasks 3, 8 match). `BotActivityState.RecordClaim(BotTaskResponse?, DateTimeOffset)` / `Clear()` / `Current` / `Snapshot(long? CurrentTaskId, string? CurrentTaskType, DateTimeOffset? LastClaimAt)` (Tasks 4, 7, 8 match). `BotHeartbeatRequest(WorkerName, SlUuid, SessionState, CurrentRegion?, CurrentTaskKey?, CurrentTaskType?, LastClaimAt?)` (Tasks 5, 7 match). `TeleportAsync(string, double, double, double, CancellationToken, bool forceMove=false)` (Tasks 1, 3, FakeBotSession match). `BotLocation(string Region, double X, double Y)` (Tasks 1, 3, 7 match). `TaskLoop` production ctor arg order `(session, backend, idleParker, activity, verify, monitor, withdrawGroup, log)` and test ctor `(session, backend, idleParker, activity, Func<verify>, Func<monitor>, Func<withdrawGroup>, log)` (Task 8 consistent across all call sites). Consistent.
