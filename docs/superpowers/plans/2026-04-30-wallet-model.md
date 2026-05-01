# Wallet Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-obligation in-world payment flow (escrow / listing-fee / penalty paid at the terminal) with a per-user L$ wallet that supports deposits via lockless `money()` reentrancy, hard-reservations on bids, auto-funded escrow at auction close, refund-as-credit, and withdrawals routed to the user's verified SL avatar.

**Architecture:** Single feature branch (`feat/wallet-model` off `dev`) with destructive Flyway migrations (no live customers → schema-rollback safety not required). New `com.slparcelauctions.backend.wallet` package mirrors the established vertical-slice pattern. Existing `EscrowTransaction` per-escrow ledger stays; new `user_ledger` per-user append-only ledger sits above it; reconciliation job extends to validate both. LSL terminal rewrite uses single shared listen + per-flow withdraw slots (no terminal-wide lock). Path A rollout: iterate on prod, RDS snapshot before deploy, `git revert` if abandoned.

**Tech Stack:** Spring Boot 4 / Java 26 / Flyway / Hibernate (validate-mode) / Lombok / JUnit 5 / Mockito / Spring WebFlux WebClient / STOMP / Next.js 16 / React 19 / TypeScript 5 / React Query / Tailwind 4 / LSL (Mainland-only).

**Spec authority:** `docs/superpowers/specs/2026-04-30-wallet-model-design.md`.

---

## Phase ordering

```
Phase 1: Schema & entities          (compiles cleanly; no behavior yet)
Phase 2: WalletService primitives   (deposit, withdraw, debit, credit, swap)
Phase 3: SL-headers controllers     (deposit, withdraw-request)
Phase 4: User-facing controllers    (wallet view, withdraw, pay-penalty, accept-terms, pay-listing-fee)
Phase 5: Bid + BIN integration      (preconditions + reservation swap)
Phase 6: Auction-end auto-fund      (escrow auto-fund; ESCROW_PENDING retired)
Phase 7: Refund-as-credit           (escrow + listing-fee refunds → wallet credits)
Phase 8: Remove retired endpoints   (4 SL-headers payment endpoints)
Phase 9: Reconciliation extension   (wallet sums, denorm-drift)
Phase 10: Dormancy + login handler  (30d + 4 weekly IMs + auto-return)
Phase 11: Frontend wallet UI        (panel, dialogs, flow updates)
Phase 12: LSL rewrites              (terminal + verifier-giver)
Phase 13: Postman collection        (Wallet folder, retire 4 folders)
Phase 14: Documentation sweep       (READMEs, CONVENTIONS, FOOTGUNS, DEFERRED)
Phase 15: Smoke verification + PRs  (PR feat → dev, PR dev → main, smoke)
```

Phases 1-10 are backend; can run sequentially. Phase 11 (frontend) blocks on backend API shapes from phases 3-7. Phase 12 (LSL) blocks on phase 3 endpoint shapes. Phases 13-14 are wrap-up; can run in parallel with phase 12. Phase 15 is integration + handoff.

---

## Phase 1 — Schema & entities

### Task 1.1: Create Flyway migration `V<N>__wallet_balance_columns.sql`

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__wallet_balance_columns.sql` (use the next-available V-number — check the existing migration directory and pick next)

**Acceptance:** Migration adds the wallet columns to `users` with constraints and indexes. Hibernate `validate` mode passes after entity additions in Task 1.2.

**Migration content:**
```sql
ALTER TABLE users
    ADD COLUMN balance_lindens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reserved_lindens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN wallet_dormancy_started_at TIMESTAMPTZ NULL,
    ADD COLUMN wallet_dormancy_phase SMALLINT NULL,
    ADD COLUMN wallet_terms_accepted_at TIMESTAMPTZ NULL,
    ADD COLUMN wallet_terms_version VARCHAR(16) NULL;

ALTER TABLE users
    ADD CONSTRAINT users_balance_nonneg CHECK (balance_lindens >= 0),
    ADD CONSTRAINT users_reserved_nonneg CHECK (reserved_lindens >= 0),
    ADD CONSTRAINT users_balance_ge_reserved CHECK (balance_lindens >= reserved_lindens),
    ADD CONSTRAINT users_dormancy_phase_check
        CHECK (wallet_dormancy_phase IS NULL OR wallet_dormancy_phase BETWEEN 1 AND 99);
```

`99` is reserved for `COMPLETED`; valid runtime values are 1-4 plus 99.

- [ ] Step 1.1.1: Verify next available `V<N>` number by listing existing migrations.
- [ ] Step 1.1.2: Create the migration file with the SQL above.
- [ ] Step 1.1.3: Run backend test suite — Hibernate validate will fail because entities don't have these fields yet (expected; Task 1.2 fixes).
- [ ] Step 1.1.4: Commit: `feat(wallet): add balance/reserved/dormancy columns to users`.

### Task 1.2: Add wallet columns to `User` entity

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java` (extend with `balanceLindens`, `reservedLindens`, `availableLindens` if it makes sense, or keep wallet response separate per Task 4.1)

**Code additions to `User.java`:**
```java
@Column(name = "balance_lindens", nullable = false)
@Builder.Default
private Long balanceLindens = 0L;

@Column(name = "reserved_lindens", nullable = false)
@Builder.Default
private Long reservedLindens = 0L;

@Column(name = "wallet_dormancy_started_at")
private OffsetDateTime walletDormancyStartedAt;

@Column(name = "wallet_dormancy_phase")
private Integer walletDormancyPhase;

@Column(name = "wallet_terms_accepted_at")
private OffsetDateTime walletTermsAcceptedAt;

@Column(name = "wallet_terms_version", length = 16)
private String walletTermsVersion;

@Transient
public long availableLindens() {
    return balanceLindens - reservedLindens;
}
```

- [ ] Step 1.2.1: Add the columns and the `availableLindens()` derived getter.
- [ ] Step 1.2.2: Run backend tests — should pass `validate`. Existing tests should still work since defaults are `0L`.
- [ ] Step 1.2.3: Commit: `feat(wallet): add wallet fields to User entity`.

