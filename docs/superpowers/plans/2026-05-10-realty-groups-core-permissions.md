# Realty Groups Core + Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship sub-projects A (Realty Group core) + B (Permissions model) per the spec at `docs/superpowers/specs/2026-05-10-realty-groups-core-permissions-design.md`. Bundles a central `ImageStorageService` helper that converts raster uploads to WebP at the chokepoint, migrating existing avatar/default-cover/dispute upload paths to use it.

**Architecture:** New vertical-slice domain at `backend/.../realty/` with three `BaseMutableEntity` entities, a `RealtyGroupAuthorizer`, a `RealtyGroupService`, four REST controllers (public, member-self-service, user-invitations, admin), and an invitation expiry job. Frontend ships `/group/[slug]` public page, `/dashboard/groups/*` member surface, and `/admin/realty-groups/*`. A separate cross-cutting phase introduces `ImageStorageService` and migrates existing upload sites.

**Tech Stack:** Spring Boot 4 / Java 26 / Lombok / Flyway / PostgreSQL (CITEXT, partial unique indexes, TEXT[] arrays). Frontend: Next.js 16 / TanStack Query / React Hook Form + Zod / Tailwind. Notifications via existing `NotificationPublisher`. WebP encoding via a JVM-compatible library (evaluated in Task 23).

**Spec:** [2026-05-10-realty-groups-core-permissions-design.md](../specs/2026-05-10-realty-groups-core-permissions-design.md)

---

## Conventions

- **Branch:** `feat/realty-groups-core-permissions` (already created).
- **Commits:** Conventional commits. No AI attribution per memory.
- **Per task:** TDD where applicable — write failing test, verify failure, implement, verify pass, commit. For entity-only / scaffolding tasks where there's no behavior, "compile + Spring context loads" replaces the failing-test step.
- **PR:** One PR for the whole slice, opened against `dev` at the end. Claude does not merge dev → main; that's the user's call.
- **README:** swept at the final task.
- **Postman:** updated at the final tasks before PR.

---

## File structure

### Backend — new files

```
backend/src/main/java/com/slparcelauctions/backend/realty/
├── RealtyGroup.java                                   # entity
├── RealtyGroupRepository.java
├── RealtyGroupMember.java                             # entity
├── RealtyGroupMemberRepository.java
├── RealtyGroupInvitation.java                         # entity
├── RealtyGroupInvitationRepository.java
├── RealtyGroupRole.java                               # enum LEADER | AGENT (computed)
├── InvitationStatus.java                              # enum PENDING | ACCEPTED | DECLINED | REVOKED | EXPIRED
├── OldLeaderAction.java                               # enum STAY | LEAVE
├── permission/
│   └── RealtyGroupPermission.java                     # enum INVITE_AGENTS | REMOVE_AGENTS | EDIT_GROUP_PROFILE | CONFIGURE_FEES
├── auth/
│   └── RealtyGroupAuthorizer.java
├── slug/
│   └── RealtyGroupSlugFactory.java
├── service/
│   ├── RealtyGroupService.java                        # CRUD + permission edits
│   ├── RealtyGroupMembershipService.java              # leave / remove / transfer leadership
│   ├── RealtyGroupInvitationService.java              # invite / accept / decline / revoke / expiry
│   └── RealtyGroupExpiryJob.java                      # @Scheduled
├── controller/
│   ├── RealtyGroupController.java                     # POST/PATCH/DELETE under /api/v1/realty-groups
│   ├── RealtyGroupPublicController.java               # public reads
│   ├── RealtyGroupInvitationController.java           # group-scoped invitations
│   ├── MeRealtyGroupController.java                   # /me/invitations + /me/realty-groups
│   └── AdminRealtyGroupController.java                # /admin/realty-groups
├── dto/                                               # records
│   ├── CreateRealtyGroupRequest.java
│   ├── UpdateRealtyGroupRequest.java
│   ├── RealtyGroupPublicDto.java
│   ├── RealtyGroupMemberDto.java
│   ├── CreateInvitationRequest.java
│   ├── InvitationDto.java
│   ├── TransferLeadershipRequest.java
│   ├── UpdatePermissionsRequest.java
│   ├── RealtyGroupRowDto.java                         # admin list row
│   └── UserRealtyGroupAffiliationDto.java             # user-profile groups section
└── exception/
    ├── RealtyGroupNotFoundException.java
    ├── RealtyGroupNameTakenException.java
    ├── RealtyGroupRenameCooldownException.java
    ├── MemberSeatLimitReachedException.java
    ├── InvitationAlreadyPendingException.java
    ├── InvitationExpiredException.java
    ├── InvitationNotFoundException.java
    ├── LeaderCannotLeaveException.java
    ├── CannotRemoveLeaderException.java
    ├── LeaderTransferTargetNotMemberException.java
    ├── RealtyGroupPermissionDeniedException.java
    ├── GroupDissolvedException.java
    ├── InvalidWebsiteUrlException.java
    ├── AlreadyMemberException.java
    └── RealtyExceptionHandler.java                    # @RestControllerAdvice

backend/src/main/java/com/slparcelauctions/backend/storage/
├── ImageStorageService.java                           # NEW interface
├── ImageStorageServiceImpl.java                       # NEW (calls ObjectStorageService.put after raster→WebP)
├── ImageStorageContext.java                           # record (purpose, maxDim, callerKey)
├── StoredImage.java                                   # record (objectKey, contentType, sizeBytes)
└── ImagePurpose.java                                  # enum AVATAR | LOGO | COVER | LISTING_PHOTO | DEFAULT_COVER | DISPUTE_EVIDENCE

backend/src/main/resources/db/migration/
└── V24__realty_groups.sql

backend/src/main/java/com/slparcelauctions/backend/admin/
└── AdminActionType.java                               # MODIFY — add 3 new values
```

