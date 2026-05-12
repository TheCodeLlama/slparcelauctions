# Realty Groups Group Wallet — Part 1 (Tasks 1–10)

> Continuation of `2026-05-11-realty-groups-group-wallet.md`. Schema scaffolding, entities, permission enum, and wallet primitives.

---

### Task 1: Flyway V26 migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V26__realty_group_wallet.sql`
- Test: `backend/src/test/java/com/slparcelauctions/backend/db/MigrationCleanupTest.java` (existing harness verifies migration applies)

Implements spec §3.1, §3.2, §3.4, §14.1.

- [ ] **Step 1: Write the migration file**

```sql
-- V26__realty_group_wallet.sql
-- Sub-project D: group wallet schema.

-- 1. Wallet columns on realty_groups.
ALTER TABLE realty_groups
    ADD COLUMN balance_lindens BIGINT NOT NULL DEFAULT 0
        CHECK (balance_lindens >= 0),
    ADD COLUMN reserved_lindens BIGINT NOT NULL DEFAULT 0
        CHECK (reserved_lindens >= 0),
    ADD CONSTRAINT realty_groups_wallet_balance_ge_reserved
        CHECK (balance_lindens >= reserved_lindens),
    ADD COLUMN wallet_dormancy_started_at TIMESTAMPTZ NULL,
    ADD COLUMN wallet_dormancy_phase SMALLINT NULL
        CHECK (wallet_dormancy_phase BETWEEN 1 AND 4 OR wallet_dormancy_phase = 99);

-- 2. realty_group_ledger table.
CREATE TABLE realty_group_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    public_id           UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    group_id            BIGINT NOT NULL REFERENCES realty_groups(id),
    entry_type          VARCHAR(32) NOT NULL,
    amount              BIGINT NOT NULL CHECK (amount > 0),
    balance_after       BIGINT NOT NULL,
    reserved_after      BIGINT NOT NULL,
    ref_type            VARCHAR(32) NULL,
    ref_id              BIGINT NULL,
    actor_user_id       BIGINT NULL REFERENCES users(id),
    sl_transaction_id   VARCHAR(36) NULL,
    idempotency_key     VARCHAR(64) NULL,
    description         VARCHAR(500) NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_admin_id BIGINT NULL REFERENCES users(id)
);

CREATE INDEX realty_group_ledger_group_created_idx
    ON realty_group_ledger (group_id, created_at DESC);
CREATE UNIQUE INDEX realty_group_ledger_sl_tx_idx
    ON realty_group_ledger (sl_transaction_id) WHERE sl_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX realty_group_ledger_idempotency_idx
    ON realty_group_ledger (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX realty_group_ledger_listing_fee_lookup_idx
    ON realty_group_ledger (ref_type, ref_id) WHERE entry_type = 'LISTING_FEE_DEBIT';

ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_entry_type_check CHECK (
    entry_type IN (
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'AGENT_FEE_CREDIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'DORMANCY_AUTO_RETURN',
        'ADJUSTMENT'
    )
);

-- 3. Terminal command linkage so completion handlers know which ledger to write.
ALTER TABLE terminal_commands
    ADD COLUMN realty_group_id BIGINT NULL REFERENCES realty_groups(id);

CREATE INDEX terminal_commands_realty_group_id_idx
    ON terminal_commands (realty_group_id) WHERE realty_group_id IS NOT NULL;

-- 4. Reconciliation extension.
ALTER TABLE reconciliation_runs
    ADD COLUMN group_wallet_balance_total BIGINT NULL,
    ADD COLUMN group_wallet_reserved_total BIGINT NULL;

-- 5. user_ledger gets AGENT_FEE_CREDIT entry type.
ALTER TABLE user_ledger DROP CONSTRAINT user_ledger_entry_type_check;
ALTER TABLE user_ledger ADD CONSTRAINT user_ledger_entry_type_check CHECK (
    entry_type IN (
        'DEPOSIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'BID_RESERVED', 'BID_RELEASED',
        'ESCROW_DEBIT', 'ESCROW_REFUND',
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'PENALTY_DEBIT',
        'AGENT_FEE_CREDIT',
        'ADJUSTMENT'
    )
);
```

- [ ] **Step 2: Run migration**

