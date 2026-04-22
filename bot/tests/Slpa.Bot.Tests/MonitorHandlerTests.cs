using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class MonitorHandlerTests
{
    private readonly Mock<IBackendClient> _backend = new();
    private readonly FakeBotSession _session = new();

    [Fact]
    public async Task MonitorAuction_AllGood_ReportsAllGood()
    {
        var owner = Guid.NewGuid();
        var escrow = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(owner, escrow, 999_999_999);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(owner, escrow, 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.ALL_GOOD),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorAuction_OwnerChanged_ReportsOwnerChanged()
    {
        var expectedOwner = Guid.NewGuid();
        var observedOwner = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(observedOwner, Guid.NewGuid(), 999_999_999);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(expectedOwner, Guid.NewGuid(), 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.OWNER_CHANGED
                && r.ObservedOwner == observedOwner),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorAuction_AuthBuyerChanged_ReportsAuthBuyerChanged()
    {
        var owner = Guid.NewGuid();
        var expectedAuth = Guid.NewGuid();
        var observedAuth = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(owner, observedAuth, 999_999_999);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(owner, expectedAuth, 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.AUTH_BUYER_CHANGED),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorAuction_PriceMismatch_ReportsPriceMismatch()
    {
        var owner = Guid.NewGuid();
        var auth = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(owner, auth, 123);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(owner, auth, 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.PRICE_MISMATCH),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorAuction_AccessDenied_ReportsAccessDenied()
    {
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.AccessDenied);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(Guid.NewGuid(), Guid.NewGuid(), 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.ACCESS_DENIED),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorEscrow_TransferComplete_ReportsTransferComplete()
    {
        var seller = Guid.NewGuid();
        var winner = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(winner, Guid.Empty, 0);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(EscrowTask(seller, winner, 1),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.TRANSFER_COMPLETE),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorEscrow_TransferReady_ReportsTransferReady()
    {
        var seller = Guid.NewGuid();
        var winner = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(seller, winner, 0);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(EscrowTask(seller, winner, 1),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.TRANSFER_READY),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorEscrow_StillWaiting_ReportsStillWaiting()
    {
        var seller = Guid.NewGuid();
        var winner = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(seller, Guid.Empty, 999_999_999);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(EscrowTask(seller, winner, 1),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.STILL_WAITING),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MissingCoords_PostsAccessDenied()
    {
        _session.SimulateLoginSuccess();
        var task = AuctionTask(Guid.NewGuid(), Guid.NewGuid(), 999_999_999);
        task = task with { RegionName = null };
        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);

        await handler.HandleAsync(task, CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r =>
                r.Outcome == MonitorOutcome.ACCESS_DENIED
                && r.Note == "MISSING_COORDS"),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    private static ParcelSnapshot Snapshot(Guid owner, Guid authBuyer, long salePrice) =>
        new(owner, Guid.Empty, false, authBuyer, salePrice,
            "", "", 1024, 117, 0, Guid.Empty, 0);

    private static BotTaskResponse AuctionTask(
            Guid expectedOwner, Guid expectedAuthBuyer, long expectedSalePrice) => new(
        Id: 10,
        TaskType: BotTaskType.MONITOR_AUCTION,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 42,
        EscrowId: null,
        ParcelUuid: Guid.NewGuid(),
        RegionName: "Ahern",
        PositionX: 128, PositionY: 128, PositionZ: 20,
        SentinelPrice: 999_999_999,
        ExpectedOwnerUuid: expectedOwner,
        ExpectedAuthBuyerUuid: expectedAuthBuyer,
        ExpectedSalePriceLindens: expectedSalePrice,
        ExpectedWinnerUuid: null, ExpectedSellerUuid: null,
        ExpectedMaxSalePriceLindens: null,
        AssignedBotUuid: Guid.NewGuid(), FailureReason: null,
        NextRunAt: DateTimeOffset.UtcNow,
        RecurrenceIntervalSeconds: 1800,
        CreatedAt: DateTimeOffset.UtcNow, CompletedAt: null);

    private static BotTaskResponse EscrowTask(
            Guid seller, Guid winner, long maxSalePrice) => new(
        Id: 20,
        TaskType: BotTaskType.MONITOR_ESCROW,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 42,
        EscrowId: 100,
        ParcelUuid: Guid.NewGuid(),
        RegionName: "Ahern",
        PositionX: 128, PositionY: 128, PositionZ: 20,
        SentinelPrice: 999_999_999,
        ExpectedOwnerUuid: null, ExpectedAuthBuyerUuid: null,
        ExpectedSalePriceLindens: null,
        ExpectedWinnerUuid: winner, ExpectedSellerUuid: seller,
        ExpectedMaxSalePriceLindens: maxSalePrice,
        AssignedBotUuid: Guid.NewGuid(), FailureReason: null,
        NextRunAt: DateTimeOffset.UtcNow,
        RecurrenceIntervalSeconds: 900,
        CreatedAt: DateTimeOffset.UtcNow, CompletedAt: null);
}
