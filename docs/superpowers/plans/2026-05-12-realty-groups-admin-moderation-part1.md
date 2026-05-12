# Realty Groups F — Implementation Plan — Part 1 (Tasks 1–14)

Foundation + Slice 1 (group moderation entity) + Slice 2 (bulk listing suspend + 48 h auto-cancel).

---

## Task 1: V28 Flyway migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__realty_group_admin_moderation.sql`

**Spec references:** §4.1, §4.2, §4.3, §4.4, §4.5, §20.1.

- [ ] **Step 1: Write the migration SQL**

Create the file with:

```sql
-- V28: realty groups sub-project F — admin moderation toolkit.

-- §4.1 — group moderation entity (suspend + ban).
CREATE TABLE realty_group_suspensions (
    id                   BIGSERIAL PRIMARY KEY,
    public_id            UUID NOT NULL UNIQUE,
    realty_group_id      BIGINT NOT NULL REFERENCES realty_groups(id),
    issued_by_admin_id   BIGINT NOT NULL REFERENCES users(id),
    reason               VARCHAR(64) NOT NULL,
    notes                TEXT,
    issued_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ,
    lifted_at            TIMESTAMPTZ,
    lifted_by_admin_id   BIGINT REFERENCES users(id),
    lifted_notes         TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rg_susp_lifted_consistency
      CHECK ((lifted_at IS NULL AND lifted_by_admin_id IS NULL)
          OR (lifted_at IS NOT NULL AND lifted_by_admin_id IS NOT NULL))
);

CREATE INDEX ix_rg_susp_active
  ON realty_group_suspensions(realty_group_id)
  WHERE lifted_at IS NULL;

CREATE INDEX ix_rg_susp_expiry_sweep
  ON realty_group_suspensions(expires_at)
  WHERE lifted_at IS NULL AND expires_at IS NOT NULL;

-- §4.2 — user-submitted reports against groups.
CREATE TABLE realty_group_reports (
    id                   BIGSERIAL PRIMARY KEY,
    public_id            UUID NOT NULL UNIQUE,
    realty_group_id      BIGINT NOT NULL REFERENCES realty_groups(id),
    reporter_user_id     BIGINT NOT NULL REFERENCES users(id),
    reason               VARCHAR(64) NOT NULL,
    details              TEXT NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolved_by_admin_id BIGINT REFERENCES users(id),
    resolved_at          TIMESTAMPTZ,
    resolution_notes     TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rg_reports_status CHECK (
      status IN ('OPEN', 'RESOLVED', 'DISMISSED')
    )
);

CREATE UNIQUE INDEX uq_rg_reports_one_open_per_reporter
  ON realty_group_reports(realty_group_id, reporter_user_id)
  WHERE status = 'OPEN';

CREATE INDEX ix_rg_reports_open_queue
  ON realty_group_reports(realty_group_id)
  WHERE status = 'OPEN';

-- §4.3 — listing suspension audit (distinguishes auto / admin-individual / admin-group-bulk).
CREATE TABLE listing_suspensions (
    id                      BIGSERIAL PRIMARY KEY,
    public_id               UUID NOT NULL UNIQUE,
    auction_id              BIGINT NOT NULL REFERENCES auctions(id),
    cause                   VARCHAR(32) NOT NULL,
    suspended_by_admin_id   BIGINT REFERENCES users(id),
    group_suspension_id     BIGINT REFERENCES realty_group_suspensions(id),
    bulk_action_id          UUID,
    reason                  VARCHAR(64),
    notes                   TEXT,
    suspended_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lifted_at               TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_listing_susp_terminal_state
      CHECK (NOT (lifted_at IS NOT NULL AND cancelled_at IS NOT NULL)),
    CONSTRAINT ck_listing_susp_cause CHECK (
      cause IN ('AUTO_OWNERSHIP_CHANGE','AUTO_PARCEL_DELETED',
                'ADMIN_INDIVIDUAL','ADMIN_GROUP_BULK')
    )
);

CREATE INDEX ix_listing_susp_active_bulk
  ON listing_suspensions(auction_id, suspended_at)
  WHERE cause = 'ADMIN_GROUP_BULK'
    AND lifted_at IS NULL
    AND cancelled_at IS NULL;

CREATE INDEX ix_listing_susp_auction
  ON listing_suspensions(auction_id);

-- §4.3 — backfill listing_suspensions rows from existing SUSPENDED auctions.
-- Cause is inferred from the most recent fraud_flag for the auction; falls back
-- to ADMIN_INDIVIDUAL if no fraud flag is present.
INSERT INTO listing_suspensions (
    public_id, auction_id, cause, suspended_at, suspended_by_admin_id, reason
)
SELECT
    gen_random_uuid(),
    a.id,
    CASE
        WHEN ff.reason = 'OWNERSHIP_CHANGED_TO_UNKNOWN' THEN 'AUTO_OWNERSHIP_CHANGE'
        WHEN ff.reason = 'PARCEL_DELETED_OR_MERGED' THEN 'AUTO_PARCEL_DELETED'
        ELSE 'ADMIN_INDIVIDUAL'
    END AS cause,
    COALESCE(a.suspended_at, NOW()),
    NULL,
    ff.reason
  FROM auctions a
  LEFT JOIN LATERAL (
    SELECT reason FROM fraud_flags ff2
     WHERE ff2.auction_id = a.id
     ORDER BY detected_at DESC
     LIMIT 1
  ) ff ON TRUE
 WHERE a.status = 'SUSPENDED';

-- §4.4 — realty_group_sl_groups: drop unused About-text columns,
-- add drift + unregister tracking, tighten verified_via CHECK.
ALTER TABLE realty_group_sl_groups
  DROP COLUMN last_polled_at,
  DROP COLUMN poll_attempts;

UPDATE realty_group_sl_groups
   SET verified_via = 'FOUNDER_TERMINAL'
 WHERE verified_via = 'ABOUT_TEXT';

ALTER TABLE realty_group_sl_groups
  DROP CONSTRAINT ck_rg_sl_groups_verified_via;
ALTER TABLE realty_group_sl_groups
  ADD CONSTRAINT ck_rg_sl_groups_verified_via
    CHECK (verified_via IS NULL OR verified_via = 'FOUNDER_TERMINAL');

ALTER TABLE realty_group_sl_groups
  ADD COLUMN last_revalidated_at              TIMESTAMPTZ,
  ADD COLUMN current_founder_uuid             UUID,
  ADD COLUMN consecutive_fetch_failures       INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN drift_detected_at                TIMESTAMPTZ,
  ADD COLUMN drift_reason                     VARCHAR(64),
  ADD COLUMN drift_acknowledged_at            TIMESTAMPTZ,
  ADD COLUMN drift_acknowledged_by_admin_id   BIGINT REFERENCES users(id),
  ADD COLUMN unregistered_at                  TIMESTAMPTZ,
  ADD COLUMN unregistered_by_admin_id         BIGINT REFERENCES users(id),
  ADD COLUMN unregister_reason                VARCHAR(64);

CREATE INDEX ix_rg_sl_groups_drift_open
  ON realty_group_sl_groups(realty_group_id)
  WHERE drift_detected_at IS NOT NULL
    AND drift_acknowledged_at IS NULL;

CREATE INDEX ix_rg_sl_groups_reverify_due
  ON realty_group_sl_groups(last_revalidated_at)
  WHERE verified = true;

DROP INDEX IF EXISTS ix_rg_sl_groups_pending_poll;

-- §4.5 — fraud_flags supports REALTY_GROUP entity.
ALTER TABLE fraud_flags DROP CONSTRAINT fraud_flags_entity_type_check;
ALTER TABLE fraud_flags ADD CONSTRAINT fraud_flags_entity_type_check CHECK (
    entity_type IN ('USER', 'LISTING', 'REALTY_GROUP')
);

-- §20.1 step 8 — admit ADMIN_BULK_EXPIRED in cancellation_logs penalty_kind.
ALTER TABLE cancellation_logs DROP CONSTRAINT IF EXISTS cancellation_logs_penalty_kind_check;
ALTER TABLE cancellation_logs ADD CONSTRAINT cancellation_logs_penalty_kind_check CHECK (
    penalty_kind IS NULL OR penalty_kind IN (
        'NONE','WARNING','PENALTY','PENALTY_AND_30D','PERMANENT_BAN',
        'BROKER_CANCEL','ADMIN_BULK_EXPIRED'
    )
);
```

