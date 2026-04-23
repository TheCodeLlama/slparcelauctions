using FluentAssertions;
using Slpa.Bot.Sl;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class TeleportRateLimiterTests
{
    [Fact]
    public async Task TakesBucketCapacityTokens_WithoutDelay()
    {
        var limiter = new TeleportRateLimiter(6, TimeSpan.FromSeconds(10));
        var start = DateTimeOffset.UtcNow;
        for (var i = 0; i < 6; i++)
        {
            await limiter.AcquireAsync(CancellationToken.None);
        }
        var elapsed = DateTimeOffset.UtcNow - start;
        elapsed.Should().BeLessThan(TimeSpan.FromMilliseconds(500));
    }

    [Fact]
    public async Task BlocksWhenBucketExhausted_UntilRefill()
    {
        var limiter = new TeleportRateLimiter(
                capacity: 2, refillInterval: TimeSpan.FromMilliseconds(100));
        await limiter.AcquireAsync(CancellationToken.None);
        await limiter.AcquireAsync(CancellationToken.None);
        var start = DateTimeOffset.UtcNow;
        await limiter.AcquireAsync(CancellationToken.None);
        var elapsed = DateTimeOffset.UtcNow - start;
        elapsed.Should().BeGreaterOrEqualTo(TimeSpan.FromMilliseconds(90));
    }

    [Fact]
    public async Task RespectsCancellation()
    {
        var limiter = new TeleportRateLimiter(
                capacity: 0, refillInterval: TimeSpan.FromSeconds(30));
        using var cts = new CancellationTokenSource(
                TimeSpan.FromMilliseconds(200));
        var act = async () => await limiter.AcquireAsync(cts.Token);
        await act.Should().ThrowAsync<OperationCanceledException>();
    }
}
