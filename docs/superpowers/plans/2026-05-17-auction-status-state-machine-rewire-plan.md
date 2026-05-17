# Auction Status State Machine Rewire — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** [`docs/superpowers/specs/2026-05-17-auction-status-state-machine-rewire-design.md`](../specs/2026-05-17-auction-status-state-machine-rewire-design.md)

**Goal:** Wire the `AuctionStatus` enum to mirror the real listing lifecycle so parcel-lock semantics fall out of status alone. Drop `ENDED`, `ESCROW_PENDING`, `ESCROW_FUNDED`; add `FROZEN`; thread accurate status transitions through every close, escrow, dispute, and admin-cancel path.

**Architecture:** Enum + constants change ships first as the foundation. The Flyway migration translates existing prod rows in one pass. Service-layer transitions are wired one path at a time (close → escrow lifecycle → dispute → admin-cancel-from-escrow), each one atomic with its escrow-side counterpart. DTO + frontend type updates trail the backend. Tests update in lockstep with the code they cover.

**Tech Stack:** Java 26 / Spring Boot 4 / JPA, Flyway, Postgres, JUnit 5 / Mockito / AssertJ, Vitest (frontend types only).

---

## Task ordering

Tasks are mostly independent but share the `AuctionStatus` enum + `LOCKING_STATUSES`, so Task 1 must land first. Tasks 2–10 can be implemented in parallel by separate subagents; Task 11 (DTO mapper + admin filter sweep) and Task 12 (frontend types) depend on the enum landing. Task 13 (final integration sweep + cleanup) runs last.

---

### Task 1: Enum, locking constants, index DDL

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatus.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatusConstants.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/config/ParcelLockingIndexInitializer.java`

- [ ] **Step 1: Update `AuctionStatus` enum**

Remove `ENDED`, `ESCROW_PENDING`, `ESCROW_FUNDED`. Add `FROZEN`. Update the class-level javadoc that enumerates terminal "why-it-ended" states to read: `COMPLETED, CANCELLED, EXPIRED, FROZEN, DISPUTED collapse to ENDED in PublicAuctionStatus`.

```java
public enum AuctionStatus {
    DRAFT,
    DRAFT_PAID,
    VERIFICATION_PENDING,
    VERIFICATION_FAILED,
    ACTIVE,
    TRANSFER_PENDING,
    DISPUTED,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    FROZEN,
    // intentionally not in LOCKING_STATUSES — suspension releases the parcel for re-listing.
    SUSPENDED
}
```

- [ ] **Step 2: Update `AuctionStatusConstants.LOCKING_STATUSES`**

```java
public static final Set<AuctionStatus> LOCKING_STATUSES = Set.of(
        AuctionStatus.ACTIVE,
        AuctionStatus.TRANSFER_PENDING,
        AuctionStatus.DISPUTED);
```

Update the javadoc to reflect that the set now consists entirely of statuses that are actually transitioned into by code (no more defensive entries).

- [ ] **Step 3: Update `ParcelLockingIndexInitializer` DDL**

```java
private static final String DDL = """
        CREATE UNIQUE INDEX IF NOT EXISTS uq_auctions_parcel_locked_status
          ON auctions(sl_parcel_uuid)
          WHERE status IN ('ACTIVE', 'TRANSFER_PENDING', 'DISPUTED')
        """;
```

The existing `DROP_OLD` + `CREATE` idempotent pattern handles the rebuild on next boot. Update the inline comment to match the spec rationale.

- [ ] **Step 4: Verify the project still compiles**

Run: `cd backend && ./mvnw -q compile`
Expected: success. References to `AuctionStatus.ENDED` etc. will surface as compile errors — that's expected and the subsequent tasks fix them.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatus.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatusConstants.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/config/ParcelLockingIndexInitializer.java
git commit -m "refactor(auction): drop ENDED/ESCROW_PENDING/ESCROW_FUNDED, add FROZEN to status enum"
```

