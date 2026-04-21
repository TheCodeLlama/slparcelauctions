# Epic 04 Sub-Spec 1: Auction Engine Backend

> **Related:** Epic 04 task files live in `docs/implementation/epic-04/`. This sub-spec covers tasks 04-01, 04-02, 04-03, 04-04 (server side), 04-05, and the `/users/me/bids` backend carved from 04-07. Sub-spec 2 will cover the frontend (04-04 client, 04-06, 04-07 UI).

## 1. Overview

Build the backend auction engine: bid placement with pessimistic-locked concurrency, eBay-style proxy bidding with state-machine resurrection, snipe protection evaluated per emitted bid row, reliable scheduled auction-end, inline buy-it-now, WebSocket broadcast on `/topic/auction/{id}` via `afterCommit` hygiene, the `GET /users/me/bids` endpoint with derived status, and a concurrency retrofit of existing cancellation/ownership/suspension paths onto the new lock.

**Architecture.** `BidService` serializes all writes per-auction via `PESSIMISTIC_WRITE` on the `Auction` row, emitting 1-2 bid rows per transaction (manual + optional proxy counter) and evaluating snipe/buy-now inside the same lock. A `@Scheduled` auction-end sweeper queries `status=ACTIVE AND ends_at <= now` every 15s and processes matches sequentially, each in its own `@Transactional closeOne(Long)` call that re-acquires the lock, classifies outcome, flips status to `ENDED`, exhausts any remaining `ACTIVE` proxies, and broadcasts `AUCTION_ENDED`. WebSocket fan-out is via a thin `AuctionBroadcastPublisher` registered on `TransactionSynchronization.afterCommit` so readers never observe intermediate state.

**Tech stack.** Spring Boot 4, Java 26, Spring Data JPA + Hibernate, PostgreSQL, Spring WebSocket/STOMP, Lombok, JPA entities as schema source (`ddl-auto: update`, no Flyway migrations), Spring `@Scheduled`, Spring `@Async` retained only on the existing `OwnershipCheckTask` (scheduler-path `closeOne` is sequential).

## 2. Scope

**In scope:**
- Tasks 04-01 (`BidService`), 04-02 (proxy bidding), 04-03 (snipe protection), 04-04 *server-side* (WebSocket publisher + envelopes), 04-05 (auction-end scheduler + buy-it-now), and `GET /api/v1/users/me/bids` backend from 04-07.
- New entities: `Bid`, `ProxyBid`. New columns on `Auction`: `currentBidderId`, `winnerUserId`, `finalBidAmount`, `endOutcome`, `endedAt`. (`bidCount` already exists.)
- Retrofit: `CancellationService`, `OwnershipCheckTask`, `SuspensionService` onto `AuctionRepository.findByIdForUpdate`.
- Dev-profile endpoints: `POST /api/v1/dev/auction-end/run-once`, `POST /api/v1/dev/auctions/{id}/close`.
- Doc cleanup: flag DESIGN.md §554 stale wording ("Bid history (anonymized or public - configurable)") in `DEFERRED_WORK.md` — identity visibility is resolved by §1589-1591 (public).

**Out of scope — deferred to sub-spec 2:**
- Frontend auction detail page (`/auctions/[id]`), `BidPanel`, `BidHistoryList`, `CountdownTimer`, `SellerProfileCard` (Task 04-06).
- WebSocket *client* subscription + UI integration (client half of 04-04).
- My Bids dashboard tab UI + My Listings enrichment (Task 04-07 frontend).

**Out of scope — deferred to later epics:**
- Outbid / auction-ended / won / reserve-not-met notifications (Epic 09).
- Anti-fraud IP correlation and admin triage UI (Epic 10).
- Escrow handoff from `ENDED+SOLD` auctions (Epic 05 — this sub-spec flips status; Epic 05 picks up).
- User-targeted WS events (`/user/{id}/queue/*`) — public topic only for Phase 1.
- Cancellation-specific WS broadcast — re-evaluate in sub-spec 2 with the UI in hand; current cancel on `ACTIVE` is visible via My Listings + next `GET /auctions/{id}` without urgent need for a WS event.

## 3. Success Criteria

1. Two concurrent bids on the same hot auction → one succeeds, the other receives a clean `400 BID_TOO_LOW` — never a race-induced "both accepted, one overwritten."
2. Concurrent bid + cancel → deterministic outcome (one commits, the other gets a documented `409`).
3. Concurrent bid + scheduler close at `ends_at` → scheduler either skips (bid extended via snipe) or closes cleanly; bid either commits (if still `ACTIVE` after lock) or gets `409 AUCTION_NOT_ACTIVE`.
4. Concurrent bid + ownership-suspension → deterministic outcome; bid that commits before the suspension is preserved, bid that commits after sees `409`.
5. 15-second scheduler tick closes every expired auction within 30s of `ends_at`, including snipe-extended ones.
6. Proxy resolution produces a clean 1-or-2-row bid history per transaction (no simulated climbing ladder).
7. **Manual bid at exactly `P_max` → `400 BID_TOO_LOW`; the proxy retains the position.** (Tie flips to proxy per Q3 decision.)
8. **Increasing an `EXHAUSTED` proxy's max via `PUT` reactivates the row to `ACTIVE` and re-runs step 2 resolution** against the current winning proxy (if any), emitting correct bid rows inline.
9. `GET /api/v1/users/me/bids` returns correct derived status for all 7 buckets (`WINNING`, `OUTBID`, `WON`, `LOST`, `RESERVE_NOT_MET`, `CANCELLED`, `SUSPENDED`).
10. WebSocket envelopes arrive only after DB commit (reader never observes "current bid X on WS, but GET shows X-1").
11. `PROXY_AUTO` bid rows always have `ip_address = null` — the proxy owner is not at their keyboard when the counter-bid fires.
12. Test coverage on concurrency paths: bid-bid race, bid-cancel race, bid-scheduler race, bid-suspend race, proxy-update-during-counter race.

## 4. Route Map

### REST (`/api/v1`)

| Method | Path | Auth | Body | Success | Errors |
|---|---|---|---|---|---|
| `POST` | `/auctions/{id}/bids` | JWT (verified, not seller) | `{ amount: number }` | `201 BidResponse` | `400 BID_TOO_LOW` · `403 SELLER_CANNOT_BID / NOT_VERIFIED` · `404` · `409 AUCTION_NOT_ACTIVE / AUCTION_ALREADY_ENDED` |
| `GET` | `/auctions/{id}/bids` | public | — | `200 Page<BidHistoryEntry>` | `404` |
| `POST` | `/auctions/{id}/proxy-bid` | JWT (verified, not seller) | `{ maxAmount: number }` | `201 ProxyBidResponse` | `400 BID_TOO_LOW` · `403` · `404` · `409 PROXY_BID_ALREADY_EXISTS` |
| `PUT` | `/auctions/{id}/proxy-bid` | JWT | `{ maxAmount: number }` | `200 ProxyBidResponse` | `400 INVALID_PROXY_MAX / BID_TOO_LOW` · `404 PROXY_BID_NOT_FOUND` · `409 INVALID_PROXY_STATE` |
| `DELETE` | `/auctions/{id}/proxy-bid` | JWT | — | `204` | `404` · `409 CANNOT_CANCEL_WINNING_PROXY` |
| `GET` | `/auctions/{id}/proxy-bid` | JWT | — | `200 ProxyBidResponse` · `404` | — |
| `GET` | `/users/me/bids` | JWT | `?status=active\|won\|lost\|all&page=0&size=20` | `200 Page<MyBidSummary>` | — |

