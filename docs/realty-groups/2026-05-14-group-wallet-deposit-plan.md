# Group-wallet deposit (app + in-world) — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let members of a realty group fund the group wallet — through
an app-flow personal-wallet transfer or through an in-world SL terminal
deposit. Both flows are gated by a new per-member permission
`DEPOSIT_TO_GROUP_WALLET` (leader-implicit).

**Architecture:** Three new HTTP endpoints (one JWT app-flow, two
SL-terminal-flow), one new group-ledger entry type
(`MEMBER_DEPOSIT`), one new personal-wallet ledger entry type
(`GROUP_WALLET_DEPOSIT_DEBIT`), one new permission. The
`slpa-terminal.lsl` script gains a third touch-menu option ("Pay to
group") with a per-avatar 60-second pending-deposit-context slot.

**Tech Stack:** Spring Boot 4 / Java 26 / Lombok / JPA / Flyway,
Next.js 16 / React 19 / TypeScript 5 / Tailwind 4 / TanStack Query /
RHF + Zod / Vitest + MSW, LSL.

**Spec:** `docs/realty-groups/2026-05-14-group-wallet-deposit-design.md`.

---

## File Structure

### Backend — new files

- `backend/src/main/resources/db/migration/V<N>__realty_group_member_deposit.sql`
- `backend/src/main/resources/db/migration/V<N+1>__user_ledger_group_wallet_deposit_debit.sql`
- `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupDepositRequest.java`
- `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupDepositResponse.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsController.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsRequest.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsResponse.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositController.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositRequest.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositService.java`
- `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupDepositControllerTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceDepositTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsControllerTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositControllerTest.java`

### Backend — modified files

- `backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java`
- `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryType.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java`
- `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java`
- `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
- `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java`
  (add `findGroupsAvatarCanDepositTo` projection query)
- `backend/src/main/resources/application.yml`
  (add `slpa.realty.group-deposit-max-l: 500000`)

### Frontend — new files

- `frontend/src/hooks/realty/useGroupDeposit.ts`
- `frontend/src/hooks/realty/useGroupDeposit.test.tsx`
- `frontend/src/components/realty/AddFundsModal.tsx`
- `frontend/src/components/realty/AddFundsModal.test.tsx`

### Frontend — modified files

- `frontend/src/lib/realty/permissions.ts`
  (add `DEPOSIT_TO_GROUP_WALLET` label)
- `frontend/src/types/realty.ts`
  (add `RealtyGroupPermission` enum value + `GroupDepositRequest` /
  `GroupDepositResponse` types)
- `frontend/src/components/realty/WalletTab.tsx`
  (add "Add funds" button + AddFundsModal mount)
- `frontend/src/components/realty/WalletTab.test.tsx`
- `frontend/src/components/realty/GroupLedgerRow.tsx`
  (add `MEMBER_DEPOSIT` case)
- `frontend/src/components/wallet/UserLedgerRow.tsx`
  (add `GROUP_WALLET_DEPOSIT_DEBIT` case with optional group link)

### LSL — modified files

- `lsl-scripts/slpa-terminal/slpa-terminal.lsl`
- `lsl-scripts/slpa-terminal/config.notecard.example`
- `lsl-scripts/slpa-terminal/README.md`

### Postman — modified

- SLPA collection (collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`).

---

## Part 1 — Backend foundation

### Task 1: New permission enum value + flyway migration

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java`
- Create: `backend/src/main/resources/db/migration/V<N>__realty_group_permission_deposit.sql`

The `realty_group_members.permissions` column is a `TEXT[]` of enum
names. Adding a new value does **not** require a CHECK migration —
existing rows simply omit the new value, and PG's `text[]` accepts
any string. **No data backfill.**

If a CHECK constraint enumerates allowed values (check
`realty_group_members.sql` migration history), the new enum value must
be added to the constraint. Inspect at:

```bash
psql -h ... -U ... -d slpa -c "SELECT pg_get_constraintdef(c.oid) \
  FROM pg_constraint c WHERE conrelid = 'realty_group_members'::regclass;"
```

If no CHECK constraint exists on `permissions`, skip the SQL migration
entirely. Otherwise emit:

```sql
-- V<N>__realty_group_permission_deposit.sql
ALTER TABLE realty_group_members
    DROP CONSTRAINT realty_group_members_permissions_check;
ALTER TABLE realty_group_members
    ADD CONSTRAINT realty_group_members_permissions_check
    CHECK (permissions <@ ARRAY[
        'INVITE_AGENTS','REMOVE_AGENTS','EDIT_GROUP_PROFILE','CONFIGURE_FEES',
        'CREATE_LISTING','MANAGE_ALL_LISTINGS','WITHDRAW_FROM_GROUP_WALLET',
        'VIEW_GROUP_TRANSACTIONS','REGISTER_SL_GROUP','MANAGE_MEMBERS',
        'DEPOSIT_TO_GROUP_WALLET'
    ]::text[]);
```

- [ ] **Step 1: Inspect the existing CHECK constraint shape**

```bash
grep -r "realty_group_members_permissions_check" backend/src/main/resources/db/migration/
```

Expected: zero or one match. If zero, the column has no constraint and
the SQL migration in this task is unnecessary. If one match, copy the
exact `CHECK (...)` body shape into the new migration.

- [ ] **Step 2: Append the enum value**

```java
// backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java
public enum RealtyGroupPermission {
    INVITE_AGENTS,
    REMOVE_AGENTS,
    EDIT_GROUP_PROFILE,
    CONFIGURE_FEES,
    CREATE_LISTING,
    MANAGE_ALL_LISTINGS,
    WITHDRAW_FROM_GROUP_WALLET,
    VIEW_GROUP_TRANSACTIONS,
    REGISTER_SL_GROUP,
    MANAGE_MEMBERS,

    /** Sub-project H -- deposit personal SLParcels funds (or in-world L$) into
     *  the group wallet. App flow and in-world flow both gate on this. */
    DEPOSIT_TO_GROUP_WALLET;
}
```

- [ ] **Step 3: Write the migration if step 1 found a constraint**

Save as `V<N>__realty_group_permission_deposit.sql` with the next
available migration number.

- [ ] **Step 4: Verify Flyway resolves and applies**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: backend starts; `Successfully applied 1 migration` if a SQL
file was written. Ctrl-C after confirmation.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java
# Stage the migration file if one was written
git add backend/src/main/resources/db/migration/V*__realty_group_permission_deposit.sql 2>/dev/null
git commit -m "feat(realty): add DEPOSIT_TO_GROUP_WALLET permission"
```

---

### Task 2: New group-ledger entry type + flyway migration

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryType.java`
- Create: `backend/src/main/resources/db/migration/V<N>__realty_group_member_deposit.sql`

- [ ] **Step 1: Add the enum value**

```java
// backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryType.java
public enum RealtyGroupLedgerEntryType {
    LISTING_FEE_DEBIT,
    LISTING_FEE_REFUND,
    AGENT_FEE_CREDIT,
    LISTING_PAYOUT,
    WITHDRAW_QUEUED,
    WITHDRAW_COMPLETED,
    WITHDRAW_REVERSED,
    DORMANCY_AUTO_RETURN,
    ADJUSTMENT,
    ADMIN_ADJUSTMENT,
    /**
     * Sub-project H -- member-initiated deposit into the group wallet.
     * Covers both the app flow (personal SLParcels wallet -> group
     * wallet) and the in-world flow (avatar pays L$ at a terminal,
     * routed to a chosen group). actor_user_id carries the depositing
     * member; description distinguishes "Deposit from app wallet" vs
     * "Deposit at terminal in <region>" and may carry an optional
     * 200-char user memo.
     */
    MEMBER_DEPOSIT
}
```

- [ ] **Step 2: Inspect the existing entry_type CHECK constraint**

```bash
grep -A 20 "realty_group_ledger.*entry_type.*CHECK" backend/src/main/resources/db/migration/V*.sql
```

Find the most recent migration that defines or extends the
`entry_type` CHECK. Copy the `CHECK (entry_type IN (...))` form and
extend it.

- [ ] **Step 3: Write the migration**

```sql
-- V<N>__realty_group_member_deposit.sql
ALTER TABLE realty_group_ledger
    DROP CONSTRAINT realty_group_ledger_entry_type_check;
ALTER TABLE realty_group_ledger
    ADD CONSTRAINT realty_group_ledger_entry_type_check
    CHECK (entry_type IN (
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND', 'AGENT_FEE_CREDIT',
        'LISTING_PAYOUT', 'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED',
        'WITHDRAW_REVERSED', 'DORMANCY_AUTO_RETURN', 'ADJUSTMENT',
        'ADMIN_ADJUSTMENT', 'MEMBER_DEPOSIT'
    ));
```

If the actual constraint name in the existing migration differs, use
the name from grep output verbatim.

- [ ] **Step 4: Run Flyway and verify**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: `Successfully applied 1 migration to schema "public"`. Ctrl-C.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryType.java \
        backend/src/main/resources/db/migration/V*__realty_group_member_deposit.sql
git commit -m "feat(realty): add MEMBER_DEPOSIT group-ledger entry type"
```

---

### Task 3: New user-ledger entry type + flyway migration

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java`
- Create: `backend/src/main/resources/db/migration/V<N>__user_ledger_group_wallet_deposit_debit.sql`

- [ ] **Step 1: Add the enum value**

```java
// backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java
// Append after AGENT_COMMISSION_CREDIT:

/**
 * Sub-project H -- debit on a member-initiated transfer from this user's
 * personal SLParcels wallet into a realty group's wallet. Paired with a
 * {@code MEMBER_DEPOSIT} row on {@code realty_group_ledger} that shares
 * the same {@code idempotencyKey}. The {@code description} carries the
 * group display name (and optional user-supplied memo).
 */
GROUP_WALLET_DEPOSIT_DEBIT
```

- [ ] **Step 2: Inspect existing user_ledger entry_type CHECK constraint**

```bash
grep -A 20 "user_ledger.*entry_type.*CHECK" backend/src/main/resources/db/migration/V*.sql
```

- [ ] **Step 3: Write the migration**

```sql
-- V<N>__user_ledger_group_wallet_deposit_debit.sql
ALTER TABLE user_ledger
    DROP CONSTRAINT user_ledger_entry_type_check;
ALTER TABLE user_ledger
    ADD CONSTRAINT user_ledger_entry_type_check
    CHECK (entry_type IN (
        'DEPOSIT','WITHDRAW_QUEUED','WITHDRAW_COMPLETED','WITHDRAW_REVERSED',
        'BID_RESERVED','BID_RELEASED','ESCROW_DEBIT','ESCROW_REFUND',
        'LISTING_FEE_DEBIT','LISTING_FEE_REFUND','PENALTY_DEBIT','ADJUSTMENT',
        'AGENT_FEE_CREDIT','AGENT_COMMISSION_CREDIT',
        'GROUP_WALLET_DEPOSIT_DEBIT'
    ));
```

If the actual constraint name differs from `user_ledger_entry_type_check`,
use the name from grep output.

- [ ] **Step 4: Run Flyway and verify**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: migration applied. Ctrl-C.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java \
        backend/src/main/resources/db/migration/V*__user_ledger_group_wallet_deposit_debit.sql
git commit -m "feat(wallet): add GROUP_WALLET_DEPOSIT_DEBIT user-ledger entry type"
```

---

### Task 4: Add per-call max to application.yml

**Files:**
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Add the property**

Find the existing `slpa.realty.*` block (e.g.
`slpa.realty.admin-wallet-adjust-max-l`) and add a sibling:

```yaml
slpa:
  realty:
    # ...existing realty config...
    group-deposit-max-l: 500000
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/resources/application.yml
git commit -m "config(realty): add slpa.realty.group-deposit-max-l (default 500000)"
```

---

### Task 5: Service method — app-flow deposit

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceDepositTest.java`

**Why a service method here vs reusing existing pieces:** The atomic
debit-personal + credit-group has no existing counterpart. `withdraw`
in this class only touches the group ledger; the personal-wallet
ledger is owned by `WalletService`. We expose a single new method that
calls into both, holding both wallet rows under the same transaction.

- [ ] **Step 1: Write the failing service test**

```java
// backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceDepositTest.java
package com.slparcelauctions.backend.realty.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.exception.DepositAmountOutOfRangeException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;

@SpringBootTest
@Transactional
class RealtyGroupWalletServiceDepositTest {

    @Autowired RealtyGroupWalletService service;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupLedgerRepository groupLedgerRepository;
    @Autowired UserLedgerRepository userLedgerRepository;

    // Helper: seed a member user with N L$ balance and a group with M L$ balance.
    // Implementation details depend on existing test fixtures; mirror the
    // setup used by RealtyGroupWalletServiceTest in this package.

    @Test
    void deposit_credits_group_and_debits_user_atomically() {
        // GIVEN a member with L$5000 and a group at L$1000
        User member = seedMemberWithBalance(5000L);
        RealtyGroup g = seedGroupWithBalance(1000L);

        // WHEN deposit L$1200
        UUID idem = UUID.randomUUID();
        RealtyGroupWalletService.DepositResult r = service.depositFromMemberWallet(
            g.getId(), 1200L, member.getId(), "Reimbursement", idem);

        // THEN group balance = 2200, user balance = 3800
        assertThat(groupRepository.findById(g.getId()).orElseThrow().getBalanceLindens())
            .isEqualTo(2200L);
        assertThat(userRepository.findById(member.getId()).orElseThrow().getBalanceLindens())
            .isEqualTo(3800L);

        // AND two ledger rows exist, sharing idem
        assertThat(groupLedgerRepository.findByIdempotencyKey(idem.toString())).isPresent();
        assertThat(userLedgerRepository.findByIdempotencyKey(idem.toString())).isPresent();
    }

    @Test
    void deposit_rejects_insufficient_balance() {
        User member = seedMemberWithBalance(500L);
        RealtyGroup g = seedGroupWithBalance(0L);

        assertThatThrownBy(() -> service.depositFromMemberWallet(
                g.getId(), 1000L, member.getId(), null, UUID.randomUUID()))
            .isInstanceOf(InsufficientAvailableBalanceException.class);
    }

    @Test
    void deposit_rejects_amount_out_of_range_min() {
        User member = seedMemberWithBalance(5000L);
        RealtyGroup g = seedGroupWithBalance(0L);

        assertThatThrownBy(() -> service.depositFromMemberWallet(
                g.getId(), 0L, member.getId(), null, UUID.randomUUID()))
            .isInstanceOf(DepositAmountOutOfRangeException.class);
    }

    @Test
    void deposit_rejects_amount_out_of_range_max() {
        User member = seedMemberWithBalance(2_000_000L);
        RealtyGroup g = seedGroupWithBalance(0L);

        assertThatThrownBy(() -> service.depositFromMemberWallet(
                g.getId(), 1_500_000L, member.getId(), null, UUID.randomUUID()))
            .isInstanceOf(DepositAmountOutOfRangeException.class);
    }

    @Test
    void deposit_idempotent_replay_returns_same_ids() {
        User member = seedMemberWithBalance(5000L);
        RealtyGroup g = seedGroupWithBalance(0L);
        UUID idem = UUID.randomUUID();

        RealtyGroupWalletService.DepositResult r1 = service.depositFromMemberWallet(
            g.getId(), 100L, member.getId(), null, idem);
        RealtyGroupWalletService.DepositResult r2 = service.depositFromMemberWallet(
            g.getId(), 100L, member.getId(), null, idem);

        assertThat(r2.groupLedgerEntryId()).isEqualTo(r1.groupLedgerEntryId());
        assertThat(r2.personalLedgerEntryId()).isEqualTo(r1.personalLedgerEntryId());

        // Group balance was only credited once.
        assertThat(groupRepository.findById(g.getId()).orElseThrow().getBalanceLindens())
            .isEqualTo(100L);
    }

    // seedMemberWithBalance / seedGroupWithBalance helpers omitted -- copy
    // from RealtyGroupWalletServiceTest in this package and extend if needed.
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd backend && ./mvnw test -Dtest=RealtyGroupWalletServiceDepositTest
```

Expected: FAIL — method `depositFromMemberWallet` does not exist.

- [ ] **Step 3: Add the new exception class**

```java
// backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/DepositAmountOutOfRangeException.java
package com.slparcelauctions.backend.realty.wallet.exception;

public class DepositAmountOutOfRangeException extends RuntimeException {
    private final long min;
    private final long max;
    private final long requested;

    public DepositAmountOutOfRangeException(long min, long max, long requested) {
        super("amount " + requested + " out of range [" + min + ", " + max + "]");
        this.min = min;
        this.max = max;
        this.requested = requested;
    }

