namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Body for POST /api/v1/bot/tasks/{taskId}/scan-failed.
/// </summary>
public sealed record ScanFailedRequest(string Reason);
