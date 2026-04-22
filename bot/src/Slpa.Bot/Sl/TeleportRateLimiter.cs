namespace Slpa.Bot.Sl;

/// <summary>
/// Token bucket gating teleports to SL's hard cap of 6/minute. Starts full
/// and refills <c>1</c> token every <c>refillInterval</c>. AcquireAsync
/// blocks (without burning CPU) until a token is available or the caller
/// cancels.
/// </summary>
public sealed class TeleportRateLimiter : IDisposable
{
    private readonly int _capacity;
    private readonly TimeSpan _refillInterval;
    private readonly SemaphoreSlim _sem;
    private readonly Timer _refillTimer;

    public TeleportRateLimiter(int capacity, TimeSpan refillInterval)
    {
        _capacity = capacity;
        _refillInterval = refillInterval;
        // SemaphoreSlim requires initialCount <= maxCount; ensure maxCount >= 1
        // so that callers can Release() a token when capacity is 0.
        var maxCount = Math.Max(1, capacity);
        _sem = new SemaphoreSlim(capacity, maxCount);
        _refillTimer = new Timer(Refill, null, refillInterval, refillInterval);
    }

    /// <summary>Convenience ctor — 6/min with 10s refill (SL's grid cap).</summary>
    public TeleportRateLimiter(int teleportsPerMinute)
        : this(teleportsPerMinute,
               TimeSpan.FromSeconds(60d / Math.Max(1, teleportsPerMinute))) { }

    public async Task AcquireAsync(CancellationToken ct)
    {
        await _sem.WaitAsync(ct).ConfigureAwait(false);
    }

    private void Refill(object? _)
    {
        try
        {
            // Release one token per tick up to capacity.
            if (_sem.CurrentCount < _capacity) _sem.Release();
        }
        catch (SemaphoreFullException) { /* already full — no-op */ }
    }

    public void Dispose()
    {
        _refillTimer.Dispose();
        _sem.Dispose();
    }
}
