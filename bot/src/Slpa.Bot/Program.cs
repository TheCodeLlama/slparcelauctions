using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Slpa.Bot.Health;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;

var builder = WebApplication.CreateBuilder(args);

builder.Services.Configure<BotOptions>(
    builder.Configuration.GetSection(BotOptions.SectionName));
builder.Services.Configure<BackendOptions>(
    builder.Configuration.GetSection(BackendOptions.SectionName));
builder.Services.Configure<RateLimitOptions>(
    builder.Configuration.GetSection(RateLimitOptions.SectionName));

builder.Services.AddSingleton<IBotSession, LibreMetaverseBotSession>();
builder.Services.AddHostedService<BotSessionBootstrapper>();

var app = builder.Build();
app.MapBotHealth();
app.Run();

/// <summary>
/// Hosted service that drives <see cref="IBotSession.StartAsync"/> on boot
/// and <see cref="IBotSession.LogoutAsync"/> on shutdown. Separate from
/// the session itself so the session can be unit-tested without requiring
/// a host.
/// </summary>
internal sealed class BotSessionBootstrapper : IHostedService
{
    private readonly IBotSession _session;
    public BotSessionBootstrapper(IBotSession session) => _session = session;

    public Task StartAsync(CancellationToken ct) => _session.StartAsync(ct);
    public async Task StopAsync(CancellationToken ct)
    {
        await _session.LogoutAsync(ct).ConfigureAwait(false);
    }
}
