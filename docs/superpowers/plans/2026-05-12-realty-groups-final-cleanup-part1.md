# Realty Groups: G — Part 1: Foundation + Section A

> Index: [`2026-05-12-realty-groups-final-cleanup.md`](2026-05-12-realty-groups-final-cleanup.md).
> Spec: [`docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`](../specs/2026-05-12-realty-groups-final-cleanup-design.md).

**Tasks 1–10.** Foundation: Flyway V29 migration, new enum values, Section A C-era code removal, V29 smoke test.

**Order rule:** Task 1 (V29 written) lands first, but the migration **applies last** (Task 10). Tasks 2–9 are code-removal / enum-addition tasks that must complete before Task 10 actually applies V29 against the dev DB — otherwise the entity layer references columns that no longer exist.

**Concurrency:** Task 2 (enums) is parallel-safe with Tasks 3-9 (code removal). All of 3-9 are pairwise file-disjoint and parallel-safe with each other.

---

## Task 1: Write V29 Flyway migration file

**Files:**
- Create: `backend/src/main/resources/db/migration/V29__drop_c_era_agent_fee_columns_and_widen_admin_adjust.sql`

- [ ] **Step 1: Write the migration SQL**

```sql
-- V29: realty groups sub-project G -- final cleanup pass.
-- Drops C-era column family, widens ledger entry_type CHECK for ADMIN_ADJUSTMENT,
-- adds report-threshold notified flag, scrubs deleted SPEND_FROM_GROUP_WALLET enum.

-- §4 -- C-era column drops.
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_rate;
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_split;
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_amt;
ALTER TABLE realty_groups DROP COLUMN IF EXISTS agent_fee_rate;
ALTER TABLE realty_groups DROP COLUMN IF EXISTS agent_fee_split;

-- §7.1 / §7.2 -- widen entry_type CHECK to admit ADMIN_ADJUSTMENT.
ALTER TABLE realty_group_ledger DROP CONSTRAINT IF EXISTS realty_group_ledger_entry_type_check;
ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_entry_type_check CHECK (
    entry_type IN (
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND', 'AGENT_FEE_CREDIT', 'LISTING_PAYOUT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'DORMANCY_AUTO_RETURN', 'ADJUSTMENT', 'ADMIN_ADJUSTMENT'
    )
);

-- §11 -- drop dead SPEND_FROM_GROUP_WALLET permission from any persisted member rows.
UPDATE realty_group_members
   SET permissions = array_remove(permissions, 'SPEND_FROM_GROUP_WALLET')
 WHERE 'SPEND_FROM_GROUP_WALLET' = ANY(permissions);

-- §12.5 -- per-group flag for one-shot-per-cycle report-threshold notification.
ALTER TABLE realty_groups
  ADD COLUMN IF NOT EXISTS reports_threshold_notified BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/resources/db/migration/V29__drop_c_era_agent_fee_columns_and_widen_admin_adjust.sql
git commit -m "feat(realty-g): V29 migration -- drop C-era columns, widen entry_type, scrub SPEND, add threshold flag"
```

Note: the migration is committed now but is NOT applied to the dev DB until Task 10. Tasks 2–9 must complete first so the running app no longer references the dropped columns.

---

## Task 2: Add new enum values [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryType.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandAction.java`

- [ ] **Step 1: Write a failing test asserting the new enum values exist**

Test file: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryTypeTest.java`

```java
package com.slparcelauctions.backend.realty.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RealtyGroupLedgerEntryTypeTest {

    @Test
    void admin_adjustment_enum_value_present() {
        assertThat(RealtyGroupLedgerEntryType.valueOf("ADMIN_ADJUSTMENT"))
            .isEqualTo(RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT);
    }
}
```

Test file: `backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminActionTypeTest.java`

```java
package com.slparcelauctions.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminActionTypeTest {

    @Test
    void realty_group_wallet_admin_adjustment_enum_value_present() {
        assertThat(AdminActionType.valueOf("REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT"))
            .isEqualTo(AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT);
    }
}
```

Test file: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandActionTest.java`