### Dev-profile only (`@Profile("dev")`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/dev/auction-end/run-once` | Synchronously invoke the scheduler sweep. Returns `{ processed: [ids] }`. |
| `POST` | `/dev/auctions/{id}/close` | Force-close a single auction. Returns `{ closedId, outcome }`. |

### WebSocket

- **Topic:** `/topic/auction/{auctionId}` — public, no authentication required (STOMP SUBSCRIBE).
- **Envelopes:** `BidSettlementEnvelope` on bid placement / proxy set / proxy update; `AuctionEndedEnvelope` on scheduler close or buy-it-now.
- **Publication:** only from `AuctionBroadcastPublisher` inside a `TransactionSynchronization.afterCommit` callback — never inline, never pre-commit.
- **No user-targeted queues** in this sub-spec.

### Status-code discipline

| Code | Meaning |
|---|---|
| `400` | Bidder's input is invalid (amount too low, max decrease, invalid JSON). Retry with a new value. |
| `403` | Bidder is never allowed on this auction (seller, or unverified). No retry helps. |
| `404` | Resource missing (auction, proxy). |
| `409` | Resource exists but is in the wrong state (not `ACTIVE`, already ended, "you can't cancel your proxy while winning"). |

No `422` in this sub-spec — reserved for the pre-validation shape from Epic 03 sub-1 group-land gate.

### Rate limits

**None.** DESIGN.md §1542 ("No bid rate limiting - let people bid freely") and §1913 ("No bid rate limiting") are authoritative. Flag-only anti-fraud via persisted `bids.ip_address`.

## 5. Concurrency Model

### Lock strategy — pessimistic row lock on the `Auction` row

`AuctionRepository.findByIdForUpdate(Long id)` annotated `@Lock(LockModeType.PESSIMISTIC_WRITE)`. All state-mutating paths open a transaction, invoke `findByIdForUpdate` as their first action, then do validation and writes under the held lock. Readers (`findById`, `findAll`, all public-read endpoints) are unaffected — only writers serialize per auction.

### Paths that must acquire the lock

| Path | Existing or new | Notes |
|---|---|---|
| `BidService.placeBid` | new | Sections 6-8. |
| `ProxyBidService.createProxy` / `updateProxyMax` / `cancelProxy` | new | Section 7. |
| `AuctionEndTask.closeOne` | new | Section 8. |
| `CancellationService.cancel` | **retrofit** | Today takes a pre-loaded `Auction` entity from the controller; change signature to `cancel(Long auctionId, String reason)`, fetch via `findByIdForUpdate` inside the `@Transactional`. |
| `OwnershipCheckTask.checkOne` | **retrofit** | Change `auctionRepo.findById(auctionId)` to `auctionRepo.findByIdForUpdate(auctionId)`. |
| `SuspensionService.suspendFor*` | **verified** | Already called inside a locked transaction; no direct change, but spec calls this out so no future edit introduces a non-locked re-fetch. |

### Why pessimistic instead of optimistic

- Auction bid volume is bounded (<100 bids/sec on the hottest imaginable auction); pessimistic-lock serialization is a non-issue at this scale.
- Proxy resolution must emit 1-2 bid rows atomically and update `currentBid`/`currentBidderId`/`bidCount`/`endsAt` in a single commit. Optimistic locking with retry would need to replay the full resolution algorithm idempotently, including the tie/resurrection branches — a footgun.
- Snipe extension math runs inside the lock with a fresh `endsAt` read — no stale-read race.
- Concurrent bid + cancel + ownership-suspend all coordinate through one lock with deterministic winner — cleaner test surface than reasoning about three-way optimistic retries.

### Isolation level

`READ_COMMITTED` (Postgres default). The pessimistic lock on the auction row gives us serializability *for that auction* without needing a `SERIALIZABLE` transaction — the only shared mutable state is the auction row itself.

### Transaction synchronization for WS broadcast

Envelopes publish from `TransactionSynchronizationManager.registerSynchronization(TransactionSynchronizationAdapter { afterCommit() -> publisher.publishX(...) })`. Two reasons:

1. A reader subscribed to the topic must never see a `currentBid` value that isn't visible to a fresh `GET /auctions/{id}`. Publishing pre-commit breaks this.
2. If the transaction rolls back (rare but possible — constraint violation, lock timeout), we silently don't publish. Mid-transaction publish would have to be explicitly undone, which isn't a pattern STOMP supports.

## 6. Bid Placement Algorithm

`BidService.placeBid(auctionId, bidderId, amount, ipAddress)`:

```
@Transactional(isolation = READ_COMMITTED)
placeBid(auctionId, bidderId, amount, ipAddress):
  1. auction = auctionRepo.findByIdForUpdate(auctionId)
       if missing → throw AuctionNotFoundException → 404

  2. if auction.status != ACTIVE → throw InvalidAuctionStateException → 409 AUCTION_NOT_ACTIVE
  3. if auction.endsAt <= now(clock) → throw AuctionAlreadyEndedException → 409 AUCTION_ALREADY_ENDED

  4. bidder = userRepo.findById(bidderId).orElseThrow()
     if not verified → throw NotVerifiedException → 403 NOT_VERIFIED
     if bidder.id == auction.seller.id → throw SellerCannotBidException → 403 SELLER_CANNOT_BID

  5. minRequired = auction.currentBid > 0
       ? auction.currentBid + minIncrement(auction.currentBid)
       : auction.startingBid
     if amount < minRequired → throw BidTooLowException(minRequired) → 400 BID_TOO_LOW

  6. emitted = []
  7. competingProxy = proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                          auctionId, ACTIVE, bidderId)

  8. if competingProxy present:
       if amount > competingProxy.maxAmount:            // strict > (Q3 flip; proxy wins ties)
         competingProxy.status = EXHAUSTED
         competingProxy.updatedAt = now(clock)
         proxyBidRepo.save(competingProxy)
         emitted += insertBid(auction, bidder, amount, MANUAL, null, ipAddress)
       else:
         counterAmount = min(amount + minIncrement(amount), competingProxy.maxAmount)
         emitted += insertBid(auction, bidder, amount, MANUAL, null, ipAddress)
         emitted += insertBid(auction, competingProxy.bidder, counterAmount,
                              PROXY_AUTO, competingProxy.id, null)   // null IP: system bid
     else:
       emitted += insertBid(auction, bidder, amount, MANUAL, null, ipAddress)

  9. applySnipeAndBuyNow(auction, emitted)

 10. top = emitted[-1]
     auction.currentBid = top.amount
     auction.currentBidderId = top.bidder.id
     auction.bidCount += emitted.size
     auctionRepo.save(auction)

 11. TransactionSynchronizationManager.registerSynchronization(afterCommit: () ->
       if auction.status == ENDED:
         publisher.publishEnded(AuctionEndedEnvelope.of(auction, clock))
       else:
         publisher.publishSettlement(BidSettlementEnvelope.of(auction, emitted, clock)))

 12. return BidResponse(top, auction)
```