Run: `cd backend && ./mvnw -DskipTests=false test -Dtest=MigrationCleanupTest`
Expected: PASS — schema applies cleanly.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V26__realty_group_wallet.sql
git commit -m "feat(realty-wallet): add V26 migration for group wallet + ledger"
git push
```

---

### Task 2: `RealtyGroup` entity gains wallet columns

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/RealtyGroupEntityTest.java`

Implements spec §3.1.

- [ ] **Step 1: Write the failing test**

Create or extend `RealtyGroupEntityTest.java`:

```java
package com.slparcelauctions.backend.realty;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtyGroupEntityTest {

    @Test
    void newGroupDefaultsWalletColumnsToZeroAndNullDormancy() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme Realty")
            .slug("acme-realty")
            .leaderId(1L)
            .build();

        assertThat(g.getBalanceLindens()).isEqualTo(0L);
        assertThat(g.getReservedLindens()).isEqualTo(0L);
        assertThat(g.getWalletDormancyStartedAt()).isNull();
        assertThat(g.getWalletDormancyPhase()).isNull();
    }

    @Test
    void availableLindensIsBalanceMinusReserved() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(10_000L)
            .reservedLindens(2_500L)
            .build();
        assertThat(g.availableLindens()).isEqualTo(7_500L);
    }
}
```

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupEntityTest`
Expected: FAIL — `getBalanceLindens` not defined.

- [ ] **Step 3: Add the fields**

Add to `RealtyGroup.java` (under the existing dissolved-at column block):

```java
@Builder.Default
@Column(name = "balance_lindens", nullable = false)
private long balanceLindens = 0L;

@Builder.Default
@Column(name = "reserved_lindens", nullable = false)
private long reservedLindens = 0L;

@Column(name = "wallet_dormancy_started_at")
private java.time.OffsetDateTime walletDormancyStartedAt;

@Column(name = "wallet_dormancy_phase")
private Short walletDormancyPhase;

@Transient
public long availableLindens() {
    return balanceLindens - reservedLindens;
}
```

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupEntityTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/RealtyGroupEntityTest.java
git commit -m "feat(realty-wallet): add wallet columns to RealtyGroup entity"
git push
```

---

### Task 3: `RealtyGroupLedgerEntryType` enum

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryType.java`

Implements spec §3.2 (entry-type CHECK enum).

- [ ] **Step 1: Write the enum**

```java
package com.slparcelauctions.backend.realty.wallet;

/**
 * Realty-group ledger entry types. The CHECK constraint on
 * realty_group_ledger.entry_type mirrors this enum.
 */
public enum RealtyGroupLedgerEntryType {
    LISTING_FEE_DEBIT,
    LISTING_FEE_REFUND,
    AGENT_FEE_CREDIT,
    WITHDRAW_QUEUED,
    WITHDRAW_COMPLETED,
    WITHDRAW_REVERSED,
    DORMANCY_AUTO_RETURN,
    ADJUSTMENT
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryType.java
git commit -m "feat(realty-wallet): add RealtyGroupLedgerEntryType enum"
git push
```

---

### Task 4: `RealtyGroupLedgerEntry` entity

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntry.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryTest.java`

Implements spec §3.2.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.wallet;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtyGroupLedgerEntryTest {

    @Test
    void builderPopulatesCoreFields() {
        OffsetDateTime now = OffsetDateTime.now();
        RealtyGroupLedgerEntry e = RealtyGroupLedgerEntry.builder()
            .groupId(42L)
            .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT)
            .amount(500L)
            .balanceAfter(9500L)
            .reservedAfter(0L)
            .refType("AUCTION")
            .refId(123L)
            .actorUserId(7L)
            .createdAt(now)
            .build();

        assertThat(e.getGroupId()).isEqualTo(42L);
        assertThat(e.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT);
        assertThat(e.getAmount()).isEqualTo(500L);
        assertThat(e.getBalanceAfter()).isEqualTo(9500L);
        assertThat(e.getReservedAfter()).isZero();
        assertThat(e.getRefType()).isEqualTo("AUCTION");
        assertThat(e.getRefId()).isEqualTo(123L);
        assertThat(e.getActorUserId()).isEqualTo(7L);
        assertThat(e.getCreatedAt()).isEqualTo(now);
    }
}
```

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupLedgerEntryTest`
Expected: FAIL — class not defined.