- [ ] **Step 2: Verify migration applies cleanly**

Run: `cd backend && ./mvnw -q spring-boot:run` (or `docker compose restart backend`). Confirm logs show:
- `Successfully validated 28 migrations`
- `Migrating schema "public" to version "28 - realty group admin moderation"`

If the dev DB already has a partial V28 from a prior run, drop the public schema (`docker compose exec postgres psql -U slpa -d slpa -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"`) and let migrations re-run from V1.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V28__realty_group_admin_moderation.sql
git commit -m "feat(realty-f): V28 migration — moderation tables + sl_groups cleanup"
```

---

## Task 2: Domain entities (new + sync existing to V28)

**Files (new):**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupSuspension.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/SuspensionReason.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReport.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportReason.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportStatus.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/ListingSuspension.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/ListingSuspensionCause.java`

**Files (modify to sync entities with V28 schema changes):**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroup.java`
  - **Remove fields:** `lastPolledAt`, `pollAttempts` (V28 dropped these columns).
  - **Add fields:** `lastRevalidatedAt: OffsetDateTime`, `currentFounderUuid: UUID`, `consecutiveFetchFailures: int` (default 0), `driftDetectedAt: OffsetDateTime`, `driftReason: String`, `driftAcknowledgedAt: OffsetDateTime`, `driftAcknowledgedByAdmin: User` (ManyToOne), `unregisteredAt: OffsetDateTime`, `unregisteredByAdmin: User` (ManyToOne), `unregisterReason: String`.
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlag.java`
  - **Add field:** `entityType: FraudFlagEntityKind` (Enumerated, STRING). Defaults to `LISTING` for backwards compatibility (the V28 migration set the column default; this matches at the Java level).
- Modify: any existing repository / DTO / mapper that touches these dropped fields. Likely impacted: `RealtyGroupSlGroupRepository`, `SlGroupRegistrationExpiryTask`, `SlGroupAboutTextPollTask` (this will be deleted in Task 21; for Task 2, comment-out references that don't compile and add a TODO marker noting Task 21 deletion). Better: temporarily delete the broken references inline since Task 21 is going to remove them anyway — but ONLY remove imports/usages that no longer compile, not the broader About-text path code (that's Task 21).

**Spec references:** §4.1–§4.3 (new entities), §4.4 (RealtyGroupSlGroup sync), §4.5 (FraudFlag entity_type added by V28).

**Important:** Hibernate `ddl-auto: validate` (which Spring Boot 4 + Flyway sets by default) will fail to start the backend if the entity fields don't match the V28 schema. This task MUST sync the existing entities, not just create new ones.

- [ ] **Step 1: Write entity classes following the BaseMutableEntity convention**

Each entity:
- Extends `BaseMutableEntity` (gives `id`, `publicId`, `createdAt`, `updatedAt`, `version`).
- Uses Lombok `@SuperBuilder`, `@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Entity`, `@Table(name = "...")`.
- Foreign keys via `@ManyToOne(fetch = LAZY) @JoinColumn(name = "...")`.
- Enum columns: `@Enumerated(EnumType.STRING)`.

