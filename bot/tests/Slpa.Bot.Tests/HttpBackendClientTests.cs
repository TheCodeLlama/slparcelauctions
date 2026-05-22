using System.Net;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;
using WireMock.RequestBuilders;
using WireMock.ResponseBuilders;
using WireMock.Server;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class HttpBackendClientTests : IAsyncLifetime
{
    private WireMockServer _server = null!;
    private HttpBackendClient _client = null!;

    public Task InitializeAsync()
    {
        _server = WireMockServer.Start();
        var http = new HttpClient { BaseAddress = new Uri(_server.Url!) };
        var opts = Microsoft.Extensions.Options.Options.Create(new BackendOptions
        {
            BaseUrl = _server.Url!,
            SharedSecret = "test-secret-xxxxxxxx"
        });
        var botOpts = Microsoft.Extensions.Options.Options.Create(new BotOptions());
        _client = new HttpBackendClient(
            http, opts, botOpts, NullLogger<HttpBackendClient>.Instance);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() { _server.Stop(); _server.Dispose(); return Task.CompletedTask; }

    [Fact]
    public async Task Claim_200_ReturnsTask()
    {
        var botUuid = Guid.NewGuid();
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/claim").UsingPost()
                .WithHeader("Authorization", "Bearer test-secret-xxxxxxxx"))
            .RespondWith(Response.Create()
                .WithStatusCode(200)
                .WithHeader("Content-Type", "application/json")
                .WithBody("""
                          {
                            "id": 1,
                            "taskType": "WITHDRAW_GROUP",
                            "status": "IN_PROGRESS",
                            "auctionId": 0,
                            "escrowId": null,
                            "parcelUuid": "11111111-1111-1111-1111-111111111111",
                            "regionName": null,
                            "sentinelPrice": 0,
                            "createdAt": "2026-04-22T12:00:00Z",
                            "recipientUuid": "22222222-2222-2222-2222-222222222222",
                            "amountL": 1500
                          }
                          """));

        var task = await _client.ClaimAsync(botUuid, default);

        task.Should().NotBeNull();
        task!.Id.Should().Be(1);
        task.TaskType.Should().Be(BotTaskType.WITHDRAW_GROUP);
    }

    [Fact]
    public async Task Claim_204_ReturnsNull()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/claim").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(204));

        var task = await _client.ClaimAsync(Guid.NewGuid(), default);
        task.Should().BeNull();
    }

    [Fact]
    public async Task Claim_401_ThrowsAuthConfigException()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/claim").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(401));

        var act = async () => await _client.ClaimAsync(Guid.NewGuid(), default);
        await act.Should().ThrowAsync<AuthConfigException>();
    }

    [Fact]
    public async Task Claim_500ThenSuccess_RetriesAndReturns()
    {
        var callCount = 0;
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/claim").UsingPost())
            .RespondWith(Response.Create().WithCallback(_ =>
            {
                callCount++;
                if (callCount < 2)
                {
                    return new WireMock.ResponseMessage
                    {
                        StatusCode = 500,
                        BodyData = new WireMock.Util.BodyData
                        {
                            BodyAsString = "{}",
                            Encoding = System.Text.Encoding.UTF8,
                            DetectedBodyType = WireMock.Types.BodyType.String
                        }
                    };
                }
                return new WireMock.ResponseMessage
                {
                    StatusCode = 204,
                    BodyData = new WireMock.Util.BodyData
                    {
                        DetectedBodyType = WireMock.Types.BodyType.None
                    }
                };
            }));

        var task = await _client.ClaimAsync(Guid.NewGuid(), default);
        task.Should().BeNull();
        callCount.Should().BeGreaterThan(1);
    }

    [Fact]
    public async Task Heartbeat_200_ReturnsWithoutThrow()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/heartbeat").UsingPost()
                .WithHeader("Authorization", "Bearer test-secret-xxxxxxxx"))
            .RespondWith(Response.Create().WithStatusCode(200));

        await _client.SendHeartbeatAsync(
            new BotHeartbeatRequest(
                "SLPABot1 Resident",
                Guid.NewGuid().ToString(),
                "Online",
                "Hadron",
                "7",
                "WITHDRAW_GROUP",
                DateTimeOffset.UnixEpoch),
            default);
    }

    [Fact]
    public async Task Heartbeat_401_ThrowsAuthConfigException()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/heartbeat").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(401));

        var act = async () => await _client.SendHeartbeatAsync(
            new BotHeartbeatRequest("w", "u", "Online", null, null, null, null),
            default);
        await act.Should().ThrowAsync<AuthConfigException>();
    }

    [Fact]
    public async Task ReportTaskResult_204_Succeeds()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/42/result").UsingPost()
                .WithHeader("Authorization", "Bearer test-secret-xxxxxxxx"))
            .RespondWith(Response.Create().WithStatusCode(204));

        var body = new BotTaskResultRequest(
            SellToOutcome.SELL_TO_OK,
            Guid.NewGuid(),
            Guid.NewGuid(),
            0,
            true);

        var act = async () => await _client.ReportTaskResultAsync(42, body, default);
        await act.Should().NotThrowAsync();
    }

    [Fact]
    public async Task ReportTaskResult_401_ThrowsAuthConfigException()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/42/result").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(401));

        var body = new BotTaskResultRequest(
            SellToOutcome.BOT_ERROR, null, null, null, null);

        var act = async () => await _client.ReportTaskResultAsync(42, body, default);
        await act.Should().ThrowAsync<AuthConfigException>();
    }

    [Fact]
    public async Task ReportBuyOwnerResult_204_Succeeds()
    {
        _server
            .Given(Request.Create()
                .WithPath("/api/v1/bot/tasks/77/verify-buy-owner-result").UsingPost()
                .WithHeader("Authorization", "Bearer test-secret-xxxxxxxx"))
            .RespondWith(Response.Create().WithStatusCode(204));

        var body = new BuyOwnerResultRequest(
            BuyOwnerOutcome.OWNER_IS_WINNER, Guid.NewGuid(), "agent");

        var act = async () => await _client.ReportBuyOwnerResultAsync(77, body, default);
        await act.Should().NotThrowAsync();
    }

    [Fact]
    public async Task ReportBuyOwnerResult_401_ThrowsAuthConfigException()
    {
        _server
            .Given(Request.Create()
                .WithPath("/api/v1/bot/tasks/77/verify-buy-owner-result").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(401));

        var body = new BuyOwnerResultRequest(
            BuyOwnerOutcome.UNKNOWN_ERROR, null, null);

        var act = async () => await _client.ReportBuyOwnerResultAsync(77, body, default);
        await act.Should().ThrowAsync<AuthConfigException>();
    }
}
