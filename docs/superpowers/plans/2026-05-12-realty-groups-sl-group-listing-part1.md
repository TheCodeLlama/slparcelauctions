# Realty Groups E — Implementation Plan — Part 1 (Tasks 1–10)

Tasks in this file: schema migration, new entity, permission enum, member commission rate field, auction field additions, World API client extension, exception classes, code generator, SL-group service register/list/delete primitives.

Read `2026-05-12-realty-groups-sl-group-listing.md` first for the goal, file structure, conventions, and architecture overview.

---

## Task 1: Flyway V27 migration

Implements spec §3.1 (new table), §3.2 (column additions), §4.1 (MANAGE_OWN_LISTING strip).

**Files:**
- Create: `backend/src/main/resources/db/migration/V27__realty_group_sl_groups.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V27: realty groups sub-project E — SL-group-owned listings.

CREATE TABLE realty_group_sl_groups (
    id                              BIGSERIAL PRIMARY KEY,
    public_id                       UUID NOT NULL UNIQUE,
    realty_group_id                 BIGINT NOT NULL REFERENCES realty_groups(id),
    sl_group_uuid                   UUID NOT NULL,
    sl_group_name                   VARCHAR(255),
    verified                        BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at                     TIMESTAMPTZ,
    verified_via                    VARCHAR(20),
    verification_code               VARCHAR(32),
    verification_code_expires_at    TIMESTAMPTZ,
    last_polled_at                  TIMESTAMPTZ,
    poll_attempts                   INTEGER NOT NULL DEFAULT 0,
    founder_avatar_uuid             UUID,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_rg_sl_groups_sl_group_uuid UNIQUE (sl_group_uuid),
    CONSTRAINT ck_rg_sl_groups_verified_via
      CHECK (verified_via IS NULL OR verified_via IN ('ABOUT_TEXT', 'FOUNDER_TERMINAL'))
);

CREATE INDEX ix_rg_sl_groups_realty_group
  ON realty_group_sl_groups(realty_group_id);

CREATE INDEX ix_rg_sl_groups_pending_poll
  ON realty_group_sl_groups(last_polled_at)
  WHERE verified = false AND verified_via IS NULL;

ALTER TABLE realty_group_members
  ADD COLUMN agent_commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0;

ALTER TABLE realty_group_members
  ADD CONSTRAINT ck_rg_members_commission_rate_nonneg
    CHECK (agent_commission_rate >= 0);

ALTER TABLE auctions
  ADD COLUMN agent_commission_rate DECIMAL(5,4) NULL;

ALTER TABLE auctions
  ADD COLUMN realty_group_sl_group_id BIGINT NULL
    REFERENCES realty_group_sl_groups(id);

CREATE INDEX ix_auctions_realty_group_sl_group_id
  ON auctions(realty_group_sl_group_id)
  WHERE realty_group_sl_group_id IS NOT NULL;

-- MANAGE_OWN_LISTING was C-era case-2 plumbing. Case 2 was removed by E.
-- Strip the value from any existing permissions array (precautionary; no production rows today).
UPDATE realty_group_members
   SET permissions = array_remove(permissions, 'MANAGE_OWN_LISTING')
 WHERE 'MANAGE_OWN_LISTING' = ANY(permissions);
```

- [ ] **Step 2: Run the existing context-load test to confirm the migration applies cleanly**

Run: `cd backend && ./mvnw test -Dtest=BackendApplicationTests#contextLoads`
Expected: PASS. `BackendApplicationTests.contextLoads()` exercises Flyway on startup; the new migration must apply without error.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V27__realty_group_sl_groups.sql
git commit -m "feat(realty-slgroup): V27 migration — sl_groups table + member/auction columns"
git push
```

---

## Task 2: `RealtyGroupSlGroup` entity + repository

Implements spec §3.1 (the entity matching the table).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroup.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyMethod.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepositoryTest.java`

- [ ] **Step 1: Create the verify-method enum**

`backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyMethod.java`:

```java
package com.slparcelauctions.backend.realty.slgroup;

/**
 * Which verification path actually flipped a {@link RealtyGroupSlGroup} row to
 * {@code verified=true}. {@code null} while the row is still pending.
 *
 * <ul>
 *   <li>{@code ABOUT_TEXT} — the leader put the verification code in the SL group's About text
 *       and the about-text poll task observed it.</li>
 *   <li>{@code FOUNDER_TERMINAL} — the SL group's founder stepped onto an SLPA terminal and
 *       typed the verification code; backend cross-checked the avatar UUID against the SL
 *       group's founder via the World API.</li>
 * </ul>
 */
public enum SlGroupVerifyMethod {
    ABOUT_TEXT,
    FOUNDER_TERMINAL
}
```

- [ ] **Step 2: Write the entity**

`backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroup.java`:

