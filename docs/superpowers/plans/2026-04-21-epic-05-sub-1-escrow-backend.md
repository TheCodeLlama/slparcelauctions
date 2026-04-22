# Epic 05 Sub-Spec 1 — Escrow Engine Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Source spec:** [`docs/superpowers/specs/2026-04-21-epic-05-sub-1-escrow-backend.md`](../specs/2026-04-21-epic-05-sub-1-escrow-backend.md) — every task below refers back to the spec by section number. Subagents receive the relevant spec section inline with their task prompt; they should NOT be expected to read the spec cover-to-cover.

**Goal:** Build the SLPA post-auction escrow engine — two-entity state machine (`Escrow` lifecycle + `EscrowTransaction` ledger), SL-header-gated payment callbacks, pooled terminal registry with shared-secret auth, ownership-monitor-driven transfer confirmation with fraud freeze, retrying outbound payout/refund dispatcher with IN_FLIGHT staleness sweep, 48h/72h timeout job with payout-in-flight guard, real listing-fee terminal callback (replacing the dev endpoint), and listing-fee refund processor that drains the Epic 03 backlog.

**Architecture:** New `com.slparcelauctions.backend.escrow` vertical slice. `Escrow` row created synchronously in `AuctionEndTask.closeOne` when `endOutcome ∈ {SOLD, BOUGHT_NOW}`. State transitions centralized in `EscrowService.transition` with a static allowed-transitions table. Three `@Scheduled` jobs (timeout every 5min, ownership monitor every 5min, command dispatcher every 30s) reuse the Epic 04 "scheduler queries IDs → per-entity `@Transactional` task re-locks with `PESSIMISTIC_WRITE`" shape. WebSocket envelopes extend `AuctionBroadcastPublisher` and fire via `TransactionSynchronization.afterCommit` on the existing `/topic/auction/{id}` channel. All new code injects `Clock` for test-time deadline manipulation.

**Tech Stack:** Spring Boot 4, Java 26, Spring Data JPA + Hibernate, PostgreSQL, Spring WebSocket/STOMP, Lombok, `ddl-auto: update` (no Flyway migrations), Spring `RestClient` for outbound terminal HTTP, `@Scheduled`, Testcontainers.

---

## Preflight (before Task 1)

- [ ] **P.1: Confirm branch state**

```bash
git rev-parse --abbrev-ref HEAD      # expect: task/05-sub-1-escrow-backend
git status                           # expect: working tree clean (spec already committed + pushed)
git log --oneline -3
```

- [ ] **P.2: Run existing test suite to establish green baseline**

```bash
cd backend && ./mvnw test
```

Expected: all tests pass (baseline from Epic 04 sub-2 merge, ~621 backend tests). If any existing tests fail, STOP — fix the baseline before introducing Epic 05 changes.

- [ ] **P.3: Verify dev infrastructure is running**

Postgres + Redis containers per the dev-containers memory. `docker ps` should show both slpa containers.

- [ ] **P.4: Read the spec**

Implementer subagents receive inline extracts. The controller (you) should skim §3 (Architecture), §4 (State Machine), §5 (Endpoints), §6 (Schedulers), §7 (Command Contract), §10 (Listing-Fee Integration) once so you can answer subagent questions quickly.

---

## File structure overview

**New package:** `com.slparcelauctions.backend.escrow`

```
escrow/
├── Escrow.java                                  (Task 1)
├── EscrowState.java                             (Task 1)
├── EscrowRepository.java                        (Task 1, extended by Task 6/8)
├── EscrowTransaction.java                       (Task 1)
├── EscrowTransactionType.java                   (Task 1)
├── EscrowTransactionStatus.java                 (Task 1)
├── EscrowTransactionRepository.java             (Task 1)
├── EscrowCommissionCalculator.java              (Task 1)
├── EscrowService.java                           (Task 1, extended by Task 2/3/5/6/7/8/9)
├── EscrowDisputeReasonCategory.java             (Task 3)
├── EscrowController.java                        (Task 3)
├── dto/
│   ├── EscrowStatusResponse.java                (Task 3)
│   ├── EscrowTimelineEntry.java                 (Task 3)
│   ├── EscrowDisputeRequest.java                (Task 3)
│   └── ...                                      (terminal + command DTOs added Task 4/5/7)
├── exception/
│   ├── IllegalEscrowTransitionException.java    (Task 1)
│   ├── EscrowNotFoundException.java             (Task 3)
│   ├── EscrowAccessDeniedException.java         (Task 3)
│   └── EscrowExceptionHandler.java              (Task 3)
├── broadcast/
│   ├── EscrowBroadcastPublisher.java            (Task 2)
│   ├── EscrowEnvelope.java                      (Task 2, sealed — variants added per task)
│   └── StompEscrowBroadcastPublisher.java       (Task 2)
├── terminal/
│   ├── Terminal.java                            (Task 4)
│   ├── TerminalRepository.java                  (Task 4)
│   ├── TerminalRegistrationController.java      (Task 4)
│   ├── dto/TerminalRegisterRequest.java         (Task 4)
│   └── EscrowConfigProperties.java              (Task 4)
├── payment/
│   ├── EscrowPaymentController.java             (Task 5)
│   ├── dto/EscrowPaymentRequest.java            (Task 5)
│   ├── dto/SlCallbackResponse.java              (Task 5, shared w/ Task 7/9)
│   ├── EscrowCallbackResponseReason.java        (Task 5)
│   └── ListingFeePaymentController.java         (Task 9)
├── scheduler/
│   ├── EscrowTimeoutJob.java                    (Task 8)
│   ├── EscrowTimeoutTask.java                   (Task 8)
│   ├── EscrowOwnershipMonitorJob.java           (Task 6)
│   ├── EscrowOwnershipCheckTask.java            (Task 6)
│   ├── TerminalCommandDispatcherJob.java        (Task 7)
│   ├── TerminalCommandDispatcherTask.java       (Task 7)
│   └── ListingFeeRefundProcessorJob.java        (Task 9)
├── command/
│   ├── TerminalCommand.java                     (Task 7)
│   ├── TerminalCommandAction.java               (Task 7)
│   ├── TerminalCommandPurpose.java              (Task 7)
│   ├── TerminalCommandStatus.java               (Task 7)
│   ├── TerminalCommandRepository.java           (Task 7)
│   ├── TerminalHttpClient.java                  (Task 7)
│   ├── TerminalCommandBody.java                 (Task 7, outbound JSON record)
│   ├── PayoutResultController.java              (Task 7)
│   └── dto/PayoutResultRequest.java             (Task 7)
└── config/
    └── EscrowStartupValidator.java              (Task 4)

Test support:
backend/src/test/java/.../escrow/broadcast/CapturingEscrowBroadcastPublisher.java  (Task 2)
backend/src/test/java/.../escrow/command/MockTerminalHttpClient.java               (Task 7)
backend/src/test/java/.../common/ClockOverrideConfig.java                          (Task 2, shared)
```

**Modifications to existing files:**

- `Auction` — no changes (escrow state lives on the new Escrow row, not on Auction).
- `AuctionEndTask.closeOne` — add tail-end call `escrowService.createForEndedAuction(auction, now)` when `outcome ∈ {SOLD, BOUGHT_NOW}` (Task 2).
- `BidService` (or wherever inline buy-it-now closes the auction) — same hook when `outcome == BOUGHT_NOW` (Task 2).
- `ListingFeeRefund` — add `terminalCommandId` (nullable Long FK) + `lastQueuedAt` (nullable OffsetDateTime) columns (Task 9).
- `ListingFeeRefundRepository` — add `findPendingAwaitingDispatch(now)` query (Task 9).
- `application.yml` — add `slpa.escrow.*` config block (Task 4).
- `application-dev.yml` — add dev placeholder `slpa.escrow.terminal-shared-secret: dev-escrow-secret-do-not-use-in-prod` (Task 4).

---

## Conventions all tasks follow

