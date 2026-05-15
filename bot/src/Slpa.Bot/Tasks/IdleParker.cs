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
/// Relocates an idle bot into the configured rectangle. Stateless except for
/// a cooldown timestamp: each idle cycle re-checks rectangle membership, so
/// being inside the rectangle is its own idempotency. Observation-only state
/// (heartbeat/activity) is deliberately NOT consulted here — "idle" is solely
/// "TaskLoop.ClaimAsync returned null".
/// </summary>
public sealed class IdleParker : IIdleParker
{
    private readonly IBotSession _session;
    private readonly IdleParkOptions _opts;
    private readonly ILogger<IdleParker> _log;
    private readonly Func<DateTimeOffset> _now;
    private readonly Func<double> _rng;

    private DateTimeOffset _nextParkUtc = DateTimeOffset.MinValue;
    private bool _warnedDisabled;

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

            var loc = _session.CurrentLocation;
            if (loc is null) return;

            double minX = Math.Min(_opts.Corner1X, _opts.Corner2X);
            double maxX = Math.Max(_opts.Corner1X, _opts.Corner2X);
            double minY = Math.Min(_opts.Corner1Y, _opts.Corner2Y);
            double maxY = Math.Max(_opts.Corner1Y, _opts.Corner2Y);

            bool inRegion = string.Equals(
                loc.Region, _opts.Region, StringComparison.OrdinalIgnoreCase);
            bool inRect = inRegion
                && loc.X >= minX && loc.X <= maxX
                && loc.Y >= minY && loc.Y <= maxY;
            if (inRect) return; // already parked — no teleport, no cooldown

            double x = minX + _rng() * (maxX - minX);
            double y = minY + _rng() * (maxY - minY);
            double z = _opts.Z;

            _nextParkUtc = now + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);

            var result = await _session
                .TeleportAsync(_opts.Region, x, y, z, ct, forceMove: true)
                .ConfigureAwait(false);

            if (result.Success)
            {
                _log.LogInformation(
                    "Idle-parked to {Region} ({X:F1},{Y:F1},{Z:F1})",
                    _opts.Region, x, y, z);
            }
            else
            {
                _log.LogWarning(
                    "Idle-park teleport to {Region} failed: {Failure}; " +
                    "backing off {Cooldown}s",
                    _opts.Region, result.Failure, _opts.ParkCooldownSeconds);
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _nextParkUtc = _now()
                + TimeSpan.FromSeconds(_opts.ParkCooldownSeconds);
            _log.LogWarning(ex,
                "Idle-park attempt threw; backing off {Cooldown}s",
                _opts.ParkCooldownSeconds);
        }
    }
}