```java
package com.slparcelauctions.backend.realty.slgroup;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A realty group's claim on an SL group it manages land for. Created in a pending state
 * (verified=false, verification_code populated); flipped to verified by one of two paths
 * (about-text polling or founder-terminal callback). UNIQUE(sl_group_uuid) ensures an SL
 * group is registered to at most one realty group at any time across the whole system.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md}
 * §3.1, §7.
 */
@Entity
@Table(name = "realty_group_sl_groups",
        indexes = {
            @Index(name = "ix_rg_sl_groups_realty_group", columnList = "realty_group_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroupSlGroup extends BaseMutableEntity {

    @Column(name = "realty_group_id", nullable = false)
    private Long realtyGroupId;

    @Column(name = "sl_group_uuid", nullable = false)
    private UUID slGroupUuid;

    @Column(name = "sl_group_name")
    private String slGroupName;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verified_via", length = 20)
    private SlGroupVerifyMethod verifiedVia;

    @Column(name = "verification_code", length = 32)
    private String verificationCode;

    @Column(name = "verification_code_expires_at")
    private OffsetDateTime verificationCodeExpiresAt;

    @Column(name = "last_polled_at")
    private OffsetDateTime lastPolledAt;

    @Column(name = "poll_attempts", nullable = false)
    private int pollAttempts;

    @Column(name = "founder_avatar_uuid")
    private UUID founderAvatarUuid;
}
```

- [ ] **Step 3: Write the repository**

`backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java`:

```java
package com.slparcelauctions.backend.realty.slgroup;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RealtyGroupSlGroupRepository extends JpaRepository<RealtyGroupSlGroup, Long> {

    Optional<RealtyGroupSlGroup> findByPublicId(UUID publicId);

    /** UNIQUE constraint guarantees at most one row per sl_group_uuid. */
    Optional<RealtyGroupSlGroup> findBySlGroupUuid(UUID slGroupUuid);

    List<RealtyGroupSlGroup> findByRealtyGroupIdOrderByCreatedAtDesc(Long realtyGroupId);

    /**
     * Verified row for a (realty group, sl group) pair, if any. Used by the listing-create
     * gate: the parcel's owner SL group UUID must have a verified registration for the realty
     * group the agent is listing under.
     */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.realtyGroupId = :realtyGroupId
           AND r.slGroupUuid = :slGroupUuid
           AND r.verified = true
        """)
    Optional<RealtyGroupSlGroup> findVerifiedForListing(
            @Param("realtyGroupId") Long realtyGroupId,
            @Param("slGroupUuid") UUID slGroupUuid);

    /**
     * Pending rows due for an about-text poll. The partial index
     * ix_rg_sl_groups_pending_poll covers this query (verified=false AND
     * verified_via IS NULL).
     */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.verified = false
           AND r.verifiedVia IS NULL
           AND r.verificationCodeExpiresAt > :now
           AND (r.lastPolledAt IS NULL OR r.lastPolledAt < :pollCutoff)
        """)
    List<RealtyGroupSlGroup> findDueForAboutTextPoll(
            @Param("now") OffsetDateTime now,
            @Param("pollCutoff") OffsetDateTime pollCutoff);

    /** Pending rows whose verification window has expired. Used by the hourly cleanup task. */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.verified = false
           AND r.verificationCodeExpiresAt < :now
        """)
    List<RealtyGroupSlGroup> findExpiredPending(@Param("now") OffsetDateTime now);

    /** Used by founder-terminal callback to find the pending row by code. */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.verified = false
           AND r.verificationCode = :code
           AND r.verificationCodeExpiresAt > :now
        """)
    Optional<RealtyGroupSlGroup> findPendingByCode(
            @Param("code") String code,
            @Param("now") OffsetDateTime now);

    /** Used by dissolve gate. */
    long countByRealtyGroupId(Long realtyGroupId);
}
```

- [ ] **Step 4: Write the repository test**

`backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepositoryTest.java`:

```java
package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.slparcelauctions.backend.common.JpaConfig;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RealtyGroupSlGroupRepositoryTest {

    @Autowired RealtyGroupSlGroupRepository repo;

    @Test
    void persistsAndRetrievesByPublicId() {
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
                .realtyGroupId(1L)
                .slGroupUuid(UUID.randomUUID())
                .slGroupName("Test Group")
                .verified(false)
                .verificationCode("SLPA-ABCDEFGH")
                .verificationCodeExpiresAt(OffsetDateTime.now().plusDays(7))
                .build();
        // BaseMutableEntity assigns publicId at construction; capture it before save.
        UUID publicId = row.getPublicId();
        repo.save(row);

        assertThat(repo.findByPublicId(publicId)).isPresent();
    }

    @Test
    void findVerifiedForListing_returnsOnlyVerifiedRows() {
        UUID slGroup = UUID.randomUUID();
        RealtyGroupSlGroup pending = RealtyGroupSlGroup.builder()
                .realtyGroupId(1L)
                .slGroupUuid(slGroup)
                .verified(false)
                .build();
        repo.save(pending);

        assertThat(repo.findVerifiedForListing(1L, slGroup)).isEmpty();

        pending.setVerified(true);
        pending.setVerifiedAt(OffsetDateTime.now());
        repo.save(pending);

        assertThat(repo.findVerifiedForListing(1L, slGroup)).isPresent();
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupSlGroupRepositoryTest`
Expected: PASS, 2/2.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroup.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyMethod.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepositoryTest.java
git commit -m "feat(realty-slgroup): RealtyGroupSlGroup entity + repository"
git push
```

---

## Task 3: `RealtyGroupPermission` enum — drop `MANAGE_OWN_LISTING`, add `REGISTER_SL_GROUP`

Implements spec §4.1.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java`
- Test: existing `RealtyGroupPermissionTest` (if any) + new enum-value test

- [ ] **Step 1: Update the enum**

Replace the contents of `backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java`:

```java
package com.slparcelauctions.backend.realty.permission;

/**
 * Per-(group, agent) capability flags for the realty groups feature.
 *
 * <p>Stored on {@code realty_group_members.permissions} as a Postgres {@code TEXT[]} of enum
 * names. The leader of a group holds every permission implicitly (the authorizer short-circuits
 * to {@code true} when {@code user_id == realty_groups.leader_id}); a non-leader member only
 * holds the permissions present in their array.
 */
public enum RealtyGroupPermission {
    INVITE_AGENTS,
    REMOVE_AGENTS,
    EDIT_GROUP_PROFILE,
    CONFIGURE_FEES,

    /** Create an auction listing under this group. */
    CREATE_LISTING,

    /** Broker-level: cancel any case-3 listing of this group regardless of who created it. */
    MANAGE_ALL_LISTINGS,

    /** Discretionary spend from the group wallet. */
    SPEND_FROM_GROUP_WALLET,

    /** Initiate a withdrawal from the group wallet. */
    WITHDRAW_FROM_GROUP_WALLET,

    /** View the group's wallet balance + ledger. */
    VIEW_GROUP_TRANSACTIONS,

    /** Sub-project E -- register/unregister SL groups this realty group manages land for. */
    REGISTER_SL_GROUP;
}
```

Note the removed value: `MANAGE_OWN_LISTING`. Any test referencing it must be updated. The DB strip happens via `V27` (Task 1).

- [ ] **Step 2: Search for references and update**

Run: `cd backend && grep -rn "MANAGE_OWN_LISTING" src/`
Expected: zero results after this task. If any references remain (e.g., in test code), delete them (the value no longer exists).

- [ ] **Step 3: Write a values-roundtrip sanity test**

`backend/src/test/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermissionTest.java` (create if absent; add this test if file exists):

```java
package com.slparcelauctions.backend.realty.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class RealtyGroupPermissionTest {

    @Test
    void enumContainsExactlyExpectedValuesForE() {
        Set<String> names = Stream.of(RealtyGroupPermission.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "INVITE_AGENTS", "REMOVE_AGENTS", "EDIT_GROUP_PROFILE", "CONFIGURE_FEES",
                "CREATE_LISTING", "MANAGE_ALL_LISTINGS",
                "SPEND_FROM_GROUP_WALLET", "WITHDRAW_FROM_GROUP_WALLET", "VIEW_GROUP_TRANSACTIONS",
                "REGISTER_SL_GROUP");
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupPermissionTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermissionTest.java
git commit -m "feat(realty-slgroup): permission enum -- drop MANAGE_OWN_LISTING, add REGISTER_SL_GROUP"
git push
```

---

## Task 4: `RealtyGroupMember.agent_commission_rate` field

Implements spec §3.2, §9.1.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMember.java`
- Test: extend an existing `RealtyGroupMemberTest` or create a small JPA round-trip test

- [ ] **Step 1: Add the field to the entity**

In `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMember.java`, add (alongside existing fields):

```java
    /**
     * Per-listing agent commission rate snapshotted onto auctions at create-time (case 3).
     * Stored as a fraction ({@code 0.10} = 10%). Leader-edited via the invitation +
     * edit-permissions surface; non-leader members see their own rate read-only. Has no
     * effect on case-1 legacy auctions, which still use the snapshot from the group-level
     * rate/split fields until G removes them.
     */
    @Builder.Default
    @Column(name = "agent_commission_rate", nullable = false, precision = 5, scale = 4)
    private java.math.BigDecimal agentCommissionRate = java.math.BigDecimal.ZERO;
```

- [ ] **Step 2: Add a repo method that surfaces the rate**

In `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java`:

```java
    /**
     * Returns just the commission rate without loading the full entity graph; used at
     * listing-create snapshot time.
     */
    @org.springframework.data.jpa.repository.Query("""
        SELECT m.agentCommissionRate FROM RealtyGroupMember m
         WHERE m.group.id = :groupId AND m.user.id = :userId
        """)
    java.util.Optional<java.math.BigDecimal> findCommissionRate(
            @org.springframework.data.repository.query.Param("groupId") Long groupId,
            @org.springframework.data.repository.query.Param("userId") Long userId);
```

(Adjust field reference if the existing entity calls them differently — check `RealtyGroupMember.group` / `RealtyGroupMember.user` field names before writing the JPQL.)

- [ ] **Step 3: Round-trip test**

`backend/src/test/java/com/slparcelauctions/backend/realty/RealtyGroupMemberCommissionRateTest.java`:

```java
package com.slparcelauctions.backend.realty;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.slparcelauctions.backend.common.JpaConfig;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RealtyGroupMemberCommissionRateTest {

    @Autowired RealtyGroupMemberRepository repo;

    @Test
    void agentCommissionRateDefaultsToZeroAndRoundtrips() {
        // Use whatever test fixture pattern the existing realty tests use (e.g., persisting
        // a user + group via a TestEntityHelper). Assert the new member row has rate=0 by
        // default and that setting + saving a non-zero value reads back identically.
        // The point of this test is the new column round-trips through JPA + DECIMAL(5,4)
        // without truncation.
        BigDecimal explicit = new BigDecimal("0.1234");
        // ... build member + save ...
        // assertThat(loaded.getAgentCommissionRate()).isEqualByComparingTo(explicit);
    }
}
```

(The implementer fills in the fixture using existing helpers in the repo — adapt to how
other realty-group entity tests instantiate `User` + `RealtyGroup` + member rows.)

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupMemberCommissionRateTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMember.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/RealtyGroupMemberCommissionRateTest.java
git commit -m "feat(realty-slgroup): RealtyGroupMember.agentCommissionRate field + repo lookup"
git push
```

