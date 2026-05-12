# Realty Groups Group Wallet — Part 2 (Tasks 11–20)

> Continuation of `2026-05-11-realty-groups-group-wallet-part1.md`. Wallet service primitives, agent-fee distribution, listing-fee routing, withdraw flow, dissolution gate.

---

### Task 11: `RealtyGroupRepository` gains wallet helpers

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupRepository.java`

Implements spec §5.3 step 6 (pessimistic-write lock), §10.1 (dormancy queries).

- [ ] **Step 1: Add the methods**

Append inside the repository interface:

```java
@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT g FROM RealtyGroup g WHERE g.id = :id")
Optional<RealtyGroup> findByIdForUpdate(@Param("id") Long id);

/**
 * Sub-project D §10.1: groups with positive balance and no dormancy phase
 * where no current member has rotated a refresh token within `windowDays`.
 */
@Query(value = """
    SELECT g.* FROM realty_groups g
    WHERE g.dissolved_at IS NULL
      AND g.balance_lindens > 0
      AND g.wallet_dormancy_phase IS NULL
      AND NOT EXISTS (
          SELECT 1
          FROM realty_group_members m
          JOIN refresh_tokens rt ON rt.user_id = m.user_id
          WHERE m.group_id = g.id
            AND rt.created_at > now() - make_interval(days => :windowDays)
      )
    """, nativeQuery = true)
List<RealtyGroup> findEligibleForDormancyFlag(@Param("windowDays") int windowDays);

/**
 * Groups whose current dormancy phase is due for the next escalation
 * (phase 1 -> 2 etc) based on `phaseDurationDays` since
 * wallet_dormancy_started_at.
 */
@Query(value = """
    SELECT g.* FROM realty_groups g
    WHERE g.wallet_dormancy_phase BETWEEN 1 AND 4
      AND g.wallet_dormancy_started_at < (now() - make_interval(days => :phaseDurationDays * g.wallet_dormancy_phase))
    """, nativeQuery = true)
List<RealtyGroup> findDormancyPhaseDue(@Param("phaseDurationDays") int phaseDurationDays);
```

Add imports as needed (`jakarta.persistence.LockModeType`, `org.springframework.data.repository.query.Param`, `org.springframework.data.jpa.repository.Query`, `java.util.List`, `java.util.Optional`).

- [ ] **Step 2: Compile check**

Run: `cd backend && ./mvnw -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupRepository.java
git commit -m "feat(realty-wallet): add findByIdForUpdate + dormancy query helpers"
git push
```

---

### Task 12: `RealtyGroupWalletService` — `creditAgentFee`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceCreditAgentFeeTest.java`

Implements spec §7.2 (creditAgentFee group leg).

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

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RealtyGroupWalletServiceCreditAgentFeeTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final GroupWalletBroadcastPublisher publisher = mock(GroupWalletBroadcastPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupWalletService service = new RealtyGroupWalletService(
        groupRepo, ledgerRepo, null, null, null, publisher, clock);
    // null deps will be wired in later tasks; this test only exercises creditAgentFee

    @Test
    void creditAgentFee_addsBalanceAppendsLedgerAndBroadcasts() {
        UUID publicId = UUID.randomUUID();
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(1000L).reservedLindens(0L)
            .build();
        g.setPublicIdForTest(publicId);  // assume test helper exposed for test only
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any(RealtyGroupLedgerEntry.class)))
            .thenAnswer(inv -> {
                RealtyGroupLedgerEntry entry = inv.getArgument(0);
                entry.setPublicIdForTest(UUID.randomUUID());
                return entry;
            });

        service.creditAgentFee(42L, 999L, 500L);

        assertThat(g.getBalanceLindens()).isEqualTo(1500L);
        ArgumentCaptor<RealtyGroupLedgerEntry> entryCap = ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(entryCap.capture());
        RealtyGroupLedgerEntry entry = entryCap.getValue();
        assertThat(entry.getGroupId()).isEqualTo(42L);
        assertThat(entry.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT);
        assertThat(entry.getAmount()).isEqualTo(500L);
        assertThat(entry.getBalanceAfter()).isEqualTo(1500L);
        assertThat(entry.getReservedAfter()).isEqualTo(0L);
        assertThat(entry.getRefType()).isEqualTo("AUCTION");
        assertThat(entry.getRefId()).isEqualTo(999L);
        assertThat(entry.getActorUserId()).isNull();
        verify(publisher).publish(eq(publicId), eq(1500L), eq(0L), eq(1500L),
            eq("AGENT_FEE_CREDIT"), any(UUID.class));
        verify(groupRepo).save(g);
    }
}
```

> **NOTE:** `setPublicIdForTest` helpers need adding to `BaseEntity`/`BaseMutableEntity` if not present, or the test refactored to use a saved-via-`@DataJpaTest` group. If `BaseEntity` does not allow public-id seeding from a test, replace with: `when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));` and read `g.getPublicId()` (which gets assigned at construction since `BaseEntity` generates it in `@PrePersist`). Adjust assertions to use the auto-assigned `g.getPublicId()`.

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupWalletServiceCreditAgentFeeTest`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement `RealtyGroupWalletService` with `creditAgentFee` only (more methods in later tasks)**

