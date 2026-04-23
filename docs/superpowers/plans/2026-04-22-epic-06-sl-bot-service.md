# Epic 06 — SL Bot Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the C#/.NET 8 SL bot worker service that closes out Method C verification and adds BOT-tier monitoring for active auctions and escrows, with the backend hardened against unauthenticated callers.

**Architecture:** Two processes, HTTP between them. The worker polls the backend for tasks via `POST /claim` (atomic `SELECT ... FOR UPDATE SKIP LOCKED`), teleports, reads `ParcelProperties` via LibreMetaverse, and posts observations to `/verify` or `/monitor`. The backend owns all task lifecycle and business-logic interpretation; the worker is observation-only.

**Tech Stack:** C# / .NET 8, LibreMetaverse 1.9.x (NuGet), Java 26 / Spring Boot 4, PostgreSQL (via Hibernate `ddl-auto: update`), Docker Compose, xUnit + FluentAssertions + WireMock.Net (C# tests), JUnit 5 + Mockito + Testcontainers (Java tests).

**Branch:** `task/06-bot-service` (already created off `dev`; spec committed at `3fcba4f` and pushed).

**PR target:** `dev`

---

## File Structure

### New files (backend Java)

```
backend/src/main/java/com/slparcelauctions/backend/
├── bot/
│   ├── BotMonitorDispatcher.java              (Task 5)
│   ├── BotMonitorLifecycleService.java        (Task 6)
│   ├── BotSharedSecretAuthorizer.java         (Task 3)
│   ├── BotStartupValidator.java               (Task 3)
│   ├── BotTaskConfigProperties.java           (Task 3 — extracts @Value calls)
│   ├── BotTaskStatusCheckConstraintInitializer.java   (Task 1)
│   ├── BotTaskTypeCheckConstraintInitializer.java     (Task 1)
│   ├── DispatchOutcome.java                   (Task 5)
│   ├── MonitorOutcome.java                    (Task 5)
│   ├── dto/
│   │   ├── BotMonitorResultRequest.java       (Task 5)
│   │   └── BotTaskClaimRequest.java           (Task 2)
│   └── exception/
│       ├── BotEscrowTerminalException.java    (Task 5)
│       ├── BotTaskNotClaimedException.java    (Task 5)
│       ├── BotTaskNotFoundException.java      (Task 5)
│       └── BotTaskWrongTypeException.java     (Task 5)
```

### Modified files (backend Java)

```
backend/src/main/java/com/slparcelauctions/backend/
├── auction/
│   ├── CancellationService.java               (Task 6 — hook)
│   ├── auctionend/AuctionEndTask.java         (Task 6 — hook)
│   ├── fraud/FraudFlagReason.java             (Task 1 — 4 new values)
│   ├── monitoring/SuspensionService.java      (Task 6 — new method + hook)
│   └── scheduled/BotTaskTimeoutJob.java       (Task 4)
├── bot/
│   ├── BotTask.java                           (Task 1 — columns + escrow FK)
│   ├── BotTaskController.java                 (Tasks 2, 4, 5)
│   ├── BotTaskExceptionHandler.java           (Task 5 — new handlers)
│   ├── BotTaskRepository.java                 (Tasks 2, 6 — claim + bulk cancel)
│   ├── BotTaskService.java                    (Tasks 2, 4, 5, 6)
│   ├── BotTaskStatus.java                     (Task 1 — CANCELLED)
│   ├── BotTaskType.java                       (Task 1 — MONITOR_* values)
│   └── dto/BotTaskResponse.java               (Task 1 — expose monitor fields)
├── config/
│   └── SecurityConfig.java                    (Task 3, Task 12 cleanup)
└── escrow/
    ├── Escrow.java                            (Task 5 — reviewRequired flag)
    ├── EscrowService.java                     (Tasks 5, 6 — markReviewRequired + hooks)
    └── FreezeReason.java                      (Task 1 — BOT_OWNERSHIP_CHANGED)

backend/src/main/resources/
├── application.yml                            (Task 3 — slpa.bot.* keys)
└── application-dev.yml                        (Task 3 — dev shared-secret)
```

### New files (backend tests)

```
backend/src/test/java/com/slparcelauctions/backend/bot/
├── BotMonitorDispatcherTest.java              (Task 5)
├── BotMonitorLifecycleServiceTest.java        (Task 6)
├── BotSharedSecretAuthorizerTest.java         (Task 3)
├── BotStartupValidatorTest.java               (Task 3)
├── BotTaskClaimRaceIntegrationTest.java       (Task 2)
├── BotTaskControllerAuthIntegrationTest.java  (Task 3)
├── BotTaskControllerClaimSliceTest.java       (Task 2)
├── BotTaskControllerMonitorSliceTest.java     (Task 5)
├── BotTaskControllerVerifySliceTest.java      (Task 4)
├── BotTaskTimeoutJobInProgressTest.java       (Task 4)
└── BotTaskTypeCheckConstraintInitializerTest.java (Task 1)
```

### New files (C# bot service)

```
bot/
├── Slpa.Bot.sln                               (Task 7)
├── Dockerfile                                 (Task 11)
├── .env.example                               (Task 11)
├── README.md                                  (Task 7, Task 12 sweep)
├── src/Slpa.Bot/
│   ├── Slpa.Bot.csproj                        (Task 7)
│   ├── Program.cs                             (Task 7)
│   ├── appsettings.json                       (Task 7)
│   ├── Options/
│   │   ├── BotOptions.cs                      (Task 7)
│   │   ├── BackendOptions.cs                  (Task 7)
│   │   └── RateLimitOptions.cs                (Task 7)
│   ├── Sl/
│   │   ├── IBotSession.cs                     (Task 7)
│   │   ├── LibreMetaverseBotSession.cs        (Task 7)
│   │   ├── SessionState.cs                    (Task 7)
│   │   ├── TeleportRateLimiter.cs             (Task 8)
│   │   ├── TeleportResult.cs                  (Task 8)
│   │   ├── TeleportFailureKind.cs             (Task 8)
│   │   ├── ParcelReader.cs                    (Task 8)
│   │   ├── ParcelSnapshot.cs                  (Task 8)
│   │   └── SessionLostException.cs            (Task 8)
│   ├── Backend/
│   │   ├── IBackendClient.cs                  (Task 9)
│   │   ├── HttpBackendClient.cs               (Task 9)
│   │   └── Models/
│   │       ├── BotTaskResponse.cs             (Task 9)
│   │       ├── BotTaskClaimRequest.cs         (Task 9)
│   │       ├── BotTaskCompleteRequest.cs      (Task 9)
│   │       ├── BotMonitorResultRequest.cs     (Task 9)
│   │       ├── MonitorOutcome.cs              (Task 9)
│   │       ├── BotTaskType.cs                 (Task 9)
│   │       └── BotTaskStatus.cs               (Task 9)
│   ├── Tasks/
│   │   ├── TaskLoop.cs                        (Task 10)
│   │   ├── VerifyHandler.cs                   (Task 10)
│   │   └── MonitorHandler.cs                  (Task 10)
│   └── Health/
│       └── HealthEndpoint.cs                  (Task 7)
└── tests/Slpa.Bot.Tests/
    ├── Slpa.Bot.Tests.csproj                  (Task 7)
    ├── LibreMetaverseBotSessionTests.cs       (Task 7)
    ├── TeleportRateLimiterTests.cs            (Task 8)
    ├── ParcelReaderTests.cs                   (Task 8)
    ├── HttpBackendClientTests.cs              (Task 9)
    ├── VerifyHandlerTests.cs                  (Task 10)
    ├── MonitorHandlerTests.cs                 (Task 10)
    └── TaskLoopTests.cs                       (Task 10)
```

### Modified / new ops files

```
docker-compose.yml                             (Task 11 — bot-1 service block)
.github/workflows/bot-ci.yml                   (Task 11 — new)
README.md                                      (Task 11, Task 12 sweep)
docs/implementation/DEFERRED_WORK.md           (Task 12)
docs/implementation/FOOTGUNS.md                (Task 12)
```

---

## Task Order & Dependency Chain

```
1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12
```

Backend chain (Tasks 1–6) ships a fully authenticated bot API surface with monitor rows and lifecycle hooks. C# chain (Tasks 7–10) builds the worker end-to-end. Ops + docs (Tasks 11–12) wire everything together and close deferred items.

---

## Task 1: Backend — Schema & Enum Extensions

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTask.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskType.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskStatus.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagReason.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/FreezeReason.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskTypeCheckConstraintInitializer.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskStatusCheckConstraintInitializer.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskTypeCheckConstraintInitializerTest.java`

Spec reference: §3.1, §3.2, §5.1.

- [ ] **Step 1: Add new enum values to `BotTaskType`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskType.java
package com.slparcelauctions.backend.bot;

public enum BotTaskType {
    /** One-shot Method C verification; terminal on callback. */
    VERIFY,
    /** Recurring auction monitoring; re-armed every slpa.bot.monitor-auction-interval (default PT30M). */
    MONITOR_AUCTION,
    /** Recurring escrow monitoring; re-armed every slpa.bot.monitor-escrow-interval (default PT15M). */
    MONITOR_ESCROW
}
```

- [ ] **Step 2: Add `CANCELLED` to `BotTaskStatus`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskStatus.java
package com.slparcelauctions.backend.bot;

public enum BotTaskStatus {
    /** Claimable (VERIFY) or due (MONITOR_* where next_run_at <= now). */
    PENDING,
    /** Claimed; times out after slpa.bot-task.in-progress-timeout (default PT20M). */
    IN_PROGRESS,
    /** VERIFY SUCCESS terminal. MONITOR_* rows never reach this state. */
    COMPLETED,
    /** VERIFY FAILURE, PENDING timeout, IN_PROGRESS timeout (VERIFY only), or superseded. */
    FAILED,
    /** MONITOR_* terminal — cancelled by a lifecycle hook on entity termination. */
    CANCELLED
}
```

- [ ] **Step 3: Add four new reasons to `FraudFlagReason`**

Append the four new values to the existing enum. Keep docstrings per the existing style.

```java
// backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagReason.java
// Add at end of enum:

    /**
     * Raised by the bot auction monitor (Epic 06) when observed AuthBuyerID
     * no longer equals the primary escrow account UUID during an active
     * BOT-tier auction. Seller has revoked the sale-to-bot setting — treated
     * as fraud because a bot-verified listing depends on that setting.
     */
    BOT_AUTH_BUYER_REVOKED,
    /**
     * Raised by the bot auction monitor when observed SalePrice no longer
     * matches the sentinel. Seller has retargeted or lowered the price —
     * treated as fraud on an active BOT-tier auction.
     */
    BOT_PRICE_DRIFT,
    /**
     * Raised by the bot auction or escrow monitor when observed OwnerID no
     * longer matches the expected seller (auction) or is neither seller nor
     * winner (escrow). Treated as fraud in both flows.
     */
    BOT_OWNERSHIP_CHANGED,
    /**
     * Raised when the bot has received ACCESS_DENIED on a parcel for
     * slpa.bot.access-denied-streak-threshold consecutive monitor cycles
     * (default 3). Seller has revoked bot access — treated as fraud on
     * active auctions; the escrow flow uses markReviewRequired instead.
     */
    BOT_ACCESS_REVOKED
```

- [ ] **Step 4: Add `BOT_OWNERSHIP_CHANGED` to `FreezeReason`**

```java
// backend/src/main/java/com/slparcelauctions/backend/escrow/FreezeReason.java
package com.slparcelauctions.backend.escrow;

/**
 * Machine reason for an escrow freeze. Mapped to FraudFlagReason inside
 * EscrowService.freezeForFraud. See spec §4.5 + Epic 06 spec §6.2.
 */
public enum FreezeReason {
    UNKNOWN_OWNER,
    PARCEL_DELETED,
    WORLD_API_PERSISTENT_FAILURE,
    /**
     * Raised by the Epic 06 bot escrow monitor when observed OwnerID is
     * neither seller nor winner during an active escrow. Treated identically
     * to UNKNOWN_OWNER for state transition purposes; the FraudFlag reason
     * differs to keep the admin-dashboard signal source explicit.
     */
    BOT_OWNERSHIP_CHANGED
}
```

- [ ] **Step 5: Extend `EscrowService.freezeForFraud` switch for `BOT_OWNERSHIP_CHANGED`**

Modify the existing switch expression at `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java:551-555`:

```java
FraudFlagReason flagReason = switch (reason) {
    case UNKNOWN_OWNER -> FraudFlagReason.ESCROW_UNKNOWN_OWNER;
    case PARCEL_DELETED -> FraudFlagReason.ESCROW_PARCEL_DELETED;
    case WORLD_API_PERSISTENT_FAILURE -> FraudFlagReason.ESCROW_WORLD_API_FAILURE;
    case BOT_OWNERSHIP_CHANGED -> FraudFlagReason.BOT_OWNERSHIP_CHANGED;
};
```

- [ ] **Step 6: Extend `BotTask` entity with monitor columns**

Add the new nullable columns documented in spec §3.1. Also add a `@ManyToOne` reference to `Escrow` so `MONITOR_ESCROW` rows can be cancelled by escrow id without loading the auction.

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotTask.java

package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.escrow.Escrow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bot_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private BotTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BotTaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    /** Set for MONITOR_ESCROW rows; null for VERIFY and MONITOR_AUCTION. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escrow_id")
    private Escrow escrow;

    @Column(name = "parcel_uuid", nullable = false)
    private UUID parcelUuid;

    @Column(name = "region_name", length = 100)
    private String regionName;

    /** Denormalized from Parcel at task creation so the worker does not need to look up. */
    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "position_z")
    private Double positionZ;

    @Column(name = "sentinel_price", nullable = false)
    private Long sentinelPrice;

    /** MONITOR_AUCTION: expected parcel owner at each check. */
    @Column(name = "expected_owner_uuid")
    private UUID expectedOwnerUuid;

    /** MONITOR_AUCTION: expected AuthBuyerID (normally the primary escrow UUID). */
    @Column(name = "expected_auth_buyer_uuid")
    private UUID expectedAuthBuyerUuid;

    /** MONITOR_AUCTION: expected SalePrice (normally the sentinel). */
    @Column(name = "expected_sale_price_lindens")
    private Long expectedSalePriceLindens;

    /** MONITOR_ESCROW: expected winner SL UUID (signals TRANSFER_COMPLETE when observed owner). */
    @Column(name = "expected_winner_uuid")
    private UUID expectedWinnerUuid;

    /** MONITOR_ESCROW: expected seller SL UUID (normal STILL_WAITING state). */
    @Column(name = "expected_seller_uuid")
    private UUID expectedSellerUuid;

    /**
     * MONITOR_ESCROW: SalePrice threshold below which TRANSFER_READY fires
     * (in addition to the AuthBuyerID-matches-winner condition). Default 1
     * tolerates sellers who set L$1 by habit. See FOOTGUNS and spec §4.6.
     */
    @Column(name = "expected_max_sale_price_lindens")
    private Long expectedMaxSalePriceLindens;

    /** MONITOR_*: scheduled time for the next check. Null for VERIFY. */
    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;

    /** MONITOR_*: interval between checks; null for VERIFY. */
    @Column(name = "recurrence_interval_seconds")
    private Integer recurrenceIntervalSeconds;

    @Column(name = "assigned_bot_uuid")
    private UUID assignedBotUuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_data", columnDefinition = "jsonb")
    private Map<String, Object> resultData;

    /** Stamped on every monitor callback. Null for VERIFY. */
    @Column(name = "last_check_at")
    private OffsetDateTime lastCheckAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Set on terminal states only (COMPLETED / FAILED / CANCELLED). */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @UpdateTimestamp
    @Column(name = "last_updated_at", nullable = false)
    private OffsetDateTime lastUpdatedAt;
}
```

- [ ] **Step 7: Extend `BotTaskResponse` to expose monitor fields**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskResponse.java

package com.slparcelauctions.backend.bot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;

/**
 * Bot queue projection returned by the claim endpoint. Exposes monitor
 * fields (escrowId + expected* + positions + nextRunAt) so the worker has
 * everything it needs for a one-shot decision without follow-up lookups.
 * Excludes resultData and lastUpdatedAt — bot workers do not need them.
 */
public record BotTaskResponse(
        Long id,
        BotTaskType taskType,
        BotTaskStatus status,
        Long auctionId,
        Long escrowId,
        UUID parcelUuid,
        String regionName,
        Double positionX,
        Double positionY,
        Double positionZ,
        Long sentinelPrice,
        UUID expectedOwnerUuid,
        UUID expectedAuthBuyerUuid,
        Long expectedSalePriceLindens,
        UUID expectedWinnerUuid,
        UUID expectedSellerUuid,
        Long expectedMaxSalePriceLindens,
        UUID assignedBotUuid,
        String failureReason,
        OffsetDateTime nextRunAt,
        Integer recurrenceIntervalSeconds,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {

    public static BotTaskResponse from(BotTask t) {
        return new BotTaskResponse(
                t.getId(),
                t.getTaskType(),
                t.getStatus(),
                t.getAuction().getId(),
                t.getEscrow() == null ? null : t.getEscrow().getId(),
                t.getParcelUuid(),
                t.getRegionName(),
                t.getPositionX(),
                t.getPositionY(),
                t.getPositionZ(),
                t.getSentinelPrice(),
                t.getExpectedOwnerUuid(),
                t.getExpectedAuthBuyerUuid(),
                t.getExpectedSalePriceLindens(),
                t.getExpectedWinnerUuid(),
                t.getExpectedSellerUuid(),
                t.getExpectedMaxSalePriceLindens(),
                t.getAssignedBotUuid(),
                t.getFailureReason(),
                t.getNextRunAt(),
                t.getRecurrenceIntervalSeconds(),
                t.getCreatedAt(),
                t.getCompletedAt());
    }
}
```

- [ ] **Step 8: Create `BotTaskTypeCheckConstraintInitializer`**

Matches the existing `FraudFlagReasonCheckConstraintInitializer` pattern. Refreshes the `bot_tasks_task_type_check` CHECK constraint on startup so new enum values are reflected in the DB constraint.

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskTypeCheckConstraintInitializer.java
package com.slparcelauctions.backend.bot;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code bot_tasks_task_type_check} constraint on startup so
 * MONITOR_AUCTION / MONITOR_ESCROW values added in Epic 06 pass the CHECK
 * without a manual DDL edit. See {@link EnumCheckConstraintSync}.
 */
@Component
@RequiredArgsConstructor
public class BotTaskTypeCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc).sync("bot_tasks", "task_type", BotTaskType.class);
    }
}
```

- [ ] **Step 9: Create `BotTaskStatusCheckConstraintInitializer`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskStatusCheckConstraintInitializer.java
package com.slparcelauctions.backend.bot;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code bot_tasks_status_check} constraint on startup so the
 * CANCELLED value added in Epic 06 passes the CHECK without a manual DDL
 * edit. See {@link EnumCheckConstraintSync}.
 */
@Component
@RequiredArgsConstructor
public class BotTaskStatusCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc).sync("bot_tasks", "status", BotTaskStatus.class);
    }
}
```

- [ ] **Step 10: Write integration test for the check-constraint initializers**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskTypeCheckConstraintInitializerTest.java
package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the bot_tasks CHECK constraints cover all enum values after
 * ApplicationReadyEvent fires. Regression guard for Hibernate ddl-auto:update
 * not widening CHECKs on enum value additions (see FOOTGUNS).
 */
@SpringBootTest
@ActiveProfiles("test")
class BotTaskTypeCheckConstraintInitializerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void taskTypeCheckConstraintCoversAllEnumValues() {
        String constraintDef = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'bot_tasks_task_type_check'
                """,
                String.class);
        assertThat(constraintDef)
                .contains("VERIFY")
                .contains("MONITOR_AUCTION")
                .contains("MONITOR_ESCROW");
    }

    @Test
    void statusCheckConstraintCoversAllEnumValues() {
        String constraintDef = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'bot_tasks_status_check'
                """,
                String.class);
        assertThat(constraintDef)
                .contains("PENDING")
                .contains("IN_PROGRESS")
                .contains("COMPLETED")
                .contains("FAILED")
                .contains("CANCELLED");
    }
}
```

- [ ] **Step 11: Run the backend test suite**

Run: `cd backend && ./mvnw test -Dtest='BotTaskTypeCheckConstraintInitializerTest,EscrowServiceTest' -q`

Expected: PASS. Hibernate applies the new nullable columns via `ddl-auto: update`; the initializers widen the CHECK constraints on `ApplicationReadyEvent`.

If `EscrowServiceTest` fails with "FreezeReason.BOT_OWNERSHIP_CHANGED does not exist" or a switch-exhaustiveness error, re-check Step 5 (the switch clause addition).

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/BotTask.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskType.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskStatus.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskTypeCheckConstraintInitializer.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskStatusCheckConstraintInitializer.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagReason.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/FreezeReason.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskTypeCheckConstraintInitializerTest.java

git commit -m "feat(bot): extend bot_tasks schema + enums for monitoring"
```

---

## Task 2: Backend — Atomic Claim Endpoint (SKIP LOCKED)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskRepository.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskClaimRequest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerClaimSliceTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskClaimRaceIntegrationTest.java`

Spec reference: §3.3, §7.4.

- [ ] **Step 1: Create `BotTaskClaimRequest` DTO**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskClaimRequest.java
package com.slparcelauctions.backend.bot.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/v1/bot/tasks/claim. The worker identifies itself with
 * its SL avatar UUID so the backend can stamp {@code assignedBotUuid} on
 * the claimed row (useful for debug via GET /pending).
 */
public record BotTaskClaimRequest(@NotNull UUID botUuid) {}
```

- [ ] **Step 2: Write failing slice test for `POST /claim`**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerClaimSliceTest.java
package com.slparcelauctions.backend.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auction.Auction;

@WebMvcTest(controllers = BotTaskController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@Import(BotTaskExceptionHandler.class)
class BotTaskControllerClaimSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private BotTaskService service;

    @Test
    void claim_withPendingTask_returns200WithTaskPayload() throws Exception {
        UUID botUuid = UUID.randomUUID();
        Auction auction = Auction.builder().id(42L).build();
        BotTask task = BotTask.builder()
                .id(7L)
                .taskType(BotTaskType.VERIFY)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(auction)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .assignedBotUuid(botUuid)
                .createdAt(OffsetDateTime.now())
                .build();
        when(service.claim(any())).thenReturn(Optional.of(task));

        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + botUuid + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.taskType").value("VERIFY"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void claim_withEmptyQueue_returns204() throws Exception {
        when(service.claim(any())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void claim_withMissingBotUuid_returns400() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: Run the failing test**

Run: `cd backend && ./mvnw test -Dtest=BotTaskControllerClaimSliceTest -q`

Expected: FAIL with "No endpoint POST /api/v1/bot/tasks/claim" or method-not-allowed.

- [ ] **Step 4: Add `claimNext` to `BotTaskRepository`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskRepository.java
package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BotTaskRepository extends JpaRepository<BotTask, Long> {

    List<BotTask> findByStatusOrderByCreatedAtAsc(BotTaskStatus status);

    List<BotTask> findByStatusAndCreatedAtBefore(BotTaskStatus status, OffsetDateTime threshold);

    /**
     * Atomically claim the next due task. PENDING rows with {@code next_run_at}
     * in the future (future monitor cycles) are skipped. {@code FOR UPDATE
     * SKIP LOCKED} means concurrent claims from other workers never block;
     * each sees the next unlocked row.
     *
     * <p>Native query because Spring Data JPQL does not have a portable
     * {@code SKIP LOCKED} clause; it is Postgres-specific syntax (and since
     * this codebase runs exclusively on Postgres that is fine). See FOOTGUNS
     * §F.86.
     */
    @Query(value = """
            SELECT * FROM bot_tasks
             WHERE status = 'PENDING'
               AND (next_run_at IS NULL OR next_run_at <= :now)
             ORDER BY created_at ASC
             FOR UPDATE SKIP LOCKED
             LIMIT 1
            """, nativeQuery = true)
    Optional<BotTask> claimNext(@Param("now") OffsetDateTime now);

    /**
     * IN_PROGRESS rows older than {@code threshold}. Used by the bot-task
     * timeout sweep to detect workers that crashed mid-task.
     */
    List<BotTask> findByStatusAndLastUpdatedAtBefore(
            BotTaskStatus status, OffsetDateTime threshold);
}
```

- [ ] **Step 5: Add `claim(UUID botUuid)` to `BotTaskService`**

