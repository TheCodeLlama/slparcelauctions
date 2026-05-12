# Realty Groups Group Wallet — Part 3 (Tasks 21–30)

> Continuation of `2026-05-11-realty-groups-group-wallet-part2.md`. Withdraw flow, dissolution gate, dormancy, HTTP surface, frontend, finalisation.

---

### Task 21: `RealtyGroupWalletService.withdraw` + `recordWithdrawalSuccess` + `recordWithdrawalReversal`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceWithdrawTest.java`

Implements spec §5.3.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.wallet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;
import com.slparcelauctions.backend.realty.wallet.exception.LeaderFrozenException;
import com.slparcelauctions.backend.realty.wallet.exception.LeaderTermsNotAcceptedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.UserStatus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RealtyGroupWalletServiceWithdrawTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final TerminalCommandRepository cmdRepo = mock(TerminalCommandRepository.class);
    private final NotificationPublisher notif = mock(NotificationPublisher.class);
    private final GroupWalletBroadcastPublisher pub = mock(GroupWalletBroadcastPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupWalletService svc = new RealtyGroupWalletService(
        groupRepo, ledgerRepo, userRepo, cmdRepo, notif, pub, clock);

    @Test
    void happyPath_debitsBalanceAppendsLedgerQueuesTerminalCommand() {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader();
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));
        when(ledgerRepo.save(any())).thenAnswer(i -> {
            RealtyGroupLedgerEntry e = i.getArgument(0);
            // simulate db assignment of id
            try {
                var f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
                f.setAccessible(true); f.set(e, 123L);
            } catch (Exception ex) { throw new RuntimeException(ex); }
            return e;
        });
        when(cmdRepo.save(any())).thenAnswer(i -> {
            TerminalCommand c = i.getArgument(0);
            try {
                var f = c.getClass().getSuperclass().getDeclaredField("id");
                f.setAccessible(true); f.set(c, 999L);
            } catch (Exception ex) { throw new RuntimeException(ex); }
            return c;
        });
        UUID idemKey = UUID.randomUUID();

        var result = svc.withdraw(42L, 500L, idemKey, /*callerUserId*/ 5L);

        assertThat(g.getBalanceLindens()).isEqualTo(500L);
        ArgumentCaptor<TerminalCommand> cmdCap = ArgumentCaptor.forClass(TerminalCommand.class);
        verify(cmdRepo).save(cmdCap.capture());
        TerminalCommand cmd = cmdCap.getValue();
        assertThat(cmd.getPurpose()).isEqualTo(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL);
        assertThat(cmd.getRealtyGroupId()).isEqualTo(42L);
        assertThat(cmd.getRecipientUuid()).isEqualTo(leader.getSlAvatarUuid().toString());
        assertThat(cmd.getIdempotencyKey()).startsWith("GWAL-");
        assertThat(result.queueId()).isEqualTo(999L);
    }

    @Test
    void rejectsLeaderWithoutAcceptedTerms() {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader();
        leader.setWalletTermsAcceptedAt(null);
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, UUID.randomUUID(), 5L))
            .isInstanceOf(LeaderTermsNotAcceptedException.class);
    }

    @Test
    void rejectsFrozenLeader() {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader();
        leader.setStatus(UserStatus.FROZEN);
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, UUID.randomUUID(), 5L))
            .isInstanceOf(LeaderFrozenException.class);
    }

    @Test
    void rejectsInsufficientBalance() {
        RealtyGroup g = group(42L, 100L);
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(readyLeader()));

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, UUID.randomUUID(), 5L))
            .isInstanceOf(InsufficientGroupBalanceException.class);
    }

    @Test
    void idempotencyReplayReturnsExistingResult() {
        UUID idem = UUID.randomUUID();
        // Pre-existing ledger row with same idempotency key.
        RealtyGroupLedgerEntry existing = RealtyGroupLedgerEntry.builder()
            .groupId(42L)
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
            .amount(500L)
            .balanceAfter(500L)
            .reservedAfter(0L)
            .idempotencyKey(idem.toString())
            .build();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.of(existing));
        // ... existing terminal command lookup (mock the repo accordingly).
        // Assert: no new debit, returns prior queueId.
    }

    // ---- helpers ----
    private RealtyGroup group(Long id, long balance) {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(balance).build();
        try {
            var f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true); f.set(g, id);
        } catch (Exception e) { throw new RuntimeException(e); }
        return g;
    }
    private User readyLeader() {
        return User.builder().id(1L)
            .slAvatarUuid(UUID.randomUUID())
            .walletTermsAcceptedAt(java.time.OffsetDateTime.now())
            .status(UserStatus.ACTIVE).build();
    }
}
```

- [ ] **Step 2: Run the test (red)**

Expected: FAIL — methods missing.

- [ ] **Step 3: Implement `withdraw` + callback methods**

Append to `RealtyGroupWalletService`:

```java
/* ============================================================ */
/* WITHDRAW                                                      */
/* ============================================================ */

public record WithdrawResult(Long queueId, int estimatedFulfillmentSeconds) {}

/**
 * Initiate a group-wallet withdrawal. Caller is whoever holds
 * WITHDRAW_FROM_GROUP_WALLET (or the leader). Recipient is always
 * the group leader's verified SL avatar (Q3 / spec §5.3).
 */