```java
package com.slparcelauctions.backend.realty.wallet;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core realty-group wallet operations. All methods are transactional;
 * primitive helpers run with Propagation.MANDATORY and assume the caller
 * has already begun a transaction and acquired the appropriate locks.
 *
 * <p>See spec docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupWalletService {

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final TerminalCommandRepository terminalCommandRepository;
    private final NotificationPublisher notificationPublisher;
    private final GroupWalletBroadcastPublisher broadcastPublisher;
    private final Clock clock;

    /* ============================================================ */
    /* AGENT FEE CREDIT (called from AgentFeeDistributor)            */
    /* ============================================================ */

    /**
     * Credit the group wallet with its share of agent_fee_amt. Called from
     * AgentFeeDistributor inside the escrow-payout-success transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditAgentFee(Long groupId, Long auctionId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        long newBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("AUCTION")
            .refId(auctionId)
            .createdAt(OffsetDateTime.now(clock))
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT.name(), entry.getPublicId());
        log.info("group agent fee credit: groupId={}, auctionId={}, amount={}, balanceAfter={}",
            groupId, auctionId, amount, newBalance);
    }

    /* ============================================================ */
    /* INTERNAL HELPERS                                              */
    /* ============================================================ */

    private void clearDormancyOnActivity(RealtyGroup group) {
        if (group.getWalletDormancyPhase() != null && group.getWalletDormancyPhase() != 99) {
            log.info("clearing group dormancy on activity: groupId={}, priorPhase={}",
                group.getId(), group.getWalletDormancyPhase());
            group.setWalletDormancyPhase(null);
            group.setWalletDormancyStartedAt(null);
        }
    }
}
```

> The test using `setPublicIdForTest` won't compile until a test helper exists. Easiest fix: change the test to use a mock that returns a `RealtyGroup` with `id` set (mocked) and the auto-generated public_id via reflection, OR call the service through a `@DataJpaTest` integration. If `BaseEntity.@PrePersist` assigns publicId, then the entity built by `RealtyGroup.builder()` may not have a publicId until persisted. In the unit test, set the publicId via reflection:
> ```java
> java.lang.reflect.Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
> f.setAccessible(true);
> f.set(g, publicId);
> ```
> Use this approach throughout the unit tests in this plan.

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupWalletServiceCreditAgentFeeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceCreditAgentFeeTest.java
git commit -m "feat(realty-wallet): RealtyGroupWalletService.creditAgentFee"
git push
```

---

### Task 13: `WalletService.creditAgentFee` (user side)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/wallet/WalletServiceCreditAgentFeeTest.java`