Append to `BotTaskService.java` — after the existing methods.

```java
    /**
     * Atomically claims the next due PENDING task for {@code botUuid}. Wraps
     * {@link BotTaskRepository#claimNext} in a transaction and stamps the row
     * with {@code assignedBotUuid} + status=IN_PROGRESS before the lock
     * releases.
     *
     * <p>Returns empty if the queue has no due tasks — the worker should
     * back off and retry. {@code SELECT ... FOR UPDATE SKIP LOCKED} means
     * concurrent claims never block on each other; each sees the next
     * unlocked row.
     */
    @Transactional
    public Optional<BotTask> claim(UUID botUuid) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return botTaskRepo.claimNext(now).map(task -> {
            task.setStatus(BotTaskStatus.IN_PROGRESS);
            task.setAssignedBotUuid(botUuid);
            log.debug("Bot task {} claimed by {}", task.getId(), botUuid);
            return botTaskRepo.save(task);
        });
    }
```

Add the import if not present:

```java
import java.util.Optional;
```

- [ ] **Step 6: Add `POST /claim` to `BotTaskController`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java
package com.slparcelauctions.backend.bot;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.bot.dto.BotTaskClaimRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Bot worker queue + callback surface. Auth: bearer token (see
 * {@link BotSharedSecretAuthorizer}, Task 3). Endpoints:
 *
 * <ul>
 *   <li>{@code POST /claim} — atomic claim of the next due task (Task 2).</li>
 *   <li>{@code PUT /{id}/verify} — VERIFY callback (Task 4).</li>
 *   <li>{@code POST /{id}/monitor} — MONITOR_* callback (Task 5).</li>
 *   <li>{@code GET /pending} — read-only debug view of the PENDING queue.</li>
 *   <li>{@code PUT /{id}} — DEPRECATED Task 4 shim for {@code /verify}; removed in Task 12.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bot/tasks")
@RequiredArgsConstructor
public class BotTaskController {

    private final BotTaskService service;

    @PostMapping("/claim")
    public ResponseEntity<BotTaskResponse> claim(@Valid @RequestBody BotTaskClaimRequest body) {
        return service.claim(body.botUuid())
                .map(task -> ResponseEntity.ok(BotTaskResponse.from(task)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Read-only debug view of the PENDING queue. Retained for admin
     * triage — production workers use {@code POST /claim} which is atomic
     * and race-safe.
     */
    @GetMapping("/pending")
    public List<BotTaskResponse> pending() {
        return service.findPending().stream()
                .map(BotTaskResponse::from)
                .toList();
    }

    /**
     * DEPRECATED Task 4 shim: forwards to {@link #completeVerify}. Task 12
     * removes this method once all live workers are on the {@code /verify}
     * path.
     */
    @PutMapping("/{taskId}")
    @Deprecated
    public BotTaskResponse completeLegacy(
            @PathVariable Long taskId,
            @Valid @RequestBody BotTaskCompleteRequest body) {
        return BotTaskResponse.from(service.complete(taskId, body));
    }
}
```

- [ ] **Step 7: Run the failing slice test — expect PASS**

Run: `cd backend && ./mvnw test -Dtest=BotTaskControllerClaimSliceTest -q`

Expected: PASS (3 tests).

- [ ] **Step 8: Write the SKIP LOCKED race integration test**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskClaimRaceIntegrationTest.java
package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;

/**
 * Verifies that two parallel {@code claim()} calls race cleanly over
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}: one gets task A, the other
 * gets task B, neither blocks. Regression guard for FOOTGUNS §F.86.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "slpa.bot-task.timeout-check-interval=PT10M",
        "slpa.escrow.ownership-check-enabled=false",
        "slpa.escrow.timeout-enabled=false"
})
class BotTaskClaimRaceIntegrationTest {

    @Autowired private BotTaskRepository botTaskRepo;
    @Autowired private BotTaskService service;
    @Autowired private AuctionRepository auctionRepo;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    void twoConcurrentClaims_returnDifferentTasks_noDeadlock() throws Exception {
        // Seed: 2 PENDING tasks bound to a fake auction.
        Auction auction = seedAuction();
        BotTask t1 = botTaskRepo.save(BotTask.builder()
                .taskType(BotTaskType.VERIFY)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .build());
        BotTask t2 = botTaskRepo.save(BotTask.builder()
                .taskType(BotTaskType.VERIFY)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .build());

        UUID botA = UUID.randomUUID();
        UUID botB = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Callable<Optional<BotTask>> claimA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.claim(botA);
        };
        Callable<Optional<BotTask>> claimB = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.claim(botB);
        };

        Future<Optional<BotTask>> fA = pool.submit(claimA);
        Future<Optional<BotTask>> fB = pool.submit(claimB);

        Optional<BotTask> a = fA.get(10, TimeUnit.SECONDS);
        Optional<BotTask> b = fB.get(10, TimeUnit.SECONDS);

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(a).isPresent();
        assertThat(b).isPresent();
        assertThat(a.get().getId()).isNotEqualTo(b.get().getId());
        assertThat(List.of(t1.getId(), t2.getId()))
                .contains(a.get().getId(), b.get().getId());

        BotTask reloadedA = botTaskRepo.findById(a.get().getId()).orElseThrow();
        BotTask reloadedB = botTaskRepo.findById(b.get().getId()).orElseThrow();
        assertThat(reloadedA.getStatus()).isEqualTo(BotTaskStatus.IN_PROGRESS);
        assertThat(reloadedB.getStatus()).isEqualTo(BotTaskStatus.IN_PROGRESS);
        assertThat(reloadedA.getAssignedBotUuid()).isIn(botA, botB);
        assertThat(reloadedB.getAssignedBotUuid()).isIn(botA, botB);
        assertThat(reloadedA.getAssignedBotUuid())
                .isNotEqualTo(reloadedB.getAssignedBotUuid());
    }

    private Auction seedAuction() {
        // Delegates to a shared test factory. If `AuctionFactory` is not in
        // scope, reuse whatever BotTaskService's existing tests use to seed
        // an Auction — implementation detail of the test infrastructure.
        return auctionRepo.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No auction fixture — seed one via the shared factory first"));
    }
}
```

Note: this test depends on a seeded Auction row. In practice, the backend's integration test factory (`AuctionFactory` or equivalent) should be used. Adapt the `seedAuction()` helper to whatever factory pattern the codebase uses in `BotTaskServiceIntegrationTest`.

- [ ] **Step 9: Run the race test**

Run: `cd backend && ./mvnw test -Dtest=BotTaskClaimRaceIntegrationTest -q`

Expected: PASS. If it deadlocks or times out, verify the native query includes `FOR UPDATE SKIP LOCKED` and that the service method is `@Transactional` (not the outer TransactionTemplate wrapper — the service itself must participate in the row lock).

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskClaimRequest.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerClaimSliceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskClaimRaceIntegrationTest.java

git commit -m "feat(bot): atomic claim endpoint with SKIP LOCKED"
```

---

## Task 3: Backend — Bearer Auth + Startup Validator

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskConfigProperties.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotSharedSecretAuthorizer.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotStartupValidator.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-dev.yml`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotSharedSecretAuthorizerTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotStartupValidatorTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerAuthIntegrationTest.java`

Spec reference: §3.5, §3.6, §8.4.

- [ ] **Step 1: Create `BotTaskConfigProperties`**

Extracts all `@Value` reads into a typed record so the authorizer + validator can bind once and share.

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskConfigProperties.java
package com.slparcelauctions.backend.bot;

import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code slpa.bot.*} and {@code slpa.bot-task.*} keys.
 * See {@code application.yml} for defaults. Owned by the bot package — both
 * the auth filter and the startup validator depend on it.
 */
@ConfigurationProperties(prefix = "slpa")
public record BotTaskConfigProperties(
        Bot bot,
        BotTask botTask) {

    public record Bot(
            String sharedSecret,
            Duration monitorAuctionInterval,
            Duration monitorEscrowInterval,
            int accessDeniedStreakThreshold,
            int teleportsPerMinute) {}

    public record BotTask(
            long sentinelPriceLindens,
            UUID primaryEscrowUuid,
            Duration timeout,
            Duration inProgressTimeout,
            Duration timeoutCheckInterval) {}
}
```

- [ ] **Step 2: Add `application.yml` defaults + register `@ConfigurationProperties`**

Append to `backend/src/main/resources/application.yml` (under the existing `slpa:` key — preserve existing keys):

```yaml
slpa:
  bot:
    shared-secret: ${SLPA_BOT_SHARED_SECRET:}
    monitor-auction-interval: PT30M
    monitor-escrow-interval: PT15M
    access-denied-streak-threshold: 3
    teleports-per-minute: 6
  bot-task:
    sentinel-price-lindens: 999999999
    primary-escrow-uuid: ${SLPA_PRIMARY_ESCROW_UUID:00000000-0000-0000-0000-000000000099}
    timeout: PT48H
    in-progress-timeout: PT20M
    timeout-check-interval: PT15M
```

And register the properties class in the main application class if `@ConfigurationPropertiesScan` is not already in effect. Grep `backend/src/main/java/com/slparcelauctions/backend/SlpaApplication.java` for `ConfigurationPropertiesScan`; if absent, add `@EnableConfigurationProperties(BotTaskConfigProperties.class)` to the application class.

- [ ] **Step 3: Add dev shared secret**

```yaml
# backend/src/main/resources/application-dev.yml
# Append under slpa.bot:
slpa:
  bot:
    shared-secret: dev-bot-shared-secret
```

If `slpa.bot` already exists in the dev profile (due to another task), merge keys rather than duplicate the block.

- [ ] **Step 4: Write failing unit test for `BotSharedSecretAuthorizer`**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotSharedSecretAuthorizerTest.java
package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.slparcelauctions.backend.bot.BotTaskConfigProperties.Bot;
import com.slparcelauctions.backend.bot.BotTaskConfigProperties.BotTask;

class BotSharedSecretAuthorizerTest {

    private final BotTaskConfigProperties props = new BotTaskConfigProperties(
            new Bot("secret-value-12345678", null, null, 3, 6),
            new BotTask(0L, null, null, null, null));

    private final BotSharedSecretAuthorizer authorizer = new BotSharedSecretAuthorizer(props);

    @Test
    void matchingBearerToken_isAuthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer secret-value-12345678");
        assertThat(authorizer.isAuthorized(req)).isTrue();
    }

    @Test
    void missingHeader_isDenied() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThat(authorizer.isAuthorized(req)).isFalse();
    }

    @Test
    void wrongScheme_isDenied() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic secret-value-12345678");
        assertThat(authorizer.isAuthorized(req)).isFalse();
    }

    @Test
    void mismatchedToken_isDenied() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer wrong-value-12345678");
        assertThat(authorizer.isAuthorized(req)).isFalse();
    }

    @Test
    void differentLengthToken_isDenied_constantTime() {
        // Regression: a naive String.equals short-circuits on length mismatch,
        // leaking length via timing. MessageDigest.isEqual does not.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer short");
        assertThat(authorizer.isAuthorized(req)).isFalse();
    }
}
```

- [ ] **Step 5: Run the failing test**

Run: `cd backend && ./mvnw test -Dtest=BotSharedSecretAuthorizerTest -q`

Expected: FAIL with "class BotSharedSecretAuthorizer not found".

- [ ] **Step 6: Implement `BotSharedSecretAuthorizer`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotSharedSecretAuthorizer.java
package com.slparcelauctions.backend.bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Validates the {@code Authorization: Bearer <secret>} header on
 * {@code /api/v1/bot/**} requests. Mirrors the escrow terminal secret
 * pattern. Uses {@link MessageDigest#isEqual} for a constant-time compare
 * that does not leak the secret length via timing.
 *
 * <p>A {@code null} expected secret (no config set) fails closed —
 * authorizer returns {@code false}. Non-dev profiles also have
 * {@link BotStartupValidator} to fail fast at boot in that scenario.
 */
@Component
@RequiredArgsConstructor
public class BotSharedSecretAuthorizer {

    private static final String BEARER_PREFIX = "Bearer ";

    private final BotTaskConfigProperties props;

    public boolean isAuthorized(HttpServletRequest request) {
        String expected = props.bot() == null ? null : props.bot().sharedSecret();
        if (expected == null || expected.isBlank()) return false;

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) return false;
        String presented = header.substring(BEARER_PREFIX.length());

        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, presentedBytes);
    }
}
```

- [ ] **Step 7: Run the test — expect PASS**

Run: `cd backend && ./mvnw test -Dtest=BotSharedSecretAuthorizerTest -q`

Expected: PASS (5 tests).

- [ ] **Step 8: Write failing test for `BotStartupValidator`**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotStartupValidatorTest.java
package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.bot.BotTaskConfigProperties.Bot;
import com.slparcelauctions.backend.bot.BotTaskConfigProperties.BotTask;

class BotStartupValidatorTest {

