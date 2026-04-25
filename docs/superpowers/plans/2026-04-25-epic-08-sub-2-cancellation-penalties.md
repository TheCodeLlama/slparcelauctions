# Epic 08 Sub-Spec 2 — Cancellation Penalties & Anti-Circumvention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Layer escalating penalties + suspension enforcement + 48h post-cancel ownership watcher onto the existing cancellation flow. Pay-at-terminal "suspended-until-paid" model. Single PR to `dev`.

**Architecture:** Sub-spec 2 adds NO new backend packages. Extends `auction/CancellationService`, `auction/AuctionService`, `auction/monitoring/OwnershipCheckTask`, and creates a small `sl/PenaltyTerminalController`. Adds 2 columns on User, 1 on Auction, 2 on CancellationLog. New `CancellationOffenseKind` enum + new value on `FraudFlagReason` and `EscrowTransactionType`. Frontend adds `components/cancellation/`, two new dashboard components, and modifies the existing `CancelListingModal`. Closes the deferred-ledger "Cancellation WS broadcast" entry by folding `AUCTION_CANCELLED` into this sub-spec.

**Tech Stack:** Spring Boot 4 / Java 26 / JPA `ddl-auto: update` (no Flyway migrations). Next.js 16 / React 19 / TanStack Query v5 / Vitest 4 / MSW 2 / Headless UI.

**Spec:** `docs/superpowers/specs/2026-04-24-epic-08-sub-2-cancellation-penalties.md`

---

## Task 1 — Backend ladder + cancel-flow extensions

**Goal:** Land the penalty data model (User + Auction + CancellationLog columns), the `CancellationOffenseKind` enum, the new fraud + ledger enum values, the configuration properties class, and the `CancellationService.cancel` extension that walks the ladder and broadcasts `AUCTION_CANCELLED` on commit. No endpoints in this task — those are Task 2 / Task 3.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationOffenseKind.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCancelledEnvelope.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagReason.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTransactionType.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationPenaltyProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java`
- Modify: existing broadcast publisher (find under `auction/` or `escrow/broadcast/`) to add `publishCancelled` method
- Tests at: `backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceLadderTest.java` (new), `CancellationServiceTest.java` (existing — extend)

### Steps

- [ ] **Step 1: Add the User columns**

```java
// User.java — alongside listingSuspensionUntil, around line 124
@Builder.Default
@Column(name = "penalty_balance_owed", nullable = false,
        columnDefinition = "bigint not null default 0")
private Long penaltyBalanceOwed = 0L;

@Builder.Default
@Column(name = "banned_from_listing", nullable = false,
        columnDefinition = "boolean not null default false")
private Boolean bannedFromListing = false;
```

`columnDefinition` includes the SQL-side default so `ddl-auto: update` can add NOT NULL columns to existing rows on dev databases without failing — same pattern used for `tokenVersion` and `escrowExpiredUnfulfilled`.

- [ ] **Step 2: Add the Auction column**

```java
// Auction.java — find a logical position near other monitoring fields
@Column(name = "post_cancel_watch_until")
private OffsetDateTime postCancelWatchUntil;  // nullable
```

- [ ] **Step 3: Create `CancellationOffenseKind` enum**

```java
// auction/CancellationOffenseKind.java
package com.slparcelauctions.backend.auction;

/**
 * Discriminator for the consequence applied at a single cancellation event.
 * Stored on {@link CancellationLog} as a snapshot — historical fact, not a
 * derivation of what would be computed today. The decision uses live query
 * of prior log rows; the record is immutable. See spec §4.3.
 */
public enum CancellationOffenseKind {
    NONE,
    WARNING,
    PENALTY,
    PENALTY_AND_30D,
    PERMANENT_BAN
}
```

- [ ] **Step 4: Add columns to CancellationLog**

```java
// CancellationLog.java
@Enumerated(EnumType.STRING)
@Column(name = "penalty_kind", length = 30)
private CancellationOffenseKind penaltyKind;  // nullable for pre-sub-spec-2 rows

@Column(name = "penalty_amount_l")
private Long penaltyAmountL;  // nullable
```

- [ ] **Step 5: Add `CANCEL_AND_SELL` to `FraudFlagReason`**

```java
// FraudFlagReason.java — append
/**
 * Raised by the post-cancel ownership watcher (Epic 08 sub-spec 2 §6) when
 * a CANCELLED auction's parcel ownership flips to a non-seller avatar
 * within 48h of cancellation. Strong signal of off-platform deal.
 */
CANCEL_AND_SELL
```

The existing `FraudFlagReasonCheckConstraintInitializer` refreshes the SQL constraint at startup — no migration needed.

- [ ] **Step 6: Add `LISTING_PENALTY_PAYMENT` to `EscrowTransactionType`**

```java
// EscrowTransactionType.java — append
/**
 * Penalty payment from a seller at a SLPA terminal, paying down their
 * {@code User.penaltyBalanceOwed} debt. Sub-spec 2 §4.6.
 */
LISTING_PENALTY_PAYMENT
```

If `EscrowTransactionType` has a SQL constraint sync, it auto-handles. Otherwise check that the column is `EnumType.STRING` and accepts arbitrary values.

- [ ] **Step 7: Create `CancellationPenaltyProperties`**

```java
// auction/CancellationPenaltyProperties.java
package com.slparcelauctions.backend.auction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@ConfigurationProperties(prefix = "slpa.cancellation")
@Validated
public record CancellationPenaltyProperties(
        @NotNull Penalty penalty,
        @Min(1) int postCancelWatchHours
) {
    public record Penalty(
            @Min(1) long secondOffenseL,
            @Min(1) long thirdOffenseL,
            @Min(1) int thirdOffenseSuspensionDays
    ) {}
}
```

Register via `@EnableConfigurationProperties(CancellationPenaltyProperties.class)` on the configuration class for the `auction` package, OR `@ConfigurationPropertiesScan` if it's already enabled at app level.

- [ ] **Step 8: Add config to `application.yml`**

```yaml
slpa:
  cancellation:
    penalty:
      second-offense-l: 1000
      third-offense-l: 2500
      third-offense-suspension-days: 30
    post-cancel-watch-hours: 48
```

- [ ] **Step 9: Write failing test — `CancellationServiceLadderTest` ladder boundaries**

Create the file with these tests (use Mockito for dependencies — pure unit test):

```java
@Test
void cancel_activeWithBids_zeroPriorOffenses_writesWarningKind() {
    // Set up: seller with 0 prior cancelled-with-bids log rows.
    // Auction status = ACTIVE, bidCount = 1.
    // Action: cancellationService.cancel(auctionId, "reason")
    // Assert: log row penaltyKind = WARNING, penaltyAmountL = null.
    // Assert: seller.penaltyBalanceOwed unchanged (0).
    // Assert: seller.listingSuspensionUntil unchanged.
    // Assert: seller.bannedFromListing = false.
    // Assert: auction.postCancelWatchUntil = now + 48h.
}

@Test
void cancel_activeWithBids_onePriorOffense_writesPenaltyKindAndDebits1000() {
    // Set up: seller with 1 prior CancellationLog where hadBids=true.
    // Action: cancel
    // Assert: log row penaltyKind = PENALTY, penaltyAmountL = 1000.
    // Assert: seller.penaltyBalanceOwed = 1000 (was 0).
    // Assert: seller.listingSuspensionUntil unchanged.
}

@Test
void cancel_activeWithBids_twoPriorOffenses_writesPenaltyAnd30dAndDebits2500AndSuspends() {
    // Set up: seller with 2 prior cancelled-with-bids log rows.
    // Action: cancel
    // Assert: log row penaltyKind = PENALTY_AND_30D, penaltyAmountL = 2500.
    // Assert: seller.penaltyBalanceOwed += 2500.
    // Assert: seller.listingSuspensionUntil = now + 30 days (within 1s tolerance).
}

