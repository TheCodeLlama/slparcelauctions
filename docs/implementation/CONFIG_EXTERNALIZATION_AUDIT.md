# Configuration Externalization Audit

**Date:** 2026-05-21
**Purpose:** Inventory of hardcoded policy values in the codebase that are NOT yet externalized to `application.yml` and that an operator might reasonably want to tune without a code change.

**Status (2026-05-21):** Sections 2-6 externalized in branch `chore/externalize-config`. Section 1 P1 (commission) externalized AND a read-path bug fixed (the payout calculator hardcoded 5% and ignored the per-auction `commissionRate` snapshot, silently dropping coupon commission discounts at payout). Section 1 P3 (bid increments) deliberately NOT externalized to config: per-auction-creator-set bid increments is tracked as separate future feature work.

## How to read this

Each row is a hardcoded literal that behaves like a policy knob. The `application.yml` tree under `slpa.*` already externalizes a large set of values (listing fee, commission default rate, cancellation penalty ladder, escrow timeouts, ownership-monitor cadences, photo caps, notification cleanup, support rate limits, etc.) — those are NOT repeated here. This document is the gap list.

Priority is a rough operator-value judgment:

- **P1** — likely to need tuning in production response to real load or business change; or a correctness risk (drift between two definitions of the same value).
- **P2** — plausible to tune; low churn expected.
- **P3** — spec-fixed or near-constant; externalize for consistency only.

All file/line references were captured 2026-05-21 against `main`. Verify the line still points at the literal before acting — the surrounding code may have shifted.

---

## 1. Fees and money amounts

| Priority | Location | Current value | Controls | Suggested key |
|---|---|---|---|---|
| **P1** | `escrow/EscrowCommissionCalculator.java:13-14` | `FLOOR_LINDENS = 50L`, `RATE_PERCENT = 5L` | The 5% / L$50-floor commission policy. `slpa.commission.default-rate: 0.05` already exists in yml and is snapshotted onto `Auction.commissionRate` at listing creation. **Verify whether the calculator reads the per-auction snapshot or this hardcoded constant** — if it uses `RATE_PERCENT`, that is a drift bug (a coupon-discounted commission rate would be ignored at payout). At minimum the `L$50` floor should be `slpa.commission.minimum-lindens`. | `slpa.commission.minimum-lindens: 50` |
| **P3 — NOT externalized (decision 2026-05-21)** | `auction/BidIncrementTable.java:38-41` | tiers `1_000` / `10_000` / `100_000`, increments `50`/`100`/`500`/`1_000` | Minimum-bid-increment ladder (DESIGN.md section 4.7). Spec-fixed but hardcoded; a structured config list would let promotions adjust it. **NOT externalized to config by decision 2026-05-21 — to become a per-auction-creator field; needs its own design pass.** | `slpa.auction.bid-increment-tiers` |

---

## 2. Time durations, deadlines, and TTLs

| Priority | Location | Current value | Controls | Suggested key |
|---|---|---|---|---|
| **P1** | `escrow/EscrowService.java:99` | `TRANSFER_DEADLINE_HOURS = 72` | The window a winning buyer has to complete the in-world transfer after auction end. A core escrow SLA; not in yml. | `slpa.escrow.transfer-deadline-hours: 72` |
| **P2** | `escrow/EscrowRetryPolicy.java:17-20` | `1m / 5m / 15m` backoff ladder | Terminal-command retry backoff schedule. | `slpa.escrow.command-retry-backoffs` |
| **P2** | `review/ReviewService.java:78` and `review/BlindReviewRevealTask.java:45` | `Duration.ofDays(14)` (twice) | Blind-review submission + reveal window. **Defined in two files** — they must stay equal; a single config key removes the drift risk. | `slpa.review.window-days: 14` |
| **P2** | `realty/service/RealtyGroupInvitationService.java:57` | `Duration.ofDays(7)` | Realty-group invitation lifetime before auto-expiry. | `slpa.realty.invitation-ttl-days: 7` |
| **P2** | `realty/slgroup/RealtyGroupSlGroupService.java:39` | `Duration.ofDays(7)` | SL-group registration verification-code TTL. | `slpa.realty.slgroup.verification-ttl-days: 7` |
| **P3** | `auction/ParcelSnapshotPhotoService.java:49` | `Duration.ofSeconds(5)` | WebClient timeout for SL parcel-snapshot image fetches. | `slpa.auction.snapshot-fetch-timeout: PT5S` |

