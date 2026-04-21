# Epic 04 Sub-Spec 1 — Auction Engine Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Source spec:** [`docs/superpowers/specs/2026-04-20-epic-04-sub-1-auction-engine-backend.md`](../specs/2026-04-20-epic-04-sub-1-auction-engine-backend.md) — every task below refers back to the spec by section number. Subagents should receive the relevant spec section inline with their task prompt; they should NOT be expected to read the spec cover-to-cover.

**Goal:** Build the SLPA auction engine backend — pessimistic-locked bid placement, eBay-style proxy bidding, snipe protection, reliable auction-end scheduling, buy-it-now, WebSocket settlement broadcast, and the My Bids dashboard endpoint.

**Architecture:** `BidService` serializes per-auction writes via `PESSIMISTIC_WRITE` on the `Auction` row. Proxy resolution emits 1-2 bid rows atomically per transaction. A `@Scheduled` sweeper processes due auctions sequentially, each via `AuctionEndTask.closeOne(Long)`. WebSocket publishing happens via `TransactionSynchronizationManager.registerSynchronization(afterCommit)` so readers never observe mid-transaction state. Existing `CancellationService` / `OwnershipCheckTask` / `SuspensionService` retrofit onto the same lock.

**Tech Stack:** Spring Boot 4, Java 26, Spring Data JPA + Hibernate, PostgreSQL, Spring WebSocket/STOMP, Lombok, `ddl-auto: update` (no Flyway migrations), `@Scheduled`.

---

## Preflight (before starting Task 1)

- [ ] **P.1: Confirm branch state**

```bash
git rev-parse --abbrev-ref HEAD      # expect: task/04-sub-1-auction-engine-backend
git status                           # expect: working tree clean (spec already committed)
git log --oneline -3
```

- [ ] **P.2: Run existing test suite to establish green baseline**

```bash
cd backend && ./mvnw test
```

Expected: all tests pass (baseline from Epic 03 sub-2 merge). If any existing tests fail, STOP — fix the baseline before introducing Epic 04 changes.

- [ ] **P.3: Verify dev infrastructure is running**

Postgres + Redis + MinIO per the dev-containers memory. `docker ps` should show the three slpa containers.

- [ ] **P.4: Read the spec**

The implementer subagents will receive inline extracts. The controller (you) should skim §5 (Concurrency Model), §6 (Bid Placement), §7 (Proxy Bidding), §8 (Auction-End Scheduler) once so you can answer subagent questions quickly.

---

## Task 1: Schema foundations

**Spec reference:** §5 (Concurrency Model), §9 (Entities, Repositories, DTOs).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/Bid.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/BidType.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/BidRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBid.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidStatus.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionEndOutcome.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidPartialUniqueIndexInitializer.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidPersistenceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/ProxyBidPersistenceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/ProxyBidPartialUniqueIndexInitializerTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java` — add `currentBidderId`, `winnerUserId`, `finalBidAmount`, `endOutcome`, `endedAt` columns; add `@Index(name="ix_auctions_status_ends_at", columnList="status, ends_at")` to `@Table`.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java` — add `findByIdForUpdate(Long)` with `@Lock(LockModeType.PESSIMISTIC_WRITE)` and `findActiveIdsDueForEnd(OffsetDateTime)`.

**Steps:**

- [ ] **Step 1.1: Create the enum files**

`BidType.java`, `ProxyBidStatus.java`, `AuctionEndOutcome.java` per spec §9. Each is a simple enum with the documented values.

- [ ] **Step 1.2: Write failing Bid persistence test**

Create `BidPersistenceTest.java` that persists a `Bid` row and reads it back. Assert every column is round-tripped, including nullable fields (`proxyBidId`, `snipeExtensionMinutes`, `newEndsAt`, `ipAddress`).

Run: `./mvnw test -Dtest=BidPersistenceTest` — expect FAIL ("cannot find symbol Bid").

- [ ] **Step 1.3: Create Bid.java per spec §9**

All fields as specified. Include all three indexes in `@Table(indexes={...})`. Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. `@CreationTimestamp` on `createdAt`.

Run: `./mvnw test -Dtest=BidPersistenceTest` — expect PASS.

- [ ] **Step 1.4: Create BidRepository.java**

Methods per spec §9: `findByAuctionIdOrderByCreatedAtDesc(Long, Pageable)`, `findByAuctionIdOrderByCreatedAtAsc(Long)`. The My Bids query method lands in Task 8 — do not add here.

- [ ] **Step 1.5: Write failing ProxyBid persistence + uniqueness test**

`ProxyBidPersistenceTest.java` — persist/read round-trip. Also include a `@Test void partialUniqueIndexAllowsMultipleNonActive()` that persists one ACTIVE + one EXHAUSTED + one CANCELLED for the same user/auction successfully, and asserts that inserting a second ACTIVE row for the same (user, auction) throws `DataIntegrityViolationException` (the partial unique index).

Run: expect FAIL (cannot find symbol).

- [ ] **Step 1.6: Create ProxyBid.java + ProxyBidRepository.java + ProxyBidPartialUniqueIndexInitializer.java per spec §9**

Repository methods: `findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc`, `existsByAuctionIdAndBidderIdAndStatus`, `findFirstByAuctionIdAndStatusAndBidderIdNot`, and the `@Modifying` `exhaustAllActiveByAuctionId`.

Initializer — `@EventListener(ApplicationReadyEvent.class)` that runs `CREATE UNIQUE INDEX IF NOT EXISTS proxy_bids_one_active_per_user ON proxy_bids (auction_id, user_id) WHERE status = 'ACTIVE'`.

Run: expect PASS for the persistence test.

- [ ] **Step 1.7: Write failing initializer test**

`ProxyBidPartialUniqueIndexInitializerTest.java` — `@SpringBootTest` that asserts after app context loads, the index exists by querying `pg_indexes` for `proxy_bids_one_active_per_user`.

Run: expect PASS (initializer has already fired by the time the test runs).

- [ ] **Step 1.8: Modify Auction.java**

Add five columns per spec §9 (all nullable except `bidCount` which already exists). Add `@Index(name="ix_auctions_status_ends_at", columnList="status, ends_at")` to the existing `@Table(indexes=...)` annotation.

- [ ] **Step 1.9: Modify AuctionRepository.java**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Auction a WHERE a.id = :id")
Optional<Auction> findByIdForUpdate(@Param("id") Long id);