- [ ] **Step 3: Write the entity**

```java
package com.slparcelauctions.backend.realty.wallet;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Append-only ledger row for a realty group's wallet. See spec §3.2.
 * Never mutate an existing row; resolve by appending a new row.
 */
@Entity
@Table(name = "realty_group_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroupLedgerEntry extends BaseEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private RealtyGroupLedgerEntryType entryType;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "reserved_after", nullable = false)
    private long reservedAfter;

    @Column(name = "ref_type", length = 32)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "sl_transaction_id", length = 36)
    private String slTransactionId;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(length = 500)
    private String description;

    @Column(name = "created_by_admin_id")
    private Long createdByAdminId;
}
```

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupLedgerEntryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntry.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryTest.java
git commit -m "feat(realty-wallet): add RealtyGroupLedgerEntry entity"
git push
```

---

### Task 5: `RealtyGroupLedgerRepository`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerRepository.java`

Implements spec §3.2, §8.1 (listing-fee refund lookup).

- [ ] **Step 1: Write the repository**

```java
package com.slparcelauctions.backend.realty.wallet;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RealtyGroupLedgerRepository
        extends JpaRepository<RealtyGroupLedgerEntry, Long> {

    Optional<RealtyGroupLedgerEntry> findByPublicId(UUID publicId);

    Optional<RealtyGroupLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    /**
     * Used by ListingFeeRefundProcessor to determine the origin wallet
     * for a listing-fee refund. The partial index
     * realty_group_ledger_listing_fee_lookup_idx makes this O(log n).
     */
    @Query("""
        SELECT e FROM RealtyGroupLedgerEntry e
        WHERE e.entryType = com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT
          AND e.refType = 'AUCTION'
          AND e.refId = :auctionId
        """)
    Optional<RealtyGroupLedgerEntry> findListingFeeDebitForAuction(@Param("auctionId") Long auctionId);

    @Query("""
        SELECT e FROM RealtyGroupLedgerEntry e
        WHERE e.groupId = :groupId
        ORDER BY e.createdAt DESC
        """)
    List<RealtyGroupLedgerEntry> findRecentForGroup(
        @Param("groupId") Long groupId, Pageable page);

    @Query("""
        SELECT e FROM RealtyGroupLedgerEntry e
        WHERE e.groupId = :groupId AND e.createdAt < :before
        ORDER BY e.createdAt DESC
        """)
    List<RealtyGroupLedgerEntry> findOlderForGroup(
        @Param("groupId") Long groupId,
        @Param("before") OffsetDateTime before,
        Pageable page);
}
```

- [ ] **Step 2: Run the build (compile check)**

Run: `cd backend && ./mvnw -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerRepository.java
git commit -m "feat(realty-wallet): add RealtyGroupLedgerRepository"
git push
```

---

### Task 6: Permission enum gains 3 D values

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermissionTest.java`

Implements spec §4.1.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.permission;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtyGroupPermissionTest {

    @Test
    void dEnumValuesAreDefined() {
        assertThat(RealtyGroupPermission.values())
            .contains(
                RealtyGroupPermission.SPEND_FROM_GROUP_WALLET,
                RealtyGroupPermission.WITHDRAW_FROM_GROUP_WALLET,
                RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS);
    }
}
```

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupPermissionTest`
Expected: FAIL — symbols not defined.

- [ ] **Step 3: Add the enum values**

Edit `RealtyGroupPermission.java`. Replace the final brace block (after `MANAGE_ALL_LISTINGS;`) by changing the semicolon to a comma and appending:

```java
    /** Broker-level: manage (pause/cancel) any of the group's listings regardless of who
     *  created them. Defined here so the enum is whole; wired by sub-project E. No-op in
     *  sub-project C. */
    MANAGE_ALL_LISTINGS,

    /** Sub-project D — defined but not wired. Reserved for future discretionary group
     *  spend (advertising, paying member penalties, sponsored auctions). Listing-fee
     *  debits under CREATE_LISTING do not require this permission — the spend is
     *  intrinsic to the listing. */
    SPEND_FROM_GROUP_WALLET,

    /** Sub-project D — initiate a withdrawal from the group wallet. Recipient is always
     *  the group leader's verified SL avatar regardless of caller. */
    WITHDRAW_FROM_GROUP_WALLET,

    /** Sub-project D — view the group's wallet balance + ledger. */
    VIEW_GROUP_TRANSACTIONS;