`RealtyGroupSuspension` fields:
- `realtyGroup` (`RealtyGroup`, ManyToOne, `realty_group_id`)
- `issuedByAdmin` (`User`, ManyToOne, `issued_by_admin_id`)
- `reason` (`SuspensionReason`, enum)
- `notes` (String)
- `issuedAt` (OffsetDateTime)
- `expiresAt` (OffsetDateTime, nullable)
- `liftedAt` (OffsetDateTime, nullable)
- `liftedByAdmin` (`User`, ManyToOne, nullable)
- `liftedNotes` (String, nullable)

Helper method: `boolean isActive(OffsetDateTime now)` returning `liftedAt == null && (expiresAt == null || expiresAt.isAfter(now))`.

`SuspensionReason` enum: `FRAUD`, `REPORTS_RESOLVED_AGAINST`, `TOS_VIOLATION`, `ABUSE`, `OTHER`.

`RealtyGroupReport` fields:
- `realtyGroup` (`RealtyGroup`, ManyToOne)
- `reporter` (`User`, ManyToOne, `reporter_user_id`)
- `reason` (`RealtyGroupReportReason`, enum)
- `details` (String)
- `status` (`RealtyGroupReportStatus`, enum, default OPEN)
- `resolvedByAdmin` (`User`, ManyToOne, nullable)
- `resolvedAt` (OffsetDateTime, nullable)
- `resolutionNotes` (String, nullable)

`RealtyGroupReportReason` enum: `FRAUDULENT_LISTINGS`, `MISLEADING_ATTRIBUTION`, `HARASSMENT`, `IMPERSONATION`, `SPAM`, `OTHER`.
`RealtyGroupReportStatus` enum: `OPEN`, `RESOLVED`, `DISMISSED`.

`ListingSuspension` fields:
- `auction` (`Auction`, ManyToOne)
- `cause` (`ListingSuspensionCause`, enum)
- `suspendedByAdmin` (`User`, ManyToOne, nullable)
- `groupSuspension` (`RealtyGroupSuspension`, ManyToOne, nullable)
- `bulkActionId` (UUID, nullable)
- `reason` (String, nullable; freeform — corresponds to whatever the cause carries)
- `notes` (String, nullable)
- `suspendedAt` (OffsetDateTime)
- `liftedAt` (OffsetDateTime, nullable)
- `cancelledAt` (OffsetDateTime, nullable)

`ListingSuspensionCause` enum: `AUTO_OWNERSHIP_CHANGE`, `AUTO_PARCEL_DELETED`, `ADMIN_INDIVIDUAL`, `ADMIN_GROUP_BULK`.

- [ ] **Step 2: Add empty-init test for each entity**

Files:
- `backend/src/test/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupSuspensionTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/ListingSuspensionTest.java`

Each just builds the entity via the builder, asserts non-null fields, and (for `RealtyGroupSuspension`) tests `isActive` against several time combinations.

- [ ] **Step 3: Run + commit**

```bash
./mvnw test -Dtest=RealtyGroupSuspensionTest -Dtest=RealtyGroupReportTest -Dtest=ListingSuspensionTest
git add backend/src/main/java backend/src/test/java
git commit -m "feat(realty-f): moderation + reports + listing-suspension entities"
```

---

## Task 3: Repositories

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupSuspensionRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/ListingSuspensionRepository.java`

**Spec references:** §8.

- [ ] **Step 1: Write repository interfaces**

`RealtyGroupSuspensionRepository extends JpaRepository<RealtyGroupSuspension, Long>`:
```java
@Query("""
    SELECT s FROM RealtyGroupSuspension s
     WHERE s.realtyGroup.id = :groupId
       AND s.liftedAt IS NULL
       AND (s.expiresAt IS NULL OR s.expiresAt > :now)
""")
Optional<RealtyGroupSuspension> findActiveByGroupId(Long groupId, OffsetDateTime now);

@Query("""
    SELECT s FROM RealtyGroupSuspension s
     WHERE s.realtyGroup.id = :groupId
     ORDER BY s.issuedAt DESC
""")
List<RealtyGroupSuspension> findHistoryByGroupId(Long groupId);

@Query("""
    SELECT s FROM RealtyGroupSuspension s
     WHERE s.liftedAt IS NULL
       AND s.expiresAt IS NOT NULL
       AND s.expiresAt < :now
""")
List<RealtyGroupSuspension> findExpired(OffsetDateTime now);

Optional<RealtyGroupSuspension> findByPublicId(UUID publicId);
```

`RealtyGroupReportRepository extends JpaRepository<RealtyGroupReport, Long>`:
```java
Optional<RealtyGroupReport> findByPublicId(UUID publicId);

@Query("""
    SELECT COUNT(r) > 0 FROM RealtyGroupReport r
     WHERE r.realtyGroup.id = :groupId
       AND r.reporter.id = :reporterId
       AND r.status = com.slparcelauctions.backend.realty.reports.RealtyGroupReportStatus.OPEN
""")
boolean existsOpenByGroupAndReporter(Long groupId, Long reporterId);

Page<RealtyGroupReport> findByStatus(RealtyGroupReportStatus status, Pageable pageable);