---

## Task 5: `Auction.realty_group_sl_group_id` + `agent_commission_rate` fields

Implements spec §3.2, §3.3.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`
- Test: small round-trip test

- [ ] **Step 1: Add fields to the entity**

In `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`, alongside existing `realty_group_id`, `listing_agent_id`, etc.:

```java
    /**
     * Sub-project E case-3 discriminator. NULL for individual listings and for legacy case-1
     * rows. Set at create-time to the verified {@link com.slparcelauctions.backend.realty
     * .slgroup.RealtyGroupSlGroup} row whose SL group UUID matches the parcel's SL owner.
     */
    @Column(name = "realty_group_sl_group_id")
    private Long realtyGroupSlGroupId;

    /**
     * Per-listing commission rate snapshotted from {@code realty_group_members.agent_commission_rate}
     * at listing-create. NULL for non-case-3 auctions. Consumed by {@code AgentCommissionDistributor}
     * at SOLD close.
     */
    @Column(name = "agent_commission_rate", precision = 5, scale = 4)
    private java.math.BigDecimal agentCommissionRate;
```

- [ ] **Step 2: Round-trip test**

`backend/src/test/java/com/slparcelauctions/backend/auction/AuctionCase3ColumnsTest.java`:

```java
package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.slparcelauctions.backend.common.JpaConfig;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class AuctionCase3ColumnsTest {

    @Autowired AuctionRepository repo;

    @Test
    void case3ColumnsAreNullableAndRoundtrip() {
        // Use the existing AuctionTestFixture pattern: build a draft auction with the new
        // case-3 columns set and assert they survive a save/load cycle. The realty_group_sl_group_id
        // FK does not need to exist (use NULL); the point is the columns load.
        // Once a fixture is built, set:
        //   auction.setRealtyGroupSlGroupId(123L);
        //   auction.setAgentCommissionRate(new BigDecimal("0.0750"));
        // Then persist + reload + assert.
    }
}
```

(Adapt the fixture pattern from the existing `AuctionRepositoryTest` or similar tests.)

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=AuctionCase3ColumnsTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/AuctionCase3ColumnsTest.java
git commit -m "feat(realty-slgroup): Auction.realty_group_sl_group_id + agent_commission_rate fields"
git push
```

---

## Task 6: `SlWorldApiClient.fetchGroupPage` + `GroupPageData` DTO

Implements spec §7 (registration entry + founder cross-check).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/GroupPageData.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClient.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/sl/SlWorldApiClientGroupTest.java`

- [ ] **Step 1: DTO**

`backend/src/main/java/com/slparcelauctions/backend/sl/dto/GroupPageData.java`:

```java
package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

/**
 * Typed wrapper for the relevant fields of {@code world.secondlife.com/group/{uuid}} that
 * realty-group SL-group verification cares about. {@code aboutText} is the full About /
 * Charter text the leader edits. {@code founderUuid} is the SL avatar UUID of the founder
 * (matched against the founder-terminal callback). {@code name} is the SL group's display
 * name (recorded onto the registration row for UI labels).
 */