### Bid increment table

From DESIGN.md §4.7. Static helper `BidIncrementTable.minIncrement(currentBidLong)`:

| `currentBid` range | `minIncrement` |
|---|---|
| L$0 - L$999 | L$50 |
| L$1 000 - L$9 999 | L$100 |
| L$10 000 - L$99 999 | L$500 |
| L$100 000+ | L$1 000 |

Table-driven parametric test covers every tier boundary (`0`, `50`, `999`, `1000`, `9999`, `10000`, `99999`, `100000`).

### Snipe protection — per-emitted-row evaluation

```
applySnipeAndBuyNow(auction, emitted):
  // Snipe: evaluate each bid in order; each qualifying bid extends from its own timestamp.
  if auction.snipeProtect:
    window = Duration.ofMinutes(auction.snipeWindowMin)
    for bid in emitted:
      if Duration.between(bid.createdAt, auction.endsAt).compareTo(window) <= 0:
        newEnd = bid.createdAt.plus(window)
        bid.snipeExtensionMinutes = auction.snipeWindowMin
        bid.newEndsAt = newEnd
        auction.endsAt = newEnd

  // Buy-it-now: if any emitted bid amount >= buyNowPrice, end inline with BOUGHT_NOW.
  if auction.buyNowPrice != null:
    for bid in emitted:
      if bid.amount >= auction.buyNowPrice:
        auction.status = ENDED
        auction.endOutcome = BOUGHT_NOW
        auction.winnerUserId = emitted[-1].bidder.id
        auction.finalBidAmount = emitted[-1].amount
        auction.endedAt = now(clock)
        proxyBidRepo.exhaustAllActiveByAuctionId(auction.id)
        return
```

Extensions **stack**: three bids in a row within the window each extend the auction. The lock guarantees no other transaction can re-read `endsAt` until this one commits, so the per-row evaluation is atomic.

### IP capture

- Spring config: `server.forward-headers-strategy=framework` (adds `ForwardedHeaderFilter` behind trusted proxies).
- `BidController` passes `HttpServletRequest` to the service; `BidService` extracts `request.getHeader("X-Forwarded-For")` and takes the leftmost entry (original client), trimming whitespace. If absent, falls back to `request.getRemoteAddr()`.
- IPv6-safe: column `ip_address VARCHAR(45)` nullable.
- Stored only on `MANUAL` bids. `PROXY_AUTO` bids store `null` — no HTTP request initiated them. `BUY_NOW` is always a `MANUAL` bid that happened to hit the buy-now price; it stores the caller's IP.
- Broken-chain case (header present but not from a trusted proxy): store `null` rather than a bogus internal IP. Anti-fraud is a soft signal; a clean null is better than a misleading value.

### Error payloads

Matches the Epic 03 sub-1 `ApiError` shape (error code + message + optional extras):

```json
{ "error": "BID_TOO_LOW", "message": "Minimum bid is L$550.", "minRequired": 550 }
{ "error": "SELLER_CANNOT_BID", "message": "Sellers cannot bid on their own auction." }
{ "error": "NOT_VERIFIED", "message": "Bidding requires a verified SL avatar." }
{ "error": "AUCTION_NOT_ACTIVE", "message": "This auction is not accepting bids.", "currentStatus": "ENDED" }
{ "error": "AUCTION_ALREADY_ENDED", "message": "This auction ended at 2026-04-20T12:00:00Z." }
```

## 7. Proxy Bidding Algorithm

### State machine

- `ACTIVE` — proxy is live; system may counter-bid on its behalf. At most one `ACTIVE` row per `(auction_id, user_id)` at any time (dual-enforced: DB partial unique index + application-level check).
- `EXHAUSTED` — another bidder has beaten this proxy's max. Row stays for audit and to support resurrection via `PUT` increase.
- `CANCELLED` — explicit `DELETE` while the owner was not winning. Dormant, no effect, cannot be un-cancelled (`PUT` on `CANCELLED` returns `409 INVALID_PROXY_STATE`; create a new proxy instead).

### `POST /proxy-bid` — createProxy

```
@Transactional
createProxy(auctionId, bidderId, maxAmount, ipAddress):
  1. auction = auctionRepo.findByIdForUpdate(auctionId)
  2. validate: status=ACTIVE, not ended, bidder verified, bidder != seller
  3. if proxyBidRepo.existsByAuctionIdAndBidderIdAndStatus(auctionId, bidderId, ACTIVE):
       throw ProxyBidAlreadyExistsException → 409
  4. minRequired = auction.currentBid > 0
                    ? auction.currentBid + minIncrement(auction.currentBid)
                    : auction.startingBid
     if maxAmount < minRequired → throw BidTooLowException → 400
  5. newProxy = proxyBidRepo.save(ProxyBid(auction, bidder, maxAmount, ACTIVE, now, now))
  6. emitted = resolveProxyResolution(auction, newProxy)
  7. applySnipeAndBuyNow(auction, emitted)
  8. if emitted non-empty:
       auction.currentBid = emitted[-1].amount
       auction.currentBidderId = emitted[-1].bidder.id
       auction.bidCount += emitted.size
       auctionRepo.save(auction)
  9. afterCommit → publish settlement (or ended if buy-now)
 10. return ProxyBidResponse(newProxy)
```

### Step 2 resolution helper — `resolveProxyResolution`

Shared by `createProxy`, `updateProxyMax` (ACTIVE branch with new winner context), and `updateProxyMax` (EXHAUSTED resurrection branch).

```
resolveProxyResolution(auction, proxy):
  existing = proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                 auction.id, ACTIVE, proxy.bidder.id)
  emitted = []

  if existing is null:
    // No competing proxy — place the opening bid.
    openingAmount = max(auction.currentBid + minIncrement(auction.currentBid),
                        auction.startingBid)
    if openingAmount > proxy.maxAmount:
      throw BidTooLowException(openingAmount)   // defensive; pre-validated upstream
    emitted += insertBid(auction, proxy.bidder, openingAmount,
                         PROXY_AUTO, proxy.id, null)
    return emitted

  if proxy.maxAmount > existing.maxAmount:
    // proxy wins: counter at min(existing.max + increment, proxy.max)
    settleAmount = min(existing.maxAmount + minIncrement(existing.maxAmount),
                       proxy.maxAmount)
    existing.status = EXHAUSTED
    existing.updatedAt = now(clock)
    proxyBidRepo.save(existing)
    emitted += insertBid(auction, proxy.bidder, settleAmount, PROXY_AUTO, proxy.id, null)

  else if proxy.maxAmount < existing.maxAmount:
    // existing wins: proxy exhausted; emit flush + counter
    settleAmount = min(proxy.maxAmount + minIncrement(proxy.maxAmount),
                       existing.maxAmount)
    proxy.status = EXHAUSTED
    proxy.updatedAt = now(clock)
    proxyBidRepo.save(proxy)
    emitted += insertBid(auction, proxy.bidder, proxy.maxAmount, PROXY_AUTO, proxy.id, null)
    emitted += insertBid(auction, existing.bidder, settleAmount, PROXY_AUTO, existing.id, null)

  else:
    // equal max — earliest created_at wins
    if existing.createdAt.isBefore(proxy.createdAt):
      proxy.status = EXHAUSTED
      proxy.updatedAt = now(clock)
      proxyBidRepo.save(proxy)
      emitted += insertBid(auction, proxy.bidder, proxy.maxAmount, PROXY_AUTO, proxy.id, null)
      emitted += insertBid(auction, existing.bidder, existing.maxAmount, PROXY_AUTO, existing.id, null)
    else:
      // proxy is the earlier row (rare — can occur on resurrection of a pre-existing EXHAUSTED row)
      existing.status = EXHAUSTED
      existing.updatedAt = now(clock)
      proxyBidRepo.save(existing)
      emitted += insertBid(auction, proxy.bidder, proxy.maxAmount, PROXY_AUTO, proxy.id, null)

  return emitted
```