```

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupPermissionTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermissionTest.java
git commit -m "feat(realty-wallet): add SPEND/WITHDRAW/VIEW group wallet permissions"
git push
```

---

### Task 7: `UserLedgerEntryType` gains `AGENT_FEE_CREDIT`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/wallet/UserLedgerEntryTypeTest.java`

Implements spec §3.2 (user-side mirror) + §14.1 step 5.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.wallet;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UserLedgerEntryTypeTest {

    @Test
    void agentFeeCreditIsDefined() {
        assertThat(UserLedgerEntryType.values())
            .contains(UserLedgerEntryType.AGENT_FEE_CREDIT);
    }
}
```

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=UserLedgerEntryTypeTest`
Expected: FAIL — symbol not defined.

- [ ] **Step 3: Add the value**

Append to `UserLedgerEntryType.java` enum (before the final `;`):

```java
,

    /** Sub-project D — listing-agent's slice of agent_fee_amt at escrow completion. */
    AGENT_FEE_CREDIT
```

- [ ] **Step 4: Run the test (green)**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java \
        backend/src/test/java/com/slparcelauctions/backend/wallet/UserLedgerEntryTypeTest.java
git commit -m "feat(realty-wallet): add AGENT_FEE_CREDIT to UserLedgerEntryType"
git push
```

---

### Task 8: `TerminalCommand` gains `realtyGroupId` + `GROUP_WALLET_WITHDRAWAL` purpose

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommand.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandPurpose.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandTest.java` (existing or new)

Implements spec §5.3 step 7 + §14.1 step 3.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.escrow.command;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TerminalCommandGroupWalletTest {

    @Test
    void groupWalletWithdrawalPurposeIsDefined() {
        assertThat(TerminalCommandPurpose.values())
            .contains(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL);
    }

    @Test
    void realtyGroupIdFieldIsBuildableAndReadable() {
        TerminalCommand cmd = TerminalCommand.builder()
            .action(TerminalCommandAction.WITHDRAW)
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .recipientUuid("00000000-0000-0000-0000-000000000001")
            .amount(500L)
            .status(TerminalCommandStatus.QUEUED)
            .idempotencyKey("GWAL-1")
            .realtyGroupId(42L)
            .build();
        assertThat(cmd.getRealtyGroupId()).isEqualTo(42L);
        assertThat(cmd.getPurpose())
            .isEqualTo(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL);
    }
}
```

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=TerminalCommandGroupWalletTest`
Expected: FAIL — symbols not defined.

- [ ] **Step 3: Implement**

Edit `TerminalCommandPurpose.java`: append a value (mirror the `WALLET_WITHDRAWAL` block):

```java
,

    /**
     * Realty-group-initiated wallet withdrawal. Recipient UUID is always the
     * group leader's verified SL avatar. The originating
     * realty_group_ledger.id is encoded in the idempotency key as
     * "GWAL-{id}" (see RealtyGroupWalletService.withdraw). Callback flow is
     * handled by GroupWalletWithdrawalCallbackHandler.
     */
    GROUP_WALLET_WITHDRAWAL
```

Edit `TerminalCommand.java` — add the field alongside `escrowId` / `listingFeeRefundId`:

```java
@Column(name = "realty_group_id")
private Long realtyGroupId;
```

- [ ] **Step 4: Run the test (green)**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommand.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandPurpose.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandGroupWalletTest.java
git commit -m "feat(realty-wallet): add realtyGroupId + GROUP_WALLET_WITHDRAWAL to TerminalCommand"
git push
```

---

### Task 9: `GroupWalletBalanceChangedEnvelope` + broadcaster

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/broadcast/GroupWalletBalanceChangedEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/broadcast/GroupWalletBroadcastPublisher.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/broadcast/GroupWalletBroadcastPublisherTest.java`

Implements spec §11.3.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.wallet.broadcast;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import static org.mockito.Mockito.*;

class GroupWalletBroadcastPublisherTest {

