using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Test seam for idle-parking. <see cref="IdleParker"/> is the only
/// implementation; <c>TaskLoop</c> tests inject a no-op.
/// </summary>
public interface IIdleParker
{
    Task ParkIfNeededAsync(CancellationToken ct);
}

/// <summary>
/// Drives an idle bot toward its resting state. Resting state is derived each
/// cycle from observable session signals (never stored), so a task teleport
/// that unseats the bot is handled automatically next cycle:
/// <list type="bullet">
/// <item>Adrift — not seated, outside the rectangle/region → teleport in.</item>
/// <item>InRectangle — standing in the rectangle → sit on a random chair
/// (or, with no chairs configured, this IS the goal: today's behavior).</item>
/// <item>Seated — believed seated on a recorded chair → done.</item>
/// </list>
/// Observation-only state (heartbeat/activity) is deliberately NOT consulted —
/// "idle" is solely "TaskLoop.ClaimAsync returned null".
/// </summary>
public sealed class IdleParker : IIdleParker
{
    internal enum IdleRestState { Adrift, InRectangle, Seated }

    private readonly IBotSession _session;
    private readonly IdleParkOptions _opts;
    private readonly ILogger<IdleParker> _log;
    private readonly Func<DateTimeOffset> _now;
    private readonly Func<double> _rng;

    private DateTimeOffset _nextParkUtc = DateTimeOffset.MinValue;
    private bool _warnedDisabled;
    private bool _warnedBadChair;
    private Guid? _seatedChair;

    public IdleParker(
        IBotSession session,
        IOptions<IdleParkOptions> opts,
        ILogger<IdleParker> log)
        : this(session, opts.Value, log,
            () => DateTimeOffset.UtcNow, () => Random.Shared.NextDouble())
    {
    }

    internal IdleParker(
        IBotSession session,
        IdleParkOptions opts,
        ILogger<IdleParker> log,
        Func<DateTimeOffset> now,
        Func<double> rng)
    {
        _session = session;
        _opts = opts;
        _log = log;
        _now = now;
        _rng = rng;
    }

    /// <summary>
    /// Pure resting-state classifier. <paramref name="loc"/> must be non-null
    /// (the caller handles the no-location case before calling this).
    /// </summary>
    internal static IdleRestState DeriveState(
        bool isSeated, Guid? seatedChair, BotLocation loc, IdleParkOptions opts)
    {
        if (isSeated && seatedChair is not null)
            return IdleRestState.Seated;

        double minX = Math.Min(opts.Corner1X, opts.Corner2X);
        double maxX = Math.Max(opts.Corner1X, opts.Corner2X);
        double minY = Math.Min(opts.Corner1Y, opts.Corner2Y);
        double maxY = Math.Max(opts.Corner1Y, opts.Corner2Y);

        bool inRect = string.Equals(
                loc.Region, opts.Region, StringComparison.OrdinalIgnoreCase)
            && loc.X >= minX && loc.X <= maxX
            && loc.Y >= minY && loc.Y <= maxY;

        return inRect ? IdleRestState.InRectangle : IdleRestState.Adrift;
    }

    public async Task ParkIfNeededAsync(CancellationToken ct)
    {
        try
        {
            if (!_opts.Enabled) return;

            if (string.IsNullOrWhiteSpace(_opts.Region))
            {
                if (!_warnedDisabled)
                {
                    _log.LogWarning(
                        "IdlePark enabled but Region is blank; idle-parking disabled.");
                    _warnedDisabled = true;
                }
                return;
            }

            var now = _now();
            if (now < _nextParkUtc) return;

            bool isSeated = _session.IsSeated;
            if (!isSeated) _seatedChair = null; // sole unseat-detection

            var loc = _session.CurrentLocation;
            if (!isSeated && loc is null) return; // can't position yet, no cooldown

            // Short-circuit the Seated case here (not just in DeriveState) so
            // we never dereference loc! when seated — loc may be null while
            // IsSeated is true.
            var state = isSeated && _seatedChair is not null
                ? IdleRestState.Seated
                : DeriveState(isSeated, _seatedChair, loc!, _opts);

            switch (state)
            {
                case IdleRestState.Seated:
                    return; // goal reached

                case IdleRestState.Adrift:
                {
                    double minX = Math.Min(_opts.Corner1X, _opts.Corner2X);
                    double maxX = Math.Max(_opts.Corner1X, _opts.Corner2X);
                    double minY = Math.Min(_opts.Corner1Y, _opts.Corner2Y);
                    double maxY = Math.Max(_opts.Corner1Y, _opts.Corner2Y);

                    double x = minX + _rng() * (maxX - minX);
                    double y = minY + _rng() * (maxY - minY);
                    double z = _opts.Z;

                    _nextParkUtc = now + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);

                    var tp = await _session
                        .TeleportAsync(_opts.Region, x, y, z, ct, forceMove: true)
                        .ConfigureAwait(false);

                    if (tp.Success)
                        _log.LogInformation(
                            "Idle-parked to {Region} ({X:F1},{Y:F1},{Z:F1})",
                            _opts.Region, x, y, z);
                    else
                        _log.LogWarning(
                            "Idle-park teleport to {Region} failed: {Failure}; "
                            + "backing off {Cooldown}s",
                            _opts.Region, tp.Failure, _opts.ParkCooldownSeconds);
                    return;
                }

                case IdleRestState.InRectangle:
                {
                    var chairs = ParseChairs();
                    if (chairs.Count == 0) return; // no chairs -> this IS the goal

                    var chair = chairs[(int)(_rng() * chairs.Count)];
                    _nextParkUtc = now + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);

                    var sr = await _session.SitAsync(chair, ct).ConfigureAwait(false);
                    if (sr.Success)
                    {
                        _seatedChair = chair;
                        _log.LogInformation("Idle-sat on chair {Chair}", chair);
                    }
                    else
                    {
                        _log.LogWarning(
                            "Idle-sit on {Chair} failed: {Failure}; staying "
                            + "standing, backing off {Cooldown}s",
                            chair, sr.Failure, _opts.ParkCooldownSeconds);
                    }
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            // Re-read the clock: the awaited call may have thrown after an
            // unpredictable delay, so the pre-await `now` is a stale anchor.
            _nextParkUtc = _now()
                + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);
            _log.LogWarning(ex,
                "Idle-park attempt threw; backing off {Cooldown}s",
                _opts.ParkCooldownSeconds);
        }
    }

    private List<Guid> ParseChairs()
    {
        var list = new List<Guid>(_opts.Chairs.Count);
        foreach (var s in _opts.Chairs)
        {
            if (Guid.TryParse(s, out var g))
            {
                list.Add(g);
            }
            else if (!_warnedBadChair)
            {
                _log.LogWarning(
                    "IdlePark.Chairs has unparseable entry '{Entry}'; skipping.", s);
                _warnedBadChair = true;
            }
        }
        return list;
    }
}