### `PUT /proxy-bid` — updateProxyMax

```
@Transactional
updateProxyMax(auctionId, bidderId, newMax, ipAddress):
  1. auction = auctionRepo.findByIdForUpdate(auctionId)
  2. validate: status=ACTIVE, not ended, bidder verified

  3. proxy = proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(auctionId, bidderId)
     if proxy is null → throw ProxyBidNotFoundException → 404

  4. if proxy.status == CANCELLED:
       throw InvalidProxyStateException("Cancelled proxy cannot be updated; create a new one") → 409

  5. if proxy.status == ACTIVE:
       if bidderId == auction.currentBidderId:
         // Caller is currently winning — max must be > their own winning bid.
         if newMax <= auction.currentBid → throw InvalidProxyMaxException → 400
         proxy.maxAmount = newMax
         proxy.updatedAt = now(clock)
         proxyBidRepo.save(proxy)
         // No new bid rows: caller is already winning and no competing proxy could have
         // survived in ACTIVE status under the lock. No WS publish (nothing public changed).
         return ProxyBidResponse(proxy)
       else:
         // Defensive branch: ACTIVE-but-not-winning is unreachable under the pessimistic-
         // lock semantics. Any transaction that moved currentBidderId off this caller would
         // have flipped their proxy to EXHAUSTED in the same commit. Kept only to avoid
         // silently mis-handling a hypothetical lock-acquisition regression.
         log.warn("updateProxyMax hit ACTIVE-but-not-winning branch for proxy {}; "
                + "this should be unreachable — investigate lock acquisition.", proxy.id)
         minRequired = auction.currentBid + minIncrement(auction.currentBid)
         if newMax < minRequired → throw BidTooLowException → 400
         proxy.maxAmount = newMax
         proxy.updatedAt = now(clock)
         proxyBidRepo.save(proxy)
         emitted = resolveProxyResolution(auction, proxy)

  6. elif proxy.status == EXHAUSTED:
       // Resurrection path — increase only.
       if newMax <= proxy.maxAmount:
         throw InvalidProxyMaxException("Increase only on exhausted proxy") → 400
       minRequired = auction.currentBid + minIncrement(auction.currentBid)
       if newMax < minRequired → throw BidTooLowException → 400
       proxy.status = ACTIVE
       proxy.maxAmount = newMax
       proxy.updatedAt = now(clock)
       proxyBidRepo.save(proxy)
       emitted = resolveProxyResolution(auction, proxy)

  7. applySnipeAndBuyNow(auction, emitted)
  8. if emitted non-empty:
       update auction.currentBid, currentBidderId, bidCount; save
  9. afterCommit → publish settlement / ended only if emitted non-empty
 10. return ProxyBidResponse(proxy)
```

### `DELETE /proxy-bid` — cancelProxy

```
@Transactional
cancelProxy(auctionId, bidderId):
  1. auction = auctionRepo.findByIdForUpdate(auctionId)
  2. proxy = proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(auctionId, bidderId)
  3. if proxy is null or proxy.status != ACTIVE → throw ProxyBidNotFoundException → 404
  4. if auction.currentBidderId == bidderId:
       throw CannotCancelWinningProxyException → 409
  5. proxy.status = CANCELLED
     proxy.updatedAt = now(clock)
     proxyBidRepo.save(proxy)
  // No bid rows, no WS publish (currentBid unchanged).
  // Return 204.
```

### `GET /proxy-bid`

Returns the most recent row for `(auction, caller)` regardless of status. Lets the client render "You had an ACTIVE proxy / you were exhausted at L$X / you cancelled at time Y." If no row exists at all → `404`.

### Invariants pinned by tests

- Tie at equality (Q3 flip): `amount == P_max` never exhausts the proxy.
- Tie at equality across two proxies: earliest `created_at` wins.
- Resurrection: `EXHAUSTED` → `ACTIVE` via `PUT` increase → runs resolution → correct bid rows emitted.
- Cancel-while-winning: `409`, no state change.
- Exactly one `ACTIVE` row per `(auction, user)` at any time — DB partial unique index + application check.
- `PROXY_AUTO` bid rows always have `ip_address = null`.
- Proxy auto-bid amounts never exceed `maxAmount` (capped at `min(..., maxAmount)` in every settle branch).

## 8. Auction-End Scheduler + Buy-It-Now

### Scheduler bean

```java
@Service
@ConditionalOnProperty(name = "slpa.auction-end.enabled",
                       havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor @Slf4j
public class AuctionEndScheduler {
    private final AuctionRepository auctionRepo;
    private final AuctionEndTask auctionEndTask;
    private final Clock clock;

    @Scheduled(fixedDelayString =
        "${slpa.auction-end.scheduler-frequency:PT15S}")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> dueIds = auctionRepo.findActiveIdsDueForEnd(now);
        if (dueIds.isEmpty()) return;
        log.info("AuctionEndScheduler processing {} due auctions", dueIds.size());
        for (Long id : dueIds) {
            try {
                auctionEndTask.closeOne(id);
            } catch (RuntimeException e) {
                log.error("Failed to close auction {}: {}", id, e.getMessage(), e);
                // continue: a single auction's failure must not abort the sweep
            }
        }
    }
}
```

**Sequential, not `@Async`.** The workload is DB-bound (acquire lock, classify, save), ~10ms per close. Even a 100-auction batch resolves in ~1s — well under the 15s tick budget. `@Async` with an unbounded `SimpleAsyncTaskExecutor` would be a footgun; a bounded thread pool is premature optimization. If Phase 2+ traffic demands parallelism, adding a bounded pool is a localized change.

### Per-auction worker

