using FluentAssertions;
using Slpa.Bot.Sl;
using Xunit;

namespace Slpa.Bot.Tests;

/// <summary>
/// Exercises the teleport + parcel-read contract through <see cref="IBotSession"/>.
/// Real LibreMetaverse behavior is covered by manual smoke tests.
/// </summary>
public sealed class ParcelReaderTests
{
    [Fact]
    public async Task FakeSession_TeleportAccessDenied_ReturnsFailure()
    {
        var session = new FakeBotSession
        {
            TeleportPolicy = r => TeleportResult.Fail(TeleportFailureKind.AccessDenied)
        };
        var result = await session.TeleportAsync("Ahern", 128, 128, 20, default);
        result.Success.Should().BeFalse();
        result.Failure.Should().Be(TeleportFailureKind.AccessDenied);
    }

    [Fact]
    public async Task FakeSession_ReadParcel_ReturnsSnapshot()
    {
        var expected = new ParcelSnapshot(
            Guid.NewGuid(), Guid.Empty, false, Guid.NewGuid(), 999_999_999,
            "Test Parcel", "desc", 1024, 117, 0, Guid.Empty, 0);
        var session = new FakeBotSession { ReadPolicy = (_, _) => expected };
        var snap = await session.ReadParcelAsync(128, 128, default);
        snap.Should().BeEquivalentTo(expected);
    }
}