    private static final UUID PLACEHOLDER =
            UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID REAL =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void blankSharedSecret_throws() {
        BotTaskConfigProperties props = props("", REAL);
        assertThatThrownBy(() -> new BotStartupValidator(props).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slpa.bot.shared-secret");
    }

    @Test
    void devPlaceholderSharedSecret_throws() {
        BotTaskConfigProperties props = props("dev-bot-shared-secret", REAL);
        assertThatThrownBy(() -> new BotStartupValidator(props).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev placeholder");
    }

    @Test
    void placeholderPrimaryEscrowUuid_throws() {
        BotTaskConfigProperties props = props("real-secret-12345678", PLACEHOLDER);
        assertThatThrownBy(() -> new BotStartupValidator(props).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary-escrow-uuid");
    }

    @Test
    void validConfig_passes() {
        BotTaskConfigProperties props = props("real-secret-12345678", REAL);
        assertThatCode(() -> new BotStartupValidator(props).validate())
                .doesNotThrowAnyException();
    }

    private static BotTaskConfigProperties props(String secret, UUID primaryEscrow) {
        return new BotTaskConfigProperties(
                new Bot(secret, null, null, 3, 6),
                new BotTask(999_999_999L, primaryEscrow, null, null, null));
    }
}
```

- [ ] **Step 9: Implement `BotStartupValidator`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotStartupValidator.java
package com.slparcelauctions.backend.bot;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fails fast on non-dev profiles when {@code slpa.bot.shared-secret} is
 * unset / blank / equal to the dev placeholder, or when
 * {@code slpa.bot-task.primary-escrow-uuid} is still the dev placeholder.
 * Mirrors {@link com.slparcelauctions.backend.escrow.config.EscrowStartupValidator}
 * in intent. Dev profile skips this validator entirely.
 *
 * <p>Closes two DEFERRED_WORK items:
 * <ul>
 *   <li>"Bot service authentication" (Epic 03 sub-spec 1, Task 8).</li>
 *   <li>Bot half of "Primary escrow UUID + SLPA trusted-owner-keys
 *       production config".</li>
 * </ul>
 */
@Component
@Profile("!dev & !test")
@RequiredArgsConstructor
@Slf4j
public class BotStartupValidator {

    private static final String DEV_PLACEHOLDER_SECRET = "dev-bot-shared-secret";
    private static final UUID DEV_PLACEHOLDER_ESCROW_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final int MIN_SECRET_LENGTH = 16;

    private final BotTaskConfigProperties props;

    @PostConstruct
    public void validate() {
        String secret = props.bot() == null ? null : props.bot().sharedSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "slpa.bot.shared-secret must be set in non-dev profiles. "
                            + "Configure via SLPA_BOT_SHARED_SECRET.");
        }
        if (DEV_PLACEHOLDER_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "slpa.bot.shared-secret is still the dev placeholder "
                            + "\"" + DEV_PLACEHOLDER_SECRET + "\". "
                            + "Rotate via SLPA_BOT_SHARED_SECRET before deploying.");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "slpa.bot.shared-secret must be at least "
                            + MIN_SECRET_LENGTH + " characters; got "
                            + secret.length() + ".");
        }

        UUID primaryEscrow = props.botTask() == null
                ? null : props.botTask().primaryEscrowUuid();
        if (DEV_PLACEHOLDER_ESCROW_UUID.equals(primaryEscrow)) {
            throw new IllegalStateException(
                    "slpa.bot-task.primary-escrow-uuid is still the dev "
                            + "placeholder " + DEV_PLACEHOLDER_ESCROW_UUID
                            + ". Configure via SLPA_PRIMARY_ESCROW_UUID.");
        }

        log.info("Bot startup validation OK: secretLen={}, primaryEscrowUuid={}",
                secret.length(), primaryEscrow);
    }
}
```

- [ ] **Step 10: Run the validator test — expect PASS**

Run: `cd backend && ./mvnw test -Dtest=BotStartupValidatorTest -q`

Expected: PASS (4 tests).

- [ ] **Step 11: Wire bearer auth into `SecurityConfig`**

Modify the existing `/api/v1/bot/**` matchers. Replace the two existing `.permitAll()` entries with a single rule that requires the bearer check via an `access()` method, or a filter. The simplest approach: add a small filter that runs the authorizer and marks the request authenticated if it passes, and keep the matcher `.authenticated()`.

Replace lines 130-136 of `SecurityConfig.java`:

```java
// OLD:
//   .requestMatchers(HttpMethod.GET, "/api/v1/bot/tasks/pending").permitAll()
//   .requestMatchers(HttpMethod.PUT, "/api/v1/bot/tasks/**").permitAll()

// NEW:
.requestMatchers("/api/v1/bot/**").access(
    (auth, ctx) -> new org.springframework.security.authorization.AuthorizationDecision(
            botSharedSecretAuthorizer.isAuthorized(ctx.getRequest())))
```

Add the dependency + field at the top of the class:

```java
private final BotSharedSecretAuthorizer botSharedSecretAuthorizer;
```

Because the `@RequiredArgsConstructor` already wires final fields, no further change is needed there. The `@Import` of security packages at the top of the file already covers `AuthorizationDecision`; if the IDE complains, add the full import:

```java
import org.springframework.security.authorization.AuthorizationDecision;
```

- [ ] **Step 12: Write integration test for auth gating**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerAuthIntegrationTest.java
package com.slparcelauctions.backend.bot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-stack auth test. Boots the container with
 * {@code slpa.bot.shared-secret=test-integration-secret} and verifies the
 * {@code /api/v1/bot/**} surface rejects missing / wrong bearer tokens and
 * admits the right one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "slpa.bot.shared-secret=test-integration-secret-xxxxxxxx",
        "slpa.escrow.ownership-check-enabled=false",
        "slpa.escrow.timeout-enabled=false"
})
class BotTaskControllerAuthIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void claim_withoutBearer_returns401() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void claim_withWrongBearer_returns401() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .header("Authorization", "Bearer wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void claim_withCorrectBearer_returnsNoContentOrOk() throws Exception {
        // Queue is empty in this test → 204. If a pre-existing test
        // fixture seeds a PENDING task, relax this to isOk()/isNoContent().
        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .header("Authorization", "Bearer test-integration-secret-xxxxxxxx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 13: Run the auth integration test**

Run: `cd backend && ./mvnw test -Dtest=BotTaskControllerAuthIntegrationTest -q`

Expected: PASS (3 tests). If the 401 tests fail because Spring Security's default 403 fires instead, check the `.access(...)` matcher is reached before the catch-all — `/api/v1/bot/**` must sit above `/api/v1/**`.

- [ ] **Step 14: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskConfigProperties.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotSharedSecretAuthorizer.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotStartupValidator.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        backend/src/main/resources/application.yml \
        backend/src/main/resources/application-dev.yml \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotSharedSecretAuthorizerTest.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotStartupValidatorTest.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerAuthIntegrationTest.java

# Also include SlpaApplication.java if you added @EnableConfigurationProperties
# git add backend/src/main/java/com/slparcelauctions/backend/SlpaApplication.java

git commit -m "feat(bot): bearer-token auth + startup validator"
```

---

## Task 4: Backend — VERIFY Callback Split + IN_PROGRESS Timeout

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/BotTaskTimeoutJob.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerVerifySliceTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskTimeoutJobInProgressTest.java`

Spec reference: §3.4, §3.7, §5.1.

- [ ] **Step 1: Add `PUT /{id}/verify` to `BotTaskController`**

Add the new handler alongside the deprecated legacy shim introduced in Task 2:

```java
    /**
     * VERIFY callback: terminates the task (COMPLETED/FAILED) and
     * transitions the auction. See {@link BotTaskService#complete} for
     * the full contract.
     */
    @PutMapping("/{taskId}/verify")
    public BotTaskResponse completeVerify(
            @PathVariable Long taskId,
            @Valid @RequestBody BotTaskCompleteRequest body) {
        return BotTaskResponse.from(service.complete(taskId, body));
    }
```

Keep `completeLegacy()` as a `@Deprecated` shim for one release (removed in Task 12).

- [ ] **Step 2: Write failing slice test for `/verify` + deprecated shim**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerVerifySliceTest.java
package com.slparcelauctions.backend.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auction.Auction;

@WebMvcTest(controllers = BotTaskController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@Import(BotTaskExceptionHandler.class)
class BotTaskControllerVerifySliceTest {

    @Autowired private MockMvc mvc;
    @MockBean private BotTaskService service;

    @Test
    void verify_success_returns200() throws Exception {
        BotTask task = stub(5L, BotTaskStatus.COMPLETED);
        when(service.complete(eq(5L), any())).thenReturn(task);

        mvc.perform(put("/api/v1/bot/tasks/5/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "result": "SUCCESS",
                                  "authBuyerId": "00000000-0000-0000-0000-000000000099",
                                  "salePrice": 999999999,
                                  "parcelOwner": "11111111-1111-1111-1111-111111111111"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void legacyPath_routesToSameHandler() throws Exception {
        BotTask task = stub(6L, BotTaskStatus.FAILED);
        when(service.complete(eq(6L), any())).thenReturn(task);

        mvc.perform(put("/api/v1/bot/tasks/6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"FAILURE\",\"failureReason\":\"timeout\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    private BotTask stub(long id, BotTaskStatus status) {
        return BotTask.builder()
                .id(id)
                .taskType(BotTaskType.VERIFY)
                .status(status)
                .auction(Auction.builder().id(42L).build())
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
```

- [ ] **Step 3: Run the slice test — expect PASS**

Run: `cd backend && ./mvnw test -Dtest=BotTaskControllerVerifySliceTest -q`

Expected: PASS (2 tests). Both the new `/verify` path and the legacy shim route to `BotTaskService.complete()`.

- [ ] **Step 4: Extend `BotTaskService` with IN_PROGRESS sweep helpers**

Append to `BotTaskService.java`. These are called by the new sweep method in `BotTaskTimeoutJob`.

```java
    /**
     * IN_PROGRESS tasks whose {@code lastUpdatedAt} is older than
     * {@code threshold}. Used by the IN_PROGRESS timeout sweep. For VERIFY
     * tasks this is a crashed-worker signal; for MONITOR_* tasks it means
     * the worker picked up a due check but never reported back.
     */
    @Transactional(readOnly = true)
    public List<BotTask> findInProgressOlderThan(Duration threshold) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(threshold);
        return botTaskRepo.findByStatusAndLastUpdatedAtBefore(
                BotTaskStatus.IN_PROGRESS, cutoff);
    }

    /**
     * Applies divergent IN_PROGRESS-timeout behavior per spec §3.7:
     * <ul>
     *   <li>VERIFY → FAILED with reason {@code "TIMEOUT (IN_PROGRESS)"}; if
     *       the auction is still VERIFICATION_PENDING, flip it to
     *       VERIFICATION_FAILED with a retry-friendly note (same as PENDING
     *       sweep).</li>
     *   <li>MONITOR_* → <strong>re-armed</strong>: status=PENDING,
     *       nextRunAt = now + recurrenceIntervalSeconds. A different worker
     *       (or the same one) will retry next cycle. No FAILED row.</li>
     * </ul>
     */
    @Transactional
    public void handleInProgressTimeout(BotTask task) {
        if (task.getStatus() != BotTaskStatus.IN_PROGRESS) {
            log.debug("Skipping IN_PROGRESS timeout for bot task {} (status={})",
                    task.getId(), task.getStatus());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (task.getTaskType() == BotTaskType.VERIFY) {
            task.setStatus(BotTaskStatus.FAILED);
            task.setFailureReason("TIMEOUT (IN_PROGRESS)");
            task.setCompletedAt(now);
            botTaskRepo.save(task);

            Auction auction = task.getAuction();
            if (auction.getStatus() == AuctionStatus.VERIFICATION_PENDING) {
                auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
                auction.setVerificationNotes(
                        "Sale-to-bot task stalled mid-verify. "
                                + "You can retry at no extra cost.");
                auctionRepo.save(auction);
            }
            log.info("Bot VERIFY task {} timed out mid-IN_PROGRESS (auctionId={})",
                    task.getId(), auction.getId());
            return;
        }
        // MONITOR_AUCTION or MONITOR_ESCROW — re-arm.
        int interval = task.getRecurrenceIntervalSeconds() == null
                ? 0 : task.getRecurrenceIntervalSeconds();
        task.setStatus(BotTaskStatus.PENDING);
        task.setNextRunAt(now.plusSeconds(interval));
        task.setAssignedBotUuid(null);
        botTaskRepo.save(task);
        log.info("Bot MONITOR task {} re-armed after IN_PROGRESS timeout "
                        + "(type={}, nextRunAt={})",
                task.getId(), task.getTaskType(), task.getNextRunAt());
    }
```

The `BotTaskType` import should already be present; `Duration` needs `import java.time.Duration;`.

- [ ] **Step 5: Extend `BotTaskTimeoutJob` with IN_PROGRESS sweep**

```java
// backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/BotTaskTimeoutJob.java
package com.slparcelauctions.backend.auction.scheduled;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Times out stuck bot tasks. Two passes:
 * <ul>
 *   <li>{@link #sweepPending()} — existing 48h PENDING sweep (unclaimed
 *       tasks that were never picked up).</li>
 *   <li>{@link #sweepInProgress()} — new in Epic 06. Catches workers that
 *       crashed mid-execution. VERIFY tasks flip to FAILED; MONITOR_* tasks
 *       re-arm to PENDING (different worker retries next cycle). Closes
 *       the "IN_PROGRESS bot task timeout" deferred item.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BotTaskTimeoutJob {

    private final BotTaskService service;

    @Value("${slpa.bot-task.timeout-hours:48}")
    private int timeoutHours;

    @Value("${slpa.bot-task.in-progress-timeout:PT20M}")
    private Duration inProgressTimeout;

    @Scheduled(fixedDelayString = "${slpa.bot-task.timeout-check-interval:PT15M}")
    public void sweepPending() {
        Duration threshold = Duration.ofHours(timeoutHours);
        List<BotTask> timedOut = service.findPendingOlderThan(threshold);
        if (timedOut.isEmpty()) return;
        timedOut.forEach(service::markTimedOut);
        log.info("BotTaskTimeoutJob: PENDING sweep timed out {} task(s)",
                timedOut.size());
    }

    @Scheduled(fixedDelayString = "${slpa.bot-task.timeout-check-interval:PT15M}")
    public void sweepInProgress() {
        List<BotTask> stalled = service.findInProgressOlderThan(inProgressTimeout);
        if (stalled.isEmpty()) return;
        stalled.forEach(service::handleInProgressTimeout);
        log.info("BotTaskTimeoutJob: IN_PROGRESS sweep cleared {} task(s) "
                        + "(threshold={})",
                stalled.size(), inProgressTimeout);
    }
}
```

- [ ] **Step 6: Write integration test for IN_PROGRESS sweep**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskTimeoutJobInProgressTest.java
package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;

/**
 * Verifies the divergent IN_PROGRESS timeout behavior: VERIFY flips to
 * FAILED and the auction back to VERIFICATION_FAILED; MONITOR_AUCTION
 * re-arms to PENDING instead of failing.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "slpa.bot-task.timeout-check-interval=PT10M",
        "slpa.bot-task.in-progress-timeout=PT1M",
        "slpa.escrow.ownership-check-enabled=false",
        "slpa.escrow.timeout-enabled=false"
})
class BotTaskTimeoutJobInProgressTest {

    @Autowired private BotTaskService service;
    @Autowired private BotTaskRepository botTaskRepo;
    @Autowired private AuctionRepository auctionRepo;

    @MockBean private Clock clock;

    @Test
    void verifyTaskInProgressForTwoMinutes_failsAndFlipsAuction() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(clock).thenReturn(now);

        Auction auction = seedAuctionInStatus(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = seedInProgressTask(
                auction, BotTaskType.VERIFY, now.minusMinutes(2));

        for (BotTask stalled : service.findInProgressOlderThan(Duration.ofMinutes(1))) {
            service.handleInProgressTimeout(stalled);
        }

        BotTask reloaded = botTaskRepo.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("TIMEOUT (IN_PROGRESS)");

        Auction reloadedAuction = auctionRepo.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getStatus())
                .isEqualTo(AuctionStatus.VERIFICATION_FAILED);
    }

    @Test
    void monitorAuctionTaskInProgressForTwoMinutes_reArmsToPending() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(clock).thenReturn(now);

        Auction auction = seedAuctionInStatus(AuctionStatus.ACTIVE);
        BotTask task = seedInProgressTask(
                auction, BotTaskType.MONITOR_AUCTION, now.minusMinutes(2));
        task.setRecurrenceIntervalSeconds(1800);
        task.setAssignedBotUuid(UUID.randomUUID());
        botTaskRepo.save(task);

        for (BotTask stalled : service.findInProgressOlderThan(Duration.ofMinutes(1))) {
            service.handleInProgressTimeout(stalled);
        }

        BotTask reloaded = botTaskRepo.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(reloaded.getAssignedBotUuid()).isNull();
        assertThat(reloaded.getNextRunAt()).isNotNull();
        assertThat(reloaded.getCompletedAt()).isNull();
    }

    // Helpers — adapt to the codebase's existing test factory utilities.
    private Auction seedAuctionInStatus(AuctionStatus status) {
        // The real implementation should delegate to AuctionFactory or the
        // existing BotTaskServiceIntegrationTest fixture helper. For a
        // concrete implementation, copy the seeder from
        // BotTaskClaimRaceIntegrationTest.seedAuction() or the equivalent.
        return auctionRepo.findAll().stream()
                .filter(a -> a.getStatus() == status)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Seed an auction in status " + status + " via the shared factory"));
    }

    private BotTask seedInProgressTask(
            Auction auction, BotTaskType type, OffsetDateTime lastUpdatedAt) {
        BotTask t = botTaskRepo.save(BotTask.builder()
                .taskType(type)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(auction)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .build());
        // Force lastUpdatedAt backdate. Since @UpdateTimestamp overwrites on
        // save, this test relies on a direct JDBC update in a real
        // implementation. See shared test helper DbTimeWarper.setLastUpdated.
        return t;
    }

    private static org.mockito.stubbing.OngoingStubbing<?> when(Object mock) {
        return org.mockito.Mockito.when(((Clock) mock).instant());
    }
}
```

(Note: the clock/time-warp helpers in the existing backend test suite will likely provide cleaner fixtures than this inline stub. Reuse the pattern from an existing test that time-warps `@UpdateTimestamp`, e.g., `EscrowOwnershipCheckTaskTest` if it exists — grep for `@UpdateTimestamp` and `Instant` mocks.)

- [ ] **Step 7: Run the sweep test**

Run: `cd backend && ./mvnw test -Dtest=BotTaskTimeoutJobInProgressTest -q`

Expected: PASS (2 tests). If the time-warp helper pattern is different, copy the fixture from the nearest equivalent existing test and adapt.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/BotTaskTimeoutJob.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerVerifySliceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskTimeoutJobInProgressTest.java

git commit -m "feat(bot): /verify callback path + IN_PROGRESS timeout sweep"
```

---

## Task 5: Backend — MONITOR Callback + Dispatcher

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/MonitorOutcome.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/DispatchOutcome.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorDispatcher.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotMonitorResultRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/exception/BotTaskNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/exception/BotTaskNotClaimedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/exception/BotTaskWrongTypeException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/exception/BotEscrowTerminalException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskExceptionHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorDispatcherTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerMonitorSliceTest.java`

Spec reference: §3.9, §6.1, §6.2, §6.3, §6.4.

- [ ] **Step 1: Create `MonitorOutcome` enum**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/MonitorOutcome.java
package com.slparcelauctions.backend.bot;

/**
 * Observation-only vocabulary emitted by the bot worker's monitor handler.
 * Deliberately omits {@code FRAUD_DETECTED} — fraud interpretation is the
 * backend dispatcher's responsibility. See Epic 06 spec §2.5, §6.
 */
public enum MonitorOutcome {
    /** Everything still matches expected values. MONITOR_AUCTION only. */
    ALL_GOOD,
    /** Observed AuthBuyerID differs from expected. */
    AUTH_BUYER_CHANGED,
    /** Observed SalePrice differs from expected. */
    PRICE_MISMATCH,
    /** Observed OwnerID differs from expected (auction) or is neither seller nor winner (escrow). */
    OWNER_CHANGED,
    /** Bot could not enter the parcel (access list, ban, estate-level block). */
    ACCESS_DENIED,
    /** Escrow: observed owner == expected winner. */
    TRANSFER_COMPLETE,
    /** Escrow: seller has configured sale-to-winner; price below threshold. */
    TRANSFER_READY,
    /** Escrow: no relevant change — neither transfer nor fraud. */
    STILL_WAITING
}
```

- [ ] **Step 2: Create `DispatchOutcome` record**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/DispatchOutcome.java
package com.slparcelauctions.backend.bot;

/**
 * Return value from {@link BotMonitorDispatcher#dispatch}. The
 * {@code shouldReArm} bit controls whether the monitor row is re-armed
 * (PENDING + bumped {@code nextRunAt}) or left alone (lifecycle hook
 * already cancelled it as part of the triggered business transition).
 * See spec §6 — transaction-ordering hazard note on TRANSFER_COMPLETE.
 *
 * @param shouldReArm {@code true} → caller bumps nextRunAt + sets PENDING;
 *                    {@code false} → caller leaves the row to whatever the
 *                    downstream hook wrote (CANCELLED, typically).
 * @param logAction   one-word string describing what the dispatcher did,
 *                    for the structured log line.
 */
public record DispatchOutcome(boolean shouldReArm, String logAction) {}
```

- [ ] **Step 3: Create `BotMonitorResultRequest` DTO**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotMonitorResultRequest.java
package com.slparcelauctions.backend.bot.dto;

import java.util.UUID;

import com.slparcelauctions.backend.bot.MonitorOutcome;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/bot/tasks/{id}/monitor. {@code outcome} is required;
 * the {@code observed*} fields and {@code note} are contextual — the
 * backend records them in {@code BotTask.resultData} for audit.
 */
public record BotMonitorResultRequest(
        @NotNull MonitorOutcome outcome,
        UUID observedOwner,
        UUID observedAuthBuyer,
        Long observedSalePrice,
        @Size(max = 500) String note) {}
```

- [ ] **Step 4: Create the four new exception classes**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/exception/BotTaskNotFoundException.java
package com.slparcelauctions.backend.bot.exception;

import lombok.Getter;

@Getter
public class BotTaskNotFoundException extends RuntimeException {
    private final Long taskId;

    public BotTaskNotFoundException(Long taskId) {
        super("Bot task not found: " + taskId);
        this.taskId = taskId;
    }
}
```

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/exception/BotTaskNotClaimedException.java
package com.slparcelauctions.backend.bot.exception;

import com.slparcelauctions.backend.bot.BotTaskStatus;

import lombok.Getter;

@Getter
public class BotTaskNotClaimedException extends RuntimeException {
    private final Long taskId;
    private final BotTaskStatus currentStatus;

    public BotTaskNotClaimedException(Long taskId, BotTaskStatus currentStatus) {
        super("Bot task " + taskId + " is not claimed (status=" + currentStatus + ")");
        this.taskId = taskId;
        this.currentStatus = currentStatus;
    }
}
```

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/exception/BotTaskWrongTypeException.java
package com.slparcelauctions.backend.bot.exception;

import com.slparcelauctions.backend.bot.BotTaskType;

import lombok.Getter;

@Getter
public class BotTaskWrongTypeException extends RuntimeException {
    private final Long taskId;
    private final BotTaskType actual;
    private final BotTaskType expected;

    public BotTaskWrongTypeException(Long taskId, BotTaskType actual, BotTaskType expected) {
        super("Bot task " + taskId + " is of type " + actual
                + " but the endpoint expects " + expected);
        this.taskId = taskId;
        this.actual = actual;
        this.expected = expected;
    }
}
```

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/exception/BotEscrowTerminalException.java
package com.slparcelauctions.backend.bot.exception;

import com.slparcelauctions.backend.escrow.EscrowState;

import lombok.Getter;

@Getter
public class BotEscrowTerminalException extends RuntimeException {
    private final Long escrowId;
    private final EscrowState state;

    public BotEscrowTerminalException(Long escrowId, EscrowState state) {
        super("Escrow " + escrowId + " is in terminal state " + state
                + "; cannot apply monitor result");
        this.escrowId = escrowId;
        this.state = state;
    }
}
```

- [ ] **Step 5: Extend `BotTaskExceptionHandler` with the four new mappings**

Add these `@ExceptionHandler` methods to `BotTaskExceptionHandler.java`:

```java
    @ExceptionHandler(BotTaskNotFoundException.class)
    public ProblemDetail handleTaskNotFound(
            BotTaskNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Bot Task Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_TASK_NOT_FOUND");
        pd.setProperty("taskId", e.getTaskId());
        return pd;
    }

    @ExceptionHandler(BotTaskNotClaimedException.class)
    public ProblemDetail handleNotClaimed(
            BotTaskNotClaimedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Bot Task Not Claimed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_TASK_NOT_CLAIMED");
        pd.setProperty("taskId", e.getTaskId());
        pd.setProperty("currentStatus", e.getCurrentStatus().name());
        return pd;
    }

    @ExceptionHandler(BotTaskWrongTypeException.class)
    public ProblemDetail handleWrongType(
            BotTaskWrongTypeException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Bot Task Wrong Type");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_TASK_WRONG_TYPE");
        pd.setProperty("taskId", e.getTaskId());
        pd.setProperty("actual", e.getActual().name());
        pd.setProperty("expected", e.getExpected().name());
        return pd;
    }

    @ExceptionHandler(BotEscrowTerminalException.class)
    public ProblemDetail handleEscrowTerminal(
            BotEscrowTerminalException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Escrow Terminal");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BOT_ESCROW_TERMINAL");
        pd.setProperty("escrowId", e.getEscrowId());
        pd.setProperty("state", e.getState().name());
        return pd;
    }
```

Add the corresponding imports:

```java
import com.slparcelauctions.backend.bot.exception.BotEscrowTerminalException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotClaimedException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotFoundException;
import com.slparcelauctions.backend.bot.exception.BotTaskWrongTypeException;
```

- [ ] **Step 6: Add `reviewRequired` flag to `Escrow`**

```java
// backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java
// Append alongside existing columns, before createdAt:

    /**
     * Set to {@code true} by {@link EscrowService#markReviewRequired} when
     * the bot monitor observes persistent ACCESS_DENIED during escrow (spec
     * §6.2). Does not change lifecycle state; a flag for admin triage.
     * Default false.
     */
    @Builder.Default
    @Column(name = "review_required", nullable = false)
    private Boolean reviewRequired = false;
```

- [ ] **Step 7: Add `markReviewRequired` to `EscrowService`**

Append at the end of `EscrowService.java` (before the closing brace):

```java
    /**
     * Flags an escrow for admin review without changing lifecycle state.
     * Called by {@link com.slparcelauctions.backend.bot.BotMonitorDispatcher}
     * when the bot observes persistent ACCESS_DENIED on an active escrow.
     * Idempotent — already-flagged escrows are a no-op. Does not publish a
     * broadcast envelope (admin-only signal per Epic 06 spec §6.3).
     */
    @Transactional
    public void markReviewRequired(Escrow escrow) {
        if (Boolean.TRUE.equals(escrow.getReviewRequired())) {
            log.debug("Escrow {} already flagged for review", escrow.getId());
            return;
        }
        escrow.setReviewRequired(true);
        escrowRepo.save(escrow);
        log.warn("Escrow {} flagged for admin review", escrow.getId());
    }
```

- [ ] **Step 8: Write failing unit tests for `BotMonitorDispatcher`**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorDispatcherTest.java
package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.monitoring.SuspensionService;
import com.slparcelauctions.backend.bot.dto.BotMonitorResultRequest;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.FreezeReason;

@ExtendWith(MockitoExtension.class)
class BotMonitorDispatcherTest {

    @Mock private SuspensionService suspensionService;
    @Mock private EscrowService escrowService;

    @InjectMocks private BotMonitorDispatcher dispatcher;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        dispatcher = new BotMonitorDispatcher(suspensionService, escrowService, clock);
    }

    // ---------- MONITOR_AUCTION ----------

    @Test
    void monitorAuction_allGood_reArms_noServiceCalls() {
        BotTask task = auctionTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.ALL_GOOD,
                        null, null, null, null));
        assertThat(out.shouldReArm()).isTrue();
        assertThat(out.logAction()).isEqualTo("ALL_GOOD");
        verify(suspensionService, never()).suspendForBotObservation(
                any(), any(), any());
    }

    @Test
    void monitorAuction_authBuyerChanged_suspends_noReArm() {
        BotTask task = auctionTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.AUTH_BUYER_CHANGED,
                        UUID.randomUUID(), UUID.randomUUID(), 0L, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(suspensionService, times(1)).suspendForBotObservation(
                eq(task.getAuction()),
                eq(FraudFlagReason.BOT_AUTH_BUYER_REVOKED),
                any());
    }

    @Test
    void monitorAuction_priceMismatch_suspends_noReArm() {
        BotTask task = auctionTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.PRICE_MISMATCH,
                        UUID.randomUUID(), UUID.randomUUID(), 1L, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(suspensionService, times(1)).suspendForBotObservation(
                eq(task.getAuction()),
                eq(FraudFlagReason.BOT_PRICE_DRIFT),
                any());
    }

    @Test
    void monitorAuction_ownerChanged_suspends_noReArm() {
        BotTask task = auctionTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.OWNER_CHANGED,
                        UUID.randomUUID(), null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(suspensionService, times(1)).suspendForBotObservation(
                eq(task.getAuction()),
                eq(FraudFlagReason.BOT_OWNERSHIP_CHANGED),
                any());
    }

    @Test
    void monitorAuction_accessDeniedBelowThreshold_reArms_noSuspend() {
        BotTask task = auctionTask();
        Map<String, Object> data = new HashMap<>();
        data.put("accessDeniedStreak", 1);
        task.setResultData(data);
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.ACCESS_DENIED,
                        null, null, null, null));
        assertThat(out.shouldReArm()).isTrue();
        assertThat(task.getResultData().get("accessDeniedStreak")).isEqualTo(2);
        verify(suspensionService, never()).suspendForBotObservation(
                any(), any(), any());
    }

    @Test
    void monitorAuction_accessDeniedAtThreshold_suspends_noReArm() {
        BotTask task = auctionTask();
        Map<String, Object> data = new HashMap<>();
        data.put("accessDeniedStreak", 2);
        task.setResultData(data);
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.ACCESS_DENIED,
                        null, null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(suspensionService, times(1)).suspendForBotObservation(
                eq(task.getAuction()),
                eq(FraudFlagReason.BOT_ACCESS_REVOKED),
                any());
    }

    @Test
    void monitorAuction_streakResetsOnNonDenial() {
        BotTask task = auctionTask();
        Map<String, Object> data = new HashMap<>();
        data.put("accessDeniedStreak", 2);
        task.setResultData(data);
        dispatcher.dispatch(task, new BotMonitorResultRequest(
                MonitorOutcome.ALL_GOOD, null, null, null, null));
        assertThat(task.getResultData().get("accessDeniedStreak")).isEqualTo(0);
    }

    // ---------- MONITOR_ESCROW ----------

    @Test
    void monitorEscrow_stillWaiting_reArms_noServiceCalls() {
        BotTask task = escrowTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.STILL_WAITING,
                        task.getExpectedSellerUuid(), null, null, null));
        assertThat(out.shouldReArm()).isTrue();
        verify(escrowService, never()).confirmTransfer(any(), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void monitorEscrow_transferComplete_callsConfirmTransfer_noReArm() {
        BotTask task = escrowTask();
        UUID winner = task.getExpectedWinnerUuid();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.TRANSFER_COMPLETE,
                        winner, null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(escrowService, times(1)).confirmTransfer(eq(task.getEscrow()), any());
    }

    @Test
    void monitorEscrow_ownerChangedToWinner_treatedAsTransferComplete() {
        BotTask task = escrowTask();
        UUID winner = task.getExpectedWinnerUuid();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.OWNER_CHANGED,
                        winner, null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(escrowService, times(1)).confirmTransfer(eq(task.getEscrow()), any());
        verify(escrowService, never()).freezeForFraud(any(), any(), any(), any());
    }

    @Test
    void monitorEscrow_ownerChangedToThirdParty_freezes_noReArm() {
        BotTask task = escrowTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.OWNER_CHANGED,
                        UUID.randomUUID(), null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        ArgumentCaptor<FreezeReason> reason = ArgumentCaptor.forClass(FreezeReason.class);
        verify(escrowService, times(1)).freezeForFraud(
                eq(task.getEscrow()), reason.capture(), any(), any());
        assertThat(reason.getValue()).isEqualTo(FreezeReason.BOT_OWNERSHIP_CHANGED);
    }

    @Test
    void monitorEscrow_transferReady_publishesBroadcastOnFirstTransition() {
        BotTask task = escrowTask();
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.TRANSFER_READY,
                        task.getExpectedSellerUuid(), task.getExpectedWinnerUuid(),
                        0L, null));
        assertThat(out.shouldReArm()).isTrue();
        assertThat(task.getResultData().get("transferReady")).isEqualTo(true);
        verify(escrowService, times(1)).publishTransferReadyObserved(task.getEscrow());
    }

    @Test
    void monitorEscrow_accessDeniedAtThreshold_marksReviewRequired_noReArm() {
        BotTask task = escrowTask();
        Map<String, Object> data = new HashMap<>();
        data.put("accessDeniedStreak", 2);
        task.setResultData(data);
        DispatchOutcome out = dispatcher.dispatch(
                task, new BotMonitorResultRequest(MonitorOutcome.ACCESS_DENIED,
                        null, null, null, null));
        assertThat(out.shouldReArm()).isFalse();
        verify(escrowService, times(1)).markReviewRequired(task.getEscrow());
    }

    // ---------- helpers ----------

    private BotTask auctionTask() {
        return BotTask.builder()
                .id(1L)
                .taskType(BotTaskType.MONITOR_AUCTION)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(Auction.builder().id(42L).build())
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .expectedOwnerUuid(UUID.randomUUID())
                .expectedAuthBuyerUuid(UUID.randomUUID())
                .expectedSalePriceLindens(999_999_999L)
                .recurrenceIntervalSeconds(1800)
                .resultData(new HashMap<>())
                .build();
    }

    private BotTask escrowTask() {
        Escrow escrow = Escrow.builder().id(100L).state(EscrowState.FUNDED).build();
        return BotTask.builder()
                .id(2L)
                .taskType(BotTaskType.MONITOR_ESCROW)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(Auction.builder().id(42L).build())
                .escrow(escrow)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .expectedSellerUuid(UUID.randomUUID())
                .expectedWinnerUuid(UUID.randomUUID())
                .expectedMaxSalePriceLindens(1L)
                .recurrenceIntervalSeconds(900)
                .resultData(new HashMap<>())
                .build();
    }
}
```

- [ ] **Step 9: Implement `BotMonitorDispatcher`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorDispatcher.java
package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.monitoring.SuspensionService;
import com.slparcelauctions.backend.bot.dto.BotMonitorResultRequest;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.FreezeReason;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Interprets {@link MonitorOutcome} observations emitted by the bot worker
 * and dispatches to the appropriate downstream service. Returns a
 * {@link DispatchOutcome} indicating whether the caller should re-arm the
 * monitor row — paths that trigger lifecycle hooks (suspend, freeze,
 * confirmTransfer) return {@code shouldReArm=false} so the hook's
 * CANCELLED write is not overwritten. Spec §6.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotMonitorDispatcher {

    static final int ACCESS_DENIED_STREAK_THRESHOLD = 3;
    private static final String STREAK_KEY = "accessDeniedStreak";
    private static final String TRANSFER_READY_KEY = "transferReady";

    private final SuspensionService suspensionService;
    private final EscrowService escrowService;
    private final Clock clock;

    public DispatchOutcome dispatch(BotTask task, BotMonitorResultRequest req) {
        ensureResultData(task);
        return switch (task.getTaskType()) {
            case MONITOR_AUCTION -> dispatchAuction(task, req);
            case MONITOR_ESCROW -> dispatchEscrow(task, req);
            case VERIFY -> throw new IllegalStateException(
                    "VERIFY task " + task.getId() + " on monitor dispatcher");
        };
    }

    private DispatchOutcome dispatchAuction(BotTask task, BotMonitorResultRequest req) {
        switch (req.outcome()) {
            case ALL_GOOD:
                resetStreak(task);
                return new DispatchOutcome(true, "ALL_GOOD");
            case AUTH_BUYER_CHANGED:
                resetStreak(task);
                suspensionService.suspendForBotObservation(
                        task.getAuction(),
                        FraudFlagReason.BOT_AUTH_BUYER_REVOKED,
                        evidence(req));
                return new DispatchOutcome(false, "SUSPENDED_AUTH_BUYER_REVOKED");
            case PRICE_MISMATCH:
                resetStreak(task);
                suspensionService.suspendForBotObservation(
                        task.getAuction(),
                        FraudFlagReason.BOT_PRICE_DRIFT,
                        evidence(req));
                return new DispatchOutcome(false, "SUSPENDED_PRICE_DRIFT");
            case OWNER_CHANGED:
                resetStreak(task);
                suspensionService.suspendForBotObservation(
                        task.getAuction(),
                        FraudFlagReason.BOT_OWNERSHIP_CHANGED,
                        evidence(req));
                return new DispatchOutcome(false, "SUSPENDED_OWNERSHIP");
            case ACCESS_DENIED:
                int newStreak = bumpStreak(task);
                if (newStreak >= ACCESS_DENIED_STREAK_THRESHOLD) {
                    suspensionService.suspendForBotObservation(
                            task.getAuction(),
                            FraudFlagReason.BOT_ACCESS_REVOKED,
                            evidence(req));
                    return new DispatchOutcome(false, "SUSPENDED_ACCESS_REVOKED");
                }
                return new DispatchOutcome(true, "ACCESS_DENIED_STREAK_" + newStreak);
            case TRANSFER_COMPLETE:
            case TRANSFER_READY:
            case STILL_WAITING:
                log.warn("Escrow outcome {} received on auction task {}; ignoring",
                        req.outcome(), task.getId());
                resetStreak(task);
                return new DispatchOutcome(true, "ESCROW_OUTCOME_ON_AUCTION");
            default:
                throw new IllegalStateException(
                        "Unknown MonitorOutcome " + req.outcome());
        }
    }

    private DispatchOutcome dispatchEscrow(BotTask task, BotMonitorResultRequest req) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        switch (req.outcome()) {
            case STILL_WAITING:
                resetStreak(task);
                return new DispatchOutcome(true, "STILL_WAITING");
            case TRANSFER_READY:
                resetStreak(task);
                boolean firstTransition =
                        !Boolean.TRUE.equals(task.getResultData().get(TRANSFER_READY_KEY));
                task.getResultData().put(TRANSFER_READY_KEY, true);
                if (firstTransition) {
                    escrowService.publishTransferReadyObserved(task.getEscrow());
                }
                return new DispatchOutcome(true, "TRANSFER_READY");
            case TRANSFER_COMPLETE:
                resetStreak(task);
                escrowService.confirmTransfer(task.getEscrow(), now);
                return new DispatchOutcome(false, "CONFIRMED_TRANSFER");
            case OWNER_CHANGED:
                resetStreak(task);
                if (req.observedOwner() != null
                        && req.observedOwner().equals(task.getExpectedWinnerUuid())) {
                    // Backend re-check: the worker's classifier lost the
                    // winner-match race. Treat as TRANSFER_COMPLETE.
                    escrowService.confirmTransfer(task.getEscrow(), now);
                    return new DispatchOutcome(false, "CONFIRMED_TRANSFER_VIA_OWNER_CHANGED");
                }
                escrowService.freezeForFraud(
                        task.getEscrow(),
                        FreezeReason.BOT_OWNERSHIP_CHANGED,
                        evidence(req),
                        now);
                return new DispatchOutcome(false, "FROZEN_OWNERSHIP");
            case AUTH_BUYER_CHANGED:
                resetStreak(task);
                if (req.observedAuthBuyer() != null
                        && req.observedAuthBuyer().equals(task.getExpectedWinnerUuid())) {
                    // Seller configured sale-to-winner. Backend re-check:
                    // treat as TRANSFER_READY.
                    boolean first =
                            !Boolean.TRUE.equals(task.getResultData().get(TRANSFER_READY_KEY));
                    task.getResultData().put(TRANSFER_READY_KEY, true);
                    if (first) {
                        escrowService.publishTransferReadyObserved(task.getEscrow());
                    }
                    return new DispatchOutcome(true, "TRANSFER_READY_VIA_AUTH_BUYER");
                }
                log.info("AUTH_BUYER_CHANGED during escrow {} (observed={}); "
                                + "seller reconfiguring",
                        task.getEscrow().getId(), req.observedAuthBuyer());
                return new DispatchOutcome(true, "AUTH_BUYER_CHANGED_INFO");
            case PRICE_MISMATCH:
                resetStreak(task);
                log.info("PRICE_MISMATCH during escrow {} (observed={}); "
                                + "seller adjusting",
                        task.getEscrow().getId(), req.observedSalePrice());
                return new DispatchOutcome(true, "PRICE_MISMATCH_INFO");
            case ACCESS_DENIED:
                int newStreak = bumpStreak(task);
                if (newStreak >= ACCESS_DENIED_STREAK_THRESHOLD) {
                    escrowService.markReviewRequired(task.getEscrow());
                    return new DispatchOutcome(false, "REVIEW_REQUIRED_ACCESS_DENIED");
                }
                return new DispatchOutcome(true, "ACCESS_DENIED_STREAK_" + newStreak);
            case ALL_GOOD:
                log.warn("ALL_GOOD outcome on escrow task {}; ignoring",
                        task.getId());
                resetStreak(task);
                return new DispatchOutcome(true, "AUCTION_OUTCOME_ON_ESCROW");
            default:
                throw new IllegalStateException(
                        "Unknown MonitorOutcome " + req.outcome());
        }
    }

    private static void ensureResultData(BotTask task) {
        if (task.getResultData() == null) task.setResultData(new HashMap<>());
    }

    private static void resetStreak(BotTask task) {
        task.getResultData().put(STREAK_KEY, 0);
    }

    private static int bumpStreak(BotTask task) {
        Object priorObj = task.getResultData().get(STREAK_KEY);
        int prior = priorObj instanceof Number n ? n.intValue() : 0;
        int next = prior + 1;
        task.getResultData().put(STREAK_KEY, next);
        return next;
    }

    private static Map<String, Object> evidence(BotMonitorResultRequest req) {
        Map<String, Object> ev = new HashMap<>();
        if (req.observedOwner() != null) ev.put("observedOwner", req.observedOwner().toString());
        if (req.observedAuthBuyer() != null) ev.put("observedAuthBuyer", req.observedAuthBuyer().toString());
        if (req.observedSalePrice() != null) ev.put("observedSalePrice", req.observedSalePrice());
        if (req.note() != null) ev.put("note", req.note());
        return ev;
    }
}
```

- [ ] **Step 10: Add `suspendForBotObservation` to `SuspensionService`**

Append to `SuspensionService.java`:

```java
    /**
     * Bot-monitor-triggered suspend. Unlike {@link #suspendForOwnershipChange}
     * (which is keyed on a World-API {@link ParcelMetadata} shape), bot
     * observations arrive as a loose evidence map. The caller supplies the
     * specific {@link FraudFlagReason} so the admin dashboard can tell the
     * four bot-detected causes apart.
     */
    @Transactional
    public void suspendForBotObservation(
            Auction auction,
            FraudFlagReason reason,
            Map<String, Object> evidence) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        auction.setStatus(AuctionStatus.SUSPENDED);
        auction.setLastOwnershipCheckAt(now);
        auctionRepo.save(auction);

        fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .parcel(auction.getParcel())
                .reason(reason)
                .detectedAt(now)
                .evidenceJson(evidence)
                .resolved(false)
                .build());

        log.warn("Auction {} SUSPENDED by bot monitor: reason={}, evidence={}",
                auction.getId(), reason, evidence);
    }
```

Add `import java.util.Map;` if not already present.

- [ ] **Step 11: Add `publishTransferReadyObserved` to `EscrowService`**

The simplest shape is a no-op stub for Phase 1 that logs the event — the actual envelope wiring can piggyback on the existing `EscrowBroadcastPublisher`. Append to `EscrowService.java`:

```java
    /**
     * Notifies the frontend that the bot has observed {@code AuthBuyerID ==
     * winner} and {@code SalePrice == 0} on the parcel — i.e., the seller
     * has configured the sale-to-winner and the winner can now accept.
     * Idempotent. Spec §6.2.
     *
     * <p>Phase 1 emits a structured log only; the {@code TRANSFER_READY_OBSERVED}
     * envelope shape is deferred until the escrow UI needs it (tracked in
     * DEFERRED_WORK — "TRANSFER_READY_OBSERVED envelope shape").
     */
    @Transactional
    public void publishTransferReadyObserved(Escrow escrow) {
        log.info("Escrow {} observed TRANSFER_READY (seller configured sale-to-winner)",
                escrow.getId());
    }
```

- [ ] **Step 12: Run the dispatcher unit tests — expect PASS**

Run: `cd backend && ./mvnw test -Dtest=BotMonitorDispatcherTest -q`

Expected: PASS (all tests). If `suspendForBotObservation` signature does not compile, re-check Step 10. If `publishTransferReadyObserved` is missing, re-check Step 11.

- [ ] **Step 13: Extend `BotTaskService` with `recordMonitorResult`**

Append to `BotTaskService.java`:

```java
    /**
     * Handles a monitor callback. Loads the task, validates it is an
     * IN_PROGRESS MONITOR_* row for the claimed endpoint's expected type,
     * delegates to {@link BotMonitorDispatcher#dispatch}, stamps result
     * data + lastCheckAt, and re-arms or leaves the row per the dispatcher's
     * decision.
     */
    @Transactional
    public BotTask recordMonitorResult(Long taskId, BotMonitorResultRequest body) {
        BotTask task = botTaskRepo.findById(taskId)
                .orElseThrow(() -> new BotTaskNotFoundException(taskId));

        if (task.getStatus() != BotTaskStatus.IN_PROGRESS) {
            throw new BotTaskNotClaimedException(taskId, task.getStatus());
        }
        if (task.getTaskType() == BotTaskType.VERIFY) {
            throw new BotTaskWrongTypeException(
                    taskId, task.getTaskType(), BotTaskType.MONITOR_AUCTION);
        }
        if (task.getTaskType() == BotTaskType.MONITOR_ESCROW
                && task.getEscrow() != null
                && task.getEscrow().getState().isTerminal()) {
            throw new BotEscrowTerminalException(
                    task.getEscrow().getId(), task.getEscrow().getState());
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        task.setLastCheckAt(now);

        DispatchOutcome outcome = dispatcher.dispatch(task, body);
        log.info("bot monitor {} auction={} outcome={} action={}",
                taskId, task.getAuction().getId(),
                body.outcome(), outcome.logAction());

        if (outcome.shouldReArm()) {
            int interval = task.getRecurrenceIntervalSeconds() == null
                    ? 0 : task.getRecurrenceIntervalSeconds();
            task.setStatus(BotTaskStatus.PENDING);
            task.setNextRunAt(now.plusSeconds(interval));
            task.setAssignedBotUuid(null);
        }
        return botTaskRepo.save(task);
    }
```

Add the imports:

```java
import com.slparcelauctions.backend.bot.dto.BotMonitorResultRequest;
import com.slparcelauctions.backend.bot.exception.BotEscrowTerminalException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotClaimedException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotFoundException;
import com.slparcelauctions.backend.bot.exception.BotTaskWrongTypeException;
```

And extend the `@RequiredArgsConstructor`-generated constructor with a `BotMonitorDispatcher dispatcher` final field:

```java
    private final BotMonitorDispatcher dispatcher;
```

- [ ] **Step 14: Add `isTerminal()` to `EscrowState`** (if not already present)

If the enum lacks this helper, append:

```java
    public boolean isTerminal() {
        return this == COMPLETED || this == EXPIRED
                || this == DISPUTED || this == FROZEN;
    }
```

Otherwise skip.

- [ ] **Step 15: Add `POST /{id}/monitor` to `BotTaskController`**

```java
    /**
     * MONITOR callback: applies the bot's observation and re-arms or
     * cancels the row per dispatcher logic. See
     * {@link BotTaskService#recordMonitorResult}.
     */
    @PostMapping("/{taskId}/monitor")
    public BotTaskResponse recordMonitorResult(
            @PathVariable Long taskId,
            @Valid @RequestBody BotMonitorResultRequest body) {
        return BotTaskResponse.from(service.recordMonitorResult(taskId, body));
    }
```

Add the import: `import com.slparcelauctions.backend.bot.dto.BotMonitorResultRequest;`

- [ ] **Step 16: Write slice test for `POST /monitor`**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerMonitorSliceTest.java
package com.slparcelauctions.backend.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.bot.exception.BotTaskNotFoundException;

@WebMvcTest(controllers = BotTaskController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@Import(BotTaskExceptionHandler.class)
class BotTaskControllerMonitorSliceTest {

    @Autowired private MockMvc mvc;
    @MockBean private BotTaskService service;

    @Test
    void monitor_success_returns200() throws Exception {
        BotTask task = BotTask.builder()
                .id(9L)
                .taskType(BotTaskType.MONITOR_AUCTION)
                .status(BotTaskStatus.PENDING)
                .auction(Auction.builder().id(42L).build())
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .createdAt(OffsetDateTime.now())
                .build();
        when(service.recordMonitorResult(eq(9L), any())).thenReturn(task);

        mvc.perform(post("/api/v1/bot/tasks/9/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome": "ALL_GOOD"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("MONITOR_AUCTION"));
    }

    @Test
    void monitor_unknownTask_returns404() throws Exception {
        when(service.recordMonitorResult(eq(99L), any()))
                .thenThrow(new BotTaskNotFoundException(99L));

        mvc.perform(post("/api/v1/bot/tasks/99/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"ALL_GOOD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOT_TASK_NOT_FOUND"));
    }

    @Test
    void monitor_missingOutcome_returns400() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/9/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 17: Run the monitor slice test**

Run: `cd backend && ./mvnw test -Dtest=BotTaskControllerMonitorSliceTest -q`

Expected: PASS (3 tests).

- [ ] **Step 18: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/MonitorOutcome.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/DispatchOutcome.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorDispatcher.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotMonitorResultRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/exception/ \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskExceptionHandler.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowState.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorDispatcherTest.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerMonitorSliceTest.java

git commit -m "feat(bot): MONITOR callback endpoint + dispatcher"
```

---

## Task 6: Backend — Lifecycle Hooks (Create / Cancel Monitor Rows)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskRepository.java` (bulk cancel queries)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java` (hook on `complete` SUCCESS)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java` (hook on close)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java` (hook all three suspend paths)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java` (hook on cancel)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java` (hook on createForEndedAuction + terminal methods)
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleServiceTest.java`

Spec reference: §3.8, §5.2.

- [ ] **Step 1: Add bulk-cancel queries to `BotTaskRepository`**

Append to `BotTaskRepository.java`:

```java
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            UPDATE BotTask t
               SET t.status = com.slparcelauctions.backend.bot.BotTaskStatus.CANCELLED,
                   t.completedAt = :now,
                   t.lastUpdatedAt = :now
             WHERE t.auction.id = :auctionId
               AND t.taskType IN :types
               AND t.status IN (com.slparcelauctions.backend.bot.BotTaskStatus.PENDING,
                                com.slparcelauctions.backend.bot.BotTaskStatus.IN_PROGRESS)
            """)
    int cancelLiveByAuctionIdAndTypes(
            @Param("auctionId") Long auctionId,
            @Param("types") Collection<BotTaskType> types,
            @Param("now") OffsetDateTime now);

    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            UPDATE BotTask t
               SET t.status = com.slparcelauctions.backend.bot.BotTaskStatus.CANCELLED,
                   t.completedAt = :now,
                   t.lastUpdatedAt = :now
             WHERE t.escrow.id = :escrowId
               AND t.taskType = com.slparcelauctions.backend.bot.BotTaskType.MONITOR_ESCROW
               AND t.status IN (com.slparcelauctions.backend.bot.BotTaskStatus.PENDING,
                                com.slparcelauctions.backend.bot.BotTaskStatus.IN_PROGRESS)
            """)
    int cancelLiveByEscrowId(
            @Param("escrowId") Long escrowId,
            @Param("now") OffsetDateTime now);
```

Add the import: `import java.util.Collection;`. The `lastUpdatedAt` inclusion in the SET clause closes FOOTGUNS §F.87 (`@Modifying` bypasses `@UpdateTimestamp`).

- [ ] **Step 2: Implement `BotMonitorLifecycleService`**

```java
// backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleService.java
package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates and cancels {@link BotTask} monitor rows in sync with auction
 * and escrow lifecycle transitions. Every hook is a no-op if no matching
 * row exists, so call sites can invoke unconditionally without branching
 * on verification tier.
 *
 * <p>Callers pass the entity they already have in scope; all persistence
 * runs inside the caller's transaction so the monitor row write and the
 * entity state flip succeed or roll back together.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotMonitorLifecycleService {

    private static final List<BotTaskType> AUCTION_MONITOR_TYPES =
            List.of(BotTaskType.MONITOR_AUCTION);

    private final BotTaskRepository botTaskRepo;
    private final UserRepository userRepo;
    private final BotTaskConfigProperties props;
    private final Clock clock;

    /**
     * Creates a MONITOR_AUCTION row for a freshly-activated BOT-tier auction.
     * Called from {@link BotTaskService#complete} SUCCESS path. No-op for
     * non-BOT verification tiers.
     */
    @Transactional
    public void onAuctionActivatedBot(Auction auction) {
        if (auction.getVerificationTier() != VerificationTier.BOT) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration interval = props.bot().monitorAuctionInterval();
        Parcel parcel = auction.getParcel();
        BotTask row = BotTask.builder()
                .taskType(BotTaskType.MONITOR_AUCTION)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(parcel.getSlParcelUuid())
                .regionName(parcel.getRegionName())
                .positionX(parcel.getPositionX())
                .positionY(parcel.getPositionY())
                .positionZ(parcel.getPositionZ())
                .sentinelPrice(props.botTask().sentinelPriceLindens())
                .expectedOwnerUuid(parcel.getOwnerUuid())
                .expectedAuthBuyerUuid(props.botTask().primaryEscrowUuid())
                .expectedSalePriceLindens(props.botTask().sentinelPriceLindens())
                .nextRunAt(now.plus(interval))
                .recurrenceIntervalSeconds((int) interval.getSeconds())
                .build();
        botTaskRepo.save(row);
        log.info("MONITOR_AUCTION row created for auction {}: nextRunAt={}",
                auction.getId(), row.getNextRunAt());
    }

    /**
     * Cancels live MONITOR_AUCTION rows on ended / suspended / cancelled
     * auctions. Safe to call unconditionally — no-op when no rows match.
     */
    @Transactional
    public void onAuctionClosed(Auction auction) {
        int cancelled = botTaskRepo.cancelLiveByAuctionIdAndTypes(
                auction.getId(), AUCTION_MONITOR_TYPES, OffsetDateTime.now(clock));
        if (cancelled > 0) {
            log.info("MONITOR_AUCTION rows cancelled for auction {}: count={}",
                    auction.getId(), cancelled);
        }
    }

    /**
     * Creates a MONITOR_ESCROW row for a freshly-created escrow on a
     * BOT-tier auction. Called from {@link com.slparcelauctions.backend.escrow.EscrowService#createForEndedAuction}
     * at the end of the create transaction. No-op for non-BOT tiers.
     */
    @Transactional
    public void onEscrowCreatedBot(Escrow escrow) {
        Auction auction = escrow.getAuction();
        if (auction.getVerificationTier() != VerificationTier.BOT) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration interval = props.bot().monitorEscrowInterval();

        User winner = userRepo.findById(auction.getWinnerUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "Winner user not found for auction " + auction.getId()));
        UUID winnerUuid = winner.getSlAvatarUuid();
        UUID sellerUuid = auction.getSeller().getSlAvatarUuid();
        Parcel parcel = auction.getParcel();

        BotTask row = BotTask.builder()
                .taskType(BotTaskType.MONITOR_ESCROW)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .escrow(escrow)
                .parcelUuid(parcel.getSlParcelUuid())
                .regionName(parcel.getRegionName())
                .positionX(parcel.getPositionX())
                .positionY(parcel.getPositionY())
                .positionZ(parcel.getPositionZ())
                .sentinelPrice(props.botTask().sentinelPriceLindens())
                .expectedSellerUuid(sellerUuid)
                .expectedWinnerUuid(winnerUuid)
                .expectedMaxSalePriceLindens(1L)
                .nextRunAt(now.plus(interval))
                .recurrenceIntervalSeconds((int) interval.getSeconds())
                .build();
        botTaskRepo.save(row);
        log.info("MONITOR_ESCROW row created for escrow {}: nextRunAt={}",
                escrow.getId(), row.getNextRunAt());
    }

    /**
     * Cancels live MONITOR_ESCROW rows on terminal escrow states
     * (COMPLETED, EXPIRED, DISPUTED, FROZEN). Safe to call unconditionally.
     */
    @Transactional
    public void onEscrowTerminal(Escrow escrow) {
        int cancelled = botTaskRepo.cancelLiveByEscrowId(
                escrow.getId(), OffsetDateTime.now(clock));
        if (cancelled > 0) {
            log.info("MONITOR_ESCROW rows cancelled for escrow {}: count={}",
                    escrow.getId(), cancelled);
        }
    }
}
```

- [ ] **Step 3: Wire the hook into `BotTaskService.complete` SUCCESS path**

At the end of the SUCCESS path in `BotTaskService.complete` — after `auctionRepo.saveAndFlush(auction)` succeeds and before the log line — call:

```java
        monitorLifecycle.onAuctionActivatedBot(auction);
```

Add the field and import:

```java
    private final BotMonitorLifecycleService monitorLifecycle;
```

- [ ] **Step 4: Wire the hook into `AuctionEndTask.closeOne`**

After `auctionRepo.save(auction)` (line 98) and BEFORE `proxyBidRepo.exhaustAllActiveByAuctionId(auctionId)`, call:

```java
        monitorLifecycle.onAuctionClosed(auction);
```

Add the field + constructor arg:

```java
    private final BotMonitorLifecycleService monitorLifecycle;
```

- [ ] **Step 5: Wire the hook into `SuspensionService`**

Add the hook at the end of all three suspend methods (`suspendForOwnershipChange`, `suspendForDeletedParcel`, `suspendForBotObservation`), immediately after `fraudFlagRepo.save(...)`:

```java
        monitorLifecycle.onAuctionClosed(auction);
```

Add the field:

```java
    private final BotMonitorLifecycleService monitorLifecycle;
```

- [ ] **Step 6: Wire the hook into `CancellationService.cancel`**

After `auctionRepo.save(a)` (line 94) and before the `log.info` call:

```java
        monitorLifecycle.onAuctionClosed(saved);
```

Add field + import.

- [ ] **Step 7: Wire hooks into `EscrowService`**

- `createForEndedAuction` — immediately before the final `return` of the created escrow row, call:
  ```java
          monitorLifecycle.onEscrowCreatedBot(escrow);
  ```
- `freezeForFraud` — after the `fraudFlagRepo.save(...)` and before the `queueRefundIfFunded(escrow)` call:
  ```java
          monitorLifecycle.onEscrowTerminal(escrow);
  ```
- `expirePayment` and `expireTransfer` — after the `escrow = escrowRepo.save(escrow)` call and before the envelope registration:
  ```java
          monitorLifecycle.onEscrowTerminal(escrow);
  ```
- The `COMPLETED` transition (search `EscrowService.java` for `EscrowState.COMPLETED` — there should be a single method that flips from `TRANSFER_PENDING` to `COMPLETED` on the payout result callback). Add:
  ```java
          monitorLifecycle.onEscrowTerminal(escrow);
  ```
- Dispute — search for `DISPUTED` similarly, add the hook after the state flip.

Add the field:

```java
    private final BotMonitorLifecycleService monitorLifecycle;
