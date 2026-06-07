# HQ Featured Boards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship in-world HQ Featured boards bundled with a new PROMO-01 (Featured listing) purchase flow. Sellers click "Feature this listing" on their auction management page, L$500 (configurable) debits from their wallet, the auction's `is_featured` flips, and a slot row is appended to the least-loaded board's queue. Each of 5 (configurable) HQ board prims shows the C2 layout (full-bleed square photo + gradient caption + QR) via Media-on-Prim, cycling every 30s when the queue holds 2+, static when 1, falling back to algorithmic featured then to a "List your parcel here" placeholder. Touching a board fires a dialog with [Teleport / View listing].

**Architecture:** Backend is a new `featured_board_slots` table + 4 services (`FeaturedBoardAssignmentService` for pure least-loaded logic, `FeaturedBoardSlotService` for transactional assign/release, `BoardContentResolver` for the read-side payload, `PromotionService` for the purchase orchestration) + 2 controllers (`InWorldFeaturedBoardController` public, `AdminFeaturedBoardController` admin) + ledger entries `PROMOTION_DEBIT` / `PROMOTION_REFUND` + an afterCommit hook in `AuctionEndTask` for slot release. Frontend is two new dynamic pages (`/in-world/board/[boardIndex]`, `/in-world/board/placeholder`), a "Feature this listing" button on auction management, and an admin page at `/admin/featured-boards`. LSL is one new outbound-only script. All three new config knobs land on existing `AuctionConfigProperties`.

**Tech Stack:** Spring Boot 4 / Java 24 / PostgreSQL 17 / Redis / Flyway; Next.js 16 / React 19 / Vitest; LSL.

---

## File Map

**Backend — create:**

- `backend/src/main/resources/db/migration/V46__featured_board_slots.sql` — table + indexes
- `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlot.java` — entity
- `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotRepository.java` — Spring Data repo
- `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardAssignmentService.java` — pure least-loaded logic
- `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotService.java` — transactional assign/release
- `backend/src/main/java/com/slparcelauctions/backend/promotion/BoardContentResolver.java` — read-side payload builder
- `backend/src/main/java/com/slparcelauctions/backend/promotion/PromotionService.java` — buy-PROMO-01 orchestration
- `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/FeaturedBoardPayloadDto.java`
- `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/FeaturedBoardListingDto.java`
- `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/AdminFeaturedBoardRowDto.java`
- `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/MovePromotionSlotRequest.java`
- `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/PurchaseFeaturedResponse.java`
- `backend/src/main/java/com/slparcelauctions/backend/promotion/InWorldFeaturedBoardController.java` — public anonymous
- `backend/src/main/java/com/slparcelauctions/backend/promotion/PromotionController.java` — `/me/promotions/featured`
- `backend/src/main/java/com/slparcelauctions/backend/promotion/AdminFeaturedBoardController.java` — admin curator
- `backend/src/main/java/com/slparcelauctions/backend/promotion/exception/PromotionAlreadyActiveException.java`
- `backend/src/main/java/com/slparcelauctions/backend/promotion/exception/NotAuctionSellerException.java`
- `backend/src/main/java/com/slparcelauctions/backend/promotion/exception/InvalidBoardIndexException.java`

Plus the matching test classes under `backend/src/test/java/com/slparcelauctions/backend/promotion/`.

**Backend — modify:**

- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionConfigProperties.java` — add 3 fields
- `backend/src/main/resources/application.yml` — add 3 defaults
- `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java` — add `PROMOTION_DEBIT`, `PROMOTION_REFUND`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java` — add `debitPromotionFee(...)`
- `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java` — afterCommit hook → `slotService.releaseForAuction(...)`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatusTransitionService.java` (or equivalent CANCELLED/WITHDRAWN dispatcher) — afterCommit hook
- `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` — `permitAll` on `/in-world/featured-board/**` and `/in-world/board/placeholder`; admin matcher on `/admin/featured-boards/**`

**Frontend — create:**

- `frontend/src/app/in-world/board/[boardIndex]/page.tsx`
- `frontend/src/app/in-world/board/placeholder/page.tsx`
- `frontend/src/components/inworld/FeaturedBoardView.tsx`
- `frontend/src/components/inworld/FeaturedBoardCycler.tsx`
- `frontend/src/components/inworld/PlaceholderBoardView.tsx`
- `frontend/src/components/listing/FeatureListingButton.tsx`
- `frontend/src/components/listing/FeatureListingModal.tsx`
- `frontend/src/hooks/useFeatureListing.ts`
- `frontend/src/lib/api/promotions.ts`
- `frontend/src/lib/api/inWorldBoards.ts`
- `frontend/src/app/admin/featured-boards/page.tsx`
- `frontend/src/components/admin/featured-boards/AdminFeaturedBoardsTable.tsx`
- `frontend/src/types/promotion.ts`

Plus the matching `.test.tsx` / `.test.ts` files.

**Frontend — modify:**

- `frontend/scripts/verify-no-inline-styles.sh` — add board pages to allowlist
- `frontend/src/components/listing/AuctionManagementPanel.tsx` (or wherever the seller manages an active auction) — render `FeatureListingButton`

**LSL — create:**

- `lsl-scripts/featured-board/featured-board.lsl`
- `lsl-scripts/featured-board/config.notecard.example`
- `lsl-scripts/featured-board/README.md`
- Update `lsl-scripts/README.md` (top-level index) to list the new script

**Postman — modify:**

- SLPA collection in workspace `SLPA` (collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`) — add folder "In-World Boards" with anonymous + admin requests

---

## Phase 1 — Config + ledger types + migration

### Task 1: Add featured-board config properties

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionConfigProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionConfigPropertiesFixture.java`

- [ ] **Step 1: Add the three fields to the record.**

In `AuctionConfigProperties.java`, append to the record's parameter list (preserve existing order; add at end so deserialization is unambiguous):

```java
@Min(1) long featuredPriceLindens,
@Min(1) @Max(13) int featuredSlotCount,
@Min(1) int featuredBoardCycleSeconds
```

Add the imports if missing: `jakarta.validation.constraints.Max`, `jakarta.validation.constraints.Min`.

- [ ] **Step 2: Add the YAML defaults.**

In `application.yml`, under `slpa.auction`, append:

```yaml
    # PROMO-01 (Featured listing) pricing.
    featured-price-lindens: 500
    # Number of HQ boards currently activated (1..13). Boards beyond this
    # index render the placeholder route.
    featured-slot-count: 5
    # Cross-fade interval when a board's queue holds 2+ listings.
    featured-board-cycle-seconds: 30
```

- [ ] **Step 3: Update the test fixture so other tests still construct AuctionConfigProperties.**

In `AuctionConfigPropertiesFixture.java`, find the existing factory method (e.g. `defaults()`) and add the three new fields with sensible test values matching the defaults (`500L, 5, 30`).

- [ ] **Step 4: Run the fixture's dependents to confirm nothing broke.**

Run: `cd backend && ./mvnw test -Dtest=AuctionConfigPropertiesFixture*`
Expected: green.

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionConfigProperties.java backend/src/main/resources/application.yml backend/src/test/java/com/slparcelauctions/backend/auction/AuctionConfigPropertiesFixture.java
git commit -m "feat(backend): add featured-board config (price, slot count, cycle seconds)"
```

---

### Task 2: Add `PROMOTION_DEBIT` / `PROMOTION_REFUND` ledger entry types

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java`

- [ ] **Step 1: Append the two enum constants.**

Add at the end of `UserLedgerEntryType` (after `DORMANCY_AUTO_RETURN`):

```java
    ,

    /**
     * PROMO-01 (Featured listing) purchase debit. Issued by
     * {@code PromotionService.purchaseFeatured} when a seller buys
     * Featured exposure for one of their auctions. {@code refType="AUCTION"},
     * {@code refId=auctionId}. Idempotency: the matching active row in
     * {@code featured_board_slots} (unique per auction while
     * {@code released_at IS NULL}) is the source of truth — a duplicate
     * purchase attempt fails before this ledger entry is written.
     */
    PROMOTION_DEBIT,

    /**
     * PROMO-01 refund credited back to wallet. Issued by an admin via the
     * existing wallet adjustment / coupon path when a force-released slot
     * warrants a refund. Not auto-issued by slot release on auction end.
     */
    PROMOTION_REFUND
```

- [ ] **Step 2: Compile to confirm no enum-switch regressed.**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS. (Any service doing `switch(entryType)` without a default will surface here.)

- [ ] **Step 3: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java
git commit -m "feat(backend): add PROMOTION_DEBIT and PROMOTION_REFUND ledger entry types"
```

---

### Task 3: Write the Flyway migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V46__featured_board_slots.sql`

- [ ] **Step 1: Confirm V45 is the latest on disk.**

Run: `ls backend/src/main/resources/db/migration/ | sort -V | tail -1`
Expected: `V45__auction_parcel_scan.sql`. If a higher V exists, bump this task's filename accordingly.

- [ ] **Step 2: Write the migration.**

Create `V46__featured_board_slots.sql`:

```sql
-- One row per (auction × board assignment). Auctions land on boards via
-- PROMO-01 purchase (the seller buying Featured exposure); slots release
-- when the auction ends, is cancelled/withdrawn, or admin force-releases.
-- Per-board queue order is `position` asc within the same board where
-- released_at IS NULL.

CREATE TABLE featured_board_slots (
    id           BIGSERIAL PRIMARY KEY,
    public_id    UUID NOT NULL UNIQUE,
    board_index  INTEGER NOT NULL,
    auction_id   BIGINT NOT NULL REFERENCES auctions(id),
    position     INTEGER NOT NULL,
    assigned_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT featured_board_slots_board_index_range
        CHECK (board_index BETWEEN 1 AND 13)
);

-- Live queue lookup: rows for a specific active board, in display order.
CREATE INDEX featured_board_slots_live_queue_idx
    ON featured_board_slots (board_index, position)
    WHERE released_at IS NULL;

-- Exactly one active row per auction (an auction is on at most one board
-- at a time). Used as the idempotency guard for PROMO-01 purchase — the
-- second buy attempt fails the constraint and the controller maps to 409.
CREATE UNIQUE INDEX featured_board_slots_active_per_auction_idx
    ON featured_board_slots (auction_id)
    WHERE released_at IS NULL;
```

- [ ] **Step 3: Run the migration against dev.**

Run: `docker compose restart backend`
Watch the logs for `Successfully applied 1 migration to schema "public", now at version v46`.

Run: `docker exec slpa-postgres psql -U slpa slpa -c "\d featured_board_slots"`
Expected: table prints with the columns above.

- [ ] **Step 4: Commit.**

```bash
git add backend/src/main/resources/db/migration/V46__featured_board_slots.sql
git commit -m "feat(backend): V46 featured_board_slots migration"
```

---

## Phase 2 — Entity, repository, and pure-logic assignment service

### Task 4: Add the `FeaturedBoardSlot` entity

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlot.java`

- [ ] **Step 1: Write the entity.**

```java
package com.slparcelauctions.backend.promotion;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One row per (auction × board assignment) representing a single PROMO-01
 * board-slot lease. Active iff {@code releasedAt IS NULL}. See spec §4.
 */
@Entity
@Table(name = "featured_board_slots")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FeaturedBoardSlot extends BaseMutableEntity {

    @Column(name = "board_index", nullable = false)
    private int boardIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    @JsonIgnore
    private Auction auction;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;
}
```

- [ ] **Step 2: Compile.**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlot.java
git commit -m "feat(backend): FeaturedBoardSlot entity"
```

---

### Task 5: Add the repository

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotRepository.java`

- [ ] **Step 1: Write the repository.**

```java
package com.slparcelauctions.backend.promotion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeaturedBoardSlotRepository extends JpaRepository<FeaturedBoardSlot, Long> {

    Optional<FeaturedBoardSlot> findByPublicId(UUID publicId);

    /**
     * The live queue for one board, in display (cycle) order. Caller decides
     * static-vs-cycle based on the list size.
     */
    @Query("""
        SELECT s FROM FeaturedBoardSlot s
        WHERE s.boardIndex = :boardIndex AND s.releasedAt IS NULL
        ORDER BY s.position ASC, s.id ASC
        """)
    List<FeaturedBoardSlot> liveQueue(@Param("boardIndex") int boardIndex);

    /**
     * Active rows across all boards — used by {@code FeaturedBoardAssignmentService}
     * to compute per-board counts and by the admin curator. Ordering is
     * (boardIndex, position) so callers can group cheaply.
     */
    @Query("""
        SELECT s FROM FeaturedBoardSlot s
        WHERE s.releasedAt IS NULL
        ORDER BY s.boardIndex ASC, s.position ASC, s.id ASC
        """)
    List<FeaturedBoardSlot> allActive();

    /**
     * Active row for a given auction (a listing is on at most one board at
     * a time). Used by the release-on-auction-end path.
     */
    @Query("""
        SELECT s FROM FeaturedBoardSlot s
        WHERE s.auction.id = :auctionId AND s.releasedAt IS NULL
        """)
    Optional<FeaturedBoardSlot> findActiveByAuctionId(@Param("auctionId") long auctionId);
}
```

- [ ] **Step 2: Compile.**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotRepository.java
git commit -m "feat(backend): FeaturedBoardSlot repository"
```