### Backend — modified files

```
backend/pom.xml                                       # add WebP encoder dep (Task 23 picks the library)
backend/src/main/java/com/slparcelauctions/backend/user/AvatarService.java                # use ImageStorageService
backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverService.java      # use ImageStorageService
backend/src/main/java/com/slparcelauctions/backend/escrow/dispute/DisputeEvidenceUploadService.java  # use ImageStorageService
backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto*.java             # listing photos (path TBD by audit)
backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java # add 9 methods
backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java                   # FK realized in migration; no Java change
backend/src/main/java/com/slparcelauctions/backend/user/UserController.java OR new endpoint  # GET /users/{publicId}/realty-groups
backend/src/main/java/com/slparcelauctions/backend/admin/AdminActionTypeCheckConstraintInitializer.java  # add new values
```

### Frontend — new files

```
frontend/src/app/group/[slug]/page.tsx
frontend/src/app/dashboard/groups/page.tsx
frontend/src/app/dashboard/groups/create/page.tsx
frontend/src/app/dashboard/groups/[slug]/manage/page.tsx
frontend/src/app/dashboard/invitations/page.tsx
frontend/src/app/admin/realty-groups/page.tsx
frontend/src/app/admin/realty-groups/[publicId]/page.tsx

frontend/src/components/realty/
├── RealtyGroupHeroBanner.tsx
├── LeaderCard.tsx
├── RealtyGroupAgentsGrid.tsx
├── RealtyGroupMemberCard.tsx
├── GroupCreateForm.tsx
├── GroupProfileForm.tsx
├── MembersTab.tsx
├── InvitationsTab.tsx
├── SettingsTab.tsx
├── InvitationsList.tsx
├── PermissionToggleRow.tsx
├── GroupChip.tsx
└── GroupBadge.tsx

frontend/src/hooks/realty/useRealtyGroups.ts
frontend/src/lib/api/realtyGroups.ts
frontend/src/lib/realty/permissions.ts                # client-side enum mirror
frontend/src/lib/realty/errorMessages.ts              # realtyGroupErrorMessage()
frontend/src/types/realty.ts
```

### Frontend — modified files

```
frontend/src/components/user/PublicProfileView.tsx OR similar  # add "Groups" section (path confirmed in Task 36)
frontend/src/components/layout/AdminNav.tsx                    # add "Realty Groups" entry
```

### Docs

```
README.md                                             # sweep at task end (section: Realty Groups)
```

---

## Phase 1 — Flyway migration + entities

### Task 1: Flyway V24 migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__realty_groups.sql`

- [ ] **Step 1: Write migration SQL** (full content per spec §13.1; reproduced verbatim here)

```sql
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE realty_groups (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name VARCHAR(64) NOT NULL,
    name_lower CITEXT GENERATED ALWAYS AS (lower(name)) STORED,
    slug VARCHAR(80) NOT NULL,
    leader_id BIGINT NOT NULL REFERENCES users(id),
    logo_object_key VARCHAR(500),
    logo_content_type VARCHAR(100),
    logo_size_bytes BIGINT,
    cover_object_key VARCHAR(500),
    cover_content_type VARCHAR(100),
    cover_size_bytes BIGINT,
    description TEXT,
    website TEXT,
    agent_fee_rate NUMERIC(5,4) NOT NULL DEFAULT 0.0000,
    agent_fee_split NUMERIC(5,4) NOT NULL DEFAULT 0.5000,
    member_seat_limit INTEGER NOT NULL DEFAULT 50,
    last_renamed_at TIMESTAMPTZ,
    dissolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ix_realty_groups_name_lower_active
  ON realty_groups (name_lower) WHERE dissolved_at IS NULL;
CREATE UNIQUE INDEX ix_realty_groups_slug_active
  ON realty_groups (slug) WHERE dissolved_at IS NULL;
CREATE INDEX ix_realty_groups_leader ON realty_groups (leader_id);

CREATE TABLE realty_group_members (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    group_id BIGINT NOT NULL REFERENCES realty_groups(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    permissions TEXT[] NOT NULL DEFAULT '{}',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (group_id, user_id)
);
CREATE INDEX ix_realty_group_members_user ON realty_group_members (user_id);

CREATE TABLE realty_group_invitations (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    group_id BIGINT NOT NULL REFERENCES realty_groups(id) ON DELETE CASCADE,
    invited_user_id BIGINT NOT NULL REFERENCES users(id),
    invited_by_id BIGINT NOT NULL REFERENCES users(id),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','DECLINED','REVOKED','EXPIRED')),
    permissions TEXT[] NOT NULL DEFAULT '{}',
    expires_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ix_invitations_one_live_per_pair
  ON realty_group_invitations (group_id, invited_user_id) WHERE status = 'PENDING';
CREATE INDEX ix_invitations_invitee_status
  ON realty_group_invitations (invited_user_id, status);

ALTER TABLE auctions
  ADD CONSTRAINT fk_auctions_realty_group
  FOREIGN KEY (realty_group_id) REFERENCES realty_groups(id) ON DELETE SET NULL;
```

