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
}

/// <summary>In-test fake. Mirrors the real session's state machine.</summary>
public sealed class FakeBotSession : IBotSession
{
    public SessionState State { get; private set; } = SessionState.Starting;
    public Guid BotUuid { get; } = Guid.NewGuid();

    public Func<string, TeleportResult> TeleportPolicy { get; set; } =
        _ => TeleportResult.Ok();

    public Func<double, double, ParcelSnapshot?> ReadPolicy { get; set; } =
        (_, _) => null;

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
        string regionName, double x, double y, double z, CancellationToken ct)
        => Task.FromResult(TeleportPolicy(regionName));

    public Task<ParcelSnapshot?> ReadParcelAsync(
        double x, double y, CancellationToken ct)
        => Task.FromResult(ReadPolicy(x, y));

    public void SimulateLoginSuccess() => State = SessionState.Online;
    public void SimulateDisconnect() => State = SessionState.Reconnecting;
}
