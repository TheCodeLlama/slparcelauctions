using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

/// <summary>
/// Spec §5 — VERIFY_SELL_TO handler. Drives the teleport → read-parcel →
/// classify → report path via the in-test <see cref="FakeBotSession"/>
/// (TeleportPolicy / ReadPolicy) + a mocked <see cref="IBackendClient"/>;
/// never touches GridClient. The for-sale signal is the typed
/// <c>ParcelSnapshot.ForSale</c> bool (sourced from
/// <c>OpenMetaverse.ParcelFlags.ForSale</c> in the real session), not the
/// raw <c>Flags</c> bitfield.
/// </summary>
public sealed class VerifySellToHandlerTests
{
    private readonly Mock<IBackendClient> _backend = new();
    private readonly FakeBotSession _session = new();

    private VerifySellToHandler NewHandler() =>
        new(_session, _backend.Object, NullLogger<VerifySellToHandler>.Instance);

    private SellToOutcome? Reported() =>
        _captured?.Outcome;

    private BotTaskResultRequest? _captured;

    public VerifySellToHandlerTests()
    {
        _session.SimulateLoginSuccess();
        _backend
            .Setup(b => b.ReportTaskResultAsync(
                It.IsAny<long>(), It.IsAny<BotTaskResultRequest>(),
                It.IsAny<CancellationToken>()))
            .Callback<long, BotTaskResultRequest, CancellationToken>(
                (_, body, _) => _captured = body)
            .Returns(Task.CompletedTask);
    }

    [Fact]
    public async Task OwnerEqualsExpectedWinner_ReportsOwnerAlreadyWinner()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(
            owner: winner, authBuyer: Guid.NewGuid(), price: 0, forSale: true);
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.OWNER_ALREADY_WINNER);
    }

    [Fact]
    public async Task NotForSale_ReportsSellToNotSet()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(
            owner: Guid.NewGuid(), authBuyer: winner, price: 0, forSale: false);
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.SELL_TO_NOT_SET);
    }

    [Fact]
    public async Task AuthBuyerEmpty_ReportsSellToNotSet()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(
            owner: Guid.NewGuid(), authBuyer: Guid.Empty, price: 0, forSale: true);
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.SELL_TO_NOT_SET);
    }

    [Fact]
    public async Task AuthBuyerNotWinner_ReportsWrongBuyer()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(
            owner: Guid.NewGuid(), authBuyer: Guid.NewGuid(), price: 0, forSale: true);
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.WRONG_BUYER);
    }

    [Fact]
    public async Task AuthBuyerWinnerButPriceNonZero_ReportsPriceNotZero()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(
            owner: Guid.NewGuid(), authBuyer: winner, price: 250, forSale: true);
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.PRICE_NOT_ZERO);
    }

    [Fact]
    public async Task AuthBuyerWinnerPriceZeroForSale_ReportsSellToOk()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(
            owner: Guid.NewGuid(), authBuyer: winner, price: 0, forSale: true);
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.SELL_TO_OK);
        _captured!.ObservedSalePrice.Should().Be(0);
        _captured.ObservedForSale.Should().BeTrue();
        _captured.ObservedAuthBuyerUuid.Should().Be(winner);
    }

    /// <summary>
    /// Regression for the prod incident: a correctly group-owned, for-sale,
    /// L$0, sell-to-winner parcel must classify as SELL_TO_OK. The bot log
    /// showed owner=&lt;group&gt; authBuyer=&lt;winner&gt; salePrice=0 →
    /// SELL_TO_NOT_SET because the old code ANDed the raw Flags bitfield with
    /// 0x00000080 (ParcelFlags.ForSaleObjects, NOT ForSale). The snapshot
    /// factory deliberately leaves Flags=0 so this would still be
    /// SELL_TO_NOT_SET under the buggy code, and SELL_TO_OK now that the
    /// handler reads the typed ParcelSnapshot.ForSale.
    /// </summary>
    [Fact]
    public async Task GroupOwnedForSaleZeroPriceToWinner_ReportsSellToOk_ProdRegression()
    {
        var winner = Guid.NewGuid();
        var group = Guid.NewGuid(); // group owner UUID, distinct from winner
        _session.ReadPolicy = (_, _) => Snapshot(
            owner: group, authBuyer: winner, price: 0, forSale: true);
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.SELL_TO_OK);
        _captured!.ObservedForSale.Should().BeTrue();
        _captured.ObservedSalePrice.Should().Be(0);
        _captured.ObservedAuthBuyerUuid.Should().Be(winner);
    }

    [Fact]
    public async Task TeleportAccessDenied_ReportsAccessDenied_NoReadParcel()
    {
        var winner = Guid.NewGuid();
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.AccessDenied);
        var readCalled = false;
        _session.ReadPolicy = (_, _) => { readCalled = true; return null; };
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.ACCESS_DENIED);
        readCalled.Should().BeFalse();
    }

    [Fact]
    public async Task TeleportRegionNotFound_ReportsParcelNotFound()
    {
        var winner = Guid.NewGuid();
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.RegionNotFound);
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.PARCEL_NOT_FOUND);
    }

    [Fact]
    public async Task ReadParcelNull_ReportsBotError()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => null;
        var task = BuildVerifySellToTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(SellToOutcome.BOT_ERROR);
    }

    private static ParcelSnapshot Snapshot(
        Guid owner, Guid authBuyer, long price, bool forSale) => new(
        OwnerId: owner,
        GroupId: Guid.Empty,
        IsGroupOwned: false,
        AuthBuyerId: authBuyer,
        SalePrice: price,
        ForSale: forSale,
        Name: "P",
        Description: "",
        AreaSqm: 1024,
        MaxPrims: 234,
        Category: 0,
        SnapshotId: Guid.NewGuid(),
        Flags: 0u);

    private static BotTaskResponse BuildVerifySellToTask(Guid winner) => new(
        Id: 99,
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
}