@Transactional
public WithdrawResult withdraw(Long groupId, long amount, UUID idempotencyKey, Long callerUserId) {
    if (amount <= 0) {
        throw new IllegalArgumentException("amount must be positive: " + amount);
    }
    String idemStr = idempotencyKey.toString();
    Optional<RealtyGroupLedgerEntry> replay = ledgerRepository.findByIdempotencyKey(idemStr);
    if (replay.isPresent()) {
        // Find the matching TerminalCommand to return the original queueId.
        TerminalCommand prior = terminalCommandRepository
            .findByIdempotencyKey("GWAL-" + replay.get().getId())
            .orElseThrow(() -> new IllegalStateException(
                "ledger row exists but terminal command missing: ledgerId=" + replay.get().getId()));
        return new WithdrawResult(prior.getId(), 60);
    }

    RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
    User leader = userRepository.findById(group.getLeaderId())
        .orElseThrow(() -> new IllegalStateException(
            "group " + group.getPublicId() + " leader id " + group.getLeaderId() + " missing"));

    if (leader.getWalletTermsAcceptedAt() == null) {
        throw new com.slparcelauctions.backend.realty.wallet.exception
            .LeaderTermsNotAcceptedException(leader.getPublicId());
    }
    if (leader.getStatus() == com.slparcelauctions.backend.user.UserStatus.BANNED
            || leader.getStatus() == com.slparcelauctions.backend.user.UserStatus.FROZEN) {
        throw new com.slparcelauctions.backend.realty.wallet.exception.LeaderFrozenException();
    }
    long available = group.availableLindens();
    if (available < amount) {
        throw new com.slparcelauctions.backend.realty.wallet.exception
            .InsufficientGroupBalanceException(available, amount);
    }

    long newBalance = group.getBalanceLindens() - amount;
    group.setBalanceLindens(newBalance);
    clearDormancyOnActivity(group);
    groupRepository.save(group);

    RealtyGroupLedgerEntry queuedEntry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
        .groupId(groupId)
        .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
        .amount(amount)
        .balanceAfter(newBalance)
        .reservedAfter(group.getReservedLindens())
        .actorUserId(callerUserId)
        .idempotencyKey(idemStr)
        .refType("TERMINAL_COMMAND")
        // refId backfilled by callback path; null is acceptable here.
        .createdAt(OffsetDateTime.now(clock))
        .build());

    TerminalCommand cmd = terminalCommandRepository.save(TerminalCommand.builder()
        .action(com.slparcelauctions.backend.escrow.command.TerminalCommandAction.WITHDRAW)
        .purpose(com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
        .recipientUuid(leader.getSlAvatarUuid().toString())
        .amount(amount)
        .status(com.slparcelauctions.backend.escrow.command.TerminalCommandStatus.QUEUED)
        .idempotencyKey("GWAL-" + queuedEntry.getId())
        .realtyGroupId(groupId)
        .nextAttemptAt(OffsetDateTime.now(clock))
        .attemptCount(0)
        .requiresManualReview(false)
        .build());

    broadcastPublisher.publish(group.getPublicId(),
        newBalance, group.getReservedLindens(), group.availableLindens(),
        RealtyGroupLedgerEntryType.WITHDRAW_QUEUED.name(), queuedEntry.getPublicId());

    log.info("group withdraw queued: groupId={}, amount={}, leader={}, callerUserId={}, queueId={}",
        groupId, amount, leader.getPublicId(), callerUserId, cmd.getId());
    return new WithdrawResult(cmd.getId(), 60);
}

/**
 * Called by GroupWalletWithdrawalCallbackHandler.onSuccess.
 * Idempotent: if a WITHDRAW_COMPLETED row already exists for the
 * referenced WITHDRAW_QUEUED, no-op.
 */
@Transactional(propagation = Propagation.MANDATORY)
public void recordWithdrawalSuccess(Long queuedLedgerId, String slTransactionKey) {
    RealtyGroupLedgerEntry queued = ledgerRepository.findById(queuedLedgerId).orElseThrow();
    // No balance change on completion (balance was decremented at queue time).
    ledgerRepository.save(RealtyGroupLedgerEntry.builder()
        .groupId(queued.getGroupId())
        .entryType(RealtyGroupLedgerEntryType.WITHDRAW_COMPLETED)
        .amount(queued.getAmount())
        .balanceAfter(queued.getBalanceAfter())
        .reservedAfter(queued.getReservedAfter())
        .slTransactionId(slTransactionKey)
        .refType("REALTY_GROUP_LEDGER_ENTRY")
        .refId(queuedLedgerId)
        .createdAt(OffsetDateTime.now(clock))
        .build());
    log.info("group withdraw completed: ledgerId={}, slTxn={}", queuedLedgerId, slTransactionKey);
}

/**
 * Called by GroupWalletWithdrawalCallbackHandler.onStall.
 * Credits the L$ back to the group balance and appends a
 * WITHDRAW_REVERSED row.
 */
@Transactional(propagation = Propagation.MANDATORY)
public void recordWithdrawalReversal(Long queuedLedgerId, String reason) {
    RealtyGroupLedgerEntry queued = ledgerRepository.findById(queuedLedgerId).orElseThrow();
    RealtyGroup group = groupRepository.findByIdForUpdate(queued.getGroupId()).orElseThrow();
    long newBalance = group.getBalanceLindens() + queued.getAmount();
    group.setBalanceLindens(newBalance);
    groupRepository.save(group);

    RealtyGroupLedgerEntry reversed = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
        .groupId(queued.getGroupId())
        .entryType(RealtyGroupLedgerEntryType.WITHDRAW_REVERSED)
        .amount(queued.getAmount())
        .balanceAfter(newBalance)
        .reservedAfter(group.getReservedLindens())
        .description(reason == null ? "transport failure" : reason)
        .refType("REALTY_GROUP_LEDGER_ENTRY")
        .refId(queuedLedgerId)
        .createdAt(OffsetDateTime.now(clock))
        .build());

    broadcastPublisher.publish(group.getPublicId(),
        newBalance, group.getReservedLindens(), group.availableLindens(),
        RealtyGroupLedgerEntryType.WITHDRAW_REVERSED.name(), reversed.getPublicId());
    log.warn("group withdraw reversed: ledgerId={}, reason={}, balanceAfter={}",
        queuedLedgerId, reason, newBalance);
}

public Long findGroupIdForLedgerEntry(Long ledgerEntryId) {
    return ledgerRepository.findById(ledgerEntryId).map(RealtyGroupLedgerEntry::getGroupId).orElse(null);
}
```

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupWalletServiceWithdrawTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceWithdrawTest.java
git commit -m "feat(realty-wallet): RealtyGroupWalletService.withdraw + callback recorders"
git push
```

---

### Task 22: `GroupWalletWithdrawalCallbackHandler` + wire into `TerminalCommandService`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/GroupWalletWithdrawalCallbackHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/GroupWalletWithdrawalCallbackHandlerTest.java`

Implements spec §5.3 callback flow + §11.2 notifications.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.wallet;

import org.junit.jupiter.api.Test;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.notification.NotificationPublisher;

import static org.mockito.Mockito.*;

class GroupWalletWithdrawalCallbackHandlerTest {

