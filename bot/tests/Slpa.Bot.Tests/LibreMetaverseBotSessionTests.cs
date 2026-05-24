using FluentAssertions;
using Slpa.Bot.Sl;
using Xunit;

namespace Slpa.Bot.Tests;

/// <summary>
/// Behavioural tests for the session state machine through the
/// <see cref="IBotSession"/> interface. LibreMetaverse internals are never
/// touched — the real session is covered by manual smoke tests documented
/// in bot/README.md.
/// </summary>
public sealed class LibreMetaverseBotSessionTests
{
    [Fact]
    public void FakeSession_DefaultsToStarting()
    {
        IBotSession session = new FakeBotSession();
        session.State.Should().Be(SessionState.Starting);
    }

    [Fact]
    public async Task FakeSession_TransitionsToOnlineAfterStart()
    {
        var session = new FakeBotSession();
        await session.StartAsync(CancellationToken.None);
        ((FakeBotSession)session).SimulateLoginSuccess();
        session.State.Should().Be(SessionState.Online);
    }

    [Fact]
    public async Task FakeSession_TransitionsToReconnectingOnDisconnect()
    {
        var session = new FakeBotSession();
        await session.StartAsync(CancellationToken.None);
        session.SimulateLoginSuccess();
        session.SimulateDisconnect();
        ((IBotSession)session).State.Should().Be(SessionState.Reconnecting);
    }

    [Fact]
    public async Task FakeSession_LogoutTransitionsToStopped()
    {
        var session = new FakeBotSession();
        await session.StartAsync(CancellationToken.None);
        session.SimulateLoginSuccess();
        await session.LogoutAsync(CancellationToken.None);
        session.State.Should().Be(SessionState.Stopped);
    }

    [Fact]
    public async Task FakeSession_CapturesForcedTeleportCall()
    {
        var session = new FakeBotSession();

        await session.TeleportAsync("Hadron", 31, 66, 25, default, forceMove: true);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.Region.Should().Be("Hadron");
        call.X.Should().Be(31);
        call.Y.Should().Be(66);
        call.Z.Should().Be(25);
        call.ForceMove.Should().BeTrue();
    }

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
}

/// <summary>In-test fake. Mirrors the real session's state machine.</summary>
public sealed class FakeBotSession : IBotSession
{
    public SessionState State { get; private set; } = SessionState.Starting;
    public Guid BotUuid { get; } = Guid.NewGuid();

    /// <summary>
    /// Ordered log of method names called on this session, for call-order
    /// assertions in handler tests (e.g. WaitForRegionTerrain before
    /// GetRegionTerrainHeights).
    /// </summary>
    public List<string> CallLog { get; } = new();

    public Func<string, TeleportResult> TeleportPolicy { get; set; } =
        _ => TeleportResult.Ok();

    public BotLocation? CurrentLocation { get; set; }

    /// <summary>Every <see cref="TeleportAsync"/> call, for assertions.</summary>
    public List<TeleportCall> TeleportCalls { get; } = new();

    public bool IsSeated { get; set; }

    public Func<Guid, SitResult> SitPolicy { get; set; } = _ => SitResult.Ok();

    /// <summary>Every <see cref="SitAsync"/> call, for assertions.</summary>
    public List<SitCall> SitCalls { get; } = new();

    public Func<double, double, ParcelSnapshot?> ReadPolicy { get; set; } =
        (_, _) => null;

    /// <summary>
    /// Policy for <see cref="RequestAllSimParcelsAsync"/>. Returns the
    /// LocalID (nullable) for the given coords. Defaults to returning null.
    /// </summary>
    public Func<double, double, int?> RequestAllSimParcelsPolicy { get; set; } =
        (_, _) => null;

    /// <summary>Policy for <see cref="GetRegionParcelLocalIds"/>. Defaults to a 64x64 all-zero grid.</summary>
    public Func<uint[,]> ParcelLocalIdsPolicy { get; set; } =
        () => new uint[64, 64];