```java
@Service @RequiredArgsConstructor @Slf4j
public class AuctionEndTask {
    private final AuctionRepository auctionRepo;
    private final ProxyBidRepository proxyBidRepo;
    private final UserRepository userRepo;
    private final AuctionBroadcastPublisher publisher;
    private final Clock clock;

    @Transactional
    public void closeOne(Long auctionId) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId).orElse(null);
        if (a == null) return;
        if (a.getStatus() != AuctionStatus.ACTIVE) return;                  // already closed
        if (a.getEndsAt().isAfter(OffsetDateTime.now(clock))) return;       // snipe-extended

        AuctionEndOutcome outcome = classifyOutcome(a);
        a.setStatus(AuctionStatus.ENDED);
        a.setEndOutcome(outcome);
        a.setEndedAt(OffsetDateTime.now(clock));
        if (outcome == AuctionEndOutcome.SOLD) {
            a.setWinnerUserId(a.getCurrentBidderId());
            a.setFinalBidAmount(a.getCurrentBid());
        }
        auctionRepo.save(a);
        proxyBidRepo.exhaustAllActiveByAuctionId(auctionId);

        String winnerDisplayName = outcome == AuctionEndOutcome.SOLD
            ? userRepo.findById(a.getCurrentBidderId())
                      .map(User::getDisplayName).orElse(null)
            : null;
        Long finalBid = outcome == AuctionEndOutcome.SOLD ? a.getCurrentBid() : null;
        Long winnerId = outcome == AuctionEndOutcome.SOLD ? a.getCurrentBidderId() : null;

        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronizationAdapter() {
                @Override public void afterCommit() {
                    publisher.publishEnded(new AuctionEndedEnvelope(
                        auctionId, OffsetDateTime.now(clock), a.getEndsAt(),
                        outcome, finalBid, winnerId, winnerDisplayName,
                        a.getBidCount().longValue()));
                }
            });
    }

    private AuctionEndOutcome classifyOutcome(Auction a) {
        if (a.getBidCount() == null || a.getBidCount() == 0)
            return AuctionEndOutcome.NO_BIDS;
        if (a.getReservePrice() != null && a.getCurrentBid() < a.getReservePrice())
            return AuctionEndOutcome.RESERVE_NOT_MET;
        return AuctionEndOutcome.SOLD;
    }
}
```

### Buy-it-now (inline in `BidService` — not here)

Handled by `applySnipeAndBuyNow` inside the bid-placement transaction. When triggered:
- Flips `status=ENDED`, `endOutcome=BOUGHT_NOW`, populates `winnerUserId`, `finalBidAmount`, `endedAt`.
- Exhausts remaining `ACTIVE` proxies via `proxyBidRepo.exhaustAllActiveByAuctionId`.
- `afterCommit` publishes `AuctionEndedEnvelope` with `endOutcome=BOUGHT_NOW` — not `BidSettlementEnvelope`.
- Winner display name is in hand (bidder `User` was loaded in step 4 of `placeBid` — no extra `userRepo` call needed for buy-now path).

### Config

```yaml
slpa:
  auction-end:
    enabled: true
    scheduler-frequency: PT15S
```

Tests disable the scheduler (`slpa.auction-end.enabled=false`) and trigger sweeps manually via the dev endpoint or direct bean invocation to avoid races.

### Dev-profile endpoints

```java
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
@RequiredArgsConstructor
public class DevAuctionEndController {
    private final AuctionEndScheduler scheduler;
    private final AuctionEndTask task;

    @PostMapping("/auction-end/run-once")
    public Map<String, Object> runOnce() {
        // Synchronous sweep; returns the ids that were dispatched.
        // Implementation: expose a test-only runOnce() on the scheduler or re-run the body.
        ...
    }

    @PostMapping("/auctions/{id}/close")
    public Map<String, Object> closeOne(@PathVariable Long id) {
        task.closeOne(id);
        return Map.of("closedId", id);
    }
}
```

## 9. Entities, Repositories, DTOs

### `Bid` entity

```java
@Entity @Table(name = "bids",
    indexes = {
        @Index(name = "ix_bids_auction_created",     columnList = "auction_id, created_at"),
        @Index(name = "ix_bids_user_auction_amount", columnList = "user_id, auction_id, amount DESC"),
        @Index(name = "ix_bids_user_created",        columnList = "user_id, created_at DESC")
    })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Bid {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User bidder;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "bid_type", nullable = false, length = 16)
    private BidType bidType;

    @Column(name = "proxy_bid_id")
    private Long proxyBidId;           // soft FK; nullable

    @Column(name = "snipe_extension_minutes")
    private Integer snipeExtensionMinutes;

    @Column(name = "new_ends_at")
    private OffsetDateTime newEndsAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

`proxyBidId` is a plain `Long` (soft FK) rather than `@ManyToOne ProxyBid` — no code path navigates bid → proxy, and a raw id avoids the eager-fetch footgun and keeps writes cheap.

### `ProxyBid` entity

```java
@Entity @Table(name = "proxy_bids",
    indexes = {
        @Index(name = "ix_proxy_bids_auction_status", columnList = "auction_id, status"),
        @Index(name = "ix_proxy_bids_user_auction",   columnList = "user_id, auction_id")
    })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProxyBid {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User bidder;

    @Column(name = "max_amount", nullable = false)
    private Long maxAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProxyBidStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

### Partial unique index — dual enforcement

**Application-level** (fast-fail, surfaces 409):
```java
if (proxyBidRepo.existsByAuctionIdAndBidderIdAndStatus(auctionId, bidderId, ACTIVE)) {
    throw new ProxyBidAlreadyExistsException(auctionId, bidderId);
}
```

**Database-level** (defense-in-depth): new `ProxyBidPartialUniqueIndexInitializer`, mirrors `AuctionStatusCheckConstraintInitializer` from Epic 03 sub-2:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyBidPartialUniqueIndexInitializer {
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() {
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS "
                   + "proxy_bids_one_active_per_user "
                   + "ON proxy_bids (auction_id, user_id) "
                   + "WHERE status = 'ACTIVE'");
        log.info("Ensured partial unique index on proxy_bids (auction_id, user_id) WHERE status='ACTIVE'");
    }
}
```

### New columns on `Auction`

```java
@Column(name = "current_bidder_id")
private Long currentBidderId;

@Column(name = "winner_user_id")
private Long winnerUserId;

@Column(name = "final_bid_amount")
private Long finalBidAmount;

@Enumerated(EnumType.STRING)
@Column(name = "end_outcome", length = 32)
private AuctionEndOutcome endOutcome;

@Column(name = "ended_at")
private OffsetDateTime endedAt;
```

All nullable — `ddl-auto: update` adds them cleanly without backfill gymnastics. `bidCount` already exists on the entity (`Auction.java:129-130`, `nullable=false` default `= 0`) — no change needed.

Also add composite index on `(status, ends_at)` via `@Table(indexes={...})` on the existing `Auction` entity to support the scheduler query:

```java
@Index(name = "ix_auctions_status_ends_at", columnList = "status, ends_at")
```

### Repositories

```java
public interface BidRepository extends JpaRepository<Bid, Long> {
    Page<Bid> findByAuctionIdOrderByCreatedAtDesc(Long auctionId, Pageable pageable);
    List<Bid> findByAuctionIdOrderByCreatedAtAsc(Long auctionId);
    // MyBids query: see Section 10.
}

public interface ProxyBidRepository extends JpaRepository<ProxyBid, Long> {
    Optional<ProxyBid> findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(Long auctionId, Long bidderId);
    boolean existsByAuctionIdAndBidderIdAndStatus(Long auctionId, Long bidderId, ProxyBidStatus status);
    Optional<ProxyBid> findFirstByAuctionIdAndStatusAndBidderIdNot(Long auctionId, ProxyBidStatus status, Long excludedBidderId);