### Task 1.3: Create `user_ledger` migration + `UserLedgerEntry` entity + repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__user_ledger.sql`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntry.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java` (enum)
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerRepository.java`

**Migration:** see spec §3.2 SQL block. Verbatim.

**Enum values:**
```
DEPOSIT, WITHDRAW_QUEUED, WITHDRAW_COMPLETED, WITHDRAW_REVERSED,
BID_RESERVED, BID_RELEASED,
ESCROW_DEBIT, ESCROW_REFUND,
LISTING_FEE_DEBIT, LISTING_FEE_REFUND,
PENALTY_DEBIT,
ADJUSTMENT
```

**Entity skeleton:**
```java
@Entity
@Table(name = "user_ledger", indexes = {
    @Index(name = "user_ledger_user_created_idx", columnList = "user_id, created_at DESC")
})
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserLedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private UserLedgerEntryType entryType;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(name = "reserved_after", nullable = false)
    private Long reservedAfter;

    @Column(name = "ref_type", length = 32)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "sl_transaction_id", length = 36, unique = true)
    private String slTransactionId;

    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by_admin_id")
    private Long createdByAdminId;
}
```

**Repository:**
```java
public interface UserLedgerRepository extends JpaRepository<UserLedgerEntry, Long> {
    Optional<UserLedgerEntry> findBySlTransactionId(String slTransactionId);
    Optional<UserLedgerEntry> findByIdempotencyKey(String idempotencyKey);
    List<UserLedgerEntry> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
    Page<UserLedgerEntry> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
```

- [ ] Step 1.3.1: Write the migration SQL.
- [ ] Step 1.3.2: Write the enum, entity, repository.
- [ ] Step 1.3.3: Slice test (`@DataJpaTest`): insert two `DEPOSIT` rows for same user — assert both persist; assert UNIQUE constraint on `sl_transaction_id` (insert two with same sl_transaction_id → constraint violation).
- [ ] Step 1.3.4: Commit: `feat(wallet): add user_ledger table + entity + repository`.

### Task 1.4: Create `bid_reservations` migration + entity + repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__bid_reservations.sql`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/BidReservation.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/BidReservationReleaseReason.java` (enum)
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/BidReservationRepository.java`

**Migration:** see spec §3.3 SQL block. Verbatim.

**Enum:**
```
OUTBID, AUCTION_CANCELLED, AUCTION_FRAUD_FREEZE, ESCROW_FUNDED, USER_BANNED
```

(Plus `AUCTION_BIN_ENDED` — adding here per spec §6.3 to release other users' reservations cleanly when a BIN closes the auction. Update the migration's CHECK constraint to include this value.)

**Repository:**
```java
public interface BidReservationRepository extends JpaRepository<BidReservation, Long> {
    @Query("SELECT br FROM BidReservation br
            WHERE br.auctionId = :auctionId AND br.releasedAt IS NULL")
    Optional<BidReservation> findActiveForAuction(@Param("auctionId") Long auctionId);

    @Query("SELECT br FROM BidReservation br
            WHERE br.userId = :userId AND br.releasedAt IS NULL")
    List<BidReservation> findActiveByUser(@Param("userId") Long userId);

    @Query("SELECT br FROM BidReservation br
            WHERE br.auctionId = :auctionId AND br.releasedAt IS NULL")
    List<BidReservation> findAllActiveForAuction(@Param("auctionId") Long auctionId);

    @Query("SELECT COALESCE(SUM(br.amount), 0) FROM BidReservation br
            WHERE br.userId = :userId AND br.releasedAt IS NULL")
    long sumActiveByUser(@Param("userId") Long userId);

    @Query("SELECT br.userId, COALESCE(SUM(br.amount), 0) FROM BidReservation br
            WHERE br.releasedAt IS NULL GROUP BY br.userId")
    List<Object[]> sumActiveGroupedByUser();
}
```

- [ ] Step 1.4.1: Write migration SQL with the partial unique index.
- [ ] Step 1.4.2: Write the enum, entity, repository.
- [ ] Step 1.4.3: Slice test: insert active reservation for (userA, auctionX), confirm second insert for same pair fails the partial unique index. Insert second reservation after marking first `released_at=now` — should succeed.
- [ ] Step 1.4.4: Commit: `feat(wallet): add bid_reservations table + entity + repository`.

### Task 1.5: Reconciliation schema extension

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__reconciliation_extension.sql`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationRun.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationStatus.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationStatusCheckConstraintInitializer.java`

**Migration:**
```sql
ALTER TABLE reconciliation_runs
    ADD COLUMN wallet_balance_total BIGINT NULL,
    ADD COLUMN wallet_reserved_total BIGINT NULL,
    ADD COLUMN escrow_locked_total BIGINT NULL;

ALTER TABLE reconciliation_runs
    DROP CONSTRAINT IF EXISTS reconciliation_runs_status_check,
    ADD CONSTRAINT reconciliation_runs_status_check
        CHECK (status IN ('BALANCED', 'DRIFT', 'ERROR', 'DENORM_DRIFT'));
```

**Entity:** add the three columns. Update the `ReconciliationStatus` enum to include `DENORM_DRIFT`. Update the constraint initializer if it programmatically refers to the constraint values.

- [ ] Step 1.5.1: Write migration SQL.
- [ ] Step 1.5.2: Update entity, enum, initializer.
- [ ] Step 1.5.3: Slice test: insert run row with `status=DENORM_DRIFT` succeeds; with `status=INVALID` fails.
- [ ] Step 1.5.4: Commit: `feat(reconciliation): add wallet sums + DENORM_DRIFT status`.

### Task 1.6: Retire ESCROW_PENDING + payment_deadline + listing_fee_refunds.terminal_command_id

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__retire_escrow_pending_state.sql`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowState.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java` (drop the `findExpiredPending` query)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTimeoutJob.java` (drop the pending-payment branch)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/dto/EscrowStatusResponse.java` (drop `paymentDeadline` field)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/broadcast/EscrowCreatedEnvelope.java` (drop `paymentDeadline` field)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/listingfee/ListingFeeRefund.java` (drop `terminalCommandId` field — to be confirmed by reading the entity)

**Migration:**
```sql
-- Defensive: convert any existing in-flight REFUND TerminalCommands to wallet credits.
-- Expected count = 0 in current dev/prod state.
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT * FROM terminal_commands WHERE action = 'REFUND' AND status IN ('QUEUED', 'IN_FLIGHT', 'FAILED')
    LOOP
        RAISE NOTICE 'WARN: pre-wallet REFUND command id=% will not be migrated; review manually', rec.id;
    END LOOP;
END $$;

-- Defensive cleanup of stale ESCROW_PENDING rows (expected count 0).
DELETE FROM escrows WHERE state = 'ESCROW_PENDING';

-- Update escrow state CHECK to remove ESCROW_PENDING.
ALTER TABLE escrows
    DROP CONSTRAINT IF EXISTS escrows_state_check,
    ADD CONSTRAINT escrows_state_check
        CHECK (state IN ('FUNDED', 'TRANSFER_PENDING', 'COMPLETED',
                         'DISPUTED', 'FROZEN', 'EXPIRED'));

-- Drop the payment_deadline column.
ALTER TABLE escrows DROP COLUMN payment_deadline;
DROP INDEX IF EXISTS ix_escrows_payment_deadline;

-- Drop the listing_fee_refunds.terminal_command_id column.
ALTER TABLE listing_fee_refunds DROP COLUMN IF EXISTS terminal_command_id;
```

**EscrowState enum changes:** remove the `ESCROW_PENDING` constant.

**EscrowTimeoutJob changes:**
```java
@Scheduled(...)
public void sweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    // ESCROW_PENDING removed: nothing to expire on the payment-deadline path.
    escrowRepo.findExpiredTransferPending(now).forEach(timeoutTask::expireTransfer);
}
```

- [ ] Step 1.6.1: Write the migration with defensive cleanup.
- [ ] Step 1.6.2: Drop `ESCROW_PENDING` from `EscrowState` enum.
- [ ] Step 1.6.3: Drop `findExpiredPending` from `EscrowRepository`.
- [ ] Step 1.6.4: Update `EscrowTimeoutJob.sweep()` to remove the expired-pending branch.
- [ ] Step 1.6.5: Drop `paymentDeadline` field + getter/builder method from `Escrow.java`. Search-and-replace any references; deal with breakage.
- [ ] Step 1.6.6: Drop `paymentDeadline` field from `EscrowStatusResponse` DTO and `EscrowCreatedEnvelope`.
- [ ] Step 1.6.7: Drop `terminalCommandId` from `ListingFeeRefund` (verify field exists; if it does, drop it).
- [ ] Step 1.6.8: Run `./mvnw compile` — fix any compile errors from removed enum value / column.
- [ ] Step 1.6.9: Run full test suite — fix breakage. Tests that referenced `ESCROW_PENDING` or `paymentDeadline` need updates per the new model (auto-fund flow in phase 6).
- [ ] Step 1.6.10: Commit: `feat(escrow): retire ESCROW_PENDING state and payment_deadline column`.

### Task 1.7: Trim TerminalCommand REFUND action (defensive)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandAction.java` (or whatever the enum is named)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java` (or dispatcher) — add a `CRITICAL` log path for `REFUND` action arrivals (defensive; per spec §9.3 keep for one release cycle)

**Note:** Don't drop `REFUND` from the enum value list — keep it defensively. The dispatcher's switch should log `CRITICAL: unexpected REFUND action received; refunds are now wallet credits` and mark the command FAILED with a clear errorMessage. After one release cycle this can be cleaned up in a follow-up PR.

- [ ] Step 1.7.1: Update the dispatcher's switch to handle `REFUND` defensively (log CRITICAL, mark FAILED).
- [ ] Step 1.7.2: Add a unit test asserting the defensive branch fires correctly.
- [ ] Step 1.7.3: Commit: `feat(terminal): defensive REFUND-action handling for wallet model`.

---

## Phase 2 — WalletService primitives

The new `com.slparcelauctions.backend.wallet` package houses all wallet operations. This phase builds the primitives; callers in subsequent phases use them.

### Task 2.1: WalletService interface + skeleton

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java` (the impl could be the same class — Spring service with `@Service`)
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/exception/InsufficientAvailableBalanceException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/exception/PenaltyOutstandingException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/exception/AmountExceedsOwedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/exception/UserNotLinkedException.java` (used by `/sl/wallet/deposit` for unknown payerUuid path)
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/exception/UserStatusBlockedException.java` (banned/frozen)
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/exception/IdempotentReplayResult.java` (sealed-marker class for caller-aware replay handling)

**Service skeleton:**
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {
    private final UserRepository userRepository;
    private final UserLedgerRepository ledgerRepository;
    private final BidReservationRepository reservationRepository;
    private final TerminalCommandService terminalCommandService;
    private final Clock clock;

    @Transactional
    public DepositResult deposit(...) {...}

    @Transactional
    public WithdrawQueuedResult withdrawSiteInitiated(...) {...}

    @Transactional
    public WithdrawQueuedResult withdrawTouchInitiated(...) {...}

    @Transactional
    public PenaltyDebitResult payPenalty(...) {...}

    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationSwapResult swapReservation(...) {...}

    @Transactional(propagation = Propagation.MANDATORY)
    public EscrowAutoFundResult autoFundEscrow(...) {...}

    @Transactional(propagation = Propagation.MANDATORY)
    public void creditEscrowRefund(...) {...}

    @Transactional(propagation = Propagation.MANDATORY)
    public void creditListingFeeRefund(...) {...}

    @Transactional(propagation = Propagation.MANDATORY)
    public void debitListingFee(...) {...}
}
```

The `MANDATORY` propagation marks methods that callers must invoke inside an existing transaction (because they assume locks are held).

- [ ] Step 2.1.1: Create the package, service skeleton, exception classes.
- [ ] Step 2.1.2: Create result-type DTOs co-located in the wallet package (`DepositResult`, `WithdrawQueuedResult`, `PenaltyDebitResult`, `ReservationSwapResult`, `EscrowAutoFundResult`).
- [ ] Step 2.1.3: Wire the service via Spring with no method bodies yet (return null / throw UnsupportedOperationException). Compile.
- [ ] Step 2.1.4: Commit: `feat(wallet): scaffold WalletService package and exception types`.

### Task 2.2: `WalletService.deposit()`

**Files:**
- Modify: `WalletService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/WalletServiceDepositTest.java`

**Method:**
```java
@Transactional
public DepositResult deposit(
    UUID payerUuid, long amount, String slTransactionKey, Long terminalId
) {
    // 1. Idempotency replay
    Optional<UserLedgerEntry> existing = ledgerRepository.findBySlTransactionId(slTransactionKey);
    if (existing.isPresent()) {
        return DepositResult.replay(existing.get());
    }

    // 2. User lookup
    User user = userRepository.findBySlAvatarUuid(payerUuid)
        .orElseThrow(() -> new UserNotLinkedException(payerUuid));

    // 3. Status check
    if (user.getStatus() == UserStatus.BANNED || user.getStatus() == UserStatus.FROZEN) {
        throw new UserStatusBlockedException(user.getId(), user.getStatus());
    }

    // 4. Lock + credit
    User locked = userRepository.findByIdForUpdate(user.getId()).orElseThrow();
    long newBalance = locked.getBalanceLindens() + amount;
    locked.setBalanceLindens(newBalance);
    userRepository.save(locked);

    // 5. Append ledger row
    UserLedgerEntry entry = UserLedgerEntry.builder()
        .userId(locked.getId())
        .entryType(UserLedgerEntryType.DEPOSIT)
        .amount(amount)
        .balanceAfter(newBalance)
        .reservedAfter(locked.getReservedLindens())
        .slTransactionId(slTransactionKey)
        .createdAt(OffsetDateTime.now(clock))
        .build();
    ledgerRepository.save(entry);

    return DepositResult.ok(locked, entry);
}
```

(Need `userRepository.findByIdForUpdate(...)` — if it doesn't exist on the repository, add it: `@Lock(LockModeType.PESSIMISTIC_WRITE) Optional<User> findByIdForUpdate(@Param("id") Long id)`.)

**Tests:** matrix of:
- Happy path: known verified user, new sl_transaction_id → balance increases, ledger row appended.
- Replay: same sl_transaction_id called twice → second call returns the original DepositResult; balance only increases once.
- Unknown payerUuid → `UserNotLinkedException`.
- Banned user → `UserStatusBlockedException`.
- Frozen user → `UserStatusBlockedException`.

- [ ] Step 2.2.1: Add `findByIdForUpdate` to `UserRepository` if missing.
- [ ] Step 2.2.2: Write `WalletServiceDepositTest` with the matrix above (Mockito-based unit tests).
- [ ] Step 2.2.3: Implement the method. All tests pass.
- [ ] Step 2.2.4: Commit: `feat(wallet): WalletService.deposit() with idempotent replay`.

### Task 2.3: `WalletService.withdrawSiteInitiated()` and `withdrawTouchInitiated()`

**Files:**
- Modify: `WalletService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/WalletServiceWithdrawTest.java`

**Method (site-initiated):**
```java
@Transactional
public WithdrawQueuedResult withdrawSiteInitiated(
    Long userId, long amount, String idempotencyKey
) {
    // Idempotency replay
    Optional<UserLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) return WithdrawQueuedResult.replay(existing.get());