@Query("SELECT a.id FROM Auction a WHERE a.status = 'ACTIVE' AND a.endsAt <= :now")
List<Long> findActiveIdsDueForEnd(@Param("now") OffsetDateTime now);
```

- [ ] **Step 1.10: Write failing lock test**

`AuctionRepositoryLockTest.java` — integration test that asserts `findByIdForUpdate` actually acquires `PESSIMISTIC_WRITE` by using two threads: thread 1 opens a transaction, calls `findByIdForUpdate(id)`, waits on a latch; thread 2 opens a transaction, calls `findByIdForUpdate(id)` with a 500ms lock timeout — expects thread 2 to time out until thread 1 releases.

Alternative if lock-timeout timing is flaky in CI: query `pg_locks` and assert a `RowExclusiveLock` exists on the auctions table when thread 1 is in the middle of its transaction.

Run: expect PASS (lock annotation already in place).

- [ ] **Step 1.11: Run full test suite**

```bash
cd backend && ./mvnw test
```

All existing + new tests green.

- [ ] **Step 1.12: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/Bid.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/BidType.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/BidRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBid.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidStatus.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionEndOutcome.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidPartialUniqueIndexInitializer.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/

git commit -m "feat(auction): schema foundations for bid/proxy/end-outcome + pessimistic lock query"
git push
```

---

## Task 2: Bid placement (core path, no proxy, no snipe, no buy-now)

**Spec reference:** §4 (routes), §5 (concurrency), §6 (placement algorithm), §11 (exceptions).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/BidIncrementTable.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/BidController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PlaceBidRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/BidResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/BidHistoryEntry.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/BidTooLowException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/SellerCannotBidException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionAlreadyEndedException.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidIncrementTableTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidServiceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidControllerTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidPlacementIntegrationTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidBidRaceTest.java` (the concurrent-bid test — covers Success Criterion §1)
- Modify: existing `@ControllerAdvice` / `GlobalExceptionHandler` in `backend/.../web/` — add handlers for the new exception types.

**Steps:**

- [ ] **Step 2.1: Write failing BidIncrementTable test**

Table-driven parametric test covering every tier boundary per spec §6: inputs `0, 49, 50, 999, 1000, 9999, 10000, 99999, 100000, 500000` — assert exact `minIncrement` values `50, 50, 50, 100, 100, 500, 500, 1000, 1000, 1000`.

Run: FAIL.

- [ ] **Step 2.2: Implement BidIncrementTable.java**

```java
public final class BidIncrementTable {
    private BidIncrementTable() {}
    public static long minIncrement(long currentBid) {
        if (currentBid < 1_000L)   return 50L;
        if (currentBid < 10_000L)  return 100L;
        if (currentBid < 100_000L) return 500L;
        return 1_000L;
    }
}
```

Run: PASS.

- [ ] **Step 2.3: Create exception classes**

Per spec §11: `BidTooLowException(long minRequired)`, `SellerCannotBidException()`, `AuctionAlreadyEndedException(OffsetDateTime endsAt)`. All extend `RuntimeException`. Getters for the structured fields.

- [ ] **Step 2.4: Create request/response DTOs**

`PlaceBidRequest`, `BidResponse`, `BidHistoryEntry` as records per spec §9.

- [ ] **Step 2.5: Write failing BidService test cases (one per validation path)**

`BidServiceTest.java` with Mockito + fixed `Clock`. Cases:

1. `throws AuctionNotFoundException when auction missing`
2. `throws InvalidAuctionStateException when status != ACTIVE`
3. `throws AuctionAlreadyEndedException when endsAt <= now`
4. `throws NotVerifiedException when bidder unverified`
5. `throws SellerCannotBidException when bidder is seller`
6. `throws BidTooLowException below startingBid on first bid`
7. `throws BidTooLowException below currentBid + increment`
8. `places bid successfully, updates auction.currentBid/currentBidderId/bidCount, increments bidCount by 1` (happy path, no proxy, no snipe, no buy-now)
9. `persists ipAddress and bidType=MANUAL on the bid row`
10. `publishes BidSettlementEnvelope only after commit` (use a fake publisher that records whether it was invoked inside or outside the transaction — easy via a `@TransactionalEventListener` mock or by checking `TransactionSynchronizationManager.isActualTransactionActive()` at publish time)

Run: all FAIL.

- [ ] **Step 2.6: Implement BidService.placeBid — core path only**

Skip proxy/snipe/buy-now — Task 3 adds snipe+buy-now, Task 4 adds proxy. For Task 2, implement steps 1-5, step 6 (just emit one MANUAL bid), step 10-12 of spec §6 algorithm.

Structure:
```java
@Service @RequiredArgsConstructor @Slf4j
public class BidService {
    private final AuctionRepository auctionRepo;
    private final BidRepository bidRepo;
    private final UserRepository userRepo;
    private final Clock clock;
    private final AuctionBroadcastPublisher publisher;  // will be injected in Task 5; for Task 2, use a no-op or stub

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BidResponse placeBid(Long auctionId, Long bidderId, long amount, String ipAddress) {
        // steps 1-5 per spec §6
        // step 6 (trivial: no proxy): emit single MANUAL bid
        // step 10: update auction state
        // step 11: afterCommit publish (stub in Task 2)
        // step 12: return BidResponse
    }
}
```

For Task 2, the `publisher` field can be a `@Nullable` placeholder (add `@Autowired(required=false)` or inject a no-op default `AuctionBroadcastPublisher` bean in a test configuration). Task 5 replaces it with the real STOMP implementation.

Run: all 10 BidServiceTest cases PASS.

- [ ] **Step 2.7: Create BidController + register exception handlers**

`BidController`:
- `POST /api/v1/auctions/{id}/bids` — `@PreAuthorize("isAuthenticated()")`, extracts `X-Forwarded-For` leftmost from `HttpServletRequest`, delegates to `BidService.placeBid`.
- `GET /api/v1/auctions/{id}/bids` — public, paged, delegates to `bidRepo.findByAuctionIdOrderByCreatedAtDesc`, maps to `BidHistoryEntry`.

Global exception handler mappings per spec §11 table.

- [ ] **Step 2.8: Write failing integration test**

`BidPlacementIntegrationTest.java` — `@SpringBootTest` with TestContainers Postgres. Seeds a verified user and an ACTIVE auction via existing fixtures. Places a bid via `MockMvc.perform(post("/api/v1/auctions/{id}/bids"))`. Asserts 201, bid row persisted, auction updated.

- [ ] **Step 2.9: Write failing bid-bid race regression test**

`BidBidRaceTest.java` — two threads racing `placeBid` on the same auction via `TransactionTemplate` + `CountDownLatch`. Assert exactly one commits (one `BidTooLowException`, one success).

**Critical:** the test class is NOT `@Transactional` — the test framework transaction would serialize the threads and mask the race. Use explicit `TransactionTemplate` for each thread.

- [ ] **Step 2.10: Full test run**

```bash
cd backend && ./mvnw test
```

All green.

- [ ] **Step 2.11: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/
git commit -m "feat(auction): bid placement service with pessimistic-lock concurrency + validation"
git push
```

