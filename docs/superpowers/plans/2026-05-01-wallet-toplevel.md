# Wallet Top-Level + Header Indicator + Beautify + Ledger UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote `/wallet` to a top-level route with a verified-only header indicator (icon + L$, hover-popover desktop, tap-to-route mobile), beautify `WalletPanel` to match Material-3 tokens, add full pagination + filters + CSV export to the ledger view, and wire WebSocket live updates from server-side wallet mutations to the frontend cache.

**Architecture:** Single feature branch (`feat/wallet-toplevel` off `dev`, already created). Backend additions are purely additive: a new STOMP publisher hooked into `WalletService` mutation methods, an extended `GET /me/wallet/ledger` endpoint with filter/page params, and a new streaming `GET /me/wallet/ledger/export.csv`. Frontend moves the page to `/wallet`, adds `<HeaderWalletIndicator>`, refreshes the panel chrome with design tokens + lucide icons + the project's modal/input/pagination components, splits the ledger into its own component with a filter bar, and subscribes to `/user/queue/wallet` via the existing STOMP infrastructure.

**Tech Stack:** Spring Boot 4 / Java 26 / Spring WebSocket (STOMP) / JPA Specification API / `StreamingResponseBody` for CSV / JUnit 5 + Mockito / Next.js 16 / React 19 / TypeScript 5 / React Query / `@stomp/stompjs` / Tailwind 4 / lucide-react / date-fns.

**Spec authority:** `docs/superpowers/specs/2026-05-01-wallet-toplevel-and-header-indicator-design.md`.

---

## Phase ordering

```
1. Backend WS publish infrastructure
2. Backend ledger pagination + filters
3. Backend CSV export endpoint
4. Frontend /wallet route + dashboard tab removal
5. Frontend HeaderWalletIndicator + Header/MobileMenu integration
6. Frontend shared Modal + WalletPanel beautify (tokens, icons, hierarchy)
7. Frontend Ledger split-out (LedgerFilterBar + LedgerTable + pagination + export)
8. Frontend WS subscription hook + integration
9. Smoke + PR
```

Phases 1-3 are backend, no frontend dependency. Phase 4 unblocks 5/6/7. Phase 8 depends on phase 1's WS publisher being live in prod (or stubbed in dev). Phase 9 closes out.

---

## Phase 1 — Backend WebSocket publish infrastructure

### Task 1.1: `WalletBalanceChangedEnvelope` record

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/broadcast/WalletBalanceChangedEnvelope.java`

**Acceptance:** Compiles cleanly; record has factory `of(User, String, Long, OffsetDateTime)`.

```java
package com.slparcelauctions.backend.wallet.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.user.User;

/**
 * Published to /user/queue/wallet on every wallet mutation. Frontend
 * subscriber merges into the React Query cache so balance updates appear
 * without polling. See spec docs/superpowers/specs/2026-05-01-wallet-toplevel-and-header-indicator-design.md §7.
 */
public record WalletBalanceChangedEnvelope(
        long balance,
        long reserved,
        long available,
        long penaltyOwed,
        String reason,           // matches a UserLedgerEntryType name
        Long ledgerEntryId,
        OffsetDateTime occurredAt
) {
    public static WalletBalanceChangedEnvelope of(User u, String reason,
            Long ledgerEntryId, OffsetDateTime occurredAt) {
        long owed = u.getPenaltyBalanceOwed() == null ? 0L : u.getPenaltyBalanceOwed();
        return new WalletBalanceChangedEnvelope(
                u.getBalanceLindens(),
                u.getReservedLindens(),
                u.availableLindens(),
                owed,
                reason,
                ledgerEntryId,
                occurredAt);
    }
}
```

- [ ] Step 1.1.1: Write the file.
- [ ] Step 1.1.2: `./mvnw compile -q -DskipTests` — green.
- [ ] Step 1.1.3: Commit `feat(wallet): WalletBalanceChangedEnvelope record`.

### Task 1.2: `WalletBroadcastPublisher`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/broadcast/WalletBroadcastPublisher.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/wallet/broadcast/WalletBroadcastPublisherTest.java`

**Service shape:**

```java
package com.slparcelauctions.backend.wallet.broadcast;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletBroadcastPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * Registers an afterCommit synchronization that publishes the envelope
     * to {@code /user/{user.id}/queue/wallet}. If called outside a tx,
     * publishes immediately (defensive — production callers always run
     * inside @Transactional).
     */
    public void publish(User user, String reason, Long ledgerEntryId) {
        WalletBalanceChangedEnvelope envelope = WalletBalanceChangedEnvelope.of(
                user, reason, ledgerEntryId, OffsetDateTime.now(clock));
        Long userId = user.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doPublish(userId, envelope);
                        }
                    });
        } else {
            doPublish(userId, envelope);
        }
    }

    private void doPublish(Long userId, WalletBalanceChangedEnvelope envelope) {
        messagingTemplate.convertAndSendToUser(
                userId.toString(), "/queue/wallet", envelope);
        log.debug("WALLET_BALANCE_CHANGED published: userId={}, reason={}, ledgerEntryId={}",
                userId, envelope.reason(), envelope.ledgerEntryId());
    }
}
```

**Test (Mockito):**

```java
package com.slparcelauctions.backend.wallet.broadcast;

import static org.mockito.Mockito.*;

import java.time.*;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.slparcelauctions.backend.user.User;

class WalletBroadcastPublisherTest {

    @Test
    void publishWithoutActiveTx_sendsImmediately() {
        SimpMessagingTemplate tpl = mock(SimpMessagingTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);
        WalletBroadcastPublisher pub = new WalletBroadcastPublisher(tpl, clock);

        User u = User.builder().id(42L)
                .balanceLindens(100L).reservedLindens(20L)
                .penaltyBalanceOwed(0L).build();

        pub.publish(u, "DEPOSIT", 7L);

        verify(tpl).convertAndSendToUser(eq("42"), eq("/queue/wallet"),
                any(WalletBalanceChangedEnvelope.class));
    }
}
```

- [ ] Step 1.2.1: Write the publisher.
- [ ] Step 1.2.2: Write the test (no-tx path; afterCommit path is exercised by integration tests in Phase 1.3).
- [ ] Step 1.2.3: `./mvnw test -Dtest=WalletBroadcastPublisherTest -q` — passes.
- [ ] Step 1.2.4: Commit `feat(wallet): WalletBroadcastPublisher with afterCommit synchronization`.