- **No Flyway migrations.** All tables created via `ddl-auto: update` (CONVENTIONS.md).
- **Lombok everywhere** on entities (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`), services (`@RequiredArgsConstructor @Slf4j`), and DTOs (records preferred).
- **Every task ships a working vertical slice** — entity + repo + service + controller (where applicable) + tests.
- **TDD:** write the failing test, run it to see RED, write the minimum code to make it GREEN, run it, commit.
- **Frequent commits.** Every TDD cycle ends in a commit. A task typically commits 4-8 times.
- **No AI / tool attribution** in any commit message or PR.
- **`Clock` injection** — every new service and scheduler injects a `Clock` bean; never call raw `OffsetDateTime.now()`.
- **`PagedResponse<T>`** for any paginated endpoint (none expected in this sub-spec).
- **Commit format:** Conventional-commits style (`feat(escrow):`, `test(escrow):`, `fix(escrow):`, `docs:`).

---

## Task 1: Foundation — `Escrow` + `EscrowTransaction` + state machine + commission

**Spec reference:** §3 (data model), §4 (state machine + commission), §9 (concurrency).

**Goal:** Ship the escrow entities, repositories, state-transition machinery, commission calculator, and the `IllegalEscrowTransitionException`. No controllers. Unit tests only (DB not exercised yet beyond entity wiring).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowState.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTransaction.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTransactionType.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTransactionStatus.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTransactionRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowCommissionCalculator.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/exception/IllegalEscrowTransitionException.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowCommissionCalculatorTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowServiceTransitionTest.java`

### Step 1.1: `EscrowState` enum

- [ ] **1.1: Create `EscrowState.java`**

```java
package com.slparcelauctions.backend.escrow;

/**
 * Per-auction escrow lifecycle states (spec §4.1). {@code FUNDED} is a
 * transient internal state — in current code it always atomically advances
 * to {@code TRANSFER_PENDING} in the same transaction, so external observers
 * see the flip directly from {@code ESCROW_PENDING} to {@code TRANSFER_PENDING}.
 * Terminal states: {@code COMPLETED}, {@code EXPIRED}, {@code DISPUTED},
 * {@code FROZEN}. No resume paths in sub-spec 1.
 */
public enum EscrowState {
    ESCROW_PENDING,
    FUNDED,
    TRANSFER_PENDING,
    COMPLETED,
    DISPUTED,
    EXPIRED,
    FROZEN
}
```

- [ ] **1.2: Commit — `feat(escrow): add EscrowState enum`**

### Step 1.2: `Escrow` entity

- [ ] **1.3: Create `Escrow.java`**

```java
package com.slparcelauctions.backend.escrow;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.auction.Auction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-auction escrow lifecycle row (spec §3.1, §4). Created synchronously by
 * AuctionEndTask.closeOne on SOLD or BOUGHT_NOW outcomes. Deadlines:
 * paymentDeadline = auction.endedAt + 48h; transferDeadline = fundedAt + 72h.
 * Commission computed at creation and immutable.
 */
@Entity
@Table(name = "escrows",
        indexes = {
                @Index(name = "ix_escrows_state", columnList = "state"),
                @Index(name = "ix_escrows_payment_deadline", columnList = "payment_deadline"),
                @Index(name = "ix_escrows_transfer_deadline", columnList = "transfer_deadline")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Escrow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false, unique = true)
    private Auction auction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EscrowState state;

    @Column(name = "final_bid_amount", nullable = false)
    private Long finalBidAmount;

    @Column(name = "commission_amt", nullable = false)
    private Long commissionAmt;

    @Column(name = "payout_amt", nullable = false)
    private Long payoutAmt;

    @Column(name = "payment_deadline", nullable = false)
    private OffsetDateTime paymentDeadline;

    @Column(name = "transfer_deadline")
    private OffsetDateTime transferDeadline;

    @Column(name = "funded_at")
    private OffsetDateTime fundedAt;

    @Column(name = "transfer_confirmed_at")
    private OffsetDateTime transferConfirmedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "disputed_at")
    private OffsetDateTime disputedAt;

    @Column(name = "frozen_at")
    private OffsetDateTime frozenAt;

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Builder.Default
    @Column(name = "consecutive_world_api_failures", nullable = false)
    private Integer consecutiveWorldApiFailures = 0;

    @Column(name = "dispute_reason_category", length = 40)
    private String disputeReasonCategory;

    @Column(name = "dispute_description", columnDefinition = "text")
    private String disputeDescription;

    @Column(name = "freeze_reason", length = 40)
    private String freezeReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

- [ ] **1.4: Commit — `feat(escrow): add Escrow entity`**

### Step 1.3: `EscrowRepository`

- [ ] **1.5: Create `EscrowRepository.java`**

```java
package com.slparcelauctions.backend.escrow;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EscrowRepository extends JpaRepository<Escrow, Long> {

    Optional<Escrow> findByAuctionId(Long auctionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Escrow e WHERE e.id = :id")
    Optional<Escrow> findByIdForUpdate(@Param("id") Long id);
}
```

- [ ] **1.6: Commit — `feat(escrow): EscrowRepository with pessimistic lock`**

### Step 1.4: Ledger — `EscrowTransaction` entity + enums + repository

- [ ] **1.7: Create `EscrowTransactionType.java`**

```java
package com.slparcelauctions.backend.escrow;

public enum EscrowTransactionType {
    AUCTION_ESCROW_PAYMENT,
    AUCTION_ESCROW_PAYOUT,
    AUCTION_ESCROW_REFUND,
    AUCTION_ESCROW_COMMISSION,
    LISTING_FEE_PAYMENT,
    LISTING_FEE_REFUND
}
```

- [ ] **1.8: Create `EscrowTransactionStatus.java`**

```java
package com.slparcelauctions.backend.escrow;

public enum EscrowTransactionStatus {
    PENDING,
    COMPLETED,
    FAILED
}
```

- [ ] **1.9: Create `EscrowTransaction.java`**

```java
package com.slparcelauctions.backend.escrow;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "escrow_transactions",
        indexes = {
                @Index(name = "ix_escrow_tx_escrow", columnList = "escrow_id"),
                @Index(name = "ix_escrow_tx_auction", columnList = "auction_id"),
                @Index(name = "ix_escrow_tx_sl_txn", columnList = "sl_transaction_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EscrowTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escrow_id")
    private Escrow escrow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id")
    private Auction auction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EscrowTransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EscrowTransactionStatus status;

    @Column(nullable = false)
    private Long amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_id")
    private User payer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payee_id")
    private User payee;

    @Column(name = "sl_transaction_id", length = 100)
    private String slTransactionId;

    @Column(name = "terminal_id", length = 100)
    private String terminalId;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
```

- [ ] **1.10: Create `EscrowTransactionRepository.java`**

```java
package com.slparcelauctions.backend.escrow;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Long> {

    List<EscrowTransaction> findByEscrowIdOrderByCreatedAtAsc(Long escrowId);

    Optional<EscrowTransaction> findFirstBySlTransactionIdAndType(
            String slTransactionId, EscrowTransactionType type);
}
```

- [ ] **1.11: Commit — `feat(escrow): EscrowTransaction ledger entity + enums`**

### Step 1.5: Commission calculator — TDD

- [ ] **1.12: Write `EscrowCommissionCalculatorTest.java` (RED)**

```java
package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EscrowCommissionCalculatorTest {

    private final EscrowCommissionCalculator calc = new EscrowCommissionCalculator();

    @Test
    void computesFivePercentAboveOneThousand() {
        assertThat(calc.commission(1000L)).isEqualTo(50L);
        assertThat(calc.commission(10_000L)).isEqualTo(500L);
        assertThat(calc.commission(100_000L)).isEqualTo(5_000L);
    }

    @Test
    void floorsToFiftyBelowOneThousand() {
        assertThat(calc.commission(500L)).isEqualTo(50L);
        assertThat(calc.commission(100L)).isEqualTo(50L);
    }

    @Test
    void clampsCommissionToFinalBidForPathologicallyLowAmounts() {
        assertThat(calc.commission(1L)).isEqualTo(1L);
        assertThat(calc.commission(25L)).isEqualTo(25L);
        assertThat(calc.commission(49L)).isEqualTo(49L);
        assertThat(calc.commission(50L)).isEqualTo(50L);
    }

    @Test
    void payoutIsAlwaysFinalMinusCommission() {
        assertThat(calc.payout(1L)).isEqualTo(0L);
        assertThat(calc.payout(100L)).isEqualTo(50L);
        assertThat(calc.payout(1000L)).isEqualTo(950L);
        assertThat(calc.payout(10_000L)).isEqualTo(9_500L);
    }
}
```

Run: `./mvnw test -Dtest=EscrowCommissionCalculatorTest` — Expected: COMPILE FAIL (class not defined).

- [ ] **1.13: Create `EscrowCommissionCalculator.java` (GREEN)**

```java
package com.slparcelauctions.backend.escrow;

import org.springframework.stereotype.Component;

/**
 * Commission + payout math for auction-escrow payouts (spec §4.3). The L$50
 * floor means auctions closing under L$1,000 pay a disproportionate
 * commission — intentional per DESIGN.md to discourage micro-auctions.
 */
@Component
public class EscrowCommissionCalculator {

    private static final long FLOOR_LINDENS = 50L;
    private static final long RATE_PERCENT = 5L;

    public long commission(long finalBidAmount) {
        long raw = Math.floorDiv(finalBidAmount * RATE_PERCENT, 100L);
        long afterFloor = Math.max(raw, FLOOR_LINDENS);
        return Math.min(afterFloor, finalBidAmount);
    }

    public long payout(long finalBidAmount) {
        return finalBidAmount - commission(finalBidAmount);
    }
}
```

Run: `./mvnw test -Dtest=EscrowCommissionCalculatorTest` — Expected: PASS.

- [ ] **1.14: Commit — `feat(escrow): commission calculator with L$50 floor`**

### Step 1.6: `IllegalEscrowTransitionException`

- [ ] **1.15: Create `exception/IllegalEscrowTransitionException.java`**

```java
package com.slparcelauctions.backend.escrow.exception;

import com.slparcelauctions.backend.escrow.EscrowState;

import lombok.Getter;

@Getter
public class IllegalEscrowTransitionException extends RuntimeException {

    private final Long escrowId;
    private final EscrowState currentState;
    private final EscrowState attemptedTarget;

    public IllegalEscrowTransitionException(
            Long escrowId, EscrowState currentState, EscrowState attemptedTarget) {
        super("Escrow " + escrowId + " cannot transition from "
                + currentState + " to " + attemptedTarget);
        this.escrowId = escrowId;
        this.currentState = currentState;
        this.attemptedTarget = attemptedTarget;
    }
}
```

- [ ] **1.16: Commit — `feat(escrow): IllegalEscrowTransitionException`**

### Step 1.7: State machine — TDD

- [ ] **1.17: Write `EscrowServiceTransitionTest.java` (RED)**

```java
package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.escrow.exception.IllegalEscrowTransitionException;

class EscrowServiceTransitionTest {

    @Test
    void validTransitionsFromEscrowPending() {
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.FUNDED)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.EXPIRED)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.DISPUTED)).isTrue();
    }

    @Test
    void validTransitionsFromFunded() {
        assertThat(EscrowService.isAllowed(EscrowState.FUNDED, EscrowState.TRANSFER_PENDING)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.FUNDED, EscrowState.DISPUTED)).isTrue();
    }

    @Test
    void validTransitionsFromTransferPending() {
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.COMPLETED)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.EXPIRED)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.FROZEN)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.DISPUTED)).isTrue();
    }

    @Test
    void terminalStatesAllowNoTransitions() {
        for (EscrowState target : EscrowState.values()) {
            assertThat(EscrowService.isAllowed(EscrowState.COMPLETED, target)).isFalse();
            assertThat(EscrowService.isAllowed(EscrowState.EXPIRED, target)).isFalse();
            assertThat(EscrowService.isAllowed(EscrowState.DISPUTED, target)).isFalse();
            assertThat(EscrowService.isAllowed(EscrowState.FROZEN, target)).isFalse();
        }
    }

    @Test
    void rejectsBackwardsTransitions() {
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.ESCROW_PENDING)).isFalse();
        assertThat(EscrowService.isAllowed(EscrowState.FUNDED, EscrowState.ESCROW_PENDING)).isFalse();
    }

    @Test
    void rejectsSkippingStates() {
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.COMPLETED)).isFalse();
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.TRANSFER_PENDING)).isFalse();
    }

    @Test
    void enforceTransitionThrowsForInvalidMoves() {
        assertThatThrownBy(() ->
                EscrowService.enforceTransitionAllowed(42L, EscrowState.COMPLETED, EscrowState.FUNDED))
                .isInstanceOf(IllegalEscrowTransitionException.class)
                .hasMessageContaining("42")
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("FUNDED");
    }

    @Test
    void enforceTransitionPassesThroughForValidMoves() {
        EscrowService.enforceTransitionAllowed(42L, EscrowState.ESCROW_PENDING, EscrowState.FUNDED);
    }
}
```

Run: `./mvnw test -Dtest=EscrowServiceTransitionTest` — Expected: COMPILE FAIL.

- [ ] **1.18: Create minimum `EscrowService.java` (GREEN)**

(Full service grows task by task — this task ships only the static transition table and two helper statics.)

```java
package com.slparcelauctions.backend.escrow;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.escrow.exception.IllegalEscrowTransitionException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central orchestrator for escrow lifecycle transitions (spec §4.2). Tasks
 * 2-9 progressively add methods — this task ships the static allowed-
 * transitions table and the isAllowed / enforceTransitionAllowed helpers
 * plus the collaborators later tasks will use.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowService {

    static final Map<EscrowState, Set<EscrowState>> ALLOWED_TRANSITIONS = Map.of(
            EscrowState.ESCROW_PENDING,
                    Set.of(EscrowState.FUNDED, EscrowState.EXPIRED, EscrowState.DISPUTED),
            EscrowState.FUNDED,
                    Set.of(EscrowState.TRANSFER_PENDING, EscrowState.DISPUTED),
            EscrowState.TRANSFER_PENDING,
                    Set.of(EscrowState.COMPLETED, EscrowState.EXPIRED,
                           EscrowState.FROZEN, EscrowState.DISPUTED),
            EscrowState.COMPLETED, Set.of(),
            EscrowState.EXPIRED, Set.of(),
            EscrowState.DISPUTED, Set.of(),
            EscrowState.FROZEN, Set.of()
    );

    private final EscrowRepository escrowRepo;
    private final EscrowTransactionRepository ledgerRepo;
    private final EscrowCommissionCalculator commission;
    private final Clock clock;

    public static boolean isAllowed(EscrowState from, EscrowState to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void enforceTransitionAllowed(Long escrowId, EscrowState from, EscrowState to) {
        if (!isAllowed(from, to)) {
            throw new IllegalEscrowTransitionException(escrowId, from, to);
        }
    }
}
```

Run: `./mvnw test -Dtest=EscrowServiceTransitionTest` — Expected: PASS.

- [ ] **1.19: Commit — `feat(escrow): state machine transition table + enforcers`**

### Step 1.8: Sanity check

- [ ] **1.20: Run full suite**

Run: `cd backend && ./mvnw test`
Expected: all existing tests pass + the two new unit tests pass.

---

## Task 2: Auction-end integration + broadcast infrastructure

**Spec reference:** §3.2 (cross-package touchpoint), §4.2 (transition), §8 (WS envelopes), §6.4 (Clock injection).

**Goal:** Wire `EscrowService.createForEndedAuction` into `AuctionEndTask.closeOne` and the inline buy-it-now close path. Ship the `EscrowBroadcastPublisher` interface + `StompEscrowBroadcastPublisher` + sealed `EscrowEnvelope` hierarchy with its first concrete variant `ESCROW_CREATED`. Add shared test-support `ClockOverrideConfig`. Prove end-to-end: an auction closing with SOLD creates an Escrow row and publishes an envelope after commit.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowEnvelope.java` (sealed interface)
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowCreatedEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowBroadcastPublisher.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/StompEscrowBroadcastPublisher.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/NoOpEscrowBroadcastPublisher.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/broadcast/CapturingEscrowBroadcastPublisher.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/common/ClockOverrideConfig.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowCreateOnAuctionEndIntegrationTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java` — add `createForEndedAuction(Auction auction, OffsetDateTime endedAt)`.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java` — call `escrowService.createForEndedAuction(...)` on SOLD outcome, same transaction.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java` — call `escrowService.createForEndedAuction(...)` on the inline buy-it-now close path (outcome=BOUGHT_NOW).

### Steps

- [ ] **2.1: Create `EscrowEnvelope.java` sealed interface**

```java
package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

/**
 * Sealed hierarchy of escrow broadcast envelopes published on
 * {@code /topic/auction/{id}}. Each variant has a fixed {@code type}
 * discriminator string; clients route on {@code type}. Spec §8.
 */
public sealed interface EscrowEnvelope
        permits EscrowCreatedEnvelope {

    String type();
    Long auctionId();
    Long escrowId();
    OffsetDateTime serverTime();
}
```

(Later tasks widen the `permits` clause as they add variants.)

- [ ] **2.2: Create `EscrowCreatedEnvelope.java`**

```java
package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

public record EscrowCreatedEnvelope(
        String type,
        Long auctionId,
        Long escrowId,
        EscrowState state,
        OffsetDateTime paymentDeadline,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowCreatedEnvelope of(Escrow e, OffsetDateTime serverTime) {
        return new EscrowCreatedEnvelope(
                "ESCROW_CREATED",
                e.getAuction().getId(),
                e.getId(),
                e.getState(),
                e.getPaymentDeadline(),
                serverTime);
    }
}
```

- [ ] **2.3: Create `EscrowBroadcastPublisher.java` interface**

```java
package com.slparcelauctions.backend.escrow.broadcast;

/**
 * Abstraction over the WebSocket broadcast layer for escrow events. The
 * production implementation is StompEscrowBroadcastPublisher; the no-op
 * fallback steps aside for test slices that don't need real broadcasts.
 * Every publish method is safe to invoke from a
 * {@code TransactionSynchronization.afterCommit} callback.
 */
public interface EscrowBroadcastPublisher {
    void publishCreated(EscrowCreatedEnvelope envelope);
}
```

(Method set grows per task.)

- [ ] **2.4: Create `StompEscrowBroadcastPublisher.java`**

```java
package com.slparcelauctions.backend.escrow.broadcast;

import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class StompEscrowBroadcastPublisher implements EscrowBroadcastPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publishCreated(EscrowCreatedEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_CREATED");
    }

    void publish(EscrowEnvelope envelope, Long auctionId, String logLabel) {
        String destination = "/topic/auction/" + auctionId;
        log.info("Publishing {} to {}: escrowId={}", logLabel, destination, envelope.escrowId());
        try {
            messagingTemplate.convertAndSend(destination, envelope);
        } catch (MessagingException e) {
            log.warn("Failed to publish {} for auction {}: {}",
                    logLabel, auctionId, e.getMessage(), e);
        }
    }
}
```

- [ ] **2.5: Create `NoOpEscrowBroadcastPublisher.java`**

```java
package com.slparcelauctions.backend.escrow.broadcast;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@ConditionalOnMissingBean(EscrowBroadcastPublisher.class)
@Slf4j
public class NoOpEscrowBroadcastPublisher implements EscrowBroadcastPublisher {
    @Override
    public void publishCreated(EscrowCreatedEnvelope envelope) {
        log.debug("NoOp publishCreated: {}", envelope);
    }
}
```

- [ ] **2.6: Create test-support `CapturingEscrowBroadcastPublisher.java`**

```java
package com.slparcelauctions.backend.escrow.broadcast;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CapturingEscrowBroadcastPublisher implements EscrowBroadcastPublisher {
    public final List<EscrowCreatedEnvelope> created = new CopyOnWriteArrayList<>();

    @Override public void publishCreated(EscrowCreatedEnvelope envelope) { created.add(envelope); }

    public void reset() {
        created.clear();
    }
}
```

- [ ] **2.7: Create shared `ClockOverrideConfig.java`**

```java
package com.slparcelauctions.backend.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces {@link Clock} with a fixed instance tests can advance. Applied
 * via {@code @Import(ClockOverrideConfig.class)} on integration tests that
 * exercise deadline-sensitive code.
 */
