using System.Collections.Generic;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Pure per-cell land-use classifier. No I/O, no logging - callers do that.
/// Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md.
///
/// Precedence (highest first): Listed > Protected > Abandoned > ForSale > Other.
/// The listed parcel always wins; Linden categories (Protected, Abandoned)
/// beat ForSale because Linden parcels can carry the ForSale flag in odd states.
/// </summary>
public static class ParcelLandUseClassifier
{
    /// <summary>
    /// Classifies a 64x64 grid of parcel LocalIDs into a 4096-byte array of
    /// <see cref="ParcelLandUseCategory"/> values, row-major SW-first.
    /// </summary>
    /// <param name="parcelGrid">64x64 LocalIDs as returned by
    /// <see cref="IBotSession.GetRegionParcelLocalIds"/>. A value of 0 means
    /// no parcel data for that cell (partial-download race or unoccupied);
    /// classified as Other.</param>
    /// <param name="snapshots">Snapshot of every parcel cached in the sim,
    /// keyed by LocalID. As returned by
    /// <see cref="IBotSession.GetAllSimParcelSnapshots"/>. LocalIDs present
    /// in <paramref name="parcelGrid"/> but absent from this dict are
    /// classified as Other (partial-download tolerance).</param>
    /// <param name="listedLocalId">LocalID of the listed parcel, the one
    /// being auctioned. Cells matching this value are classified as Listed
    /// regardless of the parcel's name or flags.</param>
    public static byte[] Classify(
        uint[,] parcelGrid,
        IReadOnlyDictionary<uint, ParcelSnapshot> snapshots,
        uint listedLocalId)
    {
        var cells = new byte[4096];
        for (int row = 0; row < 64; row++)
        {
            for (int col = 0; col < 64; col++)
            {
                cells[row * 64 + col] = (byte)ClassifyCell(
                    parcelGrid[row, col], snapshots, listedLocalId);
            }
        }
        return cells;
    }

    private static ParcelLandUseCategory ClassifyCell(
        uint localId,
        IReadOnlyDictionary<uint, ParcelSnapshot> snapshots,
        uint listedLocalId)
    {
        if (localId == 0) return ParcelLandUseCategory.Other;
        if (localId == listedLocalId) return ParcelLandUseCategory.Listed;
        if (!snapshots.TryGetValue(localId, out var snap))
            return ParcelLandUseCategory.Other;

        var name = snap.Name ?? string.Empty;
        if (name.Contains("Protected Land", System.StringComparison.OrdinalIgnoreCase))
            return ParcelLandUseCategory.Protected;
        if (name.Contains("Abandoned Land", System.StringComparison.OrdinalIgnoreCase))
            return ParcelLandUseCategory.Abandoned;
        if (snap.ForSale)
            return ParcelLandUseCategory.ForSale;
        return ParcelLandUseCategory.Other;
    }
}