    @Modifying
    @Query("UPDATE ProxyBid p SET p.status = 'EXHAUSTED', p.updatedAt = CURRENT_TIMESTAMP "
         + "WHERE p.auction.id = :auctionId AND p.status = 'ACTIVE'")
    int exhaustAllActiveByAuctionId(@Param("auctionId") Long auctionId);
}

// Additions to existing AuctionRepository
public interface AuctionRepository extends JpaRepository<Auction, Long> {
    // existing methods retained

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Auction a WHERE a.id = :id")
    Optional<Auction> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT a.id FROM Auction a WHERE a.status = 'ACTIVE' AND a.endsAt <= :now")
    List<Long> findActiveIdsDueForEnd(@Param("now") OffsetDateTime now);
}
```

### Enums

```java
public enum BidType { MANUAL, PROXY_AUTO, BUY_NOW }
public enum ProxyBidStatus { ACTIVE, EXHAUSTED, CANCELLED }
public enum AuctionEndOutcome { SOLD, RESERVE_NOT_MET, NO_BIDS, BOUGHT_NOW }
public enum MyBidStatus { WINNING, OUTBID, WON, LOST, RESERVE_NOT_MET, CANCELLED, SUSPENDED }
```

No changes to the existing `AuctionStatus` enum (`ACTIVE`, `ENDED`, `SUSPENDED`, etc.). `AuctionStatusCheckConstraintInitializer` (Epic 03 sub-2) does **not** need an update.

### Request / response DTOs

```java
public record PlaceBidRequest(@NotNull @Min(1) Long amount) {}
public record SetProxyBidRequest(@NotNull @Min(1) Long maxAmount) {}

public record BidResponse(
    Long bidId, Long auctionId, Long amount, BidType bidType,
    Long bidCount, OffsetDateTime endsAt, OffsetDateTime originalEndsAt,
    Integer snipeExtensionMinutes, OffsetDateTime newEndsAt,
    boolean buyNowTriggered) {}

public record BidHistoryEntry(
    Long bidId, Long userId, String bidderDisplayName,
    Long amount, BidType bidType,
    Integer snipeExtensionMinutes, OffsetDateTime newEndsAt,
    OffsetDateTime createdAt) {}
// Publicly visible; no ip_address, no proxy max, no proxy_bid_id.
// bidType IS included — eBay-style auctions show proxy vs manual per DESIGN.md §1589-1591.

