using FluentAssertions;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class BotActivityStateTests
{
    private static BotTaskResponse Task1() => new(
        7, BotTaskType.MONITOR_AUCTION, BotTaskStatus.IN_PROGRESS,
        42, null, Guid.NewGuid(), "Hadron", 1, 2, 3,
        999, null, null, null, null, null, null,
        Guid.NewGuid(), null, null, null,
        DateTimeOffset.UnixEpoch, null);

    [Fact]
    public void RecordClaim_WithTask_SetsIdTypeAndLastClaim()
    {
        var s = new BotActivityState();
        var t = DateTimeOffset.UnixEpoch.AddMinutes(5);

        s.RecordClaim(Task1(), t);

        var snap = s.Current;
        snap.CurrentTaskId.Should().Be(7);
        snap.CurrentTaskType.Should().Be("MONITOR_AUCTION");
        snap.LastClaimAt.Should().Be(t);
    }

    [Fact]
    public void RecordClaim_WithNull_SetsLastClaim_ClearsTask()
    {
        var s = new BotActivityState();
        s.RecordClaim(Task1(), DateTimeOffset.UnixEpoch);

        var t = DateTimeOffset.UnixEpoch.AddMinutes(9);
        s.RecordClaim(null, t);

        var snap = s.Current;
        snap.CurrentTaskId.Should().BeNull();
        snap.CurrentTaskType.Should().BeNull();
        snap.LastClaimAt.Should().Be(t);
    }

    [Fact]
    public void Clear_NullsTask_KeepsLastClaim()
    {
        var s = new BotActivityState();
        var t = DateTimeOffset.UnixEpoch.AddMinutes(3);
        s.RecordClaim(Task1(), t);

        s.Clear();

        var snap = s.Current;
        snap.CurrentTaskId.Should().BeNull();
        snap.CurrentTaskType.Should().BeNull();
        snap.LastClaimAt.Should().Be(t);
    }

    [Fact]
    public async Task ConcurrentReadWrite_DoesNotThrow_AndEndsConsistent()
    {
        var s = new BotActivityState();
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));

        var writer = Task.Run(() =>
        {
            var n = 0;
            while (!cts.Token.IsCancellationRequested)
            {
                s.RecordClaim(Task1(), DateTimeOffset.UnixEpoch.AddSeconds(n++));
                s.Clear();
            }
        });
        var reader = Task.Run(() =>
        {
            while (!cts.Token.IsCancellationRequested)
            {
                _ = s.Current;
            }
        });

        await Task.WhenAll(writer, reader);
        s.Current.Should().NotBeNull();
    }
}