    private final RealtyGroupWalletService svc = mock(RealtyGroupWalletService.class);
    private final NotificationPublisher notif = mock(NotificationPublisher.class);
    private final GroupWalletWithdrawalCallbackHandler h =
        new GroupWalletWithdrawalCallbackHandler(svc, notif);

    @Test
    void onSuccessParsesLedgerIdAndRecords() {
        TerminalCommand cmd = TerminalCommand.builder()
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .idempotencyKey("GWAL-42")
            .amount(500L)
            .build();
        when(svc.findGroupIdForLedgerEntry(42L)).thenReturn(7L);

        h.onSuccess(cmd, "slTxn-abc");

        verify(svc).recordWithdrawalSuccess(42L, "slTxn-abc");
        verify(notif).groupWalletWithdrawalCompleted(7L, 500L, 42L);
    }

    @Test
    void onStallReversesAndNotifies() {
        TerminalCommand cmd = TerminalCommand.builder()
            .idempotencyKey("GWAL-99")
            .amount(250L)
            .build();
        when(svc.findGroupIdForLedgerEntry(99L)).thenReturn(7L);

        h.onStall(cmd, "transport failure");

        verify(svc).recordWithdrawalReversal(99L, "transport failure");
        verify(notif).groupWalletWithdrawalReversed(7L, 250L, 99L, "transport failure");
    }
}
```

> The notification methods (`groupWalletWithdrawalCompleted`, `groupWalletWithdrawalReversed`) don't exist on `NotificationPublisher` yet — they need adding in this task too (see Step 3).

- [ ] **Step 2: Run the test (red)**

Expected: FAIL — handler not defined, notifications missing.

- [ ] **Step 3: Implement**

```java
// GroupWalletWithdrawalCallbackHandler.java
package com.slparcelauctions.backend.realty.wallet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.notification.NotificationPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWalletWithdrawalCallbackHandler {

    private final RealtyGroupWalletService walletService;
    private final NotificationPublisher notificationPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void onSuccess(TerminalCommand cmd, String slTransactionKey) {
        Long ledgerId = parseLedgerId(cmd);
        if (ledgerId == null) return;
        walletService.recordWithdrawalSuccess(ledgerId, slTransactionKey);
        Long groupId = walletService.findGroupIdForLedgerEntry(ledgerId);
        if (groupId != null) {
            notificationPublisher.groupWalletWithdrawalCompleted(
                groupId, cmd.getAmount(), ledgerId);
        }
        log.info("group withdrawal callback success: commandId={}, ledgerId={}, slTxn={}",
                cmd.getId(), ledgerId, slTransactionKey);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onStall(TerminalCommand cmd, String reason) {
        Long ledgerId = parseLedgerId(cmd);
        if (ledgerId == null) return;
        walletService.recordWithdrawalReversal(ledgerId, reason);
        Long groupId = walletService.findGroupIdForLedgerEntry(ledgerId);
        if (groupId != null) {
            notificationPublisher.groupWalletWithdrawalReversed(
                groupId, cmd.getAmount(), ledgerId,
                reason == null ? "transport failure" : reason);
        }
        log.warn("group withdrawal callback stall: commandId={}, ledgerId={}, reason={}",
                cmd.getId(), ledgerId, reason);
    }

    private Long parseLedgerId(TerminalCommand cmd) {
        String key = cmd.getIdempotencyKey();
        if (key == null || !key.startsWith("GWAL-")) {
            log.error("group withdrawal command {} has unexpected idempotencyKey: {}",
                    cmd.getId(), key);
            return null;
        }
        try {
            return Long.parseLong(key.substring(5));
        } catch (NumberFormatException e) {
            log.error("group withdrawal command {} idempotencyKey not parseable: {}",
                    cmd.getId(), key);
            return null;
        }
    }
}
```

Add to `NotificationPublisher`:

```java
public void groupWalletWithdrawalCompleted(Long groupId, long amount, Long ledgerId) {
    // Fan out to leader + delegates with VIEW_GROUP_TRANSACTIONS — implementer
    // adapts to the existing notification dispatcher pattern. The wiring detail
    // matches Epic 09's SL IM dispatcher pattern.
    log.info("[NOTIF] group {} withdrawal completed L${} (ledger {})", groupId, amount, ledgerId);
}

public void groupWalletWithdrawalReversed(Long groupId, long amount, Long ledgerId, String reason) {
    log.warn("[NOTIF] group {} withdrawal reversed L${} (ledger {}) reason={}", groupId, amount, ledgerId, reason);
}
```

> If `NotificationPublisher` is heavily slot-fastened to user-IDs, the implementer may need to add a small helper that resolves the group's leader-and-delegates list once and calls the existing per-user dispatch. Keep the public methods on `NotificationPublisher` as shown so the handler stays simple.

Wire into `TerminalCommandService.applySuccessfulCallback`:

```java
} else if (cmd.getPurpose() == TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL) {
    groupWalletWithdrawalCallbackHandler.onSuccess(cmd, slTxn);
}
```

And in the stall handler branch:

```java
} else if (cmd.getPurpose() == TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL) {
    groupWalletWithdrawalCallbackHandler.onStall(cmd, req.errorMessage());
}
```

Add the field:
```java
private final com.slparcelauctions.backend.realty.wallet.GroupWalletWithdrawalCallbackHandler
    groupWalletWithdrawalCallbackHandler;
```

- [ ] **Step 4: Run the test (green)**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/GroupWalletWithdrawalCallbackHandler.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/GroupWalletWithdrawalCallbackHandlerTest.java
git commit -m "feat(realty-wallet): GroupWalletWithdrawalCallbackHandler + TerminalCommandService routing"
git push
```

---