@Test
void cancel_activeWithBids_threePriorOffenses_writesPermanentBan() {
    // Set up: seller with 3 prior cancelled-with-bids log rows.
    // Action: cancel
    // Assert: log row penaltyKind = PERMANENT_BAN, penaltyAmountL = null.
    // Assert: seller.bannedFromListing = true.
    // Assert: penaltyBalanceOwed and listingSuspensionUntil unchanged from prior state.
}

@Test
void cancel_activeWithBids_fivePriorOffenses_stillWritesPermanentBan() {
    // Set up: seller with 5 prior offenses (already banned previously).
    // Assert: log row penaltyKind = PERMANENT_BAN.
    // Assert: bannedFromListing remains true (idempotent).
}

@Test
void cancel_activeWithoutBids_writesNoneKind() {
    // Set up: ACTIVE auction with bidCount = 0.
    // Assert: log row penaltyKind = NONE, penaltyAmountL = null.
    // Assert: seller fields unchanged.
    // Assert: auction.postCancelWatchUntil = null.
}

@Test
void cancel_preActiveStatus_writesNoneKindAndQueuesRefund() {
    // Set up: DRAFT_PAID auction, listingFeePaid = true.
    // Assert: log row penaltyKind = NONE.
    // Assert: ListingFeeRefund row queued (existing behavior).
    // Assert: postCancelWatchUntil = null.
}

@Test
void cancel_activeWithBids_existingPenaltyBalance_addsToIt() {
    // Set up: seller already owes 1000 (from prior offense).
    //         Now they hit offense #3.
    // Assert: penaltyBalanceOwed = 3500 (1000 + 2500 from offense #3).
}
```

Run: `cd backend && ./mvnw test -Dtest=CancellationServiceLadderTest` — expect all to FAIL (logic not yet implemented).

- [ ] **Step 10: Add the COUNT query to `CancellationLogRepository`**

```java
// CancellationLogRepository.java
@Query("SELECT count(c) FROM CancellationLog c " +
       "WHERE c.seller.id = :sellerId AND c.hadBids = true")
long countPriorOffensesWithBids(@Param("sellerId") Long sellerId);
```

- [ ] **Step 11: Implement the ladder in `CancellationService.cancel`**

Replace the existing method body. Key changes: COUNT before INSERT, ladder evaluation, snapshot to log row, apply consequence, set postCancelWatchUntil, register afterCommit broadcast. Inject `CancellationPenaltyProperties` and the broadcast publisher.

```java
@Transactional
public Auction cancel(Long auctionId, String reason) {
    Auction a = auctionRepo.findByIdForUpdate(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    if (!CANCELLABLE.contains(a.getStatus())) {
        throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "CANCEL");
    }
    if (a.getStatus() == AuctionStatus.ACTIVE
            && a.getEndsAt() != null
            && OffsetDateTime.now(clock).isAfter(a.getEndsAt())) {
        throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "CANCEL_AFTER_END");
    }

    AuctionStatus from = a.getStatus();
    boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;
    boolean activeWithBids = from == AuctionStatus.ACTIVE && hadBids;

    User seller = userRepo.findByIdForUpdate(a.getSeller().getId())
            .orElseThrow();  // pessimistic lock — must exist (FK)

    // 1) Pre-INSERT count → ladder index → consequence snapshot
    CancellationOffenseKind kind;
    Long amountL;
    if (activeWithBids) {
        long prior = logRepo.countPriorOffensesWithBids(seller.getId());
        long index = Math.min(prior, 3);
        switch ((int) index) {
            case 0 -> { kind = CancellationOffenseKind.WARNING; amountL = null; }
            case 1 -> { kind = CancellationOffenseKind.PENALTY;
                        amountL = props.penalty().secondOffenseL(); }
            case 2 -> { kind = CancellationOffenseKind.PENALTY_AND_30D;
                        amountL = props.penalty().thirdOffenseL(); }
            default -> { kind = CancellationOffenseKind.PERMANENT_BAN; amountL = null; }
        }
    } else {
        kind = CancellationOffenseKind.NONE;
        amountL = null;
    }

    // 2) INSERT log row with snapshot
    logRepo.save(CancellationLog.builder()
            .auction(a)
            .seller(seller)
            .cancelledFromStatus(from.name())
            .hadBids(hadBids)
            .reason(reason)
            .penaltyKind(kind)
            .penaltyAmountL(amountL)
            .build());

    // 3) Apply consequence + watcher window
    if (activeWithBids) {
        seller.setCancelledWithBids(seller.getCancelledWithBids() + 1);
        OffsetDateTime now = OffsetDateTime.now(clock);
        switch (kind) {
            case PENALTY -> seller.setPenaltyBalanceOwed(
                    seller.getPenaltyBalanceOwed() + amountL);
            case PENALTY_AND_30D -> {
                seller.setPenaltyBalanceOwed(
                        seller.getPenaltyBalanceOwed() + amountL);
                seller.setListingSuspensionUntil(
                        now.plusDays(props.penalty().thirdOffenseSuspensionDays()));
            }
            case PERMANENT_BAN -> seller.setBannedFromListing(true);
            default -> { /* WARNING — no-op */ }
        }
        userRepo.save(seller);
        a.setPostCancelWatchUntil(now.plusHours(props.postCancelWatchHours()));
    }

    // 4) Existing listing-fee refund logic (unchanged)
    if (Boolean.TRUE.equals(a.getListingFeePaid())
            && from != AuctionStatus.ACTIVE) {
        refundRepo.save(ListingFeeRefund.builder()
                .auction(a)
                .amount(a.getListingFeeAmt() == null ? 0L : a.getListingFeeAmt())
                .status(RefundStatus.PENDING)
                .build());
        log.info("Listing fee refund (PENDING) created for auction {}", a.getId());
    }

    // 5) Status flip + monitor lifecycle
    a.setStatus(AuctionStatus.CANCELLED);
    Auction saved = auctionRepo.save(a);
    monitorLifecycle.onAuctionClosed(saved);
    log.info("Auction {} cancelled from {} (hadBids={}, kind={})",
            a.getId(), from, hadBids, kind);

    // 6) afterCommit WS broadcast — never inside the tx
    final AuctionCancelledEnvelope env = AuctionCancelledEnvelope.of(
            saved, hadBids, OffsetDateTime.now(clock));
    TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() {
                    broadcastPublisher.publishCancelled(env);
                }
            });

    return saved;
}
```

Update the constructor injection list (`@RequiredArgsConstructor` adds these automatically): `CancellationPenaltyProperties props`, `AuctionBroadcastPublisher broadcastPublisher` (or whatever the existing publisher is named).

- [ ] **Step 12: Add `findByIdForUpdate` to UserRepository if missing**

Check if exists; if not add:
```java
@Query("select u from User u where u.id = :id")
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<User> findByIdForUpdate(@Param("id") Long id);
```

- [ ] **Step 13: Create `AuctionCancelledEnvelope`**

```java
// auction/dto/AuctionCancelledEnvelope.java
package com.slparcelauctions.backend.auction.dto;

import com.slparcelauctions.backend.auction.Auction;
import java.time.OffsetDateTime;