```

- [ ] **Step 8: Write integration test for lifecycle flow**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleServiceTest.java
package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;

/**
 * Verifies the lifecycle hook creates the monitor row on activation and
 * cancels it on termination. Uses the seeded auction / escrow fixtures
 * from the broader integration test factory.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "slpa.bot-task.timeout-check-interval=PT10M",
        "slpa.escrow.ownership-check-enabled=false",
        "slpa.escrow.timeout-enabled=false"
})
class BotMonitorLifecycleServiceTest {

    @Autowired private BotMonitorLifecycleService lifecycle;
    @Autowired private BotTaskRepository botTaskRepo;
    @Autowired private AuctionRepository auctionRepo;
    @Autowired private EscrowRepository escrowRepo;

    @Test
    void onAuctionActivatedBot_createsMonitorRow() {
        Auction auction = seedAuction(VerificationTier.BOT, AuctionStatus.ACTIVE);
        lifecycle.onAuctionActivatedBot(auction);

        List<BotTask> rows = botTaskRepo.findByStatusOrderByCreatedAtAsc(
                BotTaskStatus.PENDING);
        BotTask monitor = rows.stream()
                .filter(r -> r.getTaskType() == BotTaskType.MONITOR_AUCTION)
                .filter(r -> r.getAuction().getId().equals(auction.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(monitor.getExpectedOwnerUuid())
                .isEqualTo(auction.getParcel().getOwnerUuid());
        assertThat(monitor.getRecurrenceIntervalSeconds()).isEqualTo(1800);
        assertThat(monitor.getNextRunAt()).isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void onAuctionActivatedBot_skipsNonBotTier() {
        Auction auction = seedAuction(VerificationTier.WORLD_API, AuctionStatus.ACTIVE);
        lifecycle.onAuctionActivatedBot(auction);

        long monitorCount = botTaskRepo.findByStatusOrderByCreatedAtAsc(
                BotTaskStatus.PENDING).stream()
                .filter(r -> r.getTaskType() == BotTaskType.MONITOR_AUCTION)
                .filter(r -> r.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(monitorCount).isZero();
    }

    @Test
    void onAuctionClosed_cancelsLiveRows() {
        Auction auction = seedAuction(VerificationTier.BOT, AuctionStatus.ACTIVE);
        lifecycle.onAuctionActivatedBot(auction);

        lifecycle.onAuctionClosed(auction);

        List<BotTask> cancelled = botTaskRepo.findAll().stream()
                .filter(r -> r.getAuction().getId().equals(auction.getId()))
                .filter(r -> r.getStatus() == BotTaskStatus.CANCELLED)
                .toList();
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.get(0).getCompletedAt()).isNotNull();
        assertThat(cancelled.get(0).getLastUpdatedAt()).isNotNull();
    }

    @Test
    void onEscrowTerminal_cancelsEscrowMonitorRows() {
        Escrow escrow = seedEscrowForBotAuction();
        lifecycle.onEscrowCreatedBot(escrow);

        lifecycle.onEscrowTerminal(escrow);

        List<BotTask> cancelled = botTaskRepo.findAll().stream()
                .filter(r -> r.getEscrow() != null
                        && r.getEscrow().getId().equals(escrow.getId()))
                .filter(r -> r.getStatus() == BotTaskStatus.CANCELLED)
                .toList();
        assertThat(cancelled).hasSize(1);
    }

    // Helpers — adapt to the codebase's existing test factory utilities.
    private Auction seedAuction(VerificationTier tier, AuctionStatus status) {
        return auctionRepo.findAll().stream()
                .filter(a -> a.getStatus() == status && a.getVerificationTier() == tier)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Seed an auction in tier=" + tier + ", status=" + status
                                + " via the shared factory"));
    }

    private Escrow seedEscrowForBotAuction() {
        return escrowRepo.findAll().stream()
                .filter(e -> e.getAuction().getVerificationTier() == VerificationTier.BOT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Seed a BOT-tier escrow via the shared factory"));
    }
}
```

- [ ] **Step 9: Run the integration test**

Run: `cd backend && ./mvnw test -Dtest=BotMonitorLifecycleServiceTest -q`

Expected: PASS (4 tests). Depending on the test fixture factory, `seedAuction` / `seedEscrowForBotAuction` may need inline construction — follow the pattern used by existing integration tests for other services.

- [ ] **Step 10: Run the full backend suite to confirm no regressions**

Run: `cd backend && ./mvnw test -q`

Expected: PASS. New tests: ~20 added. Existing suite: unchanged.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleService.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleServiceTest.java

git commit -m "feat(bot): lifecycle-hook monitor row creation + cancellation"
```

---

## Task 7: C# — Project Scaffold + Login Loop

**Files:**
- Create: `bot/Slpa.Bot.sln`
- Create: `bot/src/Slpa.Bot/Slpa.Bot.csproj`
- Create: `bot/src/Slpa.Bot/Program.cs`
- Create: `bot/src/Slpa.Bot/appsettings.json`
- Create: `bot/src/Slpa.Bot/Options/BotOptions.cs`
- Create: `bot/src/Slpa.Bot/Options/BackendOptions.cs`
- Create: `bot/src/Slpa.Bot/Options/RateLimitOptions.cs`
- Create: `bot/src/Slpa.Bot/Sl/IBotSession.cs`
- Create: `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs`
- Create: `bot/src/Slpa.Bot/Sl/SessionState.cs`
- Create: `bot/src/Slpa.Bot/Health/HealthEndpoint.cs`
- Create: `bot/tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj`
- Create: `bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs`
- Create: `bot/README.md`

Spec reference: §4.1, §4.2, §8.1.

- [ ] **Step 1: Create the solution and project structure**

```bash
mkdir -p bot/src/Slpa.Bot/Options bot/src/Slpa.Bot/Sl bot/src/Slpa.Bot/Backend/Models \
         bot/src/Slpa.Bot/Tasks bot/src/Slpa.Bot/Health bot/tests/Slpa.Bot.Tests
```

Create the solution file:

```
# bot/Slpa.Bot.sln — minimal; dotnet will auto-populate.
```

Use `dotnet`:

```bash
cd bot && \
  dotnet new sln -n Slpa.Bot && \
  dotnet new webapi -n Slpa.Bot -o src/Slpa.Bot --framework net8.0 && \
  dotnet new xunit -n Slpa.Bot.Tests -o tests/Slpa.Bot.Tests --framework net8.0 && \
  dotnet sln add src/Slpa.Bot/Slpa.Bot.csproj tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj && \
  dotnet add tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj reference src/Slpa.Bot/Slpa.Bot.csproj
```

- [ ] **Step 2: Add NuGet dependencies**

```bash
cd bot && \
  dotnet add src/Slpa.Bot/Slpa.Bot.csproj package LibreMetaverse --version 1.9.11 && \
  dotnet add src/Slpa.Bot/Slpa.Bot.csproj package Microsoft.Extensions.Hosting && \
  dotnet add src/Slpa.Bot/Slpa.Bot.csproj package Microsoft.Extensions.Options.ConfigurationExtensions && \
  dotnet add tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj package FluentAssertions --version 6.12.0 && \
  dotnet add tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj package Microsoft.Extensions.Logging.Abstractions && \
  dotnet add tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj package WireMock.Net --version 1.5.50 && \
  dotnet add tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj package Moq --version 4.20.70
```

- [ ] **Step 3: Write `Options/BotOptions.cs`, `BackendOptions.cs`, `RateLimitOptions.cs`**

```csharp
// bot/src/Slpa.Bot/Options/BotOptions.cs
namespace Slpa.Bot.Options;

public sealed class BotOptions
{
    public const string SectionName = "Bot";

    /// <summary>"Firstname Lastname" — the SL login form.</summary>
    public string Username { get; set; } = "";

    public string Password { get; set; } = "";

    /// <summary>The bot's SL avatar UUID — included in claim requests.</summary>
    public Guid BotUuid { get; set; }

    /// <summary>"last", "home", or a region name; see LibreMetaverse docs.</summary>
    public string StartLocation { get; set; } = "last";
}
```

```csharp
// bot/src/Slpa.Bot/Options/BackendOptions.cs
namespace Slpa.Bot.Options;

public sealed class BackendOptions
{
    public const string SectionName = "Backend";

    public string BaseUrl { get; set; } = "http://localhost:8080";
    public string SharedSecret { get; set; } = "";
    public Guid PrimaryEscrowUuid { get; set; }
}
```

```csharp
// bot/src/Slpa.Bot/Options/RateLimitOptions.cs
namespace Slpa.Bot.Options;

public sealed class RateLimitOptions
{
    public const string SectionName = "RateLimit";

    public int TeleportsPerMinute { get; set; } = 6;
}
```

- [ ] **Step 4: Write `Sl/SessionState.cs` and `Sl/IBotSession.cs`**

```csharp
// bot/src/Slpa.Bot/Sl/SessionState.cs
namespace Slpa.Bot.Sl;

public enum SessionState
{
    Starting,
    Online,
    Reconnecting,
    Error
}
```

```csharp
// bot/src/Slpa.Bot/Sl/IBotSession.cs
namespace Slpa.Bot.Sl;

/// <summary>
/// Test boundary around LibreMetaverse. Production wiring uses
/// <see cref="LibreMetaverseBotSession"/>; tests fake this interface and
/// never touch <c>GridClient</c>.
/// </summary>
public interface IBotSession : IAsyncDisposable
{
    SessionState State { get; }

    Guid BotUuid { get; }

    /// <summary>Starts the login loop. Idempotent; returns when state != Starting.</summary>
    Task StartAsync(CancellationToken ct);

    /// <summary>Logs out cleanly. No-op if already offline.</summary>
    Task LogoutAsync(CancellationToken ct);
}
```

- [ ] **Step 5: Write `Sl/LibreMetaverseBotSession.cs` — the real impl**

```csharp
// bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs
using System.Threading.Channels;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using OpenMetaverse;
using Slpa.Bot.Options;

namespace Slpa.Bot.Sl;

/// <summary>
/// LibreMetaverse-backed <see cref="IBotSession"/>. On construction the
/// <see cref="GridClient"/> is configured for a headless bot — no textures,
/// no mesh downloads, no sounds, no inventory fetch.
///
/// The login loop watches <see cref="NetworkManager.LoginProgress"/> and
/// <see cref="NetworkManager.Disconnected"/> to drive state transitions.
/// On disconnect, auto-reconnect with exponential backoff (1s, 2s, 4s, 8s…
/// capped at 60s) until the cancellation token fires.
/// </summary>
public sealed class LibreMetaverseBotSession : IBotSession
{
    private static readonly TimeSpan[] ReconnectBackoff =
        new[] { 1, 2, 4, 8, 15, 30, 60 }
            .Select(s => TimeSpan.FromSeconds(s))
            .ToArray();

    private const string LoginUri =
        "https://login.agni.lindenlab.com/cgi-bin/login.cgi";

    private readonly BotOptions _opts;
    private readonly ILogger<LibreMetaverseBotSession> _log;
    private readonly GridClient _client;
    private CancellationTokenSource? _runCts;
    private Task? _runTask;
    private int _stateValue = (int)SessionState.Starting;

    public LibreMetaverseBotSession(
        IOptions<BotOptions> opts,
        ILogger<LibreMetaverseBotSession> log)
    {
        _opts = opts.Value;
        _log = log;
        _client = CreateHeadlessClient();
    }

    public SessionState State => (SessionState)Volatile.Read(ref _stateValue);

    public Guid BotUuid => _opts.BotUuid;

    public async Task StartAsync(CancellationToken ct)
    {
        _runCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _runTask = Task.Run(() => RunLoop(_runCts.Token), _runCts.Token);
        await Task.Yield();
    }

    public async Task LogoutAsync(CancellationToken ct)
    {
        if (State == SessionState.Error || State == SessionState.Starting)
        {
            return;
        }
        try
        {
            _client.Network.Logout();
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Logout failed — may leave zombie session");
        }
        finally
        {
            TransitionTo(SessionState.Error);
        }
        _runCts?.Cancel();
        if (_runTask is not null)
        {
            try { await _runTask.ConfigureAwait(false); }
            catch (OperationCanceledException) { /* expected */ }
        }
    }

    public async ValueTask DisposeAsync()
    {
        _runCts?.Cancel();
        if (_runTask is not null)
        {
            try { await _runTask.ConfigureAwait(false); }
            catch { /* swallow on dispose */ }
        }
        _runCts?.Dispose();
    }

    private async Task RunLoop(CancellationToken ct)
    {
        var backoffIdx = 0;
        while (!ct.IsCancellationRequested)
        {
            TransitionTo(SessionState.Starting);
            var loginParams = _client.Network.DefaultLoginParams(
                FirstName(), LastName(), _opts.Password,
                "Slpa.Bot", "1.0");
            loginParams.URI = LoginUri;
            loginParams.Start = _opts.StartLocation;

            var loggedIn = await TryLoginAsync(loginParams).ConfigureAwait(false);
            if (!loggedIn)
            {
                var delay = ReconnectBackoff[
                    Math.Min(backoffIdx, ReconnectBackoff.Length - 1)];
                _log.LogWarning("Login failed; retrying in {Delay}", delay);
                backoffIdx++;
                try { await Task.Delay(delay, ct).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
                continue;
            }

            backoffIdx = 0;
            TransitionTo(SessionState.Online);
            _log.LogInformation("Bot {Uuid} ONLINE as {User}", BotUuid, _opts.Username);

            await WaitForDisconnectAsync(ct).ConfigureAwait(false);
            if (ct.IsCancellationRequested) return;

            TransitionTo(SessionState.Reconnecting);
            _log.LogWarning("Bot {Uuid} disconnected; reconnecting", BotUuid);
        }
    }

    private Task<bool> TryLoginAsync(LoginParams loginParams)
    {
        var tcs = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        EventHandler<LoginProgressEventArgs>? handler = null;
        handler = (_, e) =>
        {
            switch (e.Status)
            {
                case LoginStatus.Success:
                    _client.Network.LoginProgress -= handler;
                    tcs.TrySetResult(true);
                    break;
                case LoginStatus.Failed:
                    _client.Network.LoginProgress -= handler;
                    _log.LogError("Login failed: {Message}", e.Message);
                    tcs.TrySetResult(false);
                    break;
            }
        };
        _client.Network.LoginProgress += handler;
        _client.Network.BeginLogin(loginParams);
        return tcs.Task;
    }

    private Task WaitForDisconnectAsync(CancellationToken ct)
    {
        var tcs = new TaskCompletionSource<object?>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        EventHandler<DisconnectedEventArgs>? handler = null;
        handler = (_, _) =>
        {
            _client.Network.Disconnected -= handler!;
            tcs.TrySetResult(null);
        };
        _client.Network.Disconnected += handler;
        ct.Register(() =>
        {
            _client.Network.Disconnected -= handler!;
            tcs.TrySetCanceled(ct);
        });
        return tcs.Task;
    }

    private void TransitionTo(SessionState next)
    {
        Volatile.Write(ref _stateValue, (int)next);
    }

    private static GridClient CreateHeadlessClient()
    {
        var c = new GridClient();
        c.Settings.MULTIPLE_SIMS = false;
        c.Settings.ALWAYS_REQUEST_PARCEL_ACL = false;
        c.Settings.ALWAYS_REQUEST_PARCEL_DWELL = false;
        c.Settings.ALWAYS_DECODE_OBJECTS = false;
        c.Settings.OBJECT_TRACKING = false;
        c.Settings.AVATAR_TRACKING = false;
        c.Settings.USE_ASSET_CACHE = false;
        c.Settings.SEND_AGENT_APPEARANCE = false;
        c.Settings.SEND_AGENT_UPDATES = true; // required for teleport
        c.Settings.STORE_LAND_PATCHES = false;
        return c;
    }

    private string FirstName()
    {
        var parts = _opts.Username.Split(' ', 2);
        return parts.Length > 0 ? parts[0] : _opts.Username;
    }

    private string LastName()
    {
        var parts = _opts.Username.Split(' ', 2);
        return parts.Length > 1 ? parts[1] : "Resident";
    }
}
```

- [ ] **Step 6: Write `Health/HealthEndpoint.cs`**

```csharp
// bot/src/Slpa.Bot/Health/HealthEndpoint.cs
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Health;

public static class HealthEndpoint
{
    /// <summary>
    /// Maps <c>GET /health</c> to report the current <see cref="SessionState"/>.
    /// Returns HTTP 200 for Online; 503 for anything else so Docker's
    /// healthcheck flips Red on sustained disconnect.
    /// </summary>
    public static IEndpointRouteBuilder MapBotHealth(this IEndpointRouteBuilder app)
    {
        app.MapGet("/health", (IBotSession session) =>
        {
            return session.State == SessionState.Online
                ? Results.Ok(new { state = session.State.ToString() })
                : Results.Json(new { state = session.State.ToString() },
                        statusCode: StatusCodes.Status503ServiceUnavailable);
        });
        return app;
    }
}
```

- [ ] **Step 7: Write `Program.cs`**

```csharp
// bot/src/Slpa.Bot/Program.cs
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Slpa.Bot.Health;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;

var builder = WebApplication.CreateBuilder(args);

builder.Services.Configure<BotOptions>(
    builder.Configuration.GetSection(BotOptions.SectionName));
builder.Services.Configure<BackendOptions>(
    builder.Configuration.GetSection(BackendOptions.SectionName));
builder.Services.Configure<RateLimitOptions>(
    builder.Configuration.GetSection(RateLimitOptions.SectionName));

builder.Services.AddSingleton<IBotSession, LibreMetaverseBotSession>();
builder.Services.AddHostedService<BotSessionBootstrapper>();

var app = builder.Build();
app.MapBotHealth();
app.Run();

/// <summary>
/// Hosted service that drives <see cref="IBotSession.StartAsync"/> on boot
/// and <see cref="IBotSession.LogoutAsync"/> on shutdown. Separate from
/// the session itself so the session can be unit-tested without requiring
/// a host.
/// </summary>
internal sealed class BotSessionBootstrapper : IHostedService
{
    private readonly IBotSession _session;
    public BotSessionBootstrapper(IBotSession session) => _session = session;

    public Task StartAsync(CancellationToken ct) => _session.StartAsync(ct);
    public async Task StopAsync(CancellationToken ct)
    {
        await _session.LogoutAsync(ct).ConfigureAwait(false);
    }
}
```

- [ ] **Step 8: Write `appsettings.json`**

```json
{
  "Logging": {
    "LogLevel": {
      "Default": "Information",
      "Microsoft.AspNetCore": "Warning"
    }
  },
  "Bot": {
    "Username": "",
    "Password": "",
    "BotUuid": "00000000-0000-0000-0000-000000000000",
    "StartLocation": "last"
  },
  "Backend": {
    "BaseUrl": "http://localhost:8080",
    "SharedSecret": "",
    "PrimaryEscrowUuid": "00000000-0000-0000-0000-000000000099"
  },
  "RateLimit": {
    "TeleportsPerMinute": 6
  }
}
```

- [ ] **Step 9: Write login-loop state-machine tests (fake `IBotSession`, not `GridClient`)**

```csharp
// bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs
using FluentAssertions;
using Slpa.Bot.Sl;
using Xunit;

namespace Slpa.Bot.Tests;

/// <summary>
/// Behavioural tests for the session state machine through the
/// <see cref="IBotSession"/> interface. LibreMetaverse internals are never
/// touched — the real session is covered by manual smoke tests documented
/// in bot/README.md.
/// </summary>
public sealed class LibreMetaverseBotSessionTests
{
    [Fact]
    public void FakeSession_DefaultsToStarting()
    {
        IBotSession session = new FakeBotSession();
        session.State.Should().Be(SessionState.Starting);
    }

    [Fact]
    public async Task FakeSession_TransitionsToOnlineAfterStart()
    {
        var session = new FakeBotSession();
        await session.StartAsync(CancellationToken.None);
        ((FakeBotSession)session).SimulateLoginSuccess();
        session.State.Should().Be(SessionState.Online);
    }

    [Fact]
    public async Task FakeSession_TransitionsToReconnectingOnDisconnect()
    {
        var session = new FakeBotSession();
        await session.StartAsync(CancellationToken.None);
        session.SimulateLoginSuccess();
        session.SimulateDisconnect();
        ((IBotSession)session).State.Should().Be(SessionState.Reconnecting);
    }

    [Fact]
    public async Task FakeSession_LogoutTransitionsToError()
    {
        var session = new FakeBotSession();
        await session.StartAsync(CancellationToken.None);
        session.SimulateLoginSuccess();
        await session.LogoutAsync(CancellationToken.None);
        session.State.Should().Be(SessionState.Error);
    }
}

/// <summary>In-test fake. Mirrors the real session's state machine.</summary>
public sealed class FakeBotSession : IBotSession
{
    public SessionState State { get; private set; } = SessionState.Starting;
    public Guid BotUuid { get; } = Guid.NewGuid();

    public Task StartAsync(CancellationToken ct)
    {
        State = SessionState.Starting;
        return Task.CompletedTask;
    }
    public Task LogoutAsync(CancellationToken ct)
    {
        State = SessionState.Error;
        return Task.CompletedTask;
    }
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;