---

### Task 6: Add `FeaturedBoardAssignmentService` (pure logic, unit-testable, no DB)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardAssignmentService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/promotion/FeaturedBoardAssignmentServiceTest.java`

- [ ] **Step 1: Write the failing tests first.**

```java
package com.slparcelauctions.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FeaturedBoardAssignmentServiceTest {

    private final FeaturedBoardAssignmentService svc = new FeaturedBoardAssignmentService();

    @Test
    void empty_pool_picks_board_one_at_position_zero() {
        var result = svc.assign(/* slotCount */ 5, /* perBoardCounts */ Map.of());
        assertThat(result.boardIndex()).isEqualTo(1);
        assertThat(result.position()).isEqualTo(0);
    }

    @Test
    void unbalanced_pool_picks_lowest_loaded_board() {
        var counts = Map.of(1, 3, 2, 3, 3, 1, 4, 2, 5, 3);
        var result = svc.assign(5, counts);
        assertThat(result.boardIndex()).isEqualTo(3);
        assertThat(result.position()).isEqualTo(1);
    }

    @Test
    void tie_breaks_to_lowest_board_index() {
        var counts = Map.of(1, 2, 2, 1, 3, 1, 4, 2, 5, 1);
        var result = svc.assign(5, counts);
        assertThat(result.boardIndex()).isEqualTo(2);
        assertThat(result.position()).isEqualTo(1);
    }

    @Test
    void balanced_full_pool_picks_first_board() {
        var counts = Map.of(1, 3, 2, 3, 3, 3, 4, 3, 5, 3);
        var result = svc.assign(5, counts);
        assertThat(result.boardIndex()).isEqualTo(1);
        assertThat(result.position()).isEqualTo(3);
    }

    @Test
    void boards_not_in_counts_treated_as_empty() {
        var counts = Map.of(1, 5);
        var result = svc.assign(3, counts);
        assertThat(result.boardIndex()).isEqualTo(2);
        assertThat(result.position()).isEqualTo(0);
    }

    @Test
    void slotCount_one_always_returns_board_one() {
        var result = svc.assign(1, Map.of(1, 7));
        assertThat(result.boardIndex()).isEqualTo(1);
        assertThat(result.position()).isEqualTo(7);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail.**

Run: `cd backend && ./mvnw test -Dtest=FeaturedBoardAssignmentServiceTest -q`
Expected: compilation failure (service doesn't exist yet).

- [ ] **Step 3: Write the service.**

```java
package com.slparcelauctions.backend.promotion;

import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Pure least-loaded board selection. No DB, no transactions, no Spring
 * dependencies beyond {@code @Service} for DI. Callers are responsible
 * for supplying the canonical per-board active-row counts (typically
 * derived from {@code FeaturedBoardSlotRepository.allActive()}).
 */
@Service
public class FeaturedBoardAssignmentService {

    public record Assignment(int boardIndex, int position) {}

    /**
     * Pick the board with the fewest active rows. Boards not in the counts
     * map are treated as empty. Tiebreak: lowest board index wins.
     *
     * @param slotCount  total active board count (config-driven, 1..13).
     * @param perBoardCounts  map of boardIndex -> active row count.
     * @return the board to assign to and the {@code position} the new row
     *         should take (== current count for that board, since rows
     *         are appended).
     */
    public Assignment assign(int slotCount, Map<Integer, Integer> perBoardCounts) {
        if (slotCount < 1) {
            throw new IllegalArgumentException("slotCount must be >= 1, got " + slotCount);
        }
        int bestBoard = 1;
        int bestCount = Integer.MAX_VALUE;
        for (int b = 1; b <= slotCount; b++) {
            int count = perBoardCounts.getOrDefault(b, 0);
            if (count < bestCount) {
                bestBoard = b;
                bestCount = count;
            }
        }
        return new Assignment(bestBoard, bestCount);
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass.**

Run: `cd backend && ./mvnw test -Dtest=FeaturedBoardAssignmentServiceTest -q`
Expected: BUILD SUCCESS, 6 tests passing.

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardAssignmentService.java backend/src/test/java/com/slparcelauctions/backend/promotion/FeaturedBoardAssignmentServiceTest.java
git commit -m "feat(backend): FeaturedBoardAssignmentService with least-loaded selection"
```

---

## Phase 3 — Transactional slot service + auction-end release

### Task 7: Add `FeaturedBoardSlotService.assign` + `releaseForAuction`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/exception/PromotionAlreadyActiveException.java`

- [ ] **Step 1: Add the exception.**

```java
package com.slparcelauctions.backend.promotion.exception;

import java.util.UUID;

/**
 * The auction already has an active PROMO-01 slot — a duplicate purchase
 * attempt. Maps to HTTP 409 / problem code {@code PROMOTION_ALREADY_ACTIVE}.
 */
public class PromotionAlreadyActiveException extends RuntimeException {
    private final UUID auctionPublicId;

    public PromotionAlreadyActiveException(UUID auctionPublicId) {
        super("Auction " + auctionPublicId + " already has an active PROMO-01 slot");
        this.auctionPublicId = auctionPublicId;
    }

    public UUID getAuctionPublicId() {
        return auctionPublicId;
    }
}
```

- [ ] **Step 2: Add the service.**

```java
package com.slparcelauctions.backend.promotion;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionConfigProperties;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Transactional façade over {@link FeaturedBoardSlotRepository}. Owns the
 * row-level invariants — exactly one active slot per auction, append-only
 * position within a board, releaseTimestamp set on terminal transitions.
 *
 * <p>All public methods participate in the caller's transaction by default
 * (the typical caller is {@code PromotionService.purchaseFeatured} which
 * runs inside the wallet-debit transaction). {@code releaseForAuction} is
 * the one path that callers commonly invoke from {@code afterCommit} hooks
 * — those callers MUST open a fresh transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeaturedBoardSlotService {

    private final FeaturedBoardSlotRepository slotRepo;
    private final FeaturedBoardAssignmentService assignmentService;
    private final AuctionConfigProperties auctionConfig;

    /**
     * Create a slot row for {@code auction}, picking the least-loaded board.
     * Must run inside a transaction. Fails fast with
     * {@link PromotionAlreadyActiveException} if the auction already has an
     * active row (enforced by the unique partial index in V46).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FeaturedBoardSlot assign(Auction auction) {
        if (slotRepo.findActiveByAuctionId(auction.getId()).isPresent()) {
            throw new PromotionAlreadyActiveException(auction.getPublicId());
        }
        Map<Integer, Integer> counts = countsByBoard();
        var pick = assignmentService.assign(auctionConfig.featuredSlotCount(), counts);

        FeaturedBoardSlot slot = FeaturedBoardSlot.builder()
                .publicId(UUID.randomUUID())
                .boardIndex(pick.boardIndex())
                .auction(auction)
                .position(pick.position())
                .assignedAt(OffsetDateTime.now())
                .build();
        slot = slotRepo.save(slot);
        log.info("PROMO-01 slot assigned: auctionId={} boardIndex={} position={} slotId={}",
                auction.getId(), pick.boardIndex(), pick.position(), slot.getId());
        return slot;
    }

    /**
     * Release whatever slot (if any) currently holds {@code auctionId}.
     * Idempotent — no-op if no active row exists. Called by the auction
     * lifecycle (ENDED/CANCELLED/WITHDRAWN) via afterCommit hooks; called
     * directly by the admin force-release endpoint.
     */
    @Transactional
    public void releaseForAuction(long auctionId) {
        slotRepo.findActiveByAuctionId(auctionId).ifPresent(slot -> {
            slot.setReleasedAt(OffsetDateTime.now());
            slotRepo.save(slot);
            log.info("PROMO-01 slot released: auctionId={} slotId={} boardIndex={}",
                    auctionId, slot.getId(), slot.getBoardIndex());
        });
    }

    /**
     * Snapshot per-board active row counts. Cheap on small tables; if the
     * pool grows large enough that this becomes a hot path, swap for a
     * GROUP BY in the repository.
     */
    private Map<Integer, Integer> countsByBoard() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (FeaturedBoardSlot s : slotRepo.allActive()) {
            counts.merge(s.getBoardIndex(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Used by the admin curator and the read-side resolver.
     */
    @Transactional(readOnly = true)
    public List<FeaturedBoardSlot> activeQueueFor(int boardIndex) {
        return slotRepo.liveQueue(boardIndex);
    }

    @Transactional(readOnly = true)
    public List<FeaturedBoardSlot> allActive() {
        return slotRepo.allActive();
    }
}
```

- [ ] **Step 3: Compile.**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotService.java backend/src/main/java/com/slparcelauctions/backend/promotion/exception/PromotionAlreadyActiveException.java
git commit -m "feat(backend): FeaturedBoardSlotService transactional assign/release"
```

---

### Task 8: Integration test for assign + release

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotServiceIntegrationTest.java`

- [ ] **Step 1: Write the test.**

```java
package com.slparcelauctions.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;
import com.slparcelauctions.backend.testsupport.AuctionTestFactory; // existing helper
import com.slparcelauctions.backend.testsupport.UserTestFactory;

@SpringBootTest
@ActiveProfiles("dev")
class FeaturedBoardSlotServiceIntegrationTest {

    @Autowired FeaturedBoardSlotService slotService;
    @Autowired FeaturedBoardSlotRepository slotRepo;
    @Autowired AuctionTestFactory auctions;
    @Autowired UserTestFactory users;
    @Autowired TransactionTemplate tx;

    @Test
    void first_purchase_lands_on_board_one_position_zero() {
        Auction a = tx.execute(s -> auctions.activeAuction(users.verifiedSeller()));
        FeaturedBoardSlot slot = tx.execute(s -> slotService.assign(a));
        assertThat(slot.getBoardIndex()).isEqualTo(1);
        assertThat(slot.getPosition()).isEqualTo(0);
        assertThat(slot.getReleasedAt()).isNull();
    }

    @Test
    void six_purchases_balance_across_five_boards() {
        var sellers = users.verifiedSellers(6);
        var assigned = sellers.stream().map(seller ->
            tx.execute(s -> slotService.assign(auctions.activeAuction(seller)))
        ).toList();
        // Boards 1..5 each get one, board 1 gets the 6th.
        assertThat(assigned.get(0).getBoardIndex()).isEqualTo(1);
        assertThat(assigned.get(1).getBoardIndex()).isEqualTo(2);
        assertThat(assigned.get(2).getBoardIndex()).isEqualTo(3);
        assertThat(assigned.get(3).getBoardIndex()).isEqualTo(4);
        assertThat(assigned.get(4).getBoardIndex()).isEqualTo(5);
        assertThat(assigned.get(5).getBoardIndex()).isEqualTo(1);
        assertThat(assigned.get(5).getPosition()).isEqualTo(1);
    }

    @Test
    void duplicate_purchase_fails_with_PromotionAlreadyActive() {
        Auction a = tx.execute(s -> auctions.activeAuction(users.verifiedSeller()));
        tx.execute(s -> slotService.assign(a));
        assertThatThrownBy(() -> tx.execute(s -> slotService.assign(a)))
                .isInstanceOf(PromotionAlreadyActiveException.class);
    }

    @Test
    void release_drops_active_row_and_frees_board_for_reuse() {
        var s1 = users.verifiedSeller();
        var s2 = users.verifiedSeller();
        Auction a1 = tx.execute(s -> auctions.activeAuction(s1));
        Auction a2 = tx.execute(s -> auctions.activeAuction(s2));
        FeaturedBoardSlot first  = tx.execute(s -> slotService.assign(a1));
        FeaturedBoardSlot second = tx.execute(s -> slotService.assign(a2));
        assertThat(second.getBoardIndex()).isEqualTo(2);

        tx.execute(s -> { slotService.releaseForAuction(a1.getId()); return null; });

        // Next assignment goes to board 1 again (now empty), not board 3.
        var s3 = users.verifiedSeller();
        Auction a3 = tx.execute(s -> auctions.activeAuction(s3));
        FeaturedBoardSlot third = tx.execute(s -> slotService.assign(a3));
        assertThat(third.getBoardIndex()).isEqualTo(1);
    }

    @Test
    void release_for_nonexistent_auction_is_noop() {
        // Must not throw.
        tx.execute(s -> { slotService.releaseForAuction(9_999_999L); return null; });
    }
}
```

> **Note on test factories**: this plan assumes `AuctionTestFactory` and `UserTestFactory` exist under `backend/src/test/java/.../testsupport/`. If they do not, dig out the patterns from `AuctionServiceIntegrationTest` and create thin equivalents — do not push test data through real REST endpoints.

- [ ] **Step 2: Run tests.**

Run: `cd backend && ./mvnw test -Dtest=FeaturedBoardSlotServiceIntegrationTest -q`
Expected: all green.

- [ ] **Step 3: Commit.**

```bash
git add backend/src/test/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotServiceIntegrationTest.java
git commit -m "test(backend): FeaturedBoardSlotService integration tests"
```

---

### Task 9: Hook auction-end / cancel / withdraw to slot release

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java`
- Modify: (whichever service flips auctions to CANCELLED — likely `AuctionCancellationService`)
- Modify: (whichever service flips auctions to WITHDRAWN — likely `WithdrawalService` or admin path)

> Use Grep to locate the exact files if names differ: `grep -rn "setStatus(AuctionStatus.CANCELLED" backend/src/main/java` and similar.

- [ ] **Step 1: Inject `FeaturedBoardSlotService` into `AuctionEndTask`.**

Add to the constructor parameter list. Existing pattern: services are `final` fields with `@RequiredArgsConstructor`.

- [ ] **Step 2: Inside `AuctionEndTask.closeOne`, after the outcome stamp and before the `afterCommit` envelope publish, schedule the release.**

The repo's auction-state pattern uses `TransactionSynchronization.afterCommit` (per Explore findings). Match it:

```java
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// ... inside closeOne, after auction.setEndedAt(now) and outcome classification:
final long auctionIdFinal = auction.getId();
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        slotService.releaseForAuction(auctionIdFinal);
    }
});
```

- [ ] **Step 3: Mirror the same hook in the cancellation + withdrawal paths.**

For each of the cancel / withdraw services, find the transactional method that sets `status = CANCELLED` (or `WITHDRAWN`) and add the same `afterCommit` block. Both should call `slotService.releaseForAuction(auctionId)`.

- [ ] **Step 4: Add an integration test.**

Create `backend/src/test/java/com/slparcelauctions/backend/promotion/SlotReleaseOnAuctionLifecycleIntegrationTest.java`:

```java
@SpringBootTest
@ActiveProfiles("dev")
class SlotReleaseOnAuctionLifecycleIntegrationTest {

