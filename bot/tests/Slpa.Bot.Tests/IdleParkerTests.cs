using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class IdleParkerTests
{
    private static IdleParkOptions Opts() => new();

    private static Func<double> Seq(params double[] xs)
    {
        var i = 0;
        return () => xs[Math.Min(i++, xs.Length - 1)];
    }

    private static IdleParker Make(
        FakeBotSession session, IdleParkOptions opts,
        Func<DateTimeOffset> now, Func<double> rng) =>
        new(session, opts, NullLogger<IdleParker>.Instance, now, rng);

    [Fact]
    public void IdleParkOptions_DefaultsToHadronRectangle()
    {
        var o = new IdleParkOptions();
        o.Enabled.Should().BeTrue();
        o.Region.Should().Be("Hadron");
        o.Corner1X.Should().Be(44);
        o.Corner1Y.Should().Be(73);
        o.Corner2X.Should().Be(30);
        o.Corner2Y.Should().Be(65);
        o.Z.Should().Be(25);
        o.ParkCooldownSeconds.Should().Be(180);
        o.Chairs.Should().BeEquivalentTo(new[]
        {
            "d28b2fea-8020-b875-777b-6e432a7d9317",
            "65f7f3e4-1a06-0a07-9233-a3f9a44ff88c",
            "273a9a21-9a23-ca63-58e0-fe817f0a524a",
            "02080632-9fcc-1e1f-36b3-8dd54a694f12",
            "6a8106b7-d771-4c5c-ee19-62b4291de07a",
            "0c852666-669a-9670-e663-380e18d748b7",
            "cd2dbb84-8b18-f28e-c19c-f40468036fc6",
            "ca2c885f-d3fd-2368-1ea7-4c57e014ea5a",
        });
    }

    [Fact]
    public async Task Disabled_WhenEnabledFalse_NoTeleport()
    {
        var opts = Opts();
        opts.Enabled = false;
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Elsewhere", 1, 1) };
        var parker = Make(session, opts, () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().BeEmpty();
    }

    [Fact]
    public async Task Disabled_WhenRegionBlank_NoTeleport()
    {
        var opts = Opts();
        opts.Region = "  ";
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Elsewhere", 1, 1) };
        var parker = Make(session, opts, () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().BeEmpty();
    }

    [Fact]
    public async Task InRectangle_NoTeleport()
    {
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Hadron", 37, 69) };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().BeEmpty();
    }

    [Fact]
    public async Task DifferentRegion_TeleportsIntoRectangle_Forced()
    {
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Ahern", 50, 50) };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.Region.Should().Be("Hadron");
        call.ForceMove.Should().BeTrue();
        call.X.Should().BeInRange(30, 44);
        call.Y.Should().BeInRange(65, 73);
        call.Z.Should().Be(25);
    }

    [Fact]
    public async Task SameRegionOutsideRectangle_TeleportsForced()
    {
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Hadron", 200, 200) };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.ForceMove.Should().BeTrue();
        call.X.Should().BeInRange(30, 44);
        call.Y.Should().BeInRange(65, 73);
    }

    [Fact]
    public async Task CornerOrderIndependent_StillParksWithinBounds()
    {
        var opts = Opts();
        (opts.Corner1X, opts.Corner2X) = (opts.Corner2X, opts.Corner1X);
        (opts.Corner1Y, opts.Corner2Y) = (opts.Corner2Y, opts.Corner1Y);
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Ahern", 1, 1) };
        var parker = Make(session, opts, () => DateTimeOffset.UnixEpoch, () => 0.5);

        await parker.ParkIfNeededAsync(default);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.X.Should().BeInRange(30, 44);
        call.Y.Should().BeInRange(65, 73);
    }

    [Fact]
    public async Task RandomPoint_AtExtremes_WithinBounds()
    {
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Ahern", 1, 1) };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, Seq(0.0, 1.0));

        await parker.ParkIfNeededAsync(default);

        var call = session.TeleportCalls.Should().ContainSingle().Subject;
        call.X.Should().Be(30);   // minX + 0.0*(maxX-minX)
        call.Y.Should().Be(73);   // minY + 1.0*(maxY-minY)
    }

    [Fact]
    public async Task Cooldown_BlocksReattempt_ThenAllowsAfterExpiry()
    {
        var now = DateTimeOffset.UnixEpoch;
        var session = new FakeBotSession { CurrentLocation = new BotLocation("Ahern", 1, 1) };
        var parker = Make(session, Opts(), () => now, () => 0.5);

        await parker.ParkIfNeededAsync(default);          // attempt 1
        session.TeleportCalls.Should().HaveCount(1);

        now = DateTimeOffset.UnixEpoch.AddSeconds(60);     // < 180s cooldown
        await parker.ParkIfNeededAsync(default);
        session.TeleportCalls.Should().HaveCount(1);       // blocked

        now = DateTimeOffset.UnixEpoch.AddSeconds(181);    // cooldown expired
        await parker.ParkIfNeededAsync(default);
        session.TeleportCalls.Should().HaveCount(2);       // allowed
    }

    [Fact]
    public async Task FailedTeleport_SetsCooldown()
    {
        var now = DateTimeOffset.UnixEpoch;
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Ahern", 1, 1),
            TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.RegionNotFound)
        };
        var parker = Make(session, Opts(), () => now, () => 0.5);

        await parker.ParkIfNeededAsync(default);
        await parker.ParkIfNeededAsync(default);           // same clock — in cooldown

        session.TeleportCalls.Should().HaveCount(1);
    }

    [Fact]
    public async Task ExceptionThrown_IsSwallowed_AndSetsCooldown()
    {
        var now = DateTimeOffset.UnixEpoch;
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Ahern", 1, 1),
            TeleportPolicy = _ => throw new InvalidOperationException("boom")
        };
        var parker = Make(session, Opts(), () => now, () => 0.5);

        // Does not throw (generic catch swallows non-OCE).
        await parker.ParkIfNeededAsync(default);
        // Second call at same clock is blocked by the cooldown set in catch.
        await parker.ParkIfNeededAsync(default);

        session.TeleportCalls.Should().HaveCount(1);
    }

    [Fact]
    public async Task CurrentLocationNull_SkipsGracefully()
    {
        var now = DateTimeOffset.UnixEpoch;
        var session = new FakeBotSession { CurrentLocation = null };
        var parker = Make(session, Opts(), () => now, () => 0.5);

        await parker.ParkIfNeededAsync(default);
        session.TeleportCalls.Should().BeEmpty();

        // No cooldown burned — once location appears it parks immediately.
        session.CurrentLocation = new BotLocation("Ahern", 1, 1);
        await parker.ParkIfNeededAsync(default);
        session.TeleportCalls.Should().HaveCount(1);
    }

    [Fact]
    public async Task Cancellation_Propagates()
    {
        var session = new FakeBotSession
        {
            CurrentLocation = new BotLocation("Ahern", 1, 1),
            TeleportPolicy = _ => throw new OperationCanceledException()
        };
        var parker = Make(session, Opts(), () => DateTimeOffset.UnixEpoch, () => 0.5);

        var act = async () => await parker.ParkIfNeededAsync(default);
        await act.Should().ThrowAsync<OperationCanceledException>();
    }
}