Implements spec §7.2 (creditAgentFee user leg).

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.wallet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.broadcast.WalletBroadcastPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WalletServiceCreditAgentFeeTest {

    @Test
    void creditAgentFee_addsBalanceAndAppendsLedger() {
        UserRepository userRepo = mock(UserRepository.class);
        UserLedgerRepository ledgerRepo = mock(UserLedgerRepository.class);
        BidReservationRepository resRepo = mock(BidReservationRepository.class);
        TerminalCommandRepository cmdRepo = mock(TerminalCommandRepository.class);
        WalletBroadcastPublisher pub = mock(WalletBroadcastPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

        WalletService svc = new WalletService(userRepo, ledgerRepo, resRepo, cmdRepo, pub, clock);

        User u = User.builder().id(7L).balanceLindens(0L).reservedLindens(0L).build();
        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));
        when(ledgerRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.creditAgentFee(7L, 999L, 250L);

        assertThat(u.getBalanceLindens()).isEqualTo(250L);
        ArgumentCaptor<UserLedgerEntry> cap = ArgumentCaptor.forClass(UserLedgerEntry.class);
        verify(ledgerRepo).save(cap.capture());
        UserLedgerEntry e = cap.getValue();
        assertThat(e.getUserId()).isEqualTo(7L);
        assertThat(e.getEntryType()).isEqualTo(UserLedgerEntryType.AGENT_FEE_CREDIT);
        assertThat(e.getAmount()).isEqualTo(250L);
        assertThat(e.getBalanceAfter()).isEqualTo(250L);
        assertThat(e.getRefType()).isEqualTo("AUCTION");
        assertThat(e.getRefId()).isEqualTo(999L);
    }
}
```

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=WalletServiceCreditAgentFeeTest`
Expected: FAIL — method missing.

- [ ] **Step 3: Add the method to `WalletService.java`**

Append after `creditListingFeeRefund`:

```java
/**
 * Credit the listing agent's user wallet with their share of agent_fee_amt.
 * Called from AgentFeeDistributor inside the escrow-payout-success transaction.
 */
@Transactional(propagation = Propagation.MANDATORY)
public void creditAgentFee(Long userId, Long auctionId, long amount) {
    if (amount <= 0) {
        throw new IllegalArgumentException("amount must be positive: " + amount);
    }
    User user = userRepository.findByIdForUpdate(userId).orElseThrow();
    UserLedgerEntry entry = creditCommon(user, amount,
        UserLedgerEntryType.AGENT_FEE_CREDIT, "AUCTION", auctionId);
    walletBroadcastPublisher.publish(user,
        UserLedgerEntryType.AGENT_FEE_CREDIT.name(), entry.getPublicId());
}
```

- [ ] **Step 4: Run the test (green)**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java \
        backend/src/test/java/com/slparcelauctions/backend/wallet/WalletServiceCreditAgentFeeTest.java
git commit -m "feat(realty-wallet): WalletService.creditAgentFee for user-side credit"
git push
```

---

### Task 14: `AgentFeeDistributor` service

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/agentfee/AgentFeeDistributor.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/agentfee/AgentFeeDistributorTest.java`

Implements spec §7.2.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.auction.agentfee;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.wallet.WalletService;

import static org.mockito.Mockito.*;

class AgentFeeDistributorTest {

    private final RealtyGroupWalletService groupSvc = mock(RealtyGroupWalletService.class);
    private final WalletService userSvc = mock(WalletService.class);
    private final AgentFeeDistributor dist = new AgentFeeDistributor(groupSvc, userSvc);

    @Test
    void splitsAgentFeeBetweenGroupAndAgentByFloorOfSplit() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        User agent = User.builder().id(7L).build();
        when(a.getListingAgent()).thenReturn(agent);

        // 50 * 0.5 = 25 group, 25 agent
        dist.distribute(a, 50L);

        verify(groupSvc).creditAgentFee(42L, 999L, 25L);
        verify(userSvc).creditAgentFee(7L, 999L, 25L);
    }

    @Test
    void floorRoundingOnOddSplit() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        User agent = User.builder().id(7L).build();
        when(a.getListingAgent()).thenReturn(agent);

        // 51 * 0.5 = 25.5, floor -> 25 group, 26 agent
        dist.distribute(a, 51L);

        verify(groupSvc).creditAgentFee(42L, 999L, 25L);
        verify(userSvc).creditAgentFee(7L, 999L, 26L);
    }

    @Test
    void zeroSplitMeansAgentGetsEverything() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(BigDecimal.ZERO);
        when(a.getId()).thenReturn(999L);
        User agent = User.builder().id(7L).build();
        when(a.getListingAgent()).thenReturn(agent);

        dist.distribute(a, 100L);

        verify(groupSvc, never()).creditAgentFee(anyLong(), anyLong(), anyLong());
        verify(userSvc).creditAgentFee(7L, 999L, 100L);
    }

    @Test
    void fullSplitMeansGroupGetsEverything() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("1.0"));
        when(a.getId()).thenReturn(999L);
        User agent = User.builder().id(7L).build();
        when(a.getListingAgent()).thenReturn(agent);

        dist.distribute(a, 100L);

        verify(groupSvc).creditAgentFee(42L, 999L, 100L);
        verify(userSvc, never()).creditAgentFee(anyLong(), anyLong(), anyLong());
    }

    @Test
    void nullGroupIdSkipsGroupCredit() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(null);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        User agent = User.builder().id(7L).build();
        when(a.getListingAgent()).thenReturn(agent);

        dist.distribute(a, 50L);

        verify(groupSvc, never()).creditAgentFee(anyLong(), anyLong(), anyLong());
        verify(userSvc).creditAgentFee(7L, 999L, 25L);
    }

    @Test
    void nullAgentSkipsAgentCredit() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        when(a.getListingAgent()).thenReturn(null);

        dist.distribute(a, 50L);

        verify(groupSvc).creditAgentFee(42L, 999L, 25L);
        verify(userSvc, never()).creditAgentFee(anyLong(), anyLong(), anyLong());
    }
}
```

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=AgentFeeDistributorTest`
Expected: FAIL — class not defined.