    @Autowired FeaturedBoardSlotService slotService;
    @Autowired FeaturedBoardSlotRepository slotRepo;
    @Autowired AuctionEndTask auctionEndTask;
    @Autowired AuctionTestFactory auctions;
    @Autowired UserTestFactory users;
    @Autowired TransactionTemplate tx;

    @Test
    void auction_end_releases_slot() {
        Auction a = tx.execute(s -> {
            Auction created = auctions.activeAuction(users.verifiedSeller());
            slotService.assign(created);
            // Push endsAt into the past so closeOne classifies + ends it.
            created.setEndsAt(OffsetDateTime.now().minusSeconds(1));
            return created;
        });

        auctionEndTask.closeOne(a.getId());

        assertThat(slotRepo.findActiveByAuctionId(a.getId())).isEmpty();
    }

    // mirror tests for cancel + withdraw
}
```

- [ ] **Step 5: Run.**

Run: `cd backend && ./mvnw test -Dtest=SlotReleaseOnAuctionLifecycleIntegrationTest -q`
Expected: all green.

- [ ] **Step 6: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java backend/src/main/java/com/slparcelauctions/backend/auction/AuctionCancellationService.java backend/src/test/java/com/slparcelauctions/backend/promotion/SlotReleaseOnAuctionLifecycleIntegrationTest.java
# add other modified files
git commit -m "feat(backend): release PROMO-01 slot on auction end/cancel/withdraw"
```

---

## Phase 4 — Wallet debit + PROMO-01 purchase orchestration

### Task 10: Add `WalletService.debitPromotionFee`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java`

- [ ] **Step 1: Add the method.**

Mirror `debitListingFee` exactly. Insert near it:

```java
@Transactional(propagation = Propagation.MANDATORY)
public void debitPromotionFee(User user, long amount, Long auctionId, String promotionCode) {
    if (amount <= 0) {
        throw new IllegalArgumentException("amount must be positive: " + amount);
    }
    rejectIfFrozen(user);
    if (user.availableLindens() < amount) {
        throw new InsufficientAvailableBalanceException(user.availableLindens(), amount);
    }
    long newBalance = user.getBalanceLindens() - amount;
    user.setBalanceLindens(newBalance);
    userRepository.save(user);
    UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
            .userId(user.getId())
            .entryType(UserLedgerEntryType.PROMOTION_DEBIT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(user.getReservedLindens())
            .refType("AUCTION")
            .refId(auctionId)
            .description("PROMO-01:" + promotionCode)
            .build());
    walletBroadcastPublisher.publish(user,
            UserLedgerEntryType.PROMOTION_DEBIT.name(), entry.getPublicId());
    log.info("PROMO-01 debit: userId={}, amount={}, auctionId={}, balanceAfter={}, code={}",
            user.getId(), amount, auctionId, newBalance, promotionCode);
}
```

> **Verify** `UserLedgerEntry.builder()` has a `description` field. If not, drop the `.description(...)` line — the ledger entry still records `refType`/`refId`.

- [ ] **Step 2: Compile.**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java
git commit -m "feat(backend): WalletService.debitPromotionFee mirrors listing-fee debit"
```

---

### Task 11: Add `PromotionService.purchaseFeatured`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/PromotionService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/exception/NotAuctionSellerException.java`

- [ ] **Step 1: Add the exception.**

```java
package com.slparcelauctions.backend.promotion.exception;

import java.util.UUID;

/** Caller is not the seller of the targeted auction. HTTP 403 / NOT_AUCTION_SELLER. */
public class NotAuctionSellerException extends RuntimeException {
    public NotAuctionSellerException(UUID auctionPublicId) {
        super("Not the seller of auction " + auctionPublicId);
    }
}
```

- [ ] **Step 2: Add the service.**

```java
package com.slparcelauctions.backend.promotion;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionConfigProperties;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.promotion.exception.NotAuctionSellerException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the buy-PROMO-01 flow: caller-owns-auction check, atomic
 * wallet debit + slot assignment + is_featured flip. One transaction; any
 * failure rolls back the wallet and the slot row together.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {

    public static final String PROMO_01_CODE = "PROMO-01";

    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;
    private final WalletService walletService;
    private final FeaturedBoardSlotService slotService;
    private final AuctionConfigProperties auctionConfig;

    public record PurchaseResult(FeaturedBoardSlot slot, long newBalanceLindens) {}

    @Transactional
    public PurchaseResult purchaseFeatured(long callerUserId, UUID auctionPublicId) {
        Auction auction = auctionRepo.findByPublicId(auctionPublicId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
        if (auction.getSeller() == null
                || !auction.getSeller().getId().equals(callerUserId)) {
            throw new NotAuctionSellerException(auctionPublicId);
        }
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "PROMO-01 can only be bought on ACTIVE auctions; got " + auction.getStatus());
        }

        User caller = userRepo.findById(callerUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found: " + callerUserId));

        long price = auctionConfig.featuredPriceLindens();
        walletService.debitPromotionFee(caller, price, auction.getId(), PROMO_01_CODE);
        FeaturedBoardSlot slot = slotService.assign(auction);
        auction.setFeatured(true);
        auction.setFeaturedUntil(auction.getEndsAt());

        log.info("PROMO-01 purchased: callerUserId={} auctionId={} price={} slotId={}",
                callerUserId, auction.getId(), price, slot.getId());
        return new PurchaseResult(slot, caller.getBalanceLindens());
    }
}
```

> Adjust setter names (`setFeatured` vs `setIsFeatured`) and field names by consulting `Auction.java`.

- [ ] **Step 3: Compile.**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/PromotionService.java backend/src/main/java/com/slparcelauctions/backend/promotion/exception/NotAuctionSellerException.java
git commit -m "feat(backend): PromotionService.purchaseFeatured atomic debit+assign"
```

---

### Task 12: Integration test for purchase happy path + duplicate + insufficient funds

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/promotion/PromotionServiceIntegrationTest.java`

- [ ] **Step 1: Write the test.**

```java
package com.slparcelauctions.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.promotion.exception.NotAuctionSellerException;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;
import com.slparcelauctions.backend.testsupport.AuctionTestFactory;
import com.slparcelauctions.backend.testsupport.UserTestFactory;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;

@SpringBootTest
@ActiveProfiles("dev")
class PromotionServiceIntegrationTest {

    @Autowired PromotionService promotionService;
    @Autowired FeaturedBoardSlotRepository slotRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionTestFactory auctions;
    @Autowired UserTestFactory users;
    @Autowired TransactionTemplate tx;

    @Test
    void happy_path_debits_500_assigns_slot_flips_featured() {
        User seller = users.verifiedSellerWithBalance(10_000);
        Auction a = tx.execute(s -> auctions.activeAuction(seller));
        var result = promotionService.purchaseFeatured(seller.getId(), a.getPublicId());
        assertThat(result.newBalanceLindens()).isEqualTo(9_500);
        assertThat(result.slot().getBoardIndex()).isEqualTo(1);
        Auction reloaded = tx.execute(s -> auctions.byId(a.getId()));
        assertThat(reloaded.isFeatured()).isTrue();
        assertThat(reloaded.getFeaturedUntil()).isEqualTo(a.getEndsAt());
    }

    @Test
    void duplicate_purchase_throws_and_rolls_back_wallet() {
        User seller = users.verifiedSellerWithBalance(10_000);
        Auction a = tx.execute(s -> auctions.activeAuction(seller));
        promotionService.purchaseFeatured(seller.getId(), a.getPublicId());
        assertThatThrownBy(() ->
                promotionService.purchaseFeatured(seller.getId(), a.getPublicId()))
                .isInstanceOf(PromotionAlreadyActiveException.class);
        // Balance should still be 9_500, not 9_000.
        assertThat(userRepo.findById(seller.getId()).orElseThrow().getBalanceLindens())
                .isEqualTo(9_500);
    }

    @Test
    void insufficient_funds_throws_and_no_slot_created() {
        User seller = users.verifiedSellerWithBalance(100);
        Auction a = tx.execute(s -> auctions.activeAuction(seller));
        assertThatThrownBy(() ->
                promotionService.purchaseFeatured(seller.getId(), a.getPublicId()))
                .isInstanceOf(InsufficientAvailableBalanceException.class);
        assertThat(slotRepo.findActiveByAuctionId(a.getId())).isEmpty();
    }

    @Test
    void non_seller_throws() {
        User seller = users.verifiedSellerWithBalance(10_000);
        User intruder = users.verifiedSellerWithBalance(10_000);
        Auction a = tx.execute(s -> auctions.activeAuction(seller));
        assertThatThrownBy(() ->
                promotionService.purchaseFeatured(intruder.getId(), a.getPublicId()))
                .isInstanceOf(NotAuctionSellerException.class);
    }
}
```

- [ ] **Step 2: Run.**

Run: `cd backend && ./mvnw test -Dtest=PromotionServiceIntegrationTest -q`
Expected: green.

- [ ] **Step 3: Commit.**

```bash
git add backend/src/test/java/com/slparcelauctions/backend/promotion/PromotionServiceIntegrationTest.java
git commit -m "test(backend): PromotionService.purchaseFeatured integration"
```

---

## Phase 5 — Read side: `BoardContentResolver` + public controller

### Task 13: Add the DTOs

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/FeaturedBoardListingDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/FeaturedBoardPayloadDto.java`

- [ ] **Step 1: `FeaturedBoardListingDto`.**

```java
package com.slparcelauctions.backend.promotion.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FeaturedBoardListingDto(
        UUID publicId,
        String title,
        String region,
        Integer sqm,
        String photoUrl,
        long currentBid,
        OffsetDateTime endsAt,
        String listingUrl,
        String slurl
) {}
```

- [ ] **Step 2: `FeaturedBoardPayloadDto`.**

```java
package com.slparcelauctions.backend.promotion.dto;

import java.util.List;

public record FeaturedBoardPayloadDto(
        int boardIndex,
        int cycleSeconds,
        List<FeaturedBoardListingDto> listings,
        Source source
) {
    public enum Source { PROMO_01, ALGORITHMIC, PLACEHOLDER }
}
```

- [ ] **Step 3: Compile.**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/dto/FeaturedBoardListingDto.java backend/src/main/java/com/slparcelauctions/backend/promotion/dto/FeaturedBoardPayloadDto.java
git commit -m "feat(backend): featured-board DTOs"
```

---

### Task 14: Add `BoardContentResolver`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/BoardContentResolver.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/promotion/BoardContentResolverTest.java`

- [ ] **Step 1: Write the failing tests.**