@TestConfiguration
public class ClockOverrideConfig {

    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-05-01T12:00:00Z");

    @Bean
    @Primary
    public MutableFixedClock testClock() {
        return new MutableFixedClock(DEFAULT_INSTANT);
    }

    public static final class MutableFixedClock extends Clock {
        private volatile Instant instant;

        public MutableFixedClock(Instant initial) { this.instant = initial; }

        public void set(Instant next) { this.instant = next; }
        public void advance(java.time.Duration d) { this.instant = this.instant.plus(d); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
```

- [ ] **2.8: Commit — `feat(escrow): broadcast interface + envelopes + test clock`**

- [ ] **2.9: Extend `EscrowService` with `createForEndedAuction`**

Add to `EscrowService.java`:

```java
    private final EscrowBroadcastPublisher broadcastPublisher;

    private static final long PAYMENT_DEADLINE_HOURS = 48;

    /**
     * Creates the Escrow row for an auction that just closed with
     * {@code endOutcome ∈ {SOLD, BOUGHT_NOW}}. Called from
     * {@code AuctionEndTask.closeOne} and from the inline buy-it-now close
     * path in {@code BidService} — both run inside the caller's transaction.
     * The ESCROW_CREATED broadcast is registered on afterCommit so observers
     * never see a row that gets rolled back with the close.
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Escrow createForEndedAuction(
            com.slparcelauctions.backend.auction.Auction auction,
            java.time.OffsetDateTime endedAt) {
        long finalBid = auction.getFinalBidAmount();
        Escrow escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.ESCROW_PENDING)
                .finalBidAmount(finalBid)
                .commissionAmt(commission.commission(finalBid))
                .payoutAmt(commission.payout(finalBid))
                .paymentDeadline(endedAt.plusHours(PAYMENT_DEADLINE_HOURS))
                .consecutiveWorldApiFailures(0)
                .build();
        Escrow saved = escrowRepo.save(escrow);

        final EscrowCreatedEnvelope envelope = EscrowCreatedEnvelope.of(saved, endedAt);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { broadcastPublisher.publishCreated(envelope); }
                });
        log.info("Escrow {} created for auction {} (final L${}, commission L${}, payout L${})",
                saved.getId(), auction.getId(), finalBid, saved.getCommissionAmt(), saved.getPayoutAmt());
        return saved;
    }
```

Commit — `feat(escrow): EscrowService.createForEndedAuction stamps row + afterCommit broadcast`.

- [ ] **2.10: Write integration test `EscrowCreateOnAuctionEndIntegrationTest.java` (RED)**

Test outline (full code):

```java
package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.auctionend.AuctionEndTask;
import com.slparcelauctions.backend.common.ClockOverrideConfig;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.testing.AuctionFixtures;

@SpringBootTest
@Import({ClockOverrideConfig.class, EscrowCreateOnAuctionEndIntegrationTest.PublisherConfig.class})
class EscrowCreateOnAuctionEndIntegrationTest {

    @TestConfiguration
    static class PublisherConfig {
        @Bean @Primary EscrowBroadcastPublisher capturing() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @Autowired AuctionRepository auctionRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired AuctionEndTask auctionEndTask;
    @Autowired EscrowBroadcastPublisher publisher;
    @Autowired Clock clock;
    @Autowired AuctionFixtures fixtures;

    @BeforeEach void reset() {
        ((CapturingEscrowBroadcastPublisher) publisher).reset();
    }

    @Test
    void soldOutcomeCreatesEscrowRowAndBroadcasts() {
        Auction a = fixtures.activeAuctionWithSingleBid(/* bidAmount */ 5000L);
        a.setEndsAt(OffsetDateTime.now(clock).minusSeconds(1));
        auctionRepo.save(a);

        auctionEndTask.closeOne(a.getId());

        Auction reloaded = auctionRepo.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(reloaded.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);

        Escrow escrow = escrowRepo.findByAuctionId(a.getId()).orElseThrow();
        assertThat(escrow.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(escrow.getFinalBidAmount()).isEqualTo(5000L);
        assertThat(escrow.getCommissionAmt()).isEqualTo(250L);
        assertThat(escrow.getPayoutAmt()).isEqualTo(4750L);
        assertThat(escrow.getPaymentDeadline())
                .isEqualTo(reloaded.getEndedAt().plusHours(48));

        var captured = (CapturingEscrowBroadcastPublisher) publisher;
        assertThat(captured.created).hasSize(1);
        assertThat(captured.created.get(0).auctionId()).isEqualTo(a.getId());
    }

    @Test
    void noBidsOutcomeDoesNotCreateEscrow() {
        Auction a = fixtures.activeAuctionNoBids();
        a.setEndsAt(OffsetDateTime.now(clock).minusSeconds(1));
        auctionRepo.save(a);
        auctionEndTask.closeOne(a.getId());
        assertThat(escrowRepo.findByAuctionId(a.getId())).isEmpty();
    }