```java
package com.slparcelauctions.backend.escrow.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalCommandActionTest {

    @Test
    void withdraw_group_enum_value_present() {
        assertThat(TerminalCommandAction.valueOf("WITHDRAW_GROUP"))
            .isEqualTo(TerminalCommandAction.WITHDRAW_GROUP);
    }
}
```

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
./mvnw test -Dtest=RealtyGroupLedgerEntryTypeTest,AdminActionTypeTest,TerminalCommandActionTest
```

Expected: 3 failures with `IllegalArgumentException: No enum constant ...`.

- [ ] **Step 3: Add the enum values**

Edit `RealtyGroupLedgerEntryType.java` — append after `ADJUSTMENT`:

```java
    /**
     * Sub-project G -- admin-initiated wallet adjustment (credit or debit).
     * Direction is encoded in the sign of {@code amount}. A required
     * {@code description} carries the freeform admin reason. Audit row is
     * paired via {@link com.slparcelauctions.backend.admin.audit.AdminActionType#REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT}.
     * See spec §7.2.
     */
    ADMIN_ADJUSTMENT
```

(Add a comma after `ADJUSTMENT` to make the list well-formed.)

Edit `AdminActionType.java` — append at the end before the closing brace:

```java
    ,
    REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT
```

Or simply add the new value to the enum list following the existing pattern.

Edit `TerminalCommandAction.java` — append a new value:

```java
    /**
     * Sub-project G -- pay L$ from the group wallet to a registered SL group
     * (rather than to an avatar). Bot fulfillment via
     * {@code Self.GiveGroupMoney(slGroupUuid, amount, memo)}. See spec §7.3 / §7.4.
     */
    WITHDRAW_GROUP
```

- [ ] **Step 4: Run the tests to confirm they pass**

```bash
./mvnw test -Dtest=RealtyGroupLedgerEntryTypeTest,AdminActionTypeTest,TerminalCommandActionTest
```

Expected: 3 passes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryType.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandAction.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupLedgerEntryTypeTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminActionTypeTest.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandActionTest.java
git commit -m "feat(realty-g): add ADMIN_ADJUSTMENT, REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT, WITHDRAW_GROUP enums"
```

---

## Task 3: Delete `AgentFeeDistributor` and its tests [parallel-safe]

**Files:**
- Delete: `backend/src/main/java/com/slparcelauctions/backend/realty/AgentFeeDistributor.java` (path may differ — search to confirm)
- Delete: any `AgentFeeDistributorTest.java` files in `backend/src/test`

- [ ] **Step 1: Locate every reference**

```bash
grep -rn "AgentFeeDistributor" backend/
```

Expected: import statements + test files + Javadoc references in `RealtyGroupWalletService`.

- [ ] **Step 2: Delete the class file and its tests**

```bash
git rm backend/src/main/java/com/slparcelauctions/backend/realty/AgentFeeDistributor.java
git rm backend/src/test/java/com/slparcelauctions/backend/realty/AgentFeeDistributorTest.java
```

(If grep returns additional locations, delete those too. Adjust paths to match the actual file locations.)

- [ ] **Step 3: Remove every remaining import/reference**

Edit `RealtyGroupWalletService.java` (the comment at line 50 / 55 references the distributor in Javadoc — clean up):

Locate and remove any:

```java
import com.slparcelauctions.backend.realty.AgentFeeDistributor;
```

Update the Javadoc that says `(called from AgentFeeDistributor)` to remove the reference (the method itself stays; only the comment is stale).

- [ ] **Step 4: Build and run all tests to confirm zero references remain and nothing breaks**

```bash
./mvnw clean compile
./mvnw test
grep -rn "AgentFeeDistributor" backend/ || echo "OK: no references"
```

Expected: clean compile + tests green + grep prints "OK: no references".

- [ ] **Step 5: Commit**

```bash
git add -A backend/
git commit -m "refactor(realty-g): remove AgentFeeDistributor (C-era case-1, unreachable post-E)"
```

---

## Task 4: Strip case-1 snapshot path from `RealtyGroupListingService` [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingServiceTest.java` (if it exists)

- [ ] **Step 1: Locate the case-1 path**

```bash
grep -n "case-1\|case 1\|agentFeeRate\|agentFeeSplit\|agent_fee_rate\|agent_fee_split" \
  backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java
```

Expected output identifies:
- Javadoc lines mentioning the case-1 path
- Snapshot logic that writes `auctions.agent_fee_rate` / `agent_fee_split` from `group.getAgentFeeRate()` / `group.getAgentFeeSplit()`
- Lines 44 and 118 mention `agentFeeRate` in Javadoc; line 163 has the case-3 comment.

- [ ] **Step 2: Remove the case-1 snapshot logic**

Delete the snapshot lines + Javadoc references. Concrete edit (paraphrase — match exact context in the current file):

```java
// REMOVE:
// auction.setAgentFeeRate(group.getAgentFeeRate());
// auction.setAgentFeeSplit(group.getAgentFeeSplit());
// auction.setAgentFeeAmt(BigDecimal.ZERO);  // backfilled by AgentFeeDistributor

// REMOVE Javadoc lines referencing case-1 snapshot.
```

