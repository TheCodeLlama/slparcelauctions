# SLPA Bot Service

C#/.NET 8 worker that logs into Second Life as an `SLPABot*` account and
services tasks from the SLPA backend (Method C verification, BOT-tier
auction monitoring, BOT-tier escrow monitoring).

## Environment

| Variable                        | Required | Default                                | Notes                                      |
|---------------------------------|----------|----------------------------------------|--------------------------------------------|
| `Bot__Username`                 | yes      | —                                      | SL login form (`"Firstname Lastname"`)     |
| `Bot__Password`                 | yes      | —                                      | secret; never commit                       |
| `Bot__BotUuid`                  | yes      | —                                      | the account's SL avatar UUID               |
| `Bot__StartLocation`            | no       | `last`                                 | `last`, `home`, or a region name           |
| `Backend__BaseUrl`              | no       | `http://localhost:8080`                | backend origin                             |
| `Backend__SharedSecret`         | yes      | —                                      | matches `slpa.bot.shared-secret`           |
| `Backend__PrimaryEscrowUuid`    | yes      | `00000000-0000-0000-0000-000000000099` | sanity ref; backend is authoritative       |
| `RateLimit__TeleportsPerMinute` | no       | `6`                                    | SL's hard cap                              |

ASP.NET uses `__` (double underscore) as the section separator for
environment variables. In `appsettings.json` these are nested under
`Bot`, `Backend`, and `RateLimit` keys.

## Local run

```bash
cd bot
dotnet run --project src/Slpa.Bot
```

Health: `curl http://localhost:8081/health` — returns `{ "state": "Online" }`
with HTTP 200 once login completes. Any other state (`Starting`,
`Reconnecting`, `Error`) returns HTTP 503 so Docker's healthcheck flips
Red on sustained disconnect.

## Tests

```bash
cd bot && dotnet test
```

The test suite exercises the state machine through the `IBotSession`
interface only. The real `LibreMetaverseBotSession` (and the live
LibreMetaverse `GridClient`) is covered by the manual smoke test below —
tests never touch `GridClient` directly.

## Manual smoke test

1. Export env vars for `SLPABot1 Resident` in `.env.bot-1` (copy `.env.example`
   once Task 11 lands).
2. Start backend locally (`cd backend && ./mvnw spring-boot:run`).
3. Start bot: `docker compose up bot-1` (Task 11).
4. Verify `GET http://localhost:8081/health` → `Online` within ~10 s.
5. Queue a VERIFY task via the Postman `Dev/Bot simulate verify` helper.
6. Confirm the bot teleports and posts to `PUT /api/v1/bot/tasks/{id}/verify`.

## Prerequisites

- .NET 8 SDK (or 9+ SDK that can target `net8.0`)
- A valid Second Life `SLPABot*` account (real credentials — no mock mode)
- A running SLPA backend with matching `slpa.bot.shared-secret`