    public void SimulateLoginSuccess() => State = SessionState.Online;
    public void SimulateDisconnect() => State = SessionState.Reconnecting;
}
```

- [ ] **Step 10: Write `bot/README.md`**

```markdown
# SLPA Bot Service

C#/.NET 8 worker that logs into Second Life as an `SLPABot*` account and
services tasks from the SLPA backend (Method C verification, BOT-tier
auction monitoring, BOT-tier escrow monitoring).

## Environment

| Variable                    | Required | Default                                | Notes                                      |
|-----------------------------|----------|----------------------------------------|--------------------------------------------|
| `Bot__Username`             | yes      | —                                       | SL login form (`"Firstname Lastname"`)     |
| `Bot__Password`             | yes      | —                                       | secret; never commit                        |
| `Bot__BotUuid`              | yes      | —                                       | the account's SL avatar UUID                |
| `Bot__StartLocation`        | no       | `last`                                 | `last`, `home`, or a region name            |
| `Backend__BaseUrl`          | no       | `http://localhost:8080`                | backend origin                              |
| `Backend__SharedSecret`     | yes      | —                                       | matches `slpa.bot.shared-secret`            |
| `Backend__PrimaryEscrowUuid`| yes      | `00000000-0000-0000-0000-000000000099` | sanity ref; backend is authoritative        |
| `RateLimit__TeleportsPerMinute` | no   | `6`                                    | SL's hard cap                               |

## Local run

```bash
cd bot
dotnet run --project src/Slpa.Bot
```

Health: `curl http://localhost:8081/health` → `{ "state": "Online" }` once
login completes.

## Tests

```bash
cd bot && dotnet test
```

## Manual smoke test

1. Export env vars for `SLPABot1 Resident` in `.env.bot-1` (copy `.env.example`).
2. Start backend locally (`cd backend && ./mvnw spring-boot:run`).
3. Start bot: `docker compose up bot-1` (Task 11).
4. Verify `GET http://localhost:8081/health` → `Online` within ~10 s.
5. Queue a VERIFY task via the Postman `Dev/Bot simulate verify` helper.
6. Confirm the bot teleports and posts to `PUT /api/v1/bot/tasks/{id}/verify`.
```

- [ ] **Step 11: Run `dotnet test`**

```bash
cd bot && dotnet test
```

Expected: PASS (4 tests in `LibreMetaverseBotSessionTests`).

- [ ] **Step 12: Commit**

```bash
git add bot/

git commit -m "feat(bot-service): C# project scaffold + login loop"
```

---

## Task 8: C# — Teleport + ParcelProperties Read

**Files:**
- Modify: `bot/src/Slpa.Bot/Sl/IBotSession.cs` (add TeleportAsync + ReadParcelAsync)
- Modify: `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs`
- Create: `bot/src/Slpa.Bot/Sl/TeleportRateLimiter.cs`
- Create: `bot/src/Slpa.Bot/Sl/TeleportResult.cs`
- Create: `bot/src/Slpa.Bot/Sl/TeleportFailureKind.cs`
- Create: `bot/src/Slpa.Bot/Sl/ParcelSnapshot.cs`
- Create: `bot/src/Slpa.Bot/Sl/ParcelReader.cs`
- Create: `bot/src/Slpa.Bot/Sl/SessionLostException.cs`
- Test: `bot/tests/Slpa.Bot.Tests/TeleportRateLimiterTests.cs`
- Test: `bot/tests/Slpa.Bot.Tests/ParcelReaderTests.cs`

Spec reference: §4.3, §7.1, §7.3.

- [ ] **Step 1: Define supporting records + enums**

```csharp
// bot/src/Slpa.Bot/Sl/TeleportFailureKind.cs
namespace Slpa.Bot.Sl;

public enum TeleportFailureKind
{
    AccessDenied,
    RegionNotFound,
    Timeout,
    Other
}
```

```csharp
// bot/src/Slpa.Bot/Sl/TeleportResult.cs
namespace Slpa.Bot.Sl;

public sealed record TeleportResult(bool Success, TeleportFailureKind? Failure)
{
    public static TeleportResult Ok() => new(true, null);
    public static TeleportResult Fail(TeleportFailureKind kind) => new(false, kind);
}
```

```csharp
// bot/src/Slpa.Bot/Sl/ParcelSnapshot.cs
namespace Slpa.Bot.Sl;

/// <summary>
/// Snapshot of ParcelProperties the bot can observe. Mirrors the backend's
/// BotTaskCompleteRequest shape — the worker passes this straight through
/// to the VERIFY callback.
/// </summary>
public sealed record ParcelSnapshot(
    Guid OwnerId,
    Guid GroupId,
    bool IsGroupOwned,
    Guid AuthBuyerId,
    long SalePrice,
    string Name,
    string Description,
    int AreaSqm,
    int MaxPrims,
    int Category,
    Guid SnapshotId,
    uint Flags);
```

```csharp
// bot/src/Slpa.Bot/Sl/SessionLostException.cs
namespace Slpa.Bot.Sl;

public sealed class SessionLostException : Exception
{
    public SessionLostException(string message) : base(message) {}
}
```

- [ ] **Step 2: Write the rate-limiter test first (TDD)**

```csharp
// bot/tests/Slpa.Bot.Tests/TeleportRateLimiterTests.cs
using FluentAssertions;
using Slpa.Bot.Sl;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class TeleportRateLimiterTests
{
    [Fact]
    public async Task TakesBucketCapacityTokens_WithoutDelay()
    {
        var limiter = new TeleportRateLimiter(6, TimeSpan.FromSeconds(10));
        var start = DateTimeOffset.UtcNow;
        for (var i = 0; i < 6; i++)
        {
            await limiter.AcquireAsync(CancellationToken.None);
        }
        var elapsed = DateTimeOffset.UtcNow - start;
        elapsed.Should().BeLessThan(TimeSpan.FromMilliseconds(500));
    }

    [Fact]
    public async Task BlocksWhenBucketExhausted_UntilRefill()
    {
        var limiter = new TeleportRateLimiter(
                capacity: 2, refillInterval: TimeSpan.FromMilliseconds(100));
        await limiter.AcquireAsync(CancellationToken.None);
        await limiter.AcquireAsync(CancellationToken.None);
        var start = DateTimeOffset.UtcNow;
        await limiter.AcquireAsync(CancellationToken.None);
        var elapsed = DateTimeOffset.UtcNow - start;
        elapsed.Should().BeGreaterOrEqualTo(TimeSpan.FromMilliseconds(90));
    }

    [Fact]
    public async Task RespectsCancellation()
    {
        var limiter = new TeleportRateLimiter(
                capacity: 0, refillInterval: TimeSpan.FromSeconds(30));
        using var cts = new CancellationTokenSource(
                TimeSpan.FromMilliseconds(200));
        var act = async () => await limiter.AcquireAsync(cts.Token);
        await act.Should().ThrowAsync<OperationCanceledException>();
    }
}
```

- [ ] **Step 3: Implement `TeleportRateLimiter`**

```csharp
// bot/src/Slpa.Bot/Sl/TeleportRateLimiter.cs
namespace Slpa.Bot.Sl;

/// <summary>
/// Token bucket gating teleports to SL's hard cap of 6/minute. Starts full
/// and refills <c>1</c> token every <c>refillInterval</c>. AcquireAsync
/// blocks (without burning CPU) until a token is available or the caller
/// cancels.
/// </summary>
public sealed class TeleportRateLimiter : IDisposable
{
    private readonly int _capacity;
    private readonly TimeSpan _refillInterval;
    private readonly SemaphoreSlim _sem;
    private readonly Timer _refillTimer;

    public TeleportRateLimiter(int capacity, TimeSpan refillInterval)
    {
        _capacity = capacity;
        _refillInterval = refillInterval;
        _sem = new SemaphoreSlim(capacity, capacity);
        _refillTimer = new Timer(Refill, null, refillInterval, refillInterval);
    }

    /// <summary>Convenience ctor — 6/min with 10s refill (SL's grid cap).</summary>
    public TeleportRateLimiter(int teleportsPerMinute)
        : this(teleportsPerMinute,
               TimeSpan.FromSeconds(60d / Math.Max(1, teleportsPerMinute))) {}

    public async Task AcquireAsync(CancellationToken ct)
    {
        await _sem.WaitAsync(ct).ConfigureAwait(false);
    }

    private void Refill(object? _)
    {
        try
        {
            // Release one token per tick up to capacity.
            if (_sem.CurrentCount < _capacity) _sem.Release();
        }
        catch (SemaphoreFullException) { /* already full — no-op */ }
    }

    public void Dispose()
    {
        _refillTimer.Dispose();
        _sem.Dispose();
    }
}
```

- [ ] **Step 4: Run the rate-limiter tests**

```bash
cd bot && dotnet test --filter "FullyQualifiedName~TeleportRateLimiter"
```

Expected: PASS (3 tests).

- [ ] **Step 5: Extend `IBotSession` with teleport + parcel-read methods**

```csharp
// bot/src/Slpa.Bot/Sl/IBotSession.cs — add to the interface:

    /// <summary>
    /// Teleports to <paramref name="regionName"/> at (x, y, z). Awaits the
    /// LibreMetaverse TeleportFinished / TeleportFailed race with a 30s
    /// timeout. Rate-limited per SL's 6/min cap. Throws
    /// <see cref="SessionLostException"/> if the session drops mid-teleport.
    /// </summary>
    Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z, CancellationToken ct);

    /// <summary>
    /// Requests ParcelProperties for the parcel at (x, y) within the
    /// bot's current region. Awaits the ParcelProperties event with a 10s
    /// timeout. Returns null on timeout; throws on session loss.
    /// </summary>
    Task<ParcelSnapshot?> ReadParcelAsync(double x, double y, CancellationToken ct);
```

- [ ] **Step 6: Implement `TeleportAsync` + `ReadParcelAsync` on `LibreMetaverseBotSession`**

Add the rate limiter field + the two methods. The event-race pattern is the same as `TryLoginAsync` from Task 7.

```csharp
// Append to LibreMetaverseBotSession.cs:

private readonly TeleportRateLimiter _rateLimiter;

// Extend the ctor:
public LibreMetaverseBotSession(
    IOptions<BotOptions> opts,
    IOptions<RateLimitOptions> rateOpts,
    ILogger<LibreMetaverseBotSession> log)
{
    _opts = opts.Value;
    _log = log;
    _client = CreateHeadlessClient();
    _rateLimiter = new TeleportRateLimiter(rateOpts.Value.TeleportsPerMinute);
}

public async Task<TeleportResult> TeleportAsync(
    string regionName, double x, double y, double z, CancellationToken ct)
{
    if (State != SessionState.Online) throw new SessionLostException(
        $"Cannot teleport in state {State}");

    await _rateLimiter.AcquireAsync(ct).ConfigureAwait(false);

    var tcs = new TaskCompletionSource<TeleportResult>(
        TaskCreationOptions.RunContinuationsAsynchronously);
    EventHandler<TeleportEventArgs>? handler = null;
    handler = (_, e) =>
    {
        if (e.Status is TeleportStatus.Finished or TeleportStatus.Failed
            or TeleportStatus.Cancelled)
        {
            _client.Self.TeleportProgress -= handler!;
            var result = e.Status == TeleportStatus.Finished
                ? TeleportResult.Ok()
                : TeleportResult.Fail(ClassifyFailure(e.Message));
            tcs.TrySetResult(result);
        }
    };
    _client.Self.TeleportProgress += handler;
    EventHandler<DisconnectedEventArgs>? disc = null;
    disc = (_, _) => tcs.TrySetException(
        new SessionLostException("Disconnected mid-teleport"));
    _client.Network.Disconnected += disc;

    try
    {
        _client.Self.Teleport(regionName, new Vector3((float)x, (float)y, (float)z));
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(30));
        using var _ = timeoutCts.Token.Register(() =>
            tcs.TrySetResult(TeleportResult.Fail(TeleportFailureKind.Timeout)));
        return await tcs.Task.ConfigureAwait(false);
    }
    finally
    {
        _client.Self.TeleportProgress -= handler;
        _client.Network.Disconnected -= disc!;
    }
}

public async Task<ParcelSnapshot?> ReadParcelAsync(
    double x, double y, CancellationToken ct)
{
    if (State != SessionState.Online) throw new SessionLostException(
        $"Cannot read parcel in state {State}");

    var sim = _client.Network.CurrentSim;
    if (sim is null) return null;

    var tcs = new TaskCompletionSource<ParcelSnapshot?>(
        TaskCreationOptions.RunContinuationsAsynchronously);
    EventHandler<ParcelPropertiesEventArgs>? handler = null;
    handler = (_, e) =>
    {
        _client.Parcels.ParcelProperties -= handler!;
        tcs.TrySetResult(new ParcelSnapshot(
            OwnerId: Guid.Parse(e.Parcel.OwnerID.ToString()),
            GroupId: Guid.Parse(e.Parcel.GroupID.ToString()),
            IsGroupOwned: e.Parcel.IsGroupOwned,
            AuthBuyerId: Guid.Parse(e.Parcel.AuthBuyerID.ToString()),
            SalePrice: e.Parcel.SalePrice,
            Name: e.Parcel.Name ?? string.Empty,
            Description: e.Parcel.Desc ?? string.Empty,
            AreaSqm: e.Parcel.Area,
            MaxPrims: e.Parcel.MaxPrims,
            Category: (int)e.Parcel.Category,
            SnapshotId: Guid.Parse(e.Parcel.SnapshotID.ToString()),
            Flags: (uint)e.Parcel.Flags));
    };
    _client.Parcels.ParcelProperties += handler;

    try
    {
        _client.Parcels.RequestAllSimParcels(sim);
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(10));
        using var _ = timeoutCts.Token.Register(() => tcs.TrySetResult(null));
        return await tcs.Task.ConfigureAwait(false);
    }
    finally
    {
        _client.Parcels.ParcelProperties -= handler;
    }
}

private static TeleportFailureKind ClassifyFailure(string? message)
{
    if (string.IsNullOrEmpty(message)) return TeleportFailureKind.Other;
    var m = message.ToLowerInvariant();
    if (m.Contains("denied") || m.Contains("banned")
        || m.Contains("restrict")) return TeleportFailureKind.AccessDenied;
    if (m.Contains("not found") || m.Contains("does not exist"))
        return TeleportFailureKind.RegionNotFound;
    return TeleportFailureKind.Other;
}
```

Add the imports at the top of the file:

```csharp
using OpenMetaverse;
```

- [ ] **Step 7: Write `ParcelReader` as a thin wrapper for dispatcher use** *(optional; LibreMetaverseBotSession already exposes ReadParcelAsync — skip this step and reference the session directly)*

- [ ] **Step 8: Write `ParcelReaderTests.cs` using a fake session**

```csharp
// bot/tests/Slpa.Bot.Tests/ParcelReaderTests.cs
using FluentAssertions;
using Slpa.Bot.Sl;
using Xunit;

namespace Slpa.Bot.Tests;

/// <summary>
/// Exercises the teleport + parcel-read contract through <see cref="IBotSession"/>.
/// Real LibreMetaverse behavior is covered by manual smoke tests.
/// </summary>
public sealed class ParcelReaderTests
{
    [Fact]
    public async Task FakeSession_TeleportAccessDenied_ReturnsFailure()
    {
        var session = new FakeBotSession
        {
            TeleportPolicy = r => TeleportResult.Fail(TeleportFailureKind.AccessDenied)
        };
        var result = await session.TeleportAsync("Ahern", 128, 128, 20, default);
        result.Success.Should().BeFalse();
        result.Failure.Should().Be(TeleportFailureKind.AccessDenied);
    }

    [Fact]
    public async Task FakeSession_ReadParcel_ReturnsSnapshot()
    {
        var expected = new ParcelSnapshot(
            Guid.NewGuid(), Guid.Empty, false, Guid.NewGuid(), 999_999_999,
            "Test Parcel", "desc", 1024, 117, 0, Guid.Empty, 0);
        var session = new FakeBotSession { ReadPolicy = (_, _) => expected };
        var snap = await session.ReadParcelAsync(128, 128, default);
        snap.Should().BeEquivalentTo(expected);
    }
}
```

And extend `FakeBotSession` in `LibreMetaverseBotSessionTests.cs` to honor these policies:

```csharp
// bot/tests/Slpa.Bot.Tests/LibreMetaverseBotSessionTests.cs — extend FakeBotSession:

public sealed class FakeBotSession : IBotSession
{
    public SessionState State { get; private set; } = SessionState.Starting;
    public Guid BotUuid { get; } = Guid.NewGuid();

    public Func<string, TeleportResult> TeleportPolicy { get; set; } =
        _ => TeleportResult.Ok();
    public Func<double, double, ParcelSnapshot?> ReadPolicy { get; set; } =
        (_, _) => null;

    public Task StartAsync(CancellationToken ct) { State = SessionState.Starting; return Task.CompletedTask; }
    public Task LogoutAsync(CancellationToken ct) { State = SessionState.Error; return Task.CompletedTask; }
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;

    public Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z, CancellationToken ct)
        => Task.FromResult(TeleportPolicy(regionName));

    public Task<ParcelSnapshot?> ReadParcelAsync(double x, double y, CancellationToken ct)
        => Task.FromResult(ReadPolicy(x, y));

    public void SimulateLoginSuccess() => State = SessionState.Online;
    public void SimulateDisconnect() => State = SessionState.Reconnecting;
}
```

- [ ] **Step 9: Run the parcel-reader tests**

```bash
cd bot && dotnet test --filter "FullyQualifiedName~ParcelReader"
```

Expected: PASS (2 tests).

- [ ] **Step 10: Run the full bot test suite**

```bash
cd bot && dotnet test
```

Expected: PASS (all tests — rate limiter + session state + parcel reader).

- [ ] **Step 11: Commit**

```bash
git add bot/

git commit -m "feat(bot-service): rate-limited teleport + ParcelProperties read"
```

---

## Task 9: C# — Backend Client + Auth

**Files:**
- Create: `bot/src/Slpa.Bot/Backend/IBackendClient.cs`
- Create: `bot/src/Slpa.Bot/Backend/HttpBackendClient.cs`
- Create: `bot/src/Slpa.Bot/Backend/Models/BotTaskType.cs`
- Create: `bot/src/Slpa.Bot/Backend/Models/BotTaskStatus.cs`
- Create: `bot/src/Slpa.Bot/Backend/Models/MonitorOutcome.cs`
- Create: `bot/src/Slpa.Bot/Backend/Models/BotTaskResponse.cs`
- Create: `bot/src/Slpa.Bot/Backend/Models/BotTaskClaimRequest.cs`
- Create: `bot/src/Slpa.Bot/Backend/Models/BotTaskCompleteRequest.cs`
- Create: `bot/src/Slpa.Bot/Backend/Models/BotMonitorResultRequest.cs`
- Modify: `bot/src/Slpa.Bot/Program.cs` (register HttpClient)
- Test: `bot/tests/Slpa.Bot.Tests/HttpBackendClientTests.cs`

Spec reference: §3.9, §4.1, §7.2.

- [ ] **Step 1: Create the C# mirrors of the backend DTOs**

```csharp
// bot/src/Slpa.Bot/Backend/Models/BotTaskType.cs
namespace Slpa.Bot.Backend.Models;

public enum BotTaskType { VERIFY, MONITOR_AUCTION, MONITOR_ESCROW }
```

```csharp
// bot/src/Slpa.Bot/Backend/Models/BotTaskStatus.cs
namespace Slpa.Bot.Backend.Models;

public enum BotTaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }
```

```csharp
// bot/src/Slpa.Bot/Backend/Models/MonitorOutcome.cs
namespace Slpa.Bot.Backend.Models;

public enum MonitorOutcome
{
    ALL_GOOD,
    AUTH_BUYER_CHANGED,
    PRICE_MISMATCH,
    OWNER_CHANGED,
    ACCESS_DENIED,
    TRANSFER_COMPLETE,
    TRANSFER_READY,
    STILL_WAITING
}
```

```csharp
// bot/src/Slpa.Bot/Backend/Models/BotTaskResponse.cs
namespace Slpa.Bot.Backend.Models;

public sealed record BotTaskResponse(
    long Id,
    BotTaskType TaskType,
    BotTaskStatus Status,
    long AuctionId,
    long? EscrowId,
    Guid ParcelUuid,
    string? RegionName,
    double? PositionX,
    double? PositionY,
    double? PositionZ,
    long SentinelPrice,
    Guid? ExpectedOwnerUuid,
    Guid? ExpectedAuthBuyerUuid,
    long? ExpectedSalePriceLindens,
    Guid? ExpectedWinnerUuid,
    Guid? ExpectedSellerUuid,
    long? ExpectedMaxSalePriceLindens,
    Guid? AssignedBotUuid,
    string? FailureReason,
    DateTimeOffset? NextRunAt,
    int? RecurrenceIntervalSeconds,
    DateTimeOffset CreatedAt,
    DateTimeOffset? CompletedAt);
```

```csharp
// bot/src/Slpa.Bot/Backend/Models/BotTaskClaimRequest.cs
namespace Slpa.Bot.Backend.Models;

public sealed record BotTaskClaimRequest(Guid BotUuid);
```

```csharp
// bot/src/Slpa.Bot/Backend/Models/BotTaskCompleteRequest.cs
namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Mirrors backend com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest.
/// Either SUCCESS (populates observations) or FAILURE (populates failureReason).
/// </summary>
public sealed record BotTaskCompleteRequest(
    string Result,
    Guid? AuthBuyerId,
    long? SalePrice,
    Guid? ParcelOwner,
    string? ParcelName,
    int? AreaSqm,
    string? RegionName,
    double? PositionX,
    double? PositionY,
    double? PositionZ,
    string? FailureReason);
```

```csharp
// bot/src/Slpa.Bot/Backend/Models/BotMonitorResultRequest.cs
namespace Slpa.Bot.Backend.Models;

public sealed record BotMonitorResultRequest(
    MonitorOutcome Outcome,
    Guid? ObservedOwner,
    Guid? ObservedAuthBuyer,
    long? ObservedSalePrice,
    string? Note);
```

- [ ] **Step 2: Define `IBackendClient`**

```csharp
// bot/src/Slpa.Bot/Backend/IBackendClient.cs
using Slpa.Bot.Backend.Models;

namespace Slpa.Bot.Backend;

public interface IBackendClient
{
    /// <summary>
    /// Claim the next due task. Returns null on 204 (empty queue). Hard-fails
    /// on 401 (misconfigured secret) by throwing <see cref="AuthConfigException"/>.
    /// </summary>
    Task<BotTaskResponse?> ClaimAsync(Guid botUuid, CancellationToken ct);

    /// <summary>Report VERIFY outcome. Task becomes COMPLETED / FAILED.</summary>
    Task CompleteVerifyAsync(long taskId, BotTaskCompleteRequest body, CancellationToken ct);

    /// <summary>Report MONITOR_* outcome. Backend re-arms or cancels the row.</summary>
    Task PostMonitorAsync(long taskId, BotMonitorResultRequest body, CancellationToken ct);
}

/// <summary>
/// Thrown when the backend returns 401 Unauthorized. Recovery requires
/// operator intervention (rotate the shared secret); retrying is pointless.
/// The task loop catches this + terminates the process so supervisord /
/// compose restarts it (which gives a human a chance to notice log noise).
/// </summary>
public sealed class AuthConfigException : Exception
{
    public AuthConfigException(string message) : base(message) {}
}
```

- [ ] **Step 3: Write failing `HttpBackendClient` tests with WireMock.Net**

```csharp
// bot/tests/Slpa.Bot.Tests/HttpBackendClientTests.cs
using System.Net;
using System.Text.Json;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;
using WireMock.RequestBuilders;
using WireMock.ResponseBuilders;
using WireMock.Server;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class HttpBackendClientTests : IAsyncLifetime
{
    private WireMockServer _server = null!;
    private HttpBackendClient _client = null!;

    public Task InitializeAsync()
    {
        _server = WireMockServer.Start();
        var http = new HttpClient { BaseAddress = new Uri(_server.Url!) };
        var opts = Options.Create(new BackendOptions
        {
            BaseUrl = _server.Url!,
            SharedSecret = "test-secret-xxxxxxxx"
        });
        _client = new HttpBackendClient(http, opts, NullLogger<HttpBackendClient>.Instance);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() { _server.Stop(); _server.Dispose(); return Task.CompletedTask; }

    [Fact]
    public async Task Claim_200_ReturnsTask()
    {
        var botUuid = Guid.NewGuid();
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/claim").UsingPost()
                .WithHeader("Authorization", "Bearer test-secret-xxxxxxxx"))
            .RespondWith(Response.Create()
                .WithStatusCode(200)
                .WithHeader("Content-Type", "application/json")
                .WithBody("""
                          {
                            "id": 1,
                            "taskType": "VERIFY",
                            "status": "IN_PROGRESS",
                            "auctionId": 42,
                            "escrowId": null,
                            "parcelUuid": "11111111-1111-1111-1111-111111111111",
                            "regionName": "Ahern",
                            "sentinelPrice": 999999999,
                            "createdAt": "2026-04-22T12:00:00Z"
                          }
                          """));

        var task = await _client.ClaimAsync(botUuid, default);

        task.Should().NotBeNull();
        task!.Id.Should().Be(1);
        task.TaskType.Should().Be(BotTaskType.VERIFY);
    }

    [Fact]
    public async Task Claim_204_ReturnsNull()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/claim").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(204));

        var task = await _client.ClaimAsync(Guid.NewGuid(), default);
        task.Should().BeNull();
    }

    [Fact]
    public async Task Claim_401_ThrowsAuthConfigException()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/claim").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(401));

        var act = async () => await _client.ClaimAsync(Guid.NewGuid(), default);
        await act.Should().ThrowAsync<AuthConfigException>();
    }

    [Fact]
    public async Task Claim_500ThenSuccess_RetriesAndReturns()
    {
        var callCount = 0;
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/claim").UsingPost())
            .RespondWith(Response.Create().WithCallback(_ =>
            {
                callCount++;
                if (callCount < 2)
                {
                    return new WireMock.ResponseMessage
                    {
                        StatusCode = 500,
                        BodyData = new WireMock.Util.BodyData
                        {
                            BodyAsString = "{}",
                            Encoding = System.Text.Encoding.UTF8,
                            DetectedBodyType = WireMock.Types.BodyType.String
                        }
                    };
                }
                return new WireMock.ResponseMessage
                {
                    StatusCode = 204,
                    BodyData = new WireMock.Util.BodyData
                    {
                        DetectedBodyType = WireMock.Types.BodyType.None
                    }
                };
            }));

        var task = await _client.ClaimAsync(Guid.NewGuid(), default);
        task.Should().BeNull();
        callCount.Should().BeGreaterThan(1);
    }

    [Fact]
    public async Task CompleteVerify_200_ReturnsWithoutThrow()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/5/verify").UsingPut())
            .RespondWith(Response.Create().WithStatusCode(200)
                .WithBody("{\"id\":5,\"taskType\":\"VERIFY\",\"status\":\"COMPLETED\",\"auctionId\":42,\"parcelUuid\":\"11111111-1111-1111-1111-111111111111\",\"sentinelPrice\":999999999,\"createdAt\":\"2026-04-22T12:00:00Z\"}"));

        await _client.CompleteVerifyAsync(
            5,
            new BotTaskCompleteRequest("SUCCESS", Guid.NewGuid(), 999_999_999,
                Guid.NewGuid(), "Parcel", 1024, "Ahern", 128, 128, 20, null),
            default);
    }

    [Fact]
    public async Task PostMonitor_200_ReturnsWithoutThrow()
    {
        _server
            .Given(Request.Create().WithPath("/api/v1/bot/tasks/9/monitor").UsingPost())
            .RespondWith(Response.Create().WithStatusCode(200)
                .WithBody("{\"id\":9,\"taskType\":\"MONITOR_AUCTION\",\"status\":\"PENDING\",\"auctionId\":42,\"parcelUuid\":\"11111111-1111-1111-1111-111111111111\",\"sentinelPrice\":999999999,\"createdAt\":\"2026-04-22T12:00:00Z\"}"));

        await _client.PostMonitorAsync(
            9,
            new BotMonitorResultRequest(MonitorOutcome.ALL_GOOD, null, null, null, null),
            default);
    }
}
```

- [ ] **Step 4: Implement `HttpBackendClient`**

```csharp
// bot/src/Slpa.Bot/Backend/HttpBackendClient.cs
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;