- [ ] **Step 3: Implement**

```java
package com.slparcelauctions.backend.auction.agentfee;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService;
import com.slparcelauctions.backend.wallet.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Distributes agent_fee_amt at escrow-payout-success time.
 * floor(agent_fee_amt * agent_fee_split) -> group wallet,
 * remainder -> agent's user wallet. See spec §7.2.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentFeeDistributor {

    private final RealtyGroupWalletService groupWalletService;
    private final WalletService userWalletService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void distribute(Auction auction, long agentFeeAmt) {
        if (agentFeeAmt <= 0) {
            return;
        }
        BigDecimal split = auction.getAgentFeeSplit() == null
            ? BigDecimal.ZERO : auction.getAgentFeeSplit();
        long groupSlice = BigDecimal.valueOf(agentFeeAmt)
            .multiply(split)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact();
        long agentSlice = agentFeeAmt - groupSlice;

        Long groupId = auction.getRealtyGroupId();
        Long agentId = auction.getListingAgent() != null
            ? auction.getListingAgent().getId() : null;

        if (groupId == null && groupSlice > 0) {
            log.warn("agent fee groupSlice={} but realty_group_id null on auction {}; absorbed by seller payout",
                groupSlice, auction.getId());
        }
        if (agentId == null && agentSlice > 0) {
            log.warn("agent fee agentSlice={} but listing_agent_id null on auction {}; absorbed by seller payout",
                agentSlice, auction.getId());
        }

        if (groupId != null && groupSlice > 0) {
            groupWalletService.creditAgentFee(groupId, auction.getId(), groupSlice);
        }
        if (agentId != null && agentSlice > 0) {
            userWalletService.creditAgentFee(agentId, auction.getId(), agentSlice);
        }
    }
}
```

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=AgentFeeDistributorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/agentfee/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/agentfee/
git commit -m "feat(realty-wallet): AgentFeeDistributor splits fee by floor(amount * split)"
git push
```

---

### Task 15: `EscrowService.createForEndedAuction` subtracts `agent_fee_amt`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowServiceAgentFeePayoutTest.java`

Implements spec §7.1.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.escrow;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EscrowServiceAgentFeePayoutTest {

    /**
     * Unit-style: focuses purely on payoutAmt computation.
     * In practice this is a slice/integration test against the real
     * EscrowService; for the minimal version, exercise the payout formula.
     */
    @Test
    void payoutAmt_subtractsAgentFeeWhenSet() {
        // commission.payout(finalBid) == finalBid - commission.commission(finalBid)
        // Simulate: finalBid=10000, commission=500, agent_fee_amt=200
        // Expected payoutAmt = 10000 - 500 - 200 = 9300.
        Auction a = mock(Auction.class);
        when(a.getAgentFeeAmt()).thenReturn(200L);
        long base = 9500L;  // pre-agent-fee payout (finalBid - commission)
        long agentFee = a.getAgentFeeAmt() == null ? 0L : a.getAgentFeeAmt();
        long payoutAmt = base - agentFee;
        assertThat(payoutAmt).isEqualTo(9300L);
    }

    @Test
    void payoutAmt_unchangedWhenAgentFeeNull() {
        Auction a = mock(Auction.class);
        when(a.getAgentFeeAmt()).thenReturn(null);
        long base = 9500L;
        long agentFee = a.getAgentFeeAmt() == null ? 0L : a.getAgentFeeAmt();
        assertThat(base - agentFee).isEqualTo(9500L);
    }
}
```

> **Better test:** if `EscrowService.createForEndedAuction` can be exercised with a real `@SpringBootTest` integration, write the test there asserting that `escrow.getPayoutAmt()` is reduced. The unit test above is a thin sanity check; the implementer should also add a slice or integration test that drives the full path through `AuctionEndTask.closeOne -> EscrowService.createForEndedAuction -> assert escrow.payoutAmt`.

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=EscrowServiceAgentFeePayoutTest`
Expected: PASS — the unit test passes by construction; but the integration test will need the code change.

