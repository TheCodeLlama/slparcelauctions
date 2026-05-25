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
    /// <returns>
    /// A tuple of the 4096-byte classification array and the count of cells
    /// whose LocalID was 0 (no parcel data). The caller can log or act on the
    /// unmapped count without a second traversal of the grid.
    /// </returns>
    public static (byte[] Cells, int UnmappedCount) Classify(
        uint[,] parcelGrid,
        IReadOnlyDictionary<uint, ParcelSnapshot> snapshots,
        uint listedLocalId)
    {
        var cells = new byte[4096];
        int unmappedCount = 0;
        for (int row = 0; row < 64; row++)
        {
            for (int col = 0; col < 64; col++)
            {
                uint localId = parcelGrid[row, col];
                if (localId == 0) unmappedCount++;
                cells[row * 64 + col] = (byte)ClassifyCell(
                    localId, snapshots, listedLocalId);
            }
        }
        return (cells, unmappedCount);
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