- [ ] **Step 2: Validate migration locally**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
Expected: backend boots, Flyway runs V24, no errors. Press Ctrl+C.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V24__realty_groups.sql
git commit -m "feat(realty): V24 migration — realty_groups, members, invitations + auctions FK"
```

---

### Task 2: `RealtyGroupPermission` enum + `InvitationStatus` + `RealtyGroupRole` + `OldLeaderAction`

**Files:**
- Create: `backend/.../realty/permission/RealtyGroupPermission.java`
- Create: `backend/.../realty/InvitationStatus.java`
- Create: `backend/.../realty/RealtyGroupRole.java`
- Create: `backend/.../realty/OldLeaderAction.java`

- [ ] **Step 1: Implement enums**

```java
// RealtyGroupPermission.java
package com.slparcelauctions.backend.realty.permission;
public enum RealtyGroupPermission {
    INVITE_AGENTS, REMOVE_AGENTS, EDIT_GROUP_PROFILE, CONFIGURE_FEES;
}
```
```java
// InvitationStatus.java
package com.slparcelauctions.backend.realty;
public enum InvitationStatus { PENDING, ACCEPTED, DECLINED, REVOKED, EXPIRED; }
```
```java
// RealtyGroupRole.java
package com.slparcelauctions.backend.realty;
public enum RealtyGroupRole { LEADER, AGENT; }
```
```java
// OldLeaderAction.java
package com.slparcelauctions.backend.realty;
public enum OldLeaderAction { STAY, LEAVE; }
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/
git commit -m "feat(realty): permission + status + role enums"
```

---

### Task 3: `RealtyGroup` entity + repository

**Files:**
- Create: `backend/.../realty/RealtyGroup.java`
- Create: `backend/.../realty/RealtyGroupRepository.java`

- [ ] **Step 1: Implement `RealtyGroup` entity**

```java
package com.slparcelauctions.backend.realty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "realty_groups")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@SuperBuilder
public class RealtyGroup extends BaseMutableEntity {

    @Column(nullable = false, length = 64)
    private String name;

    // name_lower is a generated column; mark as insertable/updatable=false
    @Column(name = "name_lower", insertable = false, updatable = false)
    private String nameLower;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(name = "leader_id", nullable = false)
    private Long leaderId;

    @Column(name = "logo_object_key", length = 500)
    private String logoObjectKey;

    @Column(name = "logo_content_type", length = 100)
    private String logoContentType;

    @Column(name = "logo_size_bytes")
    private Long logoSizeBytes;

    @Column(name = "cover_object_key", length = 500)
    private String coverObjectKey;

    @Column(name = "cover_content_type", length = 100)
    private String coverContentType;

    @Column(name = "cover_size_bytes")
    private Long coverSizeBytes;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String website;

    @Builder.Default
    @Column(name = "agent_fee_rate", precision = 5, scale = 4, nullable = false)
    private BigDecimal agentFeeRate = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "agent_fee_split", precision = 5, scale = 4, nullable = false)
    private BigDecimal agentFeeSplit = new BigDecimal("0.5000");

    @Builder.Default
    @Column(name = "member_seat_limit", nullable = false)
    private Integer memberSeatLimit = 50;

    @Column(name = "last_renamed_at")
    private OffsetDateTime lastRenamedAt;

    @Column(name = "dissolved_at")
    private OffsetDateTime dissolvedAt;

    public boolean isDissolved() { return dissolvedAt != null; }
}
```

- [ ] **Step 2: Implement repository**

```java
package com.slparcelauctions.backend.realty;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RealtyGroupRepository extends JpaRepository<RealtyGroup, Long> {
    Optional<RealtyGroup> findByPublicId(UUID publicId);
    Optional<RealtyGroup> findBySlugAndDissolvedAtIsNull(String slug);

    @Query("SELECT g FROM RealtyGroup g WHERE LOWER(g.name) = LOWER(?1) AND g.dissolvedAt IS NULL")
    Optional<RealtyGroup> findByNameIgnoreCaseActive(String name);

    @Query("SELECT COUNT(g) FROM RealtyGroup g WHERE g.slug = ?1 AND g.dissolvedAt IS NULL AND g.id <> ?2")
    long countOtherActiveBySlug(String slug, Long excludeId);

    @Query("SELECT COUNT(g) FROM RealtyGroup g WHERE g.slug = ?1 AND g.dissolvedAt IS NULL")
    long countActiveBySlug(String slug);
}
```

- [ ] **Step 3: Compile + Spring context check**

```bash
cd backend && ./mvnw compile -q
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupRepository.java
git commit -m "feat(realty): RealtyGroup entity + repository"
```

---

### Task 4: `RealtyGroupMember` entity + repository

**Files:**
- Create: `backend/.../realty/RealtyGroupMember.java`
- Create: `backend/.../realty/RealtyGroupMemberRepository.java`

- [ ] **Step 1: Entity**

```java
package com.slparcelauctions.backend.realty;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "realty_group_members")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@SuperBuilder
public class RealtyGroupMember extends BaseMutableEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "permissions", columnDefinition = "text[]", nullable = false)
    private String[] permissions = new String[0];

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    public Set<RealtyGroupPermission> permissionSet() {
        if (permissions == null || permissions.length == 0) return EnumSet.noneOf(RealtyGroupPermission.class);
        return java.util.Arrays.stream(permissions)
            .map(RealtyGroupPermission::valueOf)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(RealtyGroupPermission.class)));
    }

    public void setPermissionSet(Set<RealtyGroupPermission> perms) {
        if (perms == null || perms.isEmpty()) {
            this.permissions = new String[0];
        } else {
            this.permissions = perms.stream().map(Enum::name).toArray(String[]::new);
        }
    }
}
```

- [ ] **Step 2: Repository**

```java
package com.slparcelauctions.backend.realty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RealtyGroupMemberRepository extends JpaRepository<RealtyGroupMember, Long> {
    Optional<RealtyGroupMember> findByGroupIdAndUserId(Long groupId, Long userId);
    Optional<RealtyGroupMember> findByPublicId(UUID publicId);
    List<RealtyGroupMember> findByGroupIdOrderByJoinedAtAsc(Long groupId);
    List<RealtyGroupMember> findByUserIdOrderByJoinedAtDesc(Long userId);
    long countByGroupId(Long groupId);
    boolean existsByGroupIdAndUserId(Long groupId, Long userId);
}
```

- [ ] **Step 3: Compile**

```bash
cd backend && ./mvnw compile -q
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMember.java backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupMemberRepository.java
git commit -m "feat(realty): RealtyGroupMember entity + repository"
```

---

### Task 5: `RealtyGroupInvitation` entity + repository

**Files:**
- Create: `backend/.../realty/RealtyGroupInvitation.java`
- Create: `backend/.../realty/RealtyGroupInvitationRepository.java`

- [ ] **Step 1: Entity**

Same shape as `RealtyGroupMember` (TEXT[] permissions, `groupId`/`invitedUserId`/`invitedById` as Long FKs), plus `InvitationStatus status`, `OffsetDateTime expiresAt`, `OffsetDateTime respondedAt`. Use `@Enumerated(EnumType.STRING)` for status. Helper `isPending()`, `isExpired()` methods.

- [ ] **Step 2: Repository**

```java
public interface RealtyGroupInvitationRepository extends JpaRepository<RealtyGroupInvitation, Long> {
    Optional<RealtyGroupInvitation> findByPublicId(UUID publicId);
    Optional<RealtyGroupInvitation> findByGroupIdAndInvitedUserIdAndStatus(Long groupId, Long invitedUserId, InvitationStatus status);
    List<RealtyGroupInvitation> findByInvitedUserIdAndStatusOrderByCreatedAtDesc(Long invitedUserId, InvitationStatus status);
    List<RealtyGroupInvitation> findByGroupIdOrderByCreatedAtDesc(Long groupId);