@Query("""
    SELECT r FROM RealtyGroupReport r
     WHERE r.realtyGroup.id = :groupId
     ORDER BY r.createdAt DESC
""")
List<RealtyGroupReport> findByGroupId(Long groupId);
```

`ListingSuspensionRepository extends JpaRepository<ListingSuspension, Long>`:
```java
@Query("""
    SELECT ls FROM ListingSuspension ls
     WHERE ls.cause = com.slparcelauctions.backend.auction.monitoring.ListingSuspensionCause.ADMIN_GROUP_BULK
       AND ls.liftedAt IS NULL
       AND ls.cancelledAt IS NULL
       AND ls.suspendedAt < :threshold
""")
List<ListingSuspension> findExpiredBulkSuspends(OffsetDateTime threshold);

@Query("""
    SELECT ls FROM ListingSuspension ls
     JOIN ls.auction a
     LEFT JOIN com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup rsg
            ON rsg.id = a.realtyGroupSlGroupId
     WHERE ls.cause = com.slparcelauctions.backend.auction.monitoring.ListingSuspensionCause.ADMIN_GROUP_BULK
       AND ls.liftedAt IS NULL
       AND ls.cancelledAt IS NULL
       AND (a.realtyGroup.id = :groupId OR rsg.realtyGroup.id = :groupId)
""")
List<ListingSuspension> findActiveBulkSuspensionsForGroup(Long groupId);

List<ListingSuspension> findByAuctionId(Long auctionId);
```

- [ ] **Step 2: Add minimal `@DataJpaTest` for each repo**

Files:
- `backend/src/test/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupSuspensionRepositoryTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportRepositoryTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/ListingSuspensionRepositoryTest.java`

Test happy paths for each custom query. Use `@TestEntityManager` to seed rows.

- [ ] **Step 3: Run + commit**

```bash
./mvnw test -Dtest='RealtyGroup*RepositoryTest,ListingSuspensionRepositoryTest'
git add backend/src/main backend/src/test
git commit -m "feat(realty-f): repositories for moderation + reports + listing suspensions"
```

---

## Task 4: Configuration properties

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupModerationProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml` (or whichever profile disables schedulers in tests)

**Spec references:** §20.2.

- [ ] **Step 1: Write `RealtyGroupModerationProperties`**

```java
@ConfigurationProperties(prefix = "slpa.realty")
@Getter @Setter
public class RealtyGroupModerationProperties {

    private GroupBulkSuspend groupBulkSuspend = new GroupBulkSuspend();
    private SlGroupReverify slGroup = new SlGroupReverify();
    private GroupSuspensionExpiry groupSuspensionExpiry = new GroupSuspensionExpiry();

    @Getter @Setter public static class GroupBulkSuspend {
        private int autoCancelHours = 48;
        private boolean enabled = true;
    }
    @Getter @Setter public static class SlGroupReverify {
        private int reverifyCadenceDays = 30;
        private int reverifyFetchFailureThreshold = 3;
        private Enabled reverify = new Enabled();
        @Getter @Setter public static class Enabled { private boolean enabled = true; }
    }
    @Getter @Setter public static class GroupSuspensionExpiry {
        private boolean enabled = true;
    }
}
```

Register via `@EnableConfigurationProperties(RealtyGroupModerationProperties.class)` on the existing `RealtyConfig` (or wherever realty-package config beans live; if no such class, create `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyConfig.java`).

- [ ] **Step 2: Add defaults to application.yml**

```yaml
slpa:
  realty:
    group-bulk-suspend:
      auto-cancel-hours: 48
      enabled: true
    sl-group:
      reverify-cadence-days: 30
      reverify-fetch-failure-threshold: 3
      reverify:
        enabled: true
    group-suspension-expiry:
      enabled: true
```

In `application-test.yml`, set all `enabled` flags to `false`.

- [ ] **Step 3: Write `RealtyGroupModerationPropertiesTest`**

`@SpringBootTest` confirms defaults; second test overrides via `@TestPropertySource`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main backend/src/test
git commit -m "feat(realty-f): moderation config properties + test/dev overrides"
```

---

## Task 5: Enum additions (AdminActionType, FraudFlagEntityKind, FraudFlagReason, CancellationOffenseKind)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionTypeCheckConstraintInitializer.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/FraudFlagEntityKind.java` (or wherever this enum lives — check via Grep)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagReason.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationOffenseKind.java`

**Spec references:** §4.5, §4.6, §11, §10.2.

- [ ] **Step 1: Add 13 new AdminActionType values**

Append (preserving alphabetical / grouped order — match existing convention):
```
REALTY_GROUP_SUSPEND,
REALTY_GROUP_UNSUSPEND,
REALTY_GROUP_BAN,
REALTY_GROUP_UNBAN,
REALTY_GROUP_FRAUD_FLAG,
REALTY_GROUP_REPORT_RESOLVE,
REALTY_GROUP_REPORT_DISMISS,
REALTY_GROUP_BULK_SUSPEND,
REALTY_GROUP_BULK_REINSTATE,
REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN,
REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER,
REALTY_GROUP_SL_GROUP_DRIFT_ACK,
REALTY_GROUP_SL_GROUP_RECHECK
```

Update `AdminActionTypeCheckConstraintInitializer` if it has a hard-coded list (typically it derives from the enum at runtime — verify).

- [ ] **Step 2: Add `REALTY_GROUP` to FraudFlagEntityKind**

If `FraudFlagEntityKind` already has `USER, LISTING`, add `REALTY_GROUP`. If the enum lives at a different path, find it via `Grep "enum FraudFlagEntityKind"`.

- [ ] **Step 3: Add three new FraudFlagReason values**

```
REALTY_GROUP_FRAUDULENT_LISTINGS,
REALTY_GROUP_IMPERSONATION,
REALTY_GROUP_REPEATED_REPORTS
```

- [ ] **Step 4: Add ADMIN_BULK_EXPIRED to CancellationOffenseKind**

Append the new value. Confirm `countPriorOffensesWithBids` (or equivalent — the existing BROKER_CANCEL exclusion site) excludes ADMIN_BULK_EXPIRED from the penalty ladder.

- [ ] **Step 5: Update enum-values smoke tests**

Touch any existing `*EnumValuesTest` that asserts the size of these enums; update expected counts.

- [ ] **Step 6: Run + commit**

```bash
./mvnw test -Dtest='*EnumValuesTest'
git add backend/src/main backend/src/test
git commit -m "feat(realty-f): enum additions — admin action / fraud / cancellation"
```

---

## Task 6: RealtyGroupSuspensionService

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupSuspensionService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/exception/SuspensionAlreadyActiveException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/exception/SuspensionNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/exception/RealtyGroupSuspendedException.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupSuspensionServiceTest.java`