**Note on compile errors:** After Task 1 the project will not compile until Tasks 2–10 land. That's by design — every reference to a dropped status must be rewritten with the correct replacement based on context. Each subsequent task picks a related cluster.

---

### Task 2: Flyway migration translating historical rows

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__rewire_auction_status.sql`

- [ ] **Step 1: Write the migration**

Translation rules per spec §7.7:

```sql
-- V36__rewire_auction_status.sql
-- Translates rows from the pre-rewire AuctionStatus enum (which sat every
-- post-close auction at ENDED) to the post-rewire enum (which reflects the
-- escrow phase directly).

BEGIN;

-- ENDED + COMPLETED escrow → COMPLETED
UPDATE auctions a
SET status = 'COMPLETED'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state = 'COMPLETED';

-- ENDED + TRANSFER_PENDING / FUNDED / ESCROW_PENDING escrow → TRANSFER_PENDING
-- (FUNDED and ESCROW_PENDING are transient escrow stops post wallet-only-
-- escrow; if any persisted for legacy reasons we treat them as mid-flight.)
UPDATE auctions a
SET status = 'TRANSFER_PENDING'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state IN ('TRANSFER_PENDING', 'FUNDED', 'ESCROW_PENDING');

-- ENDED + DISPUTED escrow → DISPUTED
UPDATE auctions a
SET status = 'DISPUTED'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state = 'DISPUTED';

-- ENDED + FROZEN escrow → FROZEN
UPDATE auctions a
SET status = 'FROZEN'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state = 'FROZEN';

-- ENDED + EXPIRED escrow → EXPIRED
UPDATE auctions a
SET status = 'EXPIRED'
FROM escrows e
WHERE a.id = e.auction_id
  AND a.status = 'ENDED'
  AND e.state = 'EXPIRED';

-- ENDED + no escrow row → EXPIRED (NO_BIDS / RESERVE_NOT_MET outcomes)
UPDATE auctions
SET status = 'EXPIRED'
WHERE status = 'ENDED'
  AND id NOT IN (SELECT auction_id FROM escrows WHERE auction_id IS NOT NULL);

-- Defensive: nothing should sit at ESCROW_PENDING / ESCROW_FUNDED today
-- (no code path sets them), but if a stale row exists it maps to the same
-- mid-flight state.
UPDATE auctions
SET status = 'TRANSFER_PENDING'
WHERE status IN ('ESCROW_PENDING', 'ESCROW_FUNDED');

COMMIT;
```

- [ ] **Step 2: Stage on dev DB before merging**

The dev Postgres bind-mount accepts migrations on backend restart. Run:
```bash
docker compose restart backend
```
Tail the backend logs to confirm `V36__rewire_auction_status.sql` applied without errors. Then `docker compose exec postgres psql -U slpa -d slpa -c "SELECT status, COUNT(*) FROM auctions GROUP BY status"` to spot-check the row counts.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V36__rewire_auction_status.sql
git commit -m "migration(V36): translate auction.status rows post-state-machine-rewire"
```

---