### Task 23: `EscrowRepository.existsInFlightForGroup` + `RealtyGroupService.dissolve` gate extension

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceDissolveGateTest.java`

Implements spec §9.1.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.service;

import org.junit.jupiter.api.Test;
// ... SpringBootTest imports + repos

import com.slparcelauctions.backend.realty.wallet.exception.GroupHasInFlightEscrowsException;
import com.slparcelauctions.backend.realty.wallet.exception.GroupHasNonzeroBalanceException;

import static org.assertj.core.api.Assertions.*;

class RealtyGroupServiceDissolveGateTest {

    @Test
    void dissolveRejectsWhenGroupHasNonzeroBalance() {
        // setup: realty group with balance=100, no active listings, no in-flight escrows
        // act: leader.dissolve()
        // assert: GroupHasNonzeroBalanceException
    }

    @Test
    void dissolveRejectsWhenGroupHasNonzeroReserved() {
        // setup: realty group with reserved=50 (artificial), no active listings, no in-flight escrows
        // assert: GroupHasNonzeroBalanceException
    }

    @Test
    void dissolveRejectsWhenInFlightEscrowsExist() {
        // setup: group-listed auction with escrow in FUNDED state, balance=0
        // assert: GroupHasInFlightEscrowsException
    }

    @Test
    void dissolveSucceedsWhenAllGatesClear() {
        // setup: balance=0, reserved=0, no listings, no in-flight escrows
        // assert: dissolved_at is set
    }
}
```

- [ ] **Step 2: Run the test (red)**

Expected: FAIL — no gate extension yet.

- [ ] **Step 3: Add the repository method**

In `EscrowRepository.java`:

```java
@Query("""
    SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END
    FROM Escrow e
    WHERE e.auction.realtyGroupId = :groupId
      AND e.state IN (
          com.slparcelauctions.backend.escrow.EscrowState.ESCROW_PENDING,
          com.slparcelauctions.backend.escrow.EscrowState.FUNDED,
          com.slparcelauctions.backend.escrow.EscrowState.TRANSFER_PENDING,
          com.slparcelauctions.backend.escrow.EscrowState.DISPUTED
      )
    """)
boolean existsInFlightForGroup(@Param("groupId") Long groupId);
```

> Verify the exact "in-flight" enum members from `EscrowState.java` before pasting. Pull from the existing escrow state machine.

- [ ] **Step 4: Extend the dissolve gate**

Find `RealtyGroupService.dissolve(...)`. After the existing `auctions.existsActiveListingsByGroupId` check, add:

```java
if (group.getBalanceLindens() != 0L || group.getReservedLindens() != 0L) {
    throw new com.slparcelauctions.backend.realty.wallet.exception
        .GroupHasNonzeroBalanceException();
}
if (escrowRepository.existsInFlightForGroup(group.getId())) {
    throw new com.slparcelauctions.backend.realty.wallet.exception
        .GroupHasInFlightEscrowsException();
}
```

Inject `EscrowRepository`:
```java
private final com.slparcelauctions.backend.escrow.EscrowRepository escrowRepository;
```

- [ ] **Step 5: Run the test (green)**

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceDissolveGateTest.java
git commit -m "feat(realty-wallet): tighten dissolve gate on nonzero balance + in-flight escrows"
git push
```

---

### Task 24: `GroupWalletDormancyTask` + `GroupWalletDormancyJob` + login reset hook

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dormancy/GroupWalletDormancyTask.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dormancy/GroupWalletDormancyJob.java`
- Modify: appropriate login/refresh handler (find by grepping for `refresh_tokens` write site)
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/dormancy/GroupWalletDormancyTaskTest.java`

Implements spec §10.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.wallet.dormancy;

import org.junit.jupiter.api.Test;
// ... SpringBootTest imports

class GroupWalletDormancyTaskTest {

    @Test
    void phase1FlagsGroupAndSendsIM() {
        // setup: group with balance=1000, no recent member rotations
        // act: task.flag(group, now)
        // assert: dormancy_started_at=now, dormancy_phase=1, IM dispatched
    }

    @Test
    void phase2And3EscalateIM() {
        // setup: group already in phase 1, no member rotation since
        // act: task.escalateOrAutoReturn(group, now+1week)
        // assert: phase incremented to 2, IM dispatched
    }

    @Test
    void phase4QueuesAutoReturnAndStampsPhase99() {
        // setup: group in phase 4
        // act: escalate
        // assert: TerminalCommand{WITHDRAW, recipient=leader, amount=balance, idempotency=group-dormancy-{id}-{phase4At}} queued
        // assert: realty_group_ledger DORMANCY_AUTO_RETURN row appended
        // assert: balance=0, phase=99
    }

    @Test
    void memberLoginClearsDormancyState() {
        // setup: group in phase 2, member logs in (refresh token rotated)
        // act: login flow runs
        // assert: group phase cleared
    }

    @Test
    void leaderChangeMidCycleUsesNewLeaderAtPhase4() {
        // setup: group in phase 3 with leader A; transfer leadership to B
        // act: escalate to phase 4
        // assert: TerminalCommand.recipient = B's slAvatarUuid
    }
}
```

- [ ] **Step 2: Run the tests (red)**

Expected: FAIL.

- [ ] **Step 3: Implement the task**