    public long getMin() { return min; }
    public long getMax() { return max; }
    public long getRequested() { return requested; }
}
```

- [ ] **Step 4: Implement the service method**

In `RealtyGroupWalletService.java`, add a `DepositResult` record and a
`depositFromMemberWallet` method. The shape mirrors the existing
`withdraw` method's idempotency check pattern. Key requirements:

- `@Transactional` (own tx, default propagation REQUIRED).
- Range check first (no DB hit needed).
- Idempotency check by `idempotencyKey.toString()` on
  `RealtyGroupLedgerRepository.findByIdempotencyKey`. If found,
  fetch the paired user-ledger row by the same key and return both
  ids.
- Lock both wallet rows: group via `findByIdForUpdate`, user via
  the same on `UserRepository` (add helper there if missing —
  follow withdraw's pattern).
- Reject group dissolved + suspended via `realtyGroupGuard.requireGroupCanOperate(groupId)`.
- Check user not frozen (mirror withdraw's leader-frozen check shape;
  for `walletFrozenAt` / `bannedFromListing`).
- Check `user.balance >= amount`; throw
  `InsufficientAvailableBalanceException(available, amount)`.
- Debit `user.balance -= amount`. Save.
- Insert `user_ledger` row: type `GROUP_WALLET_DEPOSIT_DEBIT`, amount
  `+amount` (per existing convention: amount is positive, direction
  is in the type), idempotencyKey = `idem.toString()`, description =
  `"Deposit to " + group.getName() + (memo != null ? " — " + memo : "")`.
- Credit `group.balance += amount`. Call `clearDormancyOnActivity(group)`.
  Save.
- Insert `realty_group_ledger` row: type `MEMBER_DEPOSIT`,
  amount = `+amount`, actorUserId = `userId`, idempotencyKey same,
  description = `"Deposit from app wallet" + (memo != null ? " — " +
  memo : "")`.
- Broadcast via `broadcastPublisher.publish(...)` matching the
  pattern in `creditPayout`.
- Return `new DepositResult(groupLedgerRow.getId(),
  userLedgerRow.getId(), group.availableLindens(),
  user.availableLindens())`.

Reference: copy the idempotency / locking / balance-mutation skeleton
from `withdraw` (RealtyGroupWalletService.java:255-360) and adapt.

```java
public record DepositResult(
    Long groupLedgerEntryId,
    Long personalLedgerEntryId,
    long newGroupAvailable,
    long newPersonalAvailable) {}

@Transactional
public DepositResult depositFromMemberWallet(
        Long groupId, long amount, Long userId, String memo, UUID idempotencyKey) {
    // 1. range check
    long max = configuredMaxDepositL();   // injected via @Value("${slpa.realty.group-deposit-max-l}")
    if (amount < 1 || amount > max) {
        throw new DepositAmountOutOfRangeException(1, max, amount);
    }

    // 2. idempotency check (return early on replay)
    String idemStr = idempotencyKey.toString();
    Optional<RealtyGroupLedgerEntry> priorGroup = ledgerRepository.findByIdempotencyKey(idemStr);
    if (priorGroup.isPresent()) {
        UserLedgerEntry priorUser = userLedgerRepository.findByIdempotencyKey(idemStr)
            .orElseThrow(() -> new IllegalStateException(
                "group ledger row " + priorGroup.get().getId() + " has no paired user ledger row"));
        RealtyGroup g = groupRepository.findById(groupId).orElseThrow();
        User u = userRepository.findById(userId).orElseThrow();
        return new DepositResult(priorGroup.get().getId(), priorUser.getId(),
            g.availableLindens(), u.availableLindens());
    }

    // 3. group + user state checks
    realtyGroupGuard.requireGroupCanOperate(groupId);
    RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
    User user = userRepository.findByIdForUpdate(userId).orElseThrow();
    if (user.getWalletFrozenAt() != null) {
        throw new UserStatusBlockedException("user wallet frozen");
    }
    long available = user.getBalanceLindens();
    if (available < amount) {
        throw new InsufficientAvailableBalanceException(available, amount);
    }

    // 4. debit personal wallet
    user.setBalanceLindens(available - amount);
    userRepository.save(user);
    String userDescription = "Deposit to " + group.getName()
        + (memo != null && !memo.isBlank() ? " — " + memo : "");
    UserLedgerEntry userEntry = userLedgerRepository.save(UserLedgerEntry.builder()
        .userId(userId)
        .entryType(UserLedgerEntryType.GROUP_WALLET_DEPOSIT_DEBIT)
        .amount(amount)
        .balanceAfter(user.getBalanceLindens())
        .reservedAfter(user.getReservedLindens())
        .idempotencyKey(idemStr)
        .refType("REALTY_GROUP")
        .refId(group.getId())
        .description(userDescription)
        .build());

    // 5. credit group wallet
    long newGroupBalance = group.getBalanceLindens() + amount;
    group.setBalanceLindens(newGroupBalance);
    clearDormancyOnActivity(group);
    groupRepository.save(group);
    String groupDescription = "Deposit from app wallet"
        + (memo != null && !memo.isBlank() ? " — " + memo : "");
    RealtyGroupLedgerEntry groupEntry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
        .groupId(group.getId())
        .entryType(RealtyGroupLedgerEntryType.MEMBER_DEPOSIT)
        .amount(amount)
        .balanceAfter(newGroupBalance)
        .reservedAfter(group.getReservedLindens())
        .actorUserId(userId)
        .idempotencyKey(idemStr)
        .description(groupDescription)
        .build());

    broadcastPublisher.publish(group.getPublicId(),
        newGroupBalance, group.getReservedLindens(), group.availableLindens(),
        RealtyGroupLedgerEntryType.MEMBER_DEPOSIT.name(), groupEntry.getPublicId());

    log.info("group member deposit: groupId={}, userId={}, amount={}, idem={}",
        group.getId(), userId, amount, idemStr);

    return new DepositResult(groupEntry.getId(), userEntry.getId(),
        group.availableLindens(), user.getBalanceLindens());
}
```

Inject the max property:
```java
@Value("${slpa.realty.group-deposit-max-l:500000}")
private long groupDepositMaxL;

private long configuredMaxDepositL() { return groupDepositMaxL; }
```

`UserRepository.findByIdForUpdate` may not exist yet — if not, add it
following the existing `findByIdForUpdate` pattern on
`RealtyGroupRepository`. `UserLedgerRepository.findByIdempotencyKey`
also may not exist; add it as a derived query.

- [ ] **Step 5: Run the tests to confirm they pass**

```bash
cd backend && ./mvnw test -Dtest=RealtyGroupWalletServiceDepositTest
```

Expected: 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/DepositAmountOutOfRangeException.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceDepositTest.java
git commit -m "feat(realty): RealtyGroupWalletService.depositFromMemberWallet"
```

---

### Task 6: HTTP controller — `POST /api/v1/realty/groups/{publicId}/wallet/deposit`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupDepositRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupDepositResponse.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupDepositControllerTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java`
  (map `DepositAmountOutOfRangeException` → 400)

- [ ] **Step 1: Write the failing controller test**

```java
// backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupDepositControllerTest.java
package com.slparcelauctions.backend.realty.wallet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RealtyGroupDepositControllerTest {

    @Autowired MockMvc mvc;
    // standard test fixtures: seedLeader(), seedAgentWithPermission(perm),
    // seedAgentWithoutPermission(), bearerToken(user), etc. Mirror existing
    // RealtyGroupWalletController tests in this package.

    @Test
    void leader_can_deposit() throws Exception {
        // ... seed group, leader with L$5000 personal balance ...

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", bearerToken(leader))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount": 1000, "memo": "test", "idempotencyKey": "00000000-0000-0000-0000-000000000001"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newGroupAvailable").value(1000))
            .andExpect(jsonPath("$.newPersonalAvailable").value(4000));
    }

    @Test
    void agent_without_permission_is_forbidden() throws Exception {
        // ...
        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", bearerToken(agent))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount": 100, "idempotencyKey": "00000000-0000-0000-0000-000000000002"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void agent_with_permission_can_deposit() throws Exception {
        // ... grant DEPOSIT_TO_GROUP_WALLET to agent ...
        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", bearerToken(agent))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount": 100, "idempotencyKey": "00000000-0000-0000-0000-000000000003"}
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void rejects_insufficient_balance() throws Exception {
        // leader with L$10 trying to deposit L$100
        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", bearerToken(leader))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount": 100, "idempotencyKey": "00000000-0000-0000-0000-000000000004"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("INSUFFICIENT_BALANCE")));
    }

    @Test
    void rejects_amount_above_max() throws Exception {
        // amount > slpa.realty.group-deposit-max-l (500_000 default)
        // ...
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("AMOUNT_OUT_OF_RANGE")));
    }

    @Test
    void dissolved_group_returns_410() throws Exception { /* ... */ }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd backend && ./mvnw test -Dtest=RealtyGroupDepositControllerTest
```

Expected: FAIL — route does not exist (404).

- [ ] **Step 3: Create the DTOs**

```java
// backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupDepositRequest.java
package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GroupDepositRequest(
    @NotNull @Min(1) Long amount,
    @Size(max = 200) String memo,
    @NotNull UUID idempotencyKey
) {}
```

```java
// backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupDepositResponse.java
package com.slparcelauctions.backend.realty.wallet.dto;

public record GroupDepositResponse(
    Long groupLedgerEntryId,
    Long personalLedgerEntryId,
    long newGroupAvailable,
    long newPersonalAvailable
) {}
```

- [ ] **Step 4: Add the controller route**

```java
// In RealtyGroupWalletController.java, after the withdraw method:
@PostMapping("/deposit")
@Transactional   // re-entered by service; outer tx ensures DTO load is consistent
public GroupDepositResponse deposit(
        @PathVariable UUID publicId,
        @Valid @RequestBody GroupDepositRequest req,
        @AuthenticationPrincipal AuthPrincipal principal) {
    RealtyGroup g = groupRepository.findByPublicId(publicId)
        .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
    if (g.getDissolvedAt() != null) {
        throw new GroupDissolvedException(publicId);
    }
    authorizer.assertCan(principal.userId(), g.getId(),
        RealtyGroupPermission.DEPOSIT_TO_GROUP_WALLET);

    RealtyGroupWalletService.DepositResult r = walletService.depositFromMemberWallet(
        g.getId(), req.amount(), principal.userId(), req.memo(), req.idempotencyKey());

    return new GroupDepositResponse(
        r.groupLedgerEntryId(), r.personalLedgerEntryId(),
        r.newGroupAvailable(), r.newPersonalAvailable());
}
```

- [ ] **Step 5: Map `DepositAmountOutOfRangeException` to 400**

In `RealtyExceptionHandler.java`, add a handler:

```java
@ExceptionHandler(DepositAmountOutOfRangeException.class)
public ProblemDetail handleAmountOutOfRange(DepositAmountOutOfRangeException e) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
        "AMOUNT_OUT_OF_RANGE: " + e.getMessage());
    p.setProperty("min", e.getMin());
    p.setProperty("max", e.getMax());
    return p;
}
```

`InsufficientAvailableBalanceException` should already have a handler
(used by `WalletService.withdrawCommon`). If it's mapped in
`WalletExceptionHandler` (personal-wallet scope) but the realty
controller emits it, decide between:

- (a) Letting the realty controller's package-scoped advice handle it.
  Add a duplicate handler in `RealtyExceptionHandler` returning
  `INSUFFICIENT_BALANCE` in the detail.
- (b) Re-throwing it as a realty-specific `GroupDepositInsufficientBalanceException`
  inside the service.

Use **(a)** — simpler, no new exception type.

```java
@ExceptionHandler(InsufficientAvailableBalanceException.class)
public ProblemDetail handleInsufficientBalance(InsufficientAvailableBalanceException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
        "INSUFFICIENT_BALANCE: available=" + e.getAvailable());
}
```

- [ ] **Step 6: Run the tests to confirm they pass**

```bash
cd backend && ./mvnw test -Dtest=RealtyGroupDepositControllerTest
```

Expected: 6 tests pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupDeposit*.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupDepositControllerTest.java
git commit -m "feat(realty): POST /api/v1/realty/groups/{publicId}/wallet/deposit"
```

---

## Part 2 — In-world backend

### Task 7: `POST /api/v1/sl/wallet/avatar-groups`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java`
  (add a query)
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsControllerTest.java
package com.slparcelauctions.backend.wallet.sl.group;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
// ... imports

@SpringBootTest
@AutoConfigureMockMvc
class SlAvatarGroupsControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void leader_sees_their_group() throws Exception {
        User leader = seedLeaderWithSlAvatar();
        RealtyGroup g = seedGroupLedBy(leader);

        mvc.perform(post("/api/v1/sl/wallet/avatar-groups")
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", trustedOwnerKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req("term-1", leader.getSlAvatarUuid()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups[0].publicId").value(g.getPublicId().toString()))
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void agent_without_permission_sees_empty() throws Exception {
        User agent = seedAgentWithSlAvatar();
        seedGroupWithAgentNoPermission(agent);

        mvc.perform(post("/api/v1/sl/wallet/avatar-groups")
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", trustedOwnerKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req("term-1", agent.getSlAvatarUuid()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups").isEmpty())
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void agent_with_permission_sees_group() throws Exception { /* ... */ }

    @Test
    void unknown_avatar_returns_empty_list() throws Exception {
        mvc.perform(post("/api/v1/sl/wallet/avatar-groups")
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", trustedOwnerKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req("term-1", UUID.randomUUID()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups").isEmpty());
    }

    @Test
    void bad_shared_secret_is_403() throws Exception { /* ... */ }

    @Test
    void pagination_returns_hasMore_and_nextAfter() throws Exception {
        // seed 15 groups with avatar as leader; expect groups.length = 12, hasMore = true
        // call again with after = response.nextAfter; expect 3 more, hasMore = false
    }
}
```

- [ ] **Step 2: Run to confirm fail**

```bash
cd backend && ./mvnw test -Dtest=SlAvatarGroupsControllerTest
```

Expected: FAIL (404 on route).

- [ ] **Step 3: Add the repository projection query**

```java
// backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java
// Add:

interface GroupNameAndPublicIdProjection {
    UUID getPublicId();
    String getName();
}

/**
 * Groups the given user is a member of (or leader of) that are operable
 * (not dissolved, not suspended) AND for which the user holds
 * DEPOSIT_TO_GROUP_WALLET (leader-implicit). Returned alphabetically by
 * group name; pagination via the {@code after} cursor (exclusive).
 *
 * The leader-implicit short-circuit is expressed by the OR clause:
 * either the user is the leader OR the permissions array contains the
 * literal 'DEPOSIT_TO_GROUP_WALLET'.
 *
 * Operable = dissolved_at IS NULL AND no active suspension.
 */
@Query(value = """
    SELECT g.public_id AS publicId, g.name AS name
    FROM realty_groups g
    LEFT JOIN realty_group_members m ON m.realty_group_id = g.id AND m.user_id = :userId
    WHERE g.dissolved_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM realty_group_suspensions s
          WHERE s.realty_group_id = g.id
            AND s.lifted_at IS NULL
            AND s.expires_at IS NULL OR s.expires_at > now()
      )
      AND (g.leader_id = :userId OR ('DEPOSIT_TO_GROUP_WALLET' = ANY(m.permissions)))
      AND (CAST(:after AS text) IS NULL OR lower(g.name) > lower(CAST(:after AS text)))
    ORDER BY lower(g.name) ASC
    LIMIT :limit
    """, nativeQuery = true)
List<GroupNameAndPublicIdProjection> findGroupsAvatarCanDepositTo(
        @Param("userId") Long userId,
        @Param("after") String after,
        @Param("limit") int limit);
```

The `CAST(:after AS text)` form is mandatory — passing null to a
`text` parameter is silently typed as `bytea` by PG and breaks
`lower(...)`. See `FOOTGUNS.md` and the admin-search fix on the dev
branch.

- [ ] **Step 4: Add the DTOs**

```java
// SlAvatarGroupsRequest.java
public record SlAvatarGroupsRequest(
    @NotBlank String terminalId,
    @NotBlank String sharedSecret,
    @NotBlank String avatarUuid,
    String after                       // optional, exclusive cursor
) {}

// SlAvatarGroupsResponse.java
public record SlAvatarGroupsResponse(
    List<GroupRef> groups,
    boolean hasMore,
    String nextAfter
) {
    public record GroupRef(UUID publicId, String name) {}
}
```

- [ ] **Step 5: Implement the controller**

```java
// SlAvatarGroupsController.java
@RestController
@RequestMapping("/api/v1/sl/wallet")
@RequiredArgsConstructor
@Slf4j
public class SlAvatarGroupsController {

    private static final int PAGE_SIZE = 12;

    private final TerminalService terminalService;
    private final TerminalRepository terminalRepository;
    private final SlHeaderValidator headerValidator;
    private final UserRepository userRepository;
    private final RealtyGroupMemberRepository memberRepository;

    @PostMapping("/avatar-groups")
    @Transactional(readOnly = true)
    public SlAvatarGroupsResponse avatarGroups(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlAvatarGroupsRequest req) {
        headerValidator.validate(shard, ownerKey);
        terminalService.assertSharedSecret(req.sharedSecret());
        if (!terminalRepository.existsById(req.terminalId())) {
            throw new UnknownTerminalException(req.terminalId());
        }
        terminalService.markSeen(req.terminalId());

        UUID avatarUuid;
        try {
            avatarUuid = UUID.fromString(req.avatarUuid());
        } catch (IllegalArgumentException e) {
            return new SlAvatarGroupsResponse(List.of(), false, null);
        }

        Long userId = userRepository.findIdBySlAvatarUuid(avatarUuid).orElse(null);
        if (userId == null) {
            return new SlAvatarGroupsResponse(List.of(), false, null);
        }

        // PAGE_SIZE + 1 to detect "more" without a second query.
        List<RealtyGroupMemberRepository.GroupNameAndPublicIdProjection> rows =
            memberRepository.findGroupsAvatarCanDepositTo(userId, req.after(), PAGE_SIZE + 1);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<RealtyGroupMemberRepository.GroupNameAndPublicIdProjection> page =
            hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        List<SlAvatarGroupsResponse.GroupRef> groups = page.stream()
            .map(p -> new SlAvatarGroupsResponse.GroupRef(p.getPublicId(), p.getName()))
            .toList();
        String nextAfter = hasMore ? page.get(page.size() - 1).getName() : null;

        return new SlAvatarGroupsResponse(groups, hasMore, nextAfter);
    }
}
```

- [ ] **Step 6: Run tests, confirm pass**

```bash
cd backend && ./mvnw test -Dtest=SlAvatarGroupsControllerTest
```

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/ \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/wallet/sl/group/SlAvatarGroupsControllerTest.java
git commit -m "feat(sl): POST /api/v1/sl/wallet/avatar-groups (terminal-side group enumeration)"
```

---

### Task 8: `POST /api/v1/sl/wallet/group-deposit`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
  (add `depositFromTerminal` method)
- Create: `backend/src/test/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// SlGroupDepositControllerTest.java
@SpringBootTest
@AutoConfigureMockMvc
class SlGroupDepositControllerTest {

    @Test
    void member_with_permission_deposits_to_group() throws Exception {
        // ... seed leader with sl avatar, group, terminal in region "Hadron" ...
        mvc.perform(post("/api/v1/sl/wallet/group-deposit")
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", trustedOwnerKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req("term-1", leader.getSlAvatarUuid(), group.getPublicId(),
                        1000L, "tx-1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("OK"));
    }

    @Test
    void permission_revoked_returns_refund() throws Exception {
        // ... member has slAvatarUuid but lost the permission ...
            .andExpect(jsonPath("$.outcome").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("PERMISSION_REVOKED"));
    }

    @Test
    void dissolved_group_returns_refund() throws Exception {
            .andExpect(jsonPath("$.outcome").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("GROUP_DISSOLVED"));
    }

    @Test
    void unknown_group_returns_refund() throws Exception {
            .andExpect(jsonPath("$.outcome").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("UNKNOWN_GROUP"));
    }

    @Test
    void unknown_payer_returns_refund() throws Exception {
            .andExpect(jsonPath("$.outcome").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("UNKNOWN_PAYER"));
    }

    @Test
    void idempotent_replay_returns_ok_with_same_ledger() throws Exception {
        // submit twice with same slTransactionKey; expect both OK,
        // group balance only credited once
    }

    @Test
    void bad_shared_secret_returns_error() throws Exception {
            .andExpect(jsonPath("$.outcome").value("ERROR"));
    }
}
```