- [ ] **Step 3: Modify `EscrowService.createForEndedAuction`**

Replace:
```java
.payoutAmt(commission.payout(finalBid))
```
with:
```java
.payoutAmt(commission.payout(finalBid) - nullToZero(auction.getAgentFeeAmt()))
```

Add a private helper at class bottom:
```java
private static long nullToZero(Long v) { return v == null ? 0L : v; }
```

- [ ] **Step 4: Run the full test suite**

Run: `cd backend && ./mvnw test`
Expected: PASS — no test regressions. Existing escrow tests still hold because `agent_fee_amt` is null on all pre-D test fixtures.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowServiceAgentFeePayoutTest.java
git commit -m "feat(realty-wallet): subtract agent_fee_amt from escrow payoutAmt at creation"
git push
```

---

### Task 16: `TerminalCommandService.handleEscrowPayoutSuccess` calls `AgentFeeDistributor`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandServicePayoutDistributesAgentFeeTest.java`

Implements spec §7.1 (Site B) + §7.5.

- [ ] **Step 1: Write the failing test**

Slice / integration test asserting that after a successful PAYOUT callback for an auction with `agent_fee_amt > 0`, `groupWalletService.creditAgentFee` was invoked (verify via repository state — group balance increased — or via injected mock if extracted to a collaborator). Pattern: extend existing payout callback tests to add a group-listed auction fixture.

```java
package com.slparcelauctions.backend.escrow.command;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
// ... typical SpringBootTest imports

@SpringBootTest
@Transactional
class TerminalCommandServicePayoutDistributesAgentFeeTest {

    @Autowired TerminalCommandService cmdSvc;
    // ... repositories for setting up an auction with agent_fee_amt + agent_fee_split + realty_group_id

    @Test
    void successfulPayoutCallbackCreditsGroupAndAgentWallets() {
        // setup: create realty group with zero balance, list auction under it,
        // run AuctionEndTask close path to set agent_fee_amt=50, agent_fee_split=0.5,
        // pre-stamp escrow as TRANSFER_PENDING with payoutAmt = finalBid-commission-50.
        // ...

        // Act: simulate PAYOUT success callback
        cmdSvc.applyCallback(new PayoutResultRequest("ESC-1-PAYOUT-1", "OK",
            "slTxn-abc", null /* errorMessage */));

        // Assert: group balance 25 (floor(50*0.5)), agent balance 25.
        // assertThat(reload(group).balance).isEqualTo(25L);
        // assertThat(reload(agent).balance).isEqualTo(25L);
    }
}
```

> The implementer should base this on the existing escrow-callback test class. Helper fixtures from `RealtyGroupListingIntegrationTest` (the C-era test) supply auctions with `realty_group_id` set.

- [ ] **Step 2: Run the test (red)**

Run: `cd backend && ./mvnw test -Dtest=TerminalCommandServicePayoutDistributesAgentFeeTest`
Expected: FAIL — distributor not invoked.

- [ ] **Step 3: Wire `AgentFeeDistributor` into `handleEscrowPayoutSuccess`**

Inject the distributor:

```java
private final com.slparcelauctions.backend.auction.agentfee.AgentFeeDistributor agentFeeDistributor;
```

Append at the end of `handleEscrowPayoutSuccess`, after the existing `notificationPublisher.escrowPayout(...)` call:

```java
// Sub-project D §7.2: now that the L$ has confirmed-departed SLPA for
// the reduced payoutAmt, the agent_fee_amt L$ left in our system is
// distributable. Split between group wallet and agent user wallet per
// agent_fee_split, snapshotted on the auction.
long agentFeeAmt = finalEscrow.getAuction().getAgentFeeAmt() == null
    ? 0L : finalEscrow.getAuction().getAgentFeeAmt();
if (agentFeeAmt > 0) {
    agentFeeDistributor.distribute(finalEscrow.getAuction(), agentFeeAmt);
}
```

