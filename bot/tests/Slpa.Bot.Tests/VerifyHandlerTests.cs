using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class VerifyHandlerTests
{
    private readonly Mock<IBackendClient> _backend = new();
    private readonly FakeBotSession _session = new();

    [Fact]
    public async Task HappyPath_PostsSuccessWithObservations()
    {
        var expectedOwner = Guid.NewGuid();
        var escrowUuid = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => new ParcelSnapshot(
            expectedOwner, Guid.Empty, false, escrowUuid, 999_999_999,
            "Test", "", 1024, 117, 0, Guid.Empty, 0);

        var handler = new VerifyHandler(_session, _backend.Object,
                NullLogger<VerifyHandler>.Instance);
        var task = BuildVerifyTask();

        await handler.HandleAsync(task, CancellationToken.None);

        _backend.Verify(b => b.CompleteVerifyAsync(
            task.Id,
            It.Is<BotTaskCompleteRequest>(r =>
                r.Result == "SUCCESS"
                && r.AuthBuyerId == escrowUuid
                && r.SalePrice == 999_999_999
                && r.ParcelOwner == expectedOwner),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task TeleportAccessDenied_PostsFailure()
    {
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.AccessDenied);

        var handler = new VerifyHandler(_session, _backend.Object,
                NullLogger<VerifyHandler>.Instance);
        await handler.HandleAsync(BuildVerifyTask(), CancellationToken.None);

        _backend.Verify(b => b.CompleteVerifyAsync(
            It.IsAny<long>(),
            It.Is<BotTaskCompleteRequest>(r =>
                r.Result == "FAILURE"
                && r.FailureReason == "AccessDenied"),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task ParcelReadTimeout_PostsFailure()
    {
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => null; // timeout

        var handler = new VerifyHandler(_session, _backend.Object,
                NullLogger<VerifyHandler>.Instance);
        await handler.HandleAsync(BuildVerifyTask(), CancellationToken.None);

        _backend.Verify(b => b.CompleteVerifyAsync(
            It.IsAny<long>(),
            It.Is<BotTaskCompleteRequest>(r =>
                r.Result == "FAILURE"
                && r.FailureReason == "PARCEL_READ_TIMEOUT"),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    private static BotTaskResponse BuildVerifyTask() => new(
        Id: 1,
        TaskType: BotTaskType.VERIFY,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 42,
        EscrowId: null,
        ParcelUuid: Guid.NewGuid(),
        RegionName: "Ahern",
        PositionX: 128,
        PositionY: 128,
        PositionZ: 20,
        SentinelPrice: 999_999_999,
        ExpectedOwnerUuid: null,
        ExpectedAuthBuyerUuid: null,
        ExpectedSalePriceLindens: null,
        ExpectedWinnerUuid: null,
        ExpectedSellerUuid: null,
        ExpectedMaxSalePriceLindens: null,
        AssignedBotUuid: Guid.NewGuid(),
        FailureReason: null,
        NextRunAt: null,
        RecurrenceIntervalSeconds: null,
        CreatedAt: DateTimeOffset.UtcNow,
        CompletedAt: null);
}