### Task 1.3: Hook publisher into `WalletService` mutation methods

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java`

**Hooks to add** (one publish call per mutation, after the ledger row save and inside the same `@Transactional` so the afterCommit fires once the tx commits):

| Method | Reason string | Affected user |
|---|---|---|
| `deposit` | `"DEPOSIT"` | the depositor |
| `withdrawCommon` (called by site + touch) | `"WITHDRAW_QUEUED"` | the withdrawer |
| `payPenalty` | `"PENALTY_DEBIT"` | the payer |
| `swapReservation` | `"BID_RELEASED"` for prior, `"BID_RESERVED"` for new | per-affected user |
| `autoFundEscrow` | `"ESCROW_DEBIT"` (single publish, the higher-info event) | the winner |
| `creditEscrowRefund` | `"ESCROW_REFUND"` | the winner |
| `creditListingFeeRefund` | `"LISTING_FEE_REFUND"` | the seller |
| `debitListingFee` | `"LISTING_FEE_DEBIT"` | the seller |
| `releaseReservationsForAuction` | `"BID_RELEASED"` | per-affected user |
| `releaseAllReservationsForUser` | `"BID_RELEASED"` | the user |
| `recordWithdrawalSuccess` | `"WITHDRAW_COMPLETED"` | the withdrawer |
| `recordWithdrawalReversal` | `"WITHDRAW_REVERSED"` | the withdrawer |

Inject the publisher:

```java
private final WalletBroadcastPublisher walletBroadcastPublisher;
```

Add the call after each ledger save. Example for `deposit`:

```java
UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
        // ...existing builder calls...
        .build());

walletBroadcastPublisher.publish(user, UserLedgerEntryType.DEPOSIT.name(), entry.getId());

log.info("deposit ok: ...");
return DepositResult.ok(user, entry);
```

For `swapReservation`, publish twice — once for prior bidder (after `BID_RELEASED` row save) and once for new bidder (after `BID_RESERVED` row save).

For loop-based methods (`releaseReservationsForAuction`, `releaseAllReservationsForUser`), publish per iteration after the per-user state is saved.

- [ ] Step 1.3.1: Add `WalletBroadcastPublisher` field + RequiredArgsConstructor wiring.
- [ ] Step 1.3.2: Add publish call to `deposit`.
- [ ] Step 1.3.3: Add publish calls to `withdrawCommon` (used by site + touch).
- [ ] Step 1.3.4: Add publish call to `payPenalty`.
- [ ] Step 1.3.5: Add publish calls (×2) to `swapReservation` — prior and new bidder.
- [ ] Step 1.3.6: Add publish call to `autoFundEscrow` (single, after both ledger rows).
- [ ] Step 1.3.7: Add publish calls to `creditEscrowRefund` and `creditListingFeeRefund`.
- [ ] Step 1.3.8: Add publish call to `debitListingFee`.
- [ ] Step 1.3.9: Add publish call inside the loop in `releaseReservationsForAuction`.
- [ ] Step 1.3.10: Add publish call inside the loop in `releaseAllReservationsForUser`.
- [ ] Step 1.3.11: Add publish calls to `recordWithdrawalSuccess` and `recordWithdrawalReversal`. (Look up the user via `userRepository.findById(queuedRow.getUserId())` for the success case since the existing method doesn't lock the user.)
- [ ] Step 1.3.12: `./mvnw compile -q -DskipTests` — green.
- [ ] Step 1.3.13: Run the existing test suite to confirm no regressions: `docker exec slpa-postgres-1 psql -U slpa -d slpa -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO PUBLIC;"` then `./mvnw test -q | grep -E "Tests run:|BUILD" | tail -3`. Expected: 1591/1591 still pass.
- [ ] Step 1.3.14: Commit `feat(wallet): publish WALLET_BALANCE_CHANGED on every mutation point`.

### Task 1.4: Integration test — deposit publishes envelope

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/broadcast/WalletBroadcastIntegrationTest.java`

```java
package com.slparcelauctions.backend.wallet.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.WalletService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("dev")
class WalletBroadcastIntegrationTest {

    @Autowired WalletService walletService;
    @Autowired UserRepository userRepo;
    @MockBean SimpMessagingTemplate messagingTemplate;

    @Test
    void deposit_publishesAfterCommit() {
        User u = userRepo.save(User.builder()
                .email("test+wallet-broadcast@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .balanceLindens(0L)
                .reservedLindens(0L)
                .build());
        AtomicReference<WalletBalanceChangedEnvelope> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(inv.getArgument(2, WalletBalanceChangedEnvelope.class));
            return null;
        }).when(messagingTemplate).convertAndSendToUser(
                eq(u.getId().toString()), eq("/queue/wallet"),
                any(WalletBalanceChangedEnvelope.class));

        walletService.deposit(u.getSlAvatarUuid(), 500, "tx-" + UUID.randomUUID());

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().balance()).isEqualTo(500L);
        assertThat(captured.get().reason()).isEqualTo("DEPOSIT");
    }
}
```

- [ ] Step 1.4.1: Write the test.
- [ ] Step 1.4.2: Reset DB schema, run `./mvnw test -Dtest=WalletBroadcastIntegrationTest -q`. Expected: passes.
- [ ] Step 1.4.3: Commit `test(wallet): integration test for deposit broadcast publish`.

---

## Phase 2 — Backend ledger pagination + filters

### Task 2.1: `LedgerFilter` request DTO + Specification

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/LedgerFilter.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/LedgerSpecifications.java`

**`LedgerFilter`:**

```java
package com.slparcelauctions.backend.wallet.me;

import java.time.OffsetDateTime;
import java.util.List;

import com.slparcelauctions.backend.wallet.UserLedgerEntryType;

public record LedgerFilter(
        List<UserLedgerEntryType> entryTypes,  // null/empty = all types
        OffsetDateTime from,                   // null = no lower bound
        OffsetDateTime to,                     // null = no upper bound
        Long amountMin,                        // null = no lower bound
        Long amountMax                         // null = no upper bound
) {}
```

**`LedgerSpecifications`:**

```java
package com.slparcelauctions.backend.wallet.me;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.slparcelauctions.backend.wallet.UserLedgerEntry;

import jakarta.persistence.criteria.Predicate;

public final class LedgerSpecifications {
    private LedgerSpecifications() {}

    public static Specification<UserLedgerEntry> forUser(Long userId, LedgerFilter f) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("userId"), userId));
            if (f.entryTypes() != null && !f.entryTypes().isEmpty()) {
                ps.add(root.get("entryType").in(f.entryTypes()));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("createdAt"), f.to()));
            }
            if (f.amountMin() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("amount"), f.amountMin()));
            }
            if (f.amountMax() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("amount"), f.amountMax()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
```

- [ ] Step 2.1.1: Write the DTO + Specification helper.
- [ ] Step 2.1.2: `./mvnw compile -q -DskipTests` — green.
- [ ] Step 2.1.3: Commit `feat(wallet): LedgerFilter DTO + Specification helper`.

