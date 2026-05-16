# SLParcels Bot Service

C#/.NET 8 worker that logs into Second Life as an `SLPABot*` account and
services tasks from the SLParcels backend (Method C verification, BOT-tier
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
| `RateLimit__TeleportsPerMinute` | no       | `6`                                    | SL's hard cap                              |
| `IdlePark__Enabled`             | no       | `true`                                 | master switch; `false` disables, keeps coords |
| `IdlePark__Region`              | no       | `Hadron`                               | idle home region; blank disables (warns)   |
| `IdlePark__Corner1X`            | no       | `44`                                   | rectangle corner 1 X                       |
| `IdlePark__Corner1Y`            | no       | `73`                                   | rectangle corner 1 Y                       |
| `IdlePark__Corner2X`            | no       | `30`                                   | opposite corner X                          |
| `IdlePark__Corner2Y`            | no       | `65`                                   | opposite corner Y                          |
| `IdlePark__Z`                   | no       | `25`                                   | landing altitude                           |
| `IdlePark__ParkCooldownSeconds` | no       | `180`                                  | min interval between park attempts         |
| `IdlePark__Chairs__0..N`        | no       | 8 Hadron chairs                        | idle bot sits on a random one; empty = stand only |
| `Heartbeat__IntervalSeconds`    | no       | `60`                                   | heartbeat cadence; backend TTL is 180s     |

Idle-parking is on by default with the Hadron rectangle baked in. When a bot has no task to service and is outside the configured rectangle, it teleports to a random point inside it. The heartbeat runs independently of the task loop and of session state, so a logged-in bot always appears in the admin Bot-pool panel. When `IdlePark.Chairs` is non-empty (8 chairs ship by default), an idle bot in the rectangle sits on one chair chosen at random; one attempt per idle cycle, and if it fails the bot just stays standing in the rectangle. Clear the list to revert to stand-only parking.

ASP.NET uses `__` (double underscore) as the section separator for
environment variables. In `appsettings.json` these are nested under
`Bot`, `Backend`, `RateLimit`, `IdlePark`, and `Heartbeat` keys.

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
7. Verify bot reads the correct parcel at the landing coordinates, not an
   arbitrary parcel in the same region.
8. Leave the bot with an empty task queue for ~1 min. Confirm it teleports
   into the configured rectangle (region `Hadron`, X 30-44, Y 65-73). An
   idle bot already inside the rectangle stays put (no teleport churn).
9. Open the admin Infrastructure page, Bot pool section. Within ~60 s the
   bot shows as `1/1 healthy` with its region. Kill the bot process; after
   ~180 s the row flips red (Redis TTL lapsed).
10. With chairs configured, leave a bot idle ~1 min: confirm it teleports
    into the rectangle then sits (log line `Idle-sat on chair <uuid>`).
    Confirm it does NOT re-sit every cooldown while seated, and that after a
    task teleports it away it re-sits next idle cycle. A rejected/occupied
    chair logs `Idle-sit on <uuid> failed: NotSittable` and the bot stays
    standing.

## Prerequisites

- .NET 8 SDK (or 9+ SDK that can target `net8.0`)
- A valid Second Life `SLPABot*` account (real credentials — no mock mode)
- A running SLParcels backend with matching `slpa.bot.shared-secret`