    @Modifying
    @Query("UPDATE RealtyGroupInvitation i SET i.status = 'EXPIRED', i.respondedAt = CURRENT_TIMESTAMP WHERE i.status = 'PENDING' AND i.expiresAt < CURRENT_TIMESTAMP")
    int expirePending();

    @Query("SELECT i FROM RealtyGroupInvitation i WHERE i.status = 'PENDING' AND i.expiresAt < CURRENT_TIMESTAMP")
    List<RealtyGroupInvitation> findOverdueInvitations();
}
```

- [ ] **Step 3: Compile**

```bash
cd backend && ./mvnw compile -q
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupInvitation.java backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupInvitationRepository.java
git commit -m "feat(realty): RealtyGroupInvitation entity + repository"
```

---

## Phase 2 — Exceptions + Authorizer + Slug factory

### Task 6: Domain exceptions + `@RestControllerAdvice`

**Files:**
- Create: all 14 exception classes under `backend/.../realty/exception/`
- Create: `backend/.../realty/exception/RealtyExceptionHandler.java`

- [ ] **Step 1: Implement exceptions**

Each exception is a `RuntimeException` subclass with Lombok `@Getter` for any payload fields (e.g., `RealtyGroupRenameCooldownException.cooldownEndsAt`). Follow the pattern of `AdminListingStateException` in the codebase.

Example:
```java
public class RealtyGroupRenameCooldownException extends RuntimeException {
    @Getter private final OffsetDateTime cooldownEndsAt;
    public RealtyGroupRenameCooldownException(OffsetDateTime cooldownEndsAt) {
        super("Group rename is on cooldown until " + cooldownEndsAt);
        this.cooldownEndsAt = cooldownEndsAt;
    }
}
```

- [ ] **Step 2: `RealtyExceptionHandler` @RestControllerAdvice**

Maps each exception to its HTTP status + `Problem`-style body with `code` field per spec §5.7. Pattern: copy `AuctionExceptionHandler` shape.

- [ ] **Step 3: Compile**

```bash
cd backend && ./mvnw compile -q
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/exception/
git commit -m "feat(realty): domain exceptions + exception handler"
```

---

### Task 7: `RealtyGroupSlugFactory` + tests

**Files:**
- Create: `backend/.../realty/slug/RealtyGroupSlugFactory.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slug/RealtyGroupSlugFactoryTest.java`

- [ ] **Step 1: Write failing tests**

```java
@SpringBootTest
class RealtyGroupSlugFactoryTest {
    @Autowired RealtyGroupSlugFactory factory;
    @Autowired RealtyGroupRepository repo;

    @Test void basicNameToKebab() { assertEquals("mainland-realty-co", factory.fromName("Mainland Realty Co.")); }
    @Test void stripsLeadingAndTrailingDashes() { assertEquals("mainland-realty", factory.fromName("!!!Mainland Realty!!!")); }
    @Test void truncatesAt60() { /* long input → slug ≤60 */ }
    @Test void unicodeFallback() { /* all-unicode input → "group-XXXXXXXX" fallback */ }
    @Test void appendsSuffixOnCollision() {
        repo.save(RealtyGroup.builder().name("Mainland").slug("mainland").leaderId(1L).build());
        assertEquals("mainland-2", factory.derive("Mainland", null));
    }
    @Test void renameSkipsSelfRow() { /* renaming a row with same slug doesn't add suffix */ }
}
```

- [ ] **Step 2: Run failing**

```bash
cd backend && ./mvnw test -Dtest=RealtyGroupSlugFactoryTest -q
```
Expected: compile failure / test failure.

- [ ] **Step 3: Implement**

```java
@Service
@RequiredArgsConstructor
public class RealtyGroupSlugFactory {
    private final RealtyGroupRepository repo;