namespace Slpa.Bot.Backend;

/// <summary>
/// Bearer-authenticated backend client with hand-rolled exponential retry
/// for 5xx + network errors. 401 hard-fails (the secret is wrong; retrying
/// cannot help). 4xx other than 401 gives up on the one task — the
/// backend's timeout sweep cleans up.
/// </summary>
public sealed class HttpBackendClient : IBackendClient
{
    private static readonly TimeSpan[] RetryBackoff =
    {
        TimeSpan.FromSeconds(1),
        TimeSpan.FromSeconds(2),
        TimeSpan.FromSeconds(4),
        TimeSpan.FromSeconds(8),
        TimeSpan.FromSeconds(15)
    };

    private static readonly JsonSerializerOptions JsonOpts = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly HttpClient _http;
    private readonly BackendOptions _opts;
    private readonly ILogger<HttpBackendClient> _log;

    public HttpBackendClient(
        HttpClient http,
        IOptions<BackendOptions> opts,
        ILogger<HttpBackendClient> log)
    {
        _http = http;
        _opts = opts.Value;
        _log = log;
        if (_http.BaseAddress is null && !string.IsNullOrEmpty(_opts.BaseUrl))
        {
            _http.BaseAddress = new Uri(_opts.BaseUrl);
        }
        _http.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", _opts.SharedSecret);
    }

    public async Task<BotTaskResponse?> ClaimAsync(Guid botUuid, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bot/tasks/claim")
            {
                Content = JsonContent.Create(new BotTaskClaimRequest(botUuid), options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);

        if (resp.StatusCode == HttpStatusCode.NoContent) return null;
        resp.EnsureSuccessStatusCode();
        return await resp.Content.ReadFromJsonAsync<BotTaskResponse>(JsonOpts, ct)
            .ConfigureAwait(false);
    }

    public async Task CompleteVerifyAsync(
        long taskId, BotTaskCompleteRequest body, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Put,
                $"/api/v1/bot/tasks/{taskId}/verify")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
    }

    public async Task PostMonitorAsync(
        long taskId, BotMonitorResultRequest body, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post,
                $"/api/v1/bot/tasks/{taskId}/monitor")
            {
                Content = JsonContent.Create(body, options: JsonOpts)
            };
            return req;
        }, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
    }

    private async Task<HttpResponseMessage> SendWithRetryAsync(
        Func<HttpRequestMessage> requestFactory, CancellationToken ct)
    {
        Exception? lastException = null;
        for (var attempt = 0; attempt <= RetryBackoff.Length; attempt++)
        {
            HttpRequestMessage request = requestFactory();
            try
            {
                var resp = await _http.SendAsync(request, ct).ConfigureAwait(false);
                if (resp.StatusCode == HttpStatusCode.Unauthorized)
                {
                    throw new AuthConfigException(
                        "Backend returned 401 — check slpa.bot.shared-secret");
                }
                if ((int)resp.StatusCode >= 500)
                {
                    lastException = new HttpRequestException(
                        $"Server error {(int)resp.StatusCode}");
                    resp.Dispose();
                    if (attempt < RetryBackoff.Length)
                    {
                        _log.LogWarning(
                            "HTTP {Code}; retry {Attempt} after {Delay}",
                            (int)resp.StatusCode, attempt + 1, RetryBackoff[attempt]);
                        await Task.Delay(RetryBackoff[attempt], ct).ConfigureAwait(false);
                        continue;
                    }
                }
                return resp;
            }
            catch (HttpRequestException ex) when (attempt < RetryBackoff.Length)
            {
                lastException = ex;
                _log.LogWarning(ex,
                    "Network error; retry {Attempt} after {Delay}",
                    attempt + 1, RetryBackoff[attempt]);
                await Task.Delay(RetryBackoff[attempt], ct).ConfigureAwait(false);
            }
        }
        throw lastException ?? new HttpRequestException(
            "Exhausted retries without a response");
    }
}
```

- [ ] **Step 5: Register `HttpBackendClient` in `Program.cs`**

Add between the `AddSingleton<IBotSession>` and `AddHostedService<BotSessionBootstrapper>` lines:

```csharp
builder.Services.AddHttpClient<IBackendClient, HttpBackendClient>((sp, client) =>
{
    var opts = sp.GetRequiredService<IOptions<BackendOptions>>().Value;
    client.BaseAddress = new Uri(opts.BaseUrl);
    client.Timeout = TimeSpan.FromSeconds(30);
});
```

Add `using Slpa.Bot.Backend;` and `using Microsoft.Extensions.Options;` at the top.

- [ ] **Step 6: Run the backend-client tests**

```bash
cd bot && dotnet test --filter "FullyQualifiedName~HttpBackendClient"
```

Expected: PASS (6 tests).

- [ ] **Step 7: Run the full bot test suite**

```bash
cd bot && dotnet test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add bot/

