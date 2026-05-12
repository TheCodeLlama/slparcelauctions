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
/// Sub-project G §7.4 -- WITHDRAW_GROUP handler. Drives the
/// <see cref="IBotSession.GiveGroupMoney"/> path via the in-test
/// <see cref="FakeBotSession"/>; never touches GridClient.
/// </summary>
public sealed class WithdrawGroupHandlerTests
{
    private readonly Mock<IBackendClient> _backend = new();
    private readonly FakeBotSession _session = new();

    [Fact]
    public async Task HandleAsync_CallsGiveGroupMoneyWithRecipientAmountAndMemo_WhenTaskIsWellFormed()
    {
        _session.SimulateLoginSuccess();
        var recipient = Guid.NewGuid();
        var task = BuildWithdrawGroupTask(id: 42, recipient: recipient, amountL: 1500);
        var handler = new WithdrawGroupHandler(_session, _backend.Object,
                NullLogger<WithdrawGroupHandler>.Instance);

        await handler.HandleAsync(task, CancellationToken.None);

        _session.GiveGroupMoneyCalls.Should().ContainSingle();
        var call = _session.GiveGroupMoneyCalls[0];
        call.GroupUuid.Should().Be(recipient);
        call.AmountL.Should().Be(1500);
        call.Memo.Should().Be("SLPA group wallet withdraw ref 42");
    }

    [Fact]
    public async Task HandleAsync_SkipsGiveGroupMoney_WhenRecipientIsMissing()
    {
        _session.SimulateLoginSuccess();
        var task = BuildWithdrawGroupTask(id: 43, recipient: null, amountL: 1500);
        var handler = new WithdrawGroupHandler(_session, _backend.Object,
                NullLogger<WithdrawGroupHandler>.Instance);

        await handler.HandleAsync(task, CancellationToken.None);

        _session.GiveGroupMoneyCalls.Should().BeEmpty();
    }

    [Fact]
    public async Task HandleAsync_SkipsGiveGroupMoney_WhenAmountIsMissing()
    {
        _session.SimulateLoginSuccess();
        var task = BuildWithdrawGroupTask(id: 44, recipient: Guid.NewGuid(), amountL: null);
        var handler = new WithdrawGroupHandler(_session, _backend.Object,
                NullLogger<WithdrawGroupHandler>.Instance);

        await handler.HandleAsync(task, CancellationToken.None);

        _session.GiveGroupMoneyCalls.Should().BeEmpty();
    }

    private static BotTaskResponse BuildWithdrawGroupTask(
            long id, Guid? recipient, long? amountL) => new(
        Id: id,
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
        CompletedAt: null,
        RecipientUuid: recipient,
        AmountL: amountL);
}