    public String fromName(String name) {
        if (name == null) return "";
        String slug = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.length() > 60) {
            int cut = slug.lastIndexOf('-', 60);
            slug = cut > 30 ? slug.substring(0, cut) : slug.substring(0, 60);
        }
        return slug;
    }

    public String derive(String name, Long excludeGroupId) {
        String base = fromName(name);
        if (base.isEmpty()) {
            // fallback: caller passes a publicId-derived hint; here we return placeholder
            // and let the caller patch when publicId is known
            return "group";
        }
        if (countCollisions(base, excludeGroupId) == 0) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (candidate.length() > 80) {
                int trim = candidate.length() - 80;
                candidate = base.substring(0, base.length() - trim) + "-" + i;
            }
            if (countCollisions(candidate, excludeGroupId) == 0) return candidate;
        }
        throw new IllegalStateException("Slug collision space exhausted for base=" + base);
    }

    private long countCollisions(String slug, Long excludeId) {
        return excludeId == null ? repo.countActiveBySlug(slug) : repo.countOtherActiveBySlug(slug, excludeId);
    }
}
```

Note: the unicode-fallback is handled at the service layer by patching the slug from `publicId` after entity is persisted. Document inline.

- [ ] **Step 4: Run tests, verify pass**

```bash
cd backend && ./mvnw test -Dtest=RealtyGroupSlugFactoryTest -q
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slug/ backend/src/test/java/com/slparcelauctions/backend/realty/slug/
git commit -m "feat(realty): slug factory + tests"
```

---

### Task 8: `RealtyGroupAuthorizer` + tests

**Files:**
- Create: `backend/.../realty/auth/RealtyGroupAuthorizer.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/auth/RealtyGroupAuthorizerTest.java`

- [ ] **Step 1: Write failing tests** covering: leader implicit-all, agent flag, non-member, dissolved-group (throws), nonexistent-group (throws), agent with empty perms.

- [ ] **Step 2: Run failing.** `cd backend && ./mvnw test -Dtest=RealtyGroupAuthorizerTest -q`

- [ ] **Step 3: Implement** per spec §4.2 resolution rule.

- [ ] **Step 4: Run tests, verify pass.**

- [ ] **Step 5: Commit.** `git commit -m "feat(realty): RealtyGroupAuthorizer + tests"`

---

## Phase 3 — RealtyGroupService (group CRUD + permission edits)

### Task 9: `RealtyGroupService.createGroup`

**Files:**
- Create: `backend/.../realty/service/RealtyGroupService.java` (initial — just `createGroup`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceCreateTest.java`

- [ ] **Step 1: Failing tests** — happy path, duplicate name (case-insensitive → 409), invalid website URL.
- [ ] **Step 2: Run failing.**
- [ ] **Step 3: Implement** `createGroup(CreateRealtyGroupRequest req, Long creatorUserId) → RealtyGroup`. Validates name uniqueness via repo; derives slug; inserts `RealtyGroup` (with `leader_id = creatorUserId`) + `RealtyGroupMember` leader row in single tx.
- [ ] **Step 4: Run tests pass.**
- [ ] **Step 5: Commit.** `git commit -m "feat(realty): RealtyGroupService.createGroup + tests"`

### Task 10: `RealtyGroupService.updateGroup` (rename cooldown)

Same shape — failing tests for rename within cooldown (409), outside cooldown (success), admin bypass (separate method `updateGroupAsAdmin`), website validation, fees update. Implement. Commit.

### Task 11: `RealtyGroupService.dissolveGroup`

Sets `dissolved_at = NOW()`. Tests: leader-only succeeds; non-leader 403; already-dissolved 410. Commit.

### Task 12: `RealtyGroupService.updateMemberPermissions`

Leader-only. Tests: leader updates agent's perms (success); non-leader updates (403); target is leader (rejected: leader perms are implicit, not editable). Commit.

---

## Phase 4 — Membership + invitation services

### Task 13: `RealtyGroupMembershipService.leave`

Tests: agent leaves (member row deleted); leader leaves (409 LEADER_CANNOT_LEAVE). Commit.

### Task 14: `RealtyGroupMembershipService.removeMember`

Tests: REMOVE_AGENTS holder removes agent (success); non-holder (403); target is leader (409 CANNOT_REMOVE_LEADER). Commit.

### Task 15: `RealtyGroupMembershipService.transferLeadership`

Tests:
- Happy STAY: leader_id updated; old leader's perms set to all four flags; both notifications fired.
- Happy LEAVE: leader_id updated; old leader's member row deleted.
- Target not a member: 400 TRANSFER_TARGET_NOT_MEMBER.
- Non-leader caller: 403.

Commit.

### Task 16: `RealtyGroupInvitationService.invite`

Tests:
- Happy path with INVITE_AGENTS or leader caller.
- Invitee already a member → 409 ALREADY_MEMBER.
- Invitee has live PENDING invite → 409 INVITATION_ALREADY_PENDING.
- Seat limit reached → 409 SEAT_LIMIT_REACHED.
- Username not found → 404 USER_NOT_FOUND (lookups go through UserRepository).

