using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class TaskLoopTests
{
    [Fact]
    public async Task SessionOffline_DoesNotClaim()
    {
        var session = new FakeBotSession(); // starts in Starting
        var backend = new Mock<IBackendClient>();

        var loop = new TaskLoop(session, backend.Object,
            Mock.Of<IIdleParker>(), new BotActivityState(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
        await loop.RunAsync(cts.Token);

        backend.Verify(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()),
                Times.Never);
    }

    [Fact]
    public async Task EmptyQueue_BacksOff_Retries()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        var backend = new Mock<IBackendClient>();
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync((BotTaskResponse?)null);

        var loop = new TaskLoop(session, backend.Object,
            Mock.Of<IIdleParker>(), new BotActivityState(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        backend.Verify(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()),
                Times.AtLeastOnce);
    }

    [Fact]
    public async Task HandlerCrash_LoopContinues()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        var backend = new Mock<IBackendClient>();
        var claims = 0;
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync(() =>
               {
                   claims++;
                   if (claims == 1) return MakeWithdrawTask();
                   return null;
               });

        // Use a Mock<IBotSession> that throws on GiveGroupMoney so the handler
        // raises an exception the TaskLoop must swallow.
        var throwingSession = new Mock<IBotSession>();
        throwingSession.SetupGet(s => s.State).Returns(SessionState.Online);
        throwingSession.SetupGet(s => s.BotUuid).Returns(Guid.NewGuid());
        throwingSession.Setup(s => s.GiveGroupMoney(
                It.IsAny<Guid>(), It.IsAny<int>(), It.IsAny<string>()))
            .Throws(new InvalidOperationException("boom"));

        var loop = new TaskLoop(session, backend.Object,
            Mock.Of<IIdleParker>(), new BotActivityState(),
            () => new WithdrawGroupHandler(throwingSession.Object, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(500));
        await loop.RunAsync(cts.Token);

        // Handler crashed — the loop must keep ticking and claim at least once.
        claims.Should().BeGreaterOrEqualTo(1);
    }

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
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        parker.Verify(p => p.ParkIfNeededAsync(It.IsAny<CancellationToken>()),
            Times.AtLeastOnce);
    }

    [Fact]
    public async Task EmptyQueue_IdleParkerCancellation_StopsCleanly()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        var backend = new Mock<IBackendClient>();
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync((BotTaskResponse?)null);
        var parker = new Mock<IIdleParker>();
        parker.Setup(p => p.ParkIfNeededAsync(It.IsAny<CancellationToken>()))
              .ThrowsAsync(new OperationCanceledException());

        var loop = new TaskLoop(session, backend.Object,
            parker.Object, new BotActivityState(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        var act = async () => await loop.RunAsync(cts.Token);

        await act.Should().NotThrowAsync();
        // Loop returned on the parker's OCE rather than swallowing it and
        // looping again — exactly one park attempt.
        parker.Verify(p => p.ParkIfNeededAsync(It.IsAny<CancellationToken>()),
            Times.Once);
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
                   return claims == 1 ? MakeWithdrawTask() : null;
               });
        var activity = new BotActivityState();

        var loop = new TaskLoop(session, backend.Object,
            Mock.Of<IIdleParker>(), activity,
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        var snap = activity.Current;
        snap.LastClaimAt.Should().NotBeNull();   // a claim happened
        snap.CurrentTaskId.Should().BeNull();    // cleared in finally
    }

    private static BotTaskResponse MakeWithdrawTask() => new(
        Id: 1,
        TaskType: BotTaskType.WITHDRAW_GROUP,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 0,
        EscrowId: null,
        ParcelUuid: Guid.Empty,
        RegionName: null,
        PositionX: null,
        PositionY: null,
        PositionZ: null,
        SentinelPrice: 0,
        AssignedBotUuid: Guid.NewGuid(),
        FailureReason: null,
        NextRunAt: null,
        RecurrenceIntervalSeconds: null,
        CreatedAt: DateTimeOffset.UtcNow,
        CompletedAt: null,
        RecipientUuid: Guid.NewGuid(),
        AmountL: 1500);
}