### Task 2.2: Extend `UserLedgerRepository`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerRepository.java`

```java
public interface UserLedgerRepository
        extends JpaRepository<UserLedgerEntry, Long>,
                JpaSpecificationExecutor<UserLedgerEntry> {
    // existing methods unchanged
}
```

Add `JpaSpecificationExecutor<UserLedgerEntry>` to the inheritance list. No other changes — the controller will call `findAll(Specification, Pageable)` directly.

- [ ] Step 2.2.1: Add the interface inheritance.
- [ ] Step 2.2.2: `./mvnw compile -q -DskipTests` — green.
- [ ] Step 2.2.3: Commit `feat(wallet): UserLedgerRepository extends JpaSpecificationExecutor`.

### Task 2.3: `GET /me/wallet/ledger` endpoint

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/MeWalletController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/LedgerPageResponse.java`

**Response DTO:**

```java
package com.slparcelauctions.backend.wallet.me;

import java.util.List;

import com.slparcelauctions.backend.wallet.me.WalletViewResponse.LedgerEntryDto;

public record LedgerPageResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<LedgerEntryDto> entries
) {}
```

**Controller method:**

```java
@GetMapping("/wallet/ledger")
@Transactional(readOnly = true)
public LedgerPageResponse ledger(
        @AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(name = "entryType", required = false) List<UserLedgerEntryType> entryTypes,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @RequestParam(required = false) Long amountMin,
        @RequestParam(required = false) Long amountMax) {
    int pageSize = Math.min(Math.max(size, 1), 100);
    LedgerFilter filter = new LedgerFilter(entryTypes, from, to, amountMin, amountMax);
    Pageable p = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<UserLedgerEntry> result = ledgerRepository.findAll(
            LedgerSpecifications.forUser(principal.userId(), filter), p);
    List<WalletViewResponse.LedgerEntryDto> dtos =
            result.getContent().stream().map(MeWalletController::toDto).toList();
    return new LedgerPageResponse(
            result.getNumber(), result.getSize(),
            result.getTotalElements(), result.getTotalPages(), dtos);
}
```

Add necessary imports: `org.springframework.data.domain.{Page, PageRequest, Pageable, Sort}`, `org.springframework.format.annotation.DateTimeFormat`, `java.time.OffsetDateTime`, `java.util.List`, `com.slparcelauctions.backend.wallet.UserLedgerEntry`, `com.slparcelauctions.backend.wallet.UserLedgerEntryType`.

- [ ] Step 2.3.1: Write `LedgerPageResponse` DTO.
- [ ] Step 2.3.2: Add the controller method + imports.
- [ ] Step 2.3.3: `./mvnw compile -q -DskipTests` — green.
- [ ] Step 2.3.4: Commit `feat(wallet): GET /me/wallet/ledger with pagination + filters`.

### Task 2.4: Slice test for filtered/paged ledger

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/me/MeWalletLedgerControllerSliceTest.java`

Test cases:
1. No filters → returns first page sorted DESC by `created_at`.
2. `entryType=DEPOSIT&entryType=WITHDRAW_QUEUED` → returns only those types.
3. `from=2026-05-01T00:00:00Z&to=2026-05-02T00:00:00Z` → returns only that day's entries.
4. `amountMin=100&amountMax=500` → returns only entries within range.
5. `page=1&size=10` → returns next 10 entries.
6. Unauth → 401.

Use the existing slice-test fixture pattern (see `MeWalletControllerSliceTest` if it exists; if not, mirror `EscrowControllerSliceTest`). Seed user_ledger rows via `@DataJpaTest`-style setup or REST calls in setUp.

- [ ] Step 2.4.1: Write the test.
- [ ] Step 2.4.2: `./mvnw test -Dtest=MeWalletLedgerControllerSliceTest -q` — passes.
- [ ] Step 2.4.3: Commit `test(wallet): slice test for /me/wallet/ledger filters + pagination`.

---

## Phase 3 — Backend CSV export endpoint

### Task 3.1: `LedgerCsvWriter`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/LedgerCsvWriter.java`

```java
package com.slparcelauctions.backend.wallet.me;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import com.slparcelauctions.backend.wallet.UserLedgerEntry;

/**
 * Writes a stream of {@link UserLedgerEntry} rows to an OutputStream as
 * RFC 4180 CSV. Used by the streaming export endpoint so multi-thousand-row
 * downloads don't hold the whole result in memory.
 */
public final class LedgerCsvWriter {

    private static final String HEADER =
            "id,created_at,entry_type,amount,balance_after,reserved_after," +
            "ref_type,ref_id,description,sl_transaction_id";

    private LedgerCsvWriter() {}

    public static void write(Stream<UserLedgerEntry> rows, OutputStream out)
            throws IOException {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            w.write(HEADER);
            w.write('\n');
            rows.forEach(e -> {
                try {
                    w.write(toCsvLine(e));
                    w.write('\n');
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            });
        }
    }

    private static String toCsvLine(UserLedgerEntry e) {
        return e.getId() + ","
                + escape(e.getCreatedAt().toString()) + ","
                + e.getEntryType().name() + ","
                + e.getAmount() + ","
                + e.getBalanceAfter() + ","
                + e.getReservedAfter() + ","
                + escape(e.getRefType()) + ","
                + (e.getRefId() == null ? "" : e.getRefId()) + ","
                + escape(e.getDescription()) + ","
                + escape(e.getSlTransactionId());
    }

    private static String escape(String s) {
        if (s == null) return "";
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        String escaped = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }
}
```

- [ ] Step 3.1.1: Write the file.
- [ ] Step 3.1.2: Unit test the escape logic — write `LedgerCsvWriterTest` covering: comma in description, quotes in description, newline in description, null fields, ASCII-only fast path.
- [ ] Step 3.1.3: Commit `feat(wallet): LedgerCsvWriter with RFC 4180 escaping`.

### Task 3.2: Streaming repository method

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerRepository.java`

Add a stream-returning Specification method. Note: returning `Stream` from JpaSpecificationExecutor isn't standard — instead, add a custom-impl `LedgerStreamRepository` interface + impl, or use `EntityManager.createQuery(...).getResultStream()` from a service-layer helper.

Simpler approach: add a service method that uses `EntityManager` directly. We'll put it in a small new `LedgerStreamingService`:

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/LedgerStreamingService.java`