public record ProxyBidResponse(
    Long proxyBidId, Long auctionId, Long maxAmount,
    ProxyBidStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

public record MyBidSummary(
    AuctionSummaryForMyBids auction,
    Long myHighestBidAmount, OffsetDateTime myHighestBidAt,
    Long myProxyMaxAmount,             // null unless caller has an ACTIVE proxy
    MyBidStatus myBidStatus) {}

public record AuctionSummaryForMyBids(
    Long id, AuctionStatus status, AuctionEndOutcome endOutcome,
    String parcelName, String parcelRegion, Integer parcelAreaSqm, String snapshotUrl,
    OffsetDateTime endsAt, OffsetDateTime endedAt,
    Long currentBid, Long bidderCount,
    Long sellerUserId, String sellerDisplayName) {}
```

### WebSocket envelope DTOs

```java
public record BidSettlementEnvelope(
    String type,                       // always "BID_SETTLEMENT"
    Long auctionId,
    OffsetDateTime serverTime,
    Long currentBid,
    Long currentBidderId,
    String currentBidderDisplayName,
    Long bidCount,
    OffsetDateTime endsAt,
    OffsetDateTime originalEndsAt,
    List<BidHistoryEntry> newBids) {
    // Factory: BidSettlementEnvelope.of(Auction, List<Bid> emitted, Clock)
}

public record AuctionEndedEnvelope(
    String type,                       // always "AUCTION_ENDED"
    Long auctionId,
    OffsetDateTime serverTime,
    OffsetDateTime endsAt,
    AuctionEndOutcome endOutcome,
    Long finalBid,                     // null for RESERVE_NOT_MET, NO_BIDS
    Long winnerUserId,                 // null for RESERVE_NOT_MET, NO_BIDS
    String winnerDisplayName,          // null for RESERVE_NOT_MET, NO_BIDS
    Long bidCount) {}
```

## 10. `GET /users/me/bids` Query + Derivation

### Query strategy

One JPQL query with a subquery for the user's max bid per auction, left-joined to the user's `ACTIVE` proxy row (if any). `PostgreSQL FILTER (WHERE ...)` preferred; if Hibernate HQL rejects, fall back to a two-query shape:

1. `SELECT auction_id, MAX(amount), MAX(created_at) FILTER (WHERE amount = max) FROM bids WHERE user_id = :userId GROUP BY auction_id`
2. Join with `Auction`, left-join with `ProxyBid WHERE status='ACTIVE'`, filter by `status` param.

At Phase 1 volumes (a user will have tens of bid rows, not thousands), either shape is fine.

### Filter mapping

| `status` param | SQL filter | Post-filter in service |
|---|---|---|
| `active` | `auction.status = 'ACTIVE'` | none |
| `won` | `auction.status = 'ENDED'` | `myBidStatus == WON` |
| `lost` | `auction.status IN ('ENDED', 'CANCELLED', 'SUSPENDED')` | `myBidStatus IN (LOST, RESERVE_NOT_MET, CANCELLED, SUSPENDED)` |
| `all` (default) | no filter | none |

For `won` / `lost`, pagination-by-SQL is not pristine because derivation depends on `currentBidderId`/`winnerUserId` comparisons against `:userId`. At Phase 1 volumes this is acceptable. If the dashboard ever becomes hot, add a materialized `my_bid_status` column on `bids` or a covering view.

### Status derivation

```java
static MyBidStatus derive(Long userId, Auction a) {
    return switch (a.getStatus()) {
        case ACTIVE    -> Objects.equals(a.getCurrentBidderId(), userId) ? WINNING : OUTBID;
        case ENDED     -> switch (a.getEndOutcome()) {
            case SOLD, BOUGHT_NOW ->
                Objects.equals(a.getWinnerUserId(), userId) ? WON : LOST;
            case RESERVE_NOT_MET ->
                Objects.equals(a.getCurrentBidderId(), userId) ? RESERVE_NOT_MET : LOST;
            case NO_BIDS -> LOST;       // unreachable — caller must have bid
            case null    -> LOST;       // defensive
        };
        case CANCELLED -> MyBidStatus.CANCELLED;
        case SUSPENDED -> MyBidStatus.SUSPENDED;
        default        -> LOST;         // defensive
    };
}
```

Unit-test covers every derivation branch, table-driven.

### Ordering

```sql
ORDER BY
  CASE WHEN a.status = 'ACTIVE' THEN a.ends_at END DESC NULLS LAST,
  CASE WHEN a.status != 'ACTIVE' THEN a.ended_at END DESC NULLS LAST
```

Active auctions first (ending soonest at top), then ended auctions (most recent at top).

## 11. Error Handling

### New exception classes (`backend/.../auction/exception/`)

| Exception | HTTP | Error code |
|---|---|---|
| `BidTooLowException(minRequired)` | `400` | `BID_TOO_LOW` |
| `SellerCannotBidException` | `403` | `SELLER_CANNOT_BID` |
| `NotVerifiedException` (existing from Epic 02 — reuse) | `403` | `NOT_VERIFIED` |
| `AuctionNotFoundException` (existing — reuse) | `404` | `AUCTION_NOT_FOUND` |
| `InvalidAuctionStateException` (existing — reuse) | `409` | `AUCTION_NOT_ACTIVE` etc. |
| `AuctionAlreadyEndedException` | `409` | `AUCTION_ALREADY_ENDED` |
| `ProxyBidAlreadyExistsException` | `409` | `PROXY_BID_ALREADY_EXISTS` |
| `ProxyBidNotFoundException` | `404` | `PROXY_BID_NOT_FOUND` |
| `InvalidProxyStateException(reason)` | `409` | `INVALID_PROXY_STATE` |
| `InvalidProxyMaxException(reason)` | `400` | `INVALID_PROXY_MAX` |
| `CannotCancelWinningProxyException` | `409` | `CANNOT_CANCEL_WINNING_PROXY` |

All exceptions handled in the existing `@ControllerAdvice` from Epic 02/03, which maps them to the `ApiError` payload shape.

### Validation

`PlaceBidRequest.amount` — `@NotNull`, `@Min(1)`. `SetProxyBidRequest.maxAmount` — same. Rejected by `@Valid` in controller before reaching the service.

## 12. File Structure

```
backend/src/main/java/com/slparcelauctions/backend/auction/
├── Auction.java                            (add columns + @Index for status/ends_at)
├── AuctionEndOutcome.java                  (new enum)
├── AuctionRepository.java                  (add findByIdForUpdate, findActiveIdsDueForEnd)
├── BidType.java                            (new enum)
├── Bid.java                                (new entity)
├── BidRepository.java                      (new)
├── BidService.java                         (new; owns placeBid algorithm)
├── BidController.java                      (new; POST and GET /bids)
├── BidIncrementTable.java                  (new static helper)
├── ProxyBid.java                           (new entity)
├── ProxyBidStatus.java                     (new enum)
├── ProxyBidRepository.java                 (new)
├── ProxyBidService.java                    (new; createProxy, updateProxyMax, cancelProxy)
├── ProxyBidController.java                 (new; POST/PUT/DELETE/GET /proxy-bid)
├── ProxyBidPartialUniqueIndexInitializer.java  (new startup bean; partial unique index)
├── auctionend/
│   ├── AuctionEndScheduler.java            (new)
│   └── AuctionEndTask.java                 (new)
├── broadcast/
│   ├── AuctionBroadcastPublisher.java      (interface)
│   ├── StompAuctionBroadcastPublisher.java (production impl)
│   ├── BidSettlementEnvelope.java          (record)
│   └── AuctionEndedEnvelope.java           (record)
├── dto/
│   ├── PlaceBidRequest.java
│   ├── SetProxyBidRequest.java
│   ├── BidResponse.java
│   ├── BidHistoryEntry.java
│   └── ProxyBidResponse.java
├── exception/
│   ├── BidTooLowException.java
│   ├── SellerCannotBidException.java
│   ├── AuctionAlreadyEndedException.java
│   ├── ProxyBidAlreadyExistsException.java
│   ├── ProxyBidNotFoundException.java
│   ├── InvalidProxyStateException.java
│   ├── InvalidProxyMaxException.java
│   └── CannotCancelWinningProxyException.java
├── mybids/
│   ├── MyBidStatus.java                    (new enum)
│   ├── MyBidSummary.java                   (record)
│   ├── AuctionSummaryForMyBids.java        (record)
│   ├── MyBidStatusDeriver.java             (static helper)
│   ├── MyBidsService.java                  (new)
│   └── MyBidsController.java               (new; GET /users/me/bids)
├── dev/
│   └── DevAuctionEndController.java        (@Profile("dev"))
├── CancellationService.java                (retrofit: signature cancel(Long id, String reason))
├── AuctionController.java                  (cancel route passes id not entity)
└── monitoring/
    ├── OwnershipCheckTask.java             (retrofit: findByIdForUpdate)
    └── SuspensionService.java              (verified; no direct change)
```

## 13. Testing

### Unit tests (`@ExtendWith(MockitoExtension.class)`, fixed `Clock`)

- `BidServiceTest` — every validation path, three bid-emission shapes (no proxy, proxy-exhausted, proxy-counter).
- `ProxyBidServiceTest` — happy paths, below-minimum reject, existing-active 409, step 2 resolution (3 tie branches), EXHAUSTED resurrection, CANCELLED rejection, cancel-while-winning guard.
- `AuctionEndTaskTest` — 4 outcome paths, snipe-extended skip, already-ended skip, winner display name resolution.
- `BidIncrementTableTest` — parametric tier boundaries.
- `MyBidStatusDeriverTest` — table-driven, all derivation branches.
- `AuctionBroadcastPublisherTest` — STOMP impl serializes envelopes correctly; publisher fake for capture in other tests.

### Integration tests (`@SpringBootTest` with TestContainers Postgres)

- End-to-end bid flow: create active auction → place bid → assert DB state + captured envelope.
- Proxy vs manual — two bidders, full flow, verify 1-or-2 bid rows, correct `currentBidderId`, envelope `newBids[]`.
- Snipe extension — bid at T-1min on 15-min-window auction, verify `endsAt` extended, `snipeExtensionMinutes` stamped, envelope carries new end time.
- Buy-it-now — bid equals `buyNowPrice`, verify `status=ENDED`, `endOutcome=BOUGHT_NOW`, winner set, `AuctionEndedEnvelope` captured.
- Scheduler close — `endsAt = now - 1s`, call `sweep()`, verify status + envelope.
- Scheduler skip — snipe-extended auction where `endsAt > now` when `closeOne` acquires the lock — verify no state change.
- My Bids — user places bids across all 7 status buckets, verify `GET /me/bids?status=all` derives correctly.

### Concurrency regression tests (`@SpringBootTest` with manual `TransactionTemplate` + `CountDownLatch`, **not** `@Transactional` on test method)

- **Bid-bid race** — two threads racing `placeBid`; exactly one commits, loser sees `BidTooLowException`.
- **Bid-cancel race** — concurrent `placeBid` + `CancellationService.cancel`; exactly one wins.
- **Bid-scheduler race** — scheduler marks auction due; bid transaction opens after scheduler query but before `closeOne` lock acquire; `closeOne` re-check sees new `endsAt` and skips.
- **Bid-suspend race** — concurrent `placeBid` + `OwnershipCheckTask.checkOne` that flips to SUSPENDED; deterministic outcome.
- **Proxy-update mid-counter race** — existing proxy auto-bids from counter; simultaneous `PUT` to increase same proxy's max arrives; one commits first.

### Success-criteria regression tests (explicitly pin Q3 and Q4)

- **Tie flip (Q3):** manual bid at exactly `P_max` → `400 BID_TOO_LOW`; proxy retains position.
- **EXHAUSTED resurrection (Q4):** `PUT` increase on `EXHAUSTED` row → flips to `ACTIVE`, re-runs resolution, emits correct bid rows.

### Coverage target

Match Epic 03 sub-2 baseline: ~100% on `BidService`, `ProxyBidService`, `AuctionEndTask`, `MyBidStatusDeriver`, `BidIncrementTable`.

### Manual-test plan

Checklist in the PR description with curl commands for every endpoint, `wscat` subscription for WS verification, dev-endpoint sweep trigger, scripted scenario for proxy resolution.

## 14. Config

```yaml
# application.yml additions
slpa:
  auction-end:
    enabled: true
    scheduler-frequency: PT15S

server:
  forward-headers-strategy: framework   # may already be present; confirm during Task 2
```

Test profile (`application-test.yml`) sets `slpa.auction-end.enabled=false`. Dev profile keeps defaults.

No new environment variables, no new secrets.

## 15. Deferred Work (to add during Task 9 doc sweep)

### DESIGN.md §554 stale wording

- **From:** Epic 04 sub-1 (identity visibility decision)
- **Why:** Line reads "Bid history (anonymized or public - configurable)" but §1589-1591 ("What we DON'T do") resolves identity as public — anonymization would undermine the reputation system. Line 554 is a verbatim copy from `INITIAL_DESIGN.md:214` (pre-design intake) that was never reconciled.
- **When:** Any future documentation sweep — not urgent.
- **Notes:** One-sentence cleanup. Leave §1589-1591 as the authoritative source.

### Outbid / won / reserve-not-met / auction-ended notifications

- **From:** Epic 04 sub-1 (end-of-auction and bid-placement event sources are now in place)
- **Why:** Every terminal event (being outbid, winning, auction ending with reserve unmet) has a persisted `Bid` / `Auction` row that a notification service can drain. No notifications shipped this sub-spec — email/SL IM channels don't exist yet.
- **When:** Epic 09 (Notifications)
- **Notes:** Epic 09 wires a notification publisher onto `TransactionSynchronization.afterCommit` in `BidService` and `AuctionEndTask`. The `AuctionEndedEnvelope` already carries `winnerUserId` and `bidCount` for templating.

### User-targeted WS queues (`/user/{id}/queue/*`)

- **From:** Epic 04 sub-1 (WS infra decision — public topic only)
- **Why:** The detail page's "you were outbid" toast will derive from the public `BID_SETTLEMENT` envelope (client knows its own userId). User-targeted queues are more natural for cross-page notifications (appearing even on dashboard) — scoped to Epic 09.
- **When:** Epic 09 (Notifications)

### Escrow handoff on `ENDED+SOLD`

- **From:** Epic 04 sub-1 (scheduler flips status to `ENDED+SOLD`; nothing picks up from there yet)
- **Why:** Phase 1 scope ends at auction close. Escrow flow (payment collection, ownership transfer, dispute window) is Epic 05.
- **When:** Epic 05 (Escrow Manager)
- **Notes:** Epic 05 listens for `status=ENDED, endOutcome=SOLD|BOUGHT_NOW` transitions — either via a DB-polling job or by consuming a `AuctionEndedEnvelope` subscription. Keep the envelope shape stable.

### Cancellation WS broadcast on active-auction cancel

- **From:** Epic 04 sub-1 (deliberate omission — current cancel doesn't broadcast)
- **Why:** Cancel-on-`ACTIVE` is rare and its effects are visible via `GET /auctions/{id}` and My Listings on next page load. Adding a WS envelope means defining a new envelope type, deciding ordering relative to bid settlements, and updating the sub-spec 2 UI. Not worth the complexity until sub-spec 2 proves it's needed.
- **When:** Re-evaluate in Epic 04 sub-spec 2 with the detail page in hand. If the UI needs it, add a `CancelledEnvelope` type then.

## 16. Task Breakdown

Nine bite-sized tasks, each producing a self-contained commit:

1. **Task 1: Schema** — `Bid` + `ProxyBid` entities, new `Auction` columns, all enums, repositories, `AuctionRepository.findByIdForUpdate`, `ProxyBidPartialUniqueIndexInitializer`, entity/repo tests.
2. **Task 2: Bid service (no proxy, no snipe, no buy-now)** — `BidIncrementTable`, `BidService.placeBid` for the trivial case (no competing proxy, no snipe, no buy-now), `BidController` POST/GET, exception mapping, unit + integration tests including bid-bid race.
3. **Task 3: Snipe + buy-it-now** — `applySnipeAndBuyNow` helper, per-row snipe evaluation, inline buy-now, tests.
4. **Task 4: Proxy bidding** — `ProxyBidService` (create, update, cancel, `resolveProxyResolution`), `ProxyBidController`, resurrection + tie + counter tests, proxy-update race test.
5. **Task 5: WebSocket broadcast** — `AuctionBroadcastPublisher` interface + STOMP impl, `BidSettlementEnvelope` + `AuctionEndedEnvelope` records, `afterCommit` wiring in `BidService` + `ProxyBidService`, capturing-fake publisher for existing tests.
6. **Task 6: Auction-end scheduler** — `AuctionEndScheduler` + `AuctionEndTask`, outcome classification, proxy cleanup on close, winner display-name resolution, dev endpoints, scheduler unit + integration tests including bid-scheduler race.
7. **Task 7: Concurrency retrofit** — `CancellationService.cancel(Long id, String reason)` refactor, `OwnershipCheckTask.checkOne` lock upgrade, `SuspensionService` verified, bid-cancel + bid-suspend concurrency regression tests.
8. **Task 8: My Bids endpoint** — `MyBidsService` + `MyBidsController`, `MyBidStatusDeriver`, JPQL query, integration tests across all 7 status buckets.
9. **Task 9: Documentation sweep** — README.md update (new endpoints, new WS envelope shapes), FOOTGUNS additions (pessimistic-lock gotchas, snipe loop per-row evaluation, `PROXY_AUTO` null IP, display-name resolution in scheduler path, afterCommit publish hygiene), DEFERRED_WORK entries from §15 above.

## 17. Open Questions

None at spec time. All Q&A from brainstorm resolved:

- **Q1** Subspec decomposition → backend-first (A)
- **Q2** Concurrency model → pessimistic row lock (A)
- **Q3** Proxy algorithm → tie flips to proxy (`X > P_max` for exhaustion); resurrection via PUT increase on EXHAUSTED
- **Q4** Scheduler cadence + notifications → 15s tick, no notification placeholders (Epic 09 derives from auction rows)
- **Q5** Bid schema + snipe storage → `bid_type` enum persisted, no `user_agent` column, snipe event stored on the bid row
- **Q6** WS envelope → two shapes (`BID_SETTLEMENT`, `AUCTION_ENDED`), one broadcast per transaction, `afterCommit` publish
- **Q7** My Bids shape → computed-not-materialized, keep `CANCELLED/SUSPENDED` entries, `RESERVE_NOT_MET` as distinct bucket
- **Bidder identity** → public per DESIGN.md §1589-1591; flag §554 stale wording in DEFERRED_WORK
- **`PROXY_AUTO` IP** → always null (system-generated, not a human action)
- **Scheduler concurrency** → sequential closes, not `@Async` (workload doesn't justify bounded pool)
- **ACTIVE-not-winning branch in updateProxyMax** → defensive-only, logs warn, unreachable under lock
