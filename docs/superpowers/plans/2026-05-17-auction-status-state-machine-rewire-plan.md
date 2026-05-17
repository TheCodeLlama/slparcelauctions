# Auction Status State Machine Rewire — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** [`docs/superpowers/specs/2026-05-17-auction-status-state-machine-rewire-design.md`](../specs/2026-05-17-auction-status-state-machine-rewire-design.md)

**Goal:** Wire the `AuctionStatus` enum to mirror the real listing lifecycle so parcel-lock semantics fall out of status alone. Drop `ENDED`, `ESCROW_PENDING`, `ESCROW_FUNDED`; add `FROZEN`; thread accurate status transitions through every close, escrow, dispute, and admin-cancel path.

**Architecture:** Enum + constants + index DDL ship first. The Flyway migration translates existing prod rows in one pass. Service-layer transitions are wired path-by-path (close → escrow lifecycle → dispute → admin-cancel-from-escrow). DTO + admin filter sweep + frontend types follow. Tests update in lockstep.

**Tech Stack:** Java 26 / Spring Boot 4 / JPA, Flyway, Postgres, JUnit 5 / Mockito / AssertJ, Vitest (frontend types only).

## Notes for subagents executing this plan

- **`NotificationCategory.ESCROW_FUNDED` ≠ `AuctionStatus.ESCROW_FUNDED`.** The notification category is the "buyer funded escrow" notification (used on the natural-end SOLD path). It stays. Only the **auction-status** enum value `ESCROW_FUNDED` is being dropped. Don't touch the notification category.
- **Compile-broken window.** After Task 1 lands, the project does NOT compile until Task 11 finishes. Each task between 2 and 11 fixes a slice of the compile breakage. Do not skip ahead — execute in order.
- **`AuctionStatus.ENDED` lives in ~40 production + test files.** Task 11 is the explicit sweep; tasks before that fix only the files within their scope.
- The `escrow.getAuction()` reference inside `EscrowService` returns a managed entity from the same persistence context, so `auctionRepo.save(...)` is the explicit way to flush the status change. Don't rely on Hibernate dirty checking — be explicit.

---

### Task 1: Enum, locking constants, index DDL

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatus.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatusConstants.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/config/ParcelLockingIndexInitializer.java`

- [ ] **Step 1: Update `AuctionStatus` enum**

Remove `ENDED`, `ESCROW_PENDING`, `ESCROW_FUNDED`. Add `FROZEN`. Update the class-level javadoc that enumerates terminal "why-it-ended" states to read:
`COMPLETED, CANCELLED, EXPIRED, FROZEN, DISPUTED collapse to ENDED in PublicAuctionStatus`.

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

Update the javadoc to reflect that the set now consists entirely of statuses code actually transitions into (no more defensive dead values).

- [ ] **Step 3: Update `ParcelLockingIndexInitializer` DDL**

```java
private static final String DDL = """
        CREATE UNIQUE INDEX IF NOT EXISTS uq_auctions_parcel_locked_status
          ON auctions(sl_parcel_uuid)
          WHERE status IN ('ACTIVE', 'TRANSFER_PENDING', 'DISPUTED')
        """;
```

The existing idempotent `DROP_OLD` + `CREATE` pattern handles the rebuild on next boot. Update the inline comment to match the spec rationale (status alone determines lock; no escrow join needed).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatus.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatusConstants.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/config/ParcelLockingIndexInitializer.java
git commit -m "refactor(auction): drop ENDED/ESCROW_PENDING/ESCROW_FUNDED, add FROZEN to status enum"
```

**Compile note:** Project won't compile after this commit. Subsequent tasks fix it.

---

### Task 2: Flyway migration translating historical rows

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__rewire_auction_status.sql`

- [ ] **Step 1: Write the migration**

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
-- (FUNDED and ESCROW_PENDING are transient escrow stops; if any persisted
-- for legacy reasons we treat them as mid-flight.)
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

-- Defensive: nothing should sit at ESCROW_PENDING / ESCROW_FUNDED today,
-- but if a stale row exists it maps to the same mid-flight state.
UPDATE auctions
SET status = 'TRANSFER_PENDING'
WHERE status IN ('ESCROW_PENDING', 'ESCROW_FUNDED');

COMMIT;
```

