namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Body for POST /api/v1/bot/tasks/{taskId}/scan-result.
/// All Base64 fields are standard (non-URL-safe) Base64 with no line breaks.
/// </summary>
public sealed record ScanResultRequest(
    int GridSize,
    int CellSizeMeters,
    string LayoutCellsBase64,
    float HeightBaseMeters,
    float HeightStepMeters,
    string HeightCellsBase64);