Commit.

### Task 17: `RealtyGroupInvitationService.accept` / `.decline` / `.revoke`

Each in its own commit. accept tests cover: dissolved-group race (410), already-member race (409), seat-limit race (409), expired invitation (410). Revoke is INVITE_AGENTS-gated.

---

## Phase 5 — Expiry job

### Task 18: `RealtyGroupExpiryJob`

**Files:**
- Create: `backend/.../realty/service/RealtyGroupExpiryJob.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupExpiryJobTest.java`

- [ ] **Step 1: Failing test** seeds a PENDING invitation with `expires_at` in past, runs the job tick, asserts status is EXPIRED + notification fired.

- [ ] **Step 2: Implement**

```java
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "slpa.realty.invitation-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class RealtyGroupExpiryJob {
    private final RealtyGroupInvitationRepository invitations;
    private final NotificationPublisher notifications;

    @Scheduled(fixedDelayString = "PT1M")
    @Transactional
    public void expirePendingInvitations() {
        List<RealtyGroupInvitation> overdue = invitations.findOverdueInvitations();
        for (RealtyGroupInvitation inv : overdue) {
            inv.setStatus(InvitationStatus.EXPIRED);
            inv.setRespondedAt(OffsetDateTime.now());
            notifications.realtyGroupInvitationExpired(inv);
        }
    }
}
```

- [ ] **Step 3: Run test pass + Commit.**

---

## Phase 6 — Notification publisher methods

### Task 19: Add 9 methods to `NotificationPublisher`

- [ ] **Step 1: Failing tests** in `NotificationPublisherTest` for each method's recipients + payload.
- [ ] **Step 2: Implement** — each method composes a `Notification` row with category `REALTY_GROUP` and dispatches to in-app/email/SL-IM channels per existing infrastructure. Body strings follow spec §8.
- [ ] **Step 3: Pass + Commit.** `git commit -m "feat(realty): NotificationPublisher methods for group lifecycle"`

---

## Phase 7 — DTOs + Controllers

### Task 20: DTOs

- [ ] Implement all DTO records per spec §5.8 + extras (`RealtyGroupRowDto` for admin list, `UserRealtyGroupAffiliationDto` for user profile).
- [ ] Compile + Commit. `git commit -m "feat(realty): DTOs"`

### Task 21: `RealtyGroupController` (POST/PATCH/DELETE under /api/v1/realty-groups)

- [ ] Slice tests (`@WebMvcTest`) for each endpoint: auth gate, validation, response shape.
- [ ] Implement.
- [ ] Pass + Commit.

### Task 22: `RealtyGroupPublicController` (GET endpoints, public)

Same shape. Tests cover anonymous read; member-view of permissions hidden from anonymous; by-slug lookup; dissolved-group 410.

### Task 23: `MeRealtyGroupController` (user-side invitations + my groups)

`/me/invitations`, `/me/invitations/{id}/accept`, `/me/invitations/{id}/decline`, `/me/realty-groups`.

### Task 24: `RealtyGroupInvitationController` (group-scoped invitations)

POST/GET/DELETE under `/api/v1/realty-groups/{publicId}/invitations[/{id}]`.

### Task 25: `RealtyGroupMembershipController` endpoints under `/api/v1/realty-groups/{publicId}/members/...` + `/leave` + `/transfer-leadership`

(These may be folded into `RealtyGroupController` if size permits — judgment at implementation time.)

---

## Phase 8 — Admin endpoints

### Task 26: `AdminRealtyGroupController` (5 endpoints)

- [ ] Slice tests covering ROLE_ADMIN gate, AdminActionService writes, leader-remove with replacement.
- [ ] Implement + wire `AdminActionService` with new `AdminActionType` values.
- [ ] Pass + Commit.

### Task 27: `AdminActionType` enum widening + CHECK constraint initializer

- [ ] Add `REALTY_GROUP_EDIT`, `REALTY_GROUP_DISSOLVE`, `REALTY_GROUP_MEMBER_REMOVE` to the enum.
- [ ] Update the existing `AdminActionTypeCheckConstraintInitializer` (or equivalent) to include them.
- [ ] Test that the CHECK constraint widens at startup.
- [ ] Commit.

---

## Phase 9 — Image storage helper (cross-cutting)

### Task 28: Pick WebP encoder + add dependency

- [ ] **Step 1: Evaluate** `com.github.usefulness:webp-imageio` vs `com.luciad:webp-imageio` vs `com.sksamuel.scrimage:scrimage-webp`. Decision criteria: actively maintained, native libwebp bundled, JVM-portable (Linux+Mac+Windows), Apache-2.0 license.

- [ ] **Step 2: Add to `backend/pom.xml`** with version pinned.

- [ ] **Step 3: Smoke test** — write a one-off `WebpEncoderProbeTest` that round-trips a 4x4 PNG through the encoder and verifies WebP magic bytes (`RIFF....WEBP`).

- [ ] **Step 4: Commit.** `git commit -m "feat(storage): add WebP encoder dependency"`

### Task 29: `ImageStorageService` interface + DTOs + `ImageStorageServiceImpl`

**Files:**
- Create: `backend/.../storage/ImageStorageService.java` (interface)
- Create: `backend/.../storage/ImageStorageContext.java` (record)
- Create: `backend/.../storage/StoredImage.java` (record)
- Create: `backend/.../storage/ImagePurpose.java` (enum)
- Create: `backend/.../storage/ImageStorageServiceImpl.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/storage/ImageStorageServiceTest.java`