- [ ] **Step 2: Stage on dev DB before merging**

```bash
docker compose restart backend
```
Tail the backend logs to confirm `V36__rewire_auction_status.sql` applied without errors:
```bash
docker compose logs backend --tail 100 | grep -i 'V36\|flyway\|migrate'
```

Spot-check row counts:
```bash
docker compose exec postgres psql -U slpa -d slpa -c \
    "SELECT status, COUNT(*) FROM auctions GROUP BY status ORDER BY status"
```

Expect zero rows at `ENDED`, `ESCROW_PENDING`, `ESCROW_FUNDED` post-migration.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V36__rewire_auction_status.sql
git commit -m "migration(V36): translate auction.status rows post-state-machine-rewire"
```

---

### Task 3: Auction-close paths

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidPlacementHelpers.java`

Per spec §9 Q1, `EscrowService.createForEndedAuction` owns the `ACTIVE → TRANSFER_PENDING` flip for SOLD/BOUGHT_NOW outcomes. The close paths only own the `ACTIVE → EXPIRED` transition for NO_BIDS / RESERVE_NOT_MET.

- [ ] **Step 1: Update `AuctionEndTask.closeOne` outcome routing**

Find every `auction.setStatus(AuctionStatus.ENDED)` call in the file. Replace with conditional routing based on the resolved `endOutcome`:

```java
auction.setEndOutcome(outcome);
if (outcome == AuctionEndOutcome.SOLD || outcome == AuctionEndOutcome.BOUGHT_NOW) {
    // Status flip to TRANSFER_PENDING is owned by
    // EscrowService.createForEndedAuction (called below). Leave the
    // auction at ACTIVE here so all the post-ACTIVE status writes live
    // in one place.
} else {
    // NO_BIDS / RESERVE_NOT_MET: no escrow opens, terminal failure.
    auction.setStatus(AuctionStatus.EXPIRED);
}
```

- [ ] **Step 2: Update `BidPlacementHelpers` buy-now close path**

Same pattern. Buy-now is always `BOUGHT_NOW` outcome, so the helper hands off to `EscrowService.createForEndedAuction` which owns the flip. The helper itself just drops its old `setStatus(ENDED)` call.

- [ ] **Step 3: Verify these two files compile after the change**

```bash
cd backend && ./mvnw -q -pl . compile 2>&1 | grep -E "AuctionEndTask|BidPlacementHelpers"
```

Errors in OTHER files are expected (and fixed by subsequent tasks). What matters is no error inside the two files this task touched.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/BidPlacementHelpers.java
git commit -m "refactor(auction): NO_BIDS/RESERVE_NOT_MET closes go to EXPIRED; SOLD/BOUGHT_NOW hands off"
```

---

### Task 4: Inject `AuctionRepository` into `EscrowService` and wire all status transitions

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`

- [ ] **Step 1: Inject `AuctionRepository`**

Add the field next to the existing repos:
```java
private final AuctionRepository auctionRepo;
```
The class uses Lombok's `@RequiredArgsConstructor`, so adding the field is sufficient — Lombok regenerates the constructor.

Add the import if it's not present:
```java
import com.slparcelauctions.backend.auction.AuctionRepository;
```

- [ ] **Step 2: Add a private helper for the status flip**

Near the bottom of `EscrowService`, before the closing brace:

```java
/**
 * Flips the auction status in lockstep with an escrow state transition.
 * Caller is responsible for ensuring the new status is valid given the
 * escrow's new state (e.g. TRANSFER_PENDING when escrow → TRANSFER_PENDING).
 * The auction is already a managed entity inside this @Transactional
 * boundary; the explicit save flushes the change with the rest of the
 * escrow-side writes.
 */
private void flipAuctionStatus(Escrow escrow, AuctionStatus newStatus) {
    Auction auction = escrow.getAuction();
    auction.setStatus(newStatus);
    auctionRepo.save(auction);
}
```