- [ ] **Step 4: Run the test (green)**

Run: `cd backend && ./mvnw test -Dtest=TerminalCommandServicePayoutDistributesAgentFeeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandServicePayoutDistributesAgentFeeTest.java
git commit -m "feat(realty-wallet): distribute agent fee on escrow payout success"
git push
```

---

### Task 17: `RealtyGroupWalletService.debitListingFee`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceDebitListingFeeTest.java`

Implements spec §5.4.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.wallet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RealtyGroupWalletServiceDebitListingFeeTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final GroupWalletBroadcastPublisher pub = mock(GroupWalletBroadcastPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupWalletService svc =
        new RealtyGroupWalletService(groupRepo, ledgerRepo, null, null, null, pub, clock);

    @Test
    void debitsBalanceAndAppendsLedgerWithActor() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(1000L).reservedLindens(0L).build();
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.debitListingFee(42L, 999L, 250L, 7L);

        assertThat(g.getBalanceLindens()).isEqualTo(750L);
        ArgumentCaptor<RealtyGroupLedgerEntry> cap = ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(cap.capture());
        RealtyGroupLedgerEntry e = cap.getValue();
        assertThat(e.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT);
        assertThat(e.getAmount()).isEqualTo(250L);
        assertThat(e.getRefType()).isEqualTo("AUCTION");
        assertThat(e.getRefId()).isEqualTo(999L);
        assertThat(e.getActorUserId()).isEqualTo(7L);
    }

    @Test
    void throwsWhenInsufficientBalance() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(100L).reservedLindens(0L).build();
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> svc.debitListingFee(42L, 999L, 250L, 7L))
            .isInstanceOf(InsufficientGroupBalanceException.class);
        verify(ledgerRepo, never()).save(any());
    }
}
```

- [ ] **Step 2: Run the test (red)**

Expected: FAIL — method missing.

- [ ] **Step 3: Implement**

Append to `RealtyGroupWalletService`:

```java
/* ============================================================ */
/* LISTING FEE DEBIT                                             */
/* ============================================================ */

/**
 * Debit a group-listed auction's listing fee from the group wallet.
 * Called from MeWalletController.payListingFee branching on
 * auction.realty_group_id != null. Spec §5.4.
 */
@Transactional(propagation = Propagation.MANDATORY)
public void debitListingFee(Long groupId, Long auctionId, long amount, Long actorUserId) {
    if (amount <= 0) {
        throw new IllegalArgumentException("amount must be positive: " + amount);
    }
    RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
    long available = group.availableLindens();
    if (available < amount) {
        throw new com.slparcelauctions.backend.realty.wallet.exception
            .InsufficientGroupBalanceException(available, amount);
    }
    long newBalance = group.getBalanceLindens() - amount;
    group.setBalanceLindens(newBalance);
    clearDormancyOnActivity(group);
    groupRepository.save(group);

    RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
        .groupId(groupId)
        .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT)
        .amount(amount)
        .balanceAfter(newBalance)
        .reservedAfter(group.getReservedLindens())
        .refType("AUCTION")
        .refId(auctionId)
        .actorUserId(actorUserId)
        .createdAt(OffsetDateTime.now(clock))
        .build());

    broadcastPublisher.publish(group.getPublicId(),
        newBalance, group.getReservedLindens(), group.availableLindens(),
        RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT.name(), entry.getPublicId());
    log.info("group listing fee debit: groupId={}, auctionId={}, amount={}, balanceAfter={}, actor={}",
        groupId, auctionId, amount, newBalance, actorUserId);
}
```

- [ ] **Step 4: Run the test (green)**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceDebitListingFeeTest.java
git commit -m "feat(realty-wallet): RealtyGroupWalletService.debitListingFee"
git push
```

---

### Task 18: `MeWalletController.payListingFee` branches on `auction.realty_group_id`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/me/MeWalletController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/wallet/me/MeWalletControllerPayListingFeeGroupTest.java`

Implements spec §5.4.

- [ ] **Step 1: Write the failing slice test**