**Spec references:** §8, §9.

- [ ] **Step 1: Write the failing test (RealtyGroupSuspensionServiceTest)**

Mockito-driven; use `Clock.fixed` for determinism.

Test cases (one method each):
- `issue_happyPath_writesRowAndAuditAndNotifies`
- `issue_whenAnActiveSuspensionExists_throwsSuspensionAlreadyActive`
- `issue_withBulkSuspendListings_invokesBulkListingSuspendService` (mock the bulk service for now; Task 11 implements it)
- `issue_withNullExpiresAt_writesPermanentBanWithAdminActionTypeBan`
- `lift_happyPath_setsLiftedAtAndAdminAndNotifies`
- `lift_whenAlreadyLifted_throwsSuspensionNotFound` (or a similar 409)
- `lift_withBulkReinstateListings_invokesBulkListingSuspendService`
- `findActive_returnsActiveRow_orEmpty`

- [ ] **Step 2: Run test to verify it fails**

`./mvnw test -Dtest=RealtyGroupSuspensionServiceTest` — all should fail (NoSuchMethodError / NullPointerException; service not implemented yet).

- [ ] **Step 3: Implement RealtyGroupSuspensionService**

Skeleton:
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupSuspensionService {
    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupSuspensionRepository suspensionRepo;
    private final AdminActionService adminActionService;
    private final NotificationPublisher notificationPublisher;
    private final BulkListingSuspendService bulkListingSuspendService;  // injected; Task 11 fills in
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Transactional
    public RealtyGroupSuspension issue(
        UUID groupPublicId,
        Long adminUserId,
        SuspensionReason reason,
        String notes,
        OffsetDateTime expiresAt,
        boolean bulkSuspendListings
    ) { ... }

    @Transactional
    public RealtyGroupSuspension lift(
        UUID groupPublicId,
        UUID suspensionPublicId,
        Long adminUserId,
        String liftedNotes,
        boolean bulkReinstateListings
    ) { ... }

    @Transactional(readOnly = true)
    public Optional<RealtyGroupSuspension> findActive(Long groupId) { ... }

    @Transactional(readOnly = true)
    public List<RealtyGroupSuspension> listHistory(UUID groupPublicId) { ... }
}
```

Implementation notes:
- After insert, write to Redis hash `realty_group_suspended:{groupId}` with the `expiresAt` (or `"PERMANENT"` if null). TTL = min(suspension duration, 5 minutes). Used by `RealtyGroupGuard` for short-circuit.
- `AdminActionType.REALTY_GROUP_SUSPEND` for timed; `REALTY_GROUP_BAN` for permanent (expiresAt == null).
- Notification: `notificationPublisher.realtyGroupSuspended(...)` — Task 15 adds this helper if not present; for Task 6, write the helper signature in `NotificationPublisher` interface + a stub in `NotificationPublisherImpl` calling the existing per-user publish primitive.

- [ ] **Step 4: Run test, verify all pass**

`./mvnw test -Dtest=RealtyGroupSuspensionServiceTest`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main backend/src/test
git commit -m "feat(realty-f): RealtyGroupSuspensionService — issue/lift/findActive/listHistory"
```

---

## Task 7: RealtyGroupGuard

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupGuard.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupGuardTest.java`

**Spec references:** §5.2, §5.3.

- [ ] **Step 1: Write the failing test**

Test cases:
- `requireGroupCanOperate_whenNoSuspensionRow_passes`
- `requireGroupCanOperate_whenActiveTimedSuspension_throwsRealtyGroupSuspendedExceptionWithExpiry`
- `requireGroupCanOperate_whenActivePermanentBan_throwsRealtyGroupSuspendedExceptionWithBannedStatus`
- `requireGroupCanOperate_whenLiftedRow_passes`
- `requireGroupCanOperate_usesRedisShortCircuitWhenPresent`
- `requireGroupCanOperate_redisCorruptValue_fallsThroughToDbQuery`

- [ ] **Step 2: Implement**

```java
@Component
@RequiredArgsConstructor
public class RealtyGroupGuard {
    private final RealtyGroupSuspensionRepository suspensionRepo;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public void requireGroupCanOperate(Long groupId) {
        String redisKey = "realty_group_suspended:" + groupId;
        String cached = redis.opsForValue().get(redisKey);
        if (cached != null) {
            // hot path; throw if Redis says suspended
            throwSuspendedFromRedis(cached);
            return;
        }
        // cold path
        OffsetDateTime now = OffsetDateTime.now(clock);
        suspensionRepo.findActiveByGroupId(groupId, now).ifPresent(s -> {
            throw new RealtyGroupSuspendedException(...);
        });
    }
}
```

`throwSuspendedFromRedis` parses the cached value (ISO timestamp or `"PERMANENT"`). Corrupt or unrecognized values → fall through to DB.

- [ ] **Step 3: Run + commit**

---

## Task 8: GroupSuspensionExpiryTask

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/GroupSuspensionExpiryTask.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/moderation/GroupSuspensionExpiryTaskTest.java`

