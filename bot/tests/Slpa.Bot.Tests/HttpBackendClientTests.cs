using System.Net;
using System.Text.Json;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
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
        _client = new HttpBackendClient(http, opts, NullLogger<HttpBackendClient>.Instance);
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
                            "taskType": "VERIFY",
                            "status": "IN_PROGRESS",
                            "auctionId": 42,
                            "escrowId": null,
                            "parcelUuid": "11111111-1111-1111-1111-111111111111",
                            "regionName": "Ahern",
                            "sentinelPrice": 999999999,
                            "createdAt": "2026-04-22T12:00:00Z"
                          }
                          """));

        var task = await _client.ClaimAsync(botUuid, default);

        task.Should().NotBeNull();
        task!.Id.Should().Be(1);
        task.TaskType.Should().Be(BotTaskType.VERIFY);
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
    public async Task CompleteVerify_200_ReturnsWithoutThrow()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/5/verify").UsingPut())
            .RespondWith(Response.Create().WithStatusCode(200)
                .WithBody("{\"id\":5,\"taskType\":\"VERIFY\",\"status\":\"COMPLETED\",\"auctionId\":42,\"parcelUuid\":\"11111111-1111-1111-1111-111111111111\",\"sentinelPrice\":999999999,\"createdAt\":\"2026-04-22T12:00:00Z\"}"));

        await _client.CompleteVerifyAsync(
            5,
            new BotTaskCompleteRequest("SUCCESS", Guid.NewGuid(), 999_999_999,
                Guid.NewGuid(), "Parcel", 1024, "Ahern", 128, 128, 20, null),
            default);
    }

    [Fact]
    public async Task PostMonitor_200_ReturnsWithoutThrow()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/9/monitor").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(200)
                .WithBody("{\"id\":9,\"taskType\":\"MONITOR_AUCTION\",\"status\":\"PENDING\",\"auctionId\":42,\"parcelUuid\":\"11111111-1111-1111-1111-111111111111\",\"sentinelPrice\":999999999,\"createdAt\":\"2026-04-22T12:00:00Z\"}"));

        await _client.PostMonitorAsync(
            9,
            new BotMonitorResultRequest(MonitorOutcome.ALL_GOOD, null, null, null, null),
            default);
    }
}