    @Test
    void publishesEnvelopeOnGroupTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        GroupWalletBroadcastPublisher pub = new GroupWalletBroadcastPublisher(template);
        UUID groupPublicId = UUID.randomUUID();
        UUID entryPublicId = UUID.randomUUID();

        pub.publish(groupPublicId, 1000L, 0L, 1000L,
            "LISTING_FEE_DEBIT", entryPublicId);

        verify(template).convertAndSend(
            eq("/topic/realty/groups/" + groupPublicId),
            any(GroupWalletBalanceChangedEnvelope.class));
    }
}
```

- [ ] **Step 2: Run the test (red)**

Expected: FAIL — classes not defined.

- [ ] **Step 3: Implement the envelope**

`GroupWalletBalanceChangedEnvelope.java`:

```java
package com.slparcelauctions.backend.realty.wallet.broadcast;

import java.util.UUID;

public record GroupWalletBalanceChangedEnvelope(
    String type,
    UUID groupPublicId,
    long balance,
    long reserved,
    long available,
    String latestEntryType,
    UUID latestEntryPublicId
) {
    public static GroupWalletBalanceChangedEnvelope of(
            UUID groupPublicId, long balance, long reserved, long available,
            String latestEntryType, UUID latestEntryPublicId) {
        return new GroupWalletBalanceChangedEnvelope(
            "GROUP_WALLET_BALANCE_CHANGED",
            groupPublicId, balance, reserved, available,
            latestEntryType, latestEntryPublicId);
    }
}
```

`GroupWalletBroadcastPublisher.java`:

```java
package com.slparcelauctions.backend.realty.wallet.broadcast;

import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWalletBroadcastPublisher {

    private final SimpMessagingTemplate template;

    public void publish(UUID groupPublicId, long balance, long reserved, long available,
            String latestEntryType, UUID latestEntryPublicId) {
        GroupWalletBalanceChangedEnvelope env = GroupWalletBalanceChangedEnvelope.of(
            groupPublicId, balance, reserved, available, latestEntryType, latestEntryPublicId);
        template.convertAndSend("/topic/realty/groups/" + groupPublicId, env);
        log.debug("GROUP_WALLET_BALANCE_CHANGED to /topic/realty/groups/{}: balance={}, reserved={}",
            groupPublicId, balance, reserved);
    }
}
```

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=GroupWalletBroadcastPublisherTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/broadcast/ \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/broadcast/
git commit -m "feat(realty-wallet): add group wallet WS envelope + publisher"
git push
```

---

### Task 10: Exception classes for D + `RealtyExceptionHandler` mapping

**Files:**
- Create five exceptions under `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/`:
  - `InsufficientGroupBalanceException.java`
  - `LeaderTermsNotAcceptedException.java`
  - `LeaderFrozenException.java`
  - `GroupHasNonzeroBalanceException.java`
  - `GroupHasInFlightEscrowsException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/exception/RealtyWalletExceptionHandlerTest.java`

Implements spec §5.5, §9.1.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.wallet.exception;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler;

class RealtyWalletExceptionHandlerTest {

    private final RealtyExceptionHandler handler = new RealtyExceptionHandler();