    User user = userRepository.findByIdForUpdate(userId).orElseThrow();
    if (user.getStatus() == BANNED || user.getStatus() == FROZEN) {
        throw new UserStatusBlockedException(userId, user.getStatus());
    }
    if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
    if (user.availableLindens() < amount) {
        throw new InsufficientAvailableBalanceException(user.availableLindens(), amount);
    }

    // Debit
    user.setBalanceLindens(user.getBalanceLindens() - amount);
    userRepository.save(user);

    // Ledger
    UserLedgerEntry entry = UserLedgerEntry.builder()
        .userId(userId)
        .entryType(UserLedgerEntryType.WITHDRAW_QUEUED)
        .amount(amount)
        .balanceAfter(user.getBalanceLindens())
        .reservedAfter(user.getReservedLindens())
        .idempotencyKey(idempotencyKey)
        .createdAt(OffsetDateTime.now(clock))
        .build();
    ledgerRepository.save(entry);

    // Queue terminal command
    TerminalCommand cmd = terminalCommandService.queueWithdraw(
        user.getSlAvatarUuid(), amount, /* idempotency_key auto */
    );

    return WithdrawQueuedResult.ok(entry, cmd);
}
```

**Method (touch-initiated):** identical shape but takes `slTransactionKey` instead of `idempotencyKey`, looks up user by `slAvatarUuid` from the request, returns `REFUND_BLOCKED` semantics where appropriate (caller maps to wire response).

**Tests:**
- Happy path (site).
- Happy path (touch).
- Replay (idempotencyKey → same result; sl_transaction_id → same).
- Insufficient balance → exception.
- Banned/frozen user → exception.
- Negative amount → IllegalArgumentException.

- [ ] Step 2.3.1: Confirm `TerminalCommandService.queueWithdraw(...)` exists or create it.
- [ ] Step 2.3.2: Write tests.
- [ ] Step 2.3.3: Implement both methods. Tests pass.
- [ ] Step 2.3.4: Commit: `feat(wallet): WalletService withdraw paths (site + touch)`.

### Task 2.4: `WalletService.payPenalty()`

**Files:**
- Modify: `WalletService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/WalletServicePayPenaltyTest.java`

**Method:**
```java
@Transactional
public PenaltyDebitResult payPenalty(Long userId, long amount, String idempotencyKey) {
    Optional<UserLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) return PenaltyDebitResult.replay(existing.get());

    if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");

    User user = userRepository.findByIdForUpdate(userId).orElseThrow();
    if (amount > user.getPenaltyBalanceOwed()) {
        throw new AmountExceedsOwedException(user.getPenaltyBalanceOwed(), amount);
    }
    if (user.availableLindens() < amount) {
        throw new InsufficientAvailableBalanceException(user.availableLindens(), amount);
    }

    user.setBalanceLindens(user.getBalanceLindens() - amount);
    user.setPenaltyBalanceOwed(user.getPenaltyBalanceOwed() - amount);
    userRepository.save(user);

    UserLedgerEntry entry = UserLedgerEntry.builder()
        .userId(userId)
        .entryType(UserLedgerEntryType.PENALTY_DEBIT)
        .amount(amount)
        .balanceAfter(user.getBalanceLindens())
        .reservedAfter(user.getReservedLindens())
        .idempotencyKey(idempotencyKey)
        .refType("PENALTY")
        .createdAt(OffsetDateTime.now(clock))
        .build();
    ledgerRepository.save(entry);

    return PenaltyDebitResult.ok(user, entry);
}
```

**Tests:** full payment, partial payment, exceeds-owed, insufficient-available, reserved-blocks-payment (user with all balance reserved cannot pay), idempotent replay.

- [ ] Step 2.4.1: Write tests.
- [ ] Step 2.4.2: Implement method.
- [ ] Step 2.4.3: Commit: `feat(wallet): WalletService.payPenalty() with available-balance gate`.

### Task 2.5: `WalletService.swapReservation()`

**Files:**
- Modify: `WalletService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/WalletServiceSwapReservationTest.java`

**Method:** see spec §5.4 signature. Key invariants:
- Caller must have already locked auction + relevant user rows in ascending order.
- Validates `newBidder.availableLindens() >= newBidAmount`.
- If `priorReservation != null`: marks released, decrements prior user's `reserved_lindens`, appends `BID_RELEASED` ledger row.
- Inserts new `BidReservation`, increments new bidder's `reserved_lindens`, appends `BID_RESERVED` ledger row.

**Tests (Mockito + DataJpaTest mix):**
- No prior bidder, sufficient balance → reserves cleanly.
- Prior bidder same as new bidder (self-outbid) — should be guarded at the BidService level, but defensive: `swapReservation` accepts `priorReservation.userId == newBidder.id` and produces a clean swap (release prior, reserve new).
- Prior bidder different from new — both ledger rows for the right users.
- Insufficient available → throws.
- Negative amount → IllegalArgumentException.

- [ ] Step 2.5.1: Write tests.
- [ ] Step 2.5.2: Implement method.
- [ ] Step 2.5.3: Commit: `feat(wallet): WalletService.swapReservation() for bid hard-reservation`.

### Task 2.6: `WalletService.autoFundEscrow()`

**Files:**
- Modify: `WalletService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/WalletServiceAutoFundEscrowTest.java`

**Method:**
```java
@Transactional(propagation = MANDATORY)
public EscrowAutoFundResult autoFundEscrow(Auction auction, User winner, long finalBidAmount, Long escrowId) {
    // Caller has locked auction + winner.
    BidReservation reservation = reservationRepository.findActiveForAuction(auction.getId())
        .orElseThrow(() -> new IllegalStateException(
            "auto-fund called without active reservation: auctionId=" + auction.getId()));
    if (reservation.getUserId() != winner.getId()) {
        throw new IllegalStateException(
            "active reservation user != winner: reservation.userId=" + reservation.getUserId()
            + " winner.id=" + winner.getId());
    }
    if (reservation.getAmount() != finalBidAmount) {
        throw new BidReservationAmountMismatchException(reservation.getAmount(), finalBidAmount);
    }

    // Release reservation
    reservation.setReleasedAt(OffsetDateTime.now(clock));
    reservation.setReleaseReason(BidReservationReleaseReason.ESCROW_FUNDED);
    reservationRepository.save(reservation);

    // Decrement reserved + balance
    winner.setReservedLindens(winner.getReservedLindens() - reservation.getAmount());
    winner.setBalanceLindens(winner.getBalanceLindens() - finalBidAmount);
    userRepository.save(winner);

    // Two ledger rows
    appendLedger(winner, UserLedgerEntryType.BID_RELEASED, reservation.getAmount(), "BID", reservation.getBidId());
    appendLedger(winner, UserLedgerEntryType.ESCROW_DEBIT, finalBidAmount, "ESCROW", escrowId);

    return EscrowAutoFundResult.ok();
}
```

`BidReservationAmountMismatchException` is a `RuntimeException` flagged for "system-integrity bug; freeze escrow" handling at the call site.

**Tests:** happy path, no-active-reservation defensive branch, reservation-user-mismatch defensive branch, amount-mismatch defensive branch.

- [ ] Step 2.6.1: Add `BidReservationAmountMismatchException`.
- [ ] Step 2.6.2: Write tests.
- [ ] Step 2.6.3: Implement method.
- [ ] Step 2.6.4: Commit: `feat(wallet): WalletService.autoFundEscrow() for auction-end auto-funding`.

### Task 2.7: Refund + listing-fee credit primitives

**Files:**
- Modify: `WalletService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/WalletServiceCreditTest.java`

**Methods:**
```java
@Transactional(propagation = MANDATORY)
public void creditEscrowRefund(User user, long amount, Long escrowId) {
    user.setBalanceLindens(user.getBalanceLindens() + amount);
    userRepository.save(user);
    appendLedger(user, UserLedgerEntryType.ESCROW_REFUND, amount, "ESCROW", escrowId);
}