```java
package com.slparcelauctions.backend.wallet.me;

import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.wallet.UserLedgerEntry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LedgerStreamingService {

    private final EntityManager em;

    /**
     * Streams matching ledger entries for the given user + filter. Caller
     * MUST close the stream (try-with-resources) and MUST hold an active
     * read-only transaction. Sorted DESC by created_at.
     */
    @Transactional(readOnly = true)
    public Stream<UserLedgerEntry> streamFiltered(Long userId, LedgerFilter filter) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UserLedgerEntry> q = cb.createQuery(UserLedgerEntry.class);
        Root<UserLedgerEntry> root = q.from(UserLedgerEntry.class);
        q.select(root)
            .where(LedgerSpecifications.forUser(userId, filter)
                    .toPredicate(root, q, cb))
            .orderBy(cb.desc(root.get("createdAt")));
        return em.createQuery(q).getResultStream();
    }
}
```

- [ ] Step 3.2.1: Write the streaming service.
- [ ] Step 3.2.2: `./mvnw compile -q -DskipTests` — green.
- [ ] Step 3.2.3: Commit `feat(wallet): LedgerStreamingService for CSV export`.

### Task 3.3: Rate-limit bucket for CSV export

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/LedgerExportRateLimiter.java`

```java
package com.slparcelauctions.backend.wallet.me;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * In-memory rate limiter for the CSV export endpoint. 1 export per user
 * per 60 seconds. Key on user id; the bucket is process-local — fine for
 * single-instance ECS task; can move to Redis if we go multi-instance.
 */
@Component
@RequiredArgsConstructor
public class LedgerExportRateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final ConcurrentHashMap<Long, Instant> lastExportAt = new ConcurrentHashMap<>();

    /**
     * @return true if the request is allowed; false if blocked by rate limit
     */
    public boolean tryAcquire(Long userId) {
        Instant now = clock.instant();
        Instant prior = lastExportAt.get(userId);
        if (prior != null && Duration.between(prior, now).compareTo(WINDOW) < 0) {
            return false;
        }
        lastExportAt.put(userId, now);
        return true;
    }
}
```

- [ ] Step 3.3.1: Write the limiter.
- [ ] Step 3.3.2: Unit test: first call returns true, immediate second call returns false, after the window passes (use injected `Clock.fixed` and bump it) returns true again.
- [ ] Step 3.3.3: Commit `feat(wallet): per-user CSV export rate limiter (1/60s)`.

### Task 3.4: `GET /me/wallet/ledger/export.csv` endpoint

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/MeWalletController.java`

```java
@GetMapping(value = "/wallet/ledger/export.csv", produces = "text/csv")
public ResponseEntity<StreamingResponseBody> exportLedger(
        @AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam(name = "entryType", required = false) List<UserLedgerEntryType> entryTypes,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @RequestParam(required = false) Long amountMin,
        @RequestParam(required = false) Long amountMax) {
    if (!exportRateLimiter.tryAcquire(principal.userId())) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }
    LedgerFilter filter = new LedgerFilter(entryTypes, from, to, amountMin, amountMax);
    String filename = "slpa-wallet-ledger-" + principal.userId() + "-"
            + OffsetDateTime.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
    StreamingResponseBody body = out -> {
        try (Stream<UserLedgerEntry> rows =
                ledgerStreamingService.streamFiltered(principal.userId(), filter)) {
            LedgerCsvWriter.write(rows, out);
        }
    };
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body);
}
```

Add fields:
```java
private final LedgerStreamingService ledgerStreamingService;
private final LedgerExportRateLimiter exportRateLimiter;
```

Imports: `org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody`, `org.springframework.http.{HttpHeaders, HttpStatus, MediaType, ResponseEntity}`, `java.time.format.DateTimeFormatter`, `java.util.stream.Stream`.

- [ ] Step 3.4.1: Add the controller method + imports + fields.
- [ ] Step 3.4.2: `./mvnw compile -q -DskipTests` — green.
- [ ] Step 3.4.3: Slice test: hit the endpoint, assert Content-Type, Content-Disposition, header row + first data row in body.
- [ ] Step 3.4.4: Slice test: hit twice rapidly, assert second returns 429.
- [ ] Step 3.4.5: Commit `feat(wallet): GET /me/wallet/ledger/export.csv streaming + rate-limited`.

---

## Phase 4 — Frontend `/wallet` route + dashboard tab removal

### Task 4.1: Create `/wallet` page

**Files:**
- Create: `frontend/src/app/wallet/page.tsx`

```tsx
"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useCurrentUser } from "@/lib/user";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { WalletPanel } from "@/components/wallet/WalletPanel";

export default function WalletPage() {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (!user?.verified) router.replace("/dashboard/verify");
  }, [isPending, isError, user?.verified, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading wallet..." />;
  if (!user.verified) return <LoadingSpinner label="Redirecting..." />;

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 flex flex-col gap-6">
      <div>
        <h1 className="text-headline-md font-display font-bold">Wallet</h1>
        <p className="text-sm text-on-surface-variant mt-1">
          Deposit, withdraw, and view your SLPA wallet activity.
        </p>
      </div>
      <WalletPanel />
    </div>
  );
}
```

- [ ] Step 4.1.1: Write the file.
- [ ] Step 4.1.2: `npm run build` — green; new route `/wallet` appears in build output.
- [ ] Step 4.1.3: Commit `feat(frontend): /wallet top-level route`.

### Task 4.2: Remove dashboard wallet tab

**Files:**
- Delete: `frontend/src/app/dashboard/(verified)/wallet/page.tsx`
- Modify: `frontend/src/app/dashboard/(verified)/layout.tsx`

```tsx
const TABS: TabItem[] = [
  { id: "overview", label: "Overview", href: "/dashboard/overview" },
  { id: "bids", label: "My Bids", href: "/dashboard/bids" },
  { id: "listings", label: "My Listings", href: "/dashboard/listings" },
];
```

(Drop the `wallet` entry.)

- [ ] Step 4.2.1: Delete the file.
- [ ] Step 4.2.2: Edit the TABS array.
- [ ] Step 4.2.3: `npm run build` green.
- [ ] Step 4.2.4: Commit `refactor(frontend): drop /dashboard/wallet (now /wallet top-level)`.

---

## Phase 5 — Frontend `HeaderWalletIndicator` + Header/MobileMenu

### Task 5.1: Add NavLink to Header desktop nav

**Files:**
- Modify: `frontend/src/components/layout/Header.tsx`

Insert the `Wallet` NavLink between `/dashboard` and `/auction/new`:

```tsx
<nav className="hidden md:flex items-center gap-8">
  <NavLink variant="header" href="/browse">Browse</NavLink>
  <NavLink variant="header" href="/dashboard">Dashboard</NavLink>
  {status === "authenticated" && user.verified && (
    <NavLink variant="header" href="/wallet">Wallet</NavLink>
  )}
  <NavLink variant="header" href="/auction/new">Create Listing</NavLink>
  {status === "authenticated" && user.role === "ADMIN" && (
    <NavLink variant="header" href="/admin">Admin</NavLink>
  )}
</nav>
```