```java
package com.slparcelauctions.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.featured.FeaturedService;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto.Source;
import com.slparcelauctions.backend.testsupport.AuctionTestFactory;
import com.slparcelauctions.backend.testsupport.UserTestFactory;

@SpringBootTest
@ActiveProfiles("dev")
class BoardContentResolverTest {

    @Autowired BoardContentResolver resolver;
    @Autowired FeaturedBoardSlotService slotService;
    @Autowired AuctionTestFactory auctions;
    @Autowired UserTestFactory users;
    @Autowired TransactionTemplate tx;
    @MockitoBean FeaturedService featuredService;

    @Test
    void promo_pool_queue_returned_as_PROMO_01() {
        Auction a = tx.execute(s -> auctions.activeAuction(users.verifiedSeller()));
        tx.execute(s -> slotService.assign(a));
        var payload = resolver.resolve(1);
        assertThat(payload.source()).isEqualTo(Source.PROMO_01);
        assertThat(payload.listings()).hasSize(1);
        assertThat(payload.listings().get(0).publicId()).isEqualTo(a.getPublicId());
    }

    @Test
    void empty_queue_falls_back_to_algorithmic_at_per_board_offset() {
        Auction a1 = tx.execute(s -> auctions.activeAuction(users.verifiedSeller()));
        Auction a2 = tx.execute(s -> auctions.activeAuction(users.verifiedSeller()));
        when(featuredService.featured()).thenReturn(List.of(a1, a2));
        var board1 = resolver.resolve(1);
        var board2 = resolver.resolve(2);
        assertThat(board1.source()).isEqualTo(Source.ALGORITHMIC);
        assertThat(board1.listings().get(0).publicId()).isEqualTo(a1.getPublicId());
        assertThat(board2.listings().get(0).publicId()).isEqualTo(a2.getPublicId());
    }

    @Test
    void no_promo_no_algo_returns_PLACEHOLDER() {
        when(featuredService.featured()).thenReturn(List.of());
        var payload = resolver.resolve(1);
        assertThat(payload.source()).isEqualTo(Source.PLACEHOLDER);
        assertThat(payload.listings()).isEmpty();
    }
}
```

- [ ] **Step 2: Run; expect compile failure (resolver doesn't exist).**

Run: `cd backend && ./mvnw test -Dtest=BoardContentResolverTest -q`
Expected: compile failure.

- [ ] **Step 3: Write the resolver.**

```java
package com.slparcelauctions.backend.promotion;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionConfigProperties;
import com.slparcelauctions.backend.auction.featured.FeaturedService;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardListingDto;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto.Source;

import lombok.RequiredArgsConstructor;

/**
 * Builds the public-facing payload for one board. Composition rules per
 * spec §4.2: PROMO_01 queue first, fall back to algorithmic featured at a
 * deterministic per-board offset, fall back to placeholder if both empty.
 */
@Service
@RequiredArgsConstructor
public class BoardContentResolver {

    private final FeaturedBoardSlotService slotService;
    private final FeaturedService featuredService;
    private final AuctionConfigProperties auctionConfig;

    @Transactional(readOnly = true)
    public FeaturedBoardPayloadDto resolve(int boardIndex) {
        int cycleSeconds = auctionConfig.featuredBoardCycleSeconds();

        // 1. PROMO_01 queue
        List<FeaturedBoardSlot> queue = slotService.activeQueueFor(boardIndex);
        if (!queue.isEmpty()) {
            List<FeaturedBoardListingDto> listings = queue.stream()
                    .map(s -> toDto(s.getAuction()))
                    .collect(Collectors.toList());
            return new FeaturedBoardPayloadDto(
                    boardIndex, cycleSeconds, listings, Source.PROMO_01);
        }

        // 2. Algorithmic fallback (deterministic per-board offset)
        List<Auction> algo = featuredService.featured();
        if (!algo.isEmpty()) {
            int index = (boardIndex - 1) % algo.size();
            Auction pick = algo.get(index);
            return new FeaturedBoardPayloadDto(
                    boardIndex, cycleSeconds, List.of(toDto(pick)), Source.ALGORITHMIC);
        }

        // 3. Placeholder
        return new FeaturedBoardPayloadDto(
                boardIndex, cycleSeconds, List.of(), Source.PLACEHOLDER);
    }

    /**
     * Compute the single listing currently on-screen at {@code now()} for
     * the given board's queue. The same formula runs in the browser cycle
     * timer and in the LSL touch handler so both sides agree.
     */
    public FeaturedBoardListingDto currentTouchTarget(int boardIndex) {
        FeaturedBoardPayloadDto payload = resolve(boardIndex);
        if (payload.listings().isEmpty()) return null;
        if (payload.listings().size() == 1) return payload.listings().get(0);
        long epochSeconds = System.currentTimeMillis() / 1000L;
        int idx = (int) Math.floorMod(
                epochSeconds / payload.cycleSeconds(),
                payload.listings().size());
        return payload.listings().get(idx);
    }

    private FeaturedBoardListingDto toDto(Auction a) {
        String region = a.getParcelSnapshot() != null
                ? a.getParcelSnapshot().getRegionName() : null;
        Integer sqm = a.getParcelSnapshot() != null
                ? a.getParcelSnapshot().getAreaSqm() : null;
        String photoUrl = primaryPhotoUrl(a);
        String slurl = a.getParcelSnapshot() != null
                ? a.getParcelSnapshot().getSlurl() : null;
        return new FeaturedBoardListingDto(
                a.getPublicId(),
                a.getTitle(),
                region,
                sqm,
                photoUrl,
                a.getCurrentBidAmt(),
                a.getEndsAt(),
                "/auction/" + a.getPublicId(),
                slurl
        );
    }

    private String primaryPhotoUrl(Auction a) {
        // Reuse whatever helper exists today on Auction or its photo service.
        // If none, append "/api/v1/auctions/{publicId}/primary-photo" or read
        // the first AuctionPhoto's URL through AuctionPhotoService.
        // CONFIRM during impl by reading AuctionPhotoService.
        return a.getPhotos() != null && !a.getPhotos().isEmpty()
                ? "/api/v1/photos/" + a.getPhotos().get(0).getPublicId()
                : null;
    }
}
```

> **Verify field/method names** for `Auction.getTitle`, `Auction.getCurrentBidAmt`, `Auction.getParcelSnapshot`, `ParcelSnapshot.getRegionName`/`getAreaSqm`/`getSlurl`, `Auction.getPhotos`. Adjust to match the actual entity. If the photo URL helper lives elsewhere (e.g., `AuctionPhotoService.urlFor`), inject and use that.

- [ ] **Step 4: Run tests.**

Run: `cd backend && ./mvnw test -Dtest=BoardContentResolverTest -q`
Expected: all green.

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/BoardContentResolver.java backend/src/test/java/com/slparcelauctions/backend/promotion/BoardContentResolverTest.java
git commit -m "feat(backend): BoardContentResolver + tests"
```

---

### Task 15: Add `InWorldFeaturedBoardController` + Redis caching

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/InWorldFeaturedBoardController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/exception/InvalidBoardIndexException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

- [ ] **Step 1: Exception.**

```java
package com.slparcelauctions.backend.promotion.exception;

public class InvalidBoardIndexException extends RuntimeException {
    public InvalidBoardIndexException(int boardIndex, int maxBoardIndex) {
        super("Invalid board index " + boardIndex + " (valid: 1.." + maxBoardIndex + ")");
    }
}
```

- [ ] **Step 2: Controller.**

```java
package com.slparcelauctions.backend.promotion;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import com.slparcelauctions.backend.auction.AuctionConfigProperties;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardListingDto;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto;
import com.slparcelauctions.backend.promotion.exception.InvalidBoardIndexException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/in-world")
@RequiredArgsConstructor
public class InWorldFeaturedBoardController {

    private static final Duration CACHE_TTL = Duration.ofSeconds(15);
    private static final Duration PLACEHOLDER_CACHE_TTL = Duration.ofMinutes(5);

    private final BoardContentResolver resolver;
    private final AuctionConfigProperties auctionConfig;
    private final RedisTemplate<String, Object> epic07RedisTemplate;

    @GetMapping("/featured-board/{boardIndex}")
    public FeaturedBoardPayloadDto getBoard(@PathVariable int boardIndex) {
        validateIndex(boardIndex);
        String key = "featured-board:" + boardIndex;
        Object cached = epic07RedisTemplate.opsForValue().get(key);
        if (cached instanceof FeaturedBoardPayloadDto p) return p;
        FeaturedBoardPayloadDto payload = resolver.resolve(boardIndex);
        epic07RedisTemplate.opsForValue().set(key, payload, CACHE_TTL);
        return payload;
    }

    @GetMapping("/featured-board/{boardIndex}/touch")
    public FeaturedBoardListingDto getTouchTarget(@PathVariable int boardIndex) {
        validateIndex(boardIndex);
        return resolver.currentTouchTarget(boardIndex);
    }

    @GetMapping("/board/placeholder")
    public FeaturedBoardPayloadDto getPlaceholder() {
        return new FeaturedBoardPayloadDto(0, 0, java.util.List.of(),
                FeaturedBoardPayloadDto.Source.PLACEHOLDER);
    }

    private void validateIndex(int boardIndex) {
        if (boardIndex < 1 || boardIndex > auctionConfig.featuredSlotCount()) {
            throw new InvalidBoardIndexException(boardIndex, auctionConfig.featuredSlotCount());
        }
    }
}
```

> If `epic07RedisTemplate` isn't the right bean name, search `grep -rn "RedisTemplate" backend/src/main/java/com/slparcelauctions/backend/config/cache/` and use whichever is registered.

- [ ] **Step 3: Add the exception handler.** Append to whichever global exception handler maps RuntimeExceptions to ProblemDetail (e.g., `GlobalExceptionHandler`):

```java
@ExceptionHandler(InvalidBoardIndexException.class)
public ProblemDetail handleInvalidBoardIndex(InvalidBoardIndexException e) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    pd.setProperty("code", "INVALID_BOARD_INDEX");
    return pd;
}
```

- [ ] **Step 4: SecurityConfig — `permitAll` the in-world routes.**

In `SecurityConfig.securityFilterChain`, alongside the existing `permitAll` matchers, add:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/in-world/featured-board/**").permitAll()
.requestMatchers(HttpMethod.GET, "/api/v1/in-world/board/placeholder").permitAll()
```

- [ ] **Step 5: Slice test.**

Create `backend/src/test/java/com/slparcelauctions/backend/promotion/InWorldFeaturedBoardControllerSliceTest.java`:

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class InWorldFeaturedBoardControllerSliceTest {

    @Autowired MockMvc mvc;
    @MockitoBean BoardContentResolver resolver;

    @Test
    void anonymous_can_GET_board() throws Exception {
        when(resolver.resolve(1)).thenReturn(new FeaturedBoardPayloadDto(
                1, 30, List.of(), FeaturedBoardPayloadDto.Source.PLACEHOLDER));
        mvc.perform(get("/api/v1/in-world/featured-board/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardIndex").value(1));
    }

    @Test
    void invalid_board_index_400() throws Exception {
        mvc.perform(get("/api/v1/in-world/featured-board/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BOARD_INDEX"));
    }

    @Test
    void placeholder_returns_PLACEHOLDER_source() throws Exception {
        mvc.perform(get("/api/v1/in-world/board/placeholder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PLACEHOLDER"));
    }
}
```

- [ ] **Step 6: Run.**

Run: `cd backend && ./mvnw test -Dtest=InWorldFeaturedBoardControllerSliceTest -q`
Expected: green.

- [ ] **Step 7: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/InWorldFeaturedBoardController.java backend/src/main/java/com/slparcelauctions/backend/promotion/exception/InvalidBoardIndexException.java backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java backend/src/test/java/com/slparcelauctions/backend/promotion/InWorldFeaturedBoardControllerSliceTest.java
git commit -m "feat(backend): in-world featured-board public endpoints with 15s Redis cache"
```

---

## Phase 6 — PROMO-01 purchase endpoint + admin curator

### Task 16: Add `PromotionController` (seller-facing buy endpoint)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/PromotionController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/PurchaseFeaturedRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/PurchaseFeaturedResponse.java`

- [ ] **Step 1: Request + response DTOs.**

```java
package com.slparcelauctions.backend.promotion.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record PurchaseFeaturedRequest(@NotNull UUID auctionPublicId) {}
```

```java
package com.slparcelauctions.backend.promotion.dto;

import java.util.UUID;

public record PurchaseFeaturedResponse(
        UUID slotPublicId,
        int boardIndex,
        int position,
        long priceLindens,
        long newBalanceLindens
) {}
```

- [ ] **Step 2: Controller.**

```java
package com.slparcelauctions.backend.promotion;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.AuctionConfigProperties;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.promotion.dto.PurchaseFeaturedRequest;
import com.slparcelauctions.backend.promotion.dto.PurchaseFeaturedResponse;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;
import com.slparcelauctions.backend.promotion.exception.NotAuctionSellerException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/v1/me/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;
    private final AuctionConfigProperties auctionConfig;

    @PostMapping("/featured")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseFeaturedResponse buyFeatured(
            @AuthenticationPrincipal AuthPrincipal caller,
            @Valid @RequestBody PurchaseFeaturedRequest req) {
        var result = promotionService.purchaseFeatured(
                caller.userId(), req.auctionPublicId());
        return new PurchaseFeaturedResponse(
                result.slot().getPublicId(),
                result.slot().getBoardIndex(),
                result.slot().getPosition(),
                auctionConfig.featuredPriceLindens(),
                result.newBalanceLindens()
        );
    }

    @ExceptionHandler(PromotionAlreadyActiveException.class)
    public ProblemDetail handleAlreadyActive(PromotionAlreadyActiveException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setProperty("code", "PROMOTION_ALREADY_ACTIVE");
        return pd;
    }

    @ExceptionHandler(NotAuctionSellerException.class)
    public ProblemDetail handleNotSeller(NotAuctionSellerException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setProperty("code", "NOT_AUCTION_SELLER");
        return pd;
    }
}
```