    /// <summary>
    /// Policy for the float[,] returned by <see cref="GetRegionTerrainHeights"/>.
    /// Defaults to a 64x64 all-zero grid.
    /// </summary>
    public Func<float[,]> TerrainHeightsPolicy { get; set; } =
        () => new float[64, 64];

    /// <summary>
    /// Policy for the bool[,] loaded-flag returned by
    /// <see cref="GetRegionTerrainHeights"/>. Defaults to a 64x64 all-true grid
    /// (all cells loaded) so existing tests continue to pass without change.
    /// Set individual cells to false to simulate partially loaded terrain.
    /// </summary>
    public Func<bool[,]> TerrainHeightsLoadedPolicy { get; set; } = () =>
    {
        var loaded = new bool[64, 64];
        for (int r = 0; r < 64; r++)
            for (int c = 0; c < 64; c++)
                loaded[r, c] = true;
        return loaded;
    };

    /// <summary>
    /// Policy for <see cref="WaitForRegionTerrainAsync"/>. Defaults to
    /// returning 256 (all patches received) immediately.
    /// </summary>
    public Func<CancellationToken, Task<int>> WaitForRegionTerrainPolicy { get; set; } =
        _ => Task.FromResult(256);

    /// <summary>
    /// Captures every <see cref="GiveGroupMoney"/> call so handler tests can
    /// assert on recipient / amount / memo without touching LibreMetaverse.
    /// </summary>
    public List<GiveGroupMoneyCall> GiveGroupMoneyCalls { get; } = new();

    public Task StartAsync(CancellationToken ct)
    {
        State = SessionState.Starting;
        return Task.CompletedTask;
    }
    public Task LogoutAsync(CancellationToken ct)
    {
        State = SessionState.Stopped;
        return Task.CompletedTask;
    }
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;

    public Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z,
        CancellationToken ct, bool forceMove = false)
    {
        TeleportCalls.Add(new TeleportCall(regionName, x, y, z, forceMove));
        return Task.FromResult(TeleportPolicy(regionName));
    }

    public Task<SitResult> SitAsync(Guid chairUuid, CancellationToken ct)
    {
        SitCalls.Add(new SitCall(chairUuid));
        return Task.FromResult(SitPolicy(chairUuid));
    }

    public Task<ParcelSnapshot?> ReadParcelAsync(
        double x, double y, CancellationToken ct)
        => Task.FromResult(ReadPolicy(x, y));

    public Task<int?> RequestAllSimParcelsAsync(double x, double y, CancellationToken ct)
        => Task.FromResult(RequestAllSimParcelsPolicy(x, y));

    public uint[,] GetRegionParcelLocalIds() => ParcelLocalIdsPolicy();

    public RegionTerrainHeights GetRegionTerrainHeights()
    {
        CallLog.Add(nameof(GetRegionTerrainHeights));
        return new(TerrainHeightsPolicy(), TerrainHeightsLoadedPolicy());
    }

    public Task<int> WaitForRegionTerrainAsync(CancellationToken ct)
    {
        CallLog.Add(nameof(WaitForRegionTerrainAsync));
        return WaitForRegionTerrainPolicy(ct);
    }

    public void GiveGroupMoney(Guid slGroupUuid, int amountL, string memo)
        => GiveGroupMoneyCalls.Add(new GiveGroupMoneyCall(slGroupUuid, amountL, memo));

    public void SimulateLoginSuccess() => State = SessionState.Online;
    public void SimulateDisconnect() => State = SessionState.Reconnecting;
}

/// <summary>Argument capture for <see cref="FakeBotSession.GiveGroupMoney"/>.</summary>
public sealed record GiveGroupMoneyCall(Guid GroupUuid, int AmountL, string Memo);

/// <summary>Argument capture for <see cref="FakeBotSession.TeleportAsync"/>.</summary>
public sealed record TeleportCall(string Region, double X, double Y, double Z, bool ForceMove);

/// <summary>Argument capture for <see cref="FakeBotSession.SitAsync"/>.</summary>
public sealed record SitCall(Guid ChairUuid);