```java
// GroupWalletDormancyTask.java
package com.slparcelauctions.backend.realty.wallet.dormancy;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWalletDormancyTask {

    private static final short PHASE_COMPLETED = 99;

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final TerminalCommandRepository terminalCommandRepository;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional
    public void flag(RealtyGroup group, OffsetDateTime now) {
        RealtyGroup g = groupRepository.findByIdForUpdate(group.getId()).orElseThrow();
        g.setWalletDormancyStartedAt(now);
        g.setWalletDormancyPhase((short) 1);
        groupRepository.save(g);
        notificationPublisher.groupWalletDormancyFlagged(g.getId(), 1, g.getBalanceLindens());
        log.info("group {} flagged dormant phase 1 (balance L${})", g.getPublicId(), g.getBalanceLindens());
    }

    @Transactional
    public void escalateOrAutoReturn(RealtyGroup group, OffsetDateTime now) {
        RealtyGroup g = groupRepository.findByIdForUpdate(group.getId()).orElseThrow();
        Short phase = g.getWalletDormancyPhase();
        if (phase == null || phase == PHASE_COMPLETED) return;
        if (phase < 4) {
            short next = (short) (phase + 1);
            g.setWalletDormancyPhase(next);
            groupRepository.save(g);
            notificationPublisher.groupWalletDormancyFlagged(g.getId(), next, g.getBalanceLindens());
            log.info("group {} escalated to dormancy phase {}", g.getPublicId(), next);
            return;
        }
        // phase 4 -> auto return
        User leader = userRepository.findById(g.getLeaderId()).orElseThrow();
        long balance = g.getBalanceLindens();
        if (balance > 0) {
            String idempotencyKey = "group-dormancy-" + g.getId() + "-"
                + g.getWalletDormancyStartedAt().toInstant().getEpochSecond();
            terminalCommandRepository.save(TerminalCommand.builder()
                .action(TerminalCommandAction.WITHDRAW)
                .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
                .recipientUuid(leader.getSlAvatarUuid().toString())
                .amount(balance)
                .status(TerminalCommandStatus.QUEUED)
                .idempotencyKey(idempotencyKey)
                .realtyGroupId(g.getId())
                .nextAttemptAt(OffsetDateTime.now(clock))
                .attemptCount(0)
                .requiresManualReview(false)
                .build());

            ledgerRepository.save(RealtyGroupLedgerEntry.builder()
                .groupId(g.getId())
                .entryType(RealtyGroupLedgerEntryType.DORMANCY_AUTO_RETURN)
                .amount(balance)
                .balanceAfter(0L)
                .reservedAfter(g.getReservedLindens())
                .refType("TERMINAL_COMMAND")
                .description("auto-return after 30d inactivity + 4 weekly notices")
                .createdAt(now)
                .build());

            g.setBalanceLindens(0L);
        }
        g.setWalletDormancyPhase(PHASE_COMPLETED);
        groupRepository.save(g);
        notificationPublisher.groupWalletDormancyAutoReturned(g.getId(), balance);
        log.info("group {} dormancy phase 4 -> COMPLETED, returned L${} to leader", g.getPublicId(), balance);
    }

    @Transactional
    public void clearForGroup(Long groupId) {
        RealtyGroup g = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        if (g.getWalletDormancyPhase() != null && g.getWalletDormancyPhase() != PHASE_COMPLETED) {
            g.setWalletDormancyPhase(null);
            g.setWalletDormancyStartedAt(null);
            groupRepository.save(g);
            log.info("group {} dormancy cleared on member activity", g.getPublicId());
        }
    }
}
```

```java
// GroupWalletDormancyJob.java
package com.slparcelauctions.backend.realty.wallet.dormancy;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWalletDormancyJob {

    private final RealtyGroupRepository groupRepository;
    private final GroupWalletDormancyTask task;
    private final Clock clock;

    @Value("${slpa.realty-wallet.dormancy.window-days:30}")
    private int windowDays;

    @Value("${slpa.realty-wallet.dormancy.phase-duration-days:7}")
    private int phaseDurationDays;

    @Scheduled(cron = "${slpa.realty-wallet.dormancy-job.cron:0 30 4 * * MON}", zone = "UTC")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RealtyGroup> newlyDormant = groupRepository.findEligibleForDormancyFlag(windowDays);
        newlyDormant.forEach(g -> task.flag(g, now));

        List<RealtyGroup> awaitingNext = groupRepository.findDormancyPhaseDue(phaseDurationDays);
        awaitingNext.forEach(g -> task.escalateOrAutoReturn(g, now));

        log.info("group dormancy sweep: flagged={}, escalated={}", newlyDormant.size(), awaitingNext.size());
    }
}
```

Login-reset hook: find the existing user-side refresh-token rotation site. Run:
```
grep -rn "refresh_tokens\|RefreshToken.*save\|RefreshTokenService" backend/src/main/java/com/slparcelauctions/backend/auth/ | head -10
```

Find the function that creates refresh tokens (likely `RefreshTokenService.rotate` or similar). At the end of successful rotation/creation, add:

```java
// Sub-project D §10.4: clear group dormancy state for any group the user is a member of.
List<Long> groupIds = realtyGroupMemberRepository.findGroupIdsByUserId(user.getId());
for (Long gid : groupIds) {
    groupWalletDormancyTask.clearForGroup(gid);
}
```

(Add `findGroupIdsByUserId` to `RealtyGroupMemberRepository` if not present.)

Add the notification method stubs to `NotificationPublisher`:
```java
public void groupWalletDormancyFlagged(Long groupId, int phase, long balance) {
    log.info("[NOTIF] group {} dormancy flagged phase={} balance=L${}", groupId, phase, balance);
}
public void groupWalletDormancyAutoReturned(Long groupId, long amount) {
    log.info("[NOTIF] group {} dormancy auto-returned L${}", groupId, amount);
}
```

- [ ] **Step 4: Run the tests (green)**

Run: `cd backend && ./mvnw test -Dtest=GroupWalletDormancyTaskTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dormancy/ \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/ \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/dormancy/
git commit -m "feat(realty-wallet): group dormancy task + weekly sweep + login reset hook"
git push
```

---

### Task 25: Wallet DTOs + `GroupWalletDtoMapper`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/{GroupWalletDto,GroupLedgerEntryDto,LedgerActorDto,GroupWithdrawRequest,GroupWithdrawResponse,GroupWalletDtoMapper}.java`

Implements spec §5.6.

- [ ] **Step 1: Create the records**

```java
// GroupWalletDto.java
package com.slparcelauctions.backend.realty.wallet.dto;
import java.util.List;
public record GroupWalletDto(
    long balance,
    long reserved,
    long available,
    List<GroupLedgerEntryDto> recentLedger
) {}
```

```java
// GroupLedgerEntryDto.java
package com.slparcelauctions.backend.realty.wallet.dto;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupLedgerEntryDto(
    UUID publicId,
    String entryType,
    long amount,
    long balanceAfter,
    long reservedAfter,
    String refType,
    UUID refPublicId,
    LedgerActorDto actor,
    OffsetDateTime createdAt
) {}
```

```java
// LedgerActorDto.java
package com.slparcelauctions.backend.realty.wallet.dto;
import java.util.UUID;
public record LedgerActorDto(UUID publicId, String displayName) {}
```

```java
// GroupWithdrawRequest.java
package com.slparcelauctions.backend.realty.wallet.dto;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
public record GroupWithdrawRequest(
    @Positive long amount,
    @NotNull UUID idempotencyKey
) {}
```

```java
// GroupWithdrawResponse.java
package com.slparcelauctions.backend.realty.wallet.dto;
public record GroupWithdrawResponse(long queueId, int estimatedFulfillmentSeconds) {}
```