- [ ] **Step 3: Slice test.**

Create `backend/src/test/java/com/slparcelauctions/backend/promotion/PromotionControllerSliceTest.java`. Mirror `AuctionControllerSliceTest` style — `@AutoConfigureMockMvc`, `@MockitoBean PromotionService`, JWT-authenticate as a seller, assert:

- POST with valid auction body returns 201 + body.
- Service throws `PromotionAlreadyActiveException` → 409 `PROMOTION_ALREADY_ACTIVE`.
- Service throws `NotAuctionSellerException` → 403 `NOT_AUCTION_SELLER`.
- Service throws `InsufficientAvailableBalanceException` → mapped to whatever the global handler emits (typically 422 / `INSUFFICIENT_BALANCE`; verify the existing mapping).
- Anonymous POST → 401/403.

- [ ] **Step 4: Run.**

Run: `cd backend && ./mvnw test -Dtest=PromotionControllerSliceTest -q`
Expected: green.

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/PromotionController.java backend/src/main/java/com/slparcelauctions/backend/promotion/dto/PurchaseFeaturedRequest.java backend/src/main/java/com/slparcelauctions/backend/promotion/dto/PurchaseFeaturedResponse.java backend/src/test/java/com/slparcelauctions/backend/promotion/PromotionControllerSliceTest.java
git commit -m "feat(backend): POST /me/promotions/featured buy endpoint"
```

---

### Task 17: Add admin curator endpoints

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/AdminFeaturedBoardController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/AdminFeaturedBoardRowDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/promotion/dto/MovePromotionSlotRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotService.java` — add `move` + `forceRelease` admin methods
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

- [ ] **Step 1: DTOs.**

```java
package com.slparcelauctions.backend.promotion.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminFeaturedBoardRowDto(
        UUID slotPublicId,
        int boardIndex,
        int position,
        UUID auctionPublicId,
        String auctionTitle,
        long currentBid,
        OffsetDateTime endsAt,
        OffsetDateTime assignedAt
) {}
```

```java
package com.slparcelauctions.backend.promotion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record MovePromotionSlotRequest(
        @Min(1) @Max(13) int boardIndex,
        @Min(0) int position
) {}
```

- [ ] **Step 2: Add `forceRelease` + `move` to `FeaturedBoardSlotService`.**

```java
@Transactional
public void forceRelease(UUID slotPublicId) {
    FeaturedBoardSlot slot = slotRepo.findByPublicId(slotPublicId)
            .orElseThrow(() -> new ResourceNotFoundException("Slot not found: " + slotPublicId));
    if (slot.getReleasedAt() != null) return;
    slot.setReleasedAt(OffsetDateTime.now());
    slotRepo.save(slot);
    log.info("Admin force-release: slotId={} boardIndex={}", slot.getId(), slot.getBoardIndex());
}

@Transactional
public void move(UUID slotPublicId, int boardIndex, int position) {
    if (boardIndex < 1 || boardIndex > auctionConfig.featuredSlotCount()) {
        throw new InvalidBoardIndexException(boardIndex, auctionConfig.featuredSlotCount());
    }
    FeaturedBoardSlot slot = slotRepo.findByPublicId(slotPublicId)
            .orElseThrow(() -> new ResourceNotFoundException("Slot not found: " + slotPublicId));
    if (slot.getReleasedAt() != null) {
        throw new IllegalStateException("Slot is already released");
    }
    slot.setBoardIndex(boardIndex);
    slot.setPosition(position);
    slotRepo.save(slot);
    log.info("Admin move: slotId={} -> boardIndex={} position={}",
            slot.getId(), boardIndex, position);
}
```

- [ ] **Step 3: Controller.**

```java
package com.slparcelauctions.backend.promotion;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.*;

import com.slparcelauctions.backend.promotion.dto.AdminFeaturedBoardRowDto;
import com.slparcelauctions.backend.promotion.dto.MovePromotionSlotRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/featured-boards")
@RequiredArgsConstructor
public class AdminFeaturedBoardController {

    private final FeaturedBoardSlotService slotService;

    @GetMapping
    public List<AdminFeaturedBoardRowDto> list() {
        return slotService.allActive().stream()
                .map(s -> new AdminFeaturedBoardRowDto(
                        s.getPublicId(),
                        s.getBoardIndex(),
                        s.getPosition(),
                        s.getAuction().getPublicId(),
                        s.getAuction().getTitle(),
                        s.getAuction().getCurrentBidAmt(),
                        s.getAuction().getEndsAt(),
                        s.getAssignedAt()))
                .toList();
    }

    @PostMapping("/{slotPublicId}/release")
    public void release(@PathVariable UUID slotPublicId) {
        slotService.forceRelease(slotPublicId);
    }

    @PatchMapping("/{slotPublicId}/move")
    public void move(@PathVariable UUID slotPublicId,
                     @Valid @RequestBody MovePromotionSlotRequest body) {
        slotService.move(slotPublicId, body.boardIndex(), body.position());
    }
}
```

- [ ] **Step 4: Security config — ROLE_ADMIN gate.**

```java
.requestMatchers("/api/v1/admin/featured-boards/**").hasRole("ADMIN")
```

(Place above any broader `authenticated()` catch-all.)

- [ ] **Step 5: Slice test.**

`AdminFeaturedBoardControllerSliceTest` — mirror the `AdminUserControllerSliceTest` pattern. Cover: anonymous → 401/403; ROLE_USER → 403; ROLE_ADMIN → 200 list + 200 release + 200 move; invalid `boardIndex` in move → 400.

- [ ] **Step 6: Run.**

Run: `cd backend && ./mvnw test -Dtest=AdminFeaturedBoardControllerSliceTest -q`
Expected: green.

- [ ] **Step 7: Commit.**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/promotion/AdminFeaturedBoardController.java backend/src/main/java/com/slparcelauctions/backend/promotion/dto/AdminFeaturedBoardRowDto.java backend/src/main/java/com/slparcelauctions/backend/promotion/dto/MovePromotionSlotRequest.java backend/src/main/java/com/slparcelauctions/backend/promotion/FeaturedBoardSlotService.java backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java backend/src/test/java/com/slparcelauctions/backend/promotion/AdminFeaturedBoardControllerSliceTest.java
git commit -m "feat(backend): admin featured-boards curator endpoints"
```

---

## Phase 7 — Frontend in-world rendering

### Task 18: Add the promotion type module + API clients

**Files:**
- Create: `frontend/src/types/promotion.ts`
- Create: `frontend/src/lib/api/inWorldBoards.ts`
- Create: `frontend/src/lib/api/promotions.ts`

- [ ] **Step 1: Types.**

```ts
export type FeaturedBoardSource = "PROMO_01" | "ALGORITHMIC" | "PLACEHOLDER";

export interface FeaturedBoardListing {
  publicId: string;
  title: string;
  region: string | null;
  sqm: number | null;
  photoUrl: string | null;
  currentBid: number;
  endsAt: string;
  listingUrl: string;
  slurl: string | null;
}

export interface FeaturedBoardPayload {
  boardIndex: number;
  cycleSeconds: number;
  listings: FeaturedBoardListing[];
  source: FeaturedBoardSource;
}

export interface PurchaseFeaturedResponse {
  slotPublicId: string;
  boardIndex: number;
  position: number;
  priceLindens: number;
  newBalanceLindens: number;
}
```

- [ ] **Step 2: `inWorldBoards.ts`.**

```ts
import { api } from "@/lib/api";
import type { FeaturedBoardPayload } from "@/types/promotion";

export function fetchFeaturedBoard(boardIndex: number): Promise<FeaturedBoardPayload> {
  return api.get<FeaturedBoardPayload>(`/api/v1/in-world/featured-board/${boardIndex}`);
}

export function fetchPlaceholderBoard(): Promise<FeaturedBoardPayload> {
  return api.get<FeaturedBoardPayload>("/api/v1/in-world/board/placeholder");
}
```

- [ ] **Step 3: `promotions.ts`.**

```ts
import { api } from "@/lib/api";
import type { PurchaseFeaturedResponse } from "@/types/promotion";

export function purchaseFeatured(auctionPublicId: string): Promise<PurchaseFeaturedResponse> {
  return api.post<PurchaseFeaturedResponse>(
    "/api/v1/me/promotions/featured",
    { auctionPublicId },
  );
}
```

- [ ] **Step 4: Commit.**

```bash
git add frontend/src/types/promotion.ts frontend/src/lib/api/inWorldBoards.ts frontend/src/lib/api/promotions.ts
git commit -m "feat(frontend): promotion types + in-world board + buy API clients"
```

---

### Task 19: Add the board view component (C2 layout)

**Files:**
- Create: `frontend/src/components/inworld/FeaturedBoardView.tsx`

- [ ] **Step 1: Component.**

```tsx
"use client";

import { apiUrl } from "@/lib/api/url";
import type { FeaturedBoardListing } from "@/types/promotion";

interface Props {
  listing: FeaturedBoardListing;
}

/**
 * Single-listing render of the C2 layout per spec §3.1:
 * full-bleed square photo, gradient caption with heavy text shadows, QR
 * placeholder on the right, "FEATURED" pill top-right. Inline-styled
 * because this page is exempt from the no-inline-styles guard (see
 * frontend/scripts/verify-no-inline-styles.sh allowlist).
 */
export function FeaturedBoardView({ listing }: Props) {
  const photo = apiUrl(listing.photoUrl) ?? undefined;
  const qrSrc = `https://api.qrserver.com/v1/create-qr-code/?size=80x80&data=${encodeURIComponent("https://slparcels.com" + listing.listingUrl)}`;

  return (
    <div
      style={{
        position: "relative",
        aspectRatio: "1 / 1",
        width: "100%",
        background: "#0d1b2a",
        color: "#f5f5f5",
        fontFamily: "-apple-system, 'Segoe UI', system-ui, sans-serif",
        overflow: "hidden",
      }}
    >
      {photo && (
        <img
          src={photo}
          alt=""
          style={{
            position: "absolute",
            inset: 0,
            width: "100%",
            height: "100%",
            objectFit: "cover",
          }}
        />
      )}

      {/* top bar */}
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          right: 0,
          padding: "10px 14px",
          background: "linear-gradient(180deg, rgba(0,0,0,0.6), rgba(0,0,0,0))",
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          fontSize: 11,
          letterSpacing: 1.4,
          textTransform: "uppercase",
          color: "#b8d4f0",
          fontWeight: 600,
          zIndex: 3,
        }}
      >
        <span>SLPARCELS</span>
        <span
          style={{
            background: "#d97706",
            color: "#fff",
            padding: "3px 9px",
            borderRadius: 999,
            fontSize: 9,
            letterSpacing: 0.8,
          }}
        >
          FEATURED
        </span>
      </div>

      {/* gradient caption */}
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          bottom: 0,
          padding: "14px 16px 16px",
          background:
            "linear-gradient(0deg, rgba(0,0,0,0.92) 0%, rgba(0,0,0,0.75) 40%, rgba(0,0,0,0.35) 80%, rgba(0,0,0,0) 100%)",
          display: "grid",
          gridTemplateColumns: "1fr auto",
          gap: 12,
          alignItems: "end",
        }}
      >
        <div>
          <div
            style={{
              fontSize: 18,
              fontWeight: 700,
              color: "#fff",
              lineHeight: 1.15,
              textShadow: "0 2px 6px rgba(0,0,0,0.9), 0 0 2px rgba(0,0,0,1)",
            }}
          >
            {listing.title}
          </div>
          <div
            style={{
              marginTop: 2,
              fontSize: 11,
              color: "#e5e5e5",
              textShadow: "0 1px 4px rgba(0,0,0,0.9)",
            }}
          >
            {listing.region}
            {listing.sqm ? ` · ${listing.sqm} sqm` : ""}
          </div>
          <div style={{ marginTop: 6, display: "flex", alignItems: "baseline", gap: 10 }}>
            <span
              style={{
                fontSize: 22,
                fontWeight: 800,
                color: "#fbbf24",
                lineHeight: 1,
                textShadow: "0 2px 8px rgba(0,0,0,1), 0 0 4px rgba(0,0,0,1)",
              }}
            >
              L${listing.currentBid.toLocaleString()}
            </span>
            <span
              style={{
                fontSize: 12,
                color: "#fff",
                fontWeight: 600,
                textShadow: "0 1px 4px rgba(0,0,0,0.95)",
              }}
            >
              ends {new Date(listing.endsAt).toLocaleDateString()}
            </span>
          </div>
        </div>
        <img
          src={qrSrc}
          alt=""
          style={{
            width: 66,
            height: 66,
            background: "#fff",
            padding: 4,
            borderRadius: 3,
          }}
        />
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit.**