---

## Task 3: Snipe protection + buy-it-now

**Spec reference:** §6 (applySnipeAndBuyNow helper, per-row evaluation).

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java` — add `applySnipeAndBuyNow` helper, wire into `placeBid`.
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidServiceSnipeTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidServiceBuyNowTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/SnipeAndBuyNowIntegrationTest.java`

**Steps:**

- [ ] **Step 3.1: Write failing snipe tests**

`BidServiceSnipeTest.java`:
1. `does not extend when snipeProtect=false` — auction with `snipeProtect=false`, bid inside what would be the window, assert `endsAt` unchanged.
2. `does not extend when bid is outside window` — 15-min window, bid placed at T-20min, assert `endsAt` unchanged.
3. `extends when bid lands in window` — 15-min window, bid at T-1min, assert `endsAt = bid.createdAt + 15min`, `bid.snipeExtensionMinutes=15`, `bid.newEndsAt` set.
4. `stacks extensions` — three bids in a row within the window (bid1 at T-2min extends to T+13min; bid2 at (T+13)-2min extends again; bid3 at (T+26)-2min extends again). Assert each bid row has its own `snipeExtensionMinutes` stamped and the final `endsAt` is correct.
5. `extends from bid timestamp, not original endsAt` — regression against computing `endsAt = endsAt + window` instead of `bid.createdAt + window`.