### Task 3: Auction-close paths set EXPIRED for no-sale outcomes

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidPlacementHelpers.java`

Per spec §9 Q1 sign-off, `EscrowService.createForEndedAuction` owns the `ACTIVE → TRANSFER_PENDING` flip for SOLD/BOUGHT_NOW outcomes. The close paths only own the `ACTIVE → EXPIRED` transition for NO_BIDS / RESERVE_NOT_MET (no escrow opens, no escrow service involvement).

- [ ] **Step 1: Update `AuctionEndTask.closeOne` outcome routing**

Where the current code does `auction.setStatus(ENDED)`, replace with conditional routing:

```java
auction.setEndOutcome(outcome);
if (outcome == AuctionEndOutcome.SOLD || outcome == AuctionEndOutcome.BOUGHT_NOW) {
    // Status flip to TRANSFER_PENDING is owned by EscrowService.createForEndedAuction
    // below — leave the auction at ACTIVE briefly inside this transaction.
} else {
    // NO_BIDS / RESERVE_NOT_MET: no escrow opens, terminal failure.
    auction.setStatus(AuctionStatus.EXPIRED);
}
```

For SOLD outcomes the existing call `escrowService.createForEndedAuction(auction, now)` runs next; Task 4 flips the status there.

- [ ] **Step 2: Update `BidPlacementHelpers` buy-now close path**

Same pattern: drop `setStatus(ENDED)`. Buy-now is always `BOUGHT_NOW` outcome, so the helper hands off to `EscrowService.createForEndedAuction` which owns the flip. Verify by reading the call site in `BidService.acceptBid`.

- [ ] **Step 3: Run touched tests**

```bash
cd backend && ./mvnw -Dtest='AuctionEndIntegrationTest,BidServiceBuyNowTest,BidServiceSnipeTest' test
```

Tests likely fail at assertions checking `status=ENDED` — those are updated in their own tasks (Tasks 7–9). Skip past assertion failures for now; what matters is no compile errors and no transition logic regressions.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/BidPlacementHelpers.java
git commit -m "refactor(auction): NO_BIDS/RESERVE_NOT_MET closes go to EXPIRED; SOLD/BOUGHT_NOW hands off to escrow"
```

---

### Task 4: EscrowService owns ACTIVE → TRANSFER_PENDING

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`

- [ ] **Step 1: Add the status flip inside `createForEndedAuction`**

Find the point in `createForEndedAuction` where escrow has been auto-funded and transitioned to `TRANSFER_PENDING` (just before the post-transition save). Add:

```java
// Auction status mirrors escrow phase: ACTIVE → TRANSFER_PENDING when
// the close transaction opens an escrow row. The auction param is already
// the locked row from the caller's transaction; this flip commits with
// the rest of the close.
auction.setStatus(AuctionStatus.TRANSFER_PENDING);
auctionRepo.save(auction);
```

Confirm `auctionRepo` is already injected — it is (`BidService` and others use it). If not, inject via the constructor.

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java
git commit -m "refactor(escrow): createForEndedAuction owns ACTIVE -> TRANSFER_PENDING flip"
```

---

### Task 5: EscrowService.confirmTransfer flips auction to COMPLETED

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`

- [ ] **Step 1: Add status flip inside `confirmTransfer`**

After the escrow row transitions to `COMPLETED` (or whichever terminal state confirmTransfer lands at — check the current code), add:

```java
escrow.getAuction().setStatus(AuctionStatus.COMPLETED);
auctionRepo.save(escrow.getAuction());
```

- [ ] **Step 2: Run targeted test**

```bash
./mvnw -Dtest='EscrowEndToEndIntegrationTest,EscrowOwnershipMonitorIntegrationTest' test
```

Expected: assertions on `auction.status` fail until those tests are updated in Task 12. Compile + logic must pass.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java
git commit -m "refactor(escrow): confirmTransfer flips auction to COMPLETED"
```

---

### Task 6: EscrowService.freezeForFraud flips auction to FROZEN

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`

- [ ] **Step 1: Add status flip inside `freezeForFraud`**

After the escrow row transitions to `FROZEN`, add:

```java
escrow.getAuction().setStatus(AuctionStatus.FROZEN);
auctionRepo.save(escrow.getAuction());
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java
git commit -m "refactor(escrow): freezeForFraud flips auction to FROZEN"
```

---

### Task 7: EscrowService.expireTransfer flips auction to EXPIRED

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java` (or `EscrowTimeoutTask` — wherever the transfer-timeout path lives)

- [ ] **Step 1: Locate the transfer-timeout path**

```bash
grep -rn "expireTransfer\|TRANSFER.*EXPIRED" backend/src/main/java
```

- [ ] **Step 2: Add auction status flip at the transfer-timeout site**

After the escrow row transitions to `EXPIRED`:

```java
escrow.getAuction().setStatus(AuctionStatus.EXPIRED);
auctionRepo.save(escrow.getAuction());
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/...
git commit -m "refactor(escrow): transfer-timeout flips auction to EXPIRED"
```

---

### Task 8: EscrowService.fileDispute flips auction to DISPUTED + resolution paths

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/disputes/AdminDisputeService.java`

- [ ] **Step 1: `fileDispute` flips auction to DISPUTED**

In `EscrowService.fileDispute`, after the escrow row transitions to `DISPUTED`:

```java
escrow.getAuction().setStatus(AuctionStatus.DISPUTED);
auctionRepo.save(escrow.getAuction());
```

- [ ] **Step 2: `AdminDisputeService.resolve` routes auction status by resolution**

In the resolution path (where the escrow lands at `COMPLETED` / `EXPIRED` depending on the admin's decision), match the escrow transition:

```java
escrow.getAuction().setStatus(
    resolution == DisputeResolution.IN_FAVOR_OF_BUYER
        ? AuctionStatus.COMPLETED
        : AuctionStatus.EXPIRED);
auctionRepo.save(escrow.getAuction());
```

(The `alsoCancelListing=true` branch already routes through `cancellationService.cancelByDisputeResolve` which sets `CANCELLED` — no change needed there.)

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/disputes/AdminDisputeService.java
git commit -m "refactor(escrow,disputes): dispute lifecycle mirrors auction status"
```

---

### Task 9: New admin-cancel-from-escrow path

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/FreezeReason.java` (add `ADMIN_CANCEL`) — OR a new `EscrowExpireReason` if `FreezeReason` isn't reused for EXPIRED. Inspect first.

- [ ] **Step 1: Add the cancel-from-escrow reason**

```bash
grep -rn "enum FreezeReason\|enum.*ExpireReason" backend/src/main/java
```

Pick the right enum (likely `FreezeReason` even though the escrow lands at EXPIRED, since the freeze/expire reasons share a column in many designs — verify by reading the `Escrow` entity). Add `ADMIN_CANCEL`.

- [ ] **Step 2: Add `cancelByAdminFromEscrow` on `CancellationService`**

```java
/**
 * Admin cancels a listing that's already in escrow (status =
 * TRANSFER_PENDING). Refunds the winner, marks the escrow EXPIRED with
 * reason ADMIN_CANCEL, and flips the auction to CANCELLED. The seller's
 * penalty ladder is NOT touched — staff action.
 */