@Transactional(propagation = MANDATORY)
public void creditListingFeeRefund(User user, long amount, Long listingFeeRefundId) {
    user.setBalanceLindens(user.getBalanceLindens() + amount);
    userRepository.save(user);
    appendLedger(user, UserLedgerEntryType.LISTING_FEE_REFUND, amount, "LISTING_FEE_REFUND", listingFeeRefundId);
}

@Transactional(propagation = MANDATORY)
public void debitListingFee(User user, long amount, Long auctionId) {
    if (user.availableLindens() < amount) {
        throw new InsufficientAvailableBalanceException(user.availableLindens(), amount);
    }
    user.setBalanceLindens(user.getBalanceLindens() - amount);
    userRepository.save(user);
    appendLedger(user, UserLedgerEntryType.LISTING_FEE_DEBIT, amount, "AUCTION", auctionId);
}
```

- [ ] Step 2.7.1: Write tests.
- [ ] Step 2.7.2: Implement methods.
- [ ] Step 2.7.3: Commit: `feat(wallet): credit + debit primitives for escrow refund / listing fee`.

### Task 2.8: Reservation release helper for cancellation/freeze/ban paths

**Files:**
- Modify: `WalletService.java`

**Method:**
```java
@Transactional(propagation = MANDATORY)
public void releaseReservationsForAuction(Long auctionId, BidReservationReleaseReason reason) {
    List<BidReservation> active = reservationRepository.findAllActiveForAuction(auctionId);
    for (BidReservation r : active) {
        User u = userRepository.findByIdForUpdate(r.getUserId()).orElseThrow();
        r.setReleasedAt(OffsetDateTime.now(clock));
        r.setReleaseReason(reason);
        reservationRepository.save(r);
        u.setReservedLindens(u.getReservedLindens() - r.getAmount());
        userRepository.save(u);
        appendLedger(u, UserLedgerEntryType.BID_RELEASED, r.getAmount(), "BID", r.getBidId());
    }
}

@Transactional(propagation = MANDATORY)
public void releaseAllReservationsForUser(Long userId, BidReservationReleaseReason reason) {
    List<BidReservation> active = reservationRepository.findActiveByUser(userId);
    User u = userRepository.findByIdForUpdate(userId).orElseThrow();
    for (BidReservation r : active) {
        r.setReleasedAt(OffsetDateTime.now(clock));
        r.setReleaseReason(reason);
        reservationRepository.save(r);
        u.setReservedLindens(u.getReservedLindens() - r.getAmount());
        appendLedger(u, UserLedgerEntryType.BID_RELEASED, r.getAmount(), "BID", r.getBidId());
    }
    userRepository.save(u);
}
```

- [ ] Step 2.8.1: Implement helpers + tests covering both methods.
- [ ] Step 2.8.2: Commit: `feat(wallet): release-reservations helpers for cancellation/ban paths`.

---

## Phase 3 — SL-headers controllers

### Task 3.1: `/sl/wallet/deposit` controller

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/SlWalletDepositController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/SlWalletDepositRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/SlWalletDepositResponse.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/sl/SlWalletDepositControllerSliceTest.java`

**Controller pattern:** mirror existing SL controllers (e.g., `EscrowPaymentController`). Validate headers via `SlHeaderValidator` filter, validate `sharedSecret` body field, validate `terminalId` lookup, then delegate to `WalletService.deposit()`. Map exceptions to wire responses.

**Wire responses (from spec §4.1):**
- `OK { status: "OK" }`
- `REFUND { status: "REFUND", reason: "UNKNOWN_PAYER" | "USER_FROZEN", message: "..." }`
- `ERROR { status: "ERROR", reason: "BAD_HEADERS" | "SECRET_MISMATCH" | "UNKNOWN_TERMINAL", message: "..." }`

**Slice test matrix:**
- 200 OK on valid request.
- 200 OK with replay shape on duplicate `slTransactionKey`.
- 200 with `REFUND/UNKNOWN_PAYER` on unknown UUID.
- 200 with `REFUND/USER_FROZEN` on banned user.
- 200 with `ERROR/SECRET_MISMATCH` on bad secret.
- 200 with `ERROR/UNKNOWN_TERMINAL` on bad terminalId.
- 403 with `ERROR/BAD_HEADERS` on missing SL headers.

- [ ] Step 3.1.1: Write the request DTO with validation annotations.
- [ ] Step 3.1.2: Write the response DTO.
- [ ] Step 3.1.3: Write the controller, including exception → wire-response mapping.
- [ ] Step 3.1.4: Write the slice test with the full matrix.
- [ ] Step 3.1.5: Run slice test — all green.
- [ ] Step 3.1.6: Commit: `feat(wallet): /sl/wallet/deposit controller + slice tests`.

### Task 3.2: `/sl/wallet/withdraw-request` controller

**Files:** mirror Task 3.1 structure (`SlWalletWithdrawRequestController`, request, response, slice test).

**Wire responses (from spec §4.1):**
- `OK { status: "OK", queueId: 42 }`
- `REFUND_BLOCKED { status: "REFUND_BLOCKED", reason: "INSUFFICIENT_BALANCE" | "USER_FROZEN" | "NOT_LINKED", message: "..." }`
- `ERROR { ... }`

**Note:** new wire-response status value `REFUND_BLOCKED` distinct from `REFUND`. Document this in the spec ref + controller javadoc.

- [ ] Step 3.2.1: Write request/response DTOs.
- [ ] Step 3.2.2: Write controller.
- [ ] Step 3.2.3: Write slice test matrix.
- [ ] Step 3.2.4: Commit: `feat(wallet): /sl/wallet/withdraw-request controller + slice tests`.

---

## Phase 4 — User-facing controllers

### Task 4.1: `GET /api/v1/me/wallet` + `/me/wallet/ledger`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/MeWalletController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/WalletViewResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/UserLedgerEntryDto.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/me/MeWalletControllerSliceTest.java`

**Response shape** per spec §4.2.