---

## 3. Rate limiters

| Priority | Location | Current value | Controls | Suggested key |
|---|---|---|---|---|
| **P1** | `search/config/SearchRateLimitConfig.java:102-103` | `capacity=60`, refill `PT1M` | Per-client search request rate limit. Tuning candidate under real browse load. | `slpa.ratelimit.search.capacity: 60`, `slpa.ratelimit.search.refill: PT1M` |
| **P1** | `search/config/SearchRateLimitConfig.java:118-119` | `capacity=300`, refill `PT1M` | Per-client autocomplete-suggest rate limit. | `slpa.ratelimit.suggest.capacity: 300`, `slpa.ratelimit.suggest.refill: PT1M` |
| **P3** | `search/config/SearchRateLimitConfig.java:94` | `Duration.ofMinutes(10)` | Redis TTL for rate-limit bucket entries. | `slpa.ratelimit.cache-ttl: PT10M` |
| **P2** | `wallet/LedgerExportRateLimiter.java:21` | `Duration.ofSeconds(60)` | Minimum interval between a user's wallet-ledger CSV exports. | `slpa.wallet.ledger-export-rate-limit: PT60S` |
| **P2** | `realty/reports/RealtyGroupReportRateLimiter.java:42` | `DAILY_LIMIT = 5` | Per-user daily realty-group report submission quota. | `slpa.realty.reports.daily-limit: 5` |

---

## 4. Limits, caps, and counts

| Priority | Location | Current value | Controls | Suggested key |
|---|---|---|---|---|
| **P1** | `realty/RealtyGroup.java:119` | `memberSeatLimit = 50` | Default max members per realty group. Stored per-group but the default is hardcoded; a config default lets the platform shift the baseline. | `slpa.realty.default-member-seat-limit: 50` |
| **P2** | `auction/SavedAuctionService.java:81` | `SAVED_CAP = 500` | Maximum saved auctions per user. | `slpa.auction.saved-auctions-cap: 500` |
| **P2** | `escrow/DisputeEvidenceUploadService.java:29-30` | `MAX_IMAGES_PER_SIDE = 5`, `MAX_IMAGE_SIZE_BYTES = 5 MiB` | Escrow dispute evidence upload caps. (Mirrors the support-ticket attachment caps, which ARE externalized — inconsistency worth closing.) | `slpa.escrow.dispute-max-images-per-side: 5`, `slpa.escrow.dispute-max-image-bytes: 5242880` |
| **P2** | `review/BlindReviewRevealTask.java:46` | `BATCH_LIMIT = 500` | Rows processed per run of the hourly review-reveal scheduler. | `slpa.review.reveal-batch-limit: 500` |
| **P3** | `auction/AuctionSearchQuery.java:32-35` | `MAX_SIZE=100`, `MAX_DISTANCE=50`, `DEFAULT_DISTANCE=10`, `DEFAULT_SIZE=24` | Search API pagination + distance bounds and defaults. | `slpa.auction.search-max-page-size`, `search-max-distance`, `search-default-distance`, `search-default-page-size` |
| **P3** | `auction/CancellationStatusService.java:43` | `MAX_PAGE_SIZE = 50` | Cancellation-status API pagination cap. | `slpa.auction.cancellation-status-max-page: 50` |
| **P3** | `wallet/MeWalletController.java:65` | `MAX_LEDGER_PAGE_SIZE = 100` | Wallet-ledger CSV export pagination cap. | `slpa.wallet.ledger-max-page-size: 100` |
| **P3** | `auction/MyBidsService.java:60` | `DEFAULT_PAGE_SIZE = 20` | MyBids default page size. (The yml already has a comment referencing 20 but the literal lives here.) | `slpa.auction.my-bids-default-page-size: 20` |
| **P3** | `auction/SearchSuggestService.java:19-20,27` | `LISTINGS_LIMIT=5`, `REGIONS_LIMIT=3`, `RESOLVABLE_REGIONS_LIMIT=10` | Autocomplete result caps. | `slpa.auction.search-suggest-*-limit` |