Add imports as needed: `com.slparcelauctions.backend.auction.Auction`, `com.slparcelauctions.backend.auction.AuctionStatus`.

- [ ] **Step 3: `createForEndedAuction` → TRANSFER_PENDING**

Find the line where escrow is saved post auto-fund transition (in the wallet-only-escrow path — escrow has just been set to `TRANSFER_PENDING` and `transferDeadline` stamped). Immediately after that save:

```java
flipAuctionStatus(saved, AuctionStatus.TRANSFER_PENDING);
```

Note: the escrow's `auction` reference is the same managed entity the caller passed in, so the flip is atomic with the close transaction.

- [ ] **Step 4: `confirmTransfer` → COMPLETED**

In `confirmTransfer`, after the escrow row transitions to its terminal state and the `escrowRepo.save(...)` call:

```java
flipAuctionStatus(escrow, AuctionStatus.COMPLETED);
```

- [ ] **Step 5: `freezeForFraud` → FROZEN**

After the escrow row transitions to `FROZEN` and the save:

```java
flipAuctionStatus(escrow, AuctionStatus.FROZEN);
```

- [ ] **Step 6: `expireTransfer` → EXPIRED**

After the escrow row transitions to `EXPIRED` (transfer-deadline timeout path):

```java
flipAuctionStatus(escrow, AuctionStatus.EXPIRED);
```

- [ ] **Step 7: `fileDispute` → DISPUTED**

After the escrow row transitions to `DISPUTED`:

```java
flipAuctionStatus(escrow, AuctionStatus.DISPUTED);
```

- [ ] **Step 8: Sweep `EscrowService.java` for any leftover `AuctionStatus.ENDED` references**

```bash
grep -n "AuctionStatus\.ENDED\|AuctionStatus\.ESCROW_PENDING\|AuctionStatus\.ESCROW_FUNDED" \
    backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java
```

Update each: context-dependent. Most likely they were guard checks (e.g. `if (auction.getStatus() != ENDED) ...`) — those now check `TRANSFER_PENDING` or whatever the new mid-flight status is.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java
git commit -m "refactor(escrow): EscrowService owns auction-status transitions in lockstep with escrow state"
```

---

### Task 5: AdminDisputeService routes auction status per resolution action

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/disputes/AdminDisputeService.java`

`AdminDisputeService.resolve` has four `AdminDisputeAction` values. Map each to an auction-status transition that mirrors the escrow's resolved state.

| Action | Escrow result | Auction status target |
|---|---|---|
| `RECOGNIZE_PAYMENT` | `TRANSFER_PENDING` | `TRANSFER_PENDING` (dispute closed; transfer resumes) |
| `RESET_TO_FUNDED` + `!alsoCancel` | `FUNDED` | `TRANSFER_PENDING` (FUNDED is treated as mid-flight; no separate auction status) |
| `RESET_TO_FUNDED` + `alsoCancel` | `EXPIRED` | `CANCELLED` (set by `cancelByDisputeResolution` — already correct) |
| `RESUME_TRANSFER` | `TRANSFER_PENDING` | `TRANSFER_PENDING` (unfreeze) |
| `MARK_EXPIRED` | `EXPIRED` | `EXPIRED` |

- [ ] **Step 1: Inject `AuctionRepository`**

Same pattern as Task 4: add the field, Lombok regenerates the constructor.

- [ ] **Step 2: Compute the auction-status target alongside the escrow target**

After the existing `EscrowState newState = switch (action) { ... };` block, add:

```java
AuctionStatus newAuctionStatus = switch (action) {
    case RECOGNIZE_PAYMENT -> AuctionStatus.TRANSFER_PENDING;
    case RESET_TO_FUNDED -> alsoCancel
            ? null    // cancelByDisputeResolution sets CANCELLED itself
            : AuctionStatus.TRANSFER_PENDING;
    case RESUME_TRANSFER -> AuctionStatus.TRANSFER_PENDING;
    case MARK_EXPIRED -> AuctionStatus.EXPIRED;
};
```

- [ ] **Step 3: Flip the auction unless `alsoCancel` is handling it**