```java
// GroupWalletDtoMapper.java
package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class GroupWalletDtoMapper {

    private final UserRepository userRepository;
    private final AuctionRepository auctionRepository;

    public GroupLedgerEntryDto toDto(RealtyGroupLedgerEntry e) {
        return new GroupLedgerEntryDto(
            e.getPublicId(),
            e.getEntryType().name(),
            e.getAmount(),
            e.getBalanceAfter(),
            e.getReservedAfter(),
            e.getRefType(),
            resolveRefPublicId(e),
            resolveActor(e),
            e.getCreatedAt());
    }

    public List<GroupLedgerEntryDto> toDtos(List<RealtyGroupLedgerEntry> entries) {
        return entries.stream().map(this::toDto).toList();
    }

    private UUID resolveRefPublicId(RealtyGroupLedgerEntry e) {
        if (e.getRefType() == null || e.getRefId() == null) return null;
        return switch (e.getRefType()) {
            case "AUCTION" -> auctionRepository.findById(e.getRefId())
                .map(a -> a.getPublicId()).orElse(null);
            // LISTING_FEE_REFUND, TERMINAL_COMMAND, REALTY_GROUP_LEDGER_ENTRY: not surfaced to public DTO.
            default -> null;
        };
    }

    private LedgerActorDto resolveActor(RealtyGroupLedgerEntry e) {
        if (e.getActorUserId() == null) return null;
        return userRepository.findById(e.getActorUserId())
            .map(u -> new LedgerActorDto(u.getPublicId(), displayNameOf(u)))
            .orElse(null);
    }

    private String displayNameOf(User u) {
        return u.getDisplayName() != null ? u.getDisplayName() : u.getUsername();
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd backend && ./mvnw -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/
git commit -m "feat(realty-wallet): group wallet DTOs + mapper"
git push
```

---

### Task 26: `RealtyGroupWalletController` GET wallet + GET ledger

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletControllerGetTest.java`

Implements spec §5.1, §5.2.

- [ ] **Step 1: Write the failing slice test**

Slice test that hits GET `/api/v1/realty/groups/{publicId}/wallet` with three personas:
- Leader → 200 with balance + ledger
- Delegate with `VIEW_GROUP_TRANSACTIONS` → 200
- Outsider → 403 `INSUFFICIENT_GROUP_PERMISSION`
- Wrong publicId → 404

- [ ] **Step 2: Implement**

```java
package com.slparcelauctions.backend.realty.wallet;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.wallet.dto.GroupLedgerEntryDto;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDto;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDtoMapper;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/realty/groups/{publicId}/wallet")
@RequiredArgsConstructor
public class RealtyGroupWalletController {

    private static final int LEDGER_PAGE_DEFAULT = 50;
    private static final int LEDGER_PAGE_MAX = 100;

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    private final RealtyGroupAuthorizer authorizer;
    private final GroupWalletDtoMapper mapper;

    @GetMapping
    public GroupWalletDto getWallet(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID publicId) {
        RealtyGroup g = groupRepository.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        authorizer.assertCan(principal.userId(), g.getId(), RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS);

        List<GroupLedgerEntryDto> recent = mapper.toDtos(
            ledgerRepository.findRecentForGroup(g.getId(), PageRequest.of(0, LEDGER_PAGE_DEFAULT)));
        return new GroupWalletDto(
            g.getBalanceLindens(),
            g.getReservedLindens(),
            g.availableLindens(),
            recent);
    }

    @GetMapping("/ledger")
    public List<GroupLedgerEntryDto> getLedgerPage(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID publicId,
            @RequestParam(required = false) OffsetDateTime cursor,
            @RequestParam(defaultValue = "50") int limit) {
        RealtyGroup g = groupRepository.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        authorizer.assertCan(principal.userId(), g.getId(), RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS);

        int clamp = Math.min(Math.max(1, limit), LEDGER_PAGE_MAX);
        return mapper.toDtos(cursor == null
            ? ledgerRepository.findRecentForGroup(g.getId(), PageRequest.of(0, clamp))
            : ledgerRepository.findOlderForGroup(g.getId(), cursor, PageRequest.of(0, clamp)));
    }
}
```

- [ ] **Step 3: Run the test (green)**

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletControllerGetTest.java
git commit -m "feat(realty-wallet): GET wallet + GET ledger endpoints"
git push
```

---

### Task 27: `RealtyGroupWalletController` POST withdraw

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletControllerWithdrawTest.java`

Implements spec §5.3.

- [ ] **Step 1: Write the failing slice test**

Slice that hits POST `/api/v1/realty/groups/{publicId}/wallet/withdraw` covering: 202 happy path, 403 (no permission), 410 (dissolved), 422 (insufficient balance / leader terms / leader frozen).

- [ ] **Step 2: Add the endpoint**

```java
@PostMapping("/withdraw")
public org.springframework.http.ResponseEntity<GroupWithdrawResponse> withdraw(
        @AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable UUID publicId,
        @Valid @RequestBody GroupWithdrawRequest req) {
    RealtyGroup g = groupRepository.findByPublicId(publicId)
        .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
    if (g.getDissolvedAt() != null) {
        throw new com.slparcelauctions.backend.realty.exception.GroupDissolvedException(g.getPublicId());
    }
    authorizer.assertCan(principal.userId(), g.getId(), RealtyGroupPermission.WITHDRAW_FROM_GROUP_WALLET);

    RealtyGroupWalletService.WithdrawResult result = walletService.withdraw(
        g.getId(), req.amount(), req.idempotencyKey(), principal.userId());
    return org.springframework.http.ResponseEntity.accepted().body(
        new GroupWithdrawResponse(result.queueId(), result.estimatedFulfillmentSeconds()));
}
```

Inject `RealtyGroupWalletService walletService` into the controller.

- [ ] **Step 3: Run the test (green)**

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletControllerWithdrawTest.java
git commit -m "feat(realty-wallet): POST withdraw endpoint with idempotency + gates"
git push
```

---

### Task 28: Frontend — types, API client, hooks

**Files:**
- Modify: `frontend/src/types/realty.ts`
- Create: `frontend/src/lib/api/realtyGroupWallet.ts`
- Create: `frontend/src/hooks/realty/useGroupWallet.ts`
- Create: `frontend/src/hooks/realty/useGroupLedger.ts`
- Modify: `frontend/src/test/msw/handlers.ts`