- [ ] **Step 2: Run to confirm fail**

- [ ] **Step 3: DTOs**

```java
public record SlGroupDepositRequest(
    @NotBlank String terminalId,
    @NotBlank String sharedSecret,
    @NotBlank String payerUuid,
    @NotNull UUID groupPublicId,
    @NotNull @Min(1) Long amount,
    @NotBlank String slTransactionKey
) {}
```

Response reuses the existing `SlWalletResponse` shape from
`com.slparcelauctions.backend.wallet.sl.SlWalletResponse` (with `OK`,
`REFUND`, `ERROR` outcomes and `reason` field). Add the new reasons
to `SlWalletResponseReason`:

```java
// SlWalletResponseReason.java
public enum SlWalletResponseReason {
    // ... existing ...
    UNKNOWN_GROUP,
    GROUP_DISSOLVED,
    GROUP_SUSPENDED,
    PERMISSION_REVOKED,
    AMOUNT_OUT_OF_RANGE
}
```

(`UNKNOWN_PAYER`, `USER_FROZEN` already exist for the personal flow.)

- [ ] **Step 4: Service — add `depositFromTerminal` to `RealtyGroupWalletService`**

Mirror `depositFromMemberWallet` but without the personal-wallet leg:

```java
public record TerminalDepositResult(Long groupLedgerEntryId, long newGroupAvailable) {}

@Transactional
public TerminalDepositResult depositFromTerminal(
        Long groupId, long amount, Long depositorUserId, String regionName,
        String slTransactionKey) {
    long max = configuredMaxDepositL();
    if (amount < 1 || amount > max) {
        throw new DepositAmountOutOfRangeException(1, max, amount);
    }

    // idempotency: same slTransactionKey returns prior row, no re-credit
    Optional<RealtyGroupLedgerEntry> prior =
        ledgerRepository.findByIdempotencyKey(slTransactionKey);
    if (prior.isPresent()) {
        RealtyGroup g = groupRepository.findById(groupId).orElseThrow();
        return new TerminalDepositResult(prior.get().getId(), g.availableLindens());
    }

    realtyGroupGuard.requireGroupCanOperate(groupId);
    RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();

    long newBalance = group.getBalanceLindens() + amount;
    group.setBalanceLindens(newBalance);
    clearDormancyOnActivity(group);
    groupRepository.save(group);

    String description = "Deposit at terminal in "
        + (regionName == null ? "<unknown region>" : regionName);
    RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
        .groupId(group.getId())
        .entryType(RealtyGroupLedgerEntryType.MEMBER_DEPOSIT)
        .amount(amount)
        .balanceAfter(newBalance)
        .reservedAfter(group.getReservedLindens())
        .actorUserId(depositorUserId)
        .idempotencyKey(slTransactionKey)
        .description(description)
        .slTransactionId(slTransactionKey)
        .build());

    broadcastPublisher.publish(group.getPublicId(),
        newBalance, group.getReservedLindens(), group.availableLindens(),
        RealtyGroupLedgerEntryType.MEMBER_DEPOSIT.name(), entry.getPublicId());

    log.info("group terminal deposit: groupId={}, depositorUserId={}, amount={}, slTxn={}",
        group.getId(), depositorUserId, amount, slTransactionKey);

    return new TerminalDepositResult(entry.getId(), group.availableLindens());
}
```

- [ ] **Step 5: Controller — `SlGroupDepositController.java`**

This is the highest-stakes controller in the change: L$ is in the
script's hand. **All post-auth failures return REFUND** per
`CLAUDE.md`. Only header / shared-secret / unknown-terminal /
malformed-UUID failures return ERROR.

```java
@RestController
@RequestMapping("/api/v1/sl/wallet")
@RequiredArgsConstructor
@Slf4j
public class SlGroupDepositController {

    private final TerminalService terminalService;
    private final TerminalRepository terminalRepository;
    private final SlHeaderValidator headerValidator;
    private final UserRepository userRepository;
    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupAuthorizer authorizer;
    private final RealtyGroupWalletService walletService;

    @PostMapping("/group-deposit")
    public SlWalletResponse groupDeposit(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlGroupDepositRequest req) {
        headerValidator.validate(shard, ownerKey);
        terminalService.assertSharedSecret(req.sharedSecret());

        // ERROR-class failures (pre-L$-in-hand auth/identity): script logs but
        // does not bounce -- but the script bounces defensively per CLAUDE.md.
        // After these checks every failure is REFUND.
        if (!terminalRepository.existsById(req.terminalId())) {
            return SlWalletResponse.error(SlWalletResponseReason.UNKNOWN_TERMINAL,
                "terminalId not registered");
        }
        terminalService.markSeen(req.terminalId());

        UUID payerUuid;
        try {
            payerUuid = UUID.fromString(req.payerUuid());
        } catch (IllegalArgumentException e) {
            return SlWalletResponse.error(SlWalletResponseReason.UNKNOWN_TERMINAL,
                "payerUuid not parseable");
        }

        Long userId = userRepository.findIdBySlAvatarUuid(payerUuid).orElse(null);
        if (userId == null) {
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_PAYER,
                "no SLParcels account linked to this avatar");
        }

        RealtyGroup group = groupRepository.findByPublicId(req.groupPublicId()).orElse(null);
        if (group == null) {
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_GROUP,
                "unknown group");
        }
        if (group.getDissolvedAt() != null) {
            return SlWalletResponse.refund(SlWalletResponseReason.GROUP_DISSOLVED,
                "group dissolved");
        }

        try {
            authorizer.assertCan(userId, group.getId(),
                RealtyGroupPermission.DEPOSIT_TO_GROUP_WALLET);
        } catch (Exception e) {
            return SlWalletResponse.refund(SlWalletResponseReason.PERMISSION_REVOKED,
                "no deposit permission");
        }

        String regionName = terminalRepository.findById(req.terminalId())
            .map(Terminal::getRegionName).orElse(null);

        try {
            walletService.depositFromTerminal(group.getId(), req.amount(), userId,
                regionName, req.slTransactionKey());
            return SlWalletResponse.ok();
        } catch (DepositAmountOutOfRangeException e) {
            return SlWalletResponse.refund(SlWalletResponseReason.AMOUNT_OUT_OF_RANGE,
                e.getMessage());
        } catch (RealtyGroupSuspendedException e) {
            return SlWalletResponse.refund(SlWalletResponseReason.GROUP_SUSPENDED,
                "group suspended");
        }
    }
}
```

- [ ] **Step 6: Run tests, confirm pass**

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/sl/ \
        backend/src/main/java/com/slparcelauctions/backend/wallet/sl/SlWalletResponseReason.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java \
        backend/src/test/java/com/slparcelauctions/backend/wallet/sl/group/SlGroupDepositControllerTest.java
git commit -m "feat(sl): POST /api/v1/sl/wallet/group-deposit (in-world group wallet credit)"
```

---

## Part 3 — Frontend

### Task 9: Type + permission-label updates

**Files:**
- Modify: `frontend/src/types/realty.ts`
- Modify: `frontend/src/lib/realty/permissions.ts`

- [ ] **Step 1: Add the enum value to TypeScript types**

```typescript
// frontend/src/types/realty.ts — find the RealtyGroupPermission union/enum
export type RealtyGroupPermission =
    | "INVITE_AGENTS"
    | "REMOVE_AGENTS"
    | "EDIT_GROUP_PROFILE"
    | "CONFIGURE_FEES"
    | "CREATE_LISTING"
    | "MANAGE_ALL_LISTINGS"
    | "WITHDRAW_FROM_GROUP_WALLET"
    | "VIEW_GROUP_TRANSACTIONS"
    | "REGISTER_SL_GROUP"
    | "MANAGE_MEMBERS"
    | "DEPOSIT_TO_GROUP_WALLET";

// Also add the request/response DTOs:
export type GroupDepositRequest = {
    amount: number;
    memo?: string;
    idempotencyKey: string;
};
export type GroupDepositResponse = {
    groupLedgerEntryId: number;
    personalLedgerEntryId: number;
    newGroupAvailable: number;
    newPersonalAvailable: number;
};
```

- [ ] **Step 2: Add the permission label**

```typescript
// frontend/src/lib/realty/permissions.ts — find the permissionLabel object/function
export function permissionLabel(perm: RealtyGroupPermission): string {
    switch (perm) {
        // ... existing cases ...
        case "DEPOSIT_TO_GROUP_WALLET": return "Deposit to group wallet";
        // ...
    }
}

// And the registry that drives PermissionsForm:
export const ALL_PERMISSIONS: RealtyGroupPermission[] = [
    // ...
    "DEPOSIT_TO_GROUP_WALLET",
];
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/realty.ts frontend/src/lib/realty/permissions.ts
git commit -m "feat(frontend): expose DEPOSIT_TO_GROUP_WALLET permission"
```

---

### Task 10: `useGroupDeposit` hook

**Files:**
- Create: `frontend/src/hooks/realty/useGroupDeposit.ts`
- Create: `frontend/src/hooks/realty/useGroupDeposit.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// useGroupDeposit.test.tsx — mirror useBulkCommissionEdit.test.tsx structure
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { useGroupDeposit } from "./useGroupDeposit";
import { realtyQueryKeys } from "./useRealtyGroups";

const GROUP_PUBLIC_ID = "00000000-0000-0000-0000-000000000001";