- [ ] Step 5.1.1: Apply the edit.
- [ ] Step 5.1.2: `npm run build` green.
- [ ] Step 5.1.3: Commit `feat(frontend): Wallet NavLink in desktop header (verified-only)`.

### Task 5.2: Add Wallet row to MobileMenu

**Files:**
- Modify: `frontend/src/components/layout/MobileMenu.tsx`

Read the file first to match the existing pattern. Add a `Wallet` row gated on `user.verified`. Show `Wallet · L$ <available>` if data is loaded; otherwise just `Wallet`. Use the same `useQuery(['me','wallet'], getWallet)` hook (extracted as `useWallet()` in Task 5.3).

- [ ] Step 5.2.1: Read MobileMenu.tsx, identify the row pattern.
- [ ] Step 5.2.2: Add the row using the verified gate.
- [ ] Step 5.2.3: `npm run build` green.
- [ ] Step 5.2.4: Commit `feat(frontend): Wallet row in mobile menu (verified-only)`.

### Task 5.3: Extract `useWallet()` React Query hook

**Files:**
- Create: `frontend/src/lib/wallet/use-wallet.ts`

```tsx
import { useQuery } from "@tanstack/react-query";
import { getWallet } from "@/lib/api/wallet";
import type { WalletView } from "@/types/wallet";

const WALLET_QUERY_KEY = ["me", "wallet"] as const;

export function useWallet(enabled: boolean = true) {
  return useQuery<WalletView>({
    queryKey: WALLET_QUERY_KEY,
    queryFn: getWallet,
    enabled,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
  });
}

export const walletQueryKey = WALLET_QUERY_KEY;
```

- [ ] Step 5.3.1: Write the hook.
- [ ] Step 5.3.2: Refactor `WalletPanel.tsx` to use `useWallet()` instead of its `useEffect`-based fetch.
- [ ] Step 5.3.3: `npm run build` green.
- [ ] Step 5.3.4: Commit `refactor(wallet): shared useWallet React Query hook`.

### Task 5.4: `HeaderWalletIndicator` component

**Files:**
- Create: `frontend/src/components/wallet/HeaderWalletIndicator.tsx`

```tsx
"use client";

import Link from "next/link";
import { Wallet, AlertTriangle } from "lucide-react";
import { useCurrentUser } from "@/lib/user";
import { useWallet } from "@/lib/wallet/use-wallet";
import { cn } from "@/lib/cn";

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

export function HeaderWalletIndicator() {
  const { data: user } = useCurrentUser();
  const verified = user?.verified === true;
  const { data: wallet } = useWallet(verified);

  if (!verified) return null;

  const available = wallet?.available ?? 0;
  const balance = wallet?.balance ?? 0;
  const reserved = wallet?.reserved ?? 0;
  const penaltyOwed = wallet?.penaltyOwed ?? 0;

  return (
    <Link
      href="/wallet"
      aria-label="Wallet"
      className={cn(
        "group relative flex items-center rounded-md transition-colors",
        "px-2 py-1 hover:bg-surface-container-low focus:outline-none",
        "focus-visible:ring-2 focus-visible:ring-primary"
      )}
    >
      <Wallet className="h-4 w-4 text-primary md:mr-1.5" aria-hidden />
      <span className="hidden md:inline text-sm font-medium tabular-nums text-on-surface">
        {formatLindens(available)}
      </span>
      {/* Hover popover (desktop only) */}
      <div
        role="tooltip"
        className={cn(
          "hidden md:block",
          "absolute right-0 top-full mt-1 w-64 z-50",
          "bg-surface-container rounded-lg shadow-lg p-3",
          "opacity-0 invisible pointer-events-none",
          "group-hover:opacity-100 group-hover:visible group-hover:pointer-events-auto",
          "group-focus-visible:opacity-100 group-focus-visible:visible group-focus-visible:pointer-events-auto",
          "transition-opacity"
        )}
      >
        <div className="text-xs uppercase tracking-wide text-on-surface-variant mb-2">
          Wallet
        </div>
        <dl className="text-sm space-y-1">
          <div className="flex justify-between">
            <dt className="text-on-surface-variant">Balance</dt>
            <dd className="tabular-nums text-on-surface">{formatLindens(balance)}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-on-surface-variant">Reserved</dt>
            <dd className="tabular-nums text-on-surface">{formatLindens(reserved)}</dd>
          </div>
          <div className="flex justify-between font-medium">
            <dt>Available</dt>
            <dd className="tabular-nums">{formatLindens(available)}</dd>
          </div>
        </dl>
        {penaltyOwed > 0 && (
          <div className="mt-3 flex gap-2 rounded-md bg-warning-container/40 border border-warning p-2 text-xs">
            <AlertTriangle className="h-4 w-4 text-warning shrink-0 mt-0.5" />
            <div>
              <div className="text-on-warning-container">
                Penalty owed: {formatLindens(penaltyOwed)}
              </div>
              <div className="mt-1 underline">Pay penalty →</div>
            </div>
          </div>
        )}
        <div className="mt-3 text-xs text-primary underline">View activity →</div>
      </div>
    </Link>
  );
}
```

(`Wallet` and `AlertTriangle` imports come from `lucide-react`. If the project funnels icons through `@/components/ui/icons.ts`, add them to that index instead.)

- [ ] Step 5.4.1: Check `frontend/src/components/ui/icons.ts` for existing wallet/alert exports; add if missing.
- [ ] Step 5.4.2: Write `HeaderWalletIndicator.tsx`.
- [ ] Step 5.4.3: Insert `<HeaderWalletIndicator />` in `Header.tsx` between `<div id="curator-tray-slot" />` and `<NotificationBell />`.
- [ ] Step 5.4.4: `npm run build` green.
- [ ] Step 5.4.5: `npm run verify` green (no dark variants, no hex colors, no inline styles).
- [ ] Step 5.4.6: Commit `feat(frontend): HeaderWalletIndicator in global header`.

---

## Phase 6 — Shared `<Modal>` + WalletPanel beautify

### Task 6.1: Shared `<Modal>` component

**Files:**
- Check: does `frontend/src/components/ui/Modal.tsx` (or similar like `Dialog.tsx`) already exist? Search: `find frontend/src/components/ui -iname "*modal*" -o -iname "*dialog*"`.
- If exists: skip this task; use it.
- If not: create `frontend/src/components/ui/Modal.tsx`:

```tsx
"use client";

import { useEffect, useRef } from "react";
import { cn } from "@/lib/cn";

export function Modal({
  open,
  title,
  onClose,
  children,
  footer,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  useEffect(() => {
    if (open && containerRef.current) {
      const focusable = containerRef.current.querySelector<HTMLElement>(
        "button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])"
      );
      focusable?.focus();
    }
  }, [open]);

  if (!open) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title}
      className={cn(
        "fixed inset-0 z-50 flex items-center justify-center",
        "bg-scrim/40"
      )}
      onClick={onClose}
    >
      <div
        ref={containerRef}
        className={cn(
          "bg-surface-container rounded-2xl p-6 max-w-md w-full",
          "max-h-[80vh] overflow-y-auto",
          "shadow-xl"
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold text-on-surface mb-4">{title}</h3>
        <div className="text-sm text-on-surface space-y-3">{children}</div>
        {footer && <div className="mt-4 flex justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );
}
```

- [ ] Step 6.1.1: Check for existing modal/dialog component.
- [ ] Step 6.1.2: If missing, write `Modal.tsx` per the above.
- [ ] Step 6.1.3: `npm run build` + `npm run verify` green.
- [ ] Step 6.1.4: Commit `feat(ui): shared Modal component (or skip if exists)`.

### Task 6.2: Refactor WalletPanel chrome to use `<Modal>` + design tokens

**Files:**
- Modify: `frontend/src/components/wallet/WalletPanel.tsx`

Apply the changes from spec §5:
1. Replace inner `<SimpleDialog>` with `<Modal>`.
2. Replace generic Tailwind colors with Material-3 tokens per the table in §5.2.
3. Restructure the balance display: `Available` as the dominant number, `Balance` + `Reserved` as smaller breakdown.
4. Replace the penalty card chrome with the warning-container token + `AlertTriangle` icon.
5. Replace bare `<input>` in dialogs with project's `<Input>` component (check `frontend/src/components/ui/`; if missing, build a thin wrapper around `<input>` with the right token classes).
6. Polish empty-state when no recent activity.

(Lengthy diff; the engineer reads spec §5 and applies it. Each numbered change is its own commit-able sub-step.)

- [ ] Step 6.2.1: Apply visual hierarchy refactor (dominant Available + breakdown).
- [ ] Step 6.2.2: Replace inline colors with Material-3 tokens (run `grep -nE "bg-amber|text-neutral|bg-black/|bg-white|text-red" WalletPanel.tsx` to find all sites; fix one at a time).
- [ ] Step 6.2.3: Replace `SimpleDialog` calls with `<Modal>`.
- [ ] Step 6.2.4: Replace `<input>` in dialogs with `<Input>` component.
- [ ] Step 6.2.5: Polish empty-state.
- [ ] Step 6.2.6: `npm run build` + `npm run verify` green.
- [ ] Step 6.2.7: Commit `refactor(wallet): WalletPanel beautify — tokens + hierarchy + Modal + Input`.

### Task 6.3: Add lucide icons + color coding to ledger rows

**Files:**
- Modify: `frontend/src/components/wallet/WalletPanel.tsx`

Apply spec §5.4 + §5.5. The icon mapping:

```tsx
import {
  ArrowDownToLine, ArrowUpFromLine, Clock, Undo2, Lock, Unlock,
  Tag, AlertTriangle, Pencil
} from "lucide-react";

function entryIcon(t: LedgerEntry["entryType"]): { Icon: any; tone: string } {
  switch (t) {
    case "DEPOSIT": return { Icon: ArrowDownToLine, tone: "text-success" };
    case "WITHDRAW_QUEUED": return { Icon: Clock, tone: "text-on-surface-variant" };
    case "WITHDRAW_COMPLETED": return { Icon: ArrowUpFromLine, tone: "text-on-surface" };
    case "WITHDRAW_REVERSED": return { Icon: Undo2, tone: "text-warning" };
    case "BID_RESERVED": return { Icon: Lock, tone: "text-warning" };
    case "BID_RELEASED": return { Icon: Unlock, tone: "text-on-surface-variant" };
    case "ESCROW_DEBIT": return { Icon: ArrowUpFromLine, tone: "text-on-surface" };
    case "ESCROW_REFUND": return { Icon: ArrowDownToLine, tone: "text-success" };
    case "LISTING_FEE_DEBIT": return { Icon: Tag, tone: "text-on-surface" };
    case "LISTING_FEE_REFUND": return { Icon: ArrowDownToLine, tone: "text-success" };
    case "PENALTY_DEBIT": return { Icon: AlertTriangle, tone: "text-warning" };
    case "ADJUSTMENT": return { Icon: Pencil, tone: "text-on-surface-variant" };
  }
}
```

Render `<Icon className={cn("h-4 w-4 mr-2", tone)} />` next to the entry-type label in the activity table. Color the amount column with the same tone.

- [ ] Step 6.3.1: Add the icon mapping.
- [ ] Step 6.3.2: Render icons in the activity table.
- [ ] Step 6.3.3: Color the amount column.
- [ ] Step 6.3.4: `npm run build` + `npm run verify` green.
- [ ] Step 6.3.5: Commit `feat(wallet): lucide icons + color coding on ledger rows`.

### Task 6.4: Polished date formatting

**Files:**
- Modify: `frontend/src/components/wallet/WalletPanel.tsx`

Use `date-fns` (already in package.json — verify with `grep date-fns frontend/package.json`):

```tsx
import { formatDistanceToNow, parseISO, differenceInHours } from "date-fns";

function formatLedgerDate(iso: string): string {
  const d = parseISO(iso);
  const hoursAgo = differenceInHours(new Date(), d);
  if (hoursAgo < 24) {
    return formatDistanceToNow(d, { addSuffix: true });
  }
  return d.toLocaleString(undefined, {
    year: "numeric", month: "short", day: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}
```

- [ ] Step 6.4.1: Verify `date-fns` is a dependency.
- [ ] Step 6.4.2: Add the `formatLedgerDate` helper.
- [ ] Step 6.4.3: Replace the existing `new Date(e.createdAt).toLocaleString()` calls.
- [ ] Step 6.4.4: `npm run build` green.
- [ ] Step 6.4.5: Commit `feat(wallet): relative-then-absolute date formatting on ledger rows`.

---

## Phase 7 — Ledger split + filter bar + pagination + export

### Task 7.1: Extract `<LedgerTable>`

**Files:**
- Create: `frontend/src/components/wallet/LedgerTable.tsx`
- Modify: `frontend/src/components/wallet/WalletPanel.tsx` (delegate the activity-table render)

Move the activity-table JSX from `WalletPanel` into `LedgerTable`. Props: `{ entries: LedgerEntry[]; isLoading?: boolean }`. Keep icon + color + date formatting.

