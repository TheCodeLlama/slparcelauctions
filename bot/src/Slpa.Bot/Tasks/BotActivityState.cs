using Slpa.Bot.Backend.Models;

namespace Slpa.Bot.Tasks;

/// <summary>
/// What the bot is currently doing, for the heartbeat. Single-writer
/// (TaskLoop), many-reader (HeartbeatLoop). Lock-free: an immutable snapshot
/// swapped atomically through a volatile reference.
///
/// REPORTING ONLY. Never read by IdleParker — the heartbeat must never
/// influence idle detection or the park decision (spec hard invariant).
/// </summary>
public sealed class BotActivityState
{
    public sealed record Snapshot(
        long? CurrentTaskId,
        string? CurrentTaskType,
        DateTimeOffset? LastClaimAt);

    private volatile Snapshot _snap = new(null, null, null);

    public Snapshot Current => _snap;

    /// <summary>
    /// Records a claim round. <paramref name="task"/> null = empty queue:
    /// updates LastClaimAt and clears the current task.
    /// </summary>
    public void RecordClaim(BotTaskResponse? task, DateTimeOffset now)
        => _snap = new Snapshot(task?.Id, task?.TaskType.ToString(), now);

    /// <summary>Task finished: clear task fields, keep LastClaimAt.</summary>
    public void Clear()
    {
        var s = _snap;
        _snap = new Snapshot(null, null, s.LastClaimAt);
    }
}