    @Test
    void reserveNotMetOutcomeDoesNotCreateEscrow() {
        Auction a = fixtures.activeAuctionWithBidBelowReserve();
        a.setEndsAt(OffsetDateTime.now(clock).minusSeconds(1));
        auctionRepo.save(a);
        auctionEndTask.closeOne(a.getId());
        assertThat(escrowRepo.findByAuctionId(a.getId())).isEmpty();
    }
}
```

(Note on `AuctionFixtures`: reuse the existing `@TestComponent` helpers created during Epic 04. If a fixture builder does not exist for these scenarios, the subagent should extend the existing builder rather than hand-rolling per-test object graphs.)

Run: `./mvnw test -Dtest=EscrowCreateOnAuctionEndIntegrationTest`
Expected: RED — `AuctionEndTask` does not yet call `escrowService.createForEndedAuction`.

- [ ] **2.11: Wire `AuctionEndTask.closeOne` to call `escrowService.createForEndedAuction` (GREEN)**

In `AuctionEndTask.java`, add `private final EscrowService escrowService;` to constructor-injected fields and inject this call inside `closeOne` right after the status + outcome mutation, before the `afterCommit` registration for the AUCTION_ENDED envelope:

```java
        if (outcome == AuctionEndOutcome.SOLD) {
            escrowService.createForEndedAuction(auction, now);
        }
```

Also update `BidService`'s inline buy-it-now close path (grep for `setEndOutcome(AuctionEndOutcome.BOUGHT_NOW)` to find the call site) to call `escrowService.createForEndedAuction(auction, now)` in the same transaction.

Run: `./mvnw test -Dtest=EscrowCreateOnAuctionEndIntegrationTest`
Expected: PASS.

- [ ] **2.12: Add a buy-it-now variant test**

Extend `EscrowCreateOnAuctionEndIntegrationTest` (or a sibling `EscrowCreateOnBuyNowIntegrationTest`) to verify that a `BidService.placeBid` that crosses `buyNowPrice` also creates an escrow row. Use the existing buy-now integration test infrastructure.

- [ ] **2.13: Run full backend suite**

Run: `cd backend && ./mvnw test`
Expected: all existing tests pass (no regressions from the `AuctionEndTask` / `BidService` hook additions).

- [ ] **2.14: Commit — `feat(escrow): create row on auction-end SOLD and BOUGHT_NOW paths`**

---

## Task 3: Authenticated endpoints — GET + dispute

**Spec reference:** §5.1 (REST endpoints), §4.4 (dispute semantics), §9.1 (exception taxonomy).

**Goal:** Ship `GET /api/v1/auctions/{id}/escrow` and `POST /api/v1/auctions/{id}/escrow/dispute`. Both authenticated; accessible only to seller or winner. Add `EscrowExceptionHandler` with the 403/404/409 mappings. Add `EscrowService.filedDispute(escrowId, user, reasonCategory, description)` which transitions to DISPUTED.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowDisputeReasonCategory.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/dto/EscrowStatusResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/dto/EscrowTimelineEntry.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/dto/EscrowDisputeRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/exception/EscrowNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/exception/EscrowAccessDeniedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/exception/EscrowExceptionHandler.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowControllerSliceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowDisputeIntegrationTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java` — add `getStatus(auctionId, currentUser)`, `fileDispute(...)`, package-private helpers.

### Steps

- [ ] **3.1: Create `EscrowDisputeReasonCategory.java`**

```java
package com.slparcelauctions.backend.escrow;

public enum EscrowDisputeReasonCategory {
    SELLER_NOT_RESPONSIVE,
    WRONG_PARCEL_TRANSFERRED,
    PAYMENT_NOT_CREDITED,
    FRAUD_SUSPECTED,
    OTHER
}
```

- [ ] **3.2: Create DTO records**

```java
// EscrowDisputeRequest.java
package com.slparcelauctions.backend.escrow.dto;

import com.slparcelauctions.backend.escrow.EscrowDisputeReasonCategory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EscrowDisputeRequest(
        @NotNull EscrowDisputeReasonCategory reasonCategory,
        @NotNull @Size(min = 10, max = 2000) String description) { }
```

```java
// EscrowTimelineEntry.java
package com.slparcelauctions.backend.escrow.dto;

import java.time.OffsetDateTime;

public record EscrowTimelineEntry(
        String kind,     // STATE_TRANSITION | LEDGER_PAYMENT | LEDGER_PAYOUT | LEDGER_REFUND | LEDGER_COMMISSION | LEDGER_FAILED
        String label,
        OffsetDateTime at,
        Long amount,      // nullable
        String details) { }
```

```java
// EscrowStatusResponse.java
package com.slparcelauctions.backend.escrow.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.slparcelauctions.backend.escrow.EscrowState;

public record EscrowStatusResponse(
        Long escrowId,
        Long auctionId,
        EscrowState state,
        Long finalBidAmount,
        Long commissionAmt,
        Long payoutAmt,
        OffsetDateTime paymentDeadline,
        OffsetDateTime transferDeadline,
        OffsetDateTime fundedAt,
        OffsetDateTime transferConfirmedAt,
        OffsetDateTime completedAt,
        OffsetDateTime disputedAt,
        OffsetDateTime frozenAt,
        OffsetDateTime expiredAt,
        String disputeReasonCategory,
        String disputeDescription,
        String freezeReason,
        List<EscrowTimelineEntry> timeline) { }
```

- [ ] **3.3: Create exceptions**

```java
// EscrowNotFoundException.java
package com.slparcelauctions.backend.escrow.exception;

import lombok.Getter;

@Getter
public class EscrowNotFoundException extends RuntimeException {
    private final Long auctionId;
    public EscrowNotFoundException(Long auctionId) {
        super("No escrow exists for auction " + auctionId);
        this.auctionId = auctionId;
    }
}

// EscrowAccessDeniedException.java
package com.slparcelauctions.backend.escrow.exception;

public class EscrowAccessDeniedException extends RuntimeException {
    public EscrowAccessDeniedException() {
        super("Only the seller or winner may view or act on this escrow");
    }
}
```

- [ ] **3.4: Create `EscrowExceptionHandler.java`**

```java
package com.slparcelauctions.backend.escrow.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.escrow")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class EscrowExceptionHandler {

    @ExceptionHandler(EscrowNotFoundException.class)
    public ProblemDetail handleNotFound(EscrowNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Escrow Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ESCROW_NOT_FOUND");
        pd.setProperty("auctionId", e.getAuctionId());
        return pd;
    }

    @ExceptionHandler(EscrowAccessDeniedException.class)
    public ProblemDetail handleForbidden(EscrowAccessDeniedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Escrow Forbidden");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ESCROW_FORBIDDEN");
        return pd;
    }

    @ExceptionHandler(IllegalEscrowTransitionException.class)
    public ProblemDetail handleIllegalTransition(IllegalEscrowTransitionException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Escrow Invalid Transition");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ESCROW_INVALID_TRANSITION");
        pd.setProperty("escrowId", e.getEscrowId());
        pd.setProperty("currentState", e.getCurrentState().name());
        pd.setProperty("attemptedTarget", e.getAttemptedTarget().name());
        return pd;
    }
}
```

- [ ] **3.5: Extend `EscrowService` with `getStatus(auctionId, currentUser)` + `fileDispute(...)` + timeline assembly**

Both methods enforce seller-or-winner authorization by comparing `currentUser.id` against `escrow.auction.seller.id` and `escrow.auction.winnerUserId`. `fileDispute` goes through `enforceTransitionAllowed`, stamps `disputed_at` + `disputeReasonCategory` + `disputeDescription`, writes a ledger row, queues a refund `TerminalCommand` if `funded_at IS NOT NULL` (Task 7 handles the command queue; stub the call with a `Runnable` hook for now or direct repository write — see spec §4.4), and publishes the `ESCROW_DISPUTED` envelope on afterCommit.

**Note:** the refund-queuing on dispute depends on `TerminalCommand` being available (Task 7). For this task, a no-op placeholder `// TODO(Task 7): queue refund when funded_at IS NOT NULL` is NOT acceptable per CONVENTIONS.md. Instead, Task 3 introduces a method-level hook `void queueRefundIfFunded(Escrow)` on `EscrowService` that does nothing in this task and is replaced by the real implementation in Task 7 (the implementer should leave the method body empty with a javadoc explaining the forward link to Task 7's `TerminalCommandService`).

- [ ] **3.6: Create `EscrowController.java`**

```java
package com.slparcelauctions.backend.escrow;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.CurrentUser;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/escrow")
@RequiredArgsConstructor
public class EscrowController {

    private final EscrowService escrowService;

    @GetMapping
    public ResponseEntity<EscrowStatusResponse> getStatus(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(escrowService.getStatus(auctionId, currentUser));
    }

    @PostMapping("/dispute")
    public ResponseEntity<EscrowStatusResponse> fileDispute(
            @PathVariable Long auctionId,
            @Valid @RequestBody EscrowDisputeRequest body,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(escrowService.fileDispute(auctionId, body, currentUser));
    }
}
```

(Check the exact type of the principal — likely `CurrentUser` from `com.slparcelauctions.backend.auth`; if it differs, match the existing convention used by `AuctionController`.)

- [ ] **3.7: Write `EscrowControllerSliceTest.java` — `@WebMvcTest`**

Cover: GET returns 200 for seller, 200 for winner, 403 for other user, 404 when no escrow row, 401 for anonymous. POST /dispute returns 200 for seller + winner, 403 otherwise, 409 when escrow is in COMPLETED / EXPIRED / DISPUTED / FROZEN.

- [ ] **3.8: Write `EscrowDisputeIntegrationTest.java`**

Cover dispute from `ESCROW_PENDING`, from `TRANSFER_PENDING`, and verify the refund hook was invoked only when `funded_at != null`.

- [ ] **3.9: Add `SecurityConfig` route for `/api/v1/auctions/*/escrow/**` — `authenticated()`**

Extend the existing auth chain. Follow the pattern the `AuctionController` uses for authenticated endpoints.

- [ ] **3.10: Commit — `feat(escrow): authenticated GET + dispute endpoints`**

- [ ] **3.11: Full suite green**

Run: `./mvnw test` — expect all previous + new tests pass.

---

## Task 4: Terminal registry + shared secret + startup validator

**Spec reference:** §5.2 (`/api/v1/sl/terminal/register`), §5.5 (validation pipeline), §6.5 (startup guardrails), §7.5 (terminal registration semantics).

**Goal:** Ship the `Terminal` entity + repository, the public-with-SL-headers registration endpoint, the `EscrowConfigProperties` binding `slpa.escrow.*` settings, and `EscrowStartupValidator` that fails fast on prod boot if `terminal-shared-secret` is empty.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/terminal/Terminal.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/terminal/TerminalRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/terminal/TerminalService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/terminal/TerminalRegistrationController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/terminal/dto/TerminalRegisterRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/terminal/EscrowConfigProperties.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/config/EscrowStartupValidator.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/terminal/TerminalRegistrationControllerSliceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/config/EscrowStartupValidatorTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` — whitelist `/api/v1/sl/terminal/register` as `permitAll` (SL-header-gated inside the controller).
- Modify: `backend/src/main/resources/application.yml` — add `slpa.escrow.*` block.
- Modify: `backend/src/main/resources/application-dev.yml` — add `slpa.escrow.terminal-shared-secret: dev-escrow-secret-do-not-use-in-prod`.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/BackendApplication.java` or the `@ConfigurationPropertiesScan` — register `EscrowConfigProperties`.

### Steps

- [ ] **4.1: Create `Terminal.java` entity**

```java
package com.slparcelauctions.backend.escrow.terminal;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Registered in-world terminal (spec §7.5). terminal_id is the client-chosen
 * identifier (typically the SL object UUID) — used as the primary key rather
 * than a synthetic bigint because the terminal POSTs it back on every
 * command and callback.
 */
@Entity
@Table(name = "terminals",
        indexes = {
                @Index(name = "ix_terminals_active_last_seen", columnList = "active, last_seen_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Terminal {

    @Id
    @Column(name = "terminal_id", length = 100)
    private String terminalId;

    @Column(name = "http_in_url", nullable = false, length = 500)
    private String httpInUrl;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private OffsetDateTime registeredAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

- [ ] **4.2: Create `TerminalRepository.java`**

```java
package com.slparcelauctions.backend.escrow.terminal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalRepository extends JpaRepository<Terminal, String> {

    @Query("""
            SELECT t FROM Terminal t
            WHERE t.active = true AND t.lastSeenAt >= :cutoff
            ORDER BY t.lastSeenAt DESC
            """)
    List<Terminal> findLiveTerminals(@Param("cutoff") OffsetDateTime cutoff);

    default Optional<Terminal> findAnyLive(OffsetDateTime cutoff) {
        List<Terminal> live = findLiveTerminals(cutoff);
        return live.isEmpty() ? Optional.empty() : Optional.of(live.get(0));
    }
}
```

- [ ] **4.3: Create `EscrowConfigProperties.java`**

```java
package com.slparcelauctions.backend.escrow.terminal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for escrow orchestration. Bound to {@code slpa.escrow.*}.
 * See spec §6.5 for defaults. {@code terminalSharedSecret} is validated at
 * startup by {@link com.slparcelauctions.backend.escrow.config.EscrowStartupValidator}
 * on non-dev profiles.
 */
@ConfigurationProperties(prefix = "slpa.escrow")
public record EscrowConfigProperties(
        Boolean enabled,
        String terminalSharedSecret,
        Duration terminalLiveWindow,
        Duration commandInFlightTimeout,
        Integer ownershipApiFailureThreshold,
        Duration ownershipReminderDelay) {

    public EscrowConfigProperties {
        if (enabled == null) enabled = true;
        if (terminalSharedSecret == null) terminalSharedSecret = "";
        if (terminalLiveWindow == null) terminalLiveWindow = Duration.ofMinutes(15);
        if (commandInFlightTimeout == null) commandInFlightTimeout = Duration.ofMinutes(5);
        if (ownershipApiFailureThreshold == null) ownershipApiFailureThreshold = 5;
        if (ownershipReminderDelay == null) ownershipReminderDelay = Duration.ofHours(24);
    }
}
```

- [ ] **4.4: Create `EscrowStartupValidator.java`**

```java
package com.slparcelauctions.backend.escrow.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Profile("!dev")
@RequiredArgsConstructor
@Slf4j
public class EscrowStartupValidator {

    private final EscrowConfigProperties props;

    @PostConstruct
    void validate() {
        if (props.terminalSharedSecret() == null || props.terminalSharedSecret().isBlank()) {
            throw new IllegalStateException(
                    "slpa.escrow.terminal-shared-secret must be set in non-dev profiles. "
                            + "Configure via environment variable SLPA_ESCROW_TERMINAL_SHARED_SECRET "
                            + "or a secrets-manager override.");
        }
        if (props.terminalSharedSecret().length() < 16) {
            throw new IllegalStateException(
                    "slpa.escrow.terminal-shared-secret must be at least 16 characters; got "
                            + props.terminalSharedSecret().length());
        }
        log.info("Escrow startup validation OK: enabled={}, liveWindow={}, inFlightTimeout={}",
                props.enabled(), props.terminalLiveWindow(), props.commandInFlightTimeout());
    }
}
```

- [ ] **4.5: Unit-test `EscrowStartupValidatorTest.java`**

Cover: empty secret throws, short secret throws, valid secret passes. Instantiate the validator directly (no Spring context needed — pure constructor).

- [ ] **4.6: Create `TerminalRegisterRequest.java`**

```java
package com.slparcelauctions.backend.escrow.terminal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TerminalRegisterRequest(
        @NotBlank @Size(max = 100) String terminalId,
        @NotBlank @Size(max = 500)
        @Pattern(regexp = "^https?://.+")
        String httpInUrl,
        @Size(max = 100) String regionName,
        @NotBlank String sharedSecret) { }
```

- [ ] **4.7: Create `TerminalService.java`**

```java
package com.slparcelauctions.backend.escrow.terminal;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.terminal.dto.TerminalRegisterRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TerminalService {

    private final TerminalRepository terminalRepo;
    private final EscrowConfigProperties props;
    private final Clock clock;

    @Transactional
    public Terminal register(TerminalRegisterRequest req) {
        assertSharedSecret(req.sharedSecret());
        OffsetDateTime now = OffsetDateTime.now(clock);
        Terminal t = terminalRepo.findById(req.terminalId())
                .orElseGet(() -> Terminal.builder()
                        .terminalId(req.terminalId())
                        .active(true)
                        .build());
        t.setHttpInUrl(req.httpInUrl());
        t.setRegionName(req.regionName());
        t.setLastSeenAt(now);
        t.setActive(true);
        Terminal saved = terminalRepo.save(t);
        log.info("Terminal {} registered (url={}, region={})", saved.getTerminalId(),
                saved.getHttpInUrl(), saved.getRegionName());
        return saved;
    }

    public void assertSharedSecret(String provided) {
        String expected = props.terminalSharedSecret();
        if (expected == null || expected.isBlank() || provided == null
                || !expected.equals(provided)) {
            log.warn("Shared secret mismatch on terminal call");
            throw new com.slparcelauctions.backend.escrow.exception.TerminalAuthException();
        }
    }
}
```

- [ ] **4.8: Create `TerminalAuthException` (maps to 403 via `EscrowExceptionHandler`)**

```java
package com.slparcelauctions.backend.escrow.exception;

public class TerminalAuthException extends RuntimeException {
    public TerminalAuthException() {
        super("Terminal shared secret mismatch");
    }
}
```

Extend `EscrowExceptionHandler` with a handler for this exception returning 403 with `code=SECRET_MISMATCH`.

- [ ] **4.9: Create `TerminalRegistrationController.java`**

```java
package com.slparcelauctions.backend.escrow.terminal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.terminal.dto.TerminalRegisterRequest;
import com.slparcelauctions.backend.sl.SlHeaderValidator;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/sl/terminal")
@RequiredArgsConstructor
public class TerminalRegistrationController {

    private final TerminalService terminalService;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/register")
    public ResponseEntity<TerminalResponse> register(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody TerminalRegisterRequest req) {
        headerValidator.validate(shard, ownerKey);
        Terminal t = terminalService.register(req);
        return ResponseEntity.ok(new TerminalResponse(
                t.getTerminalId(), t.getHttpInUrl(), t.getLastSeenAt()));
    }

    public record TerminalResponse(String terminalId, String httpInUrl,
                                   java.time.OffsetDateTime lastSeenAt) { }
}
```

- [ ] **4.10: Add config to `application.yml` + `application-dev.yml`**

In `application.yml`:

```yaml
slpa:
  escrow:
    enabled: true
    terminal-shared-secret: ${SLPA_ESCROW_TERMINAL_SHARED_SECRET:}
    terminal-live-window: PT15M
    command-in-flight-timeout: PT5M
    ownership-api-failure-threshold: 5
    ownership-reminder-delay: PT24H
```

In `application-dev.yml`:

```yaml
slpa:
  escrow:
    terminal-shared-secret: dev-escrow-secret-do-not-use-in-prod
```

Also add job cadence knobs:

```yaml
slpa:
  escrow:
    timeout-job:
      enabled: true
      fixed-delay: PT5M
    ownership-monitor-job:
      enabled: true
      fixed-delay: PT5M
    command-dispatcher-job:
      enabled: true
      fixed-delay: PT30S
    listing-fee-refund-job:
      enabled: true
      fixed-delay: PT1M
```

- [ ] **4.11: Register `EscrowConfigProperties` via `@ConfigurationPropertiesScan`**

If the app uses `@ConfigurationPropertiesScan` on the main class, nothing else is required. Otherwise add `@EnableConfigurationProperties(EscrowConfigProperties.class)` to a `@Configuration` class in the escrow package.

- [ ] **4.12: Update `SecurityConfig.java`**

Whitelist `/api/v1/sl/terminal/register` + `/api/v1/sl/escrow/payment` + `/api/v1/sl/escrow/payout-result` + `/api/v1/sl/listing-fee/payment` as `permitAll` (Tasks 5, 7, 9 add the other three endpoints; they can be whitelisted now to avoid re-touching in every task).

- [ ] **4.13: Write `TerminalRegistrationControllerSliceTest.java`**

Cover: happy path, missing SL headers → 403, bad shared secret → 403, duplicate `terminalId` → 200 with URL updated.

- [ ] **4.14: Commit — `feat(escrow): terminal registry + shared-secret auth + startup validator`**

- [ ] **4.15: Full suite green**

Run: `./mvnw test`. Verify `EscrowStartupValidator` does NOT fire in test profile (it's `@Profile("!dev")` — confirm the integration tests use `@ActiveProfiles("dev")` or some profile that excludes prod).

---

## Task 5: Escrow payment receiving endpoint

**Spec reference:** §5.2 (`/api/v1/sl/escrow/payment`), §5.4 (LSL response shape), §5.5 (validation pipeline), §5.6 (fraud flag rule), §4.2 (FUNDED transition).

**Goal:** Ship `POST /api/v1/sl/escrow/payment`, the validation pipeline (headers → shared secret → terminal exists → idempotency → domain), the `ESCROW_PENDING → FUNDED → TRANSFER_PENDING` atomic transition with `ESCROW_FUNDED` broadcast, and fraud-flag creation on wrong-payer.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/EscrowPaymentController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/dto/EscrowPaymentRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/dto/SlCallbackResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/EscrowCallbackResponseReason.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowFundedEnvelope.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/payment/EscrowPaymentControllerSliceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowPaymentIntegrationTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowEnvelope.java` — add `EscrowFundedEnvelope` to `permits` clause.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowBroadcastPublisher.java` — add `publishFunded(...)`; same for `StompEscrowBroadcastPublisher`, `NoOpEscrowBroadcastPublisher`, `CapturingEscrowBroadcastPublisher`.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java` — add `acceptPayment(EscrowPaymentRequest, SlHeaderContext)`.

### Key code

`EscrowPaymentRequest`:

```java
package com.slparcelauctions.backend.escrow.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EscrowPaymentRequest(
        @NotNull Long auctionId,
        @NotBlank String payerUuid,
        @NotNull @Min(1) Long amount,
        @NotBlank String slTransactionKey,
        @NotBlank String terminalId,
        @NotBlank String sharedSecret) { }
```

`EscrowCallbackResponseReason`:

```java
package com.slparcelauctions.backend.escrow.payment;

public enum EscrowCallbackResponseReason {
    // REFUND variants — terminal MUST refund the L$
    WRONG_PAYER,
    WRONG_AMOUNT,
    ESCROW_EXPIRED,
    ALREADY_FUNDED,
    // ERROR variants — terminal does NOT refund
    UNKNOWN_AUCTION,
    UNKNOWN_TERMINAL,
    SECRET_MISMATCH,
    BAD_HEADERS,
    ALREADY_PAID
}
```

`SlCallbackResponse`:

```java
package com.slparcelauctions.backend.escrow.payment.dto;

import com.slparcelauctions.backend.escrow.payment.EscrowCallbackResponseReason;

public record SlCallbackResponse(String status, EscrowCallbackResponseReason reason, String message) {
    public static SlCallbackResponse ok() {
        return new SlCallbackResponse("OK", null, null);
    }
    public static SlCallbackResponse refund(EscrowCallbackResponseReason reason, String msg) {
        return new SlCallbackResponse("REFUND", reason, msg);
    }
    public static SlCallbackResponse error(EscrowCallbackResponseReason reason, String msg) {
        return new SlCallbackResponse("ERROR", reason, msg);
    }
}
```

`EscrowFundedEnvelope` (follows the `EscrowCreatedEnvelope` pattern; `state=TRANSFER_PENDING`, carries `transferDeadline`).

`EscrowService.acceptPayment` flow (pseudocode):

```
@Transactional
public SlCallbackResponse acceptPayment(EscrowPaymentRequest req):
  // 1. Idempotency on slTransactionKey
  existing = ledgerRepo.findFirstBySlTransactionIdAndType(
      req.slTransactionKey, EscrowTransactionType.AUCTION_ESCROW_PAYMENT);
  if (existing.isPresent()) return recreateResponseFromLedger(existing);

  // 2. Load Escrow for auctionId
  Escrow e = escrowRepo.findByAuctionId(req.auctionId())
      .orElseGet(() -> null);
  if (e == null) return error(UNKNOWN_AUCTION);

  // 3. Pessimistic lock
  e = escrowRepo.findByIdForUpdate(e.getId()).orElseThrow();

  // 4. State check
  if (e.getState() == FUNDED || e.getState() == TRANSFER_PENDING || COMPLETED)
      return refund(ALREADY_FUNDED);
  if (e.getState() == EXPIRED || DISPUTED || FROZEN)
      return refund(ESCROW_EXPIRED);
  if (e.getState() != ESCROW_PENDING)
      return refund(ESCROW_EXPIRED); // catch-all

  // 5. Payer match
  User winner = userRepo.findById(e.getAuction().getWinnerUserId()).orElseThrow();
  if (!winner.getSlAvatarUuid().equalsIgnoreCase(req.payerUuid())) {
      fraudFlagService.create(e.getAuction(), FraudFlagReason.ESCROW_WRONG_PAYER, evidence);
      writeFailedLedgerRow(...);
      return refund(WRONG_PAYER);
  }

  // 6. Amount match
  if (!req.amount().equals(e.getFinalBidAmount())) {
      writeFailedLedgerRow(...);
      return refund(WRONG_AMOUNT);
  }

  // 7. Deadline
  if (OffsetDateTime.now(clock).isAfter(e.getPaymentDeadline())) {
      writeFailedLedgerRow(...);
      return refund(ESCROW_EXPIRED);
  }

  // 8. Transition atomically: ESCROW_PENDING → FUNDED → TRANSFER_PENDING
  OffsetDateTime now = OffsetDateTime.now(clock);
  enforceTransitionAllowed(e.getId(), e.getState(), EscrowState.FUNDED);
  e.setState(EscrowState.FUNDED);
  e.setFundedAt(now);
  enforceTransitionAllowed(e.getId(), e.getState(), EscrowState.TRANSFER_PENDING);
  e.setState(EscrowState.TRANSFER_PENDING);
  e.setTransferDeadline(now.plusHours(72));

  // 9. Ledger row
  ledgerRepo.save(EscrowTransaction.builder()
      .escrow(e).auction(e.getAuction())
      .type(EscrowTransactionType.AUCTION_ESCROW_PAYMENT)
      .status(EscrowTransactionStatus.COMPLETED)
      .amount(req.amount()).payer(winner)
      .slTransactionId(req.slTransactionKey())
      .terminalId(req.terminalId())
      .completedAt(now).build());

  // 10. Broadcast
  registerAfterCommit(() -> broadcastPublisher.publishFunded(EscrowFundedEnvelope.of(e, now)));

  return SlCallbackResponse.ok();
```

**Idempotency note:** `recreateResponseFromLedger` is a private helper that inspects the existing ledger row's `status` + `errorMessage` to reconstruct the exact response previously sent. For COMPLETED rows, return OK. For FAILED rows, return the same REFUND/ERROR the first attempt produced.

**Fraud-flag wiring:** use the existing `FraudFlag` entity + create a new `FraudFlagReason` value `ESCROW_WRONG_PAYER`. Call the existing service path that Epic 03's `SuspensionService` uses — reuse, don't duplicate.

### TDD steps

- [ ] **5.1: Write slice test `EscrowPaymentControllerSliceTest.java` covering 8 scenarios: valid payment, wrong payer, wrong amount, expired escrow, duplicate slTransactionKey (idempotent), bad SL headers, bad shared secret, unknown auction.**

- [ ] **5.2: Write integration test `EscrowPaymentIntegrationTest.java` covering: full end-to-end happy path (escrow created via Task 2 → payment received → state=TRANSFER_PENDING → ledger row + `ESCROW_FUNDED` envelope captured); wrong-payer creates FraudFlag; deadline-expired returns REFUND.**

- [ ] **5.3: Run both tests — RED.**

- [ ] **5.4: Implement `EscrowService.acceptPayment` per pseudocode above.**

- [ ] **5.5: Implement `EscrowPaymentController`** (routes POST `/api/v1/sl/escrow/payment`, validates SL headers via `SlHeaderValidator`, delegates to `EscrowService.acceptPayment`).

- [ ] **5.6: Run tests — GREEN.**

- [ ] **5.7: Commit — `feat(escrow): payment receiving endpoint with fraud flag on wrong payer`**

- [ ] **5.8: Dev-profile helper `POST /api/v1/dev/escrow/{auctionId}/simulate-payment`** — bypass SL headers + secret, useful for frontend dev. Small controller in `escrow.dev` subpackage, `@Profile("dev")`.

- [ ] **5.9: Commit — `feat(escrow): dev helper for simulated payment`**

- [ ] **5.10: Full suite green.**

---

## Task 6: Ownership monitor job + fraud freeze

**Spec reference:** §6.2 (`EscrowOwnershipMonitorJob`), §4.5 (fraud-freeze semantics), §9.3 (concurrency).

**Goal:** Ship the scheduled ownership monitor that polls World API for `TRANSFER_PENDING` escrows, stamps `transfer_confirmed_at` + queues PAYOUT on winner match (state stays TRANSFER_PENDING — payout callback flips to COMPLETED per §4.2), freezes on unknown-owner or parcel-deleted, handles transient World API failures with a consecutive-failure threshold, and logs a 24h seller-reminder signal.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/EscrowOwnershipMonitorJob.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/EscrowOwnershipCheckTask.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowTransferConfirmedEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowFrozenEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/FreezeReason.java` (enum: `UNKNOWN_OWNER`, `PARCEL_DELETED`, `WORLD_API_PERSISTENT_FAILURE`)
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/scheduler/EscrowOwnershipCheckTaskTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/scheduler/EscrowOwnershipMonitorIntegrationTest.java`
- Modify: `EscrowRepository.java` — add `findTransferPendingIds()` query.
- Modify: `EscrowService.java` — add `confirmTransfer(escrowId, now)` (stamps `transferConfirmedAt` + queues PAYOUT via `terminalCommandService` + publishes `ESCROW_TRANSFER_CONFIRMED` envelope; state stays `TRANSFER_PENDING`) and `freezeForFraud(escrowId, reason, evidenceJson)`.
- Modify: broadcast package — extend `EscrowBroadcastPublisher` with `publishTransferConfirmed` + `publishFrozen`; widen sealed `EscrowEnvelope.permits`.

### Ownership check flow (inside `EscrowOwnershipCheckTask.checkOne(escrowId)`, `@Transactional`)

1. `escrow = escrowRepo.findByIdForUpdate(escrowId)`; skip if state ≠ `TRANSFER_PENDING`.
2. Reuse the existing `SlWorldApiClient.fetchParcel(parcelUuid)` from Epic 03. (No new client.)
3. Outcomes:
   - `ownerId == winner.slAvatarUuid` → `escrowService.confirmTransfer(escrow, now)`.
   - `ownerId == seller.slAvatarUuid` → stamp `last_checked_at=now`; reset `consecutive_world_api_failures=0`; if `now - funded_at >= 24h`, `log.info("seller_transfer_reminder_due=true ...")`.
   - Anything else (including null) → `escrowService.freezeForFraud(escrow, FreezeReason.UNKNOWN_OWNER, Map.of("observedOwner", ownerId, "expectedWinner", winner.slAvatarUuid))`.
4. World API `404` exception from client → `freezeForFraud(escrow, FreezeReason.PARCEL_DELETED, evidence)`.
5. World API 5xx / timeout → increment `consecutive_world_api_failures`; if threshold exceeded → `freezeForFraud(escrow, FreezeReason.WORLD_API_PERSISTENT_FAILURE, evidence)`.

`freezeForFraud` writes a `FraudFlag` row (existing Epic 03 entity, new `FraudFlagReason.ESCROW_UNKNOWN_OWNER` / `ESCROW_PARCEL_DELETED` / `ESCROW_WORLD_API_FAILURE`) + stamps `frozen_at` + `freezeReason` + queues refund via `terminalCommandService.queueRefund(escrow)` (Task 7) + publishes `ESCROW_FROZEN` envelope.

### `EscrowOwnershipMonitorJob`

```java
package com.slparcelauctions.backend.escrow.scheduler;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.escrow.EscrowRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@ConditionalOnProperty(value = "slpa.escrow.ownership-monitor-job.enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class EscrowOwnershipMonitorJob {

    private final EscrowRepository escrowRepo;
    private final EscrowOwnershipCheckTask checkTask;

    @Scheduled(fixedDelayString = "${slpa.escrow.ownership-monitor-job.fixed-delay:PT5M}")
    public void sweep() {
        List<Long> ids = escrowRepo.findTransferPendingIds();
        if (ids.isEmpty()) return;
        log.info("EscrowOwnershipMonitorJob processing {} transfer-pending escrows", ids.size());
        for (Long id : ids) {
            try {
                checkTask.checkOne(id);
            } catch (RuntimeException e) {
                log.error("Escrow ownership check failed for escrow {}: {}", id, e.getMessage(), e);
            }
        }
    }
}
```

### Tests

- [ ] **6.1: Write `EscrowOwnershipCheckTaskTest.java` (RED)** — mock `SlWorldApiClient` + `EscrowService`; cover 5 outcomes: winner-match, seller-match (within 24h — no reminder log), seller-match (past 24h — reminder log), unknown-owner, 404, 5xx-below-threshold, 5xx-above-threshold.

- [ ] **6.2: Implement `EscrowOwnershipCheckTask` + `EscrowService.confirmTransfer` + `EscrowService.freezeForFraud` (GREEN)**

- [ ] **6.3: Write `EscrowOwnershipMonitorIntegrationTest.java`** — full integration: seed TRANSFER_PENDING escrow, stub `SlWorldApiClient` via `@MockitoBean`, force `sweep()`, assert state + envelope + FraudFlag as appropriate.

- [ ] **6.4: Commit — `feat(escrow): ownership monitor with fraud freeze + transient failure threshold`**

- [ ] **6.5: Dev endpoint `POST /api/v1/dev/escrow-ownership-monitor/run` (@Profile("dev"))** — triggers one `sweep()` synchronously. Mirrors Epic 03's `DevOwnershipMonitorController` pattern.

- [ ] **6.6: Full suite green.**

---

## Task 7: Terminal command dispatcher + payout callback + retry

**Spec reference:** §6.3 (dispatcher), §7 (command contract), §9.2 (idempotency), §4.2 (COMPLETED transition via callback).

**Goal:** Ship the outbound command queue — `TerminalCommand` entity, `TerminalCommandService.queuePayout(escrow)` / `queueRefund(escrow)`, the dispatcher job with IN_FLIGHT staleness sweep, `TerminalHttpClient` (real + mock), `POST /api/v1/sl/escrow/payout-result` callback handler, retry backoff, and the `ESCROW_COMPLETED` / `ESCROW_REFUND_COMPLETED` / `ESCROW_PAYOUT_STALLED` envelopes.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommand.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandAction.java` (enum: PAYOUT, REFUND)
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandPurpose.java` (enum: AUCTION_ESCROW, LISTING_FEE_REFUND)
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandStatus.java` (enum: QUEUED, IN_FLIGHT, COMPLETED, FAILED)
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandBody.java` (outbound JSON record)
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalHttpClient.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalHttpClientImpl.java` (production, uses Spring `RestClient`)
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/PayoutResultController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/dto/PayoutResultRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/TerminalCommandDispatcherJob.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/TerminalCommandDispatcherTask.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowCompletedEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowRefundCompletedEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowPayoutStalledEnvelope.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/MockTerminalHttpClient.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandDispatcherTaskTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/PayoutResultControllerSliceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/TerminalCommandRetryIntegrationTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowEndToEndIntegrationTest.java`

### `TerminalCommand.java`

```java
package com.slparcelauctions.backend.escrow.command;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "terminal_commands",
        indexes = {
                @Index(name = "ix_tcmd_status_next_attempt", columnList = "status, next_attempt_at"),
                @Index(name = "ix_tcmd_escrow", columnList = "escrow_id"),
                @Index(name = "ix_tcmd_listing_fee_refund", columnList = "listing_fee_refund_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tcmd_idempotency_key", columnNames = "idempotency_key")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TerminalCommand {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "escrow_id")
    private Long escrowId;

    @Column(name = "listing_fee_refund_id")
    private Long listingFeeRefundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TerminalCommandAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TerminalCommandPurpose purpose;

    @Column(name = "recipient_uuid", nullable = false, length = 36)
    private String recipientUuid;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TerminalCommandStatus status;

    @Column(name = "terminal_id", length = 100)
    private String terminalId;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "shared_secret_version", length = 20)
    private String sharedSecretVersion;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Builder.Default
    @Column(name = "requires_manual_review", nullable = false)
    private Boolean requiresManualReview = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
```

### `TerminalCommandRepository.java`

Custom queries:

```java
@Query("""
        SELECT c.id FROM TerminalCommand c
        WHERE (c.status = com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.QUEUED
            OR (c.status = com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.FAILED
                AND c.requiresManualReview = false))
          AND c.nextAttemptAt <= :now
        ORDER BY c.nextAttemptAt ASC
        """)
List<Long> findDispatchable(@Param("now") OffsetDateTime now);

@Query("""
        SELECT c.id FROM TerminalCommand c
        WHERE c.status = com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.IN_FLIGHT
          AND c.dispatchedAt < :cutoff
        """)
List<Long> findStaleInFlight(@Param("cutoff") OffsetDateTime cutoff);

@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM TerminalCommand c WHERE c.id = :id")
Optional<TerminalCommand> findByIdForUpdate(@Param("id") Long id);

Optional<TerminalCommand> findByIdempotencyKey(String idempotencyKey);

@Query("""
        SELECT COUNT(c) FROM TerminalCommand c
        WHERE c.escrowId = :escrowId
          AND c.action = com.slparcelauctions.backend.escrow.command.TerminalCommandAction.PAYOUT
          AND c.status IN (com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.QUEUED,
                           com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.IN_FLIGHT,
                           com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.FAILED)
        """)
long countActivePayoutCommands(@Param("escrowId") Long escrowId);
```

### `TerminalCommandService.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class TerminalCommandService {

    private final TerminalCommandRepository cmdRepo;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public TerminalCommand queuePayout(Escrow escrow) {
        return queue(escrow.getId(), null, TerminalCommandAction.PAYOUT,
                TerminalCommandPurpose.AUCTION_ESCROW,
                escrow.getAuction().getSeller().getSlAvatarUuid().toString(),
                escrow.getPayoutAmt(),
                "ESC-" + escrow.getId() + "-PAYOUT-1");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TerminalCommand queueRefund(Escrow escrow) {
        var auction = escrow.getAuction();
        var winnerUuid = /* lookup User.slAvatarUuid via winnerUserId */;
        return queue(escrow.getId(), null, TerminalCommandAction.REFUND,
                TerminalCommandPurpose.AUCTION_ESCROW,
                winnerUuid, escrow.getFinalBidAmount(),
                "ESC-" + escrow.getId() + "-REFUND-1");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TerminalCommand queueListingFeeRefund(ListingFeeRefund refund) {
        String recipientUuid = refund.getAuction().getSeller().getSlAvatarUuid().toString();
        return queue(null, refund.getId(), TerminalCommandAction.REFUND,
                TerminalCommandPurpose.LISTING_FEE_REFUND,
                recipientUuid, refund.getAmount(),
                "LFR-" + refund.getId() + "-1");
    }

    private TerminalCommand queue(Long escrowId, Long refundId,
            TerminalCommandAction action, TerminalCommandPurpose purpose,
            String recipientUuid, long amount, String idempotencyKey) {
        return cmdRepo.save(TerminalCommand.builder()
                .escrowId(escrowId)
                .listingFeeRefundId(refundId)
                .action(action)
                .purpose(purpose)
                .recipientUuid(recipientUuid)
                .amount(amount)
                .status(TerminalCommandStatus.QUEUED)
                .idempotencyKey(idempotencyKey)
                .nextAttemptAt(OffsetDateTime.now(clock))
                .attemptCount(0)
                .build());
    }
}
```

### `TerminalCommandDispatcherTask.java`

Two public methods (both `@Transactional`):

- `markStaleAndRequeue(Long commandId)` — re-locks, checks `IN_FLIGHT AND dispatched_at < cutoff`, transitions to `FAILED` with `errorMessage="IN_FLIGHT timeout without callback"`, `nextAttemptAt=now`.
- `dispatchOne(Long commandId)` — re-locks, skips non-dispatchable, picks any live terminal via `TerminalRepository.findAnyLive(now - liveWindow)`, stamps `terminal_id` + `status=IN_FLIGHT` + `attemptCount++` + `dispatchedAt=now`, POSTs to terminal via `TerminalHttpClient`. HTTP 2xx → leave IN_FLIGHT (callback completes). HTTP error → stamp FAILED with `nextAttemptAt` per backoff table (1/5/15 min), on 4th failure set `requiresManualReview=true` + publish `ESCROW_PAYOUT_STALLED` envelope.

### `TerminalCommandDispatcherJob.java`

```java
@Scheduled(fixedDelayString = "${slpa.escrow.command-dispatcher-job.fixed-delay:PT30S}")
public void dispatch() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    Duration inFlightTimeout = props.commandInFlightTimeout();
    // Prelude: requeue stale IN_FLIGHT
    cmdRepo.findStaleInFlight(now.minus(inFlightTimeout))
            .forEach(dispatcherTask::markStaleAndRequeue);
    // Main: dispatch QUEUED + retry-ready FAILED
    cmdRepo.findDispatchable(now).forEach(dispatcherTask::dispatchOne);
}
```

### `TerminalHttpClient.java`

Interface:

```java
public interface TerminalHttpClient {
    /** Returns true on HTTP 2xx ACK, false otherwise. Never throws for 4xx/5xx — wraps them. */
    TerminalHttpResult post(String url, TerminalCommandBody body);
    record TerminalHttpResult(boolean ack, String errorMessage) { }
}
```

`TerminalHttpClientImpl`:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class TerminalHttpClientImpl implements TerminalHttpClient {
    private final RestClient restClient = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build();

    @Override
    public TerminalHttpResult post(String url, TerminalCommandBody body) {
        try {
            restClient.post().uri(url).body(body).retrieve().toBodilessEntity();
            return new TerminalHttpResult(true, null);
        } catch (RestClientException e) {
            return new TerminalHttpResult(false, e.getMessage());
        }
    }
}
```

`MockTerminalHttpClient` (test-only) records received commands and allows scripting responses per idempotency key.

### `PayoutResultController.java`

```java
@PostMapping("/api/v1/sl/escrow/payout-result")
public SlCallbackResponse handlePayoutResult(
        @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
        @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
        @Valid @RequestBody PayoutResultRequest body) {
    headerValidator.validate(shard, ownerKey);
    terminalService.assertSharedSecret(body.sharedSecret());
    terminalCommandService.applyCallback(body);
    return SlCallbackResponse.ok();
}
```

`TerminalCommandService.applyCallback(PayoutResultRequest)` — locks command by idempotencyKey, idempotent on already-COMPLETED, on `success=true`:
- Mark COMPLETED; stamp `completed_at` + `slTransactionKey`.
- Write `EscrowTransaction` ledger row (type = AUCTION_ESCROW_PAYOUT / AUCTION_ESCROW_REFUND / LISTING_FEE_REFUND per purpose).
- For `action=PAYOUT purpose=AUCTION_ESCROW`: transition escrow `TRANSFER_PENDING → COMPLETED`, stamp `completed_at`, write `AUCTION_ESCROW_COMMISSION` ledger row, publish `ESCROW_COMPLETED` envelope.
- For `action=REFUND purpose=AUCTION_ESCROW`: publish `ESCROW_REFUND_COMPLETED` envelope.
- For `action=REFUND purpose=LISTING_FEE_REFUND`: flip `ListingFeeRefund.status=PROCESSED`, stamp `processed_at` + `txnRef` (Task 9 wires this path through — this task may leave a `// Task 9 integration` javadoc note with an explicit TODO on the *method body* only, replaced in Task 9).

On `success=false`: mark FAILED, increment `attemptCount`, schedule per backoff, on 4th failure stamp `requires_manual_review=true` + publish `ESCROW_PAYOUT_STALLED`.

### TDD steps

- [ ] **7.1: Implement entity, enums, repository** with 3 small persistence tests (save, findByIdempotencyKey unique, findStaleInFlight).
- [ ] **7.2: TDD `TerminalCommandService.queuePayout/queueRefund/queueListingFeeRefund`** — unit tests verifying idempotency-key format and body.
- [ ] **7.3: TDD `TerminalCommandDispatcherTaskTest`** — mock `TerminalHttpClient` + `TerminalRepository`, cover: no-live-terminals (QUEUED stays QUEUED, nextAttemptAt bumped), happy path (IN_FLIGHT after success), HTTP failure (FAILED with 1min backoff), 4th failure (requires_manual_review + ESCROW_PAYOUT_STALLED envelope), IN_FLIGHT staleness (markStaleAndRequeue).
- [ ] **7.4: TDD `PayoutResultControllerSliceTest`** — success-payout, success-refund, failure, duplicate idempotency key (OK idempotent), unknown idempotency key (404), bad secret (403).
- [ ] **7.5: Integration test `TerminalCommandRetryIntegrationTest`** — force 4 consecutive HTTP failures via `MockTerminalHttpClient`, assert final state = FAILED with `requires_manual_review=true` + `ESCROW_PAYOUT_STALLED` envelope broadcast.
- [ ] **7.6: Integration test `EscrowEndToEndIntegrationTest`** — FULL happy path: auction ends SOLD (Task 2) → payment received (Task 5) → ownership monitor detects winner (Task 6) → dispatcher POSTs payout → callback `success=true` → escrow state=COMPLETED + commission ledger row + envelopes at each stage.
- [ ] **7.7: Wire `EscrowService.confirmTransfer` to call `terminalCommandService.queuePayout(escrow)`** (replaces Task 6's stubbed call).
- [ ] **7.8: Wire `EscrowService.freezeForFraud` to call `terminalCommandService.queueRefund(escrow)`**.
- [ ] **7.9: Wire `EscrowService.fileDispute` from Task 3 to call `terminalCommandService.queueRefund(escrow)` when `funded_at != null`.**
- [ ] **7.10: Commit — `feat(escrow): terminal command dispatcher + payout callback + retry`**

---

## Task 8: Timeout job + payout-in-flight guard

**Spec reference:** §6.1 (`EscrowTimeoutJob`), §4.2 (refund-on-timeout rule).

**Goal:** Ship the 5min timeout sweeper covering both 48h ESCROW_PENDING → EXPIRED (no refund) and 72h TRANSFER_PENDING → EXPIRED (refund queued), with the payout-in-flight exclusion on the TRANSFER_PENDING query.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/EscrowTimeoutJob.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/EscrowTimeoutTask.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowExpiredEnvelope.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/scheduler/EscrowTimeoutTaskTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowTimeoutIntegrationTest.java`
- Modify: `EscrowRepository.java` — add `findExpiredPending(now)` + `findExpiredTransferPending(now)` queries.
- Modify: `EscrowService.java` — add `expirePayment(escrowId, now)` + `expireTransfer(escrowId, now)` methods.
- Modify: broadcast package — add `EscrowExpiredEnvelope` + `publishExpired` method.

### Repository queries

```java
@Query("""
        SELECT e.id FROM Escrow e
        WHERE e.state = com.slparcelauctions.backend.escrow.EscrowState.ESCROW_PENDING
          AND e.paymentDeadline < :now
        """)
List<Long> findExpiredPendingIds(@Param("now") OffsetDateTime now);

@Query("""
        SELECT e.id FROM Escrow e
        WHERE e.state = com.slparcelauctions.backend.escrow.EscrowState.TRANSFER_PENDING
          AND e.transferDeadline < :now
          AND (e.transferConfirmedAt IS NULL
               OR NOT EXISTS (
                  SELECT 1 FROM TerminalCommand c
                  WHERE c.escrowId = e.id
                    AND c.action = com.slparcelauctions.backend.escrow.command.TerminalCommandAction.PAYOUT
                    AND c.status IN (
                        com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.QUEUED,
                        com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.IN_FLIGHT,
                        com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.FAILED)
               ))
        """)
List<Long> findExpiredTransferPendingIds(@Param("now") OffsetDateTime now);
```

### TDD steps

- [ ] **8.1: Write `EscrowTimeoutTaskTest.java` (RED)** — unit tests covering: expirePayment stamps state=EXPIRED + expired_at + broadcasts ESCROW_EXPIRED with reason=PAYMENT_TIMEOUT, does NOT queue refund. expireTransfer does same with reason=TRANSFER_TIMEOUT AND queues refund via terminalCommandService.
- [ ] **8.2: Implement `EscrowTimeoutTask` + `EscrowService.expirePayment/expireTransfer` methods (GREEN)**.
- [ ] **8.3: Write `EscrowTimeoutIntegrationTest.java`** — seed escrows in ESCROW_PENDING + TRANSFER_PENDING, advance `ClockOverrideConfig.MutableFixedClock` past 48h/72h, force `sweep()`, assert state+refund queuing. Critical test: TRANSFER_PENDING escrow past 72h with `transferConfirmedAt != null` + active PAYOUT command does NOT expire.
- [ ] **8.4: Create `EscrowTimeoutJob`** — same shape as the ownership monitor job (sweep, loop with try/catch isolation).
- [ ] **8.5: Commit — `feat(escrow): timeout job with payout-in-flight guard`**
- [ ] **8.6: Full suite green.**

---

## Task 9: Listing-fee integration (payment callback + refund processor)

**Spec reference:** §10 (listing-fee flow integration), DEFERRED_WORK entries "Listing fee refund processor", "Real in-world listing-fee terminal".

**Goal:** Ship `POST /api/v1/sl/listing-fee/payment` replacing the dev-only `POST /api/v1/dev/auctions/{id}/pay` for production traffic, add the `ListingFeeRefundProcessorJob` that drains `ListingFeeRefund WHERE status=PENDING`, wire the payout-result callback to flip `ListingFeeRefund.status=PROCESSED` on refund success, and close out the three deferred-work entries (Listing fee refund processor, Real in-world listing-fee terminal, Escrow handoff from ENDED+SOLD) while opening the new ones from §11 of the spec.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/ListingFeePaymentController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/ListingFeePaymentService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/payment/dto/ListingFeePaymentRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/ListingFeeRefundProcessorJob.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/payment/ListingFeePaymentControllerSliceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/ListingFeeFlowIntegrationTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/ListingFeeRefund.java` — add `terminalCommandId` (Long, nullable) + `lastQueuedAt` (OffsetDateTime, nullable) columns.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/ListingFeeRefundRepository.java` — add `findPendingAwaitingDispatch()` query.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java` — complete the `applyCallback` branch for `purpose=LISTING_FEE_REFUND` per Task 7.

### `ListingFeePaymentService` flow

Validates: SL headers, shared secret, idempotency on `slTransactionKey`, auction exists, `status=DRAFT` AND `seller.slAvatarUuid == payerUuid` AND `amount == listingFeeAmount`. On match: delegate to `AuctionVerificationService` (or the existing helper that handles `DRAFT → DRAFT_PAID` for the dev endpoint) to transition + stamp `txnRef=slTransactionKey`. Write ledger row type=`LISTING_FEE_PAYMENT`. Response `SlCallbackResponse.ok()`. Mismatches return REFUND (wrong payer/amount) or ERROR (already paid / unknown auction) per spec §5.4.

### `ListingFeeRefundProcessorJob`

```java
@Scheduled(fixedDelayString = "${slpa.escrow.listing-fee-refund-job.fixed-delay:PT1M}")
public void drainPending() {
    List<ListingFeeRefund> refunds = listingFeeRefundRepo.findPendingAwaitingDispatch();
    for (ListingFeeRefund refund : refunds) {
        try {
            processOne(refund.getId());
        } catch (RuntimeException e) {
            log.error("ListingFeeRefund {} queue failure: {}", refund.getId(), e.getMessage(), e);
        }
    }
}

@Transactional
void processOne(Long refundId) {
    ListingFeeRefund r = listingFeeRefundRepo.findByIdForUpdate(refundId).orElseThrow();
    if (r.getStatus() != RefundStatus.PENDING) return;
    if (r.getTerminalCommandId() != null) return;  // already queued
    TerminalCommand cmd = terminalCommandService.queueListingFeeRefund(r);
    r.setTerminalCommandId(cmd.getId());
    r.setLastQueuedAt(OffsetDateTime.now(clock));
}
```

### TDD steps

- [ ] **9.1: Extend `ListingFeeRefund` entity + repository.** Add unit test for `findPendingAwaitingDispatch` returning only `status=PENDING AND terminal_command_id IS NULL`.

- [ ] **9.2: TDD `ListingFeePaymentControllerSliceTest`** — 7 cases: valid, wrong payer, wrong amount, already paid (DRAFT_PAID state), unknown auction, bad headers, bad secret.

- [ ] **9.3: Implement `ListingFeePaymentController` + `ListingFeePaymentService` (GREEN).**

- [ ] **9.4: Wire `TerminalCommandService.applyCallback` LISTING_FEE_REFUND branch** — flip `ListingFeeRefund.status=PROCESSED`, stamp `processed_at` + `txnRef`. Write ledger row type=`LISTING_FEE_REFUND`.

- [ ] **9.5: TDD `ListingFeeFlowIntegrationTest`** — full loop: SL endpoint drives DRAFT → DRAFT_PAID; seller cancels; `CancellationService` creates PENDING refund row; `ListingFeeRefundProcessorJob.drainPending()` queues a TerminalCommand; dispatcher dispatches; callback with `success=true` flips status to PROCESSED.

- [ ] **9.6: Implement `ListingFeeRefundProcessorJob` (GREEN).**

- [ ] **9.7: Commit — `feat(escrow): listing-fee payment callback + refund processor`**

- [ ] **9.8: Documentation sweep**
  - Remove from `docs/implementation/DEFERRED_WORK.md`:
    - "Listing fee refund processor"
    - "Real in-world listing-fee terminal"
    - "Escrow handoff from ENDED + SOLD"
  - Update "Primary escrow UUID + SLPA trusted-owner-keys production config" — add note that EscrowStartupValidator now fails fast on empty shared secret.
  - Add the six new DEFERRED_WORK entries from spec §11 (HMAC upgrade, regional routing, notifications, admin tooling for DISPUTED/FROZEN + secret rotation, balance reconciliation, Clock retrofit, AuctionEndedPanel frontend follow-up).
  - Add `FOOTGUNS.md` entries for: consecutive-failure threshold rationale, payout-in-flight guard on timeout job, enum-expansion-from-day-one, Clock injection discipline, pool-not-sticky terminal model, idempotency-key format.
  - Update `README.md` — bump "Implementation Status" line to note Epic 05 sub-spec 1 complete; add escrow orchestration to the backend services list.

- [ ] **9.9: Commit — `docs: Epic 05 sub-spec 1 — DEFERRED_WORK closures + openings, FOOTGUNS, README sweep`**

- [ ] **9.10: Full suite green.** Confirm total test count has grown by ~60-80 over the Epic 04 baseline (~621 → ~680-700).

---

## Completion checklist

- [ ] All 9 tasks merged through two-stage review (spec + code quality) per subagent-driven-development.
- [ ] Full backend suite green (`./mvnw test`).
- [ ] `docs/implementation/DEFERRED_WORK.md` swept.
- [ ] `docs/implementation/FOOTGUNS.md` augmented.
- [ ] `README.md` updated.
- [ ] Branch pushed; PR opened against `dev`; no AI/tool attribution in commits or PR body; squash-merge on green review.