public record AuctionCancelledEnvelope(
        String type,
        Long auctionId,
        OffsetDateTime cancelledAt,
        Boolean hadBids
) {
    public static AuctionCancelledEnvelope of(Auction a, boolean hadBids, OffsetDateTime now) {
        return new AuctionCancelledEnvelope(
                "AUCTION_CANCELLED",
                a.getId(),
                now,
                hadBids
        );
    }
}
```

- [ ] **Step 14: Add `publishCancelled` to broadcast publisher**

Find the existing publisher under `auction/` (look for one that publishes bid envelopes or auction-end envelopes — sub-spec 1 added `publishReviewRevealed` to a similar file). Add:

```java
public void publishCancelled(AuctionCancelledEnvelope envelope) {
    messaging.convertAndSend(
            "/topic/auction/" + envelope.auctionId(),
            envelope);
    log.debug("Broadcast AUCTION_CANCELLED: auctionId={}", envelope.auctionId());
}
```

- [ ] **Step 15: Run ladder tests — pass**

`cd backend && ./mvnw test -Dtest=CancellationServiceLadderTest` — all 8 should pass.

- [ ] **Step 16: Add the WS broadcast test**

```java
// CancellationServiceLadderTest
@Test
void cancel_registersAfterCommitBroadcast() {
    // Use TransactionTemplate or @SpringBootTest minimal slice to verify
    // that registerSynchronization was called with afterCommit logic.
    // Alternatively, mock TransactionSynchronizationManager.
    // Assert publishCancelled called with correct envelope shape.
}
```

If tx synchronization is awkward to test in pure unit, leave as a slice/integration test in Step 17.

- [ ] **Step 17: Concurrent cancel race test**

Add to integration tests (use Testcontainers if available in the project, or `@DataJpaTest` with explicit transactions):

```java
@Test
void cancel_concurrent_serializesViaUserRowLock() {
    // Two threads cancelling two different auctions for the SAME seller
    // simultaneously, both with hadBids=true.
    // Both should serialize through the User row's PESSIMISTIC_WRITE lock.
    // Assert: priorOffenses readings are consistent (one sees 0, the other sees 1)
    //         and the cumulative penaltyBalanceOwed matches expectation.
}
```

If a concurrency test isn't already part of the test setup (sub-spec 1 didn't have one), this can be folded into the existing `BidCancelRaceTest` style. Read that file for the established pattern.

- [ ] **Step 18: Run the full backend test suite**

`cd backend && ./mvnw test`

All review tests + cancellation ladder tests should pass. Pre-existing flakes (libwebp native-load on arm64 Mac × 3 + parcel-tags 401 mismatch) remain unchanged from Sub-spec 1's baseline.

- [ ] **Step 19: Commit Task 1**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/User.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationOffenseKind.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationPenaltyProperties.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCancelledEnvelope.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagReason.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTransactionType.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceLadderTest.java
git commit -m "feat(cancellation): penalty ladder + offense snapshot on log + AUCTION_CANCELLED broadcast"
```

(Adjust file list to whatever broadcast-publisher file you actually edited in Step 14.)

---

## Task 2 — Backend gate + endpoints + watcher

**Goal:** Land the suspension gate at listing creation, the two GET endpoints (cancellation-status preview + paginated history), the extended `/me` response, and the post-cancel watcher integration in the existing ownership scheduler.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/SellerSuspendedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/SuspensionReason.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/CancellationExceptionHandler.java` (or extend an existing auction-package handler)
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationStatusController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationStatusService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/CancellationStatusResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/CancellationHistoryDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/NextConsequenceDto.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java` — gate in `create`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/CurrentUserResponse.java` (or wherever `/me` shape lives) — add 3 fields
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java` — extend `findDueForOwnershipCheck`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/OwnershipMonitorScheduler.java` — pass `now` param
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/OwnershipCheckTask.java` — branch on auction status, route fraud reason
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` — auth rules for new GET endpoints
- Tests for each new path

### Steps

- [ ] **Step 1: Create `SuspensionReason` enum**

```java
// auction/exception/SuspensionReason.java
package com.slparcelauctions.backend.auction.exception;

public enum SuspensionReason {
    PENALTY_OWED,
    TIMED_SUSPENSION,
    PERMANENT_BAN
}
```

- [ ] **Step 2: Create `SellerSuspendedException`**

```java
// auction/exception/SellerSuspendedException.java
package com.slparcelauctions.backend.auction.exception;

import lombok.Getter;

@Getter
public class SellerSuspendedException extends RuntimeException {
    private final SuspensionReason reason;

    public SellerSuspendedException(SuspensionReason reason) {
        super("Seller is suspended from listing: " + reason);
        this.reason = reason;
    }
}
```

- [ ] **Step 3: Write failing test — gate trips with right reason for each suspension state**

```java
// AuctionServiceTest (existing — extend) or new AuctionServiceSuspensionGateTest

@Test
void create_throwsPenaltyOwed_whenPenaltyBalancePositive() {
    User seller = sellerWith(penaltyBalanceOwed = 500L);
    AuctionCreateRequest req = validRequest();

    SellerSuspendedException ex = assertThrows(
            SellerSuspendedException.class,
            () -> auctionService.create(seller.getId(), req));

    assertThat(ex.getReason()).isEqualTo(SuspensionReason.PENALTY_OWED);
}

@Test
void create_throwsTimedSuspension_whenSuspensionUntilFuture() {
    User seller = sellerWith(listingSuspensionUntil = now.plusDays(5));
    // assert reason = TIMED_SUSPENSION
}

@Test
void create_throwsPermanentBan_whenBannedFromListingTrue() {
    User seller = sellerWith(bannedFromListing = true);
    // assert reason = PERMANENT_BAN
}

@Test
void create_succeeds_whenAllGatesClear() {
    User seller = sellerWith(/* default — clean */);
    Auction created = auctionService.create(seller.getId(), validRequest());
    assertThat(created).isNotNull();
}

@Test
void create_throwsBan_evenWhenAlsoSuspendedAndOwesPenalty() {
    // Order: ban → timed → penalty. Most restrictive first.
    User seller = sellerWith(
            bannedFromListing = true,
            listingSuspensionUntil = now.plusDays(5),
            penaltyBalanceOwed = 500L);
    // assert reason = PERMANENT_BAN (not TIMED or PENALTY)
}
```

Run: fails (gate not yet implemented).

- [ ] **Step 4: Implement the gate in `AuctionService.create`**

Add at the top of the `create` method, before the existing validation:

```java
@Transactional
public Auction create(Long sellerId, AuctionCreateRequest req) {
    User seller = userRepo.findById(sellerId)
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + sellerId));

    SuspensionReason reason = checkCanCreateListing(seller);
    if (reason != null) throw new SellerSuspendedException(reason);

    // ...existing validation + entity build, unchanged
}

private SuspensionReason checkCanCreateListing(User u) {
    if (Boolean.TRUE.equals(u.getBannedFromListing())) {
        return SuspensionReason.PERMANENT_BAN;
    }
    if (u.getListingSuspensionUntil() != null
            && OffsetDateTime.now(clock).isBefore(u.getListingSuspensionUntil())) {
        return SuspensionReason.TIMED_SUSPENSION;
    }
    if (u.getPenaltyBalanceOwed() != null && u.getPenaltyBalanceOwed() > 0L) {
        return SuspensionReason.PENALTY_OWED;
    }
    return null;
}
```

Inject `Clock` if not already (`@RequiredArgsConstructor` handles it). The existing `AuctionService` already loads the seller — keep that single load and pass to the gate check.

Run: gate tests pass.

- [ ] **Step 5: Create `CancellationExceptionHandler` mapping**

Look for an existing `auction/exception` handler. Either extend it or create a new `@RestControllerAdvice(basePackageClasses = AuctionController.class)`:

```java
@ExceptionHandler(SellerSuspendedException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public ProblemDetail sellerSuspended(SellerSuspendedException ex) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    pd.setType(URI.create("https://slpa.example/problems/seller-suspended"));
    pd.setTitle("Listing creation suspended");
    pd.setProperty("code", ex.getReason().name());
    pd.setDetail(switch (ex.getReason()) {
        case PENALTY_OWED -> "You have an outstanding penalty balance. Pay at any SLPA terminal to resume listing.";
        case TIMED_SUSPENSION -> "Your listing privileges are temporarily suspended.";
        case PERMANENT_BAN -> "Your listing privileges have been permanently suspended.";
    });
    return pd;
}
```

Test the handler in a `@WebMvcTest` slice — assert 403 status, `code` field carries the right enum value.

- [ ] **Step 6: Write failing test — `/users/me/cancellation-status` shape**

```java
// CancellationStatusControllerTest — @WebMvcTest

