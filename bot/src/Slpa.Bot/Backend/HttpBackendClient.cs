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
    private static readonly TimeSpan[] RetryBackoff =
    {
        TimeSpan.FromSeconds(1),
        TimeSpan.FromSeconds(2),
        TimeSpan.FromSeconds(4),
        TimeSpan.FromSeconds(8),
        TimeSpan.FromSeconds(15)
    };

    private static readonly JsonSerializerOptions JsonOpts = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly HttpClient _http;
    private readonly BackendOptions _opts;
    private readonly ILogger<HttpBackendClient> _log;

    public HttpBackendClient(
        HttpClient http,
        IOptions<BackendOptions> opts,
        ILogger<HttpBackendClient> log)
    {
        _http = http;
        _opts = opts.Value;
        _log = log;
        _http.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", _opts.SharedSecret);
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

    /// <remarks>
    /// Retrying on 5xx is safe because the backend's verify/monitor handlers
    /// are idempotent: a second call for a task in a terminal state returns
    /// 409, never double-completes. See BotTaskService.complete + recordMonitorResult.
    /// </remarks>
    public async Task CompleteVerifyAsync(
        long taskId, BotTaskCompleteRequest body, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Put,
                $"/api/v1/bot/tasks/{taskId}/verify")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
    }

    /// <remarks>
    /// Retrying on 5xx is safe because the backend's verify/monitor handlers
    /// are idempotent: a second call for a task in a terminal state returns
    /// 409, never double-completes. See BotTaskService.complete + recordMonitorResult.
    /// </remarks>
    public async Task PostMonitorAsync(
        long taskId, BotMonitorResultRequest body, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post,
                $"/api/v1/bot/tasks/{taskId}/monitor")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
    }

    private async Task<HttpResponseMessage> SendWithRetryAsync(
        Func<HttpRequestMessage> requestFactory, CancellationToken ct)
    {
        Exception? lastException = null;
        for (var attempt = 0; attempt < RetryBackoff.Length; attempt++)
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
                    if (attempt < RetryBackoff.Length)
                    {
                        _log.LogWarning(
                            "HTTP {Code}; retry {Attempt} after {Delay}",
                            (int)resp.StatusCode, attempt + 1, RetryBackoff[attempt]);
                        await Task.Delay(RetryBackoff[attempt], ct).ConfigureAwait(false);
                        continue;
                    }
                }
                return resp;
            }
            catch (HttpRequestException ex) when (attempt < RetryBackoff.Length)
            {
                lastException = ex;
                _log.LogWarning(ex,
                    "Network error; retry {Attempt} after {Delay}",
                    attempt + 1, RetryBackoff[attempt]);
                await Task.Delay(RetryBackoff[attempt], ct).ConfigureAwait(false);
            }
        }
        throw lastException ?? new HttpRequestException(
            "Exhausted retries without a response");
    }
}