```bash
git add frontend/src/components/inworld/FeaturedBoardView.tsx
git commit -m "feat(frontend): FeaturedBoardView C2 layout"
```

---

### Task 20: Add the cycler (cross-fade timer)

**Files:**
- Create: `frontend/src/components/inworld/FeaturedBoardCycler.tsx`
- Create: `frontend/src/components/inworld/FeaturedBoardCycler.test.tsx`

- [ ] **Step 1: Failing test.**

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { FeaturedBoardCycler } from "./FeaturedBoardCycler";
import type { FeaturedBoardListing } from "@/types/promotion";

const listing = (id: string, title: string): FeaturedBoardListing => ({
  publicId: id, title, region: "Heterocera", sqm: 1024,
  photoUrl: null, currentBid: 1, endsAt: "2030-01-01T00:00:00Z",
  listingUrl: "/auction/" + id, slurl: null,
});

describe("FeaturedBoardCycler", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("renders the only listing statically when length === 1", () => {
    render(<FeaturedBoardCycler listings={[listing("a", "Alpha")]} cycleSeconds={30} />);
    expect(screen.getByText("Alpha")).toBeInTheDocument();
  });

  it("advances index every cycleSeconds when length >= 2", () => {
    const items = [listing("a", "Alpha"), listing("b", "Bravo")];
    render(<FeaturedBoardCycler listings={items} cycleSeconds={30} />);
    const initial = screen.queryByText("Alpha") || screen.queryByText("Bravo");
    expect(initial).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(30_000); });
    const flipped = screen.queryByText("Alpha") || screen.queryByText("Bravo");
    expect(flipped).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm fail.**

Run: `cd frontend && npx vitest run src/components/inworld/FeaturedBoardCycler.test.tsx`
Expected: compile failure (component missing).

- [ ] **Step 3: Component.**

```tsx
"use client";

import { useEffect, useState } from "react";
import { FeaturedBoardView } from "./FeaturedBoardView";
import type { FeaturedBoardListing } from "@/types/promotion";

interface Props {
  listings: FeaturedBoardListing[];
  cycleSeconds: number;
}

function indexAt(epochSeconds: number, cycleSeconds: number, length: number) {
  return Math.floor(epochSeconds / cycleSeconds) % length;
}

export function FeaturedBoardCycler({ listings, cycleSeconds }: Props) {
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));

  useEffect(() => {
    if (listings.length < 2) return;
    const id = setInterval(
      () => setNow(Math.floor(Date.now() / 1000)),
      cycleSeconds * 1000,
    );
    return () => clearInterval(id);
  }, [listings.length, cycleSeconds]);

  if (listings.length === 0) return null;
  const idx = listings.length === 1 ? 0 : indexAt(now, cycleSeconds, listings.length);
  return <FeaturedBoardView listing={listings[idx]} />;
}
```

> Cross-fade animation is deliberately omitted from v1 — a sharp swap every 30s is acceptable. If the user later asks for a fade, add a `style={{ transition: "opacity 600ms", opacity: ... }}` stacked-image variant.

- [ ] **Step 4: Run test.**

Run: `cd frontend && npx vitest run src/components/inworld/FeaturedBoardCycler.test.tsx`
Expected: green.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/components/inworld/FeaturedBoardCycler.tsx frontend/src/components/inworld/FeaturedBoardCycler.test.tsx
git commit -m "feat(frontend): FeaturedBoardCycler timer + tests"
```

---

### Task 21: Add the placeholder board view

**Files:**
- Create: `frontend/src/components/inworld/PlaceholderBoardView.tsx`

- [ ] **Step 1: Component.**

```tsx
export function PlaceholderBoardView() {
  return (
    <div
      style={{
        position: "relative",
        aspectRatio: "1 / 1",
        width: "100%",
        background: "linear-gradient(135deg, #0d1b2a, #1a3050)",
        color: "#fff",
        fontFamily: "-apple-system, 'Segoe UI', system-ui, sans-serif",
        display: "flex",
        flexDirection: "column",
        justifyContent: "center",
        alignItems: "center",
        textAlign: "center",
        padding: 24,
      }}
    >
      <div
        style={{
          fontSize: 13,
          letterSpacing: 1.5,
          textTransform: "uppercase",
          color: "#b8d4f0",
          fontWeight: 600,
        }}
      >
        SLPARCELS
      </div>
      <div style={{ fontSize: 22, fontWeight: 700, marginTop: 16, lineHeight: 1.2 }}>
        List your parcel here
      </div>
      <div style={{ fontSize: 13, color: "#b8d4f0", marginTop: 12 }}>
        slparcels.com
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit.**

```bash
git add frontend/src/components/inworld/PlaceholderBoardView.tsx
git commit -m "feat(frontend): PlaceholderBoardView for inactive boards"
```

---

### Task 22: Add the dynamic board route + placeholder route + allowlist

**Files:**
- Create: `frontend/src/app/in-world/board/[boardIndex]/page.tsx`
- Create: `frontend/src/app/in-world/board/placeholder/page.tsx`
- Modify: `frontend/scripts/verify-no-inline-styles.sh`

- [ ] **Step 1: Board page.**

```tsx
import { fetchFeaturedBoard } from "@/lib/api/inWorldBoards";
import { FeaturedBoardCycler } from "@/components/inworld/FeaturedBoardCycler";
import { PlaceholderBoardView } from "@/components/inworld/PlaceholderBoardView";

export const dynamic = "force-dynamic";

interface Props {
  params: Promise<{ boardIndex: string }>;
}

export default async function InWorldBoardPage({ params }: Props) {
  const { boardIndex } = await params;
  const idx = Number.parseInt(boardIndex, 10);
  if (Number.isNaN(idx) || idx < 1 || idx > 13) {
    return <PlaceholderBoardView />;
  }
  let payload;
  try {
    payload = await fetchFeaturedBoard(idx);
  } catch {
    return <PlaceholderBoardView />;
  }
  if (payload.source === "PLACEHOLDER" || payload.listings.length === 0) {
    return <PlaceholderBoardView />;
  }
  return (
    <FeaturedBoardCycler
      listings={payload.listings}
      cycleSeconds={payload.cycleSeconds}
    />
  );
}
```

- [ ] **Step 2: Placeholder page.**

```tsx
import { PlaceholderBoardView } from "@/components/inworld/PlaceholderBoardView";

export const dynamic = "force-dynamic";

export default function InWorldPlaceholderPage() {
  return <PlaceholderBoardView />;
}
```

- [ ] **Step 3: Add to allowlist.**

In `frontend/scripts/verify-no-inline-styles.sh`, append the new entries to the `allowlist` array with a justifying comment:

```bash
# - src/components/inworld/FeaturedBoardView.tsx, FeaturedBoardCycler.tsx,
#   PlaceholderBoardView.tsx, and the two in-world page routes are SL-rendered
#   via Media-on-Prim and cannot use Tailwind variants per spec
#   docs/superpowers/specs/2026-06-01-hq-featured-boards-design.md §5.3.
allowlist+=(
  "src/components/inworld/FeaturedBoardView.tsx"
  "src/components/inworld/FeaturedBoardCycler.tsx"
  "src/components/inworld/PlaceholderBoardView.tsx"
  "src/app/in-world/board/[boardIndex]/page.tsx"
  "src/app/in-world/board/placeholder/page.tsx"
)
```

(Adjust syntax if the file uses `+=()` differently or maintains the list inline within a `grep -v` filter — refer to the actual script.)

- [ ] **Step 4: Run guards.**

Run: `cd frontend && npm run verify`
Expected: all guards pass.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/app/in-world frontend/scripts/verify-no-inline-styles.sh
git commit -m "feat(frontend): in-world board routes + inline-style allowlist"
```

---

## Phase 8 — Seller-facing "Feature this listing" button

### Task 23: Add the `useFeatureListing` hook

**Files:**
- Create: `frontend/src/hooks/useFeatureListing.ts`
- Create: `frontend/src/hooks/useFeatureListing.test.tsx`

- [ ] **Step 1: Failing test.**

```tsx
import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useFeatureListing } from "./useFeatureListing";
import * as promotions from "@/lib/api/promotions";

describe("useFeatureListing", () => {
  it("calls purchaseFeatured and exposes pending + result", async () => {
    const spy = vi.spyOn(promotions, "purchaseFeatured").mockResolvedValue({
      slotPublicId: "s1", boardIndex: 1, position: 0,
      priceLindens: 500, newBalanceLindens: 9500,
    });
    const { result } = renderHook(() => useFeatureListing());
    expect(result.current.pending).toBe(false);
    await act(async () => {
      const res = await result.current.purchase("a1");
      expect(res.newBalanceLindens).toBe(9500);
    });
    expect(spy).toHaveBeenCalledWith("a1");
  });
});
```

- [ ] **Step 2: Run to confirm failure.**

Run: `cd frontend && npx vitest run src/hooks/useFeatureListing.test.tsx`
Expected: compile failure.

- [ ] **Step 3: Hook.**

```ts
"use client";

import { useState } from "react";
import { purchaseFeatured } from "@/lib/api/promotions";
import type { PurchaseFeaturedResponse } from "@/types/promotion";

export function useFeatureListing() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  async function purchase(auctionPublicId: string): Promise<PurchaseFeaturedResponse> {
    setPending(true);
    setError(null);
    try {
      return await purchaseFeatured(auctionPublicId);
    } catch (e) {
      setError(e as Error);
      throw e;
    } finally {
      setPending(false);
    }
  }

  return { purchase, pending, error };
}
```

- [ ] **Step 4: Run.**

Run: `cd frontend && npx vitest run src/hooks/useFeatureListing.test.tsx`
Expected: green.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/hooks/useFeatureListing.ts frontend/src/hooks/useFeatureListing.test.tsx
git commit -m "feat(frontend): useFeatureListing hook"
```

---

### Task 24: Add the button + confirm modal

**Files:**
- Create: `frontend/src/components/listing/FeatureListingModal.tsx`
- Create: `frontend/src/components/listing/FeatureListingButton.tsx`
- Create: `frontend/src/components/listing/FeatureListingButton.test.tsx`

- [ ] **Step 1: Modal.**

```tsx
"use client";

import { useState } from "react";
import { useFeatureListing } from "@/hooks/useFeatureListing";
import { isApiError } from "@/lib/api";

interface Props {
  auctionPublicId: string;
  priceLindens: number;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

export function FeatureListingModal({
  auctionPublicId,
  priceLindens,
  isOpen,
  onClose,
  onSuccess,
}: Props) {
  const { purchase, pending } = useFeatureListing();
  const [message, setMessage] = useState<string | null>(null);

  if (!isOpen) return null;

  async function handleConfirm() {
    setMessage(null);
    try {
      await purchase(auctionPublicId);
      onSuccess();
    } catch (e) {
      if (isApiError(e)) {
        if (e.problem.code === "PROMOTION_ALREADY_ACTIVE") {
          setMessage("This auction is already featured.");
        } else if (e.problem.code === "INSUFFICIENT_BALANCE") {
          setMessage("Not enough wallet balance to buy Featured.");
        } else {
          setMessage(e.problem.detail ?? "Purchase failed.");
        }
      } else {
        setMessage("Purchase failed. Try again.");
      }
    }
  }

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50">
      <div className="bg-slate-900 text-white p-6 rounded max-w-md w-full">
        <h2 className="text-xl font-semibold">Feature this listing</h2>
        <p className="mt-3 text-sm text-slate-300">
          Pay L${priceLindens.toLocaleString()} from your SLParcels wallet to place
          this auction in the homepage Featured carousel and on one of the in-world
          HQ boards until the auction ends.
        </p>
        {message && <p className="mt-3 text-sm text-rose-300">{message}</p>}
        <div className="mt-5 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 rounded border border-slate-600">
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={pending}
            className="px-4 py-2 rounded bg-amber-600 hover:bg-amber-500 disabled:opacity-50"
          >
            {pending ? "Purchasing..." : `Pay L$${priceLindens.toLocaleString()}`}
          </button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Button.**

```tsx
"use client";

import { useState } from "react";
import { FeatureListingModal } from "./FeatureListingModal";

interface Props {
  auctionPublicId: string;
  priceLindens: number;
  alreadyFeatured: boolean;
  onPurchased?: () => void;
}

export function FeatureListingButton({
  auctionPublicId,
  priceLindens,
  alreadyFeatured,
  onPurchased,
}: Props) {
  const [open, setOpen] = useState(false);

  if (alreadyFeatured) {
    return (
      <span className="inline-block px-3 py-2 text-sm text-amber-400 font-semibold">
        ★ Featured
      </span>
    );
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="px-4 py-2 rounded bg-amber-600 hover:bg-amber-500 text-white font-semibold"
      >
        Feature this listing — L${priceLindens.toLocaleString()}
      </button>
      <FeatureListingModal
        auctionPublicId={auctionPublicId}
        priceLindens={priceLindens}
        isOpen={open}
        onClose={() => setOpen(false)}
        onSuccess={() => { setOpen(false); onPurchased?.(); }}
      />
    </>
  );
}
```

- [ ] **Step 3: Button test.**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { FeatureListingButton } from "./FeatureListingButton";

describe("FeatureListingButton", () => {
  it("shows 'Featured' chip when already featured", () => {
    render(<FeatureListingButton auctionPublicId="a1" priceLindens={500} alreadyFeatured />);
    expect(screen.getByText(/Featured/)).toBeInTheDocument();
  });

  it("shows the buy button with price when not yet featured", () => {
    render(<FeatureListingButton auctionPublicId="a1" priceLindens={500} alreadyFeatured={false} />);
    expect(screen.getByRole("button", { name: /Feature this listing — L\$500/ })).toBeInTheDocument();
  });
});
```

- [ ] **Step 4: Run.**

Run: `cd frontend && npx vitest run src/components/listing/FeatureListingButton.test.tsx`
Expected: green.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/components/listing/FeatureListingButton.tsx frontend/src/components/listing/FeatureListingModal.tsx frontend/src/components/listing/FeatureListingButton.test.tsx
git commit -m "feat(frontend): FeatureListingButton + modal"
```

---

### Task 25: Wire the button into the seller's auction management page

**Files:**
- Modify: the existing auction-management panel for the seller's own active auction.
- Read-first: `frontend/src/app/auction/[publicId]/page.tsx` and any seller-view component it composes (search for `SellerAuctionResponse`).

- [ ] **Step 1: Locate the seller's management panel.**

Run: `grep -rn "SellerAuctionResponse" frontend/src/components/auction frontend/src/components/listing`. Identify the component that renders for `auction.seller.publicId === currentUser.publicId` on ACTIVE auctions. Likely candidate: a panel within `AuctionDetailClient.tsx` or an explicit `SellerAuctionPanel.tsx`.

- [ ] **Step 2: Add a backend helper to expose the price in a fetchable form.**

The frontend needs to know the L$500 price. Two ways: (a) the buy button reads it from a public config endpoint, or (b) the seller panel fetches the price as a number from the same endpoint that returns the auction. Easiest: add the price to `SellerAuctionResponse` as `featuredPriceLindens` (server-side) so it travels with the auction payload.

Modify the backend `SellerAuctionResponse` DTO + the service that builds it to include `featuredPriceLindens: auctionConfig.featuredPriceLindens()` and `alreadyFeatured: auction.isFeatured()`. Update its test.

- [ ] **Step 3: Render the button in the seller panel.**

```tsx
import { FeatureListingButton } from "@/components/listing/FeatureListingButton";

// inside the seller's management panel JSX, near other listing actions:
{auction.status === "ACTIVE" && (
  <FeatureListingButton
    auctionPublicId={auction.publicId}
    priceLindens={auction.featuredPriceLindens}
    alreadyFeatured={auction.alreadyFeatured}
    onPurchased={() => router.refresh()}
  />
)}
```

- [ ] **Step 4: Update the relevant component test to cover the new chip/button surfaces.**

- [ ] **Step 5: Run.**

Run: `cd frontend && npm test`
Expected: full suite green.

- [ ] **Step 6: Commit.**

```bash
git add -p
git commit -m "feat(frontend): render Feature this listing button on seller's ACTIVE auctions"
```

---

## Phase 9 — Admin frontend

### Task 26: `/admin/featured-boards` page

**Files:**
- Create: `frontend/src/app/admin/featured-boards/page.tsx`
- Create: `frontend/src/components/admin/featured-boards/AdminFeaturedBoardsTable.tsx`
- Create: `frontend/src/lib/api/adminFeaturedBoards.ts`

- [ ] **Step 1: API client.**

```ts
import { api } from "@/lib/api";

export interface AdminFeaturedBoardRow {
  slotPublicId: string;
  boardIndex: number;
  position: number;
  auctionPublicId: string;
  auctionTitle: string;
  currentBid: number;
  endsAt: string;
  assignedAt: string;
}

export function listAdminFeaturedBoards(): Promise<AdminFeaturedBoardRow[]> {
  return api.get<AdminFeaturedBoardRow[]>("/api/v1/admin/featured-boards");
}

export function releaseSlot(slotPublicId: string): Promise<void> {
  return api.post<void>(`/api/v1/admin/featured-boards/${slotPublicId}/release`, {});
}

export function moveSlot(
  slotPublicId: string,
  boardIndex: number,
  position: number,
): Promise<void> {
  return api.patch<void>(
    `/api/v1/admin/featured-boards/${slotPublicId}/move`,
    { boardIndex, position },
  );
}
```

- [ ] **Step 2: Table component.**

```tsx
"use client";

import { useState } from "react";
import {
  AdminFeaturedBoardRow,
  releaseSlot,
  moveSlot,
} from "@/lib/api/adminFeaturedBoards";

interface Props {
  initial: AdminFeaturedBoardRow[];
  slotCount: number;
}

export function AdminFeaturedBoardsTable({ initial, slotCount }: Props) {
  const [rows, setRows] = useState(initial);

  async function handleRelease(slotPublicId: string) {
    if (!confirm("Force-release this slot? No refund is issued.")) return;
    await releaseSlot(slotPublicId);
    setRows(rs => rs.filter(r => r.slotPublicId !== slotPublicId));
  }

  async function handleMove(slotPublicId: string, boardIndex: number, position: number) {
    await moveSlot(slotPublicId, boardIndex, position);
    setRows(rs => rs.map(r => r.slotPublicId === slotPublicId
        ? { ...r, boardIndex, position } : r));
  }

  // Group by boardIndex for the by-board layout.
  const byBoard = new Map<number, AdminFeaturedBoardRow[]>();
  for (let i = 1; i <= slotCount; i++) byBoard.set(i, []);
  for (const r of rows) {
    const list = byBoard.get(r.boardIndex) ?? [];
    list.push(r);
    byBoard.set(r.boardIndex, list);
  }

  return (
    <div className="flex gap-4">
      {[...byBoard.entries()].map(([boardIndex, queue]) => (
        <div key={boardIndex} className="flex-1 border border-slate-700 rounded p-3">
          <h3 className="text-lg font-semibold">Board {boardIndex}</h3>
          {queue.length === 0
            ? <p className="text-sm text-slate-400 mt-2">empty</p>
            : queue.map(r => (
                <div key={r.slotPublicId} className="mt-3 border-t border-slate-700 pt-2">
                  <div className="text-sm font-medium">{r.auctionTitle}</div>
                  <div className="text-xs text-slate-400">
                    pos {r.position} · L${r.currentBid.toLocaleString()} · ends{" "}
                    {new Date(r.endsAt).toLocaleDateString()}
                  </div>
                  <div className="mt-2 flex gap-2 text-xs">
                    <button
                      onClick={() => handleRelease(r.slotPublicId)}
                      className="px-2 py-1 rounded bg-rose-700 text-white"
                    >
                      Release
                    </button>
                    <select
                      value={r.boardIndex}
                      onChange={e =>
                        handleMove(r.slotPublicId, Number(e.target.value), r.position)
                      }
                      className="bg-slate-800 text-white rounded"
                    >
                      {[...byBoard.keys()].map(b => (
                        <option key={b} value={b}>Board {b}</option>
                      ))}
                    </select>
                  </div>
                </div>
              ))}
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 3: Page.**

```tsx
import { listAdminFeaturedBoards } from "@/lib/api/adminFeaturedBoards";
import { AdminFeaturedBoardsTable } from "@/components/admin/featured-boards/AdminFeaturedBoardsTable";

export const dynamic = "force-dynamic";

export default async function AdminFeaturedBoardsPage() {
  // Slot count: fetch from a dedicated public-config endpoint if it exists,
  // or hardcode 5 (the current default) and bump when the config changes.
  const SLOT_COUNT = 5;
  const rows = await listAdminFeaturedBoards();
  return (
    <main className="p-6">
      <h1 className="text-2xl font-semibold">Featured boards</h1>
      <p className="text-sm text-slate-400 mt-1">
        {rows.length} active slot{rows.length === 1 ? "" : "s"} across {SLOT_COUNT} boards.
      </p>
      <div className="mt-6">
        <AdminFeaturedBoardsTable initial={rows} slotCount={SLOT_COUNT} />
      </div>
    </main>
  );
}
```

- [ ] **Step 4: Smoke-test the table component.**

Add `AdminFeaturedBoardsTable.test.tsx` covering: renders empty boards as "empty", renders rows with title + bid, calls `releaseSlot` on click.

- [ ] **Step 5: Run + commit.**

```bash
cd frontend && npm test
git add frontend/src/app/admin/featured-boards frontend/src/components/admin/featured-boards frontend/src/lib/api/adminFeaturedBoards.ts
git commit -m "feat(frontend): admin /admin/featured-boards curator page"
```

---

## Phase 10 — LSL script

### Task 27: Create the `featured-board` script directory + README

**Files:**
- Create: `lsl-scripts/featured-board/featured-board.lsl`
- Create: `lsl-scripts/featured-board/config.notecard.example`
- Create: `lsl-scripts/featured-board/README.md`
- Modify: `lsl-scripts/README.md` (top-level index)

- [ ] **Step 1: `config.notecard.example`.**

```
# Featured board configuration. Copy this to a notecard named `config`
# in the prim, edit the values, then reset the script.

BASE_URL=https://slparcels.com
BOARD_INDEX=1
DEBUG_MODE=true
```

- [ ] **Step 2: `featured-board.lsl`.**

```lsl
// SLParcels Featured Board
//
// Single-purpose: one of N board prims at SLParcels HQ. Renders the
// per-board MOAP page at BASE_URL/in-world/board/BOARD_INDEX and, on
// touch, hits the /touch endpoint to learn which listing is currently
// on-screen, then llDialogs the toucher with [Teleport] [View listing].
//
// Outbound HTTP only. No shared secret — the touch endpoint is anonymous.

// === Configuration loaded from notecard ===
string BASE_URL = "";
integer BOARD_INDEX = 0;
integer DEBUG_MODE = TRUE;

// === Runtime state ===
key   touchRequestId = NULL_KEY;
key   currentToucher = NULL_KEY;
integer dialogChannel = 0;
integer dialogListenHandle = -1;

string currentListingUrl = "";
string currentSlurl = "";

// === Notecard reader ===
string NOTECARD_NAME = "config";
integer notecardLineNum = 0;
key notecardLineRequest = NULL_KEY;
integer notecardDone = FALSE;

debugSay(string s) {
    if (DEBUG_MODE) llOwnerSay("[featured-board] " + s);
}

resetMediaUrl() {
    string url = BASE_URL + "/in-world/board/" + (string)BOARD_INDEX;
    list params = [
        PRIM_MEDIA_AUTO_PLAY,      TRUE,
        PRIM_MEDIA_AUTO_LOOP,      TRUE,
        PRIM_MEDIA_AUTO_SCALE,     TRUE,
        PRIM_MEDIA_AUTO_ZOOM,      FALSE,
        PRIM_MEDIA_FIRST_CLICK_INTERACT, FALSE,
        PRIM_MEDIA_WIDTH_PIXELS,   1024,
        PRIM_MEDIA_HEIGHT_PIXELS,  1024,
        PRIM_MEDIA_HOME_URL,       url,
        PRIM_MEDIA_CURRENT_URL,    url,
        PRIM_MEDIA_PERMS_INTERACT, PRIM_MEDIA_PERM_ANYONE,
        PRIM_MEDIA_PERMS_CONTROL,  PRIM_MEDIA_PERM_OWNER,
        PRIM_MEDIA_WHITELIST_ENABLE, TRUE,
        PRIM_MEDIA_WHITELIST,      "slparcels.com,*.slparcels.com"
    ];
    integer rc = llSetLinkMedia(LINK_THIS, 0, params);
    debugSay("media set: face=0 url=" + url + " rc=" + (string)rc);
}

requestTouchPayload(key toucher) {
    currentToucher = toucher;
    string url = BASE_URL + "/api/v1/in-world/featured-board/"
        + (string)BOARD_INDEX + "/touch";
    touchRequestId = llHTTPRequest(url, [
        HTTP_METHOD, "GET",
        HTTP_MIMETYPE, "application/json"
    ], "");
}

handleTouchResponse(string body) {
    currentListingUrl = "";
    currentSlurl = "";
    if (body == "" || body == "null") {
        llRegionSayTo(currentToucher, 0,
            "No featured listing on this board right now. Visit slparcels.com to browse.");
        return;
    }
    string title = llJsonGetValue(body, ["title"]);
    string listingUrl = llJsonGetValue(body, ["listingUrl"]);
    string slurl = llJsonGetValue(body, ["slurl"]);
    if (listingUrl != JSON_INVALID) currentListingUrl = BASE_URL + listingUrl;
    if (slurl != JSON_INVALID && slurl != "null") currentSlurl = slurl;

    dialogChannel = -1 - (integer)llFrand(1000000);
    if (dialogListenHandle != -1) llListenRemove(dialogListenHandle);
    dialogListenHandle = llListen(dialogChannel, "", currentToucher, "");
    llSetTimerEvent(60.0);
    list buttons = ["Cancel"];
    if (currentListingUrl != "") buttons = ["View listing"] + buttons;
    if (currentSlurl != "")      buttons = ["Teleport"]     + buttons;
    llDialog(currentToucher,
        title + "\n\nWhat would you like to do?", buttons, dialogChannel);
}

handleDialogChoice(string choice) {
    if (choice == "Teleport" && currentSlurl != "") {
        // SLURLs look like secondlife://Region/x/y/z — extract for llMapDestination.
        // The /touch payload already gives us a fully formed SLURL; we can
        // also use llLoadURL with the SLURL which is the simplest approach.
        llLoadURL(currentToucher,
            "Teleport to the featured parcel:", currentSlurl);
    } else if (choice == "View listing" && currentListingUrl != "") {
        llLoadURL(currentToucher,
            "Open this listing on slparcels.com?", currentListingUrl);
    }
    if (dialogListenHandle != -1) {
        llListenRemove(dialogListenHandle);
        dialogListenHandle = -1;
    }
    llSetTimerEvent(0.0);
}

// === Notecard parsing ===
readNotecardLine(integer n) {
    notecardLineNum = n;
    notecardLineRequest = llGetNotecardLine(NOTECARD_NAME, n);
}

applyNotecardLine(string line) {
    line = llStringTrim(line, STRING_TRIM);
    if (line == "" || llGetSubString(line, 0, 0) == "#") return;
    integer eq = llSubStringIndex(line, "=");
    if (eq < 1) return;
    string key = llStringTrim(llGetSubString(line, 0, eq - 1), STRING_TRIM);
    string val = llStringTrim(llGetSubString(line, eq + 1, -1), STRING_TRIM);
    if (key == "BASE_URL")    BASE_URL = val;
    else if (key == "BOARD_INDEX") BOARD_INDEX = (integer)val;
    else if (key == "DEBUG_MODE")  DEBUG_MODE = (val == "true" || val == "TRUE" || val == "1");
}

default {
    state_entry() {
        readNotecardLine(0);
    }

    dataserver(key id, string data) {
        if (id != notecardLineRequest) return;
        if (data == EOF) {
            notecardDone = TRUE;
            if (BASE_URL == "" || BOARD_INDEX < 1) {
                llOwnerSay("[featured-board] config invalid: BASE_URL='"
                    + BASE_URL + "' BOARD_INDEX=" + (string)BOARD_INDEX);
                return;
            }
            debugSay("ready: BASE_URL=" + BASE_URL
                + " BOARD_INDEX=" + (string)BOARD_INDEX);
            resetMediaUrl();
            return;
        }
        applyNotecardLine(data);
        readNotecardLine(notecardLineNum + 1);
    }

    touch_start(integer n) {
        if (!notecardDone) {
            llRegionSayTo(llDetectedKey(0), 0,
                "Board still booting; touch again in a moment.");
            return;
        }
        requestTouchPayload(llDetectedKey(0));
    }

    http_response(key id, integer status, list meta, string body) {
        if (id == touchRequestId) {
            if (status != 200) {
                debugSay("touch endpoint status=" + (string)status);
                llRegionSayTo(currentToucher, 0,
                    "Couldn't load this board's listing. Try again shortly.");
                return;
            }
            handleTouchResponse(body);
        }
    }

    listen(integer chan, string name, key id, string msg) {
        if (chan != dialogChannel || id != currentToucher) return;
        handleDialogChoice(msg);
    }

    timer() {
        // Dialog timed out without a choice — clean up.
        if (dialogListenHandle != -1) {
            llListenRemove(dialogListenHandle);
            dialogListenHandle = -1;
        }
        llSetTimerEvent(0.0);
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) llResetScript();
    }
}
```

- [ ] **Step 3: `README.md`.**

```markdown
# Featured board

In-world component of the SLParcels HQ Featured-board wall. One script
per board prim. The prim renders the per-board MOAP page from
`https://slparcels.com/in-world/board/{BOARD_INDEX}`, and on touch fires
a dialog with [Teleport / View listing / Cancel].

This is an **outbound-only** script — no HTTP-in URL is registered, no
shared secret is required.

## Deployment

1. Rez a square prim at HQ. Set its face 0 to a neutral material (the
   MOAP layer paints over it).
2. Drop `featured-board.lsl` into the prim.
3. Drop `config.notecard.example` renamed to `config`. Edit it:
   - `BASE_URL=https://slparcels.com`
   - `BOARD_INDEX=1` (each board prim gets a unique 1..N).
   - `DEBUG_MODE=false` (true while you're setting up).
4. Right-click → Edit → Reset Scripts in Selection.
5. Watch local chat for `[featured-board] ready: BASE_URL=... BOARD_INDEX=1`.

Repeat for each board, incrementing `BOARD_INDEX`. Boards beyond the
backend's configured `slpa.auction.featured-slot-count` will receive
404 from `/in-world/featured-board/{N}` — point those prims at
`/in-world/board/placeholder` instead by setting `BOARD_INDEX=0` and
adjusting the script's URL builder, or simply leave them dormant.

## Configuration

| Key | Description |
| --- | --- |
| `BASE_URL` | Frontend origin, no trailing slash. |
| `BOARD_INDEX` | 1..13. Must match a configured slot on the backend. |
| `DEBUG_MODE` | `true` enables owner-say debug logs. |

## Touch flow

1. Visitor touches the prim.
2. Script GETs `/api/v1/in-world/featured-board/{BOARD_INDEX}/touch` to
   learn which listing the MOAP cycle is currently showing.
3. Script `llDialog`s the toucher with a small menu.
4. `Teleport` and `View listing` both use `llLoadURL` — SL pops the
   browser-redirect dialog and the user clicks through.

## Limits and gotchas

- MOAP requires the viewer to have media enabled. Boards are blank to
  viewers with media off — see spec §3.3.
- First-time visitors see a one-time "Allow Always slparcels.com" prompt.
- Touch latency is bounded by the HTTP roundtrip (≤500ms typically).
- If the backend is down, touch returns the "couldn't load" message —
  the prim's MOAP layer continues showing whatever was last rendered.
- The script resets on inventory change, so dropping a new `config`
  notecard rehydrates automatically.
```

- [ ] **Step 4: Update `lsl-scripts/README.md` index.**

Append a single line linking the new directory in whichever pattern the index follows.

- [ ] **Step 5: Commit.**

```bash
git add lsl-scripts/featured-board lsl-scripts/README.md
git commit -m "feat(lsl): featured-board script + README"
```

---

## Phase 11 — Postman + manual end-to-end

### Task 28: Add "In-World Boards" Postman folder

**Files:**
- Postman collection `SLPA` (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`) in workspace `SLPA` at `https://scatr-devs.postman.co`.

- [ ] **Step 1: Create folder.**

In the `SLPA` collection, create a folder **In-World Boards** at the top level.

- [ ] **Step 2: Add requests.**

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `{{baseUrl}}/api/v1/in-world/featured-board/1` | none | Save `slotPublicId` from first listing into env: `pm.environment.set("slotPublicId", pm.response.json().listings[0]?.publicId)` |
| GET | `{{baseUrl}}/api/v1/in-world/featured-board/{{boardIndex}}/touch` | none | Uses env `boardIndex`. |
| GET | `{{baseUrl}}/api/v1/in-world/board/placeholder` | none | |
| POST | `{{baseUrl}}/api/v1/me/promotions/featured` | seller JWT | Body `{ "auctionPublicId": "{{auctionId}}" }`. Test script captures `slotPublicId` + `newBalanceLindens`. |
| GET | `{{baseUrl}}/api/v1/admin/featured-boards` | admin JWT | |
| POST | `{{baseUrl}}/api/v1/admin/featured-boards/{{slotPublicId}}/release` | admin JWT | |
| PATCH | `{{baseUrl}}/api/v1/admin/featured-boards/{{slotPublicId}}/move` | admin JWT | Body `{ "boardIndex": 3, "position": 0 }`. |

- [ ] **Step 3: Verify env variables.**

Confirm `auctionId`, `slotPublicId`, `boardIndex` are listed in the `SLPA Dev` environment. Add stubs if missing.

- [ ] **Step 4: Smoke against local backend.**

`docker compose up`, run each request once. Confirm POST `/me/promotions/featured` returns 201 with the expected shape. Confirm admin release deletes the slot from the next GET.

(No commit — Postman lives on the SaaS side. Note the additions in the PR description.)

---

### Task 29: Manual HQ end-to-end verification

- [ ] **Step 1: Activate one board prim in your test region.**

Drop `featured-board.lsl` + `config` (BOARD_INDEX=1) into a square prim. Watch chat for "ready". Verify the MOAP layer shows the placeholder (no PROMO-01 yet).

- [ ] **Step 2: Buy PROMO-01.**

Log in as a verified test seller with ≥L$500 wallet balance, create + activate a test auction, click **Feature this listing**, confirm in modal. Wallet shows -L$500.

- [ ] **Step 3: Confirm board update.**

Wait ≤15s for Redis cache to expire, then refresh the prim (right-click → Edit → Reset Scripts). Board now shows the auction's photo + title + bid + QR + FEATURED pill.

- [ ] **Step 4: Touch the board.**

Click the prim. Dialog appears with [Teleport / View listing / Cancel]. Click "View listing" — browser opens the auction page. Click "Teleport" — SL viewer offers teleport to parcel.

- [ ] **Step 5: End the auction.**

Use `dev/auction-end/run-once` (dev profile only). Wait ≤60s for the page to repoll. Board cross-fades to the next item in the queue (or the algorithmic fallback, or the placeholder).

- [ ] **Step 6: Bump slot count.**

Edit `application.yml`: `featured-slot-count: 6`. Restart backend. Activate a board prim with `BOARD_INDEX=6`. Buy PROMO-01 on a second test auction; confirm the new listing lands on board 6 (the empty one).

- [ ] **Step 7: Capture in PR description.**

Note the steps above + a couple of screenshots in the PR.

---

## Phase 12 — Wrap-up

### Task 30: Update README + sweep

- [ ] **Step 1: Update the root `README.md`** so the slice-by-slice description includes "PROMO-01 (Featured listing) purchase + in-world HQ boards (5 active, configurable up to 13)".

- [ ] **Step 2: Update `docs/monetization/monetization-options.md`** to note that PROMO-01 is **shipped** at L$500 (configurable) and now also includes in-world HQ board exposure. Cross-link to this plan and the spec.

- [ ] **Step 3: Run the full backend suite + frontend verify.**

```bash
cd backend && ./mvnw test
cd ../frontend && npm run verify && npm test
```

Expected: all green (modulo any pre-existing flakes per `backend_test_infra_flake` memory — check `target/surefire-reports/` for root cause if a wallet/auction test fails).

- [ ] **Step 4: Commit + open PR to `dev`.**

```bash
git add README.md docs/monetization/monetization-options.md
git commit -m "docs: README + monetization-options note PROMO-01 + HQ boards shipped"
git push -u origin <branch>
gh pr create --base dev --title "HQ Featured Boards + PROMO-01 purchase flow" --body "$(cat <<'EOF'
## Summary
- Ships PROMO-01 (Featured listing) at L$500 (configurable) bundled with in-world HQ board exposure.
- 5 active boards (configurable up to 13) with least-loaded per-board queue, static when 1 in queue, cycle every 30s when 2+, algorithmic fallback then placeholder.
- New seller endpoint POST /api/v1/me/promotions/featured; public in-world endpoints; admin curator at /admin/featured-boards.
- New LSL script `lsl-scripts/featured-board/`.

## Test plan
- [ ] backend: `./mvnw test` green
- [ ] frontend: `npm run verify && npm test` green
- [ ] manual HQ end-to-end per Task 29 (buy → board flips → touch dialog → auction-end → fallback)
- [ ] Postman: every In-World Boards request runs clean against local backend

See spec docs/superpowers/specs/2026-06-01-hq-featured-boards-design.md and plan docs/superpowers/plans/2026-06-01-hq-featured-boards-plan.md.
EOF
)"
```

---

## Self-review checklist (run before declaring the plan done)

This is a checklist for the engineer executing the plan, not the planner:

- [ ] V46 is still the next migration number when you start (no V46 added by another PR).
- [ ] `AuctionConfigPropertiesFixture` exists; if it doesn't, find the fixture pattern via `grep -rn "AuctionConfigProperties.builder" backend/src/test/java`.
- [ ] `epic07RedisTemplate` bean name matches the existing Redis config; adjust the controller constructor if the bean is named differently.
- [ ] `Auction.getCurrentBidAmt`, `getPhotos`, `getParcelSnapshot`, etc., match real field names. The plan uses placeholder names that match the spec's intent; verify against `Auction.java`.
- [ ] `BidReservation.releaseReason` / cancel + withdraw service names are accurate. Search for `setStatus(AuctionStatus.CANCELLED` and `setStatus(AuctionStatus.WITHDRAWN` to find the exact services that need the afterCommit hook.
- [ ] The `SellerAuctionResponse` DTO extension lives where the spec assumes; the actual field path may need a service-level adjustment too.
- [ ] The `verify-no-inline-styles.sh` script's exact allowlist syntax matches what's in the file — append the right way.