@Test
void status_zeroPriorOffenses_returnsWarningAsNextConsequence() {
    // mock service: priorOffensesWithBids = 0
    // assert: nextConsequenceIfBidsPresent.kind = "WARNING"
    // assert: amountL = null, suspends30Days = false, permanentBan = false
}

@Test
void status_onePriorOffense_returnsPenaltyAsNext() { /* kind=PENALTY, amountL=1000 */ }

@Test
void status_twoPriorOffenses_returnsPenaltyAnd30dAsNext() { /* kind=PENALTY_AND_30D, amountL=2500, suspends30Days=true */ }

@Test
void status_threePlusOffenses_returnsPermanentBanAsNext() { /* kind=PERMANENT_BAN, permanentBan=true, amountL=null */ }

@Test
void status_currentSuspensionEchoesUserState() {
    // user has penaltyBalanceOwed=500, listingSuspensionUntil=t+5d, bannedFromListing=false
    // assert response.currentSuspension matches
}
```

- [ ] **Step 7: Create the DTOs**

```java
// auction/dto/CancellationStatusResponse.java
public record CancellationStatusResponse(
        long priorOffensesWithBids,
        CurrentSuspension currentSuspension,
        NextConsequenceDto nextConsequenceIfBidsPresent
) {
    public record CurrentSuspension(
            Long penaltyBalanceOwed,
            OffsetDateTime listingSuspensionUntil,
            Boolean bannedFromListing
    ) {}
}

// auction/dto/NextConsequenceDto.java
public record NextConsequenceDto(
        CancellationOffenseKind kind,
        Long amountL,           // nullable
        Boolean suspends30Days,
        Boolean permanentBan
) {
    public static NextConsequenceDto from(CancellationOffenseKind kind, Long amount) {
        return new NextConsequenceDto(
                kind,
                amount,
                kind == CancellationOffenseKind.PENALTY_AND_30D,
                kind == CancellationOffenseKind.PERMANENT_BAN
        );
    }
}

// auction/dto/CancellationHistoryDto.java
public record CancellationHistoryDto(
        Long auctionId,
        String auctionTitle,
        String primaryPhotoUrl,
        String cancelledFromStatus,
        Boolean hadBids,
        String reason,
        OffsetDateTime cancelledAt,
        PenaltyApplied penaltyApplied  // nullable
) {
    public record PenaltyApplied(
            CancellationOffenseKind kind,
            Long amountL
    ) {}

    public static CancellationHistoryDto from(CancellationLog log, String primaryPhotoUrl) {
        PenaltyApplied applied = (log.getPenaltyKind() == null
                || log.getPenaltyKind() == CancellationOffenseKind.NONE)
                ? null
                : new PenaltyApplied(log.getPenaltyKind(), log.getPenaltyAmountL());
        return new CancellationHistoryDto(
                log.getAuction().getId(),
                log.getAuction().getTitle(),
                primaryPhotoUrl,
                log.getCancelledFromStatus(),
                log.getHadBids(),
                log.getReason(),
                log.getCancelledAt(),
                applied);
    }
}
```

- [ ] **Step 8: Create `CancellationStatusService`**

```java
@Service
@RequiredArgsConstructor
public class CancellationStatusService {
    private final CancellationLogRepository logRepo;
    private final UserRepository userRepo;
    private final CancellationPenaltyProperties props;

    @Transactional(readOnly = true)
    public CancellationStatusResponse statusFor(Long userId) {
        User u = userRepo.findById(userId).orElseThrow(...);
        long prior = logRepo.countPriorOffensesWithBids(userId);
        long index = Math.min(prior, 3);
        CancellationOffenseKind nextKind;
        Long nextAmount;
        switch ((int) index) {
            case 0 -> { nextKind = CancellationOffenseKind.WARNING; nextAmount = null; }
            case 1 -> { nextKind = CancellationOffenseKind.PENALTY;
                        nextAmount = props.penalty().secondOffenseL(); }
            case 2 -> { nextKind = CancellationOffenseKind.PENALTY_AND_30D;
                        nextAmount = props.penalty().thirdOffenseL(); }
            default -> { nextKind = CancellationOffenseKind.PERMANENT_BAN; nextAmount = null; }
        }

        return new CancellationStatusResponse(
                prior,
                new CancellationStatusResponse.CurrentSuspension(
                        u.getPenaltyBalanceOwed(),
                        u.getListingSuspensionUntil(),
                        u.getBannedFromListing()),
                NextConsequenceDto.from(nextKind, nextAmount));
    }

    @Transactional(readOnly = true)
    public Page<CancellationHistoryDto> historyFor(Long userId, Pageable page) {
        Pageable sorted = PageRequest.of(page.getPageNumber(),
                Math.min(page.getPageSize(), 50),
                Sort.by(Sort.Direction.DESC, "cancelledAt"));
        Page<CancellationLog> logs = logRepo.findBySellerId(userId, sorted);
        return logs.map(log -> CancellationHistoryDto.from(log,
                resolvePrimaryPhotoUrl(log.getAuction())));
    }