- [ ] **Step 1: Failing tests:**
  - PNG opaque round-trip → WebP magic bytes, contentType=image/webp
  - PNG with alpha round-trip → WebP magic bytes
  - JPEG round-trip → WebP
  - Text/plain payload → UnsupportedImageFormatException (415)
  - SVG payload (XML) → UnsupportedImageFormatException (415)
  - HEIC payload → UnsupportedImageFormatException (415) (until decoder added)
  - Resize-down works (input 1024px → output ≤512px with `maxDim=512`)
  - No upscale (input 100px stays 100px when `maxDim=512`)

- [ ] **Step 2: Implement** per spec §7.2-7.3. Reuse `ImageUploadValidator` for sniff+decode, then resize via Thumbnailator, encode to WebP via the new encoder, call `objectStorageService.put(key, bytes, "image/webp")`.

- [ ] **Step 3: Pass + Commit.**

### Task 30: Migrate existing callers (`AvatarService`, `UserDefaultCoverService`, `DisputeEvidenceUploadService`, listing photos)

- [ ] **Step 1: Audit listing-photo upload path** — locate which service handles `POST /auctions/{id}/photos` (likely `AuctionPhotoService` or similar). Grep + read.

- [ ] **Step 2: For each caller:**
  - Replace direct `ObjectStorageService.put(...)` (or `AvatarImageProcessor` PNG output) with `ImageStorageService.storeImage(...)`.
  - Update the entity's `content_type` column write to use the returned `StoredImage.contentType()`.
  - Object key extension flips from `.png` to `.webp` for new uploads; historical rows stay with original extensions (no migration needed since `content_type` column is the source of truth).
  - Run that caller's tests.

- [ ] **Step 3: Compile-wide check + run full backend test suite.**

```bash
cd backend && ./mvnw test -q
```

- [ ] **Step 4: Commit.** `git commit -m "feat(storage): migrate existing image upload sites to ImageStorageService"`

---

## Phase 10 — Logo + cover upload endpoints

### Task 31: `POST /api/v1/realty-groups/{publicId}/logo` + `/cover`

- [ ] Slice tests: auth gate (EDIT_GROUP_PROFILE), oversize rejection, format rejection, success updates the group's `logoObjectKey`/`coverObjectKey` columns.
- [ ] Implement in `RealtyGroupController` or a sub-controller. Calls `ImageStorageService.storeImage()` with the appropriate `ImagePurpose`.
- [ ] Pass + Commit.

---

## Phase 11 — Backend integration tests

### Task 32: End-to-end lifecycle integration tests

**File:** `backend/src/test/java/com/slparcelauctions/backend/realty/RealtyGroupIntegrationTest.java`

- [ ] **Step 1: Write integration scenarios** (`@SpringBootTest` + real Postgres):
  - Full lifecycle: create → invite → accept → permission edit → remove → dissolve
  - Multi-group: user A is in groups G1+G2 (as leader of G1, agent of G2)
  - Rename cooldown (within 30d → 409; outside → success; admin bypass → success without bumping last_renamed_at)
  - Case-insensitive name conflict
  - Concurrent permission update via optimistic lock collision
  - Soft-delete + name/slug immediately reusable
  - Expiry job tick
  - Admin force-dissolve with active members
  - Leader cannot leave; transfer-target-not-member rejection
  - FK behavior: dissolving SET-NULLs `auctions.realty_group_id` (smoke; future C consumes the column)
  - Seat limit enforcement on invite (and accept race rejection)

- [ ] **Step 2: Run tests pass.**

- [ ] **Step 3: Commit.**

---

## Phase 12 — Frontend types + API client + hooks

### Task 33: TypeScript types + API client

**Files:**
- Create: `frontend/src/types/realty.ts`
- Create: `frontend/src/lib/api/realtyGroups.ts`
- Create: `frontend/src/lib/realty/permissions.ts`
- Create: `frontend/src/lib/realty/errorMessages.ts`

- [ ] **Step 1:** Mirror backend DTOs as TS types/interfaces. Define `RealtyGroupPermission` as a TS enum (or `as const`). Implement `realtyGroupErrorMessage(err, fallback)` per the `adminListingErrorMessage` precedent.

- [ ] **Step 2:** API client wrapping `fetch` for every backend endpoint.

- [ ] **Step 3:** Type-check passes (`cd frontend && npx tsc --noEmit`).

- [ ] **Step 4: Commit.**

### Task 34: TanStack Query hooks bundle

**File:** `frontend/src/hooks/realty/useRealtyGroups.ts`

- [ ] Hooks: `useRealtyGroup(publicId)`, `useRealtyGroupBySlug(slug)`, `useMyRealtyGroups()`, `useMyInvitations()`, `useUserRealtyGroups(userPublicId)`, `useAdminRealtyGroupsList(filters)`, plus mutation hooks for every write: `useCreateGroup`, `useUpdateGroup`, `useDissolveGroup`, `useUploadLogo`, `useUploadCover`, `useInvite`, `useRevokeInvitation`, `useAcceptInvitation`, `useDeclineInvitation`, `useRemoveMember`, `useLeaveGroup`, `useTransferLeadership`, `useUpdatePermissions`.

- [ ] Each mutation invalidates the appropriate query keys.

- [ ] Toast on success/error using `useToast` + `realtyGroupErrorMessage`.

- [ ] **Tests:** MSW handlers + a Vitest spec per mutation verifying cache invalidation + toast call.