- [ ] Step 4.1.1: Write the response DTOs.
- [ ] Step 4.1.2: Write the controller (returns the user's wallet state and recent 50 ledger entries; cursor-paginated for older entries).
- [ ] Step 4.1.3: Slice test: authenticated user gets correct response; unauth → 401; banned user still gets read-only view.
- [ ] Step 4.1.4: Commit: `feat(wallet): GET /me/wallet + /me/wallet/ledger`.

### Task 4.2: `POST /me/wallet/withdraw`

**Files:**
- Modify: `MeWalletController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/WithdrawRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/WithdrawResponse.java`

Body: `{ amount, idempotencyKey }`. Recipient is **never** in the body.

- [ ] Step 4.2.1: Write request/response DTOs.
- [ ] Step 4.2.2: Add controller method; delegate to `WalletService.withdrawSiteInitiated()`.
- [ ] Step 4.2.3: Slice test: 202 happy path with queueId; 422 on insufficient available; 403 on banned/frozen; replay on duplicate idempotencyKey.
- [ ] Step 4.2.4: Commit: `feat(wallet): POST /me/wallet/withdraw`.

### Task 4.3: `POST /me/wallet/pay-penalty`

**Files:**
- Modify: `MeWalletController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/PayPenaltyRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/PayPenaltyResponse.java`

- [ ] Step 4.3.1: DTOs + controller method.
- [ ] Step 4.3.2: Slice test: full payment clears, partial payment leaves remainder, exceeds-owed → 422, insufficient-available → 422, fully-reserved-balance scenario → 422.
- [ ] Step 4.3.3: Commit: `feat(wallet): POST /me/wallet/pay-penalty`.

### Task 4.4: `POST /me/wallet/accept-terms`

**Files:**
- Modify: `MeWalletController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/AcceptTermsRequest.java`

Body: `{ termsVersion }`. Stamps `wallet_terms_accepted_at` + `wallet_terms_version`. Returns 200.

- [ ] Step 4.4.1: DTO + method.
- [ ] Step 4.4.2: Slice test: idempotent (re-accepting same version is a no-op).
- [ ] Step 4.4.3: Commit: `feat(wallet): POST /me/wallet/accept-terms`.

### Task 4.5: `POST /me/auctions/{id}/pay-listing-fee`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/PayListingFeeController.java` (or fold into `AuctionController` — recommend separate, in the wallet package since this is a wallet operation)
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/PayListingFeeRequest.java` (just `idempotencyKey`)
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/PayListingFeeResponse.java`

**Behavior** per spec §4.3 listing-fee subsection:
1. JWT → user.
2. Find auction; verify `seller_user_id == user.id` AND `state == DRAFT`.
3. Lock user.
4. Validate `user.penalty_balance_owed == 0` → 422 `PENALTY_OUTSTANDING`.
5. Validate `available >= auction.listing_fee_amount` → 422 `INSUFFICIENT_AVAILABLE_BALANCE`.
6. `WalletService.debitListingFee(user, listingFeeAmount, auctionId)`.
7. Append `escrow_transactions{type=LISTING_FEE_PAYMENT, status=COMPLETED}`.
8. Transition auction `DRAFT → DRAFT_PAID`.
9. WS envelope `LISTING_FEE_PAID` (existing or new envelope name; use whatever Epic 03/05 already publishes).

- [ ] Step 4.5.1: Read existing AuctionService listing-fee transition logic to understand the touch points.
- [ ] Step 4.5.2: Write controller + DTOs.
- [ ] Step 4.5.3: Slice test matrix: happy, penalty-outstanding, insufficient-available, wrong-state (not DRAFT), wrong-seller (not owner), already-paid (DRAFT_PAID).
- [ ] Step 4.5.4: Commit: `feat(wallet): POST /me/auctions/{id}/pay-listing-fee`.

---

## Phase 5 — Bid + BIN integration

### Task 5.1: Add penalty + balance preconditions to `BidService.placeBid()`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java` (or whatever class owns bid placement)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/PenaltyOutstandingException.java` (create if missing)
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/BidServiceTest.java`

**Changes:**
- After existing auction-state validation: add penalty precondition and available-balance precondition.
- Call `walletService.swapReservation(auction, newBidder, amount, prior, bid)` after bid persistence.
- Lock both newBidder and priorBidder (if any) in ascending user_id order before the wallet call.

**Lock-ordering helper:** `userRepository.findAllByIdInForUpdateAscending(Set<Long> ids)` or use a Java-side sort + multiple `findByIdForUpdate` calls.

- [ ] Step 5.1.1: Add the precondition checks.
- [ ] Step 5.1.2: Add the user-lock acquisition in ascending order.
- [ ] Step 5.1.3: Wire `swapReservation` call.
- [ ] Step 5.1.4: Update existing tests to use the new wallet-service collaborator (mock it). Add new tests for the precondition matrix.
- [ ] Step 5.1.5: Integration test: deposit user with L$1000, bid L$500, confirm reserved=L$500, available=L$500.
- [ ] Step 5.1.6: Commit: `feat(bid): add penalty + balance preconditions and reservation swap`.

### Task 5.2: Apply same pattern to `BinService.buyNow()`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BinService.java` (or whatever owns BIN; could be `AuctionService.buyNow()`)
- Modify tests.

**Behavior** per spec §6.3:
- Validate penalty == 0.
- Validate `available + (existing-reservation-on-this-auction-by-this-user) >= bin_price`.
- If existing reservation: release it (`ESCROW_FUNDED` reason; decrement reserved; `BID_RELEASED` row).
- Debit balance for `bin_price`. Append `ESCROW_DEBIT` row.
- Persist BIN bid + transition auction to `ENDED+BOUGHT_NOW`.
- Insert escrow row in state `FUNDED`. Append `escrow_transactions{type=AUCTION_ESCROW_PAYMENT}`.
- Release **other** active reservations on this auction (`AUCTION_BIN_ENDED` reason).

- [ ] Step 5.2.1: Read the existing BIN handler to understand current shape.
- [ ] Step 5.2.2: Implement the new flow.
- [ ] Step 5.2.3: Test matrix: BIN with no prior reservation, BIN with own prior reservation at lower amount, BIN with insufficient available even counting prior reservation, BIN releases other bidders' reservations.
- [ ] Step 5.2.4: Integration test: 3 bidders on auction, lowest-bidder clicks BIN — BIN succeeds, all 3 reservations released cleanly with correct release_reasons, escrow funded.
- [ ] Step 5.2.5: Commit: `feat(bin): wallet-driven BIN with auto-funded escrow`.

---

## Phase 6 — Auction-end auto-fund

### Task 6.1: `AuctionEndTask.closeOne` — auto-fund on SOLD

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionEndTask.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java` (escrow row creation)
- Modify tests.

**Changes per spec §6.1:**
- For `endOutcome=SOLD`: call `walletService.autoFundEscrow(...)` inside the existing close transaction, then create the escrow row directly in `FUNDED` state (not `ESCROW_PENDING` — that state is gone).
- On `BidReservationAmountMismatchException`: catch + freeze the new escrow as `FROZEN, reason=BID_RESERVATION_AMOUNT_MISMATCH` (system-integrity bug, defensive branch).

- [ ] Step 6.1.1: Update `AuctionEndTask.closeOne` and `EscrowService.create` for the new flow.
- [ ] Step 6.1.2: Update tests that asserted `ESCROW_PENDING` initial state — now expect `FUNDED`.
- [ ] Step 6.1.3: Add test for the defensive freeze branch.
- [ ] Step 6.1.4: Integration test: bid (reserves), close (auto-funds), confirm escrow in `TRANSFER_PENDING` immediately, ledger consistent.
- [ ] Step 6.1.5: Commit: `feat(escrow): auto-fund at auction-end via reservation consumption`.

### Task 6.2: Auction cancellation/freeze releases reservations

**Files:**
- Modify: cancellation handler (find via grep `auction.*cancel|cancelAuction|setState.*CANCELLED`)
- Modify: fraud-freeze handler

**Behavior per spec §6.4:** call `walletService.releaseReservationsForAuction(auctionId, reason)` inside the cancellation transaction. If the auction also has a funded escrow, follow existing escrow cancellation → refund path (which Phase 7 converts to wallet credit).

- [ ] Step 6.2.1: Find cancel/freeze entry points.
- [ ] Step 6.2.2: Add the reservation-release call.
- [ ] Step 6.2.3: Tests: 2-bidder auction cancelled → both reservations released, both users' available restored.
- [ ] Step 6.2.4: Commit: `feat(auction): cancellation releases active reservations`.

### Task 6.3: User ban releases reservations + handles funded escrows

**Files:**
- Modify: ban-action handler (likely in admin / Epic 10 code).

**Behavior per spec §6.5:** ban handler calls `walletService.releaseAllReservationsForUser(userId, USER_BANNED)`. Funded escrows where the banned user is winner enter the existing dispute disposition path.

- [ ] Step 6.3.1: Add the call to the ban handler.
- [ ] Step 6.3.2: Tests for the integration.
- [ ] Step 6.3.3: Commit: `feat(admin): user ban releases active wallet reservations`.

---

## Phase 7 — Refund-as-credit

### Task 7.1: Escrow refund → wallet credit

**Files:**
- Modify: escrow refund handler — find via grep `EscrowState.*EXPIRED|escrowRefund|AUCTION_ESCROW_REFUND`
- Modify: dispute resolution refund path

**Behavior per spec §7.1:** instead of queueing a `TerminalCommand{action=REFUND}`, call `walletService.creditEscrowRefund(winner, escrow.finalBidAmount, escrow.id)` inside the same transaction. Append `escrow_transactions{type=AUCTION_ESCROW_REFUND, status=COMPLETED}`.

- [ ] Step 7.1.1: Find existing refund path. Replace TerminalCommand-queue with wallet credit.
- [ ] Step 7.1.2: Tests for both escrow expiry and dispute-resolution refund.
- [ ] Step 7.1.3: Integration test: escrow expires (transfer deadline missed), winner's wallet receives refund, no TerminalCommand queued.
- [ ] Step 7.1.4: Commit: `feat(escrow): refund-as-credit instead of TerminalCommand REFUND`.

### Task 7.2: Listing-fee refund → wallet credit

**Files:**
- Modify: `ListingFeeRefundProcessor` (or whatever Epic 05 sub-spec 1 named it)

**Behavior per spec §7.2:** instead of queueing TerminalCommand, call `walletService.creditListingFeeRefund(seller, refund.amount, refund.id)`.

- [ ] Step 7.2.1: Update the processor.
- [ ] Step 7.2.2: Tests.
- [ ] Step 7.2.3: Commit: `feat(listing-fee): refund-as-credit`.

---

## Phase 8 — Remove retired endpoints

### Task 8.1: Delete the four SL-headers payment controllers

**Files (all to be deleted):**
- `EscrowPaymentController.java` and its DTOs.
- `ListingFeePaymentController.java` and its DTOs.
- `PenaltyLookupController.java` and its DTOs.
- `PenaltyPaymentController.java` and its DTOs.
- All related slice tests.

Plus any service-layer methods that only existed to serve these controllers. Search for callers.

- [ ] Step 8.1.1: Delete each controller + its tests + its DTOs.
- [ ] Step 8.1.2: Remove unused service methods.
- [ ] Step 8.1.3: Run full test suite — fix any breakage from leftover references.
- [ ] Step 8.1.4: Commit: `refactor: remove retired SL payment endpoints (replaced by wallet)`.

### Task 8.2: Rename dev-profile listing-fee stub

**Files:**
- Modify: dev controller that handles `/dev/auctions/{id}/pay`. Rename to `/dev/auctions/{id}/mark-listing-fee-paid`.

- [ ] Step 8.2.1: Rename endpoint path.
- [ ] Step 8.2.2: Update Postman + tests.
- [ ] Step 8.2.3: Commit: `refactor(dev): rename /pay to /mark-listing-fee-paid for clarity`.

---

## Phase 9 — Reconciliation extension

### Task 9.1: Extend `ReconciliationService.runDaily()`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationService.java`
- Modify: tests.

**Changes per spec §11:**
1. Add denorm precheck: compare `SUM(users.reserved_lindens)` vs `SUM(bid_reservations.amount WHERE released_at IS NULL)`. Mismatch → record `DENORM_DRIFT` and abort main check.
2. Add `sumWalletBalances() = SUM(users.balance_lindens)` and `sumActiveReservations() = SUM(bid_reservations.amount WHERE released_at IS NULL)`.
3. Update expected total: `expected = sumLockedEscrows() + sumWalletBalances()`. (Reservations already inside wallet balance — do not add separately.)
4. Persist breakdown columns: `wallet_balance_total`, `wallet_reserved_total`, `escrow_locked_total`.

- [ ] Step 9.1.1: Add the new sum methods.
- [ ] Step 9.1.2: Add denorm precheck.
- [ ] Step 9.1.3: Update `expected_total` calculation.
- [ ] Step 9.1.4: Tests: synthetic balance drift → BALANCED=false; synthetic reserved drift → DENORM_DRIFT.
- [ ] Step 9.1.5: Commit: `feat(reconciliation): extend daily run with wallet sums + denorm precheck`.

---

## Phase 10 — Dormancy + login handler

### Task 10.1: `WalletDormancyJob` + `WalletDormancyTask`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/dormancy/WalletDormancyJob.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/dormancy/WalletDormancyTask.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/dormancy/UserRepositoryDormancyExtension.java` (or extension queries on UserRepository)
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/dormancy/WalletDormancyJobTest.java`

**Behavior per spec §12.2:**
- Weekly cron `0 0 4 * * MON` UTC.
- Phase 1 (flag newly-dormant): finds users with `last refresh_tokens.created_at > 30d ago` AND `balance_lindens > 0` AND `wallet_dormancy_phase IS NULL` AND no active reservations AND no funded escrows. Stamps phase=1, sends IM #1.
- Phases 2/3/4: escalating; sends IM #N.
- Phase 4 → COMPLETED: queues `TerminalCommand{action=WITHDRAW}` for full balance, debits balance, append `WITHDRAW_QUEUED` ledger row, stamps phase=99.
- Re-checks liveness (refresh-token query) before any phase transition (defense in depth).

- [ ] Step 10.1.1: Add repository queries for the dormancy candidate query.
- [ ] Step 10.1.2: Implement `WalletDormancyJob.sweep()`.
- [ ] Step 10.1.3: Implement `WalletDormancyTask.flag()` and `escalateOrAutoReturn()`.
- [ ] Step 10.1.4: Tests: full flow with clock fast-forward.
- [ ] Step 10.1.5: Tests: user with active bid reservation excluded.
- [ ] Step 10.1.6: Tests: user logs in mid-cycle → liveness check clears state.
- [ ] Step 10.1.7: Commit: `feat(dormancy): WalletDormancyJob with 30d threshold and 4 weekly IMs`.

### Task 10.2: SL IM templates for dormancy

**Files:**
- Modify: Epic 09 SL IM dispatcher integration to publish `WALLET_DORMANCY_*` notification categories.
- Add IM message templates to wherever Epic 09 owns content.

- [ ] Step 10.2.1: Wire dormancy events to the SL IM dispatcher.
- [ ] Step 10.2.2: Add templates per spec §12.4.
- [ ] Step 10.2.3: Commit: `feat(notifications): wallet dormancy IM templates`.

### Task 10.3: Login + refresh handler clears dormancy state

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java` (or login handler)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenService.java`

**Change:** on successful login or refresh-token rotation, if `user.walletDormancyPhase != null`, set it to null along with `walletDormancyStartedAt`.

- [ ] Step 10.3.1: Add the clear-on-success logic.
- [ ] Step 10.3.2: Tests.
- [ ] Step 10.3.3: Commit: `feat(auth): clear wallet dormancy state on login/refresh`.

---

## Phase 11 — Frontend wallet UI

### Task 11.1: Wallet panel on dashboard

**Files:**
- Create: `frontend/src/components/wallet/WalletPanel.tsx`
- Create: `frontend/src/lib/wallet/use-wallet.ts` (React Query hook)
- Create: `frontend/src/lib/wallet/types.ts` (TypeScript types matching backend DTOs)
- Modify: dashboard page to render WalletPanel.

**Panel content:**
- Balance / Reserved / Available (3-cell display).
- Penalty owed (if > 0, with warning styling).
- "Pay Penalty" button (opens dialog).
- "Withdraw" button (opens dialog).
- "How to deposit" link (opens info modal with terminal instructions).
- Recent ledger entries (collapsible table, last 50).

- [ ] Step 11.1.1: Define TypeScript types.
- [ ] Step 11.1.2: Build the React Query hook.
- [ ] Step 11.1.3: Build the panel component.
- [ ] Step 11.1.4: Run `npm run verify` (no-emojis, no-dark-variants, no-hex-colors, no-inline-styles).
- [ ] Step 11.1.5: Commit: `feat(frontend): wallet dashboard panel`.

### Task 11.2: Withdraw dialog

**Files:**
- Create: `frontend/src/components/wallet/WithdrawDialog.tsx`
- Modify: `use-wallet.ts` to add `useWithdraw()` mutation.

**Behavior:** form with amount input, confirms `amount <= available`, posts to `/me/wallet/withdraw` with client-generated `idempotencyKey` (UUID). Shows success toast with `queueId`, closes dialog, refetches wallet.

- [ ] Step 11.2.1: Build dialog.
- [ ] Step 11.2.2: Wire to `useWithdraw` mutation.
- [ ] Step 11.2.3: Tests with React Testing Library.
- [ ] Step 11.2.4: Commit: `feat(frontend): withdraw dialog`.

### Task 11.3: Pay-penalty dialog

**Files:**
- Create: `frontend/src/components/wallet/PayPenaltyDialog.tsx`

**Behavior:** form with amount (default = `min(penaltyOwed, available)`), validates `amount <= penaltyOwed && amount <= available`. If `available < penaltyOwed`, show explicit hint "Deposit L$X more or wait for active bids to resolve."

- [ ] Step 11.3.1-4: Same pattern as 11.2.

### Task 11.4: Deposit-instructions modal + terms gate

**Files:**
- Create: `frontend/src/components/wallet/DepositInstructionsModal.tsx`
- Create: `frontend/src/components/wallet/AcceptTermsModal.tsx`
- Modify: `WalletPanel.tsx` to gate the deposit-instructions modal on `walletTermsAccepted`.

**Behavior:** before showing the deposit-instructions modal for the first time, show the AcceptTermsModal with terms text + checkbox + "I accept" button posting to `/me/wallet/accept-terms`. Once accepted, show the deposit-instructions modal (terminal locations + how to right-click + pay).

- [ ] Step 11.4.1-4: Build both modals + gating.

### Task 11.5: Bid form + BIN form precondition surfacing

**Files:**
- Modify: existing bid form component(s).
- Modify: existing BIN component(s).

**Changes:**
- Show user's current `available` next to the bid amount field.
- On submit, if backend returns 422 `PENALTY_OUTSTANDING` or `INSUFFICIENT_AVAILABLE_BALANCE`, render contextual error messaging.
- BIN button shows "L$X (you have L$Y available)" — disabled if `available + thisAuctionReservation < bin_price`.

- [ ] Step 11.5.1-3: Modify forms + tests.

### Task 11.6: Listing fee step in publish flow

**Files:**
- Modify: existing draft → DRAFT_PAID UI (find via grep "Listing fee").

**Changes:**
- Add a "Pay Listing Fee from Wallet" button that posts to `/me/auctions/{id}/pay-listing-fee`.
- Show available balance + listing fee amount.
- On 422 PENALTY_OUTSTANDING, link to "Pay your outstanding penalty first" CTA.

- [ ] Step 11.6.1-3: Modify the listing-flow UI.

### Task 11.7: Legal terms page

**Files:**
- Create: `frontend/src/app/legal/terms/page.tsx`

**Content:** the wallet terms-of-use draft from spec §13.1.

- [ ] Step 11.7.1: Build page with section headings + content.
- [ ] Step 11.7.2: Add nav link in footer.
- [ ] Step 11.7.3: Commit: `feat(frontend): /legal/terms page with wallet terms-of-use`.

---

## Phase 12 — LSL rewrites

### Task 12.1: Rewrite `slpa-terminal.lsl`

**Files:**
- Modify: `lsl-scripts/slpa-terminal/slpa-terminal.lsl` (mostly rewritten)
- Modify: `lsl-scripts/slpa-terminal/config.notecard.example` (URL paths)
- Modify: `lsl-scripts/slpa-terminal/README.md` (mostly rewritten)

**Key new shape per spec §9.1, 9.2, 9.3:**
- Steady-state: pay-default + quick-pay buttons, always live; floating text changed.
- `money()` handler: lockless, generate txKey, POST to `/sl/wallet/deposit`, retry budget, REFUND/ERROR discrimination.
- Touch: per-toucher dialog channel, `[Deposit, Withdraw]` buttons.
- Withdraw: per-flow slots in a strided list, single shared listen at startup, dispatch by avatar key on listen events.
- HTTP-in (PAYOUT/WITHDRAW): unchanged shape; defensive REFUND-action handler that owner-says CRITICAL.
- "Get Parcel Verifier" menu option **removed**; verifier inventory item removed from prim.

Concrete LSL skeleton outline (the implementation is ~600 lines; below is the structure):

```lsl
// === Notecard config ===
string  REGISTER_URL, DEPOSIT_URL, WITHDRAW_REQUEST_URL, PAYOUT_RESULT_URL, SHARED_SECRET, TERMINAL_ID;
integer DEBUG_MODE = TRUE;

// === Listen state ===
integer mainListenChan;       // single shared listen for menu + withdraw flow
integer mainListenHandle = -1;

// === Per-flow withdraw slots (strided 3-wide: avatarKey, amount, expiresAt) ===
list withdrawSessions = [];
integer MAX_WITHDRAW_SESSIONS = 4;
integer SESSION_TTL_SECONDS = 60;

// === Background payment retry (deposit) ===
key paymentReqId; key paymentPayer; integer paymentAmount;
string paymentTxKey; integer paymentRetryCount; integer paymentNextRetryAt;

// === HTTP-in inflight (PAYOUT/WITHDRAW) — unchanged ===
list inflightCmdTxKeys, inflightCmdIdempotencyKeys, inflightCmdRecipients, inflightCmdAmounts;
integer MAX_INFLIGHT_CMDS = 16;

default {
    state_entry() {
        // load notecard, request URL, request DEBIT permission, register, etc.
        // open the single shared listen
        mainListenChan = -100000 - (integer)(llFrand(50000.0));
        mainListenHandle = llListen(mainListenChan, "", NULL_KEY, "");
        // floating text + pay price defaults
        llSetPayPrice(PAY_DEFAULT, [100, 500, 1000, 5000]);
        llSetText("SLPA Terminal\nRight-click → Pay to deposit\nTouch for menu", <1,1,1>, 1.0);
    }

    money(key payer, integer amount) {
        // generate txKey, POST /sl/wallet/deposit, set up retry
    }

    touch_start(integer num) {
        key toucher = llDetectedKey(0);
        llDialog(toucher, "What would you like to do?", ["Deposit", "Withdraw"], mainListenChan);
    }

    listen(integer chan, string name, key id, string msg) {
        if (chan != mainListenChan) return;
        // 1. Check if msg is "Deposit" or "Withdraw" (top-level menu response)
        // 2. Check if id has a withdraw slot — dispatch by slot state (-1 = awaiting amount, >0 = awaiting confirm)
    }

    timer() {
        // sweep expired withdraw slots
        // run deposit-retry backoff
        // run register-retry backoff
        // sweep stale inflight HTTP-in commands
    }

    http_response(key req, integer status, list meta, string body) {
        // dispatch by req key: deposit response, register response, withdraw-request response, payout-result response
    }

    http_request(key req, string method, string body) {
        // backend-initiated PAYOUT/WITHDRAW; existing flow
    }

    transaction_result(key id, integer success, string data) {
        // post payout-result for the inflight command
    }

    changed(integer change) {
        // CHANGED_REGION_START → re-request URL
        // CHANGED_INVENTORY → llResetScript()
    }
}
```

The full implementation expands each block per the spec.

- [ ] Step 12.1.1: Read the existing `slpa-terminal.lsl` carefully so the rewrite preserves grid-guard, register/retry, region-restart, listen-cleanup discipline.
- [ ] Step 12.1.2: Write the new script. Replace existing payment-kind-and-auctionId state with the lockless deposit + per-flow withdraw model.
- [ ] Step 12.1.3: Update `config.notecard.example` to point at `/sl/wallet/deposit` and `/sl/wallet/withdraw-request` URL paths instead of the four old ones.
- [ ] Step 12.1.4: Rewrite the README to reflect the new behavior, menu structure, deposit flow, withdraw flow.
- [ ] Step 12.1.5: Commit: `feat(lsl): rewrite slpa-terminal.lsl for wallet model`.

### Task 12.2: New `slpa-verifier-giver` script + prim

**Files:**
- Create: `lsl-scripts/slpa-verifier-giver/slpa-verifier-giver.lsl`
- Create: `lsl-scripts/slpa-verifier-giver/config.notecard.example`
- Create: `lsl-scripts/slpa-verifier-giver/README.md`
- Modify: `lsl-scripts/README.md` (top-level index — add entry)

**Script:** per spec §9.4. Touch-to-receive, header-trust only, no shared secret, per-avatar rate-limit (60s).

```lsl
string FALLBACK_TEXT = "SLPA Parcel Verifier — Free\nTouch to receive";
integer DEBUG_MODE = TRUE;
integer RATE_LIMIT_SECONDS = 60;

list givenSessions = [];  // [avatarKey, lastGivenAt, ...]

default {
    state_entry() {
        // Mainland-only grid guard
        if (llGetEnv("sim_channel") != "Second Life Server") {
            llOwnerSay("CRITICAL: not on Second Life Server grid; halting.");
            return;
        }
        llSetText(FALLBACK_TEXT, <1,1,1>, 1.0);
    }

    touch_start(integer num) {
        key toucher = llDetectedKey(0);
        // rate-limit check
        integer slot = llListFindList(givenSessions, [toucher]);
        integer now = llGetUnixTime();
        if (slot != -1) {
            integer lastAt = llList2Integer(givenSessions, slot + 1);
            if (now - lastAt < RATE_LIMIT_SECONDS) {
                llRegionSayTo(toucher, 0, "Just gave you one — wait a minute.");
                return;
            }
            givenSessions = llListReplaceList(givenSessions, [toucher, now], slot, slot + 1);
        } else {
            givenSessions += [toucher, now];
        }
        llGiveInventory(toucher, "SLPA Parcel Verifier");
        if (DEBUG_MODE) llOwnerSay("SLPA Verifier Giver: gave verifier to " + llDetectedName(0));
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) llResetScript();
    }
}
```

- [ ] Step 12.2.1: Write the script.
- [ ] Step 12.2.2: Write the README (purpose, deployment locations, configuration, operations, security).
- [ ] Step 12.2.3: Update top-level `lsl-scripts/README.md` index.
- [ ] Step 12.2.4: Commit: `feat(lsl): SLPA Parcel Verifier Giver — touch-to-receive, free`.

---

## Phase 13 — Postman collection

### Task 13.1: Add Wallet folder + retire 4 folders

**Files:**
- Modify: Postman collection JSON (committed to repo? Verify location — may be in `docs/postman/` or similar).

**Per spec §15.1, 15.2:** add Wallet folder with 7 requests (variable-chained), remove `Escrow Payment`, `Listing Fee Payment`, `Penalty Lookup`, `Penalty Payment` folders.

- [ ] Step 13.1.1: Find the Postman collection JSON in the repo (or update via Postman MCP).
- [ ] Step 13.1.2: Add new requests; remove old folders.
- [ ] Step 13.1.3: Update env vars (add wallet-related, remove obsolete).
- [ ] Step 13.1.4: Commit: `feat(postman): rebuild collection for wallet model`.

---

## Phase 14 — Documentation sweep

### Task 14.1: LSL READMEs

**Files:**
- `lsl-scripts/slpa-terminal/README.md` (covered in 12.1).
- `lsl-scripts/slpa-verifier-giver/README.md` (covered in 12.2).
- `lsl-scripts/README.md` (covered in 12.2).

### Task 14.2: Implementation docs

**Files:**
- Modify: `docs/implementation/CONVENTIONS.md` — append wallet conventions:
  - Immutable ledger discipline.
  - `available = balance - reserved` invariant.
  - Recipient-UUID-locked outbound.
  - `MANDATORY` propagation for service-layer methods that assume locks held.
- Modify: `docs/implementation/FOOTGUNS.md` — add wallet footguns surfaced during build.
- Modify: `docs/implementation/DEFERRED_WORK.md` — add items from spec §17 (admin manual ledger adjustments, history export, etc.).
- Modify: root `README.md` — sweep for staleness re: terminal payment flow.

- [ ] Step 14.2.1: Append sections to each doc.
- [ ] Step 14.2.2: Commit: `docs: wallet conventions, footguns, deferred items, root README sweep`.

---

## Phase 15 — Smoke verification + PRs

### Task 15.1: Run full backend test suite

```bash
cd backend && ./mvnw test
```

Expected: all green.

- [ ] Step 15.1.1: Run + confirm green.
- [ ] Step 15.1.2: If any failures, fix before proceeding.

### Task 15.2: Run frontend tests + verify guards

```bash
cd frontend && npm test && npm run verify
```

- [ ] Step 15.2.1: Run + confirm green.
- [ ] Step 15.2.2: Fix breakage.

### Task 15.3: Open PR `feat/wallet-model` → `dev`

```bash
gh pr create --base dev --title "feat: wallet model — user L$ wallet, hard reservations, auto-funded escrow" --body "$(cat <<'EOF'
## Summary