After the existing `escrowRepo.save(escrow);` and before the refund-credit block:

```java
if (newAuctionStatus != null) {
    auction.setStatus(newAuctionStatus);
    auctionRepo.save(auction);
}
```

(The `alsoCancel` path's `cancelByDisputeResolution` call already sets `auction.status = CANCELLED`, so we skip the explicit flip there to avoid double-write contention.)

- [ ] **Step 4: Sweep this file for `AuctionStatus.ENDED` references**

```bash
grep -n "AuctionStatus\.ENDED" \
    backend/src/main/java/com/slparcelauctions/backend/admin/disputes/AdminDisputeService.java
```

Update any remaining references in guard checks or audit-detail strings.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/disputes/AdminDisputeService.java
git commit -m "refactor(disputes): AdminDisputeService routes auction status per resolution action"
```

---

### Task 6: Admin-cancel-from-escrow (new path)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/FreezeReason.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java`

- [ ] **Step 1: Add `ADMIN_CANCEL` to `FreezeReason`**

The escrow row's `freeze_reason` column is also used by the `EXPIRED` path (a single text column, just different state). Add the value:

```java
public enum FreezeReason {
    UNKNOWN_OWNER,
    PARCEL_DELETED,
    WORLD_API_PERSISTENT_FAILURE,
    BOT_OWNERSHIP_CHANGED,
    /**
     * Set when admin cancels a TRANSFER_PENDING listing via
     * {@code CancellationService.cancelByAdminFromEscrow}. The escrow
     * transitions to EXPIRED (refund-and-close) and the auction to
     * CANCELLED in the same transaction.
     */
    ADMIN_CANCEL
}
```

- [ ] **Step 2: Add the new notification category**

In `NotificationCategory.java`, add `LISTING_CANCELLED_DURING_ESCROW`. Mirror the existing category-enum ordering (alphabetical or by-domain — match the file's convention).

- [ ] **Step 3: Add the publisher signature**

In `NotificationPublisher.java`:

```java
void listingCancelledDuringEscrow(
        long winnerUserId, long auctionId, long escrowId,
        String parcelName, String adminNote);
```

- [ ] **Step 4: Implement in `NotificationPublisherImpl`**

```java
@Override
public void listingCancelledDuringEscrow(long winnerUserId, long auctionId, long escrowId,
                                          String parcelName, String adminNote) {
    String title = "Listing cancelled during escrow: " + parcelName;
    String body = "An admin cancelled this listing. Your escrow has been refunded to your SLParcels wallet.";
    notificationService.publish(new NotificationEvent(
        winnerUserId, NotificationCategory.LISTING_CANCELLED_DURING_ESCROW, title, body,
        withAuctionPublicId(
            NotificationDataBuilder.listingCancelledDuringEscrow(
                auctionId, escrowId, parcelName, adminNote),
            auctionId),
        null
    ));
}
```

- [ ] **Step 5: Implement the data builder**

In `NotificationDataBuilder.java`:

```java
public static Map<String, Object> listingCancelledDuringEscrow(
        long auctionId, long escrowId, String parcelName, String adminNote) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("auctionId", auctionId);
    data.put("escrowId", escrowId);
    data.put("parcelName", parcelName);
    if (adminNote != null && !adminNote.isBlank()) {
        data.put("adminNote", adminNote);
    }
    return data;
}
```

- [ ] **Step 6: Add the SL IM link resolver mapping**

`SlImLinkResolver.java` switches on `NotificationCategory`. Add `LISTING_CANCELLED_DURING_ESCROW` to the same group as the other escrow-related categories (deeplink resolves to `/auction/{publicId}/escrow`).

- [ ] **Step 7: Add `cancelByAdminFromEscrow` to `CancellationService`**

Inject `EscrowService` + `EscrowRepository` if not already present. Then:

```java
/**
 * Admin cancels a listing whose escrow is in TRANSFER_PENDING. Refunds
 * the winner from escrow, marks the escrow EXPIRED with reason
 * ADMIN_CANCEL, and flips the auction to CANCELLED. The seller's
 * penalty ladder is NOT touched -- staff action, not a seller offense.
 *
 * <p>Caller is {@link AdminListingService#cancel(UUID, Long, String)};
 * the seller-initiated and pre-active admin paths route through
 * {@link #cancelByAdmin(Long, Long, String)} instead.
 */
@Transactional
public Auction cancelByAdminFromEscrow(Long auctionId, Long adminUserId, String notes) {
    Auction a = auctionRepo.findByIdForUpdate(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    if (a.getStatus() != AuctionStatus.TRANSFER_PENDING) {
        throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "ADMIN_CANCEL_FROM_ESCROW");
    }
    Escrow escrow = escrowRepo.findByAuctionId(a.getId())
            .orElseThrow(() -> new IllegalStateException(
                "Auction " + a.getId() + " in TRANSFER_PENDING has no escrow row"));
    if (escrow.getState() != EscrowState.TRANSFER_PENDING) {
        throw new IllegalStateException(
            "Escrow " + escrow.getId() + " not in TRANSFER_PENDING (actual=" + escrow.getState() + ")");
    }

    OffsetDateTime now = OffsetDateTime.now(clock);

    // Refund winner. queueRefundIfFunded is idempotent on fundedAt; for
    // wallet-only-escrow rows fundedAt is always set, so the refund fires.
    escrowService.queueRefundIfFunded(escrow);

    // Escrow → EXPIRED with reason ADMIN_CANCEL.
    EscrowService.enforceTransitionAllowed(escrow.getId(), escrow.getState(), EscrowState.EXPIRED);
    escrow.setState(EscrowState.EXPIRED);
    escrow.setExpiredAt(now);
    escrow.setFreezeReason(FreezeReason.ADMIN_CANCEL.name());
    escrowRepo.save(escrow);

    // Audit trail: cancellation_logs row with cancelledByAdminId set so the
    // penalty-ladder counters don't bill the seller.
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

    notificationPublisher.listingRemovedByAdmin(
            a.getSeller().getId(), a.getId(), a.getTitle(), notes);
    notificationPublisher.listingCancelledDuringEscrow(
            a.getWinnerUserId(),
            a.getId(), escrow.getId(), a.getTitle(), notes);

    final boolean hadBids = true;
    AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(saved, hadBids, now);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        broadcastPublisher.publishCancelled(envelope);
                    }
                });
    } else {
        broadcastPublisher.publishCancelled(envelope);
    }

    log.info("Auction {} admin-cancelled from TRANSFER_PENDING by adminUserId={} (escrow {} refunded, marked EXPIRED/ADMIN_CANCEL)",
            a.getId(), adminUserId, escrow.getId());

    return saved;
}
```

Add imports as needed: `EscrowService`, `EscrowRepository`, `Escrow`, `EscrowState`, `FreezeReason`.

- [ ] **Step 8: Route from `AdminListingService.cancel`**

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

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/FreezeReason.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/listings/AdminListingService.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java
git commit -m "feat(admin): cancel listing from TRANSFER_PENDING refunds escrow and notifies winner"
```

---

### Task 7: `assertParcelNotLocked` collapse + `toPublicStatus` update

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`

- [ ] **Step 1: `assertParcelNotLocked` returns to the single-pass check**

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

Status alone encodes the lock decision now. No escrow join helper, no separate ACTIVE_ESCROW_STATES set. Update the javadoc to match.

- [ ] **Step 2: Update `AuctionDtoMapper.toPublicStatus`**

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

`DRAFT`, `DRAFT_PAID`, `VERIFICATION_PENDING`, `VERIFICATION_FAILED`, `SUSPENDED` continue to be hidden by 404 at the controller layer — they fall through to the `default` throw.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java
git commit -m "refactor(auction): collapse parcel-lock check; map terminals to public ENDED"
```

---

### Task 8: Production-side `AuctionStatus.ENDED` sweep

**Files (production code only — tests are Task 11):**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/MyBidsService.java`
- Plus any other file surfacing in the grep below.

- [ ] **Step 1: Find every remaining production reference**

```bash
grep -rln 'AuctionStatus\.ENDED\|AuctionStatus\.ESCROW_PENDING\|AuctionStatus\.ESCROW_FUNDED' \
    backend/src/main/java
```

This should now list only the files in this task plus anything missed in Tasks 1–7. If files from earlier tasks reappear, finish their context first.

- [ ] **Step 2: For each file, decide the correct replacement**

Per-call-site interpretation:

- **Status guards that mean "is this auction over?"** → replace with a check against the terminal set `{COMPLETED, EXPIRED, FROZEN, CANCELLED}`, or expose a helper `auction.isTerminal()`.
- **Status guards that mean "is this auction post-close but pre-settlement?"** → replace with `TRANSFER_PENDING`.
- **Status guards that mean "auction is no longer accepting bids"** → replace with `!= AuctionStatus.ACTIVE` (the cleanest formulation).
- **Filter / search arrays** → replace with the right subset of terminals.

Common patterns to expect:
- `if (auction.getStatus() == AuctionStatus.ENDED)` → usually means "auction closed" → check terminal set or `!= ACTIVE`.
- `if (auction.getStatus() == AuctionStatus.ESCROW_PENDING || ... == ESCROW_FUNDED || ... == TRANSFER_PENDING)` → collapse to just `== TRANSFER_PENDING`.

- [ ] **Step 3: Compile-check after the sweep**

```bash
cd backend && ./mvnw -q compile
```

Expect a clean build.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java
git commit -m "refactor(auction): replace ENDED/ESCROW_PENDING/ESCROW_FUNDED references in production code"
```

---

### Task 9: Frontend types + notification wiring

**Files:**
- Modify: `frontend/src/types/auction.ts`
- Modify: `frontend/src/lib/notifications/types.ts`
- Modify: `frontend/src/lib/notifications/categoryMap.ts`
- Modify: `frontend/src/lib/notifications/categoryMap.test.ts`
- Plus any frontend file surfacing `status === "ENDED"` for seller-context UI.

- [ ] **Step 1: Update `AuctionStatus` mirror**

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

- [ ] **Step 2: Add the new notification category**

In `frontend/src/lib/notifications/types.ts`:

```ts
export type NotificationCategory =
  // ...existing entries...
  | "LISTING_CANCELLED_DURING_ESCROW";

// in NotificationData mapping
export interface NotificationData {
  // ...existing...
  LISTING_CANCELLED_DURING_ESCROW: {
    auctionId: number;
    escrowId: number;
    parcelName: string;
    adminNote?: string;
  };
}
```

(Match the exact shape conventions of the file.)

- [ ] **Step 3: Add the category to `categoryMap.ts`**

Mirror the existing escrow-related entries — deeplink resolves to `/auction/{publicIdOrId}/escrow`. The auction publicId comes through the `auctionPublicId` decorator on the data payload (already wired by `withAuctionPublicId` on the backend, see Task 6).

- [ ] **Step 4: Add the test entry**

`categoryMap.test.ts` includes a deeplink assertion for each category. Add the matching test.

- [ ] **Step 5: Sweep frontend for seller-context `"ENDED"` checks**

```bash
grep -rn 'status === "ENDED"\|status: "ENDED"' frontend/src
```

For each match, decide:
- **Public-context UI (browse, anonymous listing)** consuming `PublicAuctionResponse.status` → leave the `"ENDED"` literal alone (public type still has it).
- **Seller-context UI** consuming `SellerAuctionResponse.status` → switch to the matching internal terminal (`COMPLETED`/`EXPIRED`/`FROZEN`/`CANCELLED`) or to a helper that recognizes the terminal set.

- [ ] **Step 6: Run frontend typecheck + Vitest**

```bash
cd frontend && npx tsc --noEmit && npx vitest run
```

Expect green. There's a pre-existing typecheck error in `useBulkCommissionEdit.test.tsx` — that's unrelated to this task; skip it if it appears.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "refactor(frontend): mirror backend status enum + wire LISTING_CANCELLED_DURING_ESCROW notification"
```

---

### Task 10: `MyBidStatusDeriver` + any other status-derived UI logic

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/mybids/MyBidStatusDeriver.java` (if it references the dropped statuses — verify with grep)
- Plus any other status-derived helper.

- [ ] **Step 1: Locate**

```bash
grep -rn 'AuctionStatus\.ENDED\|AuctionStatus\.ESCROW_PENDING\|AuctionStatus\.ESCROW_FUNDED' \
    backend/src/main/java/com/slparcelauctions/backend/auction/mybids
```

- [ ] **Step 2: Update the derivation logic**

`MyBidStatusDeriver` maps an auction's lifecycle position to a buyer-facing `MyBidStatus`. After Task 8 the auction will already be at `COMPLETED`/`EXPIRED`/`FROZEN`/`CANCELLED`/`DISPUTED`/`TRANSFER_PENDING`; the deriver should branch on these.

Mapping:
- `auction.status = ACTIVE` + caller is currently highest bidder → `LEADING`
- `auction.status = ACTIVE` + caller is outbid → `OUTBID`
- `auction.status ∈ {TRANSFER_PENDING, DISPUTED}` + caller is winner → `WON_AWAITING_TRANSFER`
- `auction.status = COMPLETED` + caller is winner → `WON_COMPLETED`
- `auction.status ∈ {EXPIRED, FROZEN, CANCELLED}` + caller is winner → `WON_THEN_LOST` (or whichever existing terminal-loss status name applies)
- Caller is not winner / no longer leading → `LOST` / `OUTBID_TERMINAL`

Match the existing `MyBidStatus` enum names — don't invent new ones unless the existing set genuinely doesn't cover the case.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/mybids
git commit -m "refactor(mybids): derive bid status from new auction-status terminals"
```

---

### Task 11: Backend test sweep

**Files:** see grep output for full list — expect ~30 test files.

- [ ] **Step 1: Find every test reference**

```bash
grep -rln 'AuctionStatus\.ENDED\|AuctionStatus\.ESCROW_PENDING\|AuctionStatus\.ESCROW_FUNDED\|"ENDED"' \
    backend/src/test
```

- [ ] **Step 2: For each file, update assertions per production reality**

Common patterns to expect:
- `AuctionEndIntegrationTest`, `BidServiceBuyNowTest`, `SnipeAndBuyNowIntegrationTest`,
  `BidPlacementIntegrationTest`, `EscrowCreateOnAuctionEndIntegrationTest`,
  `EscrowCreateOnBuyNowIntegrationTest`:
  - SOLD/BOUGHT_NOW close → expect `auction.status = TRANSFER_PENDING`
  - NO_BIDS/RESERVE_NOT_MET close → expect `auction.status = EXPIRED`
- `EscrowOwnershipMonitorIntegrationTest`, `EscrowEndToEndIntegrationTest`:
  - confirmTransfer → expect `COMPLETED`
  - freezeForFraud → expect `FROZEN`
- `EscrowTimeoutIntegrationTest`, `EscrowTimeoutTaskTest`:
  - transfer-timeout → expect `EXPIRED`
- `EscrowDisputeIntegrationTest`, `AdminDisputeServiceTest`:
  - fileDispute → expect `DISPUTED`
  - resolve(action) → expect status per the table in Task 5
- `CancellationServiceTest`: cancel paths set `CANCELLED`. Should mostly be unchanged.
- `ParcelLockingRaceIntegrationTest`: add/update to cover the new locking set
  (TRANSFER_PENDING locks, terminals don't). Drop any leftover PR #319 stopgap tests.
- `MyBidsServiceTest`, `MyBidStatusDeriverTest`, `MyBidsIntegrationTest`: update per Task 10.
- `FullFlowSmokeTest`: end-to-end status assertions.

- [ ] **Step 3: Add admin-cancel-from-escrow coverage**

Net new tests:
- `AdminListingService.cancel` on TRANSFER_PENDING listing succeeds.
- The escrow is refunded (winner wallet credited; `AUCTION_ESCROW_REFUND` ledger row).
- The escrow row is `state = EXPIRED, freezeReason = ADMIN_CANCEL`.
- The auction is `CANCELLED`.
- Seller receives `LISTING_REMOVED_BY_ADMIN`; winner receives
  `LISTING_CANCELLED_DURING_ESCROW`.
- The path rejects with `INVALID_STATUS_FOR_ACTION` for non-TRANSFER_PENDING input.

Pick a single integration test file (e.g. new `AdminListingCancelFromEscrowIntegrationTest`) under
`backend/src/test/java/com/slparcelauctions/backend/admin/listings/`. Mirror the seeding +
teardown patterns from `AdminListingServiceTest` or
`EscrowCreateOnAuctionEndIntegrationTest`.

- [ ] **Step 4: Add parcel-lock coverage for new statuses**

In `ParcelLockingRaceIntegrationTest`:
- `TRANSFER_PENDING` auction locks the parcel.
- `FROZEN`/`EXPIRED`/`COMPLETED`/`CANCELLED` auctions don't lock the parcel.
- Two ENDED-era stopgap tests from the (now-closed) PR #319 attempt should be dropped if any still exist.

- [ ] **Step 5: Run full backend test suite**

```bash
cd backend && ./mvnw test
```

Expected: green. Triage any unexpected failures.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test
git commit -m "test: update status assertions for state-machine rewire; add admin-cancel-from-escrow + parcel-lock coverage"
```

---

### Task 12: Final integration sweep + PR

- [ ] **Step 1: Final compile + test sweep**

```bash
cd backend && ./mvnw verify
cd ../frontend && npx tsc --noEmit && npx vitest run
```

Triage any straggling reference to the dropped statuses.

- [ ] **Step 2: Manual smoke notes for the PR description**

After deploy:
- Seller can re-list the previously-stuck parcel (auction 20 / parcel
  `abac26ab-…` from the prod incident — auction 17's lock should be gone
  since the migration translated it to `FROZEN`).
- A new buy-now sale shows `auction.status = TRANSFER_PENDING` until
  the ownership monitor confirms transfer, then `COMPLETED`.
- Admin can cancel a TRANSFER_PENDING listing from the admin panel; the
  winner gets a refund + `LISTING_CANCELLED_DURING_ESCROW` notification.

- [ ] **Step 3: Open PR**

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
- [ ] Post-deploy smoke: re-list the previously-stuck parcel; admin-cancel a TRANSFER_PENDING listing
EOF
)"
```

- [ ] **Step 4: Wait for user review**

User reviews + merges `dev → main` themselves per `feedback_no_merge_to_main`.

---

## Self-review checklist (run at end of plan)

1. **Spec coverage:** Every section of the spec mapped to a task?
   - Enum + constants + index → Task 1
   - Migration → Task 2
   - Close paths → Task 3
   - Escrow lifecycle transitions → Task 4
   - Dispute resolution → Task 5
   - Admin-cancel-from-escrow + notification wiring → Task 6
   - Parcel-lock collapse + public DTO mapper → Task 7
   - Production ENDED sweep → Task 8
   - Frontend types + notification mirror → Task 9
   - MyBids derivation → Task 10
   - Test sweep + new coverage → Task 11
   - Final integration + PR → Task 12
2. **Type consistency:** Method signatures match across tasks?
   `cancelByAdminFromEscrow(auctionId, adminUserId, notes)` is named the
   same in Task 6 (definition) and the `AdminListingService.cancel` call
   site. `listingCancelledDuringEscrow(winnerUserId, auctionId, escrowId,
   parcelName, adminNote)` matches across the publisher + impl + builder.
3. **Migration determinism:** Every persisted (`status`, `escrow.state`)
   combination has a translation rule. No row left at the dropped statuses
   post-V36.
4. **No dead code:** `ENDED` / `ESCROW_PENDING` / `ESCROW_FUNDED` removed
   from `AuctionStatus`, `LOCKING_STATUSES`, the index DDL, public-status
   mapper, every production file, every test, every comment, frontend
   types.
5. **Frontend mirror:** Type definitions match the backend enum; new
   notification category present in all four touch points (`types.ts`,
   `categoryMap.ts`, `categoryMap.test.ts`, backend `SlImLinkResolver`).

If any check fails, add a task or extend an existing one. Don't ship with a known gap.
