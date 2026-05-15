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
}

/// <summary>In-test fake. Mirrors the real session's state machine.</summary>
public sealed class FakeBotSession : IBotSession
{
    public SessionState State { get; private set; } = SessionState.Starting;
    public Guid BotUuid { get; } = Guid.NewGuid();

    public Func<string, TeleportResult> TeleportPolicy { get; set; } =
        _ => TeleportResult.Ok();

    public BotLocation? CurrentLocation { get; set; }

    /// <summary>Every <see cref="TeleportAsync"/> call, for assertions.</summary>
    public List<TeleportCall> TeleportCalls { get; } = new();

    public Func<double, double, ParcelSnapshot?> ReadPolicy { get; set; } =
        (_, _) => null;

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

    public Task<ParcelSnapshot?> ReadParcelAsync(
        double x, double y, CancellationToken ct)
        => Task.FromResult(ReadPolicy(x, y));

    public void GiveGroupMoney(Guid slGroupUuid, int amountL, string memo)
        => GiveGroupMoneyCalls.Add(new GiveGroupMoneyCall(slGroupUuid, amountL, memo));

    public void SimulateLoginSuccess() => State = SessionState.Online;
    public void SimulateDisconnect() => State = SessionState.Reconnecting;
}

/// <summary>Argument capture for <see cref="FakeBotSession.GiveGroupMoney"/>.</summary>
public sealed record GiveGroupMoneyCall(Guid GroupUuid, int AmountL, string Memo);

/// <summary>Argument capture for <see cref="FakeBotSession.TeleportAsync"/>.</summary>
public sealed record TeleportCall(string Region, double X, double Y, double Z, bool ForceMove);