Replaces the per-obligation in-world payment flow (escrow / listing-fee / penalty
paid at the terminal) with a per-user L$ wallet. See
`docs/superpowers/specs/2026-04-30-wallet-model-design.md` for full design.

Highlights:
- Lockless deposits via terminal `money()` reentrancy
- Hard bid reservations (eliminates "winner stiffs seller" failure mode)
- Escrow auto-funds at auction close from reservation
- Refunds collapse to wallet credits (no more `TerminalCommand{action=REFUND}`)
- Withdrawals route to user's verified SL avatar (locked at verification)
- New per-flow withdraw slots in LSL (lockless, no terminal-wide griefing surface)
- Separate `slpa-verifier-giver` prim — fixes two-place rule for parcel verifier updates

## Test plan
- [x] Backend `./mvnw test` green
- [x] Frontend `npm test && npm run verify` green
- [ ] Postman collection updated (Wallet folder; 4 SL-payment folders removed)
- [ ] Manual smoke procedures listed in design spec §14.3
EOF
)"
```

- [ ] Step 15.3.1: Push final commits.
- [ ] Step 15.3.2: Run `gh pr create` with the body above.
- [ ] Step 15.3.3: Note the PR URL for the user.

### Task 15.4: After user merges feat → dev, open PR `dev → main`

```bash
git checkout dev
git pull origin dev
gh pr create --base main --title "release: wallet model" --body "..."
```

- [ ] Step 15.4.1: Open PR.
- [ ] Step 15.4.2: Note URL for user.

### Task 15.5: After user merges dev → main, smoke-test prod

After merge, GHA auto-deploys backend; Amplify auto-deploys frontend.

Smoke procedure:

```bash
# 1. Confirm backend health
curl https://api.slparcels.com/actuator/health