Run: FAIL (method doesn't exist).

- [ ] **Step 3.2: Implement applySnipeAndBuyNow snipe branch per spec §6**

```java
private void applySnipeAndBuyNow(Auction auction, List<Bid> emitted) {
    if (Boolean.TRUE.equals(auction.getSnipeProtect())) {
        Duration window = Duration.ofMinutes(auction.getSnipeWindowMin());
        for (Bid bid : emitted) {
            Duration untilEnd = Duration.between(bid.getCreatedAt(), auction.getEndsAt());
            if (!untilEnd.isNegative() && untilEnd.compareTo(window) <= 0) {
                OffsetDateTime newEnd = bid.getCreatedAt().plus(window);
                bid.setSnipeExtensionMinutes(auction.getSnipeWindowMin());
                bid.setNewEndsAt(newEnd);
                auction.setEndsAt(newEnd);
            }
        }
    }
    // buy-now branch in step 3.4
}
```

Run: 5 snipe tests PASS.

- [ ] **Step 3.3: Write failing buy-now tests**

`BidServiceBuyNowTest.java`:
1. `does not end when no buyNowPrice` — null `buyNowPrice`, bid well above "normal", assert status still ACTIVE.
2. `does not end when bid below buyNowPrice` — `buyNowPrice=10_000`, bid 5_000, assert ACTIVE.
3. `ends with BOUGHT_NOW outcome when bid equals buyNowPrice` — exact match; assert `status=ENDED`, `endOutcome=BOUGHT_NOW`, `winnerUserId=bidder.id`, `finalBidAmount=buyNowPrice`, `endedAt` set.
4. `ends with BOUGHT_NOW when bid exceeds buyNowPrice` — bid > buyNowPrice; same assertions with `finalBidAmount=bid.amount`.
5. `publishes AuctionEndedEnvelope not BidSettlementEnvelope on buy-now` — capture publisher, assert `publishEnded` called, `publishSettlement` not called.

Run: FAIL.

- [ ] **Step 3.4: Implement buy-now branch + hook publisher dispatch branch**

```java
// inside applySnipeAndBuyNow, after snipe loop:
if (auction.getBuyNowPrice() != null) {
    for (Bid bid : emitted) {
        if (bid.getAmount() >= auction.getBuyNowPrice()) {
            auction.setStatus(AuctionStatus.ENDED);
            auction.setEndOutcome(AuctionEndOutcome.BOUGHT_NOW);
            auction.setWinnerUserId(emitted.get(emitted.size() - 1).getBidder().getId());
            auction.setFinalBidAmount(emitted.get(emitted.size() - 1).getAmount());
            auction.setEndedAt(OffsetDateTime.now(clock));
            proxyBidRepo.exhaustAllActiveByAuctionId(auction.getId());
            return;
        }
    }
}
```

Update `placeBid` step 11 to branch publish based on `auction.status`:

```java
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronizationAdapter() {
        @Override public void afterCommit() {
            if (auction.getStatus() == AuctionStatus.ENDED) {
                publisher.publishEnded(AuctionEndedEnvelope.of(auction, emitted, clock, userRepo));
            } else {
                publisher.publishSettlement(BidSettlementEnvelope.of(auction, emitted, clock));
            }
        }
    });
```

`AuctionEndedEnvelope.of` / `BidSettlementEnvelope.of` land in Task 5 — for Task 3, define these as stub static factories or inline construction. The `publisher` field is still the no-op from Task 2.

Run: 5 buy-now tests PASS.

- [ ] **Step 3.5: Write failing integration test for snipe + buy-now**

`SnipeAndBuyNowIntegrationTest.java` — full-stack test with real DB:
1. Create auction with `snipeProtect=true, snipeWindowMin=10, buyNowPrice=1000, endsAt=now+5min`. Place bid at L$500 (no extension because 5min > 10min is wrong — actually 5min ≤ 10min so this DOES extend). Adjust: `endsAt=now+15min`. Place bid → no extension. Place bid at `endsAt - 5min` → extends. Place bid ≥ 1000 → buy-now.
2. Clean state assertions after each step.

- [ ] **Step 3.6: Full test run + commit**

```bash
cd backend && ./mvnw test
git add backend/
git commit -m "feat(auction): snipe-protection per-row extension and inline buy-it-now"
git push
```

---

## Task 4: Proxy bidding

**Spec reference:** §7 (full algorithm), §4 (routes), §11 (exceptions).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SetProxyBidRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/ProxyBidResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/ProxyBidAlreadyExistsException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/ProxyBidNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/InvalidProxyStateException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/InvalidProxyMaxException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/CannotCancelWinningProxyException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java` — wire competing-proxy detection + counter-bid emission into `placeBid` step 7-8 (previously a trivial "no proxy" path).
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/ProxyBidServiceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/ProxyBidResolutionTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/ProxyBidControllerTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/ProxyBidResurrectionTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/ProxyBidTieFlipTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidVsProxyCounterIntegrationTest.java`

**Steps:**

- [ ] **Step 4.1: Create exception classes + DTOs**

Per spec §11 and §9. Each exception has the appropriate constructor arguments (e.g., `InvalidProxyStateException(String reason)`, `InvalidProxyMaxException(String reason)`).

- [ ] **Step 4.2: Write failing ProxyBidService createProxy tests**

Cover: happy-path no-competitor, existing-ACTIVE rejection (409), max below starting bid (400), invalid state (auction not ACTIVE → 409), seller rejection (403), unverified (403). All use Mockito with a fake `Clock` and a fake `AuctionBroadcastPublisher`.

- [ ] **Step 4.3: Implement ProxyBidService.createProxy + resolveProxyResolution per spec §7**

`resolveProxyResolution` is the private helper. Step-for-step from spec:
- `existing = proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(auctionId, ACTIVE, proxy.bidder.id)`
- Branch on `existing is null` → emit opening bid
- Branch on `proxy.maxAmount > existing.maxAmount` → proxy wins
- Branch on `proxy.maxAmount < existing.maxAmount` → existing wins, proxy exhausted
- Equal max → `createdAt` tiebreak

Run: tests pass.

- [ ] **Step 4.4: Write failing ProxyBidResolutionTest — all 3 tie branches**

Four scenarios (covers Q3 tie flip):
1. `newProxyMax > existingMax — newProxy wins, existing exhausted, settleAmount = existing.max + increment (capped at newProxy.max)`
2. `newProxyMax < existingMax — existing wins, newProxy exhausted, two bid rows emitted (flush + counter)`
3. `equal max, existing earlier — existing wins ties, two bid rows emitted`
4. `equal max, proxy earlier (resurrection case) — proxy wins ties, one bid row emitted`

Run: tests pass if implementation correct; tune if any case fails.

- [ ] **Step 4.5: Write failing ProxyBidResurrectionTest**

Scenario: seller creates auction with `startingBid=500, snipeProtect=false`.
- User A creates proxy max=600. Runs → A winning at L$500 (opening bid).
- User B creates proxy max=1000. Runs → B winning at L$650 (A.max + increment capped at B.max). A.status=EXHAUSTED.
- User A PUTs max=2000. Expected: A.status flips to ACTIVE, resolveProxyResolution runs, A wins (newMax=2000 > existing.max=1000), settleAmount = 1000 + 1000 increment capped at 2000 = 1500 at L$1500. Wait — that's not right. min(existing.max + increment, newProxy.max) = min(1000+1000, 2000) = 2000. So A's counter-bid at L$2000.

Actually no — the increment lookup uses the current bid, not existing.max. Let me re-check the spec. `minIncrement(existing.maxAmount)` — it's based on `existing.max`. So `minIncrement(1000) = 100` (1000-9999 range), so `settleAmount = min(1000+100, 2000) = 1100`. A's counter-bid at L$1100. B.status=EXHAUSTED.

Regardless of the exact numbers, the test asserts:
- A.status == ACTIVE
- A.maxAmount == 2000
- B.status == EXHAUSTED
- auction.currentBid == 1100 (or whatever min() evaluates to)
- auction.currentBidderId == A.id
- exactly one new bid row emitted for A (PROXY_AUTO, amount=1100, proxyBidId=A.id)

- [ ] **Step 4.6: Implement updateProxyMax + cancelProxy per spec §7**

`updateProxyMax` — full implementation including ACTIVE winning branch (no emitted, no publish), ACTIVE not-winning defensive branch (logs warn, runs resolution), CANCELLED rejection (409), EXHAUSTED resurrection (runs resolution).

`cancelProxy` — simple: find ACTIVE proxy, check not-winning, set CANCELLED.

Run: resurrection test PASS.

- [ ] **Step 4.7: Write failing ProxyBidTieFlipTest (Q3 regression)**

Two scenarios:
1. `existing proxy max=500, manual bid at amount=500 → bid rejected with BidTooLowException` (proxy retains). Wait — the manual bid is 500, currentBid is whatever existing proxy placed (opening bid at `startingBid`). If startingBid=100 and existing proxy placed opening at 100, then currentBid=100, minRequired=150. Manual bid at 500 passes minRequired. Enters step 8: `if amount > P_max (500 > 500?) — NO, so proxy counters`. So this test actually confirms proxy COUNTERS, not that manual rejected.

Wait — re-reading the success criterion #7 in spec: "Manual bid at exactly P_max → 400 BID_TOO_LOW; proxy retains position." But the algorithm in §6 step 8 says: if `amount > P_max` (strict), proxy exhausted else proxy counters. At `amount == P_max`, the "else" branch runs and proxy counters at `min(amount + increment, P_max) = P_max`. So the bid IS accepted, but the proxy emits a counter-bid at P_max, leaving the proxy winning at P_max. The manual bidder's bid is recorded, but they're immediately outbid.

So "proxy retains position" is accurate. But "400 BID_TOO_LOW" from the criterion is NOT the algorithm — the manual bid at P_max is accepted (201), it just doesn't win. Let me re-check the spec and success criterion.

Re-reading success criterion #7: "Manual bid at exactly P_max → 400 BID_TOO_LOW; the proxy retains the position."

Hmm — this conflicts with the algorithm. If we want 400 at amount==P_max, we'd need to check `amount + minIncrement(amount) > P_max` in step 5 (bid too low). Actually that's already implicit: if amount==P_max, then after the proxy counters at P_max, the next valid manual bid would need to be P_max + increment. But during this bid, the manual bid IS valid against currentBid pre-counter.

**This is a spec inconsistency I need to resolve.** Let me think about the right answer:

Option A: Manual bid at P_max IS accepted, proxy immediately counter-bids at P_max (same amount). Both rows recorded, proxy winning at P_max by tiebreak (earlier createdAt on the proxy bid).

Option B: Manual bid at P_max is rejected as too low because the proxy would immediately counter at the same amount — net effect is manual bidder hasn't improved the price.

Option A matches the algorithm as written. Option B matches the success criterion wording but requires different logic.

The user's Q3 approval said: "Step 1's X ≥ P_max should be X > P_max (proxy exhausted only when strictly outbid). At equality, the proxy bidder committed first and should hold the position." That describes Option A — proxy holds the position at equality. The bid is accepted, proxy counters to retain.

So the success criterion in the spec is slightly imprecise. Let me fix it in the spec — the test should assert that:
- Manual bid at amount == P_max IS accepted (201)
- Two bid rows are emitted: manual at P_max, proxy counter at P_max
- auction.currentBidderId == proxy owner (proxy winning)
- auction.currentBid == P_max

**Spec fix needed.** Let me update §3 success criterion #7.

- [ ] **Step 4.7 (corrected): Write failing ProxyBidTieFlipTest**

Scenarios from spec §7 invariants (Q3 flip):
1. `manual bid at amount < P_max → proxy counters at amount+increment capped at P_max` (standard counter)
2. `manual bid at amount == P_max → proxy counters at P_max (tie goes to proxy)` — bid accepted, both rows emitted, proxy winning
3. `manual bid at amount == P_max + 1 increment → still counters at P_max` (edge: P_max just barely cleared by increment math? no, amount > P_max so proxy exhausted)
4. `manual bid at amount > P_max → proxy exhausted` (standard outbid)

Assertion for #2 is the Q3 flip: `auction.currentBidderId == proxyOwner.id`, both rows present with `amount == P_max`, proxy row's `createdAt` is slightly later so tiebreak by logical sequence (proxy emitted second but wins because it's the proxy).

Wait — but if both rows have amount==P_max, who is currentBidderId? The spec step 10 says `top = emitted[-1]` and `currentBidderId = top.bidder.id`. The last emitted bid is the proxy counter, so `currentBidderId == proxyOwner.id`. Correct.

Run: implement + tests PASS.

- [ ] **Step 4.8: Update BidService.placeBid to detect competing proxy and emit counter**

Replace the Task 2 "trivial: no proxy" step 6 with the full step 7-8 from spec §6. Inject `ProxyBidRepository` into `BidService`. Keep the existing tests from Task 2 passing (they test the no-competing-proxy branch).

- [ ] **Step 4.9: Write ProxyBidController + integration tests**

Controller routes per spec §4:
- `POST /api/v1/auctions/{id}/proxy-bid` → `createProxy`
- `PUT /api/v1/auctions/{id}/proxy-bid` → `updateProxyMax`
- `DELETE /api/v1/auctions/{id}/proxy-bid` → `cancelProxy`
- `GET /api/v1/auctions/{id}/proxy-bid` → returns caller's most recent proxy

Exception handlers added to global advice.

- [ ] **Step 4.10: Write BidVsProxyCounterIntegrationTest**

Full-stack: User A sets proxy max=1000, User B places manual bid at 500 → DB state has two bid rows, auction.currentBidderId==A.id, auction.currentBid==600 (500 + 100 increment). WS publish captured via fake.

- [ ] **Step 4.11: Full test run + commit**

```bash
cd backend && ./mvnw test
git add backend/
git commit -m "feat(auction): proxy bidding with step-2 resolution, resurrection, and tie-flip semantics"
git push
```

---

## Task 5: WebSocket broadcast

**Spec reference:** §4 (WS topic), §6 step 11 (afterCommit), §9 (envelope DTOs).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/broadcast/AuctionBroadcastPublisher.java` (interface)
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/broadcast/StompAuctionBroadcastPublisher.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/broadcast/BidSettlementEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/broadcast/AuctionEndedEnvelope.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/broadcast/StompAuctionBroadcastPublisherTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/broadcast/CapturingAuctionBroadcastPublisher.java` (test-support helper)
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/broadcast/BidWebSocketIntegrationTest.java`
- Modify: `BidService` + `ProxyBidService` — swap their stub publishers for the real injected interface.

**Steps:**

- [ ] **Step 5.1: Define interface + envelope records per spec §9**

```java
public interface AuctionBroadcastPublisher {
    void publishSettlement(BidSettlementEnvelope envelope);
    void publishEnded(AuctionEndedEnvelope envelope);
}

public record BidSettlementEnvelope(
    String type,
    Long auctionId,
    OffsetDateTime serverTime,
    Long currentBid,
    Long currentBidderId,
    String currentBidderDisplayName,
    Long bidCount,
    OffsetDateTime endsAt,
    OffsetDateTime originalEndsAt,
    List<BidHistoryEntry> newBids
) {
    public static BidSettlementEnvelope of(Auction a, List<Bid> emitted, Clock clock) {
        return new BidSettlementEnvelope(
            "BID_SETTLEMENT",
            a.getId(),
            OffsetDateTime.now(clock),
            a.getCurrentBid(),
            a.getCurrentBidderId(),
            emitted.stream()
                .filter(b -> b.getBidder().getId().equals(a.getCurrentBidderId()))
                .findFirst()
                .map(b -> b.getBidder().getDisplayName())
                .orElse(null),
            a.getBidCount().longValue(),
            a.getEndsAt(),
            a.getOriginalEndsAt(),
            emitted.stream().map(BidHistoryEntry::from).toList()
        );
    }
}

public record AuctionEndedEnvelope(
    String type,
    Long auctionId,
    OffsetDateTime serverTime,
    OffsetDateTime endsAt,
    AuctionEndOutcome endOutcome,
    Long finalBid,
    Long winnerUserId,
    String winnerDisplayName,
    Long bidCount
) { ... }
```

- [ ] **Step 5.2: Write failing StompAuctionBroadcastPublisher test**

Uses Spring's `SimpMessagingTemplate` mock; asserts `convertAndSend("/topic/auction/{id}", envelope)` called with correct topic and payload.

- [ ] **Step 5.3: Implement StompAuctionBroadcastPublisher**

```java
@Component
@RequiredArgsConstructor
public class StompAuctionBroadcastPublisher implements AuctionBroadcastPublisher {
    private final SimpMessagingTemplate messagingTemplate;

    @Override public void publishSettlement(BidSettlementEnvelope envelope) {
        messagingTemplate.convertAndSend("/topic/auction/" + envelope.auctionId(), envelope);
    }
    @Override public void publishEnded(AuctionEndedEnvelope envelope) {
        messagingTemplate.convertAndSend("/topic/auction/" + envelope.auctionId(), envelope);
    }
}
```

- [ ] **Step 5.4: Create CapturingAuctionBroadcastPublisher test helper**

```java
public class CapturingAuctionBroadcastPublisher implements AuctionBroadcastPublisher {
    public final List<BidSettlementEnvelope> settlements = new CopyOnWriteArrayList<>();
    public final List<AuctionEndedEnvelope> ended = new CopyOnWriteArrayList<>();
    @Override public void publishSettlement(BidSettlementEnvelope e) { settlements.add(e); }
    @Override public void publishEnded(AuctionEndedEnvelope e) { ended.add(e); }
}
```

Available as a `@TestConfiguration` primary bean replacing the Stomp impl in tests that want to inspect envelopes.

- [ ] **Step 5.5: Wire real publisher into BidService + ProxyBidService**

Replace any stub. Remove the `@Autowired(required=false)` or default no-op from Task 2.

- [ ] **Step 5.6: Update BidServiceTest, BidServiceSnipeTest, BidServiceBuyNowTest, ProxyBidServiceTest, ProxyBidResolutionTest, ProxyBidResurrectionTest, ProxyBidTieFlipTest, BidPlacementIntegrationTest**

All tests that previously used a Mockito mock publisher can now use `CapturingAuctionBroadcastPublisher` for richer assertions on envelope contents. Minimum: assert that `settlements` / `ended` lists have the expected size and that the first settlement's `currentBid`/`newBids` match the expected state.

- [ ] **Step 5.7: Write BidWebSocketIntegrationTest**

Spring Boot test with a real STOMP subscriber (using `WebSocketStompClient`) connecting to `ws://localhost:port/ws`, subscribing to `/topic/auction/{id}`, and asserting the envelope arrives within 2s of a `placeBid` call.

- [ ] **Step 5.8: Full test run + commit**

```bash
cd backend && ./mvnw test
git add backend/
git commit -m "feat(auction): websocket broadcast of bid settlements and auction-ended envelopes"
git push
```

---

## Task 6: Auction-end scheduler

**Spec reference:** §8 (full scheduler + closeOne flow).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndScheduler.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dev/DevAuctionEndController.java`
- Modify: `backend/src/main/resources/application.yml` — add `slpa.auction-end.enabled=true, scheduler-frequency=PT15S`.
- Modify: `backend/src/test/resources/application-test.yml` or equivalent — add `slpa.auction-end.enabled=false`.
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTaskTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndSchedulerTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndIntegrationTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/auctionend/BidSchedulerRaceTest.java`

**Steps:**

- [ ] **Step 6.1: Write failing AuctionEndTask tests — 4 outcome branches**

`AuctionEndTaskTest.java` with Mockito:
1. `sold — status transitions ACTIVE→ENDED, endOutcome=SOLD, winner fields populated, proxies exhausted`
2. `reserve not met — status ENDED, endOutcome=RESERVE_NOT_MET, winner fields null`
3. `no bids — status ENDED, endOutcome=NO_BIDS, winner fields null`
4. `skip if status != ACTIVE`
5. `skip if endsAt > now (snipe-extended)`
6. `publishes AuctionEndedEnvelope via afterCommit with winnerDisplayName resolved from userRepo`

Run: FAIL.

- [ ] **Step 6.2: Implement AuctionEndTask.closeOne per spec §8**

Full code in spec §8 `AuctionEndTask`. Inject `AuctionRepository`, `ProxyBidRepository`, `UserRepository`, `AuctionBroadcastPublisher`, `Clock`.

Run: 6 tests PASS.

- [ ] **Step 6.3: Write failing AuctionEndSchedulerTest**

1. `no-op when no due auctions`
2. `dispatches all due auctions to closeOne`
3. `continues after a closeOne throws (logs error, next auction still processed)`

Use a Mockito spy on `AuctionEndTask.closeOne` to assert call counts; have one invocation `doThrow(RuntimeException)` to test the error-swallow.

- [ ] **Step 6.4: Implement AuctionEndScheduler per spec §8**

Annotated `@ConditionalOnProperty(name = "slpa.auction-end.enabled", havingValue = "true", matchIfMissing = true)` and `@Scheduled(fixedDelayString = "${slpa.auction-end.scheduler-frequency:PT15S}")`.

Ensure `@EnableScheduling` is on the main `@SpringBootApplication` class — verify it's already present (likely from Epic 03 ownership monitor).

Run: scheduler tests PASS.

- [ ] **Step 6.5: Implement DevAuctionEndController**

```java
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
@RequiredArgsConstructor
public class DevAuctionEndController {
    private final AuctionRepository auctionRepo;
    private final AuctionEndTask auctionEndTask;
    private final Clock clock;

    @PostMapping("/auction-end/run-once")
    public Map<String, Object> runOnce() {
        List<Long> dueIds = auctionRepo.findActiveIdsDueForEnd(OffsetDateTime.now(clock));
        List<Long> closed = new ArrayList<>();
        for (Long id : dueIds) {
            try {
                auctionEndTask.closeOne(id);
                closed.add(id);
            } catch (Exception e) {
                // log + continue
            }
        }
        return Map.of("processed", closed);
    }

    @PostMapping("/auctions/{id}/close")
    public Map<String, Object> closeOne(@PathVariable Long id) {
        auctionEndTask.closeOne(id);
        return Map.of("closedId", id);
    }
}
```

- [ ] **Step 6.6: Update application.yml + application-test.yml**

```yaml
# application.yml
slpa:
  auction-end:
    enabled: true
    scheduler-frequency: PT15S
```

Test profile sets `slpa.auction-end.enabled: false` to prevent the scheduler from racing integration tests.

- [ ] **Step 6.7: Write AuctionEndIntegrationTest**

Full-stack: create auction with `endsAt=now-1s` + one bid below reserve → call dev `/auction-end/run-once` → assert status=ENDED, endOutcome=RESERVE_NOT_MET, envelope captured.

Create another auction with a bid above reserve → same flow → SOLD outcome + winnerUserId + finalBidAmount populated.

- [ ] **Step 6.8: Write BidSchedulerRaceTest**

Two-thread race: one thread places a bid in the snipe window (extending endsAt); the other thread calls `closeOne(id)` via the dev endpoint. Assertion: `closeOne` either (a) sees the new endsAt via `findByIdForUpdate` re-read and skips close, or (b) closed before the bid's transaction started (bid fails with AuctionAlreadyEndedException). No third outcome — auction must not end silently on a stale endsAt.

- [ ] **Step 6.9: Full test run + commit**

```bash
cd backend && ./mvnw test
git add backend/
git commit -m "feat(auction): 15s scheduled auction-end sweeper with per-auction re-lock and outcome classification"
git push
```

---

## Task 7: Concurrency retrofit

**Spec reference:** §5 (retrofit table), success criteria 2 + 4.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java` — signature change: `cancel(Long auctionId, String reason)`, re-fetch via `findByIdForUpdate`.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java` — pass id not entity to cancellation.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/OwnershipCheckTask.java` — switch `findById` to `findByIdForUpdate`.
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/concurrency/BidCancelRaceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/concurrency/BidSuspendRaceTest.java`
- Update: existing `CancellationServiceTest.java` — adjust for new signature (tests should need only minor edits — call `cancel(a.getId(), reason)` instead of `cancel(a, reason)`).

**Steps:**

- [ ] **Step 7.1: Refactor CancellationService**

Change signature to `public Auction cancel(Long auctionId, String reason)`. First line of method body: `Auction a = auctionRepo.findByIdForUpdate(auctionId).orElseThrow(() -> new AuctionNotFoundException(auctionId));`. Keep existing validation + side-effect logic downstream.

- [ ] **Step 7.2: Update AuctionController cancel route**

`cancellationService.cancel(a.getId(), req.reason())` → `cancellationService.cancel(pathId, req.reason())`. Seller authorization check happens before `cancel` — ensure the controller still loads the auction (without lock) for the auth check, or moves the auth check inside the service after the lock. Cleaner: keep the auth check where it is (outside lock) since seller ID doesn't change mid-flight; pass the id to `cancel`.

- [ ] **Step 7.3: Update existing CancellationServiceTest**

Tests that call `cancel(Auction, String)` need to pass an `auctionId` long instead. Mock `auctionRepo.findByIdForUpdate(id)` to return the prepared `Auction`.

Run: existing cancel tests pass.

- [ ] **Step 7.4: Update OwnershipCheckTask**

Change `auctionRepo.findById(auctionId)` to `auctionRepo.findByIdForUpdate(auctionId)`. No other changes — rest of the method body is correct.

Existing `OwnershipCheckTaskTest` should still pass — verify.

- [ ] **Step 7.5: Write failing BidCancelRaceTest**

Two-thread race via `TransactionTemplate`. Thread A `placeBid`; Thread B `CancellationService.cancel`. Each opens a tx, grabs the lock, commits or rolls back. Assertion:

```
exactly one of:
  - Thread A succeeds (bid placed), Thread B fails with InvalidAuctionStateException OR succeeds (ACTIVE→CANCELLED after the bid commits)
  - Thread A fails with InvalidAuctionStateException (auction CANCELLED), Thread B succeeds (cancel before bid)
```

Both outcomes are acceptable; the assertion is "exactly one operation succeeded mutating state; the other either failed cleanly OR appended to the committed state cleanly."

Tighter spec: assert `final auction.bidCount - initial auction.bidCount` matches the bid-success case, and `final auction.status` matches whichever operation won the race.

- [ ] **Step 7.6: Write failing BidSuspendRaceTest**

Same pattern — Thread A bids, Thread B triggers `OwnershipCheckTask.checkOne(id)` which suspends. Assert deterministic outcome (bid commits before suspend → auction.bidCount+=1, then SUSPENDED; suspend commits first → bid gets 409).

- [ ] **Step 7.7: Run full suite + commit**

```bash
cd backend && ./mvnw test
git add backend/
git commit -m "fix(auction): retrofit cancellation and ownership-check onto pessimistic lock"
git push
```

---

## Task 8: My Bids endpoint

**Spec reference:** §10 (query + derivation).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/MyBidStatus.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/MyBidSummary.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/AuctionSummaryForMyBids.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/MyBidStatusDeriver.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/MyBidsService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/MyBidsController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidRepository.java` — add the My Bids projection query.
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/mybids/MyBidStatusDeriverTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/mybids/MyBidsServiceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/mybids/MyBidsIntegrationTest.java`

**Steps:**

- [ ] **Step 8.1: Create DTOs + enum**

`MyBidStatus` enum, `MyBidSummary` record, `AuctionSummaryForMyBids` record per spec §9.

- [ ] **Step 8.2: Write failing MyBidStatusDeriverTest**

Table-driven, every derivation branch from spec §10 switch block:
- ACTIVE+winning → WINNING
- ACTIVE+not-winning → OUTBID
- ENDED+SOLD+winner → WON
- ENDED+SOLD+not-winner → LOST
- ENDED+BOUGHT_NOW+winner → WON
- ENDED+BOUGHT_NOW+not-winner → LOST
- ENDED+RESERVE_NOT_MET+high-bidder → RESERVE_NOT_MET
- ENDED+RESERVE_NOT_MET+not-high → LOST
- ENDED+NO_BIDS → LOST (defensive — unreachable in practice)
- ENDED+null endOutcome → LOST (defensive)
- CANCELLED → CANCELLED
- SUSPENDED → SUSPENDED

- [ ] **Step 8.3: Implement MyBidStatusDeriver per spec §10**

Static helper. Java 26 switch expression.

Run: tests PASS.

- [ ] **Step 8.4: Write failing MyBidsService query tests**

Use a capturing fake for the `BidRepository.findMyBidSummariesForUser` method; assert:
1. `status=active` filter maps to `Set.of(AuctionStatus.ACTIVE)`
2. `status=won` maps to `Set.of(ENDED)` + post-filter on `myBidStatus==WON`
3. `status=lost` maps to `Set.of(ENDED, CANCELLED, SUSPENDED)` + post-filter
4. `status=all` maps to no status filter
5. Default (param omitted) == `all`

- [ ] **Step 8.5: Implement MyBidsService**

```java
@Service @RequiredArgsConstructor
public class MyBidsService {
    private final BidRepository bidRepo;
    private final ProxyBidRepository proxyRepo;
    private final UserRepository userRepo;

    public Page<MyBidSummary> getMyBids(Long userId, String filter, Pageable pageable) {
        Set<AuctionStatus> statusFilter = switch (Optional.ofNullable(filter).orElse("all")) {
            case "active" -> Set.of(AuctionStatus.ACTIVE);
            case "won"    -> Set.of(AuctionStatus.ENDED);
            case "lost"   -> Set.of(AuctionStatus.ENDED, AuctionStatus.CANCELLED, AuctionStatus.SUSPENDED);
            default       -> EnumSet.allOf(AuctionStatus.class);
        };
        Page<MyBidProjection> projections = bidRepo.findMyBidSummariesForUser(userId, statusFilter, pageable);
        List<MyBidSummary> result = projections.getContent().stream()
            .map(p -> toSummary(userId, p))
            .filter(s -> passesFilter(filter, s.myBidStatus()))
            .toList();
        return new PageImpl<>(result, pageable, projections.getTotalElements());
    }
    // ...
}
```

- [ ] **Step 8.6: Add query to BidRepository**

JPQL per spec §10. If Hibernate HQL rejects the `FILTER (WHERE ...)` clause, fall back to the two-query shape noted in the spec. Document the fallback if used.

- [ ] **Step 8.7: Create MyBidsController**

```java
@RestController
@RequestMapping("/api/v1/users/me/bids")
@RequiredArgsConstructor
public class MyBidsController {
    private final MyBidsService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Page<MyBidSummary> getMyBids(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        return service.getMyBids(currentUser.getId(), status, pageable);
    }
}
```

- [ ] **Step 8.8: Write MyBidsIntegrationTest across all 7 status buckets**

Seeds a user who has bid on 7 auctions each representing one MyBidStatus. Calls `GET /api/v1/users/me/bids?status=all` and asserts all 7 rows returned with correct derived status. Then calls `?status=active`, `?status=won`, `?status=lost` and asserts filter behavior.

- [ ] **Step 8.9: Full test run + commit**

```bash
cd backend && ./mvnw test
git add backend/
git commit -m "feat(auction): My Bids endpoint with derived status across all 7 buckets"
git push
```

---

## Task 9: Documentation sweep

**Spec reference:** §15 (deferred work), post-launch doc maintenance.

**Files:**
- Modify: `README.md` — add Epic 04 sub-spec 1 summary paragraph (endpoints, WS topic, test counts).
- Modify: `docs/implementation/FOOTGUNS.md` — add new sections on pessimistic-lock gotchas, snipe loop per-row evaluation, `PROXY_AUTO` null IP, display-name resolution, afterCommit publish hygiene.
- Modify: `docs/implementation/DEFERRED_WORK.md` — add entries per spec §15.

**Steps:**

- [ ] **Step 9.1: Update README.md**

Add a section under the existing sub-spec summaries:

```markdown
### Epic 04 sub-spec 1 — Auction Engine Backend

Bid placement with pessimistic-lock concurrency, eBay-style proxy bidding with
EXHAUSTED resurrection, per-emitted-row snipe extension, inline buy-it-now, and
a 15-second scheduled auction-end sweeper. WebSocket settlement + auction-ended
envelopes broadcast on `/topic/auction/{id}` via `afterCommit` hygiene. My Bids
endpoint at `GET /api/v1/users/me/bids` with derived status across 7 buckets.
Cancellation, ownership-check, and suspension paths retrofitted onto the new
pessimistic lock.

New endpoints: `POST/GET /auctions/{id}/bids`, `POST/PUT/DELETE/GET
/auctions/{id}/proxy-bid`, `GET /users/me/bids`. Dev-profile triggers for
scheduler and single-auction close.

Test counts after this sub-spec: <BACKEND_COUNT> backend (previously 445), 411
frontend unchanged.
```

- [ ] **Step 9.2: Add FOOTGUNS entries**

Append to the existing sequence (F.58+):

- **F.58:** Pessimistic write lock on `Auction` — `@Lock(LockModeType.PESSIMISTIC_WRITE)` in `findByIdForUpdate` requires the caller's `@Transactional` to be active before the query. Calling `findByIdForUpdate` outside a transaction acquires no lock. Always verify the method enters through a `@Transactional` boundary.
- **F.59:** `@Transactional` self-invocation gotcha — `AuctionEndScheduler.sweep()` calling a `@Transactional` method on itself bypasses the Spring proxy. `closeOne` lives in a separate bean (`AuctionEndTask`) for exactly this reason.
- **F.60:** Snipe extension evaluated per emitted bid row, not once per transaction. A single `placeBid` that emits two bid rows (manual + proxy counter) evaluates snipe twice — if both land in the window, `endsAt` extends from each bid's timestamp sequentially.
- **F.61:** `PROXY_AUTO` bids always have `ip_address = null`. Storing the manual bidder's IP on the proxy owner's row would corrupt anti-fraud correlation. Enforce in `insertBid` helper by branching on `BidType`.
- **F.62:** WebSocket publish must fire from `TransactionSynchronization.afterCommit`, never inline. Readers subscribed to `/topic/auction/{id}` would otherwise see a `currentBid` that isn't visible to a fresh `GET` if the transaction ultimately rolls back.
- **F.63:** Scheduler `closeOne` must re-acquire `findByIdForUpdate` and re-check both `status == ACTIVE` AND `endsAt <= now`. A snipe extension can land between the scheduler's lock-free `findActiveIdsDueForEnd` query and `closeOne`'s transaction opening.
- **F.64:** Partial unique index (`proxy_bids WHERE status='ACTIVE'`) is not expressible via JPA `@Index`. Use a startup initializer that runs `CREATE UNIQUE INDEX IF NOT EXISTS ... WHERE status='ACTIVE'` — mirrors the Epic 03 sub-2 CHECK constraint initializer pattern.

- [ ] **Step 9.3: Update DEFERRED_WORK.md**

Add entries per spec §15:
- DESIGN.md §554 stale wording cleanup
- Outbid / won / reserve-not-met / auction-ended notifications → Epic 09
- User-targeted WS queues → Epic 09
- Escrow handoff on ENDED+SOLD → Epic 05
- Cancellation WS broadcast — re-evaluate in sub-spec 2

Remove any stale "My Bids backend" entry if present (this sub-spec delivers it).

- [ ] **Step 9.4: Final test run**

```bash
cd backend && ./mvnw test
```

Count tests (the exact number updates the README placeholder from step 9.1).

- [ ] **Step 9.5: Commit**

```bash
git add README.md docs/implementation/FOOTGUNS.md docs/implementation/DEFERRED_WORK.md
git commit -m "docs: README sweep + FOOTGUNS F.58-F.64 + DEFERRED_WORK for Epic 04 sub-spec 1"
git push
```

---

## Wrap-up

- [ ] **Run full test suite one final time**

```bash
cd backend && ./mvnw test
```

- [ ] **Verify no uncommitted or unpushed changes**

```bash
git status     # expect: clean
git log origin/task/04-sub-1-auction-engine-backend..HEAD     # expect: empty
```

- [ ] **Open PR**

```bash
gh pr create --base dev --title "Epic 04 sub-spec 1: auction engine backend" --body "$(cat <<'EOF'
## Summary

- Pessimistic-lock bid placement, eBay-style proxy with EXHAUSTED resurrection, per-row snipe extension, inline buy-it-now
- 15-second scheduled auction-end sweeper with per-auction re-lock and outcome classification (SOLD / RESERVE_NOT_MET / NO_BIDS / BOUGHT_NOW)
- WebSocket settlement + auction-ended envelopes via `afterCommit` publish hygiene
- `GET /api/v1/users/me/bids` with derived status across 7 buckets
- Retrofit of cancellation, ownership-check, and suspension paths onto the pessimistic lock
- Scope: backend only. Sub-spec 2 covers the frontend auction detail page, WS client, and My Bids dashboard UI.

## Test plan

- [ ] `./mvnw test` green (test count per Task 9 README update)
- [ ] Concurrency regression tests pass: bid-bid race, bid-cancel race, bid-scheduler race, bid-suspend race
- [ ] Tie-flip regression: manual bid at `P_max` → proxy retains position
- [ ] Resurrection regression: PUT increase on EXHAUSTED proxy → flips to ACTIVE, runs resolution
- [ ] Dev endpoints reachable: `POST /api/v1/dev/auction-end/run-once`, `POST /api/v1/dev/auctions/{id}/close`
- [ ] WebSocket subscription to `/topic/auction/{id}` receives envelopes within ~1s of commit
EOF
)"
```
