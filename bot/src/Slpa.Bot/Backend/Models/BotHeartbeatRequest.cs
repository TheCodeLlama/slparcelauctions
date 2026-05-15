namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Mirrors the backend's BotHeartbeatRequest record. Serialized camelCase via
/// the shared JsonOpts. Backend requires workerName/slUuid/sessionState
/// non-blank; the rest are nullable.
/// </summary>
public sealed record BotHeartbeatRequest(
    string WorkerName,
    string SlUuid,
    string SessionState,
    string? CurrentRegion,
    string? CurrentTaskKey,
    string? CurrentTaskType,
    DateTimeOffset? LastClaimAt);