- [ ] **Commit.**

---

## Phase 13 — Frontend public page

### Task 35: `/group/[slug]/page.tsx` + components

**Files:**
- Create: `frontend/src/app/group/[slug]/page.tsx` (server component, `dynamic = "force-dynamic"`)
- Create: `RealtyGroupHeroBanner.tsx`, `LeaderCard.tsx`, `RealtyGroupAgentsGrid.tsx`, `RealtyGroupMemberCard.tsx`

- [ ] **Step 1: Vitest tests** for each component (variants, accessibility).

- [ ] **Step 2: Implement** per spec §6. Logo/cover images via `apiUrl(...)`. Anonymous viewer sees no permissions or join-dates; logged-in member sees all.

- [ ] **Step 3: Page integration test** asserting `force-dynamic` posture + anonymous render shape.

- [ ] **Commit.**

---

## Phase 14 — Frontend dashboard pages

### Task 36: `/dashboard/groups/page.tsx` — my-memberships list

- [ ] List my groups + pending invitations strip. Component + test + commit.

### Task 37: `/dashboard/groups/create/page.tsx`

- [ ] `GroupCreateForm` with React Hook Form + Zod. Submit → POST + redirect to manage page. Component + test + commit.

### Task 38: `/dashboard/groups/[slug]/manage/page.tsx`

- [ ] Tabs: Profile / Members / Invitations / Settings (leader-only). Each tab is its own component. `PermissionToggleRow` reused in invite-form + member edit modal. Tests + commit.

### Task 39: `/dashboard/invitations/page.tsx`

- [ ] Invitations list with Accept / Decline. Tests + commit.

---

## Phase 15 — Frontend admin pages

### Task 40: `/admin/realty-groups/page.tsx`

- [ ] Paginated list + status filter + search + row-action modal (force-edit, force-dissolve, force-remove-member). Tests + commit.

### Task 41: `/admin/realty-groups/[publicId]/page.tsx`

- [ ] Detail view + force-edit form + audit-log entries. Tests + commit.

### Task 42: Admin nav entry

- [ ] Add "Realty Groups" entry to `AdminNav.tsx` (or equivalent). Commit.

---

## Phase 16 — User profile "Groups" section

### Task 43: User public profile integration

- [ ] **Step 1: Locate** the existing user public profile page component (grep for `PublicProfileView` or page at `app/user/...`).

- [ ] **Step 2: Add** a "Groups" section calling `GET /users/{publicId}/realty-groups`. Hidden if empty. Uses `GroupChip` component.

- [ ] **Step 3: Vitest test** for the new section + page-level integration.

- [ ] **Commit.**

---

## Phase 17 — Postman + README

### Task 44: Postman collection — "Realty Groups" folder

- [ ] Use Postman MCP to add a "Realty Groups" folder with every endpoint mirrored. Variable-chained scripts (`groupPublicId`, `invitationPublicId`, `memberPublicId`) capture IDs from creation responses for downstream requests.

- [ ] **Commit** any local Postman-related files if there are any (most likely none — Postman lives in the Postman cloud).

### Task 45: Postman — "Admin / Realty Groups" folder

- [ ] Mirror the 5 admin endpoints + negative cases (cooldown, seat-limit, already-pending).

### Task 46: README.md sweep

- [ ] Add a "Realty Groups" section to README.md under the appropriate parent heading. Cover: feature overview, multi-group membership, permission model, public profile URL pattern, dissolution + slug-reuse semantics, admin escape hatches.

- [ ] **Commit.** `git commit -m "docs(readme): realty groups section"`

---

## Phase 18 — Final QA + PR

### Task 47: Run full test suite + verify guards

- [ ] **Step 1:** `cd backend && ./mvnw test -q` — expect green
- [ ] **Step 2:** `cd frontend && npm test` — expect green
- [ ] **Step 3:** `cd frontend && npm run verify` — expect green
- [ ] **Step 4:** `cd frontend && npm run build` — expect green

Fix any failures inline (one fix per commit).

### Task 48: Open PR to `dev`

- [ ] **Step 1:** Verify branch is up-to-date with `dev`:
```bash
git fetch origin
git rebase origin/dev
git push --force-with-lease
```

- [ ] **Step 2:** Open PR with `gh pr create` targeting `dev`, title `feat: realty groups (core + permissions)`, body summarizing the spec + the 4 deferred sub-projects + the centralized S3 helper fix.

- [ ] **Step 3:** Wait for CI; merge if green via `gh pr merge ... --merge`.

- [ ] **Step 4:** Open follow-up PR `dev → main` for user review. **Do not merge** — that's the user's call per memory.

---

## Risks / mitigations

- **WebP encoder library choice:** Task 28 evaluates options; pinned lib must support Linux+Mac+Windows. Mitigation: fall back to `com.sksamuel.scrimage:scrimage-webp` if `webp-imageio` has platform gaps.
- **Existing upload-site migration scope creep:** If the audit in Task 30 reveals more sites than expected, escalate to user; spec §13.4 authorizes a separate prerequisite PR if the migration exceeds ~150 LoC.
- **CITEXT extension permissions in prod:** RDS allows `CREATE EXTENSION citext` to the `slpa` user by default, but verify before merge. If not, switch the migration to use a `lower(name)`-indexed plain VARCHAR.
- **Slug derivation Unicode edge cases:** Names with only non-ASCII (e.g., all CJK) fall back to a publicId-derived slug, set by `RealtyGroupService.createGroup` after entity persist.