function makeQc() {
    return new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
}

function wrapper(qc: QueryClient) {
    return function Wrapper({ children }: { children: React.ReactNode }) {
        return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
    };
}

describe("useGroupDeposit", () => {
    beforeEach(() => {
        server.use(
            http.post(
                `*/api/v1/realty/groups/${GROUP_PUBLIC_ID}/wallet/deposit`,
                () => HttpResponse.json({
                    groupLedgerEntryId: 99,
                    personalLedgerEntryId: 199,
                    newGroupAvailable: 2200,
                    newPersonalAvailable: 3800,
                }, { status: 200 }),
            ),
        );
    });
    afterEach(() => vi.restoreAllMocks());

    it("invalidates realty + personal wallet keys on success", async () => {
        const qc = makeQc();
        qc.setQueryData(realtyQueryKeys.group(GROUP_PUBLIC_ID), { stale: false });
        qc.setQueryData(["wallet", "me"], { stale: false });
        const invalidateSpy = vi.spyOn(qc, "invalidateQueries");

        const { result } = renderHook(() => useGroupDeposit(GROUP_PUBLIC_ID), {
            wrapper: wrapper(qc),
        });

        await result.current.mutateAsync({
            amount: 1200,
            memo: "test",
            idempotencyKey: "00000000-0000-0000-0000-0000000000aa",
        });

        await waitFor(() => {
            expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: realtyQueryKeys.all });
            expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["wallet", "me"] });
        });
    });
});
```

- [ ] **Step 2: Run, confirm fail**

```bash
cd frontend && npm test -- useGroupDeposit
```

- [ ] **Step 3: Implement**

```typescript
// useGroupDeposit.ts
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { GroupDepositRequest, GroupDepositResponse } from "@/types/realty";
import { realtyQueryKeys } from "./useRealtyGroups";

export function useGroupDeposit(groupPublicId: string) {
    const qc = useQueryClient();
    return useMutation<GroupDepositResponse, Error, GroupDepositRequest>({
        mutationFn: (body) =>
            api.post(`/api/v1/realty/groups/${groupPublicId}/wallet/deposit`, body),
        onSuccess: () => {
            qc.invalidateQueries({ queryKey: realtyQueryKeys.all });
            qc.invalidateQueries({ queryKey: ["wallet", "me"] });
        },
    });
}
```

- [ ] **Step 4: Run, confirm pass**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/hooks/realty/useGroupDeposit.ts \
        frontend/src/hooks/realty/useGroupDeposit.test.tsx
git commit -m "feat(frontend): useGroupDeposit hook"
```

---

### Task 11: `AddFundsModal` component

**Files:**
- Create: `frontend/src/components/realty/AddFundsModal.tsx`
- Create: `frontend/src/components/realty/AddFundsModal.test.tsx`

- [ ] **Step 1: Write failing tests**

```tsx
// AddFundsModal.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { renderWithQueryClient } from "@/test/render";
import { AddFundsModal } from "./AddFundsModal";

const GROUP = { publicId: "g-uuid", name: "Hadron Realty", maxDepositL: 500000 };
const PERSONAL_AVAILABLE = 5000;

describe("AddFundsModal", () => {
    it("shows the group name and personal available", () => {
        renderWithQueryClient(
            <AddFundsModal open onClose={vi.fn()} group={GROUP}
                personalAvailable={PERSONAL_AVAILABLE} />,
        );
        expect(screen.getByText(/Add funds to Hadron Realty/)).toBeInTheDocument();
        expect(screen.getByText(/L\$5,000/)).toBeInTheDocument();
    });

    it("shows inline error on amount > personal available", async () => {
        renderWithQueryClient(<AddFundsModal open onClose={vi.fn()} group={GROUP}
            personalAvailable={PERSONAL_AVAILABLE} />);
        await userEvent.type(screen.getByLabelText(/Amount/i), "6000");
        await userEvent.click(screen.getByRole("button", { name: /Deposit/i }));
        expect(await screen.findByText(/Not enough funds in your wallet/i))
            .toBeInTheDocument();
    });

    it("shows inline error on amount > config max", async () => { /* ... */ });

    it("posts and closes on success", async () => {
        const onClose = vi.fn();
        server.use(
            http.post("*/api/v1/realty/groups/g-uuid/wallet/deposit", () =>
                HttpResponse.json({
                    groupLedgerEntryId: 1, personalLedgerEntryId: 2,
                    newGroupAvailable: 1000, newPersonalAvailable: 4000,
                }, { status: 200 })),
        );
        renderWithQueryClient(<AddFundsModal open onClose={onClose} group={GROUP}
            personalAvailable={PERSONAL_AVAILABLE} />);
        await userEvent.type(screen.getByLabelText(/Amount/i), "1000");
        await userEvent.click(screen.getByRole("button", { name: /Deposit/i }));
        await waitFor(() => expect(onClose).toHaveBeenCalled());
    });

    it("renders 400 INSUFFICIENT_BALANCE inline", async () => { /* ... */ });
});
```

- [ ] **Step 2: Run, confirm fail**

- [ ] **Step 3: Implement**

```tsx
// AddFundsModal.tsx
"use client";

import { useId, useState } from "react";
import { Modal } from "@/components/ui/Modal";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { useGroupDeposit } from "@/hooks/realty/useGroupDeposit";
import { showSuccessToast, showErrorToast } from "@/lib/toast";

export interface AddFundsModalProps {
    open: boolean;
    onClose: () => void;
    group: { publicId: string; name: string; maxDepositL: number };
    personalAvailable: number;
}

export function AddFundsModal({ open, onClose, group, personalAvailable }: AddFundsModalProps) {
    const amountId = useId();
    const memoId = useId();
    const [amount, setAmount] = useState<string>("");
    const [memo, setMemo] = useState<string>("");
    const [idempotencyKey] = useState<string>(() => crypto.randomUUID());
    const [inlineError, setInlineError] = useState<string | null>(null);

    const deposit = useGroupDeposit(group.publicId);

    const max = Math.min(personalAvailable, group.maxDepositL);

    function validate(): number | null {
        const n = Number.parseInt(amount, 10);
        if (!Number.isFinite(n) || n < 1) {
            setInlineError("Enter an amount of L$1 or more.");
            return null;
        }
        if (n > personalAvailable) {
            setInlineError("Not enough funds in your wallet.");
            return null;
        }
        if (n > group.maxDepositL) {
            setInlineError(`Maximum per deposit is L$${group.maxDepositL.toLocaleString()}.`);
            return null;
        }
        setInlineError(null);
        return n;
    }

    function handleSubmit() {
        const n = validate();
        if (n == null) return;
        deposit.mutate(
            { amount: n, memo: memo.trim() || undefined, idempotencyKey },
            {
                onSuccess: () => {
                    showSuccessToast(`Deposited L$${n.toLocaleString()} to ${group.name}.`);
                    onClose();
                },
                onError: (err) => {
                    const detail = (err as { detail?: string }).detail ?? "";
                    if (detail.startsWith("INSUFFICIENT_BALANCE")) {
                        setInlineError("Not enough funds in your wallet.");
                    } else if (detail.startsWith("AMOUNT_OUT_OF_RANGE")) {
                        setInlineError("Amount is outside the allowed range.");
                    } else if (detail.startsWith("PERMISSION_DENIED")) {
                        showErrorToast("You no longer have permission to deposit.");
                        onClose();
                    } else {
                        showErrorToast(detail || "Deposit failed.");
                        onClose();
                    }
                },
            },
        );
    }

    return (
        <Modal
            open={open}
            title={`Add funds to ${group.name}`}
            onClose={onClose}
            footer={
                <>
                    <Button variant="secondary" onClick={onClose}
                        disabled={deposit.isPending}>Cancel</Button>
                    <Button onClick={handleSubmit} loading={deposit.isPending}
                        data-testid="add-funds-submit">
                        Deposit {amount ? `L$${Number(amount).toLocaleString()}` : ""}
                    </Button>
                </>
            }
        >
            <div className="flex flex-col gap-3">
                <label htmlFor={amountId} className="flex flex-col gap-1 text-xs text-fg-muted">
                    Amount (L$)
                    <Input
                        id={amountId}
                        type="number"
                        min={1}
                        max={max}
                        value={amount}
                        onChange={(e) => setAmount(e.target.value)}
                        data-testid="add-funds-amount"
                    />
                </label>
                <p className="text-xs text-fg-muted">
                    Available: L${personalAvailable.toLocaleString()} (your wallet)
                </p>
                <label htmlFor={memoId} className="flex flex-col gap-1 text-xs text-fg-muted">
                    Memo (optional, 200 chars max)
                    <Input
                        id={memoId}
                        maxLength={200}
                        value={memo}
                        onChange={(e) => setMemo(e.target.value)}
                    />
                </label>
                {inlineError && (
                    <p className="text-sm text-danger" data-testid="add-funds-error">
                        {inlineError}
                    </p>
                )}
                <p className="text-xs text-fg-muted">
                    This transfer is final. Use Withdraw to retrieve funds.
                </p>
            </div>
        </Modal>
    );
}
```

- [ ] **Step 4: Run, confirm pass**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/realty/AddFundsModal.tsx \
        frontend/src/components/realty/AddFundsModal.test.tsx