**Spec references:** §9.3.

- [ ] **Step 0: Ensure `SystemUserResolver` exists**

Verified via `Grep` at plan-write time: no `SystemUserResolver` in the codebase today. Create it first:

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/audit/SystemUserResolver.java
package com.slparcelauctions.backend.admin.audit;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SystemUserResolver {
    @Value("${slpa.system.user-id:1}")
    private Long systemUserId;

    private final UserRepository userRepo;

    public User getSystemUser() {
        return userRepo.findById(systemUserId)
            .orElseThrow(() -> new IllegalStateException(
                "System user not seeded (id=" + systemUserId + ")"));
    }
}
```

Add `slpa.system.user-id: 1` to `application.yml` (or whichever user ID maps to the SLPA-system row in the seed data — Grep for an existing seed). Tasks 13 and 27 reuse the same resolver.

- [ ] **Step 1: Write the failing test**

Test cases:
- `runOnce_picksOnlyExpiredUnliftedRows`
- `runOnce_writesLiftedAtEqualsExpiresAt`
- `runOnce_setsLiftedByAdminToSystemUserId`
- `runOnce_doesNotTouchPermanentBans`
- `runOnce_doesNotTouchAlreadyLifted`

- [ ] **Step 2: Implement**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupSuspensionExpiryTask {
    private final RealtyGroupSuspensionRepository suspensionRepo;
    private final SystemUserResolver systemUserResolver;
    private final Clock clock;

    @Scheduled(fixedRate = 60 * 60 * 1000)
    @ConditionalOnProperty(name = "slpa.realty.group-suspension-expiry.enabled", havingValue = "true", matchIfMissing = true)
    @Transactional
    public void runOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RealtyGroupSuspension> expired = suspensionRepo.findExpired(now);
        if (expired.isEmpty()) return;
        User systemUser = systemUserResolver.getSystemUser();
        for (RealtyGroupSuspension s : expired) {
            s.setLiftedAt(s.getExpiresAt());
            s.setLiftedByAdmin(systemUser);
            s.setLiftedNotes("Auto-lifted on expiry");
        }
        log.info("Auto-lifted {} expired group suspensions", expired.size());
    }
}
```

`SystemUserResolver` is an existing component (look for the User lookup that returns the SLPA system user; if missing, create one keyed off a config property `slpa.system.user-id` or by `username = 'system'`).

- [ ] **Step 3: Run + commit**

---

## Task 9: Inject RealtyGroupGuard into C/D/E entry points

**Files (modify):**
- `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java`
- `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
- `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java`
- `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java`

**Spec references:** §5.2.

- [ ] **Step 1: Inject `RealtyGroupGuard` into each service via constructor**

For each service, add a call to `realtyGroupGuard.requireGroupCanOperate(groupId)` at the top of the relevant method bodies:

- `RealtyGroupListingService.createListingForGroup` — first line.
- `RealtyGroupWalletService.withdrawToLeader` and `withdrawToSlGroup` — first line.
- `RealtyGroupMembershipService.invite` / `acceptInvitation` / `removeMember` / `updatePermissions` / `editCommissionRate` — first line.
- `RealtyGroupSlGroupService.register` — first line.

- [ ] **Step 2: Update each service's existing slice test**

For each affected service test, add a new test case asserting that an active suspension blocks the operation (mock `RealtyGroupGuard.requireGroupCanOperate` to throw, verify the operation does not proceed).

- [ ] **Step 3: Add exception handler for `RealtyGroupSuspendedException`**

In `RealtyExceptionHandler` (existing file under `realty/exception/`), add a `@ExceptionHandler(RealtyGroupSuspendedException.class)` method returning a 409 ProblemDetail with body `{ status: "SUSPENDED" | "BANNED", expiresAt: ISO_DATE | null, reason: string }`.

- [ ] **Step 4: Run + commit**

```bash
./mvnw test -Dtest='RealtyGroup*ServiceTest'
git add backend/src/main backend/src/test
git commit -m "feat(realty-f): RealtyGroupGuard wired into C/D/E entry points"
```

---

## Task 10: Admin suspension controller + DTOs

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/AdminRealtyGroupSuspensionController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/dto/AdminSuspensionRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/dto/AdminLiftSuspensionRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/dto/SuspensionDto.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/moderation/AdminRealtyGroupSuspensionControllerSliceTest.java`

**Spec references:** §6.2, §9.

- [ ] **Step 1: Write the controller slice test (`@WebMvcTest`)**

Test cases:
- `postSuspensions_happyPath_returns201WithSuspensionDto`
- `postSuspensions_withoutAdminAuth_returns403`
- `postSuspensions_whenAlreadyActive_returns409`
- `postSuspensions_withBulkSuspendListingsTrue_invokesBulkService`
- `getSuspensions_returns200WithHistory`
- `deleteSuspensions_happyPath_returns204AndUpdatesLiftedAt`
- `deleteSuspensions_whenNotFound_returns404`

- [ ] **Step 2: Implement controller + DTOs**

```java
@RestController
@RequestMapping("/api/v1/admin/realty-groups/{publicId}/suspensions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRealtyGroupSuspensionController {
    private final RealtyGroupSuspensionService service;
    private final SuspensionDtoMapper mapper;

    @GetMapping
    public List<SuspensionDto> list(@PathVariable UUID publicId) { ... }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionDto issue(
        @PathVariable UUID publicId,
        @Valid @RequestBody AdminSuspensionRequest req,
        @AuthenticationPrincipal AuthPrincipal admin
    ) { ... }

    @DeleteMapping("/{suspensionPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lift(
        @PathVariable UUID publicId,
        @PathVariable UUID suspensionPublicId,
        @Valid @RequestBody AdminLiftSuspensionRequest req,
        @AuthenticationPrincipal AuthPrincipal admin
    ) { ... }
}
```