@Transactional
public Auction cancelByAdminFromEscrow(Long auctionId, Long adminUserId, String notes) {
    Auction a = auctionRepo.findByIdForUpdate(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    if (a.getStatus() != AuctionStatus.TRANSFER_PENDING) {
        throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "ADMIN_CANCEL_FROM_ESCROW");
    }
    Escrow escrow = escrowRepo.findByAuctionIdForUpdate(a.getId())
            .orElseThrow(() -> new IllegalStateException(
                "Auction " + a.getId() + " in TRANSFER_PENDING has no escrow row"));
    if (escrow.getState() != EscrowState.TRANSFER_PENDING) {
        throw new IllegalStateException(
            "Escrow " + escrow.getId() + " not in TRANSFER_PENDING (actual=" + escrow.getState() + ")");
    }

    OffsetDateTime now = OffsetDateTime.now(clock);

    // Refund winner.
    escrowService.queueRefundIfFunded(escrow);

    // Escrow → EXPIRED with reason ADMIN_CANCEL.
    escrow.setState(EscrowState.EXPIRED);
    escrow.setExpiredAt(now);
    escrow.setFreezeReason(FreezeReason.ADMIN_CANCEL.name());
    escrowRepo.save(escrow);

    // CancellationLog with cancelledByAdminId set.
    logRepo.save(CancellationLog.builder()
            .auction(a)
            .seller(a.getSeller())
            .cancelledFromStatus(AuctionStatus.TRANSFER_PENDING.name())
            .hadBids(true)
            .reason(notes)
            .penaltyKind(CancellationOffenseKind.NONE)
            .penaltyAmountL(null)
            .cancelledByAdminId(adminUserId)
            .build());

    a.setStatus(AuctionStatus.CANCELLED);
    Auction saved = auctionRepo.save(a);

    // Notify seller (existing) + winner (new category).
    notificationPublisher.listingRemovedByAdmin(
            a.getSeller().getId(), a.getId(), a.getTitle(), notes);
    notificationPublisher.listingCancelledDuringEscrow(
            escrow.getAuction().getWinnerUserId(),
            a.getId(), escrow.getId(), a.getTitle(), notes);

    // Broadcast AUCTION_CANCELLED on the STOMP topic so the auction-detail
    // page transitions for any subscribed viewer.
    AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(saved, true, now);
    TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() {
                    broadcastPublisher.publishCancelled(envelope);
                }
            });

    log.info("Auction {} admin-cancelled from TRANSFER_PENDING by adminUserId={} (escrow {} refunded, marked EXPIRED/ADMIN_CANCEL)",
            a.getId(), adminUserId, escrow.getId());

    return saved;
}
```

Inject `EscrowRepository` + `EscrowService` if not already present (check the existing constructor).

- [ ] **Step 3: Add winner-notification path**

Wire up `NotificationPublisher.listingCancelledDuringEscrow(winnerUserId, auctionId, escrowId, parcelName, adminNotes)` + a new category `LISTING_CANCELLED_DURING_ESCROW` in `NotificationCategory`. Mirror the existing `escrowFunded` / `listingRemovedByAdmin` patterns for title/body composition and link resolution. Title: `"Listing cancelled during escrow: {parcelName}"`. Body: `"Admin cancelled this listing. Your escrow has been refunded to your SLParcels wallet."`.

- [ ] **Step 4: Route from `AdminListingService.cancel`**

```java
@Transactional
public void cancel(UUID publicId, Long adminUserId, String notes) {
    Auction auction = resolveOrThrow(publicId);
    Long auctionId = auction.getId();

    try {
        if (auction.getStatus() == AuctionStatus.TRANSFER_PENDING) {
            cancellationService.cancelByAdminFromEscrow(auctionId, adminUserId, notes);
        } else {
            cancellationService.cancelByAdmin(auctionId, adminUserId, notes);
        }
    } catch (AuctionNotFoundException e) {
        throw new AdminListingStateException("LISTING_NOT_FOUND", e.getMessage());
    } catch (InvalidAuctionStateException e) {
        throw new AdminListingStateException(
            "INVALID_STATUS_FOR_ACTION",
            "Cannot cancel listing in status " + auction.getStatus());
    }

    adminActionService.record(
        adminUserId,
        AdminActionType.CANCEL_LISTING_FROM_REPORT,
        AdminActionTargetType.LISTING,
        auctionId,
        notes,
        SOURCE_METADATA
    );
}
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingService.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/FreezeReason.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java
git commit -m "feat(admin): cancel listing from TRANSFER_PENDING refunds escrow and notifies winner"
```

---

### Task 10: AssertParcelNotLocked collapses to single check

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java`

- [ ] **Step 1: Replace the two-pass check with the original single `existsBy...` call**

```java
private void assertParcelNotLocked(Auction candidate) {
    UUID slParcelUuid = candidate.getSlParcelUuid();
    boolean exists = auctionRepo.existsBySlParcelUuidAndStatusInAndIdNot(
            slParcelUuid, AuctionStatusConstants.LOCKING_STATUSES, candidate.getId());
    if (!exists) return;

    Long blockingId = auctionRepo
            .findFirstBySlParcelUuidAndStatusIn(slParcelUuid, AuctionStatusConstants.LOCKING_STATUSES)
            .map(Auction::getId)
            .orElse(-1L);
    throw new ParcelAlreadyListedException(candidate.getId(), blockingId);
}
```