    private String resolvePrimaryPhotoUrl(Auction auction) {
        return auction.getPhotos().stream()
                .sorted(Comparator.comparing(AuctionPhoto::getSortOrder))
                .findFirst()
                .map(AuctionPhoto::getUrl)
                .orElseGet(() -> auction.getParcel().getSnapshotUrl());
    }
}
```

Add to `CancellationLogRepository`:
```java
Page<CancellationLog> findBySellerId(Long sellerId, Pageable page);
```

- [ ] **Step 9: Create `CancellationStatusController`**

```java
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class CancellationStatusController {
    private final CancellationStatusService service;

    @GetMapping("/cancellation-status")
    public CancellationStatusResponse status(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.statusFor(principal.userId());
    }

    @GetMapping("/cancellation-history")
    public PagedResponse<CancellationHistoryDto> history(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return PagedResponse.from(
                service.historyFor(principal.userId(), PageRequest.of(page, size)));
    }
}
```

- [ ] **Step 10: Update SecurityConfig**

```java
.requestMatchers(HttpMethod.GET, "/api/v1/users/me/cancellation-status").authenticated()
.requestMatchers(HttpMethod.GET, "/api/v1/users/me/cancellation-history").authenticated()
```

Place these BEFORE any catch-all `/api/v1/**` rule.

- [ ] **Step 11: Run controller tests — pass**

`./mvnw test -Dtest=CancellationStatusControllerTest` — pass.

- [ ] **Step 12: Extend `/me` response with new fields**

Find the existing `/me` endpoint controller / DTO. The DTO is likely `CurrentUserResponse` or similar in `user/dto/`. Add 3 fields:

```java
public record CurrentUserResponse(
    // ... existing fields
    Long penaltyBalanceOwed,
    OffsetDateTime listingSuspensionUntil,
    Boolean bannedFromListing
) {
    public static CurrentUserResponse from(User u) {
        return new CurrentUserResponse(
            // ... existing field mappings
            u.getPenaltyBalanceOwed(),
            u.getListingSuspensionUntil(),
            u.getBannedFromListing()
        );
    }
}
```

Run any existing `/me` tests — they may fail on positional constructor changes. Update them to include the new fields.

- [ ] **Step 13: Extend `findDueForOwnershipCheck` for post-cancel watch**

```java
// AuctionRepository.java
@Query("""
    SELECT a.id FROM Auction a
    WHERE (a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE
            AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt < :cutoff))
       OR (a.postCancelWatchUntil IS NOT NULL
            AND a.postCancelWatchUntil > :now
            AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt < :cutoff))
    """)
List<Long> findDueForOwnershipCheck(
        @Param("cutoff") OffsetDateTime cutoff,
        @Param("now") OffsetDateTime now);
```

- [ ] **Step 14: Update `OwnershipMonitorScheduler` to pass `now`**

```java
@Scheduled(fixedDelayString = "${slpa.ownership-monitor.scheduler-frequency:PT30S}")
public void dispatchDueChecks() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime cutoff = now.minusMinutes(props.getCheckIntervalMinutes());
    List<Long> dueIds = auctionRepo.findDueForOwnershipCheck(cutoff, now);
    // ... existing dispatch logic
}
```

- [ ] **Step 15: Branch `OwnershipCheckTask` on auction status**

The current `OwnershipCheckTask.checkOne(id)` raises `OWNERSHIP_CHANGED_TO_UNKNOWN` on mismatch. Branch on `auction.status`:

```java
@Async
@Transactional
public void checkOne(Long auctionId) {
    Auction a = auctionRepo.findById(auctionId).orElseThrow(...);
    // ... existing World API call to get observed owner ...

    a.setLastOwnershipCheckAt(OffsetDateTime.now(clock));

    if (mismatchDetected) {
        FraudFlagReason reason = (a.getStatus() == AuctionStatus.CANCELLED)
                ? FraudFlagReason.CANCEL_AND_SELL
                : FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN;

        Map<String, Object> evidence = (a.getStatus() == AuctionStatus.CANCELLED)
                ? buildCancelAndSellEvidence(a, observedOwnerKey)
                : buildExistingMismatchEvidence(a, observedOwnerKey);

        raiseFlag(a, reason, evidence);

        if (a.getStatus() == AuctionStatus.CANCELLED) {
            // Prevent re-flagging on subsequent ticks
            a.setPostCancelWatchUntil(null);
        }
        // ACTIVE auctions go through the existing SuspensionService.suspendDueToOwnershipChange
        // which already pulls them out of the watcher query (status changes from ACTIVE).
    }

    auctionRepo.save(a);
}

private Map<String, Object> buildCancelAndSellEvidence(Auction a, String observedKey) {
    OffsetDateTime cancelledAt = /* derive from CancellationLog or auction status-change time */;
    OffsetDateTime now = OffsetDateTime.now(clock);
    double hoursSince = (now.toEpochSecond() - cancelledAt.toEpochSecond()) / 3600.0;
    return Map.of(
        "cancelledAt", cancelledAt.toString(),
        "expectedSellerKey", a.getSeller().getSlAvatarUuid().toString(),
        "observedOwnerKey", observedKey,
        "hoursSinceCancellation", hoursSince,
        "parcelRegion", a.getParcel().getRegionName(),
        "parcelLocalId", a.getParcel().getLocalId(),
        "auctionTitle", a.getTitle()
    );
}
```

For `cancelledAt`: easiest is to query the CancellationLog by auction id; alternatively, add a `cancelledAt` column to Auction (mirrors `postCancelWatchUntil`). The query approach is one read per flag — acceptable. Decide based on existing patterns; the spec doesn't constrain this.

- [ ] **Step 16: Test the watcher integration**

```java
@Test
void checkOne_cancelledStatus_mismatch_raisesCancelAndSellFlag() {
    // Set up: cancelled auction, postCancelWatchUntil = now + 47h
    //         World API returns owner != seller
    // Action: checkOne(auctionId)
    // Assert: FraudFlag created with reason = CANCEL_AND_SELL
    // Assert: evidence map contains expected fields including hoursSinceCancellation
    // Assert: auction.postCancelWatchUntil == null (cleared)
}

@Test
void checkOne_cancelledStatus_noMismatch_doesNotRaiseFlag() {
    // Owner matches seller — no flag, watcher window unchanged
}

@Test
void checkOne_cancelledStatus_postWatchExpired_doesNotRunWorldApi() {
    // postCancelWatchUntil < now → query shouldn't pick it up at all
    // (verify by repository test, since OwnershipCheckTask already gets
    //  filtered ids from the scheduler).
}

@Test
void findDueForOwnershipCheck_includesActiveAndPostCancelWatched() {
    // Repo test:
    // 3 auctions: ACTIVE, CANCELLED-watched, CANCELLED-watch-expired
    // assert: returns ids of first two only
}
```

- [ ] **Step 17: Run full backend suite**

`./mvnw test` — all pass.

- [ ] **Step 18: Commit Task 2**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/exception/ \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationStatusController.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationStatusService.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/dto/CancellationStatusResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/dto/CancellationHistoryDto.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/dto/NextConsequenceDto.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/ \
        backend/src/main/java/com/slparcelauctions/backend/user/dto/CurrentUserResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/
git commit -m "feat(cancellation): suspension gate + status/history endpoints + post-cancel watcher"
```

---

## Task 3 — Backend terminal payment endpoints

**Goal:** Land the two SL-terminal-only endpoints (lookup + payment), terminal auth integration, partial payment + idempotent replay, ledger row insertion, pessimistic User-row lock.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/PenaltyTerminalController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/PenaltyTerminalService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/PenaltyLookupRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/PenaltyLookupResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/PenaltyPaymentRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/PenaltyPaymentResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/exception/PenaltyOverpaymentException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` — terminal-auth on `/sl/penalty-*`
- Tests for each path

### Steps

- [ ] **Step 1: Create the request/response DTOs**

```java
// PenaltyLookupRequest.java
public record PenaltyLookupRequest(
    @NotNull UUID slAvatarUuid,
    @NotBlank String terminalId
) {}

// PenaltyLookupResponse.java
public record PenaltyLookupResponse(
    Long userId,
    String displayName,
    Long penaltyBalanceOwed
) {}

// PenaltyPaymentRequest.java
public record PenaltyPaymentRequest(
    @NotNull UUID slAvatarUuid,
    @NotBlank String slTransactionId,
    @Min(1) Long amount,
    @NotBlank String terminalId
) {}

// PenaltyPaymentResponse.java
public record PenaltyPaymentResponse(Long remainingBalance) {}
```

- [ ] **Step 2: Create `PenaltyOverpaymentException`**

```java
@Getter
@RequiredArgsConstructor
public class PenaltyOverpaymentException extends RuntimeException {
    private final long requested;
    private final long available;
}
```

Map to 422 in the existing SL exception handler (look for one in the `sl` package or extend `GlobalExceptionHandler` with a slice-scoped advice).

- [ ] **Step 3: Write failing slice tests for both endpoints**

```java
// PenaltyTerminalControllerTest — @WebMvcTest(PenaltyTerminalController.class)

@Test
void lookup_returnsBalance_whenUserOwes() {
    // mock: user with penaltyBalanceOwed = 1000
    // POST /sl/penalty-lookup with valid X-SecondLife-Owner-Key header
    // assert 200 with response { userId, displayName, penaltyBalanceOwed: 1000 }
}

@Test
void lookup_returns404_whenNoUserForAvatar() { /* unknown UUID */ }

@Test
void lookup_returns404_whenBalanceIsZero() {
    // user exists, owes nothing — terminal can show "no debt"
}

@Test
void lookup_returns401_withoutSlOwnerKeyHeader() { /* terminal auth */ }

@Test
void payment_partialClear_returns200WithRemainingBalance() {
    // user owes 1000, pay 600 → remainingBalance = 400
}

@Test
void payment_fullClear_returnsZeroBalance() { /* pay 1000 → remainingBalance = 0 */ }

@Test
void payment_idempotentReplay_returnsCurrentBalance() {
    // pay txn-1 of 600 (balance: 1000 → 400)
    // replay txn-1 → returns { remainingBalance: 400 }, NO double-decrement
}

@Test
void payment_overpayment_returns422() {
    // user owes 500, attempt to pay 1000 → 422 PenaltyOverpaymentException
}