```java
package com.slparcelauctions.backend.wallet.me;

import org.junit.jupiter.api.Test;
// ... standard slice/integration imports

class MeWalletControllerPayListingFeeGroupTest {

    @Test
    void groupListedAuctionDebitsGroupWalletNotUserWallet() {
        // setup: group with balance=1000, agent who owns parcel listed
        // under group, draft auction with listingFeeAmt=200,
        // realty_group_id set.
        //
        // act: POST /me/auctions/{publicId}/pay-listing-fee
        //
        // assert:
        //   - group.balance_lindens decreased by 200
        //   - user.balance_lindens unchanged
        //   - realty_group_ledger row exists with type=LISTING_FEE_DEBIT, actor=agent
        //   - auction.status = DRAFT_PAID
    }

    @Test
    void individualListedAuctionStillDebitsUserWallet() {
        // setup: user with balance=1000, draft auction NO realty_group_id, fee=200.
        // act: same endpoint
        // assert: user balance decreased; no group ledger row.
    }

    @Test
    void groupInsufficientBalanceReturns422() {
        // setup: group balance=50, fee=200.
        // assert: 422 with INSUFFICIENT_GROUP_BALANCE body.
    }
}
```

- [ ] **Step 2: Run the test (red)**

Expected: FAIL — current behavior debits user wallet for all auctions.

- [ ] **Step 3: Modify the controller**

Inject `RealtyGroupWalletService` into `MeWalletController`. Replace the `walletService.debitListingFee(user, amount, auction.getId());` line with:

```java
if (auction.getRealtyGroupId() != null) {
    realtyGroupWalletService.debitListingFee(
        auction.getRealtyGroupId(), auction.getId(), amount, user.getId());
} else {
    walletService.debitListingFee(user, amount, auction.getId());
}
```

- [ ] **Step 4: Run the test (green)**

Expected: PASS. Run the full wallet-controller test suite to confirm no regressions:
```
./mvnw test -Dtest='MeWalletController*Test'
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/me/MeWalletController.java \
        backend/src/test/java/com/slparcelauctions/backend/wallet/me/MeWalletControllerPayListingFeeGroupTest.java
git commit -m "feat(realty-wallet): route group-listed auctions to group wallet at pay-listing-fee"
git push
```

---

### Task 19: `RealtyGroupWalletService.creditListingFeeRefund`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceCreditListingFeeRefundTest.java`

Implements spec §8.3.

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.wallet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RealtyGroupWalletServiceCreditListingFeeRefundTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final GroupWalletBroadcastPublisher pub = mock(GroupWalletBroadcastPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
    private final RealtyGroupWalletService svc =
        new RealtyGroupWalletService(groupRepo, ledgerRepo, null, null, null, pub, clock);

    @Test
    void creditsBalanceAndAppendsRefundLedger() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(500L).build();
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.creditListingFeeRefund(42L, 999L, 200L, 17L);

        assertThat(g.getBalanceLindens()).isEqualTo(700L);
        ArgumentCaptor<RealtyGroupLedgerEntry> cap = ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(cap.capture());
        assertThat(cap.getValue().getEntryType())
            .isEqualTo(RealtyGroupLedgerEntryType.LISTING_FEE_REFUND);
        assertThat(cap.getValue().getRefType()).isEqualTo("LISTING_FEE_REFUND");
        assertThat(cap.getValue().getRefId()).isEqualTo(17L);
    }

    @Test
    void rejectsDissolvedGroup() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(0L).build();
        g.setDissolvedAt(OffsetDateTime.now());
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> svc.creditListingFeeRefund(42L, 999L, 200L, 17L))
            .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the test (red)**

Expected: FAIL.

- [ ] **Step 3: Implement**