git commit -m "feat(frontend): AddFundsModal for group-wallet deposit"
```

---

### Task 12: Wallet tab "Add funds" button integration

**Files:**
- Modify: `frontend/src/components/realty/WalletTab.tsx`
- Modify: `frontend/src/components/realty/WalletTab.test.tsx`

- [ ] **Step 1: Extend the WalletTab test**

Add tests:
- Add-funds button rendered.
- Disabled with tooltip when viewer lacks `DEPOSIT_TO_GROUP_WALLET`.
- Enabled for leader.
- Click opens the modal.

- [ ] **Step 2: Implement**

In `WalletTab.tsx`:

```tsx
const canDeposit = useViewerPermission(group, "DEPOSIT_TO_GROUP_WALLET");
const [addFundsOpen, setAddFundsOpen] = useState(false);
// ...
<Button
    variant="primary"
    onClick={() => setAddFundsOpen(true)}
    disabled={!canDeposit}
    title={canDeposit ? undefined : "Requires Deposit to group wallet permission"}
    data-testid="wallet-add-funds"
>
    Add funds
</Button>
// ... existing Withdraw button next to this ...
{addFundsOpen && (
    <AddFundsModal
        open
        onClose={() => setAddFundsOpen(false)}
        group={{
            publicId: group.publicId,
            name: group.name,
            maxDepositL: 500000,  // matches application.yml default; could be sourced from /config
        }}
        personalAvailable={personalWallet.available}
    />
)}
```

`useViewerPermission` already exists for the Withdraw button's gating
— reuse it. `personalWallet` comes from the existing `useMyWallet`
hook used elsewhere; import it if not already in `WalletTab.tsx`.

- [ ] **Step 3: Run tests**

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/realty/WalletTab.tsx \
        frontend/src/components/realty/WalletTab.test.tsx
git commit -m "feat(frontend): Add Funds button on group Wallet tab"
```

---

### Task 13: Ledger row labels (group + personal)

**Files:**
- Modify: `frontend/src/components/realty/GroupLedgerRow.tsx`
- Modify: `frontend/src/components/wallet/UserLedgerRow.tsx`
- Modify: test files alongside both

- [ ] **Step 1: Group ledger — render `MEMBER_DEPOSIT`**

```tsx
// GroupLedgerRow.tsx
case "MEMBER_DEPOSIT":
    return entry.description ?? "Deposit";
```

The service writes the prefix; the frontend renders `description`
verbatim, with no second-line split. This is consistent with the
spec's §7.3 "description is rendered verbatim" rule.

- [ ] **Step 2: Personal ledger — render `GROUP_WALLET_DEPOSIT_DEBIT`**

```tsx
// UserLedgerRow.tsx
case "GROUP_WALLET_DEPOSIT_DEBIT":
    // description is "Deposit to <groupName>" -- render with a link if the
    // server-side viewer is still a member of the group (refId points at
    // the realty_group row id; resolution happens in the parent component).
    return entry.description ?? "Deposit to group";
```

If the parent already resolves `refType = "REALTY_GROUP"` rows to a
group slug for link rendering, plug into that. Otherwise plain text —
the spec is explicit that link rendering is optional and based on
current membership; absent membership state, plain text is correct.

- [ ] **Step 3: Tests**

Add one test per row component verifying the new entry type renders
the description string.

- [ ] **Step 4: Run, commit**

```bash
git add frontend/src/components/realty/GroupLedgerRow.tsx \
        frontend/src/components/wallet/UserLedgerRow.tsx \
        frontend/src/components/realty/GroupLedgerRow.test.tsx \
        frontend/src/components/wallet/UserLedgerRow.test.tsx
git commit -m "feat(frontend): render MEMBER_DEPOSIT + GROUP_WALLET_DEPOSIT_DEBIT rows"
```

---

## Part 4 — LSL + Postman + final

### Task 14: Extend `slpa-terminal.lsl` with "Pay to group" flow

**Files:**
- Modify: `lsl-scripts/slpa-terminal/slpa-terminal.lsl`
- Modify: `lsl-scripts/slpa-terminal/config.notecard.example`
- Modify: `lsl-scripts/slpa-terminal/README.md`

LSL has no automated tests; correctness is established by reading the
state machine carefully and smoke-testing on the live grid.

- [ ] **Step 1: Add two new notecard keys**

```
# config.notecard.example — append:
AVATAR_GROUPS_URL = https://slpa.app/api/v1/sl/wallet/avatar-groups
GROUP_DEPOSIT_URL = https://slpa.app/api/v1/sl/wallet/group-deposit
```

- [ ] **Step 2: Parse + validate the new keys at startup**

In the notecard-load handler, add lines mirroring `DEPOSIT_URL`:

```lsl
else if (key == "AVATAR_GROUPS_URL") avatarGroupsUrl = value;
else if (key == "GROUP_DEPOSIT_URL") groupDepositUrl = value;
```

The startup "incomplete config" check should NOT require these — they
are optional. The "Pay to group" menu button is conditionally added
based on whether both are non-empty.

- [ ] **Step 3: Add the pending-deposit-context slot state**

Below the existing `withdrawSessions` strided list, add:

```lsl
list pendingGroupDeposits;      // [avatarKey, groupPublicIdStr, expiresAt, ...]
integer GROUP_DEPOSIT_SLOT_TTL = 60;
integer GROUP_DEPOSIT_SLOT_CAP = 4;
```

- [ ] **Step 4: Extend the touch handler menu**

Where the existing `[Deposit, Withdraw]` `llDialog` is issued, build
the list dynamically:

```lsl
list menuButtons = ["Deposit"];
if (avatarGroupsUrl != "" && groupDepositUrl != "") {
    menuButtons += ["Pay to group"];
}
menuButtons += ["Withdraw"];
llDialog(toucher, "SLParcels Terminal", menuButtons, dialogChannel);
```

- [ ] **Step 5: Handle "Pay to group" selection**

In the `listen` handler, add a branch for `message == "Pay to group"`:

```lsl
if (message == "Pay to group") {
    // POST /sl/wallet/avatar-groups to get the avatar's eligible groups
    string body = "{\"terminalId\":\"" + terminalId + "\","
        + "\"sharedSecret\":\"" + sharedSecret + "\","
        + "\"avatarUuid\":\"" + (string)id + "\"}";
    httpKey = llHTTPRequest(avatarGroupsUrl,
        [HTTP_METHOD, "POST", HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 4096, HTTP_VERIFY_CERT, TRUE], body);
    pendingHttpKind = "avatarGroups";
    pendingHttpAvatar = id;
    return;
}
```

(`pendingHttpKind` is a new script-global string mirroring the
existing pattern for the in-flight HTTP-out call. If the script uses
a strided list of in-flight calls instead, slot accordingly.)

- [ ] **Step 6: Handle the `/avatar-groups` response**

In `http_response`:

```lsl
if (pendingHttpKind == "avatarGroups" && id == httpKey) {
    pendingHttpKind = "";
    if (status != 200) {
        llRegionSayTo(pendingHttpAvatar, 0, "Unable to fetch groups, try again.");
        return;
    }
    // Parse the JSON response — { groups: [{publicId, name}, ...], hasMore, nextAfter }
    list groupNames = llJson2List(llJsonGetValue(body, ["groups"]));
    if (llGetListLength(groupNames) == 0) {
        llRegionSayTo(pendingHttpAvatar, 0,
            "You're not a member of any group with deposit permission.");
        return;
    }
    // Build button labels from group names; remember the (name -> publicId) map
    // in a parallel script-global list (since llDialog only carries labels).
    pendingGroupButtons = [];     // strided [label, publicId]
    list dialogLabels;
    integer i;
    for (i = 0; i < llGetListLength(groupNames); i++) {
        string entry = llList2String(groupNames, i);
        string pubId = llJsonGetValue(entry, ["publicId"]);
        string name = llJsonGetValue(entry, ["name"]);
        // Truncate to 24 chars for llDialog label cap
        string label = llGetSubString(name, 0, 23);
        pendingGroupButtons += [label, pubId];
        dialogLabels += [label];
    }
    string hasMore = llJsonGetValue(body, ["hasMore"]);
    if (hasMore == "true") dialogLabels += ["More..."];
    dialogLabels += ["Cancel"];
    llDialog(pendingHttpAvatar, "Pick a group to deposit into:",
        dialogLabels, dialogChannel);
}
```

- [ ] **Step 7: Handle group-name selection**

In the `listen` handler, add a branch that scans
`pendingGroupButtons` for the chosen label and stores the slot:

```lsl
if (message == "Cancel" || message == "More...") {
    // ... existing cancel / pagination handling ...
    return;
}
// Group-name button?
integer idx = llListFindList(pendingGroupButtons, [message]);
if (idx != -1 && idx % 2 == 0) {
    string pubId = llList2String(pendingGroupButtons, idx + 1);
    // Evict oldest slot if at cap
    while (llGetListLength(pendingGroupDeposits) >= GROUP_DEPOSIT_SLOT_CAP * 3) {
        pendingGroupDeposits = llDeleteSubList(pendingGroupDeposits, 0, 2);
    }
    // De-dup by avatar
    integer existing = llListFindList(pendingGroupDeposits, [id]);
    if (existing != -1) {
        pendingGroupDeposits = llDeleteSubList(pendingGroupDeposits, existing, existing + 2);
    }
    pendingGroupDeposits += [id, pubId, llGetUnixTime() + GROUP_DEPOSIT_SLOT_TTL];
    llRegionSayTo(id, 0, "You have 60 seconds to right-click -> Pay -> enter L$ amount.");
    return;
}
```