public record GroupPageData(
        UUID slGroupUuid,
        String name,
        String aboutText,
        UUID founderUuid
) {}
```

- [ ] **Step 2: Extend `SlWorldApiClient`**

In `backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClient.java`, add a sibling to `fetchParcelPage`:

```java
    /**
     * Fetches and parses {@code world.secondlife.com/group/{uuid}}. Returns the SL group's
     * display name, About text, and founder UUID. Errors propagate via the existing reactive
     * error handling pattern used by {@link #fetchParcelPage}; callers should treat
     * {@code Mono.empty()} and exceptions both as "World API lookup failed."
     */
    public reactor.core.publisher.Mono<com.slparcelauctions.backend.sl.dto.GroupPageData>
            fetchGroupPage(java.util.UUID slGroupUuid) {
        String path = "/group/" + slGroupUuid;
        return webClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String.class)
                .map(html -> parseGroupPage(slGroupUuid, html));
    }

    /**
     * Parses the World API group page HTML. The page is a small SEO-friendly page with
     * a structured info block. Fields are extracted with regex against the rendered HTML;
     * if any field is missing the corresponding record field is {@code null} (caller decides
     * whether that's fatal).
     */
    private com.slparcelauctions.backend.sl.dto.GroupPageData parseGroupPage(
            java.util.UUID slGroupUuid, String html) {
        String name = extractGroupName(html);
        String about = extractGroupAbout(html);
        java.util.UUID founder = extractFounderUuid(html);
        return new com.slparcelauctions.backend.sl.dto.GroupPageData(
                slGroupUuid, name, about, founder);
    }

    private static final java.util.regex.Pattern GROUP_NAME_PATTERN =
            java.util.regex.Pattern.compile(
                    "<div[^>]*class=\"[^\"]*\\bgroupname\\b[^\"]*\"[^>]*>([^<]+)</div>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern GROUP_ABOUT_PATTERN =
            java.util.regex.Pattern.compile(
                    "<div[^>]*class=\"[^\"]*\\bgroupcharter\\b[^\"]*\"[^>]*>(.*?)</div>",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern FOUNDER_UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "founder[^a-z0-9]*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private String extractGroupName(String html) {
        var m = GROUP_NAME_PATTERN.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractGroupAbout(String html) {
        var m = GROUP_ABOUT_PATTERN.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private java.util.UUID extractFounderUuid(String html) {
        var m = FOUNDER_UUID_PATTERN.matcher(html);
        return m.find() ? java.util.UUID.fromString(m.group(1)) : null;
    }
```

(The implementer should validate the regex patterns against an actual `world.secondlife.com/group/{uuid}` HTML response during integration testing. If the patterns don't match the real shape, tighten or replace them with a proper HTML parser. The class-level pattern constants make this trivially adjustable.)

- [ ] **Step 3: Unit test**

`backend/src/test/java/com/slparcelauctions/backend/sl/SlWorldApiClientGroupTest.java`:

```java
package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class SlWorldApiClientGroupTest {

    private final SlWorldApiClient client = new SlWorldApiClient(/* webClient mock */ null);

    @Test
    void parseGroupPage_extractsAllFields() {
        // Use reflection to invoke parseGroupPage with a representative HTML fixture, or
        // expose a package-private static parse helper. The fixture should contain a div
        // with class "groupname", a div with class "groupcharter", and a "founder" anchor
        // with a UUID match the regex.
        String html = """
                <div class="groupname">Sunset Realty</div>
                <div class="groupcharter">SLPA-ABC123XYZ987 Welcome!</div>
                <a href="/resident/00112233-4455-6677-8899-aabbccddeeff">founder Alice Resident</a>
                """;
        // Adapt: invoke the parser. Assert name, about, founderUuid.
    }
}
```

(The implementer adapts the test to match whatever WebClient mocking pattern the existing `fetchParcelPage` tests use.)

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=SlWorldApiClientGroupTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClient.java \
        backend/src/main/java/com/slparcelauctions/backend/sl/dto/GroupPageData.java \
        backend/src/test/java/com/slparcelauctions/backend/sl/SlWorldApiClientGroupTest.java
git commit -m "feat(realty-slgroup): SlWorldApiClient.fetchGroupPage + GroupPageData"
git push
```

---

## Task 7: Exception classes for E

Implements spec §5.5.

**Files (all under `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/exception/` except where noted):**
- Create: `SlGroupAlreadyRegisteredException.java`
- Create: `SlGroupNotVerifiedException.java`
- Create: `SlGroupVerificationExpiredException.java`
- Create: `SlGroupFounderMismatchException.java`
- Create: `ParcelNotOwnedByRegisteredSlGroupException.java`
- Create: `RegisteredSlGroupHasListingsException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/SlGroupRegisteredBlocksDissolveException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/BrokerCancelNotApplicableException.java`

- [ ] **Step 1: Write each exception**

Pattern: `RuntimeException` with a constructor that builds the message + (where useful) a `code` constant matching the spec's table. Example template:

```java
package com.slparcelauctions.backend.realty.slgroup.exception;

import java.util.UUID;

public class SlGroupAlreadyRegisteredException extends RuntimeException {
    public static final String CODE = "SL_GROUP_ALREADY_REGISTERED";

    private final UUID slGroupUuid;

    public SlGroupAlreadyRegisteredException(UUID slGroupUuid) {
        super("SL group " + slGroupUuid + " is already registered to a realty group.");
        this.slGroupUuid = slGroupUuid;
    }

    public UUID getSlGroupUuid() { return slGroupUuid; }
}
```

Apply the same pattern for:

- `SlGroupNotVerifiedException` (code `SL_GROUP_NOT_VERIFIED`, carries `publicId`)
- `SlGroupVerificationExpiredException` (code `SL_GROUP_VERIFICATION_EXPIRED`, carries `publicId`)
- `SlGroupFounderMismatchException` (code `SL_GROUP_FOUNDER_MISMATCH`, carries reported avatar UUID + expected)
- `ParcelNotOwnedByRegisteredSlGroupException` (code `PARCEL_NOT_OWNED_BY_REGISTERED_SL_GROUP`, carries parcel UUID + realty group publicId)
- `RegisteredSlGroupHasListingsException` (code `REGISTERED_SL_GROUP_HAS_LISTINGS`, carries SL group publicId + active count)
- `SlGroupRegisteredBlocksDissolveException` (code `SL_GROUPS_BLOCK_DISSOLVE`, carries realty group publicId + count)
- `BrokerCancelNotApplicableException` (code `BROKER_CANCEL_NOT_APPLICABLE`, carries auction publicId + reason)

- [ ] **Step 2: Smoke test**

`backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/exception/ExceptionSmokeTest.java`:

```java
package com.slparcelauctions.backend.realty.slgroup.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExceptionSmokeTest {

    @Test
    void exceptionsCarryStableCodes() {
        assertThat(SlGroupAlreadyRegisteredException.CODE).isEqualTo("SL_GROUP_ALREADY_REGISTERED");
        assertThat(SlGroupNotVerifiedException.CODE).isEqualTo("SL_GROUP_NOT_VERIFIED");
        assertThat(SlGroupVerificationExpiredException.CODE).isEqualTo("SL_GROUP_VERIFICATION_EXPIRED");
        assertThat(SlGroupFounderMismatchException.CODE).isEqualTo("SL_GROUP_FOUNDER_MISMATCH");
        assertThat(ParcelNotOwnedByRegisteredSlGroupException.CODE)
                .isEqualTo("PARCEL_NOT_OWNED_BY_REGISTERED_SL_GROUP");
        assertThat(RegisteredSlGroupHasListingsException.CODE)
                .isEqualTo("REGISTERED_SL_GROUP_HAS_LISTINGS");
    }

    @Test
    void carryUuidContext() {
        UUID id = UUID.randomUUID();
        SlGroupAlreadyRegisteredException ex = new SlGroupAlreadyRegisteredException(id);
        assertThat(ex.getSlGroupUuid()).isEqualTo(id);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=ExceptionSmokeTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/exception/ \
        backend/src/main/java/com/slparcelauctions/backend/realty/exception/SlGroupRegisteredBlocksDissolveException.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/exception/BrokerCancelNotApplicableException.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/exception/ExceptionSmokeTest.java
git commit -m "feat(realty-slgroup): exception classes for E"
git push
```

---

## Task 8: `SlGroupVerificationCodeGenerator` helper

Implements spec §7.1 (code format: `SLPA-` + 12 base32 chars, no `0/O/1/l`).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerificationCodeGenerator.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerificationCodeGeneratorTest.java`

- [ ] **Step 1: Implementation**

```java
package com.slparcelauctions.backend.realty.slgroup;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Produces SL-group verification codes of the form {@code SLPA-XXXXXXXXXXXX} using a
 * 12-character alphabet that excludes visually ambiguous characters ({@code 0, O, 1, l}).
 * Codes are unique within the pool of pending {@link RealtyGroupSlGroup} rows by virtue
 * of the calling service retrying on collision, but the alphabet + length give a search
 * space of 28^12 = ~2.4e17, so collisions are vanishingly rare in practice.
 */
@Component
public class SlGroupVerificationCodeGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";    // 31 chars: 0-9 minus 0/1, A-Z minus O/L/I
    private static final int CODE_LEN = 12;
    private static final String PREFIX = "SLPA-";

    private final SecureRandom random;

    public SlGroupVerificationCodeGenerator() {
        this(new SecureRandom());
    }

    SlGroupVerificationCodeGenerator(SecureRandom random) {
        this.random = random;
    }

    public String generate() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + CODE_LEN);
        sb.append(PREFIX);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
```

Note: the alphabet string omits the visually ambiguous characters per spec; the comment above is informational.

- [ ] **Step 2: Test**

```java
package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

class SlGroupVerificationCodeGeneratorTest {

    private final SlGroupVerificationCodeGenerator gen =
            new SlGroupVerificationCodeGenerator(new SecureRandom());

    @Test
    void generatedCodeHasExpectedShape() {
        String code = gen.generate();
        assertThat(code).startsWith("SLPA-");
        assertThat(code).hasSize(5 + 12);
        assertThat(code.substring(5)).matches("[2-9A-HJ-NP-Z]{12}");
    }

    @Test
    void generatesUniqueCodesAcrossMultipleCalls() {
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(gen.generate());
        }
        // 100 from a 28^12 space — collision probability is ~negligible
        assertThat(codes).hasSize(100);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=SlGroupVerificationCodeGeneratorTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerificationCodeGenerator.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerificationCodeGeneratorTest.java
git commit -m "feat(realty-slgroup): verification code generator (SLPA-XXXXXXXXXXXX)"
git push
```

---

## Task 9: `RealtyGroupSlGroupService.register(...)` + DTO + mapper

Implements spec §5.1 (register endpoint), §7.1 (registration entry).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/dto/RegisterSlGroupRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/dto/RealtyGroupSlGroupDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/dto/SlGroupPendingDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/dto/RealtyGroupSlGroupDtoMapper.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupServiceRegisterTest.java`

- [ ] **Step 1: DTOs + mapper**

`RegisterSlGroupRequest.java`:

```java
package com.slparcelauctions.backend.realty.slgroup.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record RegisterSlGroupRequest(@NotNull UUID slGroupUuid) {}
```

`RealtyGroupSlGroupDto.java`:

```java
package com.slparcelauctions.backend.realty.slgroup.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.realty.slgroup.SlGroupVerifyMethod;

public record RealtyGroupSlGroupDto(
        UUID publicId,
        UUID slGroupUuid,
        String slGroupName,
        boolean verified,
        OffsetDateTime verifiedAt,
        SlGroupVerifyMethod verifiedVia,
        SlGroupPendingDto pending,
        UUID founderAvatarUuid
) {}
```

`SlGroupPendingDto.java`:

```java
package com.slparcelauctions.backend.realty.slgroup.dto;

import java.time.OffsetDateTime;

public record SlGroupPendingDto(
        String verificationCode,
        OffsetDateTime verificationCodeExpiresAt,
        OffsetDateTime lastPolledAt,
        int pollAttempts
) {}
```

`RealtyGroupSlGroupDtoMapper.java`:

```java
package com.slparcelauctions.backend.realty.slgroup.dto;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;

@Component
public class RealtyGroupSlGroupDtoMapper {

    public RealtyGroupSlGroupDto toDto(RealtyGroupSlGroup row) {
        SlGroupPendingDto pending = row.isVerified() ? null : new SlGroupPendingDto(
                row.getVerificationCode(),
                row.getVerificationCodeExpiresAt(),
                row.getLastPolledAt(),
                row.getPollAttempts());
        return new RealtyGroupSlGroupDto(
                row.getPublicId(),
                row.getSlGroupUuid(),
                row.getSlGroupName(),
                row.isVerified(),
                row.getVerifiedAt(),
                row.getVerifiedVia(),
                pending,
                row.getFounderAvatarUuid());
    }
}
```

- [ ] **Step 2: Service `register(...)` method**

`RealtyGroupSlGroupService.java`:

```java
package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupAlreadyRegisteredException;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GroupPageData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the lifecycle of {@link RealtyGroupSlGroup} rows. Sub-project E spec §7.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupSlGroupService {

    static final Duration VERIFICATION_TTL = Duration.ofDays(7);

    private final RealtyGroupSlGroupRepository repo;
    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupAuthorizer authorizer;
    private final SlWorldApiClient worldApi;
    private final SlGroupVerificationCodeGenerator codeGen;
    private final Clock clock;

    @Transactional
    public RealtyGroupSlGroup register(Long callerUserId, UUID realtyGroupPublicId, UUID slGroupUuid) {
        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(realtyGroupPublicId));
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.REGISTER_SL_GROUP);

        repo.findBySlGroupUuid(slGroupUuid).ifPresent(existing -> {
            throw new SlGroupAlreadyRegisteredException(slGroupUuid);
        });

        // Fetch the SL group page to capture sl_group_name. World API errors propagate so the
        // controller can render a 422 with diagnostic; we don't squat on a UUID we couldn't
        // confirm existed in SL.
        GroupPageData page = worldApi.fetchGroupPage(slGroupUuid).block();
        String slGroupName = page == null ? null : page.name();

        OffsetDateTime now = OffsetDateTime.now(clock);
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
                .realtyGroupId(group.getId())
                .slGroupUuid(slGroupUuid)
                .slGroupName(slGroupName)
                .verified(false)
                .verificationCode(codeGen.generate())
                .verificationCodeExpiresAt(now.plus(VERIFICATION_TTL))
                .pollAttempts(0)
                .build();
        RealtyGroupSlGroup saved = repo.save(row);
        log.info("SL group registered (pending): realtyGroupId={} slGroupUuid={} code={}",
                group.getId(), slGroupUuid, saved.getVerificationCode());
        return saved;
    }
}
```

- [ ] **Step 3: Test**

```java
package com.slparcelauctions.backend.realty.slgroup;

// imports
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
// ...

@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class RealtyGroupSlGroupServiceRegisterTest {

    @org.mockito.Mock RealtyGroupSlGroupRepository repo;
    @org.mockito.Mock com.slparcelauctions.backend.realty.RealtyGroupRepository groupRepo;
    @org.mockito.Mock com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer authorizer;
    @org.mockito.Mock com.slparcelauctions.backend.sl.SlWorldApiClient worldApi;
    @org.mockito.Mock SlGroupVerificationCodeGenerator codeGen;

    private final java.time.Clock clock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-05-12T12:00:00Z"), java.time.ZoneOffset.UTC);

    @org.junit.jupiter.api.Test
    void register_happyPath_persistsPendingRow() {
        Long callerId = 7L;
        java.util.UUID groupPublic = java.util.UUID.randomUUID();
        java.util.UUID slGroupUuid = java.util.UUID.randomUUID();
        com.slparcelauctions.backend.realty.RealtyGroup group =
                com.slparcelauctions.backend.realty.RealtyGroup.builder().build();
        // Reflectively set group.id = 42 (BaseEntity has @Setter(NONE)):
        try {
            var f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(group, 42L);
        } catch (Exception e) { throw new RuntimeException(e); }

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic)).thenReturn(java.util.Optional.of(group));
        when(repo.findBySlGroupUuid(slGroupUuid)).thenReturn(java.util.Optional.empty());
        when(worldApi.fetchGroupPage(slGroupUuid)).thenReturn(reactor.core.publisher.Mono.just(
                new com.slparcelauctions.backend.sl.dto.GroupPageData(slGroupUuid, "Sunset Realty", "About text", java.util.UUID.randomUUID())));
        when(codeGen.generate()).thenReturn("SLPA-ABCDEFGHJKMN");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSlGroupService svc = new RealtyGroupSlGroupService(
                repo, groupRepo, authorizer, worldApi, codeGen, clock);

        RealtyGroupSlGroup result = svc.register(callerId, groupPublic, slGroupUuid);

        verify(authorizer).assertCan(callerId, 42L,
                com.slparcelauctions.backend.realty.permission.RealtyGroupPermission.REGISTER_SL_GROUP);
        assertThat(result.getVerificationCode()).isEqualTo("SLPA-ABCDEFGHJKMN");
        assertThat(result.getSlGroupName()).isEqualTo("Sunset Realty");
        assertThat(result.isVerified()).isFalse();
    }

    @org.junit.jupiter.api.Test
    void register_alreadyRegistered_throws() {
        java.util.UUID groupPublic = java.util.UUID.randomUUID();
        java.util.UUID slGroupUuid = java.util.UUID.randomUUID();
        com.slparcelauctions.backend.realty.RealtyGroup group =
                com.slparcelauctions.backend.realty.RealtyGroup.builder().build();
        try {
            var f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(group, 42L);
        } catch (Exception e) { throw new RuntimeException(e); }

        when(groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublic)).thenReturn(java.util.Optional.of(group));
        when(repo.findBySlGroupUuid(slGroupUuid))
                .thenReturn(java.util.Optional.of(RealtyGroupSlGroup.builder().build()));

        RealtyGroupSlGroupService svc = new RealtyGroupSlGroupService(
                repo, groupRepo, authorizer, worldApi, codeGen, clock);

        assertThatThrownBy(() -> svc.register(7L, groupPublic, slGroupUuid))
                .isInstanceOf(com.slparcelauctions.backend.realty.slgroup.exception.SlGroupAlreadyRegisteredException.class);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupSlGroupServiceRegisterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/dto/ \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupServiceRegisterTest.java
git commit -m "feat(realty-slgroup): RealtyGroupSlGroupService.register + DTOs + mapper"
git push
```

---

## Task 10: Service `getByGroup`, `unregister`, `recheck` primitives

Implements spec §5.1 (GET, DELETE, recheck), §12.2 (unregister gate).

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java` (add 3 methods)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java` (add `existsCase3ForSlGroup`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupServiceListDeleteRecheckTest.java`

- [ ] **Step 1: Add `existsCase3ForSlGroup` query**

In `AuctionRepository.java`:

```java
    @org.springframework.data.jpa.repository.Query("""
        SELECT (COUNT(a) > 0) FROM Auction a
         WHERE a.realtyGroupSlGroupId = :slGroupId
           AND a.status NOT IN ('COMPLETED','CANCELLED','EXPIRED','DISPUTED','SUSPENDED')
        """)
    boolean existsCase3ForSlGroup(
            @org.springframework.data.repository.query.Param("slGroupId") Long slGroupId);
```

- [ ] **Step 2: Add the three service methods**

Inside `RealtyGroupSlGroupService` (alongside `register`):

```java
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<RealtyGroupSlGroup> listForGroup(Long callerUserId, java.util.UUID realtyGroupPublicId) {
        com.slparcelauctions.backend.realty.RealtyGroup group = groupRepo
                .findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException(realtyGroupPublicId));
        // Members can view; non-members 403.
        if (!authorizer.isMember(callerUserId, group.getId())) {
            throw new com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException("Not a member");
        }
        return repo.findByRealtyGroupIdOrderByCreatedAtDesc(group.getId());
    }

    @org.springframework.transaction.annotation.Transactional
    public void unregister(Long callerUserId, java.util.UUID realtyGroupPublicId, java.util.UUID slGroupPublicId) {
        com.slparcelauctions.backend.realty.RealtyGroup group = groupRepo
                .findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException(realtyGroupPublicId));
        authorizer.assertCan(callerUserId, group.getId(),
                com.slparcelauctions.backend.realty.permission.RealtyGroupPermission.REGISTER_SL_GROUP);

        RealtyGroupSlGroup row = repo.findByPublicId(slGroupPublicId)
                .orElseThrow(() -> new com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException(slGroupPublicId));

        if (!row.getRealtyGroupId().equals(group.getId())) {
            // Don't leak existence of an SL group registered to a different realty group.
            throw new com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException(slGroupPublicId);
        }

        if (auctionRepo.existsCase3ForSlGroup(row.getId())) {
            throw new com.slparcelauctions.backend.realty.slgroup.exception.RegisteredSlGroupHasListingsException(
                    slGroupPublicId);
        }
        repo.delete(row);
    }

    @org.springframework.transaction.annotation.Transactional
    public RealtyGroupSlGroup recheck(Long callerUserId, java.util.UUID realtyGroupPublicId, java.util.UUID slGroupPublicId) {
        com.slparcelauctions.backend.realty.RealtyGroup group = groupRepo
                .findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException(realtyGroupPublicId));
        authorizer.assertCan(callerUserId, group.getId(),
                com.slparcelauctions.backend.realty.permission.RealtyGroupPermission.REGISTER_SL_GROUP);
        RealtyGroupSlGroup row = repo.findByPublicId(slGroupPublicId)
                .orElseThrow(() -> new com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException(slGroupPublicId));
        if (!row.getRealtyGroupId().equals(group.getId()) || row.isVerified()) {
            // already verified or wrong realty group — caller treats as no-op success
            return row;
        }
        // Delegate to the about-text poller's per-row method (introduced in Task 11) so that
        // there's only one place that knows how to scan the page for the code. For now, inline
        // an empty implementation; Task 11 wires the actual poll.
        // (Task 11 will replace this stub.)
        return row;
    }
```

Plus inject `AuctionRepository` (constructor field):

```java
    private final com.slparcelauctions.backend.auction.AuctionRepository auctionRepo;
```

(Add it to the field declarations and the `@RequiredArgsConstructor` will wire it via Lombok.)

- [ ] **Step 3: Tests**

`RealtyGroupSlGroupServiceListDeleteRecheckTest.java`:

```java
// Tests:
//   listForGroup_returnsRowsForMember
//   listForGroup_non-member_403s
//   unregister_blocksWhenActiveListingsExist
//   unregister_happyPath_deletesRow
//   unregister_otherGroupsRow_404s
//   recheck_alreadyVerified_isNoop
```

(Implementer writes each per the existing service-test pattern with Mockito mocks.)

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupSlGroupServiceListDeleteRecheckTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupServiceListDeleteRecheckTest.java
git commit -m "feat(realty-slgroup): service list/unregister/recheck + AuctionRepository.existsCase3ForSlGroup"
git push
```

---

## End of Part 1

Part 2 (Tasks 11–20): verification flows, controllers, listing case-3 flow, agent commission distributor, escrow case-3 routing, Method C check.
