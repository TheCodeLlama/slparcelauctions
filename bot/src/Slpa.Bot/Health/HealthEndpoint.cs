using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Health;

public static class HealthEndpoint
{
    /// <summary>
    /// Maps <c>GET /health</c> to report the current <see cref="SessionState"/>.
    /// Returns HTTP 200 for Online; 503 for anything else so Docker's
    /// healthcheck flips Red on sustained disconnect.
    /// </summary>
    public static IEndpointRouteBuilder MapBotHealth(this IEndpointRouteBuilder app)
    {
        app.MapGet("/health", (IBotSession session) =>
        {
            return session.State == SessionState.Online
                ? Results.Ok(new { state = session.State.ToString() })
                : Results.Json(new { state = session.State.ToString() },
                        statusCode: StatusCodes.Status503ServiceUnavailable);
        });
        return app;
    }
}
