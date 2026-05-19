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
/// VERIFY_BUY_OWNER handler. Drives the teleport -> read-parcel -> classify ->
/// report path via the in-test <see cref="FakeBotSession"/> + a mocked
/// <see cref="IBackendClient"/>; never touches GridClient.
///
/// <para>Backend dispatch (EscrowManualActionService.verifyTransfer) currently
/// only stamps <c>expectedWinnerUuid</c> on the task, so the handler can only
/// classify "owner == winner" vs "owner != winner". The residual case is
/// reported as <see cref="BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER"/>; reliable
/// stranger detection is left to the 30-min background ownership monitor that
/// has the full seller / registered-group context.</para>
/// </summary>
public sealed class VerifyBuyOwnerHandlerTests
{
    private readonly Mock<IBackendClient> _backend = new();
    private readonly FakeBotSession _session = new();
    private BuyOwnerResultRequest? _captured;

    public VerifyBuyOwnerHandlerTests()
    {
        _session.SimulateLoginSuccess();
        _backend
            .Setup(b => b.ReportBuyOwnerResultAsync(
                It.IsAny<long>(), It.IsAny<BuyOwnerResultRequest>(),
                It.IsAny<CancellationToken>()))
            .Callback<long, BuyOwnerResultRequest, CancellationToken>(
                (_, body, _) => _captured = body)
            .Returns(Task.CompletedTask);
    }

    private VerifyBuyOwnerHandler NewHandler() =>
        new(_session, _backend.Object, NullLogger<VerifyBuyOwnerHandler>.Instance);

    private BuyOwnerOutcome? Reported() => _captured?.Outcome;

    [Fact]
    public async Task OwnerEqualsWinner_ReportsOwnerIsWinner()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: winner, isGroupOwned: false);
        var task = BuildTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_IS_WINNER);
        _captured!.ObservedOwnerUuid.Should().Be(winner);
        _captured.ObservedOwnerType.Should().Be("agent");
    }

    [Fact]
    public async Task OwnerEqualsSellerAvatar_ReportsStillPreTransfer()
    {
        var winner = Guid.NewGuid();
        var seller = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: seller, isGroupOwned: false);
        var task = BuildTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER);
        _captured!.ObservedOwnerUuid.Should().Be(seller);
        _captured.ObservedOwnerType.Should().Be("agent");
    }

    [Fact]
    public async Task OwnerEqualsCase3Group_ReportsStillPreTransfer()
    {
        var winner = Guid.NewGuid();
        var group = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: group, isGroupOwned: true);
        var task = BuildTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER);
        _captured!.ObservedOwnerUuid.Should().Be(group);
        _captured.ObservedOwnerType.Should().Be("group");
    }

    [Fact]
    public async Task OwnerIsUnknownAvatar_ReportsStillPreTransfer_ObservedUuidPropagated()
    {
        // Conservative default: without expectedSellerUuid on the task, the
        // bot cannot reliably distinguish stranger from seller — it reports
        // PRE_TRANSFER and lets the World API ownership monitor handle real
        // strangers. The observed UUID + type still flow to the backend so
        // admin tooling can see what the bot saw.
        var winner = Guid.NewGuid();
        var stranger = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: stranger, isGroupOwned: false);
        var task = BuildTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER);
        _captured!.ObservedOwnerUuid.Should().Be(stranger);
    }

    [Fact]
    public async Task TeleportRegionNotFound_ReportsParcelDeleted()
    {
        var winner = Guid.NewGuid();
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.RegionNotFound);
        var readCalled = false;
        _session.ReadPolicy = (_, _) => { readCalled = true; return null; };
        var task = BuildTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.PARCEL_DELETED);
        readCalled.Should().BeFalse();
        _captured!.ObservedOwnerUuid.Should().BeNull();
    }

    [Fact]
    public async Task TeleportAccessDenied_ReportsWorldApiFailure_NoReadParcel()
    {
        // ACCESS_DENIED at teleport is a transient bot-side observation gap,
        // not evidence the parcel changed hands. WORLD_API_FAILURE leaves the
        // pending flag cleared but consumes no attempt, so the user can retry.
        var winner = Guid.NewGuid();
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.AccessDenied);
        var readCalled = false;
        _session.ReadPolicy = (_, _) => { readCalled = true; return null; };
        var task = BuildTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.WORLD_API_FAILURE);
        readCalled.Should().BeFalse();
    }

    [Fact]
    public async Task ReadParcelNull_ReportsWorldApiFailure()
    {
        var winner = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => null;
        var task = BuildTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.WORLD_API_FAILURE);
    }

    [Fact]
    public async Task MissingWinner_ReportsUnknownError()
    {
        // Defensive guard: a malformed task (no expectedWinnerUuid) shouldn't
        // teleport, but it must still close the loop with a transient outcome
        // so the backend can clear the pending flag and let the user retry.
        _session.ReadPolicy = (_, _) => Snapshot(owner: Guid.NewGuid(), isGroupOwned: false);
        var task = BuildTask(expectedWinner: null);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.UNKNOWN_ERROR);
    }

    [Fact]
    public async Task UnexpectedSessionException_ReportsUnknownError()
    {
        var winner = Guid.NewGuid();
        _session.TeleportPolicy = _ => throw new InvalidOperationException("boom");
        var task = BuildTask(winner);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.UNKNOWN_ERROR);
    }

    [Fact]
    public async Task SessionLost_PropagatesToLoop()
    {
        // The handler must let SessionLostException bubble so TaskLoop's
        // standard "session lost mid-task" handling fires — backend's
        // IN_PROGRESS sweep then cleans up the row.
        var winner = Guid.NewGuid();
        _session.TeleportPolicy = _ => throw new SessionLostException("dropped");
        var task = BuildTask(winner);

        var act = async () => await NewHandler().HandleAsync(task, CancellationToken.None);
        await act.Should().ThrowAsync<SessionLostException>();

        _backend.Verify(b => b.ReportBuyOwnerResultAsync(
                It.IsAny<long>(), It.IsAny<BuyOwnerResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Never);
    }

    private static ParcelSnapshot Snapshot(Guid owner, bool isGroupOwned) => new(
        OwnerId: owner,
        GroupId: isGroupOwned ? owner : Guid.Empty,
        IsGroupOwned: isGroupOwned,
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

    private static BotTaskResponse BuildTask(Guid winner) => BuildTask((Guid?)winner);

    private static BotTaskResponse BuildTask(Guid? expectedWinner) => new(
        Id: 77,
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
        ExpectedWinnerUuid: expectedWinner);
}
