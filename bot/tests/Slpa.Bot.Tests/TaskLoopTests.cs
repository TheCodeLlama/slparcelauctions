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
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
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
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.RunAsync(cts.Token);

        backend.Verify(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()),
                Times.AtLeastOnce);
    }

    [Fact]
    public async Task HandlerCrash_DoesNotCallback_LoopContinues()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        session.TeleportPolicy = _ => throw new InvalidOperationException("boom");
        var backend = new Mock<IBackendClient>();
        var claims = 0;
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync(() =>
               {
                   claims++;
                   if (claims == 1) return MakeVerifyTask();
                   return null;
               });

        var loop = new TaskLoop(session, backend.Object,
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(500));
        await loop.RunAsync(cts.Token);

        backend.Verify(b => b.CompleteVerifyAsync(It.IsAny<long>(),
                It.IsAny<BotTaskCompleteRequest>(), It.IsAny<CancellationToken>()),
                Times.Never);
        claims.Should().BeGreaterOrEqualTo(1);
    }

    private static BotTaskResponse MakeVerifyTask() => new(
        1, BotTaskType.VERIFY, BotTaskStatus.IN_PROGRESS,
        42, null, Guid.NewGuid(), "Ahern", 128, 128, 20,
        999_999_999, null, null, null, null, null, null,
        Guid.NewGuid(), null, null, null,
        DateTimeOffset.UtcNow, null);
}