Implements spec §6.4.

- [ ] **Step 1: Extend types** in `frontend/src/types/realty.ts`:

```ts
export interface GroupWallet {
  balance: number;
  reserved: number;
  available: number;
  recentLedger: GroupLedgerEntry[];
}

export interface GroupLedgerEntry {
  publicId: string;
  entryType:
    | 'LISTING_FEE_DEBIT' | 'LISTING_FEE_REFUND'
    | 'AGENT_FEE_CREDIT'
    | 'WITHDRAW_QUEUED' | 'WITHDRAW_COMPLETED' | 'WITHDRAW_REVERSED'
    | 'DORMANCY_AUTO_RETURN'
    | 'ADJUSTMENT';
  amount: number;
  balanceAfter: number;
  reservedAfter: number;
  refType?: string;
  refPublicId?: string;
  actor?: LedgerActor;
  createdAt: string;
}

export interface LedgerActor {
  publicId: string;
  displayName: string;
}

export interface GroupWithdrawRequest {
  amount: number;
  idempotencyKey: string;
}

export interface GroupWithdrawResponse {
  queueId: number;
  estimatedFulfillmentSeconds: number;
}
```

- [ ] **Step 2: API client** at `frontend/src/lib/api/realtyGroupWallet.ts`:

```ts
import { authFetch } from '@/lib/api/authFetch';
import { apiUrl } from '@/lib/api/url';
import type {
  GroupWallet,
  GroupLedgerEntry,
  GroupWithdrawRequest,
  GroupWithdrawResponse,
} from '@/types/realty';

export async function getGroupWallet(publicId: string): Promise<GroupWallet> {
  const res = await authFetch(apiUrl(`/api/v1/realty/groups/${publicId}/wallet`));
  if (!res.ok) throw new Error(`getGroupWallet ${res.status}`);
  return res.json();
}

export async function getGroupLedger(
  publicId: string,
  cursor?: string,
  limit = 50,
): Promise<GroupLedgerEntry[]> {
  const params = new URLSearchParams();
  if (cursor) params.set('cursor', cursor);
  params.set('limit', String(limit));
  const res = await authFetch(apiUrl(`/api/v1/realty/groups/${publicId}/wallet/ledger?${params}`));
  if (!res.ok) throw new Error(`getGroupLedger ${res.status}`);
  return res.json();
}

export async function withdrawFromGroupWallet(
  publicId: string,
  body: GroupWithdrawRequest,
): Promise<GroupWithdrawResponse> {
  const res = await authFetch(apiUrl(`/api/v1/realty/groups/${publicId}/wallet/withdraw`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new ApiError(res.status, err);
  }
  return res.json();
}

export class ApiError extends Error {
  constructor(public status: number, public body: unknown) {
    super(`API error ${status}`);
  }
}
```

- [ ] **Step 3: Hooks**

```ts
// useGroupWallet.ts
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getGroupWallet } from '@/lib/api/realtyGroupWallet';

export function useGroupWallet(publicId: string) {
  return useQuery({
    queryKey: ['realty', 'group', publicId, 'wallet'],
    queryFn: () => getGroupWallet(publicId),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}

export function useInvalidateGroupWallet(publicId: string) {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: ['realty', 'group', publicId, 'wallet'] });
    qc.invalidateQueries({ queryKey: ['realty', 'group', publicId, 'ledger'] });
  };
}
```

```ts
// useGroupLedger.ts
import { useInfiniteQuery } from '@tanstack/react-query';
import { getGroupLedger } from '@/lib/api/realtyGroupWallet';

export function useGroupLedger(publicId: string, pageSize = 50) {
  return useInfiniteQuery({
    queryKey: ['realty', 'group', publicId, 'ledger', pageSize],
    queryFn: ({ pageParam }: { pageParam?: string }) =>
      getGroupLedger(publicId, pageParam, pageSize),
    initialPageParam: undefined,
    getNextPageParam: (last) =>
      last.length === pageSize ? last[last.length - 1].createdAt : undefined,
  });
}
```

- [ ] **Step 4: MSW handlers**

In `frontend/src/test/msw/handlers.ts` add defaults:

```ts
http.get(apiUrl('/api/v1/realty/groups/:publicId/wallet'), () =>
  HttpResponse.json<GroupWallet>({
    balance: 0,
    reserved: 0,
    available: 0,
    recentLedger: [],
  }),
),
http.get(apiUrl('/api/v1/realty/groups/:publicId/wallet/ledger'), () =>
  HttpResponse.json<GroupLedgerEntry[]>([]),
),
http.post(apiUrl('/api/v1/realty/groups/:publicId/wallet/withdraw'), () =>
  HttpResponse.json<GroupWithdrawResponse>(
    { queueId: 1, estimatedFulfillmentSeconds: 60 },
    { status: 202 },
  ),
),
```

- [ ] **Step 5: Run frontend tests + verify**

Run: `cd frontend && npm run lint && npm test && npm run verify`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/types/realty.ts \
        frontend/src/lib/api/realtyGroupWallet.ts \
        frontend/src/hooks/realty/useGroupWallet.ts \
        frontend/src/hooks/realty/useGroupLedger.ts \
        frontend/src/test/msw/handlers.ts
git commit -m "feat(realty-wallet): frontend types + API client + hooks"
git push
```

---

### Task 29: Frontend — wallet page + components

**Files:**
- Create: `frontend/src/app/realty/groups/[publicId]/wallet/page.tsx`
- Create: `frontend/src/components/realty/wallet/{GroupWalletPage,GroupWalletBalanceCard,GroupWalletLedgerTable,GroupWithdrawModal,LeaderTermsBlockBanner}.tsx`
- Test: matching `*.test.tsx` files

Implements spec §6.1, §6.2.

- [ ] **Step 1: Scaffold the page**

```tsx
// app/realty/groups/[publicId]/wallet/page.tsx
import GroupWalletPage from '@/components/realty/wallet/GroupWalletPage';

export const dynamic = 'force-dynamic';