- [ ] **Step 8: Extend `money` event handler**

Before the existing `/sl/wallet/deposit` POST, check the pending-deposit slot:

```lsl
money(key payer, integer amount) {
    // Existing: log, build slTransactionKey, etc.

    // NEW: route to group-deposit if a fresh slot exists for this payer.
    integer slot = llListFindList(pendingGroupDeposits, [payer]);
    if (slot != -1) {
        integer exp = llList2Integer(pendingGroupDeposits, slot + 2);
        if (llGetUnixTime() < exp) {
            string pubId = llList2String(pendingGroupDeposits, slot + 1);
            pendingGroupDeposits = llDeleteSubList(pendingGroupDeposits, slot, slot + 2);
            // POST /sl/wallet/group-deposit
            string body = "{\"terminalId\":\"" + terminalId + "\","
                + "\"sharedSecret\":\"" + sharedSecret + "\","
                + "\"payerUuid\":\"" + (string)payer + "\","
                + "\"groupPublicId\":\"" + pubId + "\","
                + "\"amount\":" + (string)amount + ","
                + "\"slTransactionKey\":\"" + slTxnKey + "\"}";
            integer rqIdx = enqueueDepositRetry(body, payer, amount, slTxnKey,
                groupDepositUrl, "groupDeposit");
            return;
        } else {
            pendingGroupDeposits = llDeleteSubList(pendingGroupDeposits, slot, slot + 2);
        }
    }

    // Existing personal-deposit POST follows...
}
```

`enqueueDepositRetry` is a refactor of the existing deposit retry path
to accept the target URL and a "kind" tag. If the existing script has
inline retry logic instead of a helper, extract it first.

- [ ] **Step 9: Response handler for `/group-deposit`**

In `http_response`, add a branch for the `groupDeposit` kind that
mirrors the existing `/deposit` handler: OK → log success and clear
retry slot; REFUND → log + `llTransferLindenDollars` back + clear;
ERROR → log + refund (defensive) + clear.

- [ ] **Step 10: Extend the sweeper**

The existing 10-second timer that evicts expired withdraw sessions
also evicts expired `pendingGroupDeposits` entries:

```lsl
integer now = llGetUnixTime();
integer i;
for (i = 0; i < llGetListLength(pendingGroupDeposits); i += 3) {
    integer exp = llList2Integer(pendingGroupDeposits, i + 2);
    if (now > exp) {
        pendingGroupDeposits = llDeleteSubList(pendingGroupDeposits, i, i + 2);
        i -= 3;
    }
}
```

- [ ] **Step 11: Update the README**

Add to `lsl-scripts/slpa-terminal/README.md`:

- Architecture summary: new "Group-deposit flow" bullet.
- Config table: `AVATAR_GROUPS_URL` and `GROUP_DEPOSIT_URL`.
- Operations: new log lines (`group deposit ok L$<N> to <groupName>`,
  `group deposit refunded (<REASON>) L$<N>`, `group deposit retry N/5`).
- Smoke test section: member-with-permission happy path,
  member-without-permission "no eligible groups" dialog,
  race-with-expired-slot fallback to personal deposit.

- [ ] **Step 12: Commit**

```bash
git add lsl-scripts/slpa-terminal/
git commit -m "feat(lsl): slpa-terminal Pay to group flow"
```

---

### Task 15: Postman — mirror the three new endpoints

**Files:**
- SLPA Postman collection (`8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`).

- [ ] **Step 1: Add the three requests**

Using the Postman MCP `createCollectionRequest` tool (or the web UI),
add to the SLPA collection under the "Realty Wallet" / "SL Wallet"
folders:

- `POST {{baseUrl}}/api/v1/realty/groups/{{groupPublicId}}/wallet/deposit`
- `POST {{baseUrl}}/api/v1/sl/wallet/avatar-groups`
- `POST {{baseUrl}}/api/v1/sl/wallet/group-deposit`

- [ ] **Step 2: Wire variable-chaining test scripts**

The first request body uses `{{idempotencyKey}}`; pre-request script
generates a UUID and sets it on the environment. Response post-script
extracts `groupLedgerEntryId` into env for later assertions.

- [ ] **Step 3: Smoke from the collection runner**

Run the requests against the `SLPA Dev` environment with a seeded
leader token. All three should return 200.

- [ ] **Step 4: No git commit**

(Postman state is the source of truth; nothing to commit in-repo.)

---

### Task 16: Final smoke + PR

- [ ] **Step 1: Backend + frontend full test runs**

```bash
cd backend && ./mvnw test
cd frontend && npm test && npm run verify
```

- [ ] **Step 2: Manual smoke**

- Start the stack: `docker compose up --build`.
- Log in as a leader of a group, navigate to `/groups/<slug>/manage/wallet`,
  click "Add funds", deposit L$100, verify ledger row appears and
  balances move.
- Log in as an agent without permission, verify the button is
  disabled with the tooltip. Grant the permission, verify the
  button enables on reload.
- (Skip the in-world flow — covered by integration tests; live grid
  test is part of the LSL deploy procedure documented in the README.)

- [ ] **Step 3: README sweep**

Per memory rule "Update README.md at the end of every task" — open
`README.md` at the repo root, scan for sections that describe wallet
flows or realty-group capabilities. Add a one-liner mentioning
member-initiated group-wallet deposit if the wallet section calls out
existing flows by name.

- [ ] **Step 4: Push and open PR into `dev`**

```bash
git push -u origin feat/group-wallet-deposit
gh pr create --base dev --title "Group-wallet deposit (app + in-world)" --body "$(cat <<'EOF'
## Summary
- New permission `DEPOSIT_TO_GROUP_WALLET` (leader-implicit) gating two
  new flows.
- App flow: `POST /api/v1/realty/groups/{publicId}/wallet/deposit` —
  atomic transfer from the depositor's personal SLParcels wallet into
  the group wallet.
- In-world flow: `slpa-terminal.lsl` "Pay to group" touch-menu option
  with a per-avatar 60-second context slot and two new SL-headers-gated
  endpoints (`/sl/wallet/avatar-groups` for enumeration,
  `/sl/wallet/group-deposit` for the credit).
- Two new ledger entry types — `MEMBER_DEPOSIT` on
  `realty_group_ledger`, `GROUP_WALLET_DEPOSIT_DEBIT` on `user_ledger`.

See spec at `docs/realty-groups/2026-05-14-group-wallet-deposit-design.md`.

## Test plan
- [ ] `./mvnw test` — full backend suite passes
- [ ] `npm test && npm run verify` — full frontend suite + guards pass
- [ ] Manual: leader deposits via app, ledger + balances verified
- [ ] Manual: agent without permission sees disabled button + tooltip
- [ ] Manual: agent gains permission via Members tab, button enables
- [ ] LSL: deploy the new terminal config to a staging terminal,
      smoke-test the Pay to group flow on the grid
EOF
)"
```

---

## Self-Review

(Performed inline by the plan author before publishing.)

**Spec coverage.** Walked each section of the spec:

- §3 Auth model — Tasks 1 (permission), 6 (controller gate), 7 (avatar-groups filter), 8 (re-check at credit time).
- §4 Endpoints — Tasks 6, 7, 8.
- §5 Ledger entry types — Tasks 2, 3.
- §6 Limits — Task 4 (config); Tasks 5, 8 enforce.
- §7 Frontend UX — Tasks 9–13.
- §8 LSL flow — Task 14.
- §9 Test surface — backend tests covered alongside their implementation tasks; frontend tests likewise; LSL tests are smoke per the README per §8.6.
- §10 Migration / deploy order — Task 16 PR description references the order; CI applies migrations automatically before container start.

**Placeholder scan.** Each step has either concrete code, a concrete
command, or specific instructions to look up existing scaffolding
(repository patterns, fixtures) by name. No "TBD" / "TODO" / "fill in
details" text. The migration version numbers are deliberately
unresolved (`V<N>`) — the implementer reads the latest migration in
the repo at task time.

**Type consistency.**

- `DepositResult` (Task 5) and `GroupDepositResponse` (Task 6) share
  4 fields — `groupLedgerEntryId`, `personalLedgerEntryId`,
  `newGroupAvailable`, `newPersonalAvailable`.
- `TerminalDepositResult` (Task 8) returns only 2 fields
  (`groupLedgerEntryId`, `newGroupAvailable`) — the in-world flow has
  no personal-wallet side.
- `RealtyGroupPermission.DEPOSIT_TO_GROUP_WALLET` referenced
  consistently across backend (Tasks 6, 8) and frontend (Tasks 9, 12).
- `RealtyGroupLedgerEntryType.MEMBER_DEPOSIT` used in service (Tasks
  5, 8) and frontend renderer (Task 13).
- `UserLedgerEntryType.GROUP_WALLET_DEPOSIT_DEBIT` used in service
  (Task 5) and frontend renderer (Task 13).
- LSL `pendingGroupDeposits` strided list shape `[avatarKey,
  groupPublicIdStr, expiresAt, ...]` — referenced consistently
  across Steps 3, 7, 8, 10 of Task 14.

No drift found.

---

## Execution

This plan is queued for `subagent-driven-development`. Each task
above is a fresh subagent dispatch (per the skill's pattern), with
the two-stage review after each. After all tasks complete, the
final code-reviewer runs across the whole branch, then the PR opens
into `dev` (NOT main — memory `feedback_no_merge_to_main`).