After the strip, the create-listing method should only set the case-3-relevant fields (`realtyGroupId`, `realtyGroupMemberId`, etc.). The agent commission rate is stored on `RealtyGroupMember.agentCommissionRate` and used by `AgentCommissionDistributor` at payout time.

- [ ] **Step 3: Update unit tests**

Any tests that exercised the case-1 snapshot need either deletion or refactor to case-3. Refactor tests that also cover case-3 to keep only the case-3 assertions; delete tests that *only* exercised case-1.

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=RealtyGroupListingServiceTest
```

Expected: tests pass with the case-1 paths gone.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingServiceTest.java
git commit -m "refactor(realty-g): strip case-1 snapshot path from RealtyGroupListingService"
```

---

## Task 5: Strip `reassignListingAgentForCase1` [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java`
- Modify: associated test file

- [ ] **Step 1: Locate the method and its callers**

```bash
grep -rn "reassignListingAgentForCase1" backend/
```

- [ ] **Step 2: Delete the method**

Remove the method body and its `@Transactional` annotation. If the method was called from another service or event listener, update that call site to either:
- (a) Delete the call entirely if it was case-1-only logic.
- (b) Re-route to the equivalent case-3 reassignment if such logic exists.

- [ ] **Step 3: Update tests**

Delete tests that exercised this method. Run the affected test class to confirm nothing else broke.

- [ ] **Step 4: Run tests**

```bash
./mvnw test
grep -rn "reassignListingAgentForCase1" backend/ || echo "OK: no references"
```

Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "refactor(realty-g): remove reassignListingAgentForCase1 (case-1 path dead post-E)"
```

---

## Task 6: Strip `RealtyGroupService` fee branches + `UpdateRealtyGroupRequest` fields [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/dto/UpdateRealtyGroupRequest.java`
- Modify: any controller tests asserting `agentFeeRate` / `agentFeeSplit` flows through the update path.

- [ ] **Step 1: Edit `UpdateRealtyGroupRequest`**

Remove the `agentFeeRate` field (line 20: `@DecimalMin("0.0000") @DecimalMax("0.5000") BigDecimal agentFeeRate`) and the corresponding `agentFeeSplit` field, plus any related imports.

- [ ] **Step 2: Edit `RealtyGroupService.updateRealtyGroup`**

Remove the `touchesFees` branch (line 147), the `agentFeeRate` setter call (line 215-216), and the analogous `agentFeeSplit` branch. The method becomes simpler — only the non-fee fields (name, slug, logo, description) are updateable.

- [ ] **Step 3: Update affected tests**

Search for tests that pass `agentFeeRate` in the update payload:

```bash
grep -rn "agentFeeRate\|agentFeeSplit" backend/src/test/
```

Delete or refactor each. Any test asserting "update fee rate" can be deleted (the feature is gone); tests asserting "update group name" stay.

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=RealtyGroupServiceTest
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "refactor(realty-g): strip fee branches from RealtyGroupService + UpdateRealtyGroupRequest"
```

---

## Task 7: Drop `agentFeeRate` field from `RealtyGroup` entity [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java`

- [ ] **Step 1: Remove the field and its getter/setter**

Delete (lines 83-84 currently):

```java
@Column(name = "agent_fee_rate", precision = 5, scale = 4, nullable = false)
private BigDecimal agentFeeRate = BigDecimal.ZERO;
```

And the analogous `agentFeeSplit` field nearby (use the file to find the exact pattern). Lombok `@Getter`/`@Setter` handle accessor removal automatically; if the field has explicit getters/setters, delete those too.

- [ ] **Step 2: Build and verify zero references remain**

```bash
./mvnw clean compile 2>&1 | grep -E "(error|cannot find symbol)" || echo "OK"
grep -rn "getAgentFeeRate\|setAgentFeeRate\|getAgentFeeSplit\|setAgentFeeSplit" \
       backend/src/main/java/com/slparcelauctions/backend/realty/ || echo "OK: no callers"
```

Expected: clean compile + grep prints "OK: no callers".

- [ ] **Step 3: Run all tests**

```bash
./mvnw test
```

Expected: green.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java
git commit -m "refactor(realty-g): drop agentFeeRate/Split fields from RealtyGroup entity"
```

---

## Task 8: Drop `agentFeeRate` from `RealtyGroupPublicDto` + frontend type [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/dto/RealtyGroupPublicDto.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/dto/RealtyGroupDtoMapper.java` (the public mapper that populates the field)
- Modify: `frontend/src/types/realty.ts` (or wherever `RealtyGroupPublic` type lives — verify with grep)