@Test
void payment_unknownAvatar_returns404() { /* avatar UUID doesn't map to a user */ }

@Test
void payment_amountZero_returns400() { /* validation @Min(1) */ }

@Test
void payment_returns401_withoutSlOwnerKeyHeader() { /* terminal auth */ }
```

- [ ] **Step 4: Create `PenaltyTerminalService`**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PenaltyTerminalService {
    private final UserRepository userRepo;
    private final EscrowTransactionRepository ledgerRepo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PenaltyLookupResponse lookup(UUID slAvatarUuid) {
        User u = userRepo.findBySlAvatarUuid(slAvatarUuid)
                .orElseThrow(() -> new UserNotFoundException("Avatar: " + slAvatarUuid));
        long balance = u.getPenaltyBalanceOwed() == null ? 0L : u.getPenaltyBalanceOwed();
        if (balance == 0) {
            // 404 — no debt
            throw new UserNotFoundException("No penalty balance for avatar: " + slAvatarUuid);
        }
        return new PenaltyLookupResponse(u.getId(), u.getDisplayName(), balance);
    }

    @Transactional
    public PenaltyPaymentResponse pay(PenaltyPaymentRequest req) {
        User u = userRepo.findBySlAvatarUuid(req.slAvatarUuid())
                .orElseThrow(() -> new UserNotFoundException("Avatar: " + req.slAvatarUuid()));

        // Idempotency check — has this slTransactionId already been recorded?
        Optional<EscrowTransaction> existing =
                ledgerRepo.findBySlTransactionId(req.slTransactionId());
        if (existing.isPresent()) {
            // Benign replay — return current balance, no double-debit
            log.info("Idempotent replay of penalty payment {} for user {}",
                    req.slTransactionId(), u.getId());
            return new PenaltyPaymentResponse(
                    u.getPenaltyBalanceOwed() == null ? 0L : u.getPenaltyBalanceOwed());
        }

        // Pessimistic lock for the apply
        User locked = userRepo.findByIdForUpdate(u.getId()).orElseThrow();
        long balance = locked.getPenaltyBalanceOwed() == null ? 0L : locked.getPenaltyBalanceOwed();
        if (req.amount() > balance) {
            throw new PenaltyOverpaymentException(req.amount(), balance);
        }

        long newBalance = balance - req.amount();
        locked.setPenaltyBalanceOwed(newBalance);
        userRepo.save(locked);

        OffsetDateTime now = OffsetDateTime.now(clock);
        ledgerRepo.save(EscrowTransaction.builder()
                .type(EscrowTransactionType.LISTING_PENALTY_PAYMENT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(req.amount())
                .payer(locked)
                .payee(null)
                .slTransactionId(req.slTransactionId())
                .terminalId(req.terminalId())
                .completedAt(now)
                .build());

        log.info("Penalty payment recorded: user={}, amount={}, remaining={}, terminal={}",
                locked.getId(), req.amount(), newBalance, req.terminalId());

        return new PenaltyPaymentResponse(newBalance);
    }
}
```

Add to `UserRepository` if missing:
```java
Optional<User> findBySlAvatarUuid(UUID slAvatarUuid);
```

Add to `EscrowTransactionRepository` if missing:
```java
Optional<EscrowTransaction> findBySlTransactionId(String slTransactionId);
```

- [ ] **Step 5: Create `PenaltyTerminalController`**

```java
@RestController
@RequestMapping("/api/v1/sl")
@RequiredArgsConstructor
public class PenaltyTerminalController {
    private final PenaltyTerminalService service;

    @PostMapping("/penalty-lookup")
    public PenaltyLookupResponse lookup(@Valid @RequestBody PenaltyLookupRequest req) {
        return service.lookup(req.slAvatarUuid());
    }

    @PostMapping("/penalty-payment")
    public PenaltyPaymentResponse pay(@Valid @RequestBody PenaltyPaymentRequest req) {
        return service.pay(req);
    }
}
```

The terminal auth (X-SecondLife-Owner-Key) is enforced by the existing security filter chain configured for `/sl/**` paths in SecurityConfig — find the existing pattern (Epic 02 / Epic 03 set this up).

- [ ] **Step 6: Update SecurityConfig for the new paths**

```java
.requestMatchers(HttpMethod.POST, "/api/v1/sl/penalty-lookup").hasRole("SL_TERMINAL")
.requestMatchers(HttpMethod.POST, "/api/v1/sl/penalty-payment").hasRole("SL_TERMINAL")
```

(Adjust the role/auth mechanism to match the existing terminal pattern from Epic 02 / 03 / 05 — copy the exact pattern used by the escrow terminal endpoints, which already validate `X-SecondLife-Owner-Key`.)

- [ ] **Step 7: Map `PenaltyOverpaymentException` to 422**

In an existing or new `@RestControllerAdvice`:
```java
@ExceptionHandler(PenaltyOverpaymentException.class)
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public ProblemDetail overpayment(PenaltyOverpaymentException ex) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    pd.setTitle("Penalty payment exceeds outstanding balance");
    pd.setProperty("requested", ex.getRequested());
    pd.setProperty("available", ex.getAvailable());
    return pd;
}
```

- [ ] **Step 8: Concurrency test — simultaneous payments at two terminals**

```java
// PenaltyTerminalServiceConcurrencyTest — integration test
@Test
void pay_simultaneousAtTwoTerminals_serializesViaUserRowLock() {
    // Set up: user owes 1000.
    // Two threads call pay() simultaneously, each with amount=600.
    // First commits → balance: 400. Second sees 400, attempts 600 → overpayment 422.
    // Or both commit if amounts fit: e.g., 400 + 600 → first leaves 600, second leaves 0.
    // Assert: ledger has exactly two LISTING_PENALTY_PAYMENT rows totaling correct amount,
    //         no double-debit, no negative balance.
}
```

- [ ] **Step 9: Run all backend tests**

`./mvnw test` — pass.

- [ ] **Step 10: Commit Task 3**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/sl/ \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTransactionRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/sl/
git commit -m "feat(cancellation): /sl/penalty-lookup + /sl/penalty-payment with idempotent replay"
```

---

## Task 4 — Frontend

**Goal:** Land all UI surfaces — modal copy, suspension banner, history section, listing-wizard gate handling, AUCTION_CANCELLED WS handler. All built on top of the primitives that already exist in the design system.

**Files:**
- Create: `frontend/src/types/cancellation.ts`
- Create: `frontend/src/lib/api/cancellations.ts`
- Create: `frontend/src/hooks/useCancellationStatus.ts`
- Create: `frontend/src/hooks/useCancellationHistory.ts`
- Create: `frontend/src/components/cancellation/CancellationConsequenceBadge.tsx`
- Create: `frontend/src/components/dashboard/SuspensionBanner.tsx`
- Create: `frontend/src/components/dashboard/CancellationHistorySection.tsx`
- Modify: `frontend/src/components/listing/CancelListingModal.tsx`
- Modify: `frontend/src/types/auction.ts` (extend AuctionTopicEnvelope union)
- Modify: `frontend/src/app/auction/[id]/AuctionDetailClient.tsx`
- Modify: `frontend/src/app/dashboard/(verified)/overview/page.tsx`
- Modify: `frontend/src/lib/user.ts` (or wherever `/me` types live) — add 3 fields
- Modify: listing wizard error handler to catch 403 SellerSuspendedException
- Tests for each component + hook

### Steps

- [ ] **Step 1: `types/cancellation.ts`**

```typescript
export type CancellationOffenseKind =
  | "NONE"
  | "WARNING"
  | "PENALTY"
  | "PENALTY_AND_30D"
  | "PERMANENT_BAN";

export type SuspensionReasonCode =
  | "PENALTY_OWED"
  | "TIMED_SUSPENSION"
  | "PERMANENT_BAN";

