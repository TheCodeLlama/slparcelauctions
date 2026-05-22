using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;
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
            Mock.Of<IIdleParker>(), new BotActivityState(), new BotOptions(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            () => new VerifySellToHandler(session, backend.Object,
                    NullLogger<VerifySellToHandler>.Instance),
            () => new VerifyBuyOwnerHandler(session, backend.Object,
                    NullLogger<VerifyBuyOwnerHandler>.Instance),
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
            Mock.Of<IIdleParker>(), new BotActivityState(), new BotOptions(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            () => new VerifySellToHandler(session, backend.Object,
                    NullLogger<VerifySellToHandler>.Instance),
            () => new VerifyBuyOwnerHandler(session, backend.Object,
                    NullLogger<VerifyBuyOwnerHandler>.Instance),
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
            Mock.Of<IIdleParker>(), new BotActivityState(), new BotOptions(),
            () => new WithdrawGroupHandler(throwingSession.Object, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            () => new VerifySellToHandler(session, backend.Object,
                    NullLogger<VerifySellToHandler>.Instance),
            () => new VerifyBuyOwnerHandler(session, backend.Object,
                    NullLogger<VerifyBuyOwnerHandler>.Instance),
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
            parker.Object, new BotActivityState(), new BotOptions(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            () => new VerifySellToHandler(session, backend.Object,
                    NullLogger<VerifySellToHandler>.Instance),
            () => new VerifyBuyOwnerHandler(session, backend.Object,
                    NullLogger<VerifyBuyOwnerHandler>.Instance),
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
            parker.Object, new BotActivityState(), new BotOptions(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            () => new VerifySellToHandler(session, backend.Object,
                    NullLogger<VerifySellToHandler>.Instance),
            () => new VerifyBuyOwnerHandler(session, backend.Object,
                    NullLogger<VerifyBuyOwnerHandler>.Instance),
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
            Mock.Of<IIdleParker>(), activity, new BotOptions(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            () => new VerifySellToHandler(session, backend.Object,
                    NullLogger<VerifySellToHandler>.Instance),
            () => new VerifyBuyOwnerHandler(session, backend.Object,
                    NullLogger<VerifyBuyOwnerHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        var snap = activity.Current;
        snap.LastClaimAt.Should().NotBeNull();   // a claim happened
        snap.CurrentTaskId.Should().BeNull();    // cleared in finally
    }

    [Fact]
    public async Task VerifySellToTask_IsDispatchedToHandler()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        var winner = Guid.NewGuid();
        session.ReadPolicy = (_, _) => new ParcelSnapshot(
            OwnerId: Guid.NewGuid(),
            GroupId: Guid.Empty,
            IsGroupOwned: false,
            AuthBuyerId: winner,
            SalePrice: 0,
            ForSale: true,
            Name: "P",
            Description: "",
            AreaSqm: 1024,
            MaxPrims: 234,
            Category: 0,
            SnapshotId: Guid.NewGuid(),
            Flags: 0x00000080);

        var backend = new Mock<IBackendClient>();
        var claims = 0;
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync(() =>
               {
                   claims++;
                   return claims == 1 ? MakeVerifySellToTask(winner) : null;
               });

        var loop = new TaskLoop(session, backend.Object,
            Mock.Of<IIdleParker>(), new BotActivityState(), new BotOptions(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            () => new VerifySellToHandler(session, backend.Object,
                    NullLogger<VerifySellToHandler>.Instance),
            () => new VerifyBuyOwnerHandler(session, backend.Object,
                    NullLogger<VerifyBuyOwnerHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        backend.Verify(b => b.ReportTaskResultAsync(
                55,
                It.Is<BotTaskResultRequest>(r => r.Outcome == SellToOutcome.SELL_TO_OK),
                It.IsAny<CancellationToken>()),
            Times.AtLeastOnce);
    }

    [Fact]
    public async Task VerifyBuyOwnerTask_IsDispatchedToHandler()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        var winner = Guid.NewGuid();
        session.ReadPolicy = (_, _) => new ParcelSnapshot(
            OwnerId: winner, // owner == winner -> OWNER_IS_WINNER
            GroupId: Guid.Empty,
            IsGroupOwned: false,
            AuthBuyerId: Guid.Empty,
            SalePrice: 0,
            ForSale: false,
            Name: "P",
            Description: "",
            AreaSqm: 1024,
            MaxPrims: 234,
            Category: 0,
            SnapshotId: Guid.NewGuid(),
            Flags: 0u);

        var backend = new Mock<IBackendClient>();
        var claims = 0;
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync(() =>
               {
                   claims++;
                   return claims == 1 ? MakeVerifyBuyOwnerTask(winner) : null;
               });

        var loop = new TaskLoop(session, backend.Object,
            Mock.Of<IIdleParker>(), new BotActivityState(), new BotOptions(),
            () => new WithdrawGroupHandler(session, backend.Object,
                    NullLogger<WithdrawGroupHandler>.Instance),
            () => new VerifySellToHandler(session, backend.Object,
                    NullLogger<VerifySellToHandler>.Instance),
            () => new VerifyBuyOwnerHandler(session, backend.Object,
                    NullLogger<VerifyBuyOwnerHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        backend.Verify(b => b.ReportBuyOwnerResultAsync(
                66,
                It.Is<BuyOwnerResultRequest>(r => r.Outcome == BuyOwnerOutcome.OWNER_IS_WINNER),
                It.IsAny<CancellationToken>()),
            Times.AtLeastOnce);
    }

    private static BotTaskResponse MakeVerifyBuyOwnerTask(Guid winner) => new(
        Id: 66,
        TaskType: BotTaskType.VERIFY_BUY_OWNER,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 7,
        EscrowId: 11,
        ParcelUuid: Guid.NewGuid(),
        RegionName: "R",
        PositionX: 128,
        PositionY: 64,
        PositionZ: 25,
        SentinelPrice: 0,
        AssignedBotUuid: Guid.NewGuid(),
        FailureReason: null,
        NextRunAt: null,
        RecurrenceIntervalSeconds: null,
        CreatedAt: DateTimeOffset.UtcNow,
        CompletedAt: null,
        RecipientUuid: null,
        AmountL: null,
        ExpectedWinnerUuid: winner);

    private static BotTaskResponse MakeVerifySellToTask(Guid winner) => new(
        Id: 55,
        TaskType: BotTaskType.VERIFY_SELL_TO,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 7,
        EscrowId: 7,
        ParcelUuid: Guid.NewGuid(),
        RegionName: "R",
        PositionX: 128,
        PositionY: 64,
        PositionZ: 25,
        SentinelPrice: 0,
        AssignedBotUuid: Guid.NewGuid(),
        FailureReason: null,
        NextRunAt: null,
        RecurrenceIntervalSeconds: null,
        CreatedAt: DateTimeOffset.UtcNow,
        CompletedAt: null,
        RecipientUuid: null,
        AmountL: null,
        ExpectedWinnerUuid: winner);

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
