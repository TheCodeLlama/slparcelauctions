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
    // intervalSeconds = 0 -> Task.Delay(Zero) -> tight loop; the RunAsync
    // tests bound it with a short CancellationTokenSource timeout instead.
    private static HeartbeatLoop Make(
        FakeBotSession session, IBackendClient backend,
        BotActivityState activity, int intervalSeconds = 0)
    {
        var botOpts = Microsoft.Extensions.Options.Options.Create(new BotOptions { Username = "SLPABot1 Resident" });
        var hbOpts = Microsoft.Extensions.Options.Options.Create(new HeartbeatOptions { IntervalSeconds = intervalSeconds });
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