export interface CancellationStatusResponse {
  priorOffensesWithBids: number;
  currentSuspension: {
    penaltyBalanceOwed: number;
    listingSuspensionUntil: string | null;
    bannedFromListing: boolean;
  };
  nextConsequenceIfBidsPresent: {
    kind: CancellationOffenseKind;
    amountL: number | null;
    suspends30Days: boolean;
    permanentBan: boolean;
  };
}

export interface CancellationHistoryDto {
  auctionId: number;
  auctionTitle: string;
  primaryPhotoUrl: string | null;
  cancelledFromStatus: string;
  hadBids: boolean;
  reason: string;
  cancelledAt: string;
  penaltyApplied: {
    kind: CancellationOffenseKind;
    amountL: number | null;
  } | null;
}
```

- [ ] **Step 2: `lib/api/cancellations.ts`**

Use the project's existing `api.get` helper (Sub-spec 1's plan referenced a non-existent `apiFetch` — the actual primitive is `api.get`/`api.post` from `@/lib/api`).

```typescript
import { api } from "@/lib/api";
import type { PagedResponse } from "@/types/page";
import type { CancellationStatusResponse, CancellationHistoryDto } from "@/types/cancellation";

export const getCancellationStatus = () =>
  api.get<CancellationStatusResponse>("/api/v1/users/me/cancellation-status");

export const getCancellationHistory = (page = 0, size = 10) =>
  api.get<PagedResponse<CancellationHistoryDto>>(
    `/api/v1/users/me/cancellation-history?page=${page}&size=${size}`,
  );
```

- [ ] **Step 3: `hooks/useCancellationStatus.ts` + `useCancellationHistory.ts`**

```typescript
// useCancellationStatus.ts
import { useQuery } from "@tanstack/react-query";
import { getCancellationStatus } from "@/lib/api/cancellations";

export const cancellationKeys = {
  status: ["me", "cancellation-status"] as const,
  historyAll: ["me", "cancellation-history"] as const,
  history: (page: number, size: number) =>
    [...cancellationKeys.historyAll, page, size] as const,
};

export const useCancellationStatus = () =>
  useQuery({
    queryKey: cancellationKeys.status,
    queryFn: getCancellationStatus,
    staleTime: 30_000,
  });

// useCancellationHistory.ts
import { useQuery } from "@tanstack/react-query";
import { getCancellationHistory } from "@/lib/api/cancellations";
import { cancellationKeys } from "./useCancellationStatus";

export const useCancellationHistory = (page: number, size = 10) =>
  useQuery({
    queryKey: cancellationKeys.history(page, size),
    queryFn: () => getCancellationHistory(page, size),
  });
```

Tests for each — assert query keys are stable, MSW handlers respond, refetch behavior on staleness. Pattern from Sub-spec 1's `useReviews.test.tsx`.

- [ ] **Step 4: `CancellationConsequenceBadge`**

```tsx
import { Badge } from "@/components/ui/Badge";
import type { CancellationOffenseKind } from "@/types/cancellation";

interface Props {
  kind: CancellationOffenseKind | null;
  amountL: number | null;
}

export function CancellationConsequenceBadge({ kind, amountL }: Props) {
  if (!kind || kind === "NONE") {
    return <Badge variant="muted">No penalty</Badge>;
  }
  switch (kind) {
    case "WARNING":
      return <Badge variant="warning">Warning</Badge>;
    case "PENALTY":
      return <Badge variant="danger">L${amountL?.toLocaleString()} penalty</Badge>;
    case "PENALTY_AND_30D":
      return (
        <Badge variant="danger">
          L${amountL?.toLocaleString()} + 30-day suspension
        </Badge>
      );
    case "PERMANENT_BAN":
      return <Badge variant="danger">Permanent ban</Badge>;
    default:
      return null;
  }
}
```

Use the existing `Badge` primitive variants. If `muted`/`warning`/`danger` don't exist as badge variants, look at the project's design tokens and pick equivalents (the existing primitives may use `tone="default" | "warning" | "danger"` per CONVENTIONS.md §164).

Test: render each kind, assert correct copy + tone class.

- [ ] **Step 5: `SuspensionBanner`**

```tsx
"use client";
import { useCurrentUser } from "@/lib/user";
import { Banner } from "@/components/ui/Banner";  // or existing equivalent

export function SuspensionBanner() {
  const { data: user } = useCurrentUser();
  if (!user) return null;

  const banned = user.bannedFromListing;
  const suspendedUntil = user.listingSuspensionUntil
    ? new Date(user.listingSuspensionUntil)
    : null;
  const isTimedSuspended = suspendedUntil && suspendedUntil > new Date();
  const owesPenalty = (user.penaltyBalanceOwed ?? 0) > 0;

  if (banned) {
    return (
      <Banner tone="danger">
        Your listing privileges have been permanently suspended.{" "}
        Contact support to request a review.
      </Banner>
    );
  }
  if (isTimedSuspended && owesPenalty) {
    return (
      <Banner tone="warning">
        Listing suspended until {formatDate(suspendedUntil)}. You also owe{" "}
        <strong>L${user.penaltyBalanceOwed?.toLocaleString()}</strong>. Visit any
        SLPA terminal to pay.
      </Banner>
    );
  }
  if (isTimedSuspended) {
    return (
      <Banner tone="warning">
        Listing suspended until {formatDate(suspendedUntil)}.
      </Banner>
    );
  }
  if (owesPenalty) {
    return (
      <Banner tone="warning">
        You owe <strong>L${user.penaltyBalanceOwed?.toLocaleString()}</strong> in
        cancellation penalties. Visit any SLPA terminal to pay and resume listing.
      </Banner>
    );
  }
  return null;
}
```

(Adjust `<Banner>` to whatever the project's actual primitive is. If none exists, use a `Card` + design tokens.)

Tests cover all 4 states + null state + dark/light token output.

- [ ] **Step 6: `CancellationHistorySection`**

Renders a paginated list using `useCancellationHistory`. Each row uses `<ListingCard variant="compact">` (or equivalent) on the left + `<CancellationConsequenceBadge>` on the right + click-to-expand for the reason. Empty state: "No cancellations yet."

Tests cover empty state, pagination clicks, badge rendering for each kind, reason expand/collapse.

- [ ] **Step 7: Modify `CancelListingModal.tsx` for consequence-aware copy**

Add the status fetch + dynamic copy table (top-down precedence):

```tsx
const { data: status } = useCancellationStatus();
const { data: user } = useCurrentUser();
const auction = props.auction;

// Compute copy variant — top-down precedence
const copyVariant = (() => {
  if (user?.bannedFromListing) return "BANNED";
  const hasBids = (auction.bidCount ?? 0) > 0;
  if (!hasBids) return "NO_BIDS";
  const prior = status?.priorOffensesWithBids ?? 0;
  if (prior === 0) return "FIRST_OFFENSE";
  if (prior === 1) return "SECOND_OFFENSE";
  if (prior === 2) return "THIRD_OFFENSE";
  return "FOURTH_PLUS_OFFENSE";  // unbanned but on offense #4 — about to ban
})();

const copy = COPY_MAP[copyVariant];  // map to the spec §8.1 table
```

The `COPY_MAP` constant inlines the strings from spec §8.1. Confirmation button stays `variant="destructive"`; reason textarea stays free-text required.

Tests cover all 6 variants — set up the test environment with the right user state + auction state and assert the rendered copy matches.

- [ ] **Step 8: Extend `types/auction.ts` with `AuctionCancelledEnvelope`**

```typescript
export type AuctionCancelledEnvelope = {
  type: "AUCTION_CANCELLED";
  auctionId: number;
  cancelledAt: string;
  hadBids: boolean;
};

export type AuctionTopicEnvelope =
  | AuctionEnvelope
  | EscrowEnvelope
  | ReviewRevealedEnvelope
  | AuctionCancelledEnvelope;