No escrow join. The status enum itself now encodes the lock decision.

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java
git commit -m "refactor(auction): collapse parcel-lock check now that status reflects escrow phase"
```

---

### Task 11: DTO mapper + admin filter sweep

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionStatus.java` (if needed)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingService.java` (filter / sort references)
- Modify: anywhere else that switches on `AuctionStatus.ENDED`

- [ ] **Step 1: Update `AuctionDtoMapper.toPublicStatus`**

```java
private PublicAuctionStatus toPublicStatus(AuctionStatus internal) {
    return switch (internal) {
        case ACTIVE -> PublicAuctionStatus.ACTIVE;
        case TRANSFER_PENDING, DISPUTED,
             COMPLETED, EXPIRED, FROZEN, CANCELLED -> PublicAuctionStatus.ENDED;
        default -> throw new IllegalStateException(
            "Non-public status leaked to toPublicStatus: " + internal
                + ". The controller should have 404'd before calling the mapper.");
    };
}
```

- [ ] **Step 2: Sweep all references to `AuctionStatus.ENDED`**

```bash
grep -rn "AuctionStatus\.ENDED\|AuctionStatus\.ESCROW_PENDING\|AuctionStatus\.ESCROW_FUNDED" backend/src/main backend/src/test
```

Update every occurrence based on context:
- Filter / sort lists: replace `ENDED` with the new terminal set
  (`COMPLETED, EXPIRED, FROZEN, CANCELLED`) or with the in-flight set
  (`TRANSFER_PENDING, DISPUTED`), whichever the call site means.
- Comments: rewrite to match the new model.

- [ ] **Step 3: Verify backend compiles**

```bash
cd backend && ./mvnw -q test-compile
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java
# + every file the sweep touched
git commit -m "refactor(dto,admin): map new statuses and replace ENDED references"
```

---

### Task 12: Test sweep

**Files:**
- Modify: tests across `backend/src/test/java/**`

- [ ] **Step 1: Find every test assertion on the dropped statuses**

```bash
grep -rn "AuctionStatus\.ENDED\|AuctionStatus\.ESCROW_PENDING\|AuctionStatus\.ESCROW_FUNDED\|\"ENDED\"" backend/src/test
```

- [ ] **Step 2: Update each assertion**

For each test, decide the new expected status based on what the production code now sets in that scenario. Common patterns:

- BOUGHT_NOW / SOLD close + escrow auto-fund → `TRANSFER_PENDING`
- NO_BIDS / RESERVE_NOT_MET close → `EXPIRED`
- confirmTransfer → `COMPLETED`
- freezeForFraud → `FROZEN`
- transfer-timeout → `EXPIRED`
- fileDispute → `DISPUTED`
- admin-cancel-from-escrow → `CANCELLED`

- [ ] **Step 3: Add new admin-cancel-from-escrow tests**

Cover:
- Cancelling a TRANSFER_PENDING listing refunds the winner, marks the escrow `EXPIRED/ADMIN_CANCEL`, and flips the auction to `CANCELLED`.
- Cancelling a non-TRANSFER_PENDING listing through this path rejects with `INVALID_STATUS_FOR_ACTION`.
- Winner receives a `LISTING_CANCELLED_DURING_ESCROW` notification; seller receives `LISTING_REMOVED_BY_ADMIN`.

- [ ] **Step 4: Add/update parcel-lock tests**

In `ParcelLockingRaceIntegrationTest`:
- Drop the PR #319 stopgap tests (no longer needed).
- Add: `TRANSFER_PENDING` auction locks the parcel.
- Add: `FROZEN`/`EXPIRED`/`COMPLETED`/`CANCELLED`/`SUSPENDED` auctions don't lock the parcel.

- [ ] **Step 5: Run full backend test suite**

```bash
cd backend && ./mvnw test
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test
git commit -m "test: update status assertions for state-machine rewire; add admin-cancel-from-escrow coverage"
```

---

### Task 13: Frontend types + final integration sweep

**Files:**
- Modify: `frontend/src/types/auction.ts`
- Modify: any frontend file matching `status === "ENDED"` for seller-context UI

- [ ] **Step 1: Update `AuctionStatus` mirror in `frontend/src/types/auction.ts`**

```ts
export type AuctionStatus =
  | "DRAFT"
  | "DRAFT_PAID"
  | "VERIFICATION_PENDING"
  | "VERIFICATION_FAILED"
  | "ACTIVE"
  | "TRANSFER_PENDING"
  | "DISPUTED"
  | "COMPLETED"
  | "CANCELLED"
  | "EXPIRED"
  | "FROZEN"
  | "SUSPENDED";
```

`PublicAuctionStatus` stays `"ACTIVE" | "ENDED"` — unchanged.

- [ ] **Step 2: Sweep seller-context UI**

```bash
grep -rn 'status === "ENDED"\|status: "ENDED"' frontend/src
```

Decide per-file whether the comparison should target the public collapse (`PublicAuctionStatus.ENDED`) or one of the new internal terminals (`COMPLETED`, `EXPIRED`, `FROZEN`, `CANCELLED`). Public components (browse, anonymous viewers) should continue to read `"ENDED"` from `PublicAuctionResponse.status`. Seller dashboard / listing-detail components that already consume `SellerAuctionResponse.status` need to switch on the new terminal set.

- [ ] **Step 3: Run frontend typecheck + tests**

```bash
cd frontend && npx tsc --noEmit && npx vitest run
```

Expected: all green.

- [ ] **Step 4: Commit + push**

```bash
git add frontend/src/types/auction.ts
# + anything the sweep touched
git commit -m "refactor(frontend): mirror backend status enum changes"
git push
```

---

### Task 14: Open PR into `dev` and final smoke

- [ ] **Step 1: Open PR**

```bash
gh pr create --base dev --head feat/auction-status-state-machine-rewire \
    --title "feat(auction): rewire status state machine to mirror escrow lifecycle" \
    --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-05-17-auction-status-state-machine-rewire-design.md.

Drops ENDED / ESCROW_PENDING / ESCROW_FUNDED from AuctionStatus; adds FROZEN.
Threads accurate status transitions through close, escrow lifecycle,
dispute, and admin-cancel paths so parcel-lock semantics fall out of
status alone. Includes a Flyway migration translating historical rows.

Adds admin-cancel-from-escrow (TRANSFER_PENDING) with full refund-and-CANCEL
flow + dedicated LISTING_CANCELLED_DURING_ESCROW winner notification.

See spec for full state machine, sign-off decisions, and locking semantics.

## Test plan
- [x] Backend tests green
- [x] Frontend typecheck + vitest green
- [ ] Post-deploy smoke: re-list the previously-stuck parcel (auction 20)
EOF
)"
```

- [ ] **Step 2: Wait for user review**

The user reviews + merges dev → main themselves per `feedback_no_merge_to_main`.

---

## Self-review checklist

After every task completes, before declaring done:

1. **Spec coverage:** Every section of the spec mapped to a task? `LOCKING_STATUSES` change → Task 1. Migration → Task 2. Close paths → Task 3. Escrow lifecycle → Tasks 4–8. Admin-cancel-from-escrow → Task 9. Parcel-lock check collapse → Task 10. DTO + admin → Task 11. Tests → Task 12. Frontend → Task 13. PR → Task 14.
2. **Type consistency:** Every reference to `AuctionStatus.ENDED` / `ESCROW_PENDING` / `ESCROW_FUNDED` removed?
3. **Migration determinism:** All `ENDED` + escrow-state pairs covered by the migration; no row left at the dropped statuses?
4. **No dead code:** `ENDED` reference removed from `AuctionStatus`, `LOCKING_STATUSES`, the index DDL, the public-status mapper, every test, every comment.
5. **Frontend mirror:** Type definitions match the backend enum.

If any check fails, add a task or extend an existing one. Don't ship with a known gap.