- [ ] Step 7.1.1: Create `LedgerTable.tsx`.
- [ ] Step 7.1.2: Replace the inline JSX in WalletPanel with `<LedgerTable entries={...} />`.
- [ ] Step 7.1.3: `npm run build` green.
- [ ] Step 7.1.4: Commit `refactor(wallet): split LedgerTable component out of WalletPanel`.

### Task 7.2: Ledger API client + types

**Files:**
- Modify: `frontend/src/lib/api/wallet.ts`
- Modify: `frontend/src/types/wallet.ts`

Add to `wallet.ts` types:

```tsx
export interface LedgerFilter {
  entryTypes?: UserLedgerEntryType[];
  from?: string;       // ISO-8601
  to?: string;
  amountMin?: number;
  amountMax?: number;
}

export interface LedgerPage {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  entries: LedgerEntry[];
}
```

Add to `lib/api/wallet.ts`:

```tsx
export function getLedger(
  filter: LedgerFilter,
  page: number,
  size: number,
): Promise<LedgerPage> {
  const params = new URLSearchParams();
  params.set("page", String(page));
  params.set("size", String(size));
  if (filter.entryTypes?.length) {
    filter.entryTypes.forEach((t) => params.append("entryType", t));
  }
  if (filter.from) params.set("from", filter.from);
  if (filter.to) params.set("to", filter.to);
  if (filter.amountMin !== undefined) params.set("amountMin", String(filter.amountMin));
  if (filter.amountMax !== undefined) params.set("amountMax", String(filter.amountMax));
  return api.get<LedgerPage>(`/api/v1/me/wallet/ledger?${params.toString()}`);
}

export function ledgerExportUrl(filter: LedgerFilter): string {
  const params = new URLSearchParams();
  if (filter.entryTypes?.length) {
    filter.entryTypes.forEach((t) => params.append("entryType", t));
  }
  if (filter.from) params.set("from", filter.from);
  if (filter.to) params.set("to", filter.to);
  if (filter.amountMin !== undefined) params.set("amountMin", String(filter.amountMin));
  if (filter.amountMax !== undefined) params.set("amountMax", String(filter.amountMax));
  return `/api/v1/me/wallet/ledger/export.csv?${params.toString()}`;
}
```

- [ ] Step 7.2.1: Add types and API methods.
- [ ] Step 7.2.2: `npx tsc --noEmit` — no new errors.
- [ ] Step 7.2.3: Commit `feat(wallet): ledger pagination + filter API client`.

### Task 7.3: `<LedgerFilterBar>`

**Files:**
- Create: `frontend/src/components/wallet/LedgerFilterBar.tsx`