```

- [ ] **Step 9: Handle `AUCTION_CANCELLED` in `AuctionDetailClient`**

Find the existing WS envelope switch in `AuctionDetailClient.tsx`. The Sub-spec 1 fix already added a `REVIEW_REVEALED` branch and narrowed the union for type safety. Add:

```tsx
} else if (env.type === "AUCTION_CANCELLED") {
  // Invalidate the auction query so the page transitions to "cancelled" state
  queryClient.invalidateQueries({ queryKey: ["auction", auctionId] });
}
```

The existing render path checks `auction.status === "CANCELLED"` (or should) and renders a cancellation banner instead of the bid form. If that branch doesn't exist yet in `AuctionDetailClient`, add it as a small UI tweak — see how cancelled auctions are shown in the My Listings flow (Epic 03 sub-spec 2) for the existing pattern.

Test: simulate the WS envelope, assert query invalidation fires.

- [ ] **Step 10: Extend `/me` types**

Find `lib/user.ts` (or wherever the `CurrentUserResponse` mirror is). Add 3 fields:

```typescript
export interface CurrentUser {
  // ... existing
  penaltyBalanceOwed: number;
  listingSuspensionUntil: string | null;
  bannedFromListing: boolean;
}
```

Update `useCurrentUser` to expect these. Existing tests + MSW handlers may need to provide defaults — inject `{ penaltyBalanceOwed: 0, listingSuspensionUntil: null, bannedFromListing: false }` into existing fixtures.

- [ ] **Step 11: Mount banner + history on dashboard**

Edit `app/dashboard/(verified)/overview/page.tsx`:

```tsx
<>
  <SuspensionBanner />
  <PendingReviewsSection />
  {/* existing My Listings / My Bids sections */}
  <CancellationHistorySection />
</>
```

Place `SuspensionBanner` at the top (highest priority), `PendingReviewsSection` (from Sub-spec 1) next, then existing sections, then `CancellationHistorySection` near the bottom (informational, lowest priority).

- [ ] **Step 12: Listing wizard — handle 403 with structured `code`**

Find the wizard's submit handler (likely `app/listings/new/`-something or `components/listing/ListingWizardForm.tsx`'s onSubmit). Catch 403 errors:

```tsx
try {
  await submitListing(data);
} catch (e) {
  if (isApiError(e) && e.status === 403 && e.problem?.code) {
    const code = e.problem.code as SuspensionReasonCode;
    // Show focused modal/error with the right copy per code
    // Link to dashboard banner
    setSuspensionError(code);
    return;
  }
  throw e;
}
```

The error display can be a small `<SuspensionErrorModal>` or inline error block with copy keyed on `code`:
- `PENALTY_OWED`: "You have an outstanding penalty balance. Pay at any SLPA terminal to resume listing. ([Go to dashboard])"
- `TIMED_SUSPENSION`: "Your listing privileges are temporarily suspended. ([Go to dashboard])"
- `PERMANENT_BAN`: "Your listing privileges have been permanently suspended. Contact support to request a review."

Test: mock the API to return 403 with each `code`, assert the right copy renders.

- [ ] **Step 13: Run all frontend tests**

```bash
cd frontend && npm test -- --run
```

All pass. ~40 new tests across components + hooks expected.

- [ ] **Step 14: Run lint + build**

```bash
cd frontend && npm run lint
cd frontend && npm run build
```

Both pass. The Sub-spec 1 build was green at PR merge; Sub-spec 2 should not regress.

- [ ] **Step 15: Commit Task 4**

```bash
git add frontend/src/types/cancellation.ts \
        frontend/src/types/auction.ts \
        frontend/src/lib/api/cancellations.ts \
        frontend/src/hooks/useCancellationStatus.ts \
        frontend/src/hooks/useCancellationHistory.ts \
        frontend/src/components/cancellation/ \
        frontend/src/components/dashboard/SuspensionBanner.tsx \
        frontend/src/components/dashboard/SuspensionBanner.test.tsx \
        frontend/src/components/dashboard/CancellationHistorySection.tsx \
        frontend/src/components/dashboard/CancellationHistorySection.test.tsx \
        frontend/src/components/listing/CancelListingModal.tsx \
        frontend/src/components/listing/CancelListingModal.test.tsx \
        frontend/src/app/auction/[id]/AuctionDetailClient.tsx \
        frontend/src/app/dashboard/\(verified\)/overview/page.tsx \
        frontend/src/lib/user.ts
git commit -m "feat(cancellation): suspension banner + history section + consequence-aware modal + AUCTION_CANCELLED handler"
```

---

## Final steps — DEFERRED_WORK + PR

- [ ] **Resolve deferred ledger entry**

Edit `docs/implementation/DEFERRED_WORK.md`. Find and DELETE the entry titled "Cancellation WS broadcast on active-auction cancel" (around line 155 per spec §12). Sub-spec 2 ships this.

```bash
git add docs/implementation/DEFERRED_WORK.md
git commit -m "docs(deferred): remove WS-broadcast-on-cancel — shipped in sub-spec 2"
```

- [ ] **Cross-branch final review**

Dispatch a final `code-reviewer` subagent at this branch with full context. Inputs:
- Spec: `docs/superpowers/specs/2026-04-24-epic-08-sub-2-cancellation-penalties.md`
- Plan: this file
- Branch: full diff against `dev`

Verify cross-task coherence (DTO shapes match between backend and frontend, enum values consistent, copy strings match spec §8.1, security rules cover new endpoints, no leaked admin endpoints).

- [ ] **Open PR to `dev`**

```bash
gh pr create --base dev --title "Epic 08 sub-spec 2 — Cancellation Penalties" --body "..."
```

Body should follow Sub-spec 1's PR shape — summary of what shipped per task, DEFERRED_WORK changes, test plan checkbox.

---

## Self-review (plan)

1. **Spec coverage:**
   - §2 ladder → Task 1 Steps 9-11
   - §4 data model → Task 1 Steps 1-6
   - §5 cancel flow → Task 1 Step 11
   - §6 watcher → Task 2 Steps 13-16
   - §7 API surface → Task 2 (gate + GET endpoints) + Task 3 (terminal endpoints)
   - §8 frontend → Task 4
   - §12 deferred-work changes → Final steps

2. **Placeholder scan:** No TBD/TODO. Code snippets are concrete; one minor "(adjust to match)" note for the SecurityConfig terminal-auth role pattern, which is acceptable since the existing pattern is the source of truth.

3. **Type consistency:**
   - `CancellationOffenseKind` values match across Java enum and TS union (NONE, WARNING, PENALTY, PENALTY_AND_30D, PERMANENT_BAN)
   - `SuspensionReason` Java enum matches `SuspensionReasonCode` TS union (PENALTY_OWED, TIMED_SUSPENSION, PERMANENT_BAN)
   - `AuctionCancelledEnvelope` field names match between Java record and TS type
   - DTO shapes match: `CancellationStatusResponse`, `CancellationHistoryDto`, `PenaltyLookupResponse`, `PenaltyPaymentResponse`

4. **Idempotency pins:**
   - `ReviewService.reveal` from sub-spec 1 — already idempotent
   - `CancellationService.cancel` — pessimistic lock prevents double-application; permanent ban write is idempotent (true → true)
   - `PenaltyTerminalService.pay` — `slTransactionId` lookup before debit catches replay
   - `OwnershipCheckTask` for cancelled auctions — clears `postCancelWatchUntil` after raising flag, preventing re-flag

5. **Race pins:**
   - Concurrent cancellations same seller → User row pessimistic lock (Step 17)
   - Concurrent payments at two terminals → User row pessimistic lock + idempotent slTransactionId (Step 8)
   - Reveal scheduler vs cancellation race → not applicable (different resources)