`AdminSuspensionRequest`: `{ reason: SuspensionReason, notes: String (1–1000 chars), expiresAt: OffsetDateTime nullable, bulkSuspendListings: boolean default false }`. Validate `expiresAt > now` if non-null.

`AdminLiftSuspensionRequest`: `{ notes: String (0–1000), bulkReinstateListings: boolean default false }`.

`SuspensionDto`: `{ publicId, reason, notes, issuedAt, expiresAt, liftedAt, liftedNotes, issuedByAdmin: AdminSummaryDto, liftedByAdmin: AdminSummaryDto nullable, status: "ACTIVE_TIMED" | "ACTIVE_PERMANENT" | "LIFTED" | "EXPIRED" }`.

- [ ] **Step 3: Run + commit**

---

## Task 11: BulkListingSuspendService

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/BulkListingSuspendService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/BulkListingSuspendServiceTest.java`

**Spec references:** §10.1, §10.3.

- [ ] **Step 1: Write the failing test**

Test cases:
- `suspendAll_findsActiveListingsByCase1And3_writesListingSuspensionsAndFlipsStatus`
- `suspendAll_skipsAlreadySuspendedListings`
- `suspendAll_skipsNonActiveListings`
- `suspendAll_callsBotMonitorLifecycleOnEach`
- `suspendAll_publishesListingSuspendedNotificationPerSeller`
- `suspendAll_writesAdminActionWithCount`
- `suspendAll_returnsBulkActionIdAndCount`
- `reinstateAll_findsActiveBulkSuspensionsForGroup_callsReinstateOnEach`
- `reinstateAll_writesListingSuspensionLiftedAt`

- [ ] **Step 2: Implement**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkListingSuspendService {
    private final AuctionRepository auctionRepo;
    private final ListingSuspensionRepository listingSuspensionRepo;
    private final RealtyGroupRepository groupRepo;
    private final BotMonitorLifecycleService botMonitorLifecycleService;
    private final NotificationPublisher notificationPublisher;
    private final AdminAuctionService adminAuctionService;
    private final AdminActionService adminActionService;
    private final UserRepository userRepo;
    private final Clock clock;

    public record BulkSuspendResult(UUID bulkActionId, int suspendedCount) {}

    @Transactional
    public BulkSuspendResult suspendAll(
        Long groupId,
        Long adminUserId,
        String reason,
        Long linkedGroupSuspensionId
    ) {
        // 1. Find ACTIVE listings (case-1 OR case-3) for the group.
        List<Auction> targets = auctionRepo.findActiveListingsForGroup(groupId);
        if (targets.isEmpty()) {
            return new BulkSuspendResult(UUID.randomUUID(), 0);
        }
        UUID bulkActionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        User admin = userRepo.findById(adminUserId).orElseThrow();
        int count = 0;
        for (Auction a : targets) {
            if (a.getStatus() != AuctionStatus.ACTIVE) continue;
            a.setStatus(AuctionStatus.SUSPENDED);
            a.setSuspendedAt(now);
            ListingSuspension ls = ListingSuspension.builder()
                .auction(a)
                .cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
                .suspendedByAdmin(admin)
                .groupSuspension(linkedGroupSuspensionId != null
                    ? entityManager.getReference(RealtyGroupSuspension.class, linkedGroupSuspensionId)
                    : null)
                .bulkActionId(bulkActionId)
                .reason(reason)
                .suspendedAt(now)
                .build();
            listingSuspensionRepo.save(ls);
            botMonitorLifecycleService.onAuctionClosed(a);
            notificationPublisher.listingSuspended(
                a.getSeller().getId(),
                a.getId(),
                a.getTitle(),
                "ADMIN_GROUP_BULK_SUSPEND"
            );
            count++;
        }
        adminActionService.recordAction(adminUserId, AdminActionType.REALTY_GROUP_BULK_SUSPEND,
            Map.of("count", count, "bulkActionId", bulkActionId.toString(), "groupId", groupId));
        return new BulkSuspendResult(bulkActionId, count);
    }

    @Transactional
    public int reinstateAll(Long groupId, Long adminUserId, String notes) {
        List<ListingSuspension> active = listingSuspensionRepo.findActiveBulkSuspensionsForGroup(groupId);
        int count = 0;
        for (ListingSuspension ls : active) {
            adminAuctionService.reinstate(ls.getAuction().getId(), Optional.of(ls.getSuspendedAt()));
            ls.setLiftedAt(OffsetDateTime.now(clock));
            count++;
        }
        adminActionService.recordAction(adminUserId, AdminActionType.REALTY_GROUP_BULK_REINSTATE,
            Map.of("count", count, "groupId", groupId));
        return count;
    }
}
```

Add `AuctionRepository.findActiveListingsForGroup(Long groupId)`:
```java
@Query("""
    SELECT a FROM Auction a
     LEFT JOIN com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup rsg
            ON rsg.id = a.realtyGroupSlGroupId
     WHERE a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE
       AND (a.realtyGroup.id = :groupId OR rsg.realtyGroup.id = :groupId)
""")
List<Auction> findActiveListingsForGroup(Long groupId);
```

- [ ] **Step 3: Run + commit**

---

## Task 12: CancellationService.adminCancelExpiredBulkSuspend

