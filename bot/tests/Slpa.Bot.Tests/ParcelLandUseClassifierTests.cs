using System.Collections.Generic;
using FluentAssertions;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class ParcelLandUseClassifierTests
{
    private static ParcelSnapshot Snap(string name = "", bool forSale = false) =>
        new(
            Guid.Empty,
            Guid.Empty,
            false,
            Guid.Empty,
            0,
            forSale,
            name,
            string.Empty,
            0,
            0,
            0,
            Guid.Empty,
            0);

    private static uint[,] EmptyGrid() => new uint[64, 64];

    [Fact]
    public void Classify_AllCellsZero_ReturnsAllOther()
    {
        var grid = EmptyGrid();
        var snapshots = new Dictionary<uint, ParcelSnapshot>();

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result.Should().HaveCount(4096);
        result.Should().AllBeEquivalentTo((byte)ParcelLandUseCategory.Other);
    }

    [Fact]
    public void Classify_ListedParcelCells_ReturnListedRegardlessOfName()
    {
        var grid = EmptyGrid();
        grid[10, 5] = 42; grid[10, 6] = 42; grid[11, 5] = 42;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [42] = Snap(name: "Protected Land", forSale: true),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 42);

        result[10 * 64 + 5].Should().Be((byte)ParcelLandUseCategory.Listed);
        result[10 * 64 + 6].Should().Be((byte)ParcelLandUseCategory.Listed);
        result[11 * 64 + 5].Should().Be((byte)ParcelLandUseCategory.Listed);
    }

    [Fact]
    public void Classify_AbandonedLandName_ReturnsAbandoned()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 99;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [99] = Snap(name: "Abandoned Land"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Abandoned);
    }

    [Fact]
    public void Classify_ProtectedLandName_ReturnsProtected()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 100;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [100] = Snap(name: "Protected Land"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Protected);
    }

    [Fact]
    public void Classify_ProtectedWinsOverForSaleFlag()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 101;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [101] = Snap(name: "Protected Land", forSale: true),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Protected);
    }

    [Fact]
    public void Classify_AbandonedWinsOverForSaleFlag()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 102;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [102] = Snap(name: "Abandoned Land", forSale: true),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Abandoned);
    }

    [Fact]
    public void Classify_ForSaleFlagOnNonLinden_ReturnsForSale()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 200;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [200] = Snap(name: "Player Parcel", forSale: true),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.ForSale);
    }

    [Fact]
    public void Classify_NameSubstringIsCaseInsensitive()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 300;
        grid[0, 1] = 301;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [300] = Snap(name: "abandoned land"),
            [301] = Snap(name: "PROTECTED LAND - main road"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Abandoned);
        result[1].Should().Be((byte)ParcelLandUseCategory.Protected);
    }

    [Fact]
    public void Classify_LocalIdNotInSnapshots_ReturnsOther()
    {
        var grid = EmptyGrid();
        grid[5, 5] = 999;
        var snapshots = new Dictionary<uint, ParcelSnapshot>();

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[5 * 64 + 5].Should().Be((byte)ParcelLandUseCategory.Other);
    }

    [Fact]
    public void Classify_LocalIdZero_ReturnsOtherWithoutDictLookup()
    {
        var grid = EmptyGrid();
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [0] = Snap(name: "Protected Land"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result.Should().AllBeEquivalentTo((byte)ParcelLandUseCategory.Other);
    }

    [Fact]
    public void Classify_EmptyName_ReturnsOther()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 400;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [400] = Snap(name: string.Empty, forSale: false),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Other);
    }

    [Fact]
    public void Classify_MixedRegion_AssignsCorrectCategoriesPerCell()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 1;
        grid[0, 1] = 2;
        grid[0, 2] = 3;
        grid[0, 3] = 4;
        grid[0, 4] = 5;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [1] = Snap(name: "Doesn't matter, listed wins"),
            [2] = Snap(name: "Protected Land"),
            [3] = Snap(name: "Abandoned Land"),
            [4] = Snap(name: "Player parcel", forSale: true),
            [5] = Snap(name: "Player parcel"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 1);

        result[0].Should().Be((byte)ParcelLandUseCategory.Listed);
        result[1].Should().Be((byte)ParcelLandUseCategory.Protected);
        result[2].Should().Be((byte)ParcelLandUseCategory.Abandoned);
        result[3].Should().Be((byte)ParcelLandUseCategory.ForSale);
        result[4].Should().Be((byte)ParcelLandUseCategory.Other);
    }
}
