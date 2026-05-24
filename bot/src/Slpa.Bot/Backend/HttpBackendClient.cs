using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;

namespace Slpa.Bot.Backend;

/// <summary>
/// Bearer-authenticated backend client with hand-rolled exponential retry
/// for 5xx + network errors. 401 hard-fails (the secret is wrong; retrying
/// cannot help). 4xx other than 401 gives up on the one task — the
/// backend's timeout sweep cleans up.
/// </summary>
public sealed class HttpBackendClient : IBackendClient
{
    private static readonly JsonSerializerOptions JsonOpts = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly HttpClient _http;
    private readonly BackendOptions _opts;
    private readonly TimeSpan[] _retryBackoff;
    private readonly ILogger<HttpBackendClient> _log;

    public HttpBackendClient(
        HttpClient http,
        IOptions<BackendOptions> opts,
        IOptions<BotOptions> botOpts,
        ILogger<HttpBackendClient> log)
    {
        _http = http;
        _opts = opts.Value;
        _retryBackoff = ToBackoffLadder(botOpts.Value.HttpRetryBackoffSeconds);
        _log = log;
        _http.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", _opts.SharedSecret);
    }

    /// <summary>
    /// Maps a configured seconds ladder to <see cref="TimeSpan"/>s. An
    /// empty/null array falls back to a single-element <c>[1s]</c> default
    /// so the retry loop always has at least one attempt.
    /// </summary>
    private static TimeSpan[] ToBackoffLadder(int[]? seconds)
    {
        if (seconds is null || seconds.Length == 0)
        {
            return new[] { TimeSpan.FromSeconds(1) };
        }
        return Array.ConvertAll(seconds, s => TimeSpan.FromSeconds(s));
    }

    public async Task<BotTaskResponse?> ClaimAsync(Guid botUuid, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bot/tasks/claim")
            {
                Content = JsonContent.Create(new BotTaskClaimRequest(botUuid), options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);

        if (resp.StatusCode == HttpStatusCode.NoContent) return null;
        resp.EnsureSuccessStatusCode();
        return await resp.Content.ReadFromJsonAsync<BotTaskResponse>(JsonOpts, ct)
            .ConfigureAwait(false);
    }

    public async Task SendHeartbeatAsync(
        BotHeartbeatRequest body, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bot/heartbeat")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
    }

    public async Task ReportTaskResultAsync(
        long taskId, BotTaskResultRequest body, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(
                HttpMethod.Post, "/api/v1/bot/tasks/" + taskId + "/result")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
    }

    public async Task ReportBuyOwnerResultAsync(
        long taskId, BuyOwnerResultRequest body, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(
                HttpMethod.Post,
                "/api/v1/bot/tasks/" + taskId + "/verify-buy-owner-result")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
    }

    /// <inheritdoc/>
    public async Task<HttpResponseMessage> PostScanResultAsync(
        long taskId, ScanResultRequest body, CancellationToken ct)
    {
        return await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(
                HttpMethod.Post,
                "/api/v1/bot/tasks/" + taskId + "/scan-result")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
    }

    /// <inheritdoc/>
    public async Task<HttpResponseMessage> PostScanFailedAsync(
        long taskId, ScanFailedRequest body, CancellationToken ct)
    {
        return await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(
                HttpMethod.Post,
                "/api/v1/bot/tasks/" + taskId + "/scan-failed")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
    }

    private async Task<HttpResponseMessage> SendWithRetryAsync(
        Func<HttpRequestMessage> requestFactory, CancellationToken ct)
    {
        Exception? lastException = null;
        for (var attempt = 0; attempt < _retryBackoff.Length; attempt++)
        {
            HttpRequestMessage request = requestFactory();
            try
            {
                var resp = await _http.SendAsync(request, ct).ConfigureAwait(false);
                if (resp.StatusCode == HttpStatusCode.Unauthorized)
                {
                    throw new AuthConfigException(
                        "Backend returned 401 — check slpa.bot.shared-secret");
                }
                if ((int)resp.StatusCode >= 500)
                {
                    lastException = new HttpRequestException(
                        $"Server error {(int)resp.StatusCode}");
                    resp.Dispose();
                    if (attempt < _retryBackoff.Length - 1)
                    {
                        _log.LogWarning(
                            "HTTP {Code}; retry {Attempt} after {Delay}",
                            (int)resp.StatusCode, attempt + 1, _retryBackoff[attempt]);
                        await Task.Delay(_retryBackoff[attempt], ct).ConfigureAwait(false);
                        continue;
                    }
                }
                return resp;
            }
            catch (HttpRequestException ex) when (attempt < _retryBackoff.Length - 1)
            {
                lastException = ex;
                _log.LogWarning(ex,
                    "Network error; retry {Attempt} after {Delay}",
                    attempt + 1, _retryBackoff[attempt]);
                await Task.Delay(_retryBackoff[attempt], ct).ConfigureAwait(false);
            }
        }
        throw lastException ?? new HttpRequestException(
            "Exhausted retries without a response");
    }
}