```java
@Transactional(propagation = Propagation.MANDATORY)
public void creditListingFeeRefund(Long groupId, Long auctionId, long amount, Long refundRowId) {
    if (amount <= 0) {
        throw new IllegalArgumentException("amount must be positive: " + amount);
    }
    RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
    if (group.getDissolvedAt() != null) {
        throw new IllegalStateException(
            "cannot credit dissolved group " + group.getPublicId()
            + " for listing-fee refund row " + refundRowId);
    }
    long newBalance = group.getBalanceLindens() + amount;
    group.setBalanceLindens(newBalance);
    clearDormancyOnActivity(group);
    groupRepository.save(group);

    RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
        .groupId(groupId)
        .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_REFUND)
        .amount(amount)
        .balanceAfter(newBalance)
        .reservedAfter(group.getReservedLindens())
        .refType("LISTING_FEE_REFUND")
        .refId(refundRowId)
        .createdAt(OffsetDateTime.now(clock))
        .build());

    broadcastPublisher.publish(group.getPublicId(),
        newBalance, group.getReservedLindens(), group.availableLindens(),
        RealtyGroupLedgerEntryType.LISTING_FEE_REFUND.name(), entry.getPublicId());
    log.info("group listing fee refund credit: groupId={}, refundId={}, amount={}, balanceAfter={}",
        groupId, refundRowId, amount, newBalance);
}
```

- [ ] **Step 4: Run the test (green)**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceCreditListingFeeRefundTest.java
git commit -m "feat(realty-wallet): RealtyGroupWalletService.creditListingFeeRefund"
git push
```

---

### Task 20: `ListingFeeRefundProcessorTask` routes by originating ledger row

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/ListingFeeRefundProcessorTask.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/escrow/scheduler/ListingFeeRefundProcessorTaskGroupRoutingTest.java`

Implements spec §8.1.

- [ ] **Step 1: Read the existing processor**

Run: `cat backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/ListingFeeRefundProcessorTask.java`

Note the current shape — it processes each `listing_fee_refunds` row by calling `walletService.creditListingFeeRefund(...)`. The change is to look up the originating ledger row first.

- [ ] **Step 2: Write the failing slice test**

```java
package com.slparcelauctions.backend.escrow.scheduler;

import org.junit.jupiter.api.Test;
// ... standard SpringBootTest imports

class ListingFeeRefundProcessorTaskGroupRoutingTest {

    @Test
    void dEraGroupListingRefundRoutesToGroupWallet() {
        // setup: group-listed auction, D-era flow: realty_group_ledger
        // LISTING_FEE_DEBIT row exists, listing_fee_refunds row pending.
        // act: processor.processOnce()
        // assert: group.balance_lindens increased; user.balance_lindens unchanged.
    }

    @Test
    void cEraGroupListingRefundRoutesToUserWallet() {
        // setup: group-listed auction whose listing fee was debited from
        // user_ledger (NOT realty_group_ledger). listing_fee_refunds row pending.
        // act: processor.processOnce()
        // assert: user.balance_lindens increased; group.balance_lindens unchanged.
    }

    @Test
    void individualListingRefundRoutesToUserWallet() {
        // setup: individual listing, listing_fee_refunds row pending.
        // assert: user.balance_lindens increased.
    }
}
```

- [ ] **Step 3: Run the tests (red)**

Run: `cd backend && ./mvnw test -Dtest=ListingFeeRefundProcessorTaskGroupRoutingTest`
Expected: FAIL — group routing not implemented; (1) and (3) probably pass; (1) fails because the refund goes to the seller's user wallet instead of the group wallet.

- [ ] **Step 4: Modify the processor**

Inject:
```java
private final RealtyGroupLedgerRepository realtyGroupLedgerRepository;
private final RealtyGroupWalletService realtyGroupWalletService;
```

Change the per-row processing logic. Where it currently does:
```java
walletService.creditListingFeeRefund(seller, refund.getAmount(), refund.getId());
```

Replace with:
```java
Long auctionId = refund.getAuction().getId();
Optional<RealtyGroupLedgerEntry> groupDebit =
    realtyGroupLedgerRepository.findListingFeeDebitForAuction(auctionId);
if (groupDebit.isPresent()) {
    realtyGroupWalletService.creditListingFeeRefund(
        groupDebit.get().getGroupId(), auctionId, refund.getAmount(), refund.getId());
} else {
    walletService.creditListingFeeRefund(seller, refund.getAmount(), refund.getId());
}
```

- [ ] **Step 5: Run the tests (green)**

Run: `cd backend && ./mvnw test -Dtest=ListingFeeRefundProcessorTaskGroupRoutingTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/ListingFeeRefundProcessorTask.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/scheduler/ListingFeeRefundProcessorTaskGroupRoutingTest.java
git commit -m "feat(realty-wallet): listing-fee refund routes by originating ledger row"
git push
```

---

End of Part 2. Continue to `2026-05-11-realty-groups-group-wallet-part3.md` for Tasks 21–30.
