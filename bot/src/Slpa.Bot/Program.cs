using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;
using Slpa.Bot.Backend;
using Slpa.Bot.Health;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;

var builder = WebApplication.CreateBuilder(args);

builder.Services.Configure<BotOptions>(
    builder.Configuration.GetSection(BotOptions.SectionName));
builder.Services.Configure<BackendOptions>(
    builder.Configuration.GetSection(BackendOptions.SectionName));
builder.Services.Configure<RateLimitOptions>(
    builder.Configuration.GetSection(RateLimitOptions.SectionName));
builder.Services.Configure<IdleParkOptions>(
    builder.Configuration.GetSection(IdleParkOptions.SectionName));
builder.Services.Configure<HeartbeatOptions>(
    builder.Configuration.GetSection(HeartbeatOptions.SectionName));

builder.Services.AddSingleton<IBotSession, LibreMetaverseBotSession>();
builder.Services.AddSingleton<IIdleParker, IdleParker>();
builder.Services.AddSingleton<BotActivityState>();
builder.Services.AddHttpClient<IBackendClient, HttpBackendClient>((sp, client) =>
{
    var opts = sp.GetRequiredService<IOptions<BackendOptions>>().Value;
    client.BaseAddress = new Uri(opts.BaseUrl);
    client.Timeout = TimeSpan.FromSeconds(30);
});
builder.Services.AddSingleton<WithdrawGroupHandler>();
builder.Services.AddSingleton<VerifySellToHandler>();
builder.Services.AddSingleton<VerifyBuyOwnerHandler>();
builder.Services.AddHostedService<BotSessionBootstrapper>();
builder.Services.AddHostedService<TaskLoop>();
builder.Services.AddHostedService<HeartbeatLoop>();

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