export default function Page({ params }: { params: { publicId: string } }) {
  return <GroupWalletPage publicId={params.publicId} />;
}
```

- [ ] **Step 2: Write the failing component tests**

For each of `GroupWalletPage`, `GroupWalletBalanceCard`, `GroupWalletLedgerTable`, `GroupWithdrawModal`, `LeaderTermsBlockBanner`, write a focused Vitest + Testing Library test asserting:
- BalanceCard renders balance/reserved/available; reserved=0 hidden when zero.
- LedgerTable renders rows, supports load-more.
- WithdrawModal validates amount, generates idempotency key (UUID v4), surfaces 422 errors.
- LeaderTermsBlockBanner shows only when `leaderTermsAcceptedAt == null`; clicking goes to leader's account page (or surfaces the existing user-wallet ToS flow).
- WalletPage composes them and 403s appropriately.

- [ ] **Step 3: Implement**

Each component uses Tailwind utility classes (no hex, no inline styles, no dark variants) and consumes icons from `@/components/ui/icons`. Follow the existing C-shipped components (`ListAsGroupPicker`, `AgentFeePreview`) for style.

For `GroupWithdrawModal.tsx` (sketch):

```tsx
'use client';
import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { withdrawFromGroupWallet, ApiError } from '@/lib/api/realtyGroupWallet';
import { useInvalidateGroupWallet } from '@/hooks/realty/useGroupWallet';

export default function GroupWithdrawModal({
  publicId,
  available,
  leaderDisplayName,
  onClose,
}: {
  publicId: string;
  available: number;
  leaderDisplayName: string;
  onClose: () => void;
}) {
  const [amount, setAmount] = useState('');
  const [error, setError] = useState<string | null>(null);
  const invalidate = useInvalidateGroupWallet(publicId);
  const mutation = useMutation({
    mutationFn: () => withdrawFromGroupWallet(publicId, {
      amount: parseInt(amount, 10),
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: () => { invalidate(); onClose(); },
    onError: (err) => {
      if (err instanceof ApiError) {
        const body = err.body as { code?: string; message?: string };
        setError(body.message ?? body.code ?? 'withdraw failed');
      } else {
        setError(String(err));
      }
    },
  });
  // ... render modal with form
  return null; // implementer fills in
}
```

- [ ] **Step 4: Run tests + verify**

Run: `cd frontend && npm run lint && npm test && npm run verify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/realty/groups/[publicId]/wallet/ \
        frontend/src/components/realty/wallet/
git commit -m "feat(realty-wallet): group wallet page + components"
git push
```

---

### Task 30: `AgentFeePreview` extension + README + DEFERRED_WORK + PR

**Files:**
- Modify: `frontend/src/components/listing/AgentFeePreview.tsx`
- Modify: `README.md`
- Modify: `docs/implementation/DEFERRED_WORK.md`

Implements spec §6.3 + close-out.

- [ ] **Step 1: Extend AgentFeePreview**

Add a sub-line below the fee total when the listing is being created under a group. Read the group's wallet balance via `useGroupWallet(publicId)`. If insufficient balance, show a tooltip and ask the parent form to disable the publish button.

Test that the extension:
- Renders "Listing fee paid from {Group Name} wallet — current balance L${X}" for group flows.
- Disables publish + surfaces shortfall message when insufficient.
- Renders nothing extra for individual listings.

- [ ] **Step 2: Update README**

Add a "Sub-project D — group wallet" subsection under the realty-groups section, mirroring C's prose. Include:
- New endpoints: `GET /realty/groups/{publicId}/wallet`, `GET .../wallet/ledger`, `POST .../wallet/withdraw`.
- Listing fee routing change (group-listed auctions debit group wallet).
- Agent-fee distribution at escrow completion.
- Dormancy: weekly sweep, any member's login keeps a group active.
- Dissolution gate: now requires zero balance + no in-flight escrows.

- [ ] **Step 3: Update DEFERRED_WORK.md**

Append a "Realty Groups: Group Wallet (Sub-project D, 2026-05-11)" section noting:
- Admin group-wallet ops (force-credit / force-debit / manual ledger adjustments) — deferred to F.
- `SPEND_FROM_GROUP_WALLET` wiring — deferred to first discretionary-spend feature.
- SL-group-destination withdraws — deferred to E.
- Postman collection updates — note if not done in Task 28 (and add to E's plan).
- User-side `WalletDormancyJob` — appears absent from the codebase; flag for follow-up if not already deferred.

Remove any deferred items that D resolves (the C-era "Agent-fee L$ distribution" entry — that's done in D now).

- [ ] **Step 4: Open the PR**

```bash
gh pr create --base dev --head feat/realty-groups-group-wallet \
  --title "feat(realty-wallet): sub-project D — group wallet" \
  --body "$(cat <<'EOF'
## Summary
- Wallet columns on `realty_groups` + `realty_group_ledger` append-only table.
- Group-listed auctions debit listing fees from the group wallet.
- Agent fees distributed at escrow-payout-success: `floor(amount × split)` to group, remainder to listing agent.
- Leader + delegates with `WITHDRAW_FROM_GROUP_WALLET` can withdraw to the leader's verified SL avatar.
- Dissolution gate now requires zero balance + no in-flight escrows.
- Weekly group dormancy sweep keyed off any member's recent login.
- Three new permission enum values: `SPEND_FROM_GROUP_WALLET` (defined, not wired), `WITHDRAW_FROM_GROUP_WALLET`, `VIEW_GROUP_TRANSACTIONS`.

## Test plan
- [ ] Backend: `./mvnw test` — all green
- [ ] Frontend: `npm test && npm run verify` — all green
- [ ] Verified end-to-end: list as group → pay fee from group wallet → win → escrow completion credits group + agent
- [ ] Withdraw flow exercised against dev stack
- [ ] Dissolution gate blocks with non-zero balance / in-flight escrow

Spec: `docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md`
Plan: `docs/superpowers/plans/2026-05-11-realty-groups-group-wallet*.md`
EOF
)"
```

- [ ] **Step 5: Commit + push the final docs sweep**

```bash
git add frontend/src/components/listing/AgentFeePreview.tsx \
        README.md \
        docs/implementation/DEFERRED_WORK.md
git commit -m "docs(realty-wallet): README + DEFERRED_WORK sweep for sub-project D"
git push
```

---

End of Part 3. Plan complete.
