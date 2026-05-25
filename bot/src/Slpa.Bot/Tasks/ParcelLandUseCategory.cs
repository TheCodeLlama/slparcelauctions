namespace Slpa.Bot.Tasks;

/// <summary>
/// Per-cell classification of land use for the 2D parcel-map "Land Use" mode.
/// Encoded as a single byte per cell over the wire; values are stable and
/// must not be renumbered (the backend stores them raw, the frontend keys
/// the palette off the numeric value).
/// </summary>
public enum ParcelLandUseCategory : byte
{
    /// <summary>Default. Player-owned, not for sale, or missing-data fallback.</summary>
    Other = 0,

    /// <summary>Cell belongs to the listed parcel. Wins precedence.</summary>
    Listed = 1,

    /// <summary>Linden-owned parcel whose Name contains "Abandoned Land".</summary>
    Abandoned = 2,

    /// <summary>Player-owned parcel with the ForSale flag set.</summary>
    ForSale = 3,

    /// <summary>Linden-owned parcel whose Name contains "Protected Land".</summary>
    Protected = 4,
}