**Files (modify + create):**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceTest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/dto/BulkSuspendAutoCancelData.java` (or extend existing notification data builder)

**Spec references:** §10.2.

- [ ] **Step 1: Write the failing test cases inside CancellationServiceTest**

Test cases:
- `adminCancelExpiredBulkSuspend_refundsAllReservedBids`
- `adminCancelExpiredBulkSuspend_setsAuctionStatusCancelled`
- `adminCancelExpiredBulkSuspend_writesCancellationLogWithAdminBulkExpired`
- `adminCancelExpiredBulkSuspend_setsListingSuspensionCancelledAt`
- `adminCancelExpiredBulkSuspend_publishesBidderFanoutAndSellerNotification`
- `adminCancelExpiredBulkSuspend_isIdempotentAcrossDuplicateCalls`

- [ ] **Step 2: Implement**

```java
@Transactional
public void adminCancelExpiredBulkSuspend(Long auctionId, Long listingSuspensionId) {
    Auction auction = auctionRepo.findByIdForUpdate(auctionId)
        .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    if (auction.getStatus() != AuctionStatus.SUSPENDED) {
        log.info("auction {} not SUSPENDED at expiry-cancel time (was {}); skipping", auctionId, auction.getStatus());
        return;  // idempotent
    }
    refundAllReservedBids(auction);   // existing refund pathway
    auction.setStatus(AuctionStatus.CANCELLED);
    writeCancellationLog(auction, CancellationOffenseKind.ADMIN_BULK_EXPIRED, null);
    ListingSuspension ls = listingSuspensionRepo.findById(listingSuspensionId).orElseThrow();
    ls.setCancelledAt(OffsetDateTime.now(clock));
    // Bidder fan-out (cause-neutral).
    notificationPublisher.listingCancelledBySellerFanout(
        auctionId, auction.getActiveBidderUserIds(), auction.getTitle()
    );
    notificationPublisher.listingAutoCancelledFromBulkSuspend(
        auction.getSeller().getId(), auctionId, auction.getTitle()
    );
}
```

Add the `listingAutoCancelledFromBulkSuspend` helper to `NotificationPublisher` interface + impl. Use `NotificationCategory.LISTING_CANCELLED_BY_SELLER` for the body envelope but with `reason = BULK_SUSPEND_TIMER_EXPIRED` in the data builder.

- [ ] **Step 3: Run + commit**

---

## Task 13: BulkSuspendedListingExpiryTask

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/BulkSuspendedListingExpiryTask.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/BulkSuspendedListingExpiryTaskTest.java`

**Spec references:** §10.2.

- [ ] **Step 1: Write the failing test**

Test cases:
- `runOnce_picksOnlyAdminGroupBulkSuspensionsOlderThanThreshold`
- `runOnce_callsAdminCancelExpiredBulkSuspendPerRow`
- `runOnce_setsListingSuspensionCancelledAtViaTheCancellationService`
- `runOnce_writesBatchedAdminActionWithCount`
- `runOnce_isNoOpWhenNoRowsDue`
- `runOnce_doesNotTouchAdminIndividualCauseRows`
- `runOnce_doesNotTouchAutoCauseRows`

- [ ] **Step 2: Implement**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class BulkSuspendedListingExpiryTask {
    private final ListingSuspensionRepository listingSuspensionRepo;
    private final CancellationService cancellationService;
    private final AdminActionService adminActionService;
    private final RealtyGroupModerationProperties props;
    private final SystemUserResolver systemUserResolver;
    private final Clock clock;

    @Scheduled(fixedRate = 60 * 60 * 1000)
    @ConditionalOnProperty(name = "slpa.realty.group-bulk-suspend.enabled", havingValue = "true", matchIfMissing = true)
    public void runOnce() {
        OffsetDateTime threshold = OffsetDateTime.now(clock)
            .minusHours(props.getGroupBulkSuspend().getAutoCancelHours());
        List<ListingSuspension> due = listingSuspensionRepo.findExpiredBulkSuspends(threshold);
        if (due.isEmpty()) return;
        int cancelled = 0;
        for (ListingSuspension ls : due) {
            try {
                cancellationService.adminCancelExpiredBulkSuspend(ls.getAuction().getId(), ls.getId());
                cancelled++;
            } catch (Exception e) {
                log.error("Failed to cancel listing-suspension {}", ls.getId(), e);
            }
        }
        adminActionService.recordSystemAction(
            AdminActionType.REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN,
            Map.of("cancelledCount", cancelled)
        );
    }
}
```

- [ ] **Step 3: Run + commit**

---

## Task 14: Admin bulk listings controller

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/AdminRealtyGroupBulkListingsController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/dto/BulkSuspendListingsRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/dto/BulkReinstateListingsRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/dto/BulkSuspendResultDto.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/moderation/AdminRealtyGroupBulkListingsControllerSliceTest.java`

**Spec references:** §6.3.

- [ ] **Step 1: Write the slice test**

Test cases:
- `postSuspendAll_returns200WithCountAndBulkActionId`
- `postSuspendAll_withoutAdminAuth_returns403`
- `postReinstateAll_returns200WithReinstatedCount`

- [ ] **Step 2: Implement**

```java
@RestController
@RequestMapping("/api/v1/admin/realty-groups/{publicId}/listings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRealtyGroupBulkListingsController {
    private final BulkListingSuspendService service;
    private final RealtyGroupRepository groupRepo;

    @PostMapping("/suspend-all")
    public BulkSuspendResultDto suspendAll(
        @PathVariable UUID publicId,
        @Valid @RequestBody BulkSuspendListingsRequest req,
        @AuthenticationPrincipal AuthPrincipal admin
    ) { ... }

    @PostMapping("/reinstate-all")
    public ReinstateResultDto reinstateAll(
        @PathVariable UUID publicId,
        @Valid @RequestBody BulkReinstateListingsRequest req,
        @AuthenticationPrincipal AuthPrincipal admin
    ) { ... }
}
```

- [ ] **Step 3: Run + commit**

End of Part 1.
