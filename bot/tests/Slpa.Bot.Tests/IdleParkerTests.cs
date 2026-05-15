using FluentAssertions;
using Slpa.Bot.Options;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class IdleParkerTests
{
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
    }
}
