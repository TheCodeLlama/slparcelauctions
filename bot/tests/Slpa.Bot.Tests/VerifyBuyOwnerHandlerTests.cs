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
/// <para>Backend dispatch stamps <c>expectedWinnerUuid</c>,
/// <c>expectedPreTransferUuid</c> (seller avatar for case-1 / registered SL
/// group UUID for case-3) and <c>expectedOwnerType</c> ("agent"/"group") on
/// the task; the handler classifies the observed (owner, ownerType) pair
/// against that triple per the matrix in <see cref="VerifyBuyOwnerHandler"/>.</para>
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
        var seller = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: winner, isGroupOwned: false);
        var task = BuildCase1Task(winner, seller);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_IS_WINNER);
        _captured!.ObservedOwnerUuid.Should().Be(winner);
        _captured.ObservedOwnerType.Should().Be("agent");
    }

    [Fact]
    public async Task Case1_OwnerEqualsSellerAvatar_ReportsStillPreTransfer()
    {
        var winner = Guid.NewGuid();
        var seller = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: seller, isGroupOwned: false);
        var task = BuildCase1Task(winner, seller);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER);
        _captured!.ObservedOwnerUuid.Should().Be(seller);
        _captured.ObservedOwnerType.Should().Be("agent");
    }

    [Fact]
    public async Task Case3_OwnerEqualsRegisteredGroup_ReportsStillPreTransfer()
    {
        // Case-3 group listing: pre-transfer owner is the registered SL group
        // UUID with ownerType "group". Bot sees the same group still holding
        // the parcel -> PRE_TRANSFER (consumes one attempt, no freeze).
        var winner = Guid.NewGuid();
        var group = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: group, isGroupOwned: true);
        var task = BuildCase3Task(winner, group);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER);
        _captured!.ObservedOwnerUuid.Should().Be(group);
        _captured.ObservedOwnerType.Should().Be("group");
    }

    [Fact]
    public async Task Case1_OwnerIsStrangerAvatar_ReportsOwnerIsStranger()
    {
        // Now that the backend stamps expectedPreTransferUuid, a non-winner
        // non-seller owner is a real stranger -> STRANGER (backend freezes).
        var winner = Guid.NewGuid();
        var seller = Guid.NewGuid();
        var stranger = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: stranger, isGroupOwned: false);
        var task = BuildCase1Task(winner, seller);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_IS_STRANGER);
        _captured!.ObservedOwnerUuid.Should().Be(stranger);
        _captured.ObservedOwnerType.Should().Be("agent");
    }

    [Fact]
    public async Task Case3_OwnerIsStrangerGroup_ReportsOwnerIsStranger()
    {
        // Case-3 stranger: parcel is now held by some other group, not the
        // registered SL group -> STRANGER.
        var winner = Guid.NewGuid();
        var registeredGroup = Guid.NewGuid();
        var strangerGroup = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: strangerGroup, isGroupOwned: true);
        var task = BuildCase3Task(winner, registeredGroup);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_IS_STRANGER);
        _captured!.ObservedOwnerUuid.Should().Be(strangerGroup);
        _captured.ObservedOwnerType.Should().Be("group");
    }

    [Fact]
    public async Task Case3_UuidMatchesButTypeFlipped_ReportsOwnerIsStranger()
    {
        // Defensive: expected type="group" but observed type="agent" with the
        // same UUID (shouldn't happen in SL — group UUIDs and avatar UUIDs are
        // disjoint — but if it ever did, a flipped owner type means a real
        // ownership change). Safer to surface as STRANGER than treat as
        // PRE_TRANSFER.
        var winner = Guid.NewGuid();
        var registeredGroup = Guid.NewGuid();
        _session.ReadPolicy = (_, _) => Snapshot(owner: registeredGroup, isGroupOwned: false);
        var task = BuildCase3Task(winner, registeredGroup);

        await NewHandler().HandleAsync(task, CancellationToken.None);

        Reported().Should().Be(BuyOwnerOutcome.OWNER_IS_STRANGER);
        _captured!.ObservedOwnerType.Should().Be("agent");
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

    /// <summary>Case-1 (individual listing): pre-transfer owner is the seller's avatar UUID.</summary>
    private static BotTaskResponse BuildCase1Task(Guid winner, Guid seller) =>
        BuildTask(winner, expectedPreTransfer: seller, expectedOwnerType: "agent");

    /// <summary>Case-3 (group listing): pre-transfer owner is the registered SL group UUID.</summary>
    private static BotTaskResponse BuildCase3Task(Guid winner, Guid registeredGroup) =>
        BuildTask(winner, expectedPreTransfer: registeredGroup, expectedOwnerType: "group");

    /// <summary>Legacy / malformed tasks: only expectedWinnerUuid present.</summary>
    private static BotTaskResponse BuildTask(Guid winner) =>
        BuildTask((Guid?)winner, expectedPreTransfer: null, expectedOwnerType: null);

    private static BotTaskResponse BuildTask(
        Guid? expectedWinner,
        Guid? expectedPreTransfer = null,
        string? expectedOwnerType = null) => new(
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
        ExpectedWinnerUuid: expectedWinner,
        ExpectedPreTransferUuid: expectedPreTransfer,
        ExpectedOwnerType: expectedOwnerType);
}