    @Test
    void mapsInsufficientGroupBalance() {
        ResponseEntity<?> r = handler.handleInsufficientGroupBalance(
            new InsufficientGroupBalanceException(100L, 500L));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().toString()).contains("INSUFFICIENT_GROUP_BALANCE");
    }

    @Test
    void mapsLeaderTermsNotAccepted() {
        UUID leader = UUID.randomUUID();
        ResponseEntity<?> r = handler.handleLeaderTermsNotAccepted(
            new LeaderTermsNotAcceptedException(leader));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().toString()).contains("LEADER_TERMS_NOT_ACCEPTED");
    }

    @Test
    void mapsLeaderFrozen() {
        ResponseEntity<?> r = handler.handleLeaderFrozen(new LeaderFrozenException());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().toString()).contains("LEADER_FROZEN");
    }

    @Test
    void mapsGroupHasNonzeroBalance() {
        ResponseEntity<?> r = handler.handleGroupHasNonzeroBalance(
            new GroupHasNonzeroBalanceException());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().toString()).contains("GROUP_HAS_NONZERO_BALANCE");
    }

    @Test
    void mapsGroupHasInFlightEscrows() {
        ResponseEntity<?> r = handler.handleGroupHasInFlightEscrows(
            new GroupHasInFlightEscrowsException());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().toString()).contains("GROUP_HAS_INFLIGHT_ESCROWS");
    }
}
```

- [ ] **Step 2: Run the test (red)**

Expected: FAIL — classes not defined.

- [ ] **Step 3: Implement exceptions**

```java
// InsufficientGroupBalanceException.java
package com.slparcelauctions.backend.realty.wallet.exception;
import lombok.Getter;
@Getter
public class InsufficientGroupBalanceException extends RuntimeException {
    private final long available;
    private final long requested;
    public InsufficientGroupBalanceException(long available, long requested) {
        super("group balance insufficient: available=" + available + " requested=" + requested);
        this.available = available;
        this.requested = requested;
    }
}
```

```java
// LeaderTermsNotAcceptedException.java
package com.slparcelauctions.backend.realty.wallet.exception;
import java.util.UUID;
import lombok.Getter;
@Getter
public class LeaderTermsNotAcceptedException extends RuntimeException {
    private final UUID leaderPublicId;
    public LeaderTermsNotAcceptedException(UUID leaderPublicId) {
        super("group leader has not accepted wallet ToS: leader=" + leaderPublicId);
        this.leaderPublicId = leaderPublicId;
    }
}
```

```java
// LeaderFrozenException.java
package com.slparcelauctions.backend.realty.wallet.exception;
public class LeaderFrozenException extends RuntimeException {
    public LeaderFrozenException() {
        super("group leader status blocks withdrawal");
    }
}
```

```java
// GroupHasNonzeroBalanceException.java
package com.slparcelauctions.backend.realty.wallet.exception;
public class GroupHasNonzeroBalanceException extends RuntimeException {
    public GroupHasNonzeroBalanceException() {
        super("group wallet has nonzero balance; cannot dissolve");
    }
}
```

```java
// GroupHasInFlightEscrowsException.java
package com.slparcelauctions.backend.realty.wallet.exception;
public class GroupHasInFlightEscrowsException extends RuntimeException {
    public GroupHasInFlightEscrowsException() {
        super("group has in-flight escrows; cannot dissolve");
    }
}
```

Then extend `RealtyExceptionHandler.java` with five new `@ExceptionHandler` methods, mirroring the existing handler shape (returns `ResponseEntity<Map<String, Object>>` with body `{ "code": "...", "message": "..." }`, plus extra fields where the exception carries them):

```java
@ExceptionHandler(InsufficientGroupBalanceException.class)
public ResponseEntity<?> handleInsufficientGroupBalance(InsufficientGroupBalanceException ex) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
        "code", "INSUFFICIENT_GROUP_BALANCE",
        "message", ex.getMessage(),
        "available", ex.getAvailable(),
        "requested", ex.getRequested()));
}

@ExceptionHandler(LeaderTermsNotAcceptedException.class)
public ResponseEntity<?> handleLeaderTermsNotAccepted(LeaderTermsNotAcceptedException ex) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
        "code", "LEADER_TERMS_NOT_ACCEPTED",
        "message", ex.getMessage(),
        "leaderPublicId", ex.getLeaderPublicId().toString()));
}

@ExceptionHandler(LeaderFrozenException.class)
public ResponseEntity<?> handleLeaderFrozen(LeaderFrozenException ex) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
        "code", "LEADER_FROZEN",
        "message", ex.getMessage()));
}

@ExceptionHandler(GroupHasNonzeroBalanceException.class)
public ResponseEntity<?> handleGroupHasNonzeroBalance(GroupHasNonzeroBalanceException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "code", "GROUP_HAS_NONZERO_BALANCE",
        "message", ex.getMessage()));
}

@ExceptionHandler(GroupHasInFlightEscrowsException.class)
public ResponseEntity<?> handleGroupHasInFlightEscrows(GroupHasInFlightEscrowsException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "code", "GROUP_HAS_INFLIGHT_ESCROWS",
        "message", ex.getMessage()));
}
```

Add appropriate imports.

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=RealtyWalletExceptionHandlerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/ \
        backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/exception/
git commit -m "feat(realty-wallet): add group wallet exception types + handler mappings"
git push
```

---

End of Part 1. Continue to `2026-05-11-realty-groups-group-wallet-part2.md` for Tasks 11–20.