---

## 5. Cache TTLs

| Priority | Location | Current value | Controls | Suggested key |
|---|---|---|---|---|
| **P2** | `auction/FeaturedCache.java:29` | `Duration.ofSeconds(60)` | Redis TTL for the featured-listings category cache. | `slpa.auction.featured-cache-ttl: PT60S` |
| **P2** | `auction/SearchResponseCache.java:26` | `Duration.ofSeconds(30)` | Redis TTL for cached search responses (must stay aligned with the 30s `Cache-Control` header the controller emits). | `slpa.auction.search-cache-ttl: PT30S` |

---

## 6. .NET bot worker

The bot worker (`bot/src/`) has no `slpa.*` yml equivalent — its tunables live in `appsettings.json` plus hardcoded C# literals. Most of these are wired through `*Options` classes, so externalizing is mechanical (move the literal default into `appsettings.json`).

| Priority | Location | Current value | Controls | Suggested key (appsettings) |
|---|---|---|---|---|
| **P2** | `Tasks/TaskLoop.cs:17` | `TimeSpan.FromSeconds(5)` | Backoff when the SL session is offline. | `Bot:OfflineBackoffSeconds: 5` |
| **P2** | `Tasks/TaskLoop.cs:18` | `TimeSpan.FromSeconds(15)` | Backoff when the task queue is empty (the bot's poll cadence for new work). | `Bot:EmptyQueueBackoffSeconds: 15` |
| **P3** | `Tasks/HeartbeatLoop.cs` (`HeartbeatOptions`) | `IntervalSeconds = 60` | Heartbeat submission interval to the backend. Already an `Options` field; just needs a default in `appsettings.json`. | `Bot:Heartbeat:IntervalSeconds: 60` |
| **P2** | `Backend/HttpBackendClient.cs:21-28` | backoff `[1,2,4,8,15]` s | HTTP retry backoff ladder for transient backend failures. | `Bot:HttpRetryBackoffSeconds` |
| **P3** | `Program.cs` | `Timeout = TimeSpan.FromSeconds(30)` | Backend HTTP client request timeout. | `Bot:HttpTimeoutSeconds: 30` |
| **P2** | `Sl/LibreMetaverseBotSession.cs:20-23` | backoff `[1,2,4,8,15,30,60]` s | SL session reconnection backoff ladder. | `Bot:ReconnectBackoffSeconds` |
| **P3** | `Options/IdleParkOptions.cs:25` | `ParkCooldownSeconds = 180` | Cooldown between idle-park operations. Already an `Options` field. | `Bot:IdlePark:ParkCooldownSeconds: 180` |
| **P3** | `Options/RateLimitOptions.cs:7` | `TeleportsPerMinute = 6` | SL teleport rate limit. Already an `Options` field; verify whether `appsettings.json` overrides it. | `Bot:RateLimit:TeleportsPerMinute: 6` |

---

## Recommended order of work

If acting on this audit, the highest-value batch is:

1. **`EscrowCommissionCalculator` (P1)** — first confirm whether it ignores the per-auction `commissionRate` snapshot. If it does, that is a correctness bug (coupon commission discounts silently dropped at payout), not just a config gap. Fix the read path, then externalize the L$50 floor.
2. **`escrow.transfer-deadline-hours` (P1)** — a core SLA value buried in a constant.
3. **Search + suggest rate limiters (P1)** — the values most likely to need tuning under real production browse traffic.
4. **`realty.default-member-seat-limit` (P1)** — a business-policy default.
5. The two duplicated-constant cases (**review window**, defined twice; **escrow dispute caps** vs the already-externalized support caps) — externalizing collapses a drift risk.

Everything in P3 can be a single follow-up sweep; it is consistency cleanup, not operational need.

## Out of scope / deliberately not listed

- State-machine discriminator values (e.g. `UserWalletDormancyTask` phase numbers 1-4/99) — these are not tunable policy; they are the state model.
- Per-auction stored values (`Auction.snipeWindowMin`, `Auction.commissionRate`) — already per-row data, not global config.
- Anything already under the `slpa.*` tree in `application.yml`.
- Secrets and environment-specific values — already handled via `${ENV_VAR}` placeholders.