# 2. Confirm new endpoints exist (will return 401 without JWT — that's fine, proves they're routed)
curl -X GET https://api.slparcels.com/api/v1/me/wallet
# expect 401 Unauthorized

# 3. Confirm old endpoints are gone (404)
curl -X POST https://api.slparcels.com/api/v1/sl/escrow/payment -H "Content-Type: application/json" -d '{}'
# expect 404 Not Found

# 4. (Manual: cannot do without LSL avatar identity) /sl/wallet/deposit smoke — deferred to in-world swap step.
```

- [ ] Step 15.5.1: Run smoke commands; confirm responses.
- [ ] Step 15.5.2: Hand off the in-world swap procedure + manual smoke list to the user (text deliverable, see Task 15.6).

### Task 15.6: Provide manual smoke + rollback procedures to user

Final deliverable to the user (text response, not a file):

**Manual smoke procedure (after in-world LSL swap):**
1. Drop new `slpa-terminal.lsl` + updated `config` notecard into the SLPA HQ terminal prim.
2. Drop new `slpa-verifier-giver.lsl` + config into a new prim at SLPA HQ.
3. Confirm "registered" startup ping in chat.
4. Right-click the SLPA Terminal prim → Pay → L$10. Confirm owner-say `payment ok DEPOSIT L$10 from <name>`. Confirm wallet panel shows L$10.
5. Touch the prim → menu → Withdraw → 5 → Yes. Confirm L$5 arrives in your avatar wallet within ~30s. Confirm wallet panel shows L$5 remaining.
6. Touch the verifier-giver prim → confirm SLPA Parcel Verifier inventory item received.
7. End-to-end auction: deposit more L$, place a bid on a test auction, confirm reserved/available updated; outbid yourself with a second account; confirm reservations swap correctly.
8. Test BIN: click BIN on a test auction with sufficient balance; confirm escrow created in `FUNDED` state immediately.

**Rollback procedure (if smoke fails):**
1. Restore RDS snapshot:
   ```bash
   aws rds restore-db-instance-from-db-snapshot \
       --db-instance-identifier slpa-prod-db-restored \
       --db-snapshot-identifier pre-wallet-<sha> \
       --profile slpa-prod
   ```
   Update Parameter Store `SPRING_DATASOURCE_URL` to restored endpoint. Restart ECS.
2. `git revert -m 1 <merge-commit-sha>; git push origin main` — GHA redeploys old backend.
3. In-world: drag-drop old `slpa-terminal.lsl` + old config notecard back into each prim. Restore old SLPA Parcel Verifier inventory item to terminal prims. Optionally remove the new verifier-giver prim.
4. Frontend revert: Amplify auto-redeploys from reverted main commit.
5. Postman collection: revert via git on collection JSON.

Total recovery: ~45 min.

- [ ] Step 15.6.1: Provide the above as a final text deliverable to the user.

---

## Self-review (post-write)

Spec coverage check (against spec §1 In-Scope items):

- ✓ `user_ledger`, `bid_reservations`, `users` columns — Phase 1.
- ✓ `/sl/wallet/deposit`, `/sl/wallet/withdraw-request` — Phase 3.
- ✓ `/me/wallet` (GET, withdraw, pay-penalty, accept-terms, pay-listing-fee) — Phase 4.
- ✓ Bid + BIN modifications — Phase 5.
- ✓ Auction-end auto-fund + ESCROW_PENDING retirement — Phase 1.6 + Phase 6.
- ✓ Refund collapse — Phase 7.
- ✓ Removed endpoints — Phase 8.
- ✓ Reconciliation extension — Phase 9 (schema in 1.5, code in 9.1).
- ✓ Dormancy — Phase 10.
- ✓ Wallet terms-of-use page — Phase 11.7.
- ✓ LSL rewrites — Phase 12.
- ✓ Postman rebuild — Phase 13.
- ✓ Documentation sweep — Phase 14.

Placeholder scan: no `TBD`, no `TODO`, no "implement later". One use of `<next>` for Flyway version numbers — that's a real lookup the implementor performs at task time, not a placeholder. One use of `<sha>` for git commit hash — same.

Type consistency: `WalletService` method names referenced consistently across phases (`deposit`, `withdrawSiteInitiated`, `withdrawTouchInitiated`, `payPenalty`, `swapReservation`, `autoFundEscrow`, `creditEscrowRefund`, `creditListingFeeRefund`, `debitListingFee`, `releaseReservationsForAuction`, `releaseAllReservationsForUser`).

Scope check: this is one cohesive implementation plan for one cohesive feature. Phase boundaries are dependency-driven, not scope-driven. The plan is large but unified.