Renders chip toggles for entry types, two date inputs (`<input type="date">` for now; switch to project's `<DatePicker>` if it exists), two amount inputs, and Reset / Export buttons. Accepts `{ filter: LedgerFilter; onChange: (f: LedgerFilter) => void; onExport: () => void }` props.

Implementation guideline:
- Chip selection toggles items in `filter.entryTypes`.
- Date inputs convert local-date to ISO-8601 (`from` becomes start-of-day UTC, `to` becomes end-of-day UTC) before calling onChange.
- Amount inputs accept positive integers; debounce 300ms via `setTimeout` so typing doesn't fire requests on every keystroke.
- Reset clears every filter field.
- Export button calls `onExport` (which triggers a download via `<a href={url} download />` click).

- [ ] Step 7.3.1: Write `LedgerFilterBar.tsx`.
- [ ] Step 7.3.2: `npm run build` + `npm run verify` green.
- [ ] Step 7.3.3: Commit `feat(wallet): LedgerFilterBar with chip / date / amount filters + export`.

### Task 7.4: Wallet page-level ledger state with URL sync

**Files:**
- Modify: `frontend/src/components/wallet/WalletPanel.tsx`

Replace the use of `wallet.recentLedger` for the activity-table render with a paginated query backed by `getLedger()`:

```tsx
const searchParams = useSearchParams();
const router = useRouter();
const pathname = usePathname();

// Read filter + page from URL
const filter = readFilterFromParams(searchParams);
const page = parseInt(searchParams.get("page") ?? "0", 10);
const size = parseInt(searchParams.get("size") ?? "25", 10);

const { data: ledgerPage } = useQuery({
  queryKey: ["me", "wallet", "ledger", filter, page, size],
  queryFn: () => getLedger(filter, page, size),
  enabled: !!user?.verified,
});

const handleFilterChange = (next: LedgerFilter) => {
  const params = filterToUrlParams(next);
  params.set("page", "0");
  params.set("size", String(size));
  router.replace(`${pathname}?${params.toString()}`);
};

const handlePageChange = (nextPage: number) => {
  const params = new URLSearchParams(searchParams.toString());
  params.set("page", String(nextPage));
  router.replace(`${pathname}?${params.toString()}`);
};

const handleExport = () => {
  const url = ledgerExportUrl(filter);
  const a = document.createElement("a");
  a.href = url;
  a.download = "";
  a.click();
};
```

Add helpers `readFilterFromParams` and `filterToUrlParams` co-located in the same file (or in `lib/wallet/url.ts`).

- [ ] Step 7.4.1: Add filter/page URL-sync helpers.
- [ ] Step 7.4.2: Replace the recent-ledger render with the paginated query.
- [ ] Step 7.4.3: Render `<LedgerFilterBar>` + `<LedgerTable>` + `<Pagination>` in the page.
- [ ] Step 7.4.4: Wire `?penalty=open` auto-open (per spec §4.5).
- [ ] Step 7.4.5: `npm run build` + `npm run verify` green.
- [ ] Step 7.4.6: Commit `feat(wallet): paginated + filtered ledger view with URL state`.

### Task 7.5: Pagination component

**Files:**
- Check existence: `find frontend/src/components/ui -iname "*pagination*"`.
- If missing, create `frontend/src/components/ui/Pagination.tsx`:

```tsx
"use client";
import { Button } from "@/components/ui/Button";

export function Pagination({
  page, totalPages, onChange,
}: {
  page: number;
  totalPages: number;
  onChange: (p: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <nav className="flex items-center gap-2 justify-end mt-4">
      <Button
        variant="tertiary" size="sm"
        disabled={page === 0}
        onClick={() => onChange(page - 1)}
      >
        ← Prev
      </Button>
      <span className="text-sm text-on-surface-variant">
        Page {page + 1} of {totalPages}
      </span>
      <Button
        variant="tertiary" size="sm"
        disabled={page >= totalPages - 1}
        onClick={() => onChange(page + 1)}
      >
        Next →
      </Button>
    </nav>
  );
}
```

- [ ] Step 7.5.1: Check for existing Pagination; if missing, write the component.
- [ ] Step 7.5.2: Use it in WalletPanel's ledger section.
- [ ] Step 7.5.3: `npm run build` + `npm run verify` green.
- [ ] Step 7.5.4: Commit `feat(ui): Pagination component (or skip if exists)`.

---

## Phase 8 — WS subscription wiring

### Task 8.1: Identify the existing STOMP client hook

**Files:**
- Check: `find frontend/src/lib -iname "*stomp*" -o -iname "*ws*"`.

Find the existing STOMP-client hook used for live auction updates. Likely something like `useStompClient()` or a `WebSocketProvider`. Identify its API: how do you subscribe to a destination?

- [ ] Step 8.1.1: Locate the existing STOMP infrastructure file (e.g., `frontend/src/lib/ws/StompClientProvider.tsx`).
- [ ] Step 8.1.2: Read the file to understand the subscription API. Note the function name + signature.

### Task 8.2: `useWalletWsSubscription` hook

**Files:**
- Create: `frontend/src/lib/wallet/use-wallet-ws.ts`

Adapted from spec §7.3, but using whatever subscription primitive Task 8.1 surfaced:

```tsx
"use client";

import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useStompSubscribe } from "@/lib/ws/use-stomp-subscribe"; // or whatever it's actually named
import type { WalletView } from "@/types/wallet";
import { walletQueryKey } from "./use-wallet";

interface WalletBalanceChangedEnvelope {
  balance: number;
  reserved: number;
  available: number;
  penaltyOwed: number;
  reason: string;
  ledgerEntryId: number | null;
  occurredAt: string;
}

export function useWalletWsSubscription(enabled: boolean) {
  const qc = useQueryClient();

  useStompSubscribe(
    enabled ? "/user/queue/wallet" : null,
    (env: WalletBalanceChangedEnvelope) => {
      qc.setQueryData<WalletView>(walletQueryKey, (prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          balance: env.balance,
          reserved: env.reserved,
          available: env.available,
          penaltyOwed: env.penaltyOwed,
        };
      });
      qc.invalidateQueries({ queryKey: ["me", "wallet", "ledger"] });
    },
  );
}
```

(Adjust to match actual STOMP-subscription API discovered in Task 8.1. If the project's STOMP wrapper expects callback-style with frame parsing, parse the JSON manually.)

- [ ] Step 8.2.1: Write the hook against the actual STOMP API.
- [ ] Step 8.2.2: Use it in `<HeaderWalletIndicator>` (with `enabled={verified}`) and in `<WalletPanel>` (always enabled — page is verified-gated).
- [ ] Step 8.2.3: `npm run build` green.
- [ ] Step 8.2.4: Commit `feat(wallet): WS live updates via /user/queue/wallet subscription`.

---

## Phase 9 — Smoke + PR

### Task 9.1: Run full backend test suite

```bash
docker exec slpa-postgres-1 psql -U slpa -d slpa -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO PUBLIC;"
cd backend && ./mvnw test
```

Expected: 1591 + new tests, 0 failures, 0 errors.

- [ ] Step 9.1.1: Run + confirm.
- [ ] Step 9.1.2: Fix any breakage.

### Task 9.2: Run full frontend build + verify

```bash
cd frontend && npm run build && npm run verify && npm test
```

Expected: all green except the one pre-existing dark-variant warning in `StickyBidBar.tsx` (unchanged from earlier baseline).

- [ ] Step 9.2.1: Run + confirm.
- [ ] Step 9.2.2: Fix any breakage.

### Task 9.3: PR feat → dev → main

```bash
gh pr create --base dev --head feat/wallet-toplevel \
  --title "feat(wallet): top-level page + header indicator + beautify + ledger UX" \
  --body "$(cat <<EOF
## Summary

Implements docs/superpowers/specs/2026-05-01-wallet-toplevel-and-header-indicator-design.md.

- /wallet top-level route + Wallet NavLink (verified-only)
- HeaderWalletIndicator: icon + L\$ available, hover popover desktop, tap-to-route mobile
- WalletPanel beautify: visual hierarchy, Material-3 tokens, shared Modal, lucide icons + color coding
- Ledger: full pagination + filters (entry type, date range, amount range) + CSV export
- WebSocket live updates via /user/queue/wallet (publisher in WalletService, subscriber in indicator + panel)

## Test plan
- [x] ./mvnw test green
- [x] npm run build + verify green
- [ ] After deploy: GET /me/wallet/ledger returns paginated rows
- [ ] After deploy: deposit triggers WS envelope, indicator + panel update without polling
- [ ] After deploy: CSV export download triggers; second click within 60s returns 429
EOF
)"
```

After dev merge, repeat with `--base main --head dev`.

- [ ] Step 9.3.1: Open PR feat → dev. Note URL.
- [ ] Step 9.3.2: After user merges, open PR dev → main. Note URL.
- [ ] Step 9.3.3: Wait for backend deploy to complete (`gh run list --branch main --limit 1 --workflow="deploy backend"`).
- [ ] Step 9.3.4: Smoke test: hit `/me/wallet/ledger` (no params) and confirm the response shape; trigger an export and confirm the file downloads.

---

## Self-review

**Spec coverage:**

- §1 In-scope items — all 11 covered:
  - `/wallet` route → Phase 4
  - `Wallet` NavLink + MobileMenu row → Phase 5 (5.1, 5.2)
  - HeaderWalletIndicator (icon + popover + mobile) → Phase 5 (5.4)
  - WalletPanel beautify → Phase 6 (6.2, 6.3, 6.4)
  - Ledger pagination + filters → Phase 2 (backend) + Phase 7 (frontend)
  - CSV export → Phase 3 (backend) + Phase 7.4 (frontend trigger)
  - WS publish in WalletService → Phase 1 (1.3)
  - Frontend WS subscription → Phase 8
  - Polling/on-focus fallback → Phase 5.3 (useWallet hook)
  - Removed dashboard wallet tab → Phase 4 (4.2)
- §1 Excluded items — confirmed not in plan: no settings page, no description/ref-id search, no aggregations, no admin search.

**Placeholder scan:** No "TBD", "TODO", "implement later". Two "If exists / If missing" branches (Modal in 6.1, Pagination in 7.5, STOMP API in 8.1) are real lookups, not placeholders.

**Type consistency:**
- `WalletBalanceChangedEnvelope` referenced consistently — Phase 1 backend, Phase 8 frontend.
- `LedgerFilter` referenced consistently — Phase 2 backend, Phase 7 frontend.
- `useWallet` hook + `walletQueryKey` referenced consistently — Phase 5.3, Phase 8.
- `getLedger` / `ledgerExportUrl` referenced consistently — Phase 7.2, Phase 7.4.

**Scope:** One coherent implementation plan. ~9 phases of additive changes; no destructive migrations; no breaking changes to existing endpoints.