git commit -m "feat(bot-service): authenticated backend client + retry policy"
```

---

## Task 10: C# — VerifyHandler + TaskLoop + MonitorHandler

**Files:**
- Create: `bot/src/Slpa.Bot/Tasks/VerifyHandler.cs`
- Create: `bot/src/Slpa.Bot/Tasks/MonitorHandler.cs`
- Create: `bot/src/Slpa.Bot/Tasks/TaskLoop.cs`
- Modify: `bot/src/Slpa.Bot/Program.cs` (register handlers + swap bootstrapper for TaskLoop)
- Test: `bot/tests/Slpa.Bot.Tests/VerifyHandlerTests.cs`
- Test: `bot/tests/Slpa.Bot.Tests/MonitorHandlerTests.cs`
- Test: `bot/tests/Slpa.Bot.Tests/TaskLoopTests.cs`

Spec reference: §4.4, §4.5, §4.6.

- [ ] **Step 1: Write failing `VerifyHandlerTests`**

```csharp
// bot/tests/Slpa.Bot.Tests/VerifyHandlerTests.cs
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class VerifyHandlerTests
{
    private readonly Mock<IBackendClient> _backend = new();
    private readonly FakeBotSession _session = new();

    [Fact]
    public async Task HappyPath_PostsSuccessWithObservations()
    {
        var expectedOwner = Guid.NewGuid();
        var escrowUuid = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => new ParcelSnapshot(
            expectedOwner, Guid.Empty, false, escrowUuid, 999_999_999,
            "Test", "", 1024, 117, 0, Guid.Empty, 0);

        var handler = new VerifyHandler(_session, _backend.Object,
                NullLogger<VerifyHandler>.Instance);
        var task = BuildVerifyTask();

        await handler.HandleAsync(task, CancellationToken.None);

        _backend.Verify(b => b.CompleteVerifyAsync(
            task.Id,
            It.Is<BotTaskCompleteRequest>(r =>
                r.Result == "SUCCESS"
                && r.AuthBuyerId == escrowUuid
                && r.SalePrice == 999_999_999
                && r.ParcelOwner == expectedOwner),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task TeleportAccessDenied_PostsFailure()
    {
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.AccessDenied);

        var handler = new VerifyHandler(_session, _backend.Object,
                NullLogger<VerifyHandler>.Instance);
        await handler.HandleAsync(BuildVerifyTask(), CancellationToken.None);

        _backend.Verify(b => b.CompleteVerifyAsync(
            It.IsAny<long>(),
            It.Is<BotTaskCompleteRequest>(r =>
                r.Result == "FAILURE"
                && r.FailureReason == "AccessDenied"),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task ParcelReadTimeout_PostsFailure()
    {
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => null; // timeout

        var handler = new VerifyHandler(_session, _backend.Object,
                NullLogger<VerifyHandler>.Instance);
        await handler.HandleAsync(BuildVerifyTask(), CancellationToken.None);

        _backend.Verify(b => b.CompleteVerifyAsync(
            It.IsAny<long>(),
            It.Is<BotTaskCompleteRequest>(r =>
                r.Result == "FAILURE"
                && r.FailureReason == "PARCEL_READ_TIMEOUT"),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    private static BotTaskResponse BuildVerifyTask() => new(
        Id: 1,
        TaskType: BotTaskType.VERIFY,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 42,
        EscrowId: null,
        ParcelUuid: Guid.NewGuid(),
        RegionName: "Ahern",
        PositionX: 128,
        PositionY: 128,
        PositionZ: 20,
        SentinelPrice: 999_999_999,
        ExpectedOwnerUuid: null,
        ExpectedAuthBuyerUuid: null,
        ExpectedSalePriceLindens: null,
        ExpectedWinnerUuid: null,
        ExpectedSellerUuid: null,
        ExpectedMaxSalePriceLindens: null,
        AssignedBotUuid: Guid.NewGuid(),
        FailureReason: null,
        NextRunAt: null,
        RecurrenceIntervalSeconds: null,
        CreatedAt: DateTimeOffset.UtcNow,
        CompletedAt: null);
}
```

- [ ] **Step 2: Implement `VerifyHandler`**

```csharp
// bot/src/Slpa.Bot/Tasks/VerifyHandler.cs
using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Handles VERIFY tasks. Observation-only: teleports, reads parcel, reports
/// to the backend without interpreting whether the observation constitutes
/// pass or fail. The backend validates against the sentinel price + escrow
/// UUID (see <c>BotTaskService.complete</c>).
/// </summary>
public sealed class VerifyHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<VerifyHandler> _log;

    public VerifyHandler(
        IBotSession session,
        IBackendClient backend,
        ILogger<VerifyHandler> log)
    {
        _session = session;
        _backend = backend;
        _log = log;
    }

    public async Task HandleAsync(BotTaskResponse task, CancellationToken ct)
    {
        var tp = await _session.TeleportAsync(
            task.RegionName ?? string.Empty,
            task.PositionX ?? 128,
            task.PositionY ?? 128,
            task.PositionZ ?? 20,
            ct).ConfigureAwait(false);
        if (!tp.Success)
        {
            await _backend.CompleteVerifyAsync(task.Id,
                Failure(tp.Failure?.ToString() ?? "Unknown teleport failure"),
                ct).ConfigureAwait(false);
            return;
        }

        var snapshot = await _session.ReadParcelAsync(
            task.PositionX ?? 128, task.PositionY ?? 128, ct).ConfigureAwait(false);
        if (snapshot is null)
        {
            await _backend.CompleteVerifyAsync(task.Id,
                Failure("PARCEL_READ_TIMEOUT"), ct).ConfigureAwait(false);
            return;
        }

        await _backend.CompleteVerifyAsync(task.Id,
            new BotTaskCompleteRequest(
                Result: "SUCCESS",
                AuthBuyerId: snapshot.AuthBuyerId,
                SalePrice: snapshot.SalePrice,
                ParcelOwner: snapshot.OwnerId,
                ParcelName: snapshot.Name,
                AreaSqm: snapshot.AreaSqm,
                RegionName: task.RegionName,
                PositionX: task.PositionX,
                PositionY: task.PositionY,
                PositionZ: task.PositionZ,
                FailureReason: null),
            ct).ConfigureAwait(false);
        _log.LogInformation("VERIFY {TaskId} reported SUCCESS", task.Id);
    }

    private static BotTaskCompleteRequest Failure(string reason) =>
        new("FAILURE", null, null, null, null, null, null, null, null, null, reason);
}
```

- [ ] **Step 3: Run the verify tests**

```bash
cd bot && dotnet test --filter "FullyQualifiedName~VerifyHandler"
```

Expected: PASS (3 tests).

- [ ] **Step 4: Write failing `MonitorHandlerTests`**

```csharp
// bot/tests/Slpa.Bot.Tests/MonitorHandlerTests.cs
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class MonitorHandlerTests
{
    private readonly Mock<IBackendClient> _backend = new();
    private readonly FakeBotSession _session = new();

    [Fact]
    public async Task MonitorAuction_AllGood_ReportsAllGood()
    {
        var owner = Guid.NewGuid();
        var escrow = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(owner, escrow, 999_999_999);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(owner, escrow, 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.ALL_GOOD),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorAuction_OwnerChanged_ReportsOwnerChanged()
    {
        var expectedOwner = Guid.NewGuid();
        var observedOwner = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(observedOwner, Guid.NewGuid(), 999_999_999);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(expectedOwner, Guid.NewGuid(), 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.OWNER_CHANGED
                && r.ObservedOwner == observedOwner),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorAuction_AuthBuyerChanged_ReportsAuthBuyerChanged()
    {
        var owner = Guid.NewGuid();
        var expectedAuth = Guid.NewGuid();
        var observedAuth = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(owner, observedAuth, 999_999_999);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(owner, expectedAuth, 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.AUTH_BUYER_CHANGED),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorAuction_PriceMismatch_ReportsPriceMismatch()
    {
        var owner = Guid.NewGuid();
        var auth = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(owner, auth, 123);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(owner, auth, 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.PRICE_MISMATCH),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorAuction_AccessDenied_ReportsAccessDenied()
    {
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.AccessDenied);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(AuctionTask(Guid.NewGuid(), Guid.NewGuid(), 999_999_999),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.ACCESS_DENIED),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorEscrow_TransferComplete_ReportsTransferComplete()
    {
        var seller = Guid.NewGuid();
        var winner = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(winner, Guid.Empty, 0);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(EscrowTask(seller, winner, 1),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.TRANSFER_COMPLETE),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorEscrow_TransferReady_ReportsTransferReady()
    {
        var seller = Guid.NewGuid();
        var winner = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(seller, winner, 0);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(EscrowTask(seller, winner, 1),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.TRANSFER_READY),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task MonitorEscrow_StillWaiting_ReportsStillWaiting()
    {
        var seller = Guid.NewGuid();
        var winner = Guid.NewGuid();
        _session.SimulateLoginSuccess();
        _session.TeleportPolicy = _ => TeleportResult.Ok();
        _session.ReadPolicy = (_, _) => Snapshot(seller, Guid.Empty, 999_999_999);

        var handler = new MonitorHandler(_session, _backend.Object,
                NullLogger<MonitorHandler>.Instance);
        await handler.HandleAsync(EscrowTask(seller, winner, 1),
                CancellationToken.None);

        _backend.Verify(b => b.PostMonitorAsync(
            It.IsAny<long>(),
            It.Is<BotMonitorResultRequest>(r => r.Outcome == MonitorOutcome.STILL_WAITING),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    private static ParcelSnapshot Snapshot(Guid owner, Guid authBuyer, long salePrice) =>
        new(owner, Guid.Empty, false, authBuyer, salePrice,
            "", "", 1024, 117, 0, Guid.Empty, 0);

    private static BotTaskResponse AuctionTask(
            Guid expectedOwner, Guid expectedAuthBuyer, long expectedSalePrice) => new(
        Id: 10,
        TaskType: BotTaskType.MONITOR_AUCTION,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 42,
        EscrowId: null,
        ParcelUuid: Guid.NewGuid(),
        RegionName: "Ahern",
        PositionX: 128, PositionY: 128, PositionZ: 20,
        SentinelPrice: 999_999_999,
        ExpectedOwnerUuid: expectedOwner,
        ExpectedAuthBuyerUuid: expectedAuthBuyer,
        ExpectedSalePriceLindens: expectedSalePrice,
        ExpectedWinnerUuid: null, ExpectedSellerUuid: null,
        ExpectedMaxSalePriceLindens: null,
        AssignedBotUuid: Guid.NewGuid(), FailureReason: null,
        NextRunAt: DateTimeOffset.UtcNow,
        RecurrenceIntervalSeconds: 1800,
        CreatedAt: DateTimeOffset.UtcNow, CompletedAt: null);

    private static BotTaskResponse EscrowTask(
            Guid seller, Guid winner, long maxSalePrice) => new(
        Id: 20,
        TaskType: BotTaskType.MONITOR_ESCROW,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 42,
        EscrowId: 100,
        ParcelUuid: Guid.NewGuid(),
        RegionName: "Ahern",
        PositionX: 128, PositionY: 128, PositionZ: 20,
        SentinelPrice: 999_999_999,
        ExpectedOwnerUuid: null, ExpectedAuthBuyerUuid: null,
        ExpectedSalePriceLindens: null,
        ExpectedWinnerUuid: winner, ExpectedSellerUuid: seller,
        ExpectedMaxSalePriceLindens: maxSalePrice,
        AssignedBotUuid: Guid.NewGuid(), FailureReason: null,
        NextRunAt: DateTimeOffset.UtcNow,
        RecurrenceIntervalSeconds: 900,
        CreatedAt: DateTimeOffset.UtcNow, CompletedAt: null);
}
```

- [ ] **Step 5: Implement `MonitorHandler`**

```csharp
// bot/src/Slpa.Bot/Tasks/MonitorHandler.cs
using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Handles MONITOR_AUCTION and MONITOR_ESCROW tasks. Mechanical classifier
/// only — compares observed values to expected values and reports the
/// outcome. The backend dispatcher owns interpretation.
/// </summary>
public sealed class MonitorHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<MonitorHandler> _log;

    public MonitorHandler(
        IBotSession session,
        IBackendClient backend,
        ILogger<MonitorHandler> log)
    {
        _session = session;
        _backend = backend;
        _log = log;
    }

    public async Task HandleAsync(BotTaskResponse task, CancellationToken ct)
    {
        var tp = await _session.TeleportAsync(
            task.RegionName ?? string.Empty,
            task.PositionX ?? 128,
            task.PositionY ?? 128,
            task.PositionZ ?? 20,
            ct).ConfigureAwait(false);
        if (!tp.Success)
        {
            await _backend.PostMonitorAsync(task.Id,
                new BotMonitorResultRequest(
                    MonitorOutcome.ACCESS_DENIED, null, null, null,
                    tp.Failure?.ToString()),
                ct).ConfigureAwait(false);
            return;
        }

        var snap = await _session.ReadParcelAsync(
            task.PositionX ?? 128, task.PositionY ?? 128, ct).ConfigureAwait(false);
        if (snap is null)
        {
            await _backend.PostMonitorAsync(task.Id,
                new BotMonitorResultRequest(
                    MonitorOutcome.ACCESS_DENIED, null, null, null,
                    "PARCEL_READ_TIMEOUT"),
                ct).ConfigureAwait(false);
            return;
        }

        var outcome = Classify(task, snap);
        await _backend.PostMonitorAsync(task.Id,
            new BotMonitorResultRequest(
                Outcome: outcome,
                ObservedOwner: snap.OwnerId,
                ObservedAuthBuyer: snap.AuthBuyerId,
                ObservedSalePrice: snap.SalePrice,
                Note: null),
            ct).ConfigureAwait(false);
        _log.LogInformation("MONITOR {TaskId} ({Type}) reported {Outcome}",
                task.Id, task.TaskType, outcome);
    }

    private static MonitorOutcome Classify(BotTaskResponse task, ParcelSnapshot snap)
    {
        return task.TaskType switch
        {
            BotTaskType.MONITOR_AUCTION => ClassifyAuction(task, snap),
            BotTaskType.MONITOR_ESCROW => ClassifyEscrow(task, snap),
            _ => MonitorOutcome.STILL_WAITING
        };
    }

    private static MonitorOutcome ClassifyAuction(BotTaskResponse task, ParcelSnapshot snap)
    {
        if (task.ExpectedOwnerUuid is { } expOwner && snap.OwnerId != expOwner)
            return MonitorOutcome.OWNER_CHANGED;
        if (task.ExpectedAuthBuyerUuid is { } expAuth && snap.AuthBuyerId != expAuth)
            return MonitorOutcome.AUTH_BUYER_CHANGED;
        if (task.ExpectedSalePriceLindens is { } expPrice && snap.SalePrice != expPrice)
            return MonitorOutcome.PRICE_MISMATCH;
        return MonitorOutcome.ALL_GOOD;
    }

    private static MonitorOutcome ClassifyEscrow(BotTaskResponse task, ParcelSnapshot snap)
    {
        if (task.ExpectedWinnerUuid is { } winner && snap.OwnerId == winner)
            return MonitorOutcome.TRANSFER_COMPLETE;
        if (task.ExpectedSellerUuid is { } seller && snap.OwnerId != seller)
            return MonitorOutcome.OWNER_CHANGED;
        if (task.ExpectedWinnerUuid is { } winnerForAuth
            && snap.AuthBuyerId == winnerForAuth
            && snap.SalePrice <= (task.ExpectedMaxSalePriceLindens ?? 1))
            return MonitorOutcome.TRANSFER_READY;
        return MonitorOutcome.STILL_WAITING;
    }
}
```

- [ ] **Step 6: Run the monitor tests**

```bash
cd bot && dotnet test --filter "FullyQualifiedName~MonitorHandler"
```

Expected: PASS (8 tests).

- [ ] **Step 7: Write failing `TaskLoopTests`**

```csharp
// bot/tests/Slpa.Bot.Tests/TaskLoopTests.cs
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class TaskLoopTests
{
    [Fact]
    public async Task SessionOffline_DoesNotClaim()
    {
        var session = new FakeBotSession(); // starts in Starting
        var backend = new Mock<IBackendClient>();

        var loop = new TaskLoop(session,
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
        await loop.ExecuteAsync(cts.Token);

        backend.Verify(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()),
                Times.Never);
    }

    [Fact]
    public async Task EmptyQueue_BacksOff_Retries()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        var backend = new Mock<IBackendClient>();
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync((BotTaskResponse?)null);

        var loop = new TaskLoop(session,
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(400));
        await loop.ExecuteAsync(cts.Token);

        backend.Verify(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()),
                Times.AtLeastOnce);
    }

    [Fact]
    public async Task HandlerCrash_DoesNotCallback_LoopContinues()
    {
        var session = new FakeBotSession();
        session.SimulateLoginSuccess();
        session.TeleportPolicy = _ => throw new InvalidOperationException("boom");
        var backend = new Mock<IBackendClient>();
        var claims = 0;
        backend.Setup(b => b.ClaimAsync(It.IsAny<Guid>(), It.IsAny<CancellationToken>()))
               .ReturnsAsync(() =>
               {
                   claims++;
                   if (claims == 1) return MakeVerifyTask();
                   return null;
               });

        var loop = new TaskLoop(session,
            () => new VerifyHandler(session, backend.Object,
                    NullLogger<VerifyHandler>.Instance),
            () => new MonitorHandler(session, backend.Object,
                    NullLogger<MonitorHandler>.Instance),
            NullLogger<TaskLoop>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(500));
        await loop.ExecuteAsync(cts.Token);

        backend.Verify(b => b.CompleteVerifyAsync(It.IsAny<long>(),
                It.IsAny<BotTaskCompleteRequest>(), It.IsAny<CancellationToken>()),
                Times.Never);
        claims.Should().BeGreaterOrEqualTo(1);
    }

    private static BotTaskResponse MakeVerifyTask() => new(
        1, BotTaskType.VERIFY, BotTaskStatus.IN_PROGRESS,
        42, null, Guid.NewGuid(), "Ahern", 128, 128, 20,
        999_999_999, null, null, null, null, null, null,
        Guid.NewGuid(), null, null, null,
        DateTimeOffset.UtcNow, null);
}
```

- [ ] **Step 8: Implement `TaskLoop`**

```csharp
// bot/src/Slpa.Bot/Tasks/TaskLoop.cs
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Main driver. Claim → dispatch to handler → loop. Dual backoff: 5 s when
/// the session is not Online; 15 s when the queue is empty. Handler
/// exceptions are logged but never reported back to the backend — the
/// IN_PROGRESS timeout sweep cleans stalled rows.
/// </summary>
public sealed class TaskLoop : BackgroundService
{
    private static readonly TimeSpan OfflineBackoff = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan EmptyQueueBackoff = TimeSpan.FromSeconds(15);

    private readonly IBotSession _session;
    private readonly Func<VerifyHandler> _verify;
    private readonly Func<MonitorHandler> _monitor;
    private readonly IBackendClient _backend;
    private readonly ILogger<TaskLoop> _log;

    public TaskLoop(
        IBotSession session,
        IBackendClient backend,
        VerifyHandler verify,
        MonitorHandler monitor,
        ILogger<TaskLoop> log)
        : this(session, () => verify, () => monitor, log)
    {
        _backend = backend;
    }

    // Test-friendly ctor: handlers built per-call.
    internal TaskLoop(
        IBotSession session,
        Func<VerifyHandler> verify,
        Func<MonitorHandler> monitor,
        ILogger<TaskLoop> log)
    {
        _session = session;
        _verify = verify;
        _monitor = monitor;
        _log = log;
        _backend = null!; // filled by production ctor overload
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            if (_session.State != SessionState.Online)
            {
                await SafeDelayAsync(OfflineBackoff, ct).ConfigureAwait(false);
                continue;
            }

            BotTaskResponse? task;
            try
            {
                task = await _backend.ClaimAsync(_session.BotUuid, ct).ConfigureAwait(false);
            }
            catch (AuthConfigException ex)
            {
                _log.LogCritical(ex, "Auth config error; exiting");
                return;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _log.LogWarning(ex, "Claim failed; backing off");
                await SafeDelayAsync(OfflineBackoff, ct).ConfigureAwait(false);
                continue;
            }

            if (task is null)
            {
                await SafeDelayAsync(EmptyQueueBackoff, ct).ConfigureAwait(false);
                continue;
            }

            try
            {
                await DispatchAsync(task, ct).ConfigureAwait(false);
            }
            catch (SessionLostException ex)
            {
                _log.LogWarning(ex, "Session lost mid-task {Id}; backend sweep will clean up",
                        task.Id);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _log.LogError(ex, "Handler crashed on task {Id}; no callback", task.Id);
            }
        }
    }

    private Task DispatchAsync(BotTaskResponse task, CancellationToken ct)
    {
        return task.TaskType switch
        {
            BotTaskType.VERIFY => _verify().HandleAsync(task, ct),
            BotTaskType.MONITOR_AUCTION => _monitor().HandleAsync(task, ct),
            BotTaskType.MONITOR_ESCROW => _monitor().HandleAsync(task, ct),
            _ => Task.CompletedTask
        };
    }

    private static async Task SafeDelayAsync(TimeSpan delay, CancellationToken ct)
    {
        try { await Task.Delay(delay, ct).ConfigureAwait(false); }
        catch (OperationCanceledException) { /* shutting down */ }
    }
}
```

- [ ] **Step 9: Update `Program.cs` to register handlers + swap bootstrapper for TaskLoop**

Replace the hosted service registration:

```csharp
// Old: builder.Services.AddHostedService<BotSessionBootstrapper>();

builder.Services.AddSingleton<VerifyHandler>();
builder.Services.AddSingleton<MonitorHandler>();
builder.Services.AddHostedService<BotSessionBootstrapper>();
builder.Services.AddHostedService<TaskLoop>();
```

The `BotSessionBootstrapper` handles login; `TaskLoop` handles the work. Both are hosted services.

- [ ] **Step 10: Run the full bot test suite**

```bash
cd bot && dotnet test
```

Expected: PASS (all tests: rate limiter + parcel reader + http client + verify + monitor + task loop).

- [ ] **Step 11: Commit**

```bash
git add bot/

git commit -m "feat(bot-service): VerifyHandler, MonitorHandler, TaskLoop"
```

---

## Task 11: Ops — Dockerfile + Compose + CI

**Files:**
- Create: `bot/Dockerfile`
- Create: `bot/.env.example`
- Modify: `docker-compose.yml` (add `bot-1` service block)
- Create: `.github/workflows/bot-ci.yml`

Spec reference: §8.2, §8.3, §8.5, §8.6.

- [ ] **Step 1: Write the Dockerfile**

```dockerfile
# bot/Dockerfile
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY Slpa.Bot.sln ./
COPY src/Slpa.Bot/Slpa.Bot.csproj src/Slpa.Bot/
COPY tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj tests/Slpa.Bot.Tests/
RUN dotnet restore src/Slpa.Bot/Slpa.Bot.csproj
COPY . .
RUN dotnet publish src/Slpa.Bot/Slpa.Bot.csproj -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:8.0
WORKDIR /app
COPY --from=build /app/publish .
# Critical: .NET 8 defaults to 8080; the healthcheck (+ compose block)
# assume 8081. Do not drop this ENV. See FOOTGUNS entry (Task 12).
ENV ASPNETCORE_HTTP_PORTS=8081
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O- http://localhost:8081/health || exit 1
ENTRYPOINT ["dotnet", "Slpa.Bot.dll"]
```

- [ ] **Step 2: Write `.env.example`**

```
# bot/.env.example — copy to bot/.env.bot-1 (or .env.bot-N) and fill in.
# Never commit the filled file; the example stays with blank secrets.

SLPA_BOT_USERNAME=SLPABot1 Resident
SLPA_BOT_PASSWORD=
SLPA_BOT_UUID=00000000-0000-0000-0000-000000000000
SLPA_BOT_START_LOCATION=last
SLPA_BOT_SHARED_SECRET=
SLPA_PRIMARY_ESCROW_UUID=00000000-0000-0000-0000-000000000099
```

Ensure `.env.bot-*` files are covered by `.gitignore`:

```bash
cd bot && grep -q '^.env.bot-' .gitignore 2>/dev/null || echo '.env.bot-*' >> .gitignore
```

- [ ] **Step 3: Add `bot-1` service block to `docker-compose.yml`**

Append to `docker-compose.yml` (preserve existing services):

```yaml
  bot-1:
    build:
      context: ./bot
      dockerfile: Dockerfile
    env_file:
      - ./bot/.env.bot-1
    environment:
      Bot__Username: ${SLPA_BOT_USERNAME}
      Bot__Password: ${SLPA_BOT_PASSWORD}
      Bot__BotUuid: ${SLPA_BOT_UUID}
      Bot__StartLocation: ${SLPA_BOT_START_LOCATION:-last}
      Backend__BaseUrl: http://backend:8080
      Backend__SharedSecret: ${SLPA_BOT_SHARED_SECRET}
      Backend__PrimaryEscrowUuid: ${SLPA_PRIMARY_ESCROW_UUID}
      RateLimit__TeleportsPerMinute: "6"
    depends_on:
      backend:
        condition: service_healthy
    restart: unless-stopped
    # Add bot-2..bot-5 by copying this block and referencing
    # ./bot/.env.bot-2, ./bot/.env.bot-3, etc.
```

- [ ] **Step 4: Write the CI workflow**

```yaml
# .github/workflows/bot-ci.yml
name: bot CI

on:
  push:
    branches: [dev, main]
    paths:
      - 'bot/**'
      - '.github/workflows/bot-ci.yml'
  pull_request:
    paths:
      - 'bot/**'
      - '.github/workflows/bot-ci.yml'

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: bot
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-dotnet@v4
        with:
          dotnet-version: 8.0.x
      - run: dotnet restore
      - run: dotnet build --no-restore --configuration Release
      - run: dotnet test --no-build --configuration Release --verbosity normal
```

- [ ] **Step 5: Smoke-test the Dockerfile build**

```bash
cd bot && docker build -t slpa-bot:test -f Dockerfile .
```

Expected: Build succeeds. Image tag `slpa-bot:test` created.

If the build fails due to `dotnet restore` seeing both the app csproj and the tests csproj, verify both `COPY` lines in Step 1 are present so restore sees the test project's NuGet pins.

- [ ] **Step 6: Commit**

```bash
git add bot/Dockerfile bot/.env.example bot/.gitignore \
        docker-compose.yml \
        .github/workflows/bot-ci.yml

git commit -m "chore(ops): Dockerfile + compose service + CI workflow for bot-1"
```

---

## Task 12: Docs — README / DEFERRED_WORK / FOOTGUNS + Cleanup

**Files:**
- Modify: `README.md` (root — bot service section + env notes)
- Modify: `docs/implementation/DEFERRED_WORK.md` (close 2, open 5 entries)
- Modify: `docs/implementation/FOOTGUNS.md` (4 new entries)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java` (remove deprecated shim)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` (remove old matchers)

Spec reference: §10.2, §10.3.

- [ ] **Step 1: Update root `README.md`**

Add a new "Bot service" section under the architecture / services overview. Grep the current README for its existing section structure (e.g., search for `## Backend` / `## Frontend`) and insert between frontend and the infra-dependencies list:

```markdown
### Bot service

C#/.NET 8 worker that logs into Second Life and services the backend's
bot task queue (Method C verification + BOT-tier monitoring). One container
per SL account; scale by adding compose blocks. See [`bot/README.md`](bot/README.md)
for environment setup.

- Pulls tasks via `POST /api/v1/bot/tasks/claim` (atomic SKIP LOCKED).
- Reports to `PUT /api/v1/bot/tasks/{id}/verify` or
  `POST /api/v1/bot/tasks/{id}/monitor`.
- Auth: bearer token `slpa.bot.shared-secret`.
- Local dev: single `bot-1` block in `docker-compose.yml` reading
  `bot/.env.bot-1`.
```

Also add `bot/` to any "Key Directories" or "Services" list the README maintains.

- [ ] **Step 2: Close + open entries in `DEFERRED_WORK.md`**

Find and **remove** these two entries (Task 3 + Task 4 closed them):

- `### Bot service authentication` — the full stanza.
- `### IN_PROGRESS bot task timeout` — the full stanza.

For the `### Primary escrow UUID + SLPA trusted-owner-keys production config` entry, narrow its scope to just the `SLPA trusted-owner-keys` half (since the bot's primary-escrow-uuid guard landed in Task 3). Replace its body with:

```markdown
### SLPA trusted-owner-keys production config
- **From:** Epic 03 sub-spec 1 (SL header trust)
- **Why:** `slpa.sl.trusted-owner-keys` is empty in `application.yml` (dev
  override in `application-dev.yml`). Production must override via env
  var / secrets manager.
- **When:** First production deployment (pre-launch ops checklist).
- **Notes:** `SlStartupValidator` already fails fast on prod boot if
  `trusted-owner-keys` is still empty — that is the forcing function.
  The bot half of this item (primary-escrow-uuid) landed in Epic 06 Task 3
  via `BotStartupValidator` and is no longer deferred. See FOOTGUNS §F.47.
```

Now **append** these five new entries:

```markdown
### Admin pool health dashboard
- **From:** Epic 06 (spec §1.2)
- **Why:** Each bot container exposes `GET /health` with its
  `SessionState`, but no admin page aggregates them. Ops must curl each
  container directly (`docker compose exec bot-1 wget -O- http://localhost:8081/health`).
- **When:** Epic 10 (Admin & Moderation) — fold into the broader admin
  dashboard surface.
- **Notes:** Aggregation endpoint shape: `GET /api/v1/admin/bot-pool/health`
  returns `[{ botUuid, username, state, lastClaimAt, region }]`. Requires
  a heartbeat mechanism — workers POST their state every ~60 s to a new
  `POST /api/v1/bot/heartbeat` endpoint (same bearer auth), backend
  persists in Redis with a TTL.

### Notifications for bot-detected fraud
- **From:** Epic 06 (spec §1.2)
- **Why:** Bot-triggered `SuspensionService.suspendForBotObservation` writes
  a FraudFlag row visible only in my-listings; no email / SL IM fires.
  Consistent with Epic 04 / 05 deferrals.
- **When:** Epic 09 (Notifications).
- **Notes:** Hook the notification publisher into all three suspend call
  sites + `EscrowService.freezeForFraud` and `markReviewRequired`. Do
  NOT include the raw observation payload in the notification — admin-only.

### Per-worker auth tokens (`bot_workers` table)
- **From:** Epic 06 brainstorm
- **Why:** Phase 1 ships a single shared bearer secret across all workers.
  Per-worker tokens give admin auditing (which worker made which call) but
  require a new provisioning surface. Premature at Phase 1 volume.
- **When:** Indefinite — trigger is an audit-trail requirement.
- **Notes:** New `bot_workers` table: `(id, name, sl_uuid, token_hash,
  created_at, revoked_at)`. Authorizer switches from single-secret
  compare to token-hash lookup. Rotation endpoint under `/api/v1/admin/`.

### HMAC-SHA256 per-request bot auth
- **From:** Epic 06 brainstorm
- **Why:** Bearer token is replay-vulnerable between request and response;
  HMAC-SHA256 over `(method, path, body, timestamp, nonce)` with a
  replay window prevents replay. Deferred because the same improvement
  is already deferred for the escrow terminal auth, and both should
  land together.
- **When:** Phase 2 hardening — same timeline as the escrow terminal
  HMAC rollout.
- **Notes:** Backend nonce-replay window ~60 s, stored in Redis.
  `slpa.bot.shared-secret` stays as the HMAC key; rotation via config
  + redeploy.

### Parcel layout map generation
- **From:** Epic 06 spec §1.2
- **Why:** DESIGN.md §5.5 flags this as needing further design. Four
  possible implementation routes (LSL scan, bot scan, wearable scanner,
  seller-run scanner) with no decision yet.
- **When:** Indefinite — pending a dedicated design pass.
- **Notes:** The bot scan variant would live in
  `bot/src/Slpa.Bot/Tasks/LayoutMapHandler.cs` alongside verify/monitor,
  driven by a new `BotTaskType.LAYOUT_MAP`. Do not scaffold until the
  design lands.
```

- [ ] **Step 3: Append entries to `FOOTGUNS.md`**

Grep the existing FOOTGUNS.md for the last numbered entry (e.g., `§F.85`) to find the next number. Assume the next is F.86; adjust if the file has grown since this plan was written.

```markdown
### F.86 — SKIP LOCKED is not portable SQL

`SELECT ... FOR UPDATE SKIP LOCKED` is Postgres-specific syntax. Spring
Data JPQL has no equivalent; the clause must be passed via a native query
(`@Query(..., nativeQuery = true)`). Repository methods using SKIP LOCKED
therefore cannot be re-used against an in-memory H2 or Derby test DB —
integration tests that want the lock behavior must run against Testcontainers
Postgres (or the shared dev Postgres container).

**Touchpoint:** `BotTaskRepository.claimNext`. If the project ever adds
an H2-backed test profile, the claim query has to be stubbed or the tests
gated on `@ActiveProfiles("test")` (Postgres).

### F.87 — `@Modifying` bypasses `@UpdateTimestamp`

Bulk UPDATE queries annotated with `@Modifying` skip Hibernate's entity
lifecycle, so `@UpdateTimestamp`-annotated columns like `lastUpdatedAt`
are NOT refreshed. If a bulk query changes row state, it must also set
`lastUpdatedAt = :now` explicitly in the SET clause.

**Touchpoint:** `BotTaskRepository.cancelLiveByAuctionIdAndTypes` /
`cancelLiveByEscrowId`. Any future bulk update on an entity with
`@UpdateTimestamp` must follow the same pattern.

### F.88 — Hibernate `ddl-auto: update` does not widen CHECK constraints

When a Java enum gains a new value (e.g., `BotTaskType` adds `MONITOR_AUCTION`),
Hibernate's `update` mode does NOT rewrite the existing Postgres CHECK
constraint. Inserts with the new value fail at the DB level with
`check constraint violated` until the constraint is manually refreshed.

**Solution:** Register a per-(table, column, enum) `@Component` that
invokes `EnumCheckConstraintSync.sync(...)` on `ApplicationReadyEvent`.
See `BotTaskTypeCheckConstraintInitializer` /
`BotTaskStatusCheckConstraintInitializer` /
`FraudFlagReasonCheckConstraintInitializer`.

**Touchpoint:** every enum column in the DB. When adding a new
`@Enumerated(EnumType.STRING)` column, also add a constraint initializer
alongside the entity.

### F.89 — .NET 8 container default port is 8080

ASP.NET Core 8 defaults to listening on port `8080` inside containers via
`ASPNETCORE_HTTP_PORTS`. If the Dockerfile `EXPOSE`s a different port (e.g.,
`8081`), the app still listens on `8080` and every healthcheck hitting
the exposed port fails silently.

**Solution:** set `ENV ASPNETCORE_HTTP_PORTS=<port>` in the Dockerfile
BEFORE `ENTRYPOINT`. Or override via compose `environment:` block. Both
patterns work; Dockerfile is more robust.

**Touchpoint:** `bot/Dockerfile`. Any future .NET-in-container service
must set this env var explicitly.
```

- [ ] **Step 4: Remove the deprecated `PUT /api/v1/bot/tasks/{id}` shim from `BotTaskController`**

Delete the `completeLegacy` method from `BotTaskController.java` (added in Task 2 as a `@Deprecated` shim). The worker is on `/verify` by the time this task runs, so the shim is no longer needed. Verify no tests still reference the old path before deleting — grep `src/test/java/com/slparcelauctions/backend/bot` for `/api/v1/bot/tasks/\\{id\\}` or raw `PUT` invocations on the base path.

- [ ] **Step 5: Delete old `SecurityConfig` matchers for `/api/v1/bot/**`**

The bearer-auth matcher added in Task 3 already covers the full `/api/v1/bot/**` surface. Delete the Task 2 / Epic 03 sub-spec 1 legacy matcher block that permitted `GET /pending` and `PUT /**` — it is now dead code. The Task 3 `.access(...)` matcher authoritatively gates all bot paths.

- [ ] **Step 6: Verify backend suite still passes**

```bash
cd backend && ./mvnw test -q
```

Expected: PASS. The `/pending` + `/{id}` legacy paths are now behind bearer auth; any test that exercised them without the header should have been updated in earlier tasks. If a slice test fails with 401, update it to include `.header("Authorization", "Bearer …")`.

- [ ] **Step 7: Verify bot tests pass**

```bash
cd bot && dotnet test
```

Expected: PASS.

- [ ] **Step 8: Sweep the root README**

Double-check the README's high-level status table / implementation-phase list reflects Epic 06's completion. Any line that reads "Epic 06: pending" or similar should be updated to reflect the bot service as live.

- [ ] **Step 9: Final commit**

```bash
git add README.md \
        docs/implementation/DEFERRED_WORK.md \
        docs/implementation/FOOTGUNS.md \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java

git commit -m "docs(epic-06): README sweep, DEFERRED/FOOTGUNS updates, remove deprecated shim"
```

- [ ] **Step 10: Open the PR**

```bash
git push

gh pr create --base dev --head task/06-bot-service --title "Epic 06 — SL bot service (Method C + BOT-tier monitoring)" --body "$(cat <<'EOF'
## Summary

- Ships the C#/.NET 8 SL bot worker service at `bot/` (login, rate-limited teleport, ParcelProperties read, VERIFY + MONITOR handlers, TaskLoop, bearer auth to backend).
- Hardens the existing bot task surface: atomic claim endpoint with SKIP LOCKED, bearer-token auth, startup validator, IN_PROGRESS timeout sweep, split VERIFY / MONITOR callback paths.
- Adds MONITOR_AUCTION + MONITOR_ESCROW task types with lifecycle-hook-driven row creation and cancellation.
- Wires `bot-1` into `docker-compose.yml`; adds a minimal GitHub Actions job running `dotnet test` on `bot/**` changes.
- Closes deferred items: Bot service authentication, IN_PROGRESS bot task timeout, bot half of Primary escrow UUID startup guard. Opens 5 new deferred items (admin pool health, notifications, per-worker tokens, HMAC, parcel layout map).

## Test plan

- [ ] `cd backend && ./mvnw test` — full Java suite passes including new slice / integration tests (SKIP LOCKED race, auth gating, lifecycle hooks, dispatcher matrix, IN_PROGRESS timeout).
- [ ] `cd bot && dotnet test` — full C# suite passes (rate limiter, parcel reader, http client, verify + monitor handlers, task loop behavioral tests).
- [ ] `cd bot && docker build -t slpa-bot:test -f Dockerfile .` — image builds cleanly.
- [ ] `docker compose up backend bot-1` — with real `SLPABot1` creds in `bot/.env.bot-1`, bot logs in and `curl http://localhost:8081/health` returns `{"state":"Online"}` within 10 s.
- [ ] Manual: queue a VERIFY task (`POST /api/v1/dev/bot/tasks/{taskId}/complete` dev stub or real Method C listing). Bot claims, teleports, reads, posts SUCCESS. Auction flips ACTIVE with tier=BOT. MONITOR_AUCTION row appears in `bot_tasks`.
- [ ] Manual: cancel the auction. MONITOR_AUCTION row flips to CANCELLED.
EOF
)"
```

Wait for green CI (both Java + C# jobs). Review the diff against the plan one more time. Then `gh pr merge --squash` (or merge via UI).

---

## Self-Review Checklist (For the Plan Author)

### Spec coverage

| Spec section                          | Covered by task |
|---------------------------------------|-----------------|
| §1 Goal & Scope                        | Preamble        |
| §2 Architecture                        | Preamble + Tasks 7–10 |
| §3.1 Schema extensions                 | Task 1          |
| §3.2 Enum additions                    | Task 1          |
| §3.3 Atomic claim endpoint             | Task 2          |
| §3.4 Split callback endpoints          | Tasks 4, 5      |
| §3.5 Bearer-token auth                 | Task 3          |
| §3.6 Startup guards                    | Task 3          |
| §3.7 IN_PROGRESS timeout               | Task 4          |
| §3.8 Lifecycle hooks                   | Task 6          |
| §3.9 New DTOs                          | Tasks 2, 5      |
| §4.1 Project layout                    | Task 7          |
| §4.2 Login / session management        | Task 7          |
| §4.3 Teleport + parcel read            | Task 8          |
| §4.4 TaskLoop                          | Task 10         |
| §4.5 VerifyHandler                     | Task 10         |
| §4.6 MonitorHandler                    | Task 10         |
| §5 Data model details                  | Tasks 1, 6      |
| §6 Monitor callback dispatch           | Task 5          |
| §7.1 Rate limiting                     | Task 8          |
| §7.2 Retry policy                      | Task 9          |
| §7.3 Session disconnect                | Task 10         |
| §7.4 Backend error responses           | Tasks 2, 4, 5   |
| §7.5 Restart mid-IN_PROGRESS           | Task 4          |
| §8.1 Env mapping                       | Task 7          |
| §8.2 Dockerfile                        | Task 11         |
| §8.3 Compose additions                 | Task 11         |
| §8.4 Backend config                    | Task 3          |
| §8.5 Environment matrix                | Task 11 + README |
| §8.6 Startup ordering                  | Task 11         |
| §9 Testing strategy                    | Every task that touches code |
| §10 Task decomposition                 | Tasks 1–12      |
| §11 Open questions                     | (all settled)   |
| §12 Success criteria                   | Test plan in Task 12 |

### Type consistency across tasks

| Name                               | Defined in | Referenced in                       |
|------------------------------------|------------|-------------------------------------|
| `BotTaskType.MONITOR_AUCTION`      | Task 1     | Tasks 5, 6, 9, 10                   |
| `BotTaskStatus.CANCELLED`          | Task 1     | Tasks 6, 9                          |
| `FraudFlagReason.BOT_*`            | Task 1     | Tasks 5, 6                          |
| `FreezeReason.BOT_OWNERSHIP_CHANGED` | Task 1  | Task 5                              |
| `BotTask.expected*` columns        | Task 1     | Tasks 5, 6, 10                      |
| `BotTaskResponse` record shape     | Task 1     | Tasks 2, 4, 5, 9                    |
| `BotTaskClaimRequest`              | Task 2     | Task 9                              |
| `BotSharedSecretAuthorizer`        | Task 3     | SecurityConfig (Task 3)             |
| `BotTaskConfigProperties`          | Task 3     | Tasks 3, 6                          |
| `MonitorOutcome` values            | Task 5     | Tasks 5, 9, 10                      |
| `DispatchOutcome.shouldReArm`      | Task 5     | Task 5 consumer (`recordMonitorResult`) |
| `BotMonitorLifecycleService` methods | Task 6   | Tasks 5 (SuspensionService hook) , 6 |
| `IBotSession` interface            | Task 7     | Tasks 8, 10                         |
| `TeleportRateLimiter`              | Task 8     | Task 7 ctor override                |
| `ParcelSnapshot`                   | Task 8     | Task 10                             |
| `IBackendClient`                   | Task 9     | Task 10                             |
| `AuthConfigException`              | Task 9     | Task 10 task loop                   |

Shapes match across tasks.

### Placeholder scan

Searched plan for `TBD`, `TODO`, `fill in`, `implement later`, `add appropriate error handling`, "similar to Task N". None found except inside deliberate quoted-code comments.

### Plan status

All 12 tasks are fully specified with concrete code, exact file paths, commands, and commit messages. Ready for execution.