- [ ] **Step 1: Edit `RealtyGroupPublicDto`**

Remove the `agentFeeRate` field (line 38) and the Javadoc bullet about it (line 13).

- [ ] **Step 2: Edit `RealtyGroupDtoMapper`**

Remove the line that copies `group.getAgentFeeRate()` into the DTO field.

- [ ] **Step 3: Find and update the frontend type**

```bash
grep -rn "agentFeeRate" frontend/src/
```

Remove the `agentFeeRate?: number` line from the matching TypeScript type definition (typically `frontend/src/types/realty.ts`). Also delete any usage in components — Section B (Task 27) handles the visual block; this task is just the type definition.

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=RealtyGroupDtoMapperTest
cd frontend && npm run lint && npm test
cd ..
```

Expected: backend test green + frontend lint clean. Frontend visual cleanup happens in Task 27.

- [ ] **Step 5: Commit**

```bash
git add backend/ frontend/
git commit -m "refactor(realty-g): drop agentFeeRate from RealtyGroupPublicDto + frontend type"
```

---

## Task 9: Drop `RealtyGroupPermission.SPEND_FROM_GROUP_WALLET` + fixture sweep [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java` (line 23-24)
- Modify: any test fixtures that assign `SPEND_FROM_GROUP_WALLET` to a member

- [ ] **Step 1: Locate every reference**

```bash
grep -rn "SPEND_FROM_GROUP_WALLET" backend/ frontend/
```

Expected: the enum definition + any test fixtures + possibly a frontend type that mirrors `RealtyGroupPermission` strings.

- [ ] **Step 2: Delete the enum value**

Edit `RealtyGroupPermission.java` — remove:

```java
/** Discretionary spend from the group wallet. */
SPEND_FROM_GROUP_WALLET,
```

(Confirm the trailing comma on the preceding value is still well-formed.)

- [ ] **Step 3: Scrub test fixtures**

For each test fixture assigning `SPEND_FROM_GROUP_WALLET`, either delete the assignment (if the test didn't depend on the permission's presence) or replace with `WITHDRAW_FROM_GROUP_WALLET` (if the test intent was "member has wallet operational rights").

Update frontend type definition (if the enum is mirrored as a string-union in `frontend/src/types/realty.ts`) — remove the string `"SPEND_FROM_GROUP_WALLET"` from the union.

- [ ] **Step 4: Build + test**

```bash
./mvnw clean compile
./mvnw test
cd frontend && npm run lint && npm test
cd ..
grep -rn "SPEND_FROM_GROUP_WALLET" backend/ frontend/ || echo "OK: no references"
```

Expected: clean compile + tests green + grep prints "OK: no references".

- [ ] **Step 5: Commit**

```bash
git add -A backend/ frontend/
git commit -m "refactor(realty-g): drop dead SPEND_FROM_GROUP_WALLET permission"
```

---

## Task 10: Apply V29 locally + smoke test

**Files:** none modified; verification only.

- [ ] **Step 1: Drop the dev DB and let Flyway re-apply all migrations**

The dev DB is local Postgres in Docker. Tear it down and re-up:

```bash
docker compose down -v
docker compose up -d postgres redis minio
# Wait for postgres healthcheck (5-15 seconds)
```

- [ ] **Step 2: Boot the backend and let Flyway run**

```bash
docker compose up --build backend
```

Watch logs for `Successfully applied 29 migrations`. Expected: V29 applies cleanly.

- [ ] **Step 3: Verify V29's effects via psql**

```bash
docker compose exec postgres psql -U slpa -d slpa -c "\d auctions" | grep -E "agent_fee" || echo "OK: agent_fee columns gone from auctions"
docker compose exec postgres psql -U slpa -d slpa -c "\d realty_groups" | grep -E "agent_fee" || echo "OK: agent_fee columns gone from realty_groups"
docker compose exec postgres psql -U slpa -d slpa -c "\d realty_groups" | grep "reports_threshold_notified"
docker compose exec postgres psql -U slpa -d slpa -c \
  "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'realty_group_ledger_entry_type_check';"
```

Expected:
- First two greps print "OK: agent_fee columns gone …".
- Third grep shows `reports_threshold_notified | boolean | not null default false`.
- Fourth shows the CHECK includes `'ADMIN_ADJUSTMENT'`.

- [ ] **Step 4: Run the full backend test suite against the freshly-migrated DB**

```bash
./mvnw test
```

Expected: green.

- [ ] **Step 5: Push the branch**

```bash
git push
```

(All Part 1 commits are now on the remote. Part 2 begins.)
