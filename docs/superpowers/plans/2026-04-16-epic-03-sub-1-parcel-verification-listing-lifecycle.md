# Epic 03 Sub-spec 1 — Parcel Verification + Listing Lifecycle (Backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the backend for parcel verification (three methods) + auction listing lifecycle as a fully testable REST API, consumed later by Epic 03 sub-spec 2 frontend.

**Architecture:** Feature-based package layout (`parcel/`, `auction/`, `bot/`, `sl/`, `parceltag/`), JPA entities as schema source of truth (no Flyway migrations), Spring WebClient for World API + Map API, static `MainlandContinents` helper (no Grid Survey dependency), two-DTO model (`SellerAuctionResponse` vs `PublicAuctionResponse`) with type-enforced status collapse, unified `PUT /auctions/{id}/verify` dispatching to Method A (sync) / B (async LSL callback) / C (async bot callback). Parcel locking enforced via service-layer check + Postgres partial unique index. Dev-profile stubs for listing-fee payment and bot completion until Epics 05/06.

**Tech Stack:** Spring Boot 4.0.5, Java 26, Spring Data JPA + Hibernate, Spring WebFlux (WebClient), Jsoup (new — HTML parsing for World API), AWS S3 SDK (MinIO), Thumbnailator (existing — image resize for avatars only), JUnit 5 + Mockito + WireMock + Testcontainers.

**Source spec:** `docs/superpowers/specs/2026-04-16-epic-03-sub-1-parcel-verification-listing-lifecycle.md`

---

## Preflight checks

Before starting Task 1, confirm branch state and dependencies.

- [ ] **Confirm you are on `dev`, fully up to date**

```bash
cd C:/Users/heath/Repos/Personal/slpa
git status
git fetch origin
git log --oneline -3
```

Expected: clean working tree (or ignore `.claude/settings.local.json` and `.superpowers/`), `dev` at `origin/dev`.

- [ ] **Read required docs**

Read `docs/implementation/CONVENTIONS.md` and `docs/implementation/DEFERRED_WORK.md` before starting. Items in `DEFERRED_WORK.md` whose target is Epic 03 / listing creation sub-spec are NOT in this sub-spec's scope — they are Sub-spec 2 (frontend UI) and Sub-spec 3 (ownership monitoring) concerns.

- [ ] **Create the feature branch**

```bash
git checkout -b task/03-sub-1-parcel-verification-listing-lifecycle
git push -u origin task/03-sub-1-parcel-verification-listing-lifecycle
```

- [ ] **Baseline backend test count**

```bash
cd backend && ./mvnw test -q
```

Expected: `BUILD SUCCESS`, approximately 191 tests passing (from the end of Epic 02 sub-spec 2b). Record the exact number for comparison at Task 10.

- [ ] **Verify dev services are up**

```bash
docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "slpa-(postgres|redis|minio)"
```

Expected: all three containers running. If MinIO is missing:

```bash
docker run -d --name slpa-minio \
  -e MINIO_ROOT_USER=slpa-dev-key -e MINIO_ROOT_PASSWORD=slpa-dev-secret \
  -p 9000:9000 -p 9001:9001 \
  minio/minio server /data --console-address ":9001"
```

- [ ] **Correctness notes baked into this plan (do NOT treat as optional):**

  1. **`users.cancelled_with_bids` already exists.** Verified in `backend/src/main/java/.../user/User.java` — `@Column(name = "cancelled_with_bids", nullable = false) private Integer cancelledWithBids = 0;`. Task 1 does NOT add this column; it's already on the entity.
  2. **`VerificationCodeType.PARCEL` already exists.** Verified in `backend/src/main/java/.../verification/VerificationCodeType.java`. No enum change needed in Task 1.
  3. **Deleting `V1__core_tables.sql` and `V2__supporting_tables.sql` requires disabling Flyway.** Flyway otherwise throws "Detected resolved migrations not applied to database" or "Migration version X missing." Task 1 also sets `spring.flyway.enabled: false` globally.
  4. **Existing Postgres `flyway_schema_history` table** persists from prior boots. Harmless once Flyway is disabled; can be dropped manually or left alone.
  5. **Jsoup is not in `pom.xml`.** Task 2 adds the dependency.
  6. **`AvatarImageProcessor` is at `backend/src/main/java/.../user/AvatarImageProcessor.java`** — Task 9 refactors it (extracts validation into a shared `ImageUploadValidator`).
  7. **Existing SL header pattern** — `SlHeaderValidator` + `SlConfigProperties` — is reused for the new Method B LSL callback. No new validation component needed.
  8. **Repackage conflict:** Avoid naming the new package `sl.parcel` — `sl/` already exists and has an `SlParcelVerifyController` addition (Task 7). Keep parcel CRUD under `parcel/` and Method B LSL callback under `sl/`.

---

## File structure

### New packages and files (created by this sub-spec)

```
backend/src/main/java/com/slparcelauctions/backend/
├── parcel/                                                          NEW package
│   ├── Parcel.java                                                  entity (table already exists; +continent_name column)
│   ├── ParcelRepository.java
│   ├── ParcelLookupService.java
│   ├── ParcelController.java
│   └── dto/
│       ├── ParcelLookupRequest.java
│       └── ParcelResponse.java
├── auction/                                                          NEW package
│   ├── Auction.java                                                  entity (table exists; entity maps existing columns)
│   ├── AuctionStatus.java                                           enum
│   ├── AuctionStatusConstants.java                                  LOCKING_STATUSES set
│   ├── VerificationMethod.java                                      enum
│   ├── VerificationTier.java                                        enum
│   ├── AuctionRepository.java
│   ├── AuctionService.java                                          CRUD + transitions
│   ├── AuctionVerificationService.java                              unified /verify dispatch + parcel lock check
│   ├── AuctionDtoMapper.java                                        Seller/Public DTO conversion
│   ├── AuctionController.java                                       REST endpoints
│   ├── CancellationService.java
│   ├── CancellationLog.java                                         NEW entity → NEW table cancellation_logs
│   ├── CancellationLogRepository.java
│   ├── ListingFeeRefund.java                                        NEW entity → NEW table listing_fee_refunds
│   ├── ListingFeeRefundRepository.java
│   ├── RefundStatus.java                                            enum
│   ├── AuctionPhoto.java                                             NEW entity → NEW table auction_photos
│   ├── AuctionPhotoRepository.java
│   ├── AuctionPhotoService.java
│   ├── AuctionPhotoController.java
│   ├── ListingPhotoProcessor.java                                   preserves aspect ratio
│   ├── DevAuctionController.java                                    @Profile("dev"), fee-payment stub
│   ├── scheduled/
│   │   ├── ParcelCodeExpiryJob.java
│   │   └── BotTaskTimeoutJob.java
│   ├── exception/
│   │   ├── InvalidAuctionStateException.java
│   │   ├── AuctionNotFoundException.java
│   │   ├── ParcelAlreadyListedException.java
│   │   ├── PhotoLimitExceededException.java
│   │   └── AuctionExceptionHandler.java                             @RestControllerAdvice per-feature
│   ├── config/
│   │   └── ParcelLockingIndexInitializer.java                        @PostConstruct DDL for partial unique index
│   └── dto/
│       ├── AuctionCreateRequest.java
│       ├── AuctionUpdateRequest.java
│       ├── AuctionCancelRequest.java
│       ├── SellerAuctionResponse.java
│       ├── PublicAuctionResponse.java
│       ├── PublicAuctionStatus.java
│       ├── PendingVerification.java
│       ├── AuctionPhotoResponse.java
│       └── DevPayRequest.java
├── parceltag/                                                        NEW package
│   ├── ParcelTag.java                                               entity (table exists)
│   ├── ParcelTagRepository.java
│   ├── ParcelTagService.java
│   ├── ParcelTagController.java
│   └── dto/
│       ├── ParcelTagResponse.java
│       └── ParcelTagGroupResponse.java
├── bot/                                                              NEW package
│   ├── BotTask.java                                                 NEW entity → NEW table bot_tasks
│   ├── BotTaskType.java
│   ├── BotTaskStatus.java
│   ├── BotTaskRepository.java
│   ├── BotTaskService.java
│   ├── BotTaskController.java                                       production bot endpoints
│   ├── DevBotTaskController.java                                    @Profile("dev") stub
│   └── dto/
│       ├── BotTaskResponse.java
│       └── BotTaskCompleteRequest.java
├── media/                                                            NEW package
│   ├── ImageUploadValidator.java                                    shared (extracted from AvatarImageProcessor)
│   └── ImageFormat.java                                              enum: JPEG, PNG, WEBP
└── sl/                                                               MODIFY (add Method B callback)
    ├── MainlandContinents.java                                      NEW
    ├── SlWorldApiClient.java                                        NEW
    ├── SlWorldApiClientConfig.java                                  NEW (WebClient bean)
    ├── SlMapApiClient.java                                          NEW
    ├── SlMapApiClientConfig.java                                    NEW (WebClient bean)
    ├── SlParcelVerifyController.java                                NEW (Method B LSL callback)
    ├── SlParcelVerifyService.java                                   NEW
    ├── dto/
    │   ├── ParcelMetadata.java                                      NEW (World API result shape)
    │   ├── GridCoordinates.java                                     NEW (Map API result)
    │   └── SlParcelVerifyRequest.java                               NEW
    └── exception/
        ├── ParcelNotFoundInSlException.java                          NEW (404 from World API)
        ├── NotMainlandException.java                                 NEW
        └── ExternalApiTimeoutException.java                          NEW
```

### Modified files (existing)

- `backend/pom.xml` — Task 2 adds Jsoup dependency
- `backend/src/main/resources/application.yml` — Task 1 disables Flyway; Task 2+ adds `slpa.world-api.*`, `slpa.map-api.*`; Task 4 adds `slpa.listing-fee.amount-lindens`, `slpa.commission.default-rate`; Task 7 adds `slpa.verification.parcel-code-expiry-check-interval`; Task 8 adds `slpa.bot-task.*`; Task 9 adds `slpa.photos.*`
- `backend/src/main/resources/application-dev.yml` — Task 1 adds `ddl-auto: update` confirmation; other tasks add config as needed
- `backend/src/main/java/.../verification/VerificationCode.java` — Task 1 adds nullable `auction_id Long` column
- `backend/src/main/java/.../verification/VerificationCodeService.java` — Task 7 adds a PARCEL-code-with-auction-binding method
- `backend/src/main/java/.../user/AvatarImageProcessor.java` — Task 9 refactor: delegate validation to new `ImageUploadValidator`
- `backend/src/main/java/.../user/AvatarService.java` — Task 9 refactor: use new validator surface if call sites change
- `backend/src/main/java/.../common/exception/GlobalExceptionHandler.java` — Task 3 adds handler mappings for `ParcelNotFoundInSlException`, `NotMainlandException`, `ExternalApiTimeoutException`

### Deleted files

- `backend/src/main/resources/db/migration/V1__core_tables.sql` — Task 1
- `backend/src/main/resources/db/migration/V2__supporting_tables.sql` — Task 1

---

## Task 1 — Schema foundation + entity skeletons (no services yet)

**Files:**
- Delete: `backend/src/main/resources/db/migration/V1__core_tables.sql`
- Delete: `backend/src/main/resources/db/migration/V2__supporting_tables.sql`
- Modify: `backend/src/main/resources/application.yml` (disable Flyway)
- Modify: `backend/src/main/java/.../verification/VerificationCode.java` (add auction_id column)
- Modify: `backend/src/main/java/.../parcel/Parcel.java` (new — entity over existing table + continent_name)
- Create: `backend/src/main/java/.../parcel/ParcelRepository.java`
- Create: `backend/src/main/java/.../auction/AuctionStatus.java` (enum)
- Create: `backend/src/main/java/.../auction/AuctionStatusConstants.java`
- Create: `backend/src/main/java/.../auction/VerificationMethod.java` (enum)
- Create: `backend/src/main/java/.../auction/VerificationTier.java` (enum)
- Create: `backend/src/main/java/.../auction/Auction.java`
- Create: `backend/src/main/java/.../auction/AuctionRepository.java`
- Create: `backend/src/main/java/.../auction/CancellationLog.java`
- Create: `backend/src/main/java/.../auction/CancellationLogRepository.java`
- Create: `backend/src/main/java/.../auction/ListingFeeRefund.java`
- Create: `backend/src/main/java/.../auction/ListingFeeRefundRepository.java`
- Create: `backend/src/main/java/.../auction/RefundStatus.java`
- Create: `backend/src/main/java/.../auction/AuctionPhoto.java`
- Create: `backend/src/main/java/.../auction/AuctionPhotoRepository.java`
- Create: `backend/src/main/java/.../auction/config/ParcelLockingIndexInitializer.java`
- Create: `backend/src/main/java/.../bot/BotTaskType.java`
- Create: `backend/src/main/java/.../bot/BotTaskStatus.java`
- Create: `backend/src/main/java/.../bot/BotTask.java`
- Create: `backend/src/main/java/.../bot/BotTaskRepository.java`
- Create: `backend/src/main/java/.../parceltag/ParcelTag.java`
- Create: `backend/src/main/java/.../parceltag/ParcelTagRepository.java`

- [ ] **Step 1.1: Delete Flyway migration files**

```bash
rm backend/src/main/resources/db/migration/V1__core_tables.sql
rm backend/src/main/resources/db/migration/V2__supporting_tables.sql
```

- [ ] **Step 1.2: Disable Flyway globally**

Edit `backend/src/main/resources/application.yml` — change `spring.flyway.enabled` from `true` to `false`:

```yaml
spring:
  application:
    name: slpa-backend
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: update
  flyway:
    enabled: false
```

Note: also change `ddl-auto: validate` → `ddl-auto: update` at the root level (dev profile already overrides to `update`, but prod was `validate` with Flyway). Until we define a production migration strategy, `update` globally is the source-of-truth interpretation of CONVENTIONS.md.

- [ ] **Step 1.3: Confirm Flyway is out of the boot path**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected: app starts successfully. No `FlywayException`. Stop it with Ctrl-C after seeing the "Started BackendApplication" log line. If Flyway complains about `flyway_schema_history`, manually drop the table:

```bash
docker exec -i slpa-postgres psql -U slpa -d slpa -c "DROP TABLE IF EXISTS flyway_schema_history;"
```

- [ ] **Step 1.4: Add auction_id FK column to VerificationCode**

Edit `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCode.java` — add a nullable `auctionId` field. Keep the existing plain-`Long` convention (no `@ManyToOne` per the class's own comment).

```java
// Inside the VerificationCode class, alongside userId:

    @Column(name = "auction_id")  // nullable by default
    private Long auctionId;
```

- [ ] **Step 1.5: Create AuctionStatus enum**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatus.java`:

```java
package com.slparcelauctions.backend.auction;

/**
 * Full internal auction status enum. The four terminal "why-it-ended" states
 * (COMPLETED, CANCELLED, EXPIRED, DISPUTED) collapse to ENDED in
 * {@link com.slparcelauctions.backend.auction.dto.PublicAuctionStatus} when
 * serialized for non-sellers. See spec §6 for the collapse rules.
 */
public enum AuctionStatus {
    DRAFT,
    DRAFT_PAID,
    VERIFICATION_PENDING,
    VERIFICATION_FAILED,
    ACTIVE,
    ENDED,
    ESCROW_PENDING,
    ESCROW_FUNDED,
    TRANSFER_PENDING,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    DISPUTED
}
```

- [ ] **Step 1.6: Create AuctionStatusConstants for the locking set**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatusConstants.java`:

```java
package com.slparcelauctions.backend.auction;

import java.util.Set;

/**
 * Shared constants for the auction state machine. The {@link #LOCKING_STATUSES}
 * set is the authoritative source for both service-layer checks and the
 * Postgres partial unique index DDL in {@link config.ParcelLockingIndexInitializer}.
 */
public final class AuctionStatusConstants {

    /**
     * Statuses that block another auction on the same parcel from transitioning
     * to ACTIVE. See spec §8.3.
     */
    public static final Set<AuctionStatus> LOCKING_STATUSES = Set.of(
            AuctionStatus.ACTIVE,
            AuctionStatus.ENDED,
            AuctionStatus.ESCROW_PENDING,
            AuctionStatus.ESCROW_FUNDED,
            AuctionStatus.TRANSFER_PENDING,
            AuctionStatus.DISPUTED);

    private AuctionStatusConstants() {
        throw new UnsupportedOperationException();
    }
}
```

- [ ] **Step 1.7: Create VerificationMethod and VerificationTier enums**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/VerificationMethod.java`:

```java
package com.slparcelauctions.backend.auction;

public enum VerificationMethod {
    UUID_ENTRY,
    REZZABLE,
    SALE_TO_BOT
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/VerificationTier.java`:

```java
package com.slparcelauctions.backend.auction;

public enum VerificationTier {
    SCRIPT,
    BOT,
    HUMAN
}
```

- [ ] **Step 1.8: Create the Parcel entity**

Create `backend/src/main/java/com/slparcelauctions/backend/parcel/Parcel.java`:

```java
package com.slparcelauctions.backend.parcel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SL parcel row. Shared across any number of auctions — parcels are not
 * 1:1 with drafts. The {@code verified} flag means "metadata was successfully
 * fetched from the SL World API at least once" (not an ownership claim).
 * Ownership lives per-auction on {@code auctions.verification_tier /
 * auctions.verified_at}. See spec §5.1.
 */
@Entity
@Table(name = "parcels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Parcel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sl_parcel_uuid", nullable = false, unique = true)
    private UUID slParcelUuid;

    @Column(name = "owner_uuid")
    private UUID ownerUuid;

    @Column(name = "owner_type", length = 10)
    private String ownerType;   // "agent" or "group"

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(name = "grid_x")
    private Double gridX;

    @Column(name = "grid_y")
    private Double gridY;

    @Column(name = "continent_name", length = 50)
    private String continentName;

    @Column(name = "area_sqm")
    private Integer areaSqm;

    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "position_z")
    private Double positionZ;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "snapshot_url", columnDefinition = "text")
    private String snapshotUrl;

    @Column(name = "layout_map_url", columnDefinition = "text")
    private String layoutMapUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_map_data", columnDefinition = "jsonb")
    private Map<String, Object> layoutMapData;

    @Column(name = "layout_map_at")
    private OffsetDateTime layoutMapAt;

    @Column(length = 100)
    private String location;

    @Column(columnDefinition = "text")
    private String slurl;

    @Column(name = "maturity_rating", length = 10)
    private String maturityRating;  // "PG", "MATURE", "ADULT"

    @Builder.Default
    @Column(nullable = false)
    private Boolean verified = false;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "last_checked")
    private OffsetDateTime lastChecked;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

- [ ] **Step 1.9: Create the ParcelRepository**

Create `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelRepository.java`:

```java
package com.slparcelauctions.backend.parcel;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParcelRepository extends JpaRepository<Parcel, Long> {
    Optional<Parcel> findBySlParcelUuid(UUID slParcelUuid);
}
```

- [ ] **Step 1.10: Create the Auction entity**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`. This entity maps to the existing `auctions` table. Use `@ManyToMany` for the tag join so we don't need an explicit `AuctionTag` join entity (spec §5.1 confirms this choice).

```java
package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "auctions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Auction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parcel_id", nullable = false)
    private Parcel parcel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_agent_id")
    private User listingAgent;

    @Column(name = "realty_group_id")
    private Long realtyGroupId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuctionStatus status = AuctionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_tier", length = 10)
    private VerificationTier verificationTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method", length = 20)
    private VerificationMethod verificationMethod;

    @Column(name = "assigned_bot_uuid")
    private UUID assignedBotUuid;

    @Column(name = "sale_sentinel_price")
    private Long saleSentinelPrice;

    @Column(name = "last_bot_check_at")
    private OffsetDateTime lastBotCheckAt;

    @Builder.Default
    @Column(name = "bot_check_failures", nullable = false)
    private Integer botCheckFailures = 0;

    @Builder.Default
    @Column(name = "listing_fee_paid", nullable = false)
    private Boolean listingFeePaid = false;

    @Column(name = "listing_fee_amt")
    private Long listingFeeAmt;

    @Column(name = "listing_fee_txn", length = 255)
    private String listingFeeTxn;

    @Column(name = "listing_fee_paid_at")
    private OffsetDateTime listingFeePaidAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "verification_notes", columnDefinition = "text")
    private String verificationNotes;

    @Column(name = "starting_bid", nullable = false)
    private Long startingBid;

    @Column(name = "reserve_price")
    private Long reservePrice;

    @Column(name = "buy_now_price")
    private Long buyNowPrice;

    @Builder.Default
    @Column(name = "current_bid", nullable = false)
    private Long currentBid = 0L;

    @Builder.Default
    @Column(name = "bid_count", nullable = false)
    private Integer bidCount = 0;

    @Column(name = "winner_id")
    private Long winnerId;

    @Column(name = "duration_hours", nullable = false)
    private Integer durationHours;

    @Builder.Default
    @Column(name = "snipe_protect", nullable = false)
    private Boolean snipeProtect = false;

    @Column(name = "snipe_window_min")
    private Integer snipeWindowMin;

    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "original_ends_at")
    private OffsetDateTime originalEndsAt;

    @Column(name = "seller_desc", columnDefinition = "text")
    private String sellerDesc;

    @Builder.Default
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate = new BigDecimal("0.0500");

    @Column(name = "commission_amt")
    private Long commissionAmt;

    @Builder.Default
    @Column(name = "agent_fee_rate", precision = 5, scale = 4)
    private BigDecimal agentFeeRate = new BigDecimal("0.0000");

    @Column(name = "agent_fee_amt")
    private Long agentFeeAmt;

    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "auction_tags",
            joinColumns = @JoinColumn(name = "auction_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<ParcelTag> tags = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

- [ ] **Step 1.11: Create AuctionRepository**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java`:

```java
package com.slparcelauctions.backend.auction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    List<Auction> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    /**
     * Parcel-locking check. Used by AuctionVerificationService before every
     * VERIFICATION_PENDING → ACTIVE transition.
     */
    boolean existsByParcelIdAndStatusInAndIdNot(
            Long parcelId, Collection<AuctionStatus> statuses, Long excludeAuctionId);

    /** Used by ParcelCodeExpiryJob to find stuck Method B auctions. */
    List<Auction> findByStatusAndVerificationMethod(
            AuctionStatus status, VerificationMethod verificationMethod);

    Optional<Auction> findByIdAndSellerId(Long id, Long sellerId);
}
```

- [ ] **Step 1.12: Create CancellationLog entity + repository + RefundStatus + ListingFeeRefund + repository**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java`:

```java
package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "cancellation_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @Column(name = "cancelled_from_status", nullable = false, length = 30)
    private String cancelledFromStatus;

    @Builder.Default
    @Column(name = "had_bids", nullable = false)
    private Boolean hadBids = false;

    @Column(length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "cancelled_at", nullable = false, updatable = false)
    private OffsetDateTime cancelledAt;
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java`:

```java
package com.slparcelauctions.backend.auction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationLogRepository extends JpaRepository<CancellationLog, Long> {
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/RefundStatus.java`:

```java
package com.slparcelauctions.backend.auction;

public enum RefundStatus {
    PENDING,
    PROCESSED,
    FAILED
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/ListingFeeRefund.java`:

```java
package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "listing_fee_refunds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingFeeRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "txn_ref", length = 255)
    private String txnRef;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/ListingFeeRefundRepository.java`:

```java
package com.slparcelauctions.backend.auction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingFeeRefundRepository extends JpaRepository<ListingFeeRefund, Long> {
}
```

- [ ] **Step 1.13: Create AuctionPhoto entity + repository**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto.java`:

```java
package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "auction_photos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java`:

```java
package com.slparcelauctions.backend.auction;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionPhotoRepository extends JpaRepository<AuctionPhoto, Long> {

    List<AuctionPhoto> findByAuctionIdOrderBySortOrderAsc(Long auctionId);

    long countByAuctionId(Long auctionId);
}
```

- [ ] **Step 1.14: Create BotTask enums + entity + repository**

Create `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskType.java`:

```java
package com.slparcelauctions.backend.bot;

public enum BotTaskType {
    VERIFY
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskStatus.java`:

```java
package com.slparcelauctions.backend.bot;

public enum BotTaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/bot/BotTask.java`:

```java
package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.auction.Auction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bot_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private BotTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BotTaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(name = "parcel_uuid", nullable = false)
    private UUID parcelUuid;

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(name = "sentinel_price", nullable = false)
    private Long sentinelPrice;

    @Column(name = "assigned_bot_uuid")
    private UUID assignedBotUuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_data", columnDefinition = "jsonb")
    private Map<String, Object> resultData;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @UpdateTimestamp
    @Column(name = "last_updated_at", nullable = false)
    private OffsetDateTime lastUpdatedAt;
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskRepository.java`:

```java
package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BotTaskRepository extends JpaRepository<BotTask, Long> {

    List<BotTask> findByStatusOrderByCreatedAtAsc(BotTaskStatus status);

    List<BotTask> findByStatusAndCreatedAtBefore(BotTaskStatus status, OffsetDateTime threshold);
}
```

- [ ] **Step 1.15: Create ParcelTag entity + repository**

Create `backend/src/main/java/com/slparcelauctions/backend/parceltag/ParcelTag.java`:

```java
package com.slparcelauctions.backend.parceltag;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "parcel_tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParcelTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(columnDefinition = "text")
    private String description;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/parceltag/ParcelTagRepository.java`:

```java
package com.slparcelauctions.backend.parceltag;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParcelTagRepository extends JpaRepository<ParcelTag, Long> {

    List<ParcelTag> findByActiveTrueOrderByCategoryAscSortOrderAsc();

    List<ParcelTag> findByCodeIn(Set<String> codes);
}
```

- [ ] **Step 1.16: Create the parcel-locking partial unique index initializer**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/config/ParcelLockingIndexInitializer.java`:

```java
package com.slparcelauctions.backend.auction.config;

import javax.sql.DataSource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the partial unique index that enforces "at most one auction per parcel
 * can be in a locking status at a time." Runs once per JVM boot via
 * {@link ApplicationReadyEvent} — after JPA has created/validated tables, so the
 * {@code auctions} table is guaranteed to exist. Idempotent (CREATE IF NOT EXISTS).
 *
 * <p>Keeps the DDL alongside the entity package rather than as a Flyway migration
 * per CONVENTIONS.md (entities are the source of truth).
 *
 * <p>LOCKING_STATUSES must match
 * {@link com.slparcelauctions.backend.auction.AuctionStatusConstants#LOCKING_STATUSES}.
 * If one changes, update the other.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParcelLockingIndexInitializer {

    private static final String DDL = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_auctions_parcel_locked_status
              ON auctions(parcel_id)
              WHERE status IN ('ACTIVE', 'ENDED', 'ESCROW_PENDING',
                               'ESCROW_FUNDED', 'TRANSFER_PENDING', 'DISPUTED')
            """;

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void createIndex() {
        new JdbcTemplate(dataSource).execute(DDL);
        log.info("Parcel locking partial unique index ensured (uq_auctions_parcel_locked_status)");
    }
}
```

- [ ] **Step 1.17: Boot the app and verify schema creation**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Expected logs:
- `Hibernate schema update ...`
- `Parcel locking partial unique index ensured (uq_auctions_parcel_locked_status)`
- `Started BackendApplication`

Ctrl-C after confirming. Then verify tables exist:

```bash
docker exec -i slpa-postgres psql -U slpa -d slpa -c "\\d+ auctions" | head -30
docker exec -i slpa-postgres psql -U slpa -d slpa -c "\\dt bot_tasks cancellation_logs listing_fee_refunds auction_photos"
docker exec -i slpa-postgres psql -U slpa -d slpa -c "\\di uq_auctions_parcel_locked_status"
docker exec -i slpa-postgres psql -U slpa -d slpa -c "\\d verification_codes"
```

Expected:
- All four new tables present: `bot_tasks`, `cancellation_logs`, `listing_fee_refunds`, `auction_photos`
- `uq_auctions_parcel_locked_status` index exists as UNIQUE with WHERE clause
- `verification_codes` has the new `auction_id` column
- `parcels` has the new `continent_name` column

- [ ] **Step 1.18: Run existing tests to confirm nothing broke**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS, test count unchanged from baseline (~191). The schema additions must not break any existing tests.

- [ ] **Step 1.19: Commit Task 1**

```bash
cd C:/Users/heath/Repos/Personal/slpa
git add backend/src/main/resources/application.yml
git add backend/src/main/resources/db/migration/
git add backend/src/main/java/com/slparcelauctions/backend/parcel/
git add backend/src/main/java/com/slparcelauctions/backend/auction/
git add backend/src/main/java/com/slparcelauctions/backend/bot/
git add backend/src/main/java/com/slparcelauctions/backend/parceltag/
git add backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCode.java
git commit -m "feat(schema): add parcel, auction, bot_task, and related JPA entities

- Delete V1 and V2 Flyway migrations; entities are now the schema source of truth
- Disable Flyway globally in application.yml
- Add Parcel, Auction, BotTask, CancellationLog, ListingFeeRefund, AuctionPhoto, ParcelTag entities
- Add continent_name column to parcels
- Add nullable auction_id column to verification_codes (used by PARCEL type codes)
- Create partial unique index uq_auctions_parcel_locked_status via ApplicationReadyEvent"
git push
```

---

## Task 2 — World API + Map API + MainlandContinents

**Files:**
- Modify: `backend/pom.xml` (add Jsoup)
- Modify: `backend/src/main/resources/application.yml` (add `slpa.world-api.*`, `slpa.map-api.*`)
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/MainlandContinents.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/ParcelMetadata.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/GridCoordinates.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClient.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClientConfig.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/SlMapApiClient.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/SlMapApiClientConfig.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/exception/ParcelNotFoundInSlException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/exception/NotMainlandException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/exception/ExternalApiTimeoutException.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/sl/MainlandContinentsTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/sl/SlWorldApiClientTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/sl/SlMapApiClientTest.java`

- [ ] **Step 2.1: Add Jsoup and WireMock to pom.xml**

Open `backend/pom.xml`. Add inside `<dependencies>`:

```xml
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
            <version>1.17.2</version>
        </dependency>
        <dependency>
            <groupId>com.github.tomakehurst</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>3.10.0</version>
            <scope>test</scope>
        </dependency>
```

Reload Maven, confirm:

```bash
cd backend && ./mvnw dependency:resolve -q
```

Both deps are needed in this task: Jsoup for World API HTML parsing in `SlWorldApiClient`, WireMock for the unit-level HTTP-client tests in Steps 2.8 and 2.10.

- [ ] **Step 2.2: Add configuration properties**

Edit `backend/src/main/resources/application.yml`. Inside `slpa:`, add:

```yaml
slpa:
  # existing sl:, storage: keys stay
  world-api:
    base-url: https://world.secondlife.com
    timeout-ms: 10000
    retry-attempts: 3
    retry-backoff-ms: 500
  map-api:
    base-url: https://cap.secondlife.com
    cap-uuid: b713fe80-283b-4585-af4d-a3b7d9a32492
    timeout-ms: 5000
```

- [ ] **Step 2.3: Create DTOs for World API and Map API results**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/dto/ParcelMetadata.java`:

```java
package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

/**
 * Typed result of parsing the World API HTML page for a parcel. All fields
 * are best-effort — World API may omit meta tags for some parcels. Callers
 * must handle null.
 */
public record ParcelMetadata(
        UUID parcelUuid,
        UUID ownerUuid,
        String ownerType,       // "agent" or "group"
        String parcelName,
        String regionName,
        Integer areaSqm,
        String description,
        String snapshotUrl,
        String maturityRating,   // "PG", "MATURE", "ADULT"
        Double positionX,
        Double positionY,
        Double positionZ) {
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/sl/dto/GridCoordinates.java`:

```java
package com.slparcelauctions.backend.sl.dto;

public record GridCoordinates(double gridX, double gridY) {
}
```

- [ ] **Step 2.4: Create the SL exception classes**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/exception/ParcelNotFoundInSlException.java`:

```java
package com.slparcelauctions.backend.sl.exception;

import java.util.UUID;

/** World API returned 404 for the given parcel UUID. Maps to HTTP 404. */
public class ParcelNotFoundInSlException extends RuntimeException {

    private final UUID parcelUuid;

    public ParcelNotFoundInSlException(UUID parcelUuid) {
        super("Parcel not found in SL: " + parcelUuid);
        this.parcelUuid = parcelUuid;
    }

    public UUID getParcelUuid() {
        return parcelUuid;
    }
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/sl/exception/NotMainlandException.java`:

```java
package com.slparcelauctions.backend.sl.exception;

/** Parcel's grid coordinates do not fall within any known Mainland continent. Maps to HTTP 422. */
public class NotMainlandException extends RuntimeException {

    private final double gridX;
    private final double gridY;

    public NotMainlandException(double gridX, double gridY) {
        super("Parcel is not on Mainland (grid " + gridX + ", " + gridY + ")");
        this.gridX = gridX;
        this.gridY = gridY;
    }

    public double getGridX() {
        return gridX;
    }

    public double getGridY() {
        return gridY;
    }
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/sl/exception/ExternalApiTimeoutException.java`:

```java
package com.slparcelauctions.backend.sl.exception;

/** External SL API (World, Map) timed out or repeatedly failed. Maps to HTTP 504. */
public class ExternalApiTimeoutException extends RuntimeException {

    private final String api;

    public ExternalApiTimeoutException(String api, String detail) {
        super("SL " + api + " API unavailable: " + detail);
        this.api = api;
    }

    public String getApi() {
        return api;
    }
}
```

- [ ] **Step 2.5: Create MainlandContinents with unit test FIRST (TDD)**

Create test: `backend/src/test/java/com/slparcelauctions/backend/sl/MainlandContinentsTest.java`:

```java
package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainlandContinentsTest {

    @Test
    void sansaraCenter_isMainland() {
        // Sansara box: 254208.0, 265984.0, 250368.0, 259328.0
        assertThat(MainlandContinents.isMainland(260000.0, 254000.0)).isTrue();
        assertThat(MainlandContinents.continentAt(260000.0, 254000.0)).hasValue("Sansara");
    }

    @Test
    void bellisseriaWestCoastCenter_isMainland() {
        // Bellisseria West Coast: 261888.0, 267776.0, 240640.0, 250368.0
        assertThat(MainlandContinents.isMainland(264000.0, 245000.0)).isTrue();
        assertThat(MainlandContinents.continentAt(264000.0, 245000.0)).hasValue("Bellisseria");
    }

    @Test
    void zindraCenter_isMainland() {
        // Zindra: 460032.0, 466432.0, 301824.0, 307456.0
        assertThat(MainlandContinents.isMainland(463000.0, 304000.0)).isTrue();
        assertThat(MainlandContinents.continentAt(463000.0, 304000.0)).hasValue("Zindra");
    }

    @Test
    void horizonsCenter_isMainland() {
        // Horizons: 461824.0, 464384.0, 307456.0, 310016.0
        assertThat(MainlandContinents.isMainland(463000.0, 308500.0)).isTrue();
        assertThat(MainlandContinents.continentAt(463000.0, 308500.0)).hasValue("Horizons");
    }

    @Test
    void farAwayFromAllContinents_isNotMainland() {
        assertThat(MainlandContinents.isMainland(100000.0, 100000.0)).isFalse();
        assertThat(MainlandContinents.continentAt(100000.0, 100000.0)).isEmpty();
    }

    @Test
    void justOutsideSansaraEastBoundary_isNotMainland() {
        // Sansara east edge is 265984.0; just past it
        assertThat(MainlandContinents.isMainland(265984.0, 255000.0)).isFalse();
    }

    @Test
    void sansaraExactWestBoundary_isMainland() {
        // Half-open interval: x1 inclusive, x2 exclusive
        assertThat(MainlandContinents.isMainland(254208.0, 255000.0)).isTrue();
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=MainlandContinentsTest -q`
Expected: FAIL with "Cannot resolve symbol 'MainlandContinents'".

- [ ] **Step 2.6: Create MainlandContinents**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/MainlandContinents.java`:

```java
package com.slparcelauctions.backend.sl;

import java.util.List;
import java.util.Optional;

/**
 * Static Mainland continent bounding boxes from the SL wiki's
 * <a href="https://wiki.secondlife.com/wiki/ContinentDetector">ContinentDetector</a>.
 * Replaces the unreliable Grid Survey API dependency. See spec §7.3.
 *
 * <p>Bounds are half-open: {@code x1 <= x < x2} and {@code y1 <= y < y2}.
 *
 * <p>When Linden Lab adds a new Mainland continent (rare event), add an
 * entry to {@link #BOXES}.
 */
public final class MainlandContinents {

    private record Continent(double x1, double x2, double y1, double y2, String name) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    private static final List<Continent> BOXES = List.of(
            new Continent(261888.0, 267776.0, 240640.0, 250368.0, "Bellisseria"),
            new Continent(267776.0, 281600.0, 243200.0, 258048.0, "Bellisseria"),
            new Continent(296704.0, 302080.0, 252928.0, 256768.0, "Sharp"),
            new Continent(257024.0, 266240.0, 229632.0, 240640.0, "Jeogeot"),
            new Continent(251392.0, 254208.0, 256000.0, 257792.0, "Bay City"),
            new Continent(254208.0, 265984.0, 250368.0, 259328.0, "Sansara"),
            new Continent(253696.0, 259840.0, 259328.0, 265472.0, "Heterocera"),
            new Continent(281344.0, 290560.0, 257280.0, 268288.0, "Satori"),
            new Continent(290048.0, 290816.0, 268288.0, 269824.0, "Western Blake Sea"),
            new Continent(290816.0, 297216.0, 265216.0, 271872.0, "Blake Sea"),
            new Continent(286976.0, 289792.0, 268032.0, 269312.0, "Nautilus City"),
            new Continent(283136.0, 293376.0, 268288.0, 276992.0, "Nautilus"),
            new Continent(281600.0, 296960.0, 276992.0, 281856.0, "Corsica"),
            new Continent(291840.0, 295936.0, 284672.0, 289536.0, "Gaeta I"),
            new Continent(296960.0, 304640.0, 276736.0, 281600.0, "Gaeta V"),
            new Continent(460032.0, 466432.0, 301824.0, 307456.0, "Zindra"),
            new Continent(461824.0, 464384.0, 307456.0, 310016.0, "Horizons"));

    public static Optional<String> continentAt(double gridX, double gridY) {
        return BOXES.stream()
                .filter(c -> c.contains(gridX, gridY))
                .map(Continent::name)
                .findFirst();
    }

    public static boolean isMainland(double gridX, double gridY) {
        return continentAt(gridX, gridY).isPresent();
    }

    private MainlandContinents() {}
}
```

Run: `cd backend && ./mvnw test -Dtest=MainlandContinentsTest -q`
Expected: PASS (7 tests).

- [ ] **Step 2.7: Create SlWorldApiClientConfig (WebClient bean)**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClientConfig.java`:

```java
package com.slparcelauctions.backend.sl;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

@Configuration
public class SlWorldApiClientConfig {

    @Bean
    public WebClient slWorldApiWebClient(
            @Value("${slpa.world-api.base-url}") String baseUrl,
            @Value("${slpa.world-api.timeout-ms}") int timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "SLPA-Backend/1.0")
                .defaultHeader(HttpHeaders.ACCEPT, "text/html")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
```

- [ ] **Step 2.8: Write SlWorldApiClientTest (WireMock)**

Create `backend/src/test/java/com/slparcelauctions/backend/sl/SlWorldApiClientTest.java`:

```java
package com.slparcelauctions.backend.sl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;

class SlWorldApiClientTest {

    private static WireMockServer wireMock;
    private SlWorldApiClient client;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    private SlWorldApiClient newClient() {
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + wireMock.port()).build();
        return new SlWorldApiClient(webClient, 3, 100);
    }

    @Test
    void fetchParcel_validHtml_parsesMetadata() {
        UUID parcelUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ownerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String html = """
                <html><head>
                <meta property="og:title" content="Sunset Bay">
                <meta property="og:description" content="Waterfront parcel">
                <meta property="og:image" content="http://example.com/snap.jpg">
                <meta name="secondlife:region" content="Coniston">
                <meta name="secondlife:parcelid" content="%s">
                <meta name="ownerid" content="%s">
                <meta name="ownertype" content="agent">
                <meta name="area" content="1024">
                <meta name="maturityrating" content="MATURE">
                </head><body></body></html>
                """.formatted(parcelUuid, ownerUuid);
        stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        ParcelMetadata result = client.fetchParcel(parcelUuid).block();

        assertThat(result).isNotNull();
        assertThat(result.parcelName()).isEqualTo("Sunset Bay");
        assertThat(result.regionName()).isEqualTo("Coniston");
        assertThat(result.ownerUuid()).isEqualTo(ownerUuid);
        assertThat(result.ownerType()).isEqualTo("agent");
        assertThat(result.areaSqm()).isEqualTo(1024);
        assertThat(result.maturityRating()).isEqualTo("MATURE");
        assertThat(result.snapshotUrl()).isEqualTo("http://example.com/snap.jpg");
    }

    @Test
    void fetchParcel_404_throwsParcelNotFound() {
        UUID parcelUuid = UUID.randomUUID();
        stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(404)));

        client = newClient();
        assertThatThrownBy(() -> client.fetchParcel(parcelUuid).block())
                .isInstanceOf(ParcelNotFoundInSlException.class);
    }

    @Test
    void fetchParcel_500sRepeatedly_throwsTimeoutAfterRetries() {
        UUID parcelUuid = UUID.randomUUID();
        stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(503)));

        client = newClient();
        assertThatThrownBy(() -> client.fetchParcel(parcelUuid).block())
                .isInstanceOf(ExternalApiTimeoutException.class);
    }
}
```

Run the test — expect FAIL with "Cannot resolve symbol 'SlWorldApiClient'". WireMock was added in Step 2.1 so the test class compiles; it fails only on the missing implementation class.

- [ ] **Step 2.9: Create SlWorldApiClient**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClient.java`:

```java
package com.slparcelauctions.backend.sl;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Fetches parcel metadata HTML from {@code world.secondlife.com/place/{uuid}}
 * and parses meta tags with Jsoup. Unofficial API — retry 5xx with backoff,
 * fail fast on 404 (parcel does not exist), fail-hard on exhaustion.
 */
@Component
@Slf4j
public class SlWorldApiClient {

    private final WebClient webClient;
    private final int retryAttempts;
    private final long retryBackoffMs;

    @Autowired
    public SlWorldApiClient(
            @Qualifier("slWorldApiWebClient") WebClient webClient,
            @Value("${slpa.world-api.retry-attempts:3}") int retryAttempts,
            @Value("${slpa.world-api.retry-backoff-ms:500}") long retryBackoffMs) {
        this.webClient = webClient;
        this.retryAttempts = retryAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    public Mono<ParcelMetadata> fetchParcel(UUID parcelUuid) {
        return webClient.get()
                .uri("/place/{uuid}", parcelUuid)
                .retrieve()
                .onStatus(HttpStatus.NOT_FOUND::equals,
                        r -> Mono.error(new ParcelNotFoundInSlException(parcelUuid)))
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryBackoffMs))
                        .filter(this::isTransient))
                .onErrorMap(
                        throwable -> !(throwable instanceof ParcelNotFoundInSlException),
                        throwable -> new ExternalApiTimeoutException("World", throwable.getMessage()))
                .map(html -> parseHtml(parcelUuid, html));
    }

    private boolean isTransient(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }
        // Network-level exceptions (connect timeout, read timeout) are also transient
        return true;
    }

    private ParcelMetadata parseHtml(UUID parcelUuid, String html) {
        Document doc = Jsoup.parse(html);
        return new ParcelMetadata(
                parcelUuid,
                optionalUuid(meta(doc, "name", "ownerid")),
                meta(doc, "name", "ownertype"),
                meta(doc, "property", "og:title"),
                meta(doc, "name", "secondlife:region"),
                optionalInt(meta(doc, "name", "area")),
                meta(doc, "property", "og:description"),
                meta(doc, "property", "og:image"),
                meta(doc, "name", "maturityrating"),
                optionalDouble(meta(doc, "name", "position_x")),
                optionalDouble(meta(doc, "name", "position_y")),
                optionalDouble(meta(doc, "name", "position_z")));
    }

    private String meta(Document doc, String attr, String value) {
        Element e = doc.selectFirst("meta[" + attr + "=" + value + "]");
        return e != null ? e.attr("content") : null;
    }

    private UUID optionalUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            log.warn("World API returned unparseable UUID: {}", s);
            return null;
        }
    }

    private Integer optionalInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double optionalDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=SlWorldApiClientTest -q`
Expected: PASS (3 tests).

- [ ] **Step 2.10: Create SlMapApiClient + SlMapApiClientConfig + test**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/SlMapApiClientConfig.java`:

```java
package com.slparcelauctions.backend.sl;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

@Configuration
public class SlMapApiClientConfig {

    @Bean
    public WebClient slMapApiWebClient(
            @Value("${slpa.map-api.base-url}") String baseUrl,
            @Value("${slpa.map-api.timeout-ms}") int timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
```

Create test: `backend/src/test/java/com/slparcelauctions/backend/sl/SlMapApiClientTest.java`:

```java
package com.slparcelauctions.backend.sl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;

class SlMapApiClientTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private SlMapApiClient newClient() {
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + wireMock.port()).build();
        return new SlMapApiClient(webClient, "b713fe80-283b-4585-af4d-a3b7d9a32492");
    }

    @Test
    void resolveRegion_validResponse_parsesCoordinates() {
        stubFor(post(urlPathMatching("/cap/0/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/javascript")
                        .withBody("var coords = new Array();\ncoords[0] = 260000;\ncoords[1] = 254000;\n")));

        GridCoordinates result = newClient().resolveRegion("Coniston").block();
        assertThat(result).isNotNull();
        assertThat(result.gridX()).isEqualTo(260000.0);
        assertThat(result.gridY()).isEqualTo(254000.0);
    }

    @Test
    void resolveRegion_emptyResponse_throwsTimeout() {
        stubFor(post(urlPathMatching("/cap/0/.*"))
                .willReturn(aResponse().withStatus(200).withBody("var coords = new Array();\n")));

        assertThatThrownBy(() -> newClient().resolveRegion("NoSuchRegion").block())
                .isInstanceOf(ExternalApiTimeoutException.class);
    }
}
```

Run: FAIL (`SlMapApiClient` missing).

Create `backend/src/main/java/com/slparcelauctions/backend/sl/SlMapApiClient.java`:

```java
package com.slparcelauctions.backend.sl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Resolves a region name to (grid_x, grid_y) via the SL Map CAP endpoint.
 * Response is JavaScript-ish: {@code coords[0] = x; coords[1] = y;}.
 */
@Component
@Slf4j
public class SlMapApiClient {

    private static final Pattern COORD_PATTERN = Pattern.compile(
            "coords\\[(\\d+)\\]\\s*=\\s*([\\d.]+)");

    private final WebClient webClient;
    private final String capUuid;

    @Autowired
    public SlMapApiClient(
            @Qualifier("slMapApiWebClient") WebClient webClient,
            @Value("${slpa.map-api.cap-uuid}") String capUuid) {
        this.webClient = webClient;
        this.capUuid = capUuid;
    }

    public Mono<GridCoordinates> resolveRegion(String regionName) {
        String body = "var=" + regionName.replace(" ", "%20");
        return webClient.post()
                .uri("/cap/0/{cap}", capUuid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parse)
                .onErrorMap(
                        throwable -> !(throwable instanceof ExternalApiTimeoutException),
                        throwable -> new ExternalApiTimeoutException("Map", throwable.getMessage()));
    }

    private GridCoordinates parse(String response) {
        Double x = null;
        Double y = null;
        Matcher m = COORD_PATTERN.matcher(response);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            double val = Double.parseDouble(m.group(2));
            if (idx == 0) x = val;
            if (idx == 1) y = val;
        }
        if (x == null || y == null) {
            throw new ExternalApiTimeoutException("Map", "Response missing coords[0]/coords[1]");
        }
        return new GridCoordinates(x, y);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=SlMapApiClientTest -q`
Expected: PASS (2 tests).

- [ ] **Step 2.11: Run full test suite to verify nothing broke**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS, baseline +~12 tests (~203).

- [ ] **Step 2.12: Commit Task 2**

```bash
git add backend/pom.xml
git add backend/src/main/resources/application.yml
git add backend/src/main/java/com/slparcelauctions/backend/sl/
git add backend/src/test/java/com/slparcelauctions/backend/sl/MainlandContinentsTest.java
git add backend/src/test/java/com/slparcelauctions/backend/sl/SlWorldApiClientTest.java
git add backend/src/test/java/com/slparcelauctions/backend/sl/SlMapApiClientTest.java
git commit -m "feat(sl): add World API + Map API clients and MainlandContinents check

- Jsoup dependency for World API HTML parsing
- WireMock-backed unit tests for both clients
- Static continent bounding-box check replaces Grid Survey dependency
- Exception mappings: ParcelNotFoundInSlException, NotMainlandException, ExternalApiTimeoutException"
git push
```

---

## Task 3 — Parcel lookup endpoint

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/parcel/dto/ParcelLookupRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/parcel/dto/ParcelResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelLookupService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java` (add mappings for 3 new SL exceptions)
- Create: `backend/src/test/java/com/slparcelauctions/backend/parcel/ParcelLookupServiceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/parcel/ParcelControllerIntegrationTest.java`

- [ ] **Step 3.1: Create ParcelLookupRequest DTO**

Create `backend/src/main/java/com/slparcelauctions/backend/parcel/dto/ParcelLookupRequest.java`:

```java
package com.slparcelauctions.backend.parcel.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record ParcelLookupRequest(
        @NotNull UUID slParcelUuid) {
}
```

- [ ] **Step 3.2: Create ParcelResponse DTO**

Create `backend/src/main/java/com/slparcelauctions/backend/parcel/dto/ParcelResponse.java`:

```java
package com.slparcelauctions.backend.parcel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.parcel.Parcel;

public record ParcelResponse(
        Long id,
        UUID slParcelUuid,
        UUID ownerUuid,
        String ownerType,
        String regionName,
        Double gridX,
        Double gridY,
        String continentName,
        Integer areaSqm,
        String description,
        String snapshotUrl,
        String slurl,
        String maturityRating,
        Boolean verified,
        OffsetDateTime verifiedAt,
        OffsetDateTime lastChecked,
        OffsetDateTime createdAt) {

    public static ParcelResponse from(Parcel p) {
        return new ParcelResponse(
                p.getId(), p.getSlParcelUuid(), p.getOwnerUuid(), p.getOwnerType(),
                p.getRegionName(), p.getGridX(), p.getGridY(), p.getContinentName(),
                p.getAreaSqm(), p.getDescription(), p.getSnapshotUrl(), p.getSlurl(),
                p.getMaturityRating(), p.getVerified(), p.getVerifiedAt(),
                p.getLastChecked(), p.getCreatedAt());
    }
}
```

- [ ] **Step 3.3: Write ParcelLookupServiceTest (mocked clients)**

Create `backend/src/test/java/com/slparcelauctions/backend/parcel/ParcelLookupServiceTest.java`:

```java
package com.slparcelauctions.backend.parcel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.NotMainlandException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;

import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ParcelLookupServiceTest {

    @Mock SlWorldApiClient worldApi;
    @Mock SlMapApiClient mapApi;
    @Mock ParcelRepository repo;

    ParcelLookupService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        service = new ParcelLookupService(worldApi, mapApi, repo, fixed);
    }

    @Test
    void lookup_newUuidOnMainland_createsParcelAndReturnsResponse() {
        UUID parcelUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ownerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent", "Sunset Bay", "Coniston",
                1024, "Waterfront", "http://example.com/snap.jpg", "MATURE",
                128.0, 64.0, 22.0)));
        when(mapApi.resolveRegion("Coniston")).thenReturn(Mono.just(new GridCoordinates(260000.0, 254000.0)));
        when(repo.save(any(Parcel.class))).thenAnswer(inv -> {
            Parcel p = inv.getArgument(0);
            p.setId(42L);
            return p;
        });

        ParcelResponse result = service.lookup(parcelUuid);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.regionName()).isEqualTo("Coniston");
        assertThat(result.continentName()).isEqualTo("Sansara");
        assertThat(result.slurl()).contains("Coniston").contains("128").contains("64");
        assertThat(result.verified()).isTrue();
    }

    @Test
    void lookup_existingUuid_shortCircuitsNoExternalCalls() {
        UUID parcelUuid = UUID.randomUUID();
        Parcel existing = Parcel.builder()
                .id(99L).slParcelUuid(parcelUuid).regionName("Sansara").build();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.of(existing));

        ParcelResponse result = service.lookup(parcelUuid);

        assertThat(result.id()).isEqualTo(99L);
        verify(worldApi, never()).fetchParcel(any());
        verify(mapApi, never()).resolveRegion(any());
    }

    @Test
    void lookup_worldApi404_propagates() {
        UUID parcelUuid = UUID.randomUUID();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchParcel(parcelUuid))
                .thenReturn(Mono.error(new ParcelNotFoundInSlException(parcelUuid)));

        assertThatThrownBy(() -> service.lookup(parcelUuid))
                .isInstanceOf(ParcelNotFoundInSlException.class);
    }

    @Test
    void lookup_nonMainlandCoords_throwsNotMainland() {
        UUID parcelUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent", "X", "SomeEstate", 1024, null, null, "PG",
                128.0, 128.0, 22.0)));
        // Coords outside every Mainland bounding box
        when(mapApi.resolveRegion("SomeEstate")).thenReturn(Mono.just(new GridCoordinates(100000.0, 100000.0)));

        assertThatThrownBy(() -> service.lookup(parcelUuid))
                .isInstanceOf(NotMainlandException.class);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=ParcelLookupServiceTest -q`
Expected: FAIL ("Cannot resolve symbol 'ParcelLookupService'").

- [ ] **Step 3.4: Create ParcelLookupService**

Create `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelLookupService.java`:

```java
package com.slparcelauctions.backend.parcel;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.sl.MainlandContinents;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.NotMainlandException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Looks up a parcel by UUID, fetching World API + Map API metadata on first
 * sight and caching into the {@code parcels} table. Shared row — multiple
 * auctions may reference the same parcel. No ownership check here; ownership
 * is per-auction and runs at {@code /verify} time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelLookupService {

    private final SlWorldApiClient worldApi;
    private final SlMapApiClient mapApi;
    private final ParcelRepository repo;
    private final Clock clock;

    @Transactional
    public ParcelResponse lookup(UUID slParcelUuid) {
        Optional<Parcel> existing = repo.findBySlParcelUuid(slParcelUuid);
        if (existing.isPresent()) {
            return ParcelResponse.from(existing.get());
        }

        ParcelMetadata meta = worldApi.fetchParcel(slParcelUuid).block();
        GridCoordinates coords = mapApi.resolveRegion(meta.regionName()).block();

        String continent = MainlandContinents.continentAt(coords.gridX(), coords.gridY())
                .orElseThrow(() -> new NotMainlandException(coords.gridX(), coords.gridY()));

        OffsetDateTime now = OffsetDateTime.now(clock);
        Parcel parcel = Parcel.builder()
                .slParcelUuid(slParcelUuid)
                .ownerUuid(meta.ownerUuid())
                .ownerType(meta.ownerType())
                .regionName(meta.regionName())
                .gridX(coords.gridX())
                .gridY(coords.gridY())
                .continentName(continent)
                .areaSqm(meta.areaSqm())
                .description(meta.description())
                .snapshotUrl(meta.snapshotUrl())
                .maturityRating(meta.maturityRating())
                .positionX(meta.positionX())
                .positionY(meta.positionY())
                .positionZ(meta.positionZ())
                .slurl(buildSlurl(meta.regionName(), meta.positionX(), meta.positionY(), meta.positionZ()))
                .verified(true)
                .verifiedAt(now)
                .lastChecked(now)
                .build();
        parcel = repo.save(parcel);
        log.info("Parcel row created: id={}, uuid={}, region={}, continent={}",
                parcel.getId(), slParcelUuid, meta.regionName(), continent);
        return ParcelResponse.from(parcel);
    }

    private String buildSlurl(String regionName, Double x, Double y, Double z) {
        String encoded = URLEncoder.encode(regionName == null ? "" : regionName, StandardCharsets.UTF_8);
        return "https://maps.secondlife.com/secondlife/" + encoded
                + "/" + (x == null ? 128 : x.intValue())
                + "/" + (y == null ? 128 : y.intValue())
                + "/" + (z == null ? 22 : z.intValue());
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=ParcelLookupServiceTest -q`
Expected: PASS (4 tests).

- [ ] **Step 3.5: Add exception mappings to GlobalExceptionHandler**

Edit `backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java`. Add three new handler methods at the bottom of the class (before the closing brace):

```java
    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException.class)
    public org.springframework.http.ProblemDetail handleParcelNotFoundInSl(
            com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException e,
            jakarta.servlet.http.HttpServletRequest req) {
        org.springframework.http.ProblemDetail pd = org.springframework.http.ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Parcel does not exist in Second Life or has been deleted.");
        pd.setTitle("Parcel Not Found");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_PARCEL_NOT_FOUND");
        pd.setProperty("slParcelUuid", e.getParcelUuid().toString());
        return pd;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.slparcelauctions.backend.sl.exception.NotMainlandException.class)
    public org.springframework.http.ProblemDetail handleNotMainland(
            com.slparcelauctions.backend.sl.exception.NotMainlandException e,
            jakarta.servlet.http.HttpServletRequest req) {
        org.springframework.http.ProblemDetail pd = org.springframework.http.ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "Only Mainland parcels are supported at this time.");
        pd.setTitle("Not Mainland");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        pd.setProperty("code", "NOT_MAINLAND");
        pd.setProperty("gridX", e.getGridX());
        pd.setProperty("gridY", e.getGridY());
        return pd;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException.class)
    public org.springframework.http.ProblemDetail handleExternalApiTimeout(
            com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException e,
            jakarta.servlet.http.HttpServletRequest req) {
        org.springframework.http.ProblemDetail pd = org.springframework.http.ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                "An external Second Life service is unreachable. Please try again.");
        pd.setTitle("External API Timeout");
        pd.setInstance(java.net.URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_API_TIMEOUT");
        pd.setProperty("api", e.getApi());
        return pd;
    }
```

(Prefer using proper imports at the top of the file rather than fully-qualified names — but the fully-qualified form keeps this plan step self-contained. Add imports and simplify in the actual edit.)

- [ ] **Step 3.6: Create ParcelController**

Create `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelController.java`:

```java
package com.slparcelauctions.backend.parcel;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.parcel.dto.ParcelLookupRequest;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/parcels")
@RequiredArgsConstructor
public class ParcelController {

    private final ParcelLookupService service;

    @PostMapping("/lookup")
    @PreAuthorize("@verifiedUserGuard.isVerified(authentication)")
    public ParcelResponse lookup(@Valid @RequestBody ParcelLookupRequest body) {
        return service.lookup(body.slParcelUuid());
    }
}
```

Note: `@verifiedUserGuard.isVerified` is a placeholder. If the codebase already has a verified-user guard pattern, use it. Otherwise, the simpler alternative is to read the authenticated user from `SecurityContextHolder` inside the service and throw if `user.getVerified() != true`. Inspect `backend/src/main/java/.../config/SecurityConfig.java` to see the existing authentication wiring (JWT-based) and choose the correct mechanism. If no `@verifiedUserGuard` bean exists, gate by checking the authenticated user's verified flag in the service method.

- [ ] **Step 3.7: Write ParcelControllerIntegrationTest**

Create `backend/src/test/java/com/slparcelauctions/backend/parcel/ParcelControllerIntegrationTest.java`. Use the existing integration-test pattern from `SlVerificationFlowIntegrationTest` (read that file first to copy the `@SpringBootTest` + `@AutoConfigureMockMvc` + test-authentication scaffolding). Tests:

- Lookup with valid Mainland UUID (mock World/Map clients via `@MockBean`) → 200, `ParcelResponse` returned, parcel row persisted.
- Same UUID twice → second call is a cache hit (verify with `Mockito.verify(worldApi, times(1))` via an @SpyBean — or simply confirm only one row exists in `parcels` table).
- Non-Mainland coords → 422 with problem detail.
- Unparseable UUID → 400 (Jackson rejects at deserialization).
- Unauthenticated → 401.
- Authenticated but user.verified = false → 403.

Run: `cd backend && ./mvnw test -Dtest=ParcelControllerIntegrationTest -q`
Expected: PASS (6 tests).

- [ ] **Step 3.8: Postman — add `Parcel / Lookup` request**

Add request to Postman collection in the `SLPA / Parcel & Listings` folder. Capture response `id` to a `{{parcelId}}` collection variable. See `docs/implementation/epic-02` Postman capture scripts for the JS pattern.

- [ ] **Step 3.9: Run full test suite**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS, ~213 tests.

- [ ] **Step 3.10: Commit Task 3**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/parcel/
git add backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java
git add backend/src/test/java/com/slparcelauctions/backend/parcel/
git commit -m "feat(parcel): add POST /api/v1/parcels/lookup with World+Map orchestration

- Shared parcel row (one per UUID) created on first lookup, cached on subsequent calls
- Mainland continent check via MainlandContinents (no Grid Survey)
- Exception mappings: ParcelNotFoundInSlException→404, NotMainlandException→422, ExternalApiTimeoutException→504
- SLURL synthesized from region name + parcel position"
git push
```

---

## Task 4 — Auction CRUD + state machine + DTOs + cancellation

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCreateRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionUpdateRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCancelRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionStatus.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PendingVerification.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionPhotoResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/InvalidAuctionStateException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java`
- Modify: `backend/src/main/resources/application.yml` (add `slpa.commission.default-rate`, `slpa.listing-fee.amount-lindens`)
- Create: test files (see steps)

- [ ] **Step 4.1: Add config properties**

Edit `backend/src/main/resources/application.yml`. Inside `slpa:`, add:

```yaml
slpa:
  # (existing keys preserved)
  listing-fee:
    amount-lindens: 100
  commission:
    default-rate: 0.05
```

- [ ] **Step 4.2: Create request DTOs**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCreateRequest.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import java.util.Set;

import com.slparcelauctions.backend.auction.VerificationMethod;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/auctions.
 * Validation rules enforced via JSR-380 + extra checks in AuctionService
 * (reserve >= starting, buy_now >= max, etc.).
 */
public record AuctionCreateRequest(
        @NotNull Long parcelId,
        @NotNull VerificationMethod verificationMethod,
        @NotNull @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        @NotNull Integer durationHours,          // validated to be in {24,48,72,168,336}
        @NotNull Boolean snipeProtect,
        Integer snipeWindowMin,                    // required iff snipeProtect
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags) {       // parcel_tag codes
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionUpdateRequest.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import java.util.Set;

import com.slparcelauctions.backend.auction.VerificationMethod;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * All fields optional. Omitted fields leave the auction unchanged.
 * parcelId is intentionally not editable — use a fresh draft instead.
 */
public record AuctionUpdateRequest(
        VerificationMethod verificationMethod,
        @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags) {
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCancelRequest.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import jakarta.validation.constraints.Size;

public record AuctionCancelRequest(
        @Size(max = 500) String reason) {
}
```

- [ ] **Step 4.3: Create response DTOs**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionStatus.java`:

```java
package com.slparcelauctions.backend.auction.dto;

/**
 * Reduced status enum for the public view. The four terminal statuses
 * (COMPLETED, CANCELLED, EXPIRED, DISPUTED) collapse to ENDED here — the
 * public must not be able to infer why an auction ended. Enforced at the
 * type level: AuctionDtoMapper.toPublicStatus exhaustively maps from the
 * internal enum, and PublicAuctionResponse.status is typed to this enum,
 * so a serialization bug cannot leak a terminal status.
 */
public enum PublicAuctionStatus {
    ACTIVE,
    ENDED
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PendingVerification.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.VerificationMethod;

/**
 * Populated on SellerAuctionResponse when status == VERIFICATION_PENDING.
 * Per method: REZZABLE has {code, codeExpiresAt}; SALE_TO_BOT has
 * {botTaskId, instructions}; UUID_ENTRY has none (Method A is synchronous,
 * the response never carries this object populated — it transitions directly
 * to ACTIVE or VERIFICATION_FAILED).
 */
public record PendingVerification(
        VerificationMethod method,
        String code,
        OffsetDateTime codeExpiresAt,
        Long botTaskId,
        String instructions) {
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionPhotoResponse.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionPhoto;

public record AuctionPhotoResponse(
        Long id,
        String url,
        String contentType,
        Long sizeBytes,
        Integer sortOrder,
        OffsetDateTime uploadedAt) {

    public static AuctionPhotoResponse from(AuctionPhoto p) {
        String url = "/api/v1/auctions/" + p.getAuction().getId() + "/photos/" + p.getId() + "/bytes";
        return new AuctionPhotoResponse(p.getId(), url, p.getContentType(),
                p.getSizeBytes(), p.getSortOrder(), p.getUploadedAt());
    }
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

public record SellerAuctionResponse(
        Long id,
        Long sellerId,
        ParcelResponse parcel,
        AuctionStatus status,
        VerificationMethod verificationMethod,
        VerificationTier verificationTier,
        PendingVerification pendingVerification,
        String verificationNotes,
        Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        Long currentBid,
        Integer bidCount,
        Long winnerId,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime originalEndsAt,
        String sellerDesc,
        List<ParcelTagResponse> tags,
        List<AuctionPhotoResponse> photos,
        Boolean listingFeePaid,
        Long listingFeeAmt,
        String listingFeeTxn,
        OffsetDateTime listingFeePaidAt,
        BigDecimal commissionRate,
        Long commissionAmt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionResponse.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

/**
 * Public view of an auction. Notably excludes: winner_id, reservePrice (exposes only
 * hasReserve + reserveMet), listing fee fields, verification_notes, commission fields,
 * agent fee fields, assigned_bot_uuid, sale_sentinel_price, last_bot_check_at,
 * bot_check_failures, pendingVerification, seller's internal verification_method.
 */
public record PublicAuctionResponse(
        Long id,
        Long sellerId,
        ParcelResponse parcel,
        PublicAuctionStatus status,
        VerificationTier verificationTier,
        Long startingBid,
        Boolean hasReserve,
        Boolean reserveMet,
        Long buyNowPrice,
        Long currentBid,
        Integer bidCount,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime originalEndsAt,
        String sellerDesc,
        List<ParcelTagResponse> tags,
        List<AuctionPhotoResponse> photos) {
}
```

- [ ] **Step 4.4: Create ParcelTagResponse shim**

Tag response DTO is needed by both mappers. Create it now (full `ParcelTagService` is Task 9).

Create `backend/src/main/java/com/slparcelauctions/backend/parceltag/dto/ParcelTagResponse.java`:

```java
package com.slparcelauctions.backend.parceltag.dto;

import com.slparcelauctions.backend.parceltag.ParcelTag;

public record ParcelTagResponse(
        String code,
        String label,
        String category,
        String description,
        Integer sortOrder) {

    public static ParcelTagResponse from(ParcelTag t) {
        return new ParcelTagResponse(
                t.getCode(), t.getLabel(), t.getCategory(), t.getDescription(), t.getSortOrder());
    }
}
```

- [ ] **Step 4.5: Create exception classes**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/exception/InvalidAuctionStateException.java`:

```java
package com.slparcelauctions.backend.auction.exception;

import com.slparcelauctions.backend.auction.AuctionStatus;

public class InvalidAuctionStateException extends RuntimeException {

    private final Long auctionId;
    private final AuctionStatus currentState;
    private final String attemptedAction;

    public InvalidAuctionStateException(Long auctionId, AuctionStatus currentState, String attemptedAction) {
        super("Cannot '" + attemptedAction + "' auction " + auctionId + " in state " + currentState);
        this.auctionId = auctionId;
        this.currentState = currentState;
        this.attemptedAction = attemptedAction;
    }

    public Long getAuctionId() {
        return auctionId;
    }

    public AuctionStatus getCurrentState() {
        return currentState;
    }

    public String getAttemptedAction() {
        return attemptedAction;
    }
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionNotFoundException.java`:

```java
package com.slparcelauctions.backend.auction.exception;

public class AuctionNotFoundException extends RuntimeException {

    private final Long auctionId;

    public AuctionNotFoundException(Long auctionId) {
        super("Auction not found: " + auctionId);
        this.auctionId = auctionId;
    }

    public Long getAuctionId() {
        return auctionId;
    }
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java`:

```java
package com.slparcelauctions.backend.auction.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Auction-scoped exceptions. Runs before the common GlobalExceptionHandler
 * by having a higher precedence order.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuctionExceptionHandler {

    @ExceptionHandler(InvalidAuctionStateException.class)
    public ProblemDetail handleInvalidState(InvalidAuctionStateException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Invalid Auction State");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUCTION_INVALID_STATE");
        pd.setProperty("auctionId", e.getAuctionId());
        pd.setProperty("currentState", e.getCurrentState().name());
        pd.setProperty("attemptedAction", e.getAttemptedAction());
        return pd;
    }

    @ExceptionHandler(AuctionNotFoundException.class)
    public ProblemDetail handleNotFound(AuctionNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Auction Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUCTION_NOT_FOUND");
        pd.setProperty("auctionId", e.getAuctionId());
        return pd;
    }
}
```

- [ ] **Step 4.6: Create AuctionDtoMapper with unit test FIRST**

Create test: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionDtoMapperTest.java`:

```java
package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.PublicAuctionStatus;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.user.User;

class AuctionDtoMapperTest {

    private final AuctionDtoMapper mapper = new AuctionDtoMapper();

    @Test
    void toPublicStatus_activeStaysActive() {
        assertThat(mapper.toPublicStatus(AuctionStatus.ACTIVE)).isEqualTo(PublicAuctionStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = AuctionStatus.class, names = {
            "ENDED", "ESCROW_PENDING", "ESCROW_FUNDED",
            "TRANSFER_PENDING", "COMPLETED", "CANCELLED", "EXPIRED", "DISPUTED"})
    void toPublicStatus_postActiveCollapsesToEnded(AuctionStatus status) {
        assertThat(mapper.toPublicStatus(status)).isEqualTo(PublicAuctionStatus.ENDED);
    }

    @ParameterizedTest
    @EnumSource(value = AuctionStatus.class, names = {
            "DRAFT", "DRAFT_PAID", "VERIFICATION_PENDING", "VERIFICATION_FAILED"})
    void toPublicStatus_preActiveThrows(AuctionStatus status) {
        assertThatThrownBy(() -> mapper.toPublicStatus(status))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void toPublicResponse_hidesSellerOnlyFields() {
        Auction a = buildAuction(AuctionStatus.ACTIVE);
        a.setWinnerId(77L);
        a.setListingFeeAmt(100L);
        a.setListingFeeTxn("txn-42");
        a.setVerificationNotes("internal note");
        a.setCommissionAmt(500L);
        a.setReservePrice(5000L);
        a.setCurrentBid(4000L);

        PublicAuctionResponse public_ = mapper.toPublicResponse(a);

        // Reserve fields: boolean flags, NOT the raw amount
        assertThat(public_.hasReserve()).isTrue();
        assertThat(public_.reserveMet()).isFalse();
        // DTO has no raw reservePrice field — compile-time enforced
    }

    @Test
    void toPublicResponse_reserveMet_whenCurrentBidReachesReserve() {
        Auction a = buildAuction(AuctionStatus.ACTIVE);
        a.setReservePrice(5000L);
        a.setCurrentBid(5000L);

        PublicAuctionResponse public_ = mapper.toPublicResponse(a);

        assertThat(public_.hasReserve()).isTrue();
        assertThat(public_.reserveMet()).isTrue();
    }

    @Test
    void toPublicResponse_noReserve_hasReserveFalse() {
        Auction a = buildAuction(AuctionStatus.ACTIVE);
        a.setReservePrice(null);
        a.setCurrentBid(1000L);

        PublicAuctionResponse public_ = mapper.toPublicResponse(a);

        assertThat(public_.hasReserve()).isFalse();
        assertThat(public_.reserveMet()).isFalse();
    }

    @Test
    void toSellerResponse_includesAllSellerOnlyFields() {
        Auction a = buildAuction(AuctionStatus.VERIFICATION_PENDING);
        a.setVerificationMethod(VerificationMethod.UUID_ENTRY);
        a.setListingFeePaid(true);
        a.setListingFeeAmt(100L);
        a.setCommissionRate(new BigDecimal("0.0500"));

        SellerAuctionResponse seller = mapper.toSellerResponse(a, null);

        assertThat(seller.status()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        assertThat(seller.listingFeePaid()).isTrue();
        assertThat(seller.listingFeeAmt()).isEqualTo(100L);
        assertThat(seller.commissionRate()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(seller.pendingVerification()).isNull();
    }

    private Auction buildAuction(AuctionStatus status) {
        User seller = User.builder().id(42L).email("s@example.com").build();
        Parcel parcel = Parcel.builder()
                .id(100L).slParcelUuid(UUID.randomUUID())
                .regionName("Coniston").continentName("Sansara")
                .verified(true).build();
        return Auction.builder()
                .id(1L).seller(seller).parcel(parcel)
                .status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).snipeWindowMin(null)
                .listingFeePaid(false)
                .currentBid(0L).bidCount(0)
                .commissionRate(new BigDecimal("0.0500"))
                .agentFeeRate(new BigDecimal("0.0000"))
                .tags(new HashSet<>())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
```

Run: FAIL (`AuctionDtoMapper` missing).

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`:

```java
package com.slparcelauctions.backend.auction;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.dto.AuctionPhotoResponse;
import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.PublicAuctionStatus;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

/**
 * Auction → DTO conversion. The {@link #toPublicStatus(AuctionStatus)} method
 * is the linchpin of the privacy guarantee: the 4 terminal statuses collapse
 * to ENDED, and the 4 pre-ACTIVE statuses throw IllegalStateException because
 * the controller is responsible for returning 404 to non-sellers before this
 * mapper is called.
 */
@Component
public class AuctionDtoMapper {

    public PublicAuctionStatus toPublicStatus(AuctionStatus internal) {
        return switch (internal) {
            case ACTIVE -> PublicAuctionStatus.ACTIVE;
            case ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING,
                    COMPLETED, CANCELLED, EXPIRED, DISPUTED -> PublicAuctionStatus.ENDED;
            case DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED ->
                    throw new IllegalStateException(
                            "Non-public status leaked to toPublicStatus: " + internal
                                    + ". The controller should have 404'd before calling the mapper.");
        };
    }

    public PublicAuctionResponse toPublicResponse(Auction a) {
        boolean hasReserve = a.getReservePrice() != null;
        boolean reserveMet = hasReserve && a.getCurrentBid() != null
                && a.getCurrentBid() >= a.getReservePrice();
        return new PublicAuctionResponse(
                a.getId(),
                a.getSeller().getId(),
                ParcelResponse.from(a.getParcel()),
                toPublicStatus(a.getStatus()),
                a.getVerificationTier(),
                a.getStartingBid(),
                hasReserve,
                reserveMet,
                a.getBuyNowPrice(),
                a.getCurrentBid(),
                a.getBidCount(),
                a.getDurationHours(),
                a.getSnipeProtect(),
                a.getSnipeWindowMin(),
                a.getStartsAt(),
                a.getEndsAt(),
                a.getOriginalEndsAt(),
                a.getSellerDesc(),
                tagList(a),
                List.of());   // photos wired in Task 9
    }

    public SellerAuctionResponse toSellerResponse(Auction a, PendingVerification pending) {
        return new SellerAuctionResponse(
                a.getId(),
                a.getSeller().getId(),
                ParcelResponse.from(a.getParcel()),
                a.getStatus(),
                a.getVerificationMethod(),
                a.getVerificationTier(),
                pending,
                a.getVerificationNotes(),
                a.getStartingBid(),
                a.getReservePrice(),
                a.getBuyNowPrice(),
                a.getCurrentBid(),
                a.getBidCount(),
                a.getWinnerId(),
                a.getDurationHours(),
                a.getSnipeProtect(),
                a.getSnipeWindowMin(),
                a.getStartsAt(),
                a.getEndsAt(),
                a.getOriginalEndsAt(),
                a.getSellerDesc(),
                tagList(a),
                List.of(),    // photos wired in Task 9
                a.getListingFeePaid(),
                a.getListingFeeAmt(),
                a.getListingFeeTxn(),
                a.getListingFeePaidAt(),
                a.getCommissionRate(),
                a.getCommissionAmt(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }

    private List<ParcelTagResponse> tagList(Auction a) {
        return a.getTags().stream()
                .sorted(Comparator.comparing(t -> t.getSortOrder() == null ? 0 : t.getSortOrder()))
                .map(ParcelTagResponse::from)
                .toList();
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AuctionDtoMapperTest -q`
Expected: PASS (~12 tests from the parameterized + named tests).

- [ ] **Step 4.7: Create AuctionService skeleton**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java`:

```java
package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionService {

    private static final Set<Integer> ALLOWED_DURATIONS = Set.of(24, 48, 72, 168, 336);
    private static final Set<Integer> ALLOWED_SNIPE_WINDOWS = Set.of(5, 10, 15, 30, 60);

    private final AuctionRepository auctionRepo;
    private final ParcelRepository parcelRepo;
    private final UserRepository userRepo;
    private final ParcelTagRepository tagRepo;

    @Value("${slpa.commission.default-rate:0.05}")
    private BigDecimal defaultCommissionRate;

    @Transactional
    public Auction create(Long sellerId, AuctionCreateRequest req) {
        validatePricing(req.startingBid(), req.reservePrice(), req.buyNowPrice());
        validateDuration(req.durationHours());
        validateSnipe(req.snipeProtect(), req.snipeWindowMin());

        User seller = userRepo.findById(sellerId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + sellerId));
        Parcel parcel = parcelRepo.findById(req.parcelId())
                .orElseThrow(() -> new IllegalArgumentException("Parcel not found: " + req.parcelId()));

        Set<ParcelTag> tags = resolveTags(req.tags());

        Auction a = Auction.builder()
                .parcel(parcel)
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .verificationMethod(req.verificationMethod())
                .startingBid(req.startingBid())
                .reservePrice(req.reservePrice())
                .buyNowPrice(req.buyNowPrice())
                .durationHours(req.durationHours())
                .snipeProtect(req.snipeProtect())
                .snipeWindowMin(Boolean.TRUE.equals(req.snipeProtect()) ? req.snipeWindowMin() : null)
                .sellerDesc(req.sellerDesc())
                .tags(tags)
                .commissionRate(defaultCommissionRate)
                .agentFeeRate(BigDecimal.ZERO)
                .currentBid(0L)
                .bidCount(0)
                .listingFeePaid(false)
                .build();
        a = auctionRepo.save(a);
        log.info("Auction created: id={}, sellerId={}, parcelId={}, method={}",
                a.getId(), sellerId, parcel.getId(), req.verificationMethod());
        return a;
    }

    @Transactional
    public Auction update(Long auctionId, Long sellerId, AuctionUpdateRequest req) {
        Auction a = loadForSeller(auctionId, sellerId);
        if (a.getStatus() != AuctionStatus.DRAFT && a.getStatus() != AuctionStatus.DRAFT_PAID) {
            throw new InvalidAuctionStateException(auctionId, a.getStatus(), "UPDATE");
        }

        if (req.verificationMethod() != null) {
            a.setVerificationMethod(req.verificationMethod());
        }
        if (req.startingBid() != null) {
            a.setStartingBid(req.startingBid());
        }
        if (req.reservePrice() != null) {
            a.setReservePrice(req.reservePrice() < 0 ? null : req.reservePrice());
        }
        if (req.buyNowPrice() != null) {
            a.setBuyNowPrice(req.buyNowPrice() < 0 ? null : req.buyNowPrice());
        }
        validatePricing(a.getStartingBid(), a.getReservePrice(), a.getBuyNowPrice());
        if (req.durationHours() != null) {
            validateDuration(req.durationHours());
            a.setDurationHours(req.durationHours());
        }
        if (req.snipeProtect() != null) {
            a.setSnipeProtect(req.snipeProtect());
            if (!req.snipeProtect()) {
                a.setSnipeWindowMin(null);
            }
        }
        if (req.snipeWindowMin() != null) {
            a.setSnipeWindowMin(req.snipeWindowMin());
        }
        validateSnipe(a.getSnipeProtect(), a.getSnipeWindowMin());
        if (req.sellerDesc() != null) {
            a.setSellerDesc(req.sellerDesc());
        }
        if (req.tags() != null) {
            a.setTags(resolveTags(req.tags()));
        }
        return auctionRepo.save(a);
    }

    @Transactional(readOnly = true)
    public Auction loadForSeller(Long auctionId, Long sellerId) {
        return auctionRepo.findByIdAndSellerId(auctionId, sellerId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    }

    @Transactional(readOnly = true)
    public Auction load(Long auctionId) {
        return auctionRepo.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    }

    @Transactional(readOnly = true)
    public List<Auction> loadOwnedBy(Long sellerId) {
        return auctionRepo.findBySellerIdOrderByCreatedAtDesc(sellerId);
    }

    private Set<ParcelTag> resolveTags(Set<String> codes) {
        if (codes == null || codes.isEmpty()) return new HashSet<>();
        List<ParcelTag> found = tagRepo.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            Set<String> foundCodes = found.stream().map(ParcelTag::getCode).collect(java.util.stream.Collectors.toSet());
            Set<String> missing = new HashSet<>(codes);
            missing.removeAll(foundCodes);
            throw new IllegalArgumentException("Unknown parcel tag codes: " + missing);
        }
        return new HashSet<>(found);
    }

    private void validatePricing(Long starting, Long reserve, Long buyNow) {
        if (starting == null || starting < 1) {
            throw new IllegalArgumentException("startingBid must be >= 1");
        }
        if (reserve != null && reserve < starting) {
            throw new IllegalArgumentException("reservePrice must be >= startingBid");
        }
        if (buyNow != null) {
            long floor = Math.max(starting, reserve == null ? 0L : reserve);
            if (buyNow < floor) {
                throw new IllegalArgumentException("buyNowPrice must be >= max(startingBid, reservePrice)");
            }
        }
    }

    private void validateDuration(Integer durationHours) {
        if (durationHours == null || !ALLOWED_DURATIONS.contains(durationHours)) {
            throw new IllegalArgumentException(
                    "durationHours must be one of " + ALLOWED_DURATIONS);
        }
    }

    private void validateSnipe(Boolean snipeProtect, Integer snipeWindowMin) {
        if (Boolean.TRUE.equals(snipeProtect)) {
            if (snipeWindowMin == null || !ALLOWED_SNIPE_WINDOWS.contains(snipeWindowMin)) {
                throw new IllegalArgumentException(
                        "snipeWindowMin must be one of " + ALLOWED_SNIPE_WINDOWS + " when snipeProtect is true");
            }
        } else if (snipeWindowMin != null) {
            throw new IllegalArgumentException("snipeWindowMin must be null when snipeProtect is false");
        }
    }
}
```

- [ ] **Step 4.8: Create CancellationService**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java`:

```java
package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles /cancel transitions. Cancellation is allowed from pre-live and live
 * states; disallowed from ENDED+ (transfer in progress) and terminal states.
 * Refund records are created for any state where listingFeePaid=true. Bid counter
 * increments only when cancelling an ACTIVE auction with bids.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationService {

    private static final Set<AuctionStatus> CANCELLABLE = Set.of(
            AuctionStatus.DRAFT,
            AuctionStatus.DRAFT_PAID,
            AuctionStatus.VERIFICATION_PENDING,
            AuctionStatus.VERIFICATION_FAILED,
            AuctionStatus.ACTIVE);

    private final AuctionRepository auctionRepo;
    private final CancellationLogRepository logRepo;
    private final ListingFeeRefundRepository refundRepo;
    private final UserRepository userRepo;
    private final Clock clock;

    @Transactional
    public Auction cancel(Auction a, String reason) {
        if (!CANCELLABLE.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "CANCEL");
        }
        if (a.getStatus() == AuctionStatus.ACTIVE) {
            if (a.getEndsAt() != null && OffsetDateTime.now(clock).isAfter(a.getEndsAt())) {
                throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "CANCEL_AFTER_END");
            }
        }

        AuctionStatus from = a.getStatus();
        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;

        // Cancellation log
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(a.getSeller())
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason(reason)
                .build());

        // Refund record if fee was paid and this is a pre-live cancellation
        if (Boolean.TRUE.equals(a.getListingFeePaid())
                && from != AuctionStatus.ACTIVE) {
            refundRepo.save(ListingFeeRefund.builder()
                    .auction(a)
                    .amount(a.getListingFeeAmt() == null ? 0L : a.getListingFeeAmt())
                    .status(RefundStatus.PENDING)
                    .build());
            log.info("Listing fee refund (PENDING) created for auction {}", a.getId());
        }

        // cancelled_with_bids counter on ACTIVE cancellations with bids
        if (from == AuctionStatus.ACTIVE && hadBids) {
            User seller = a.getSeller();
            seller.setCancelledWithBids(seller.getCancelledWithBids() + 1);
            userRepo.save(seller);
        }

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);
        log.info("Auction {} cancelled from {} (hadBids={})", a.getId(), from, hadBids);
        return saved;
    }
}
```

- [ ] **Step 4.9: Create AuctionController**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java`:

```java
package com.slparcelauctions.backend.auction;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.dto.AuctionCancelRequest;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;
    private final CancellationService cancellationService;
    private final AuctionDtoMapper mapper;

    @PostMapping("/auctions")
    @ResponseStatus(HttpStatus.CREATED)
    public SellerAuctionResponse create(
            @Valid @RequestBody AuctionCreateRequest req,
            Authentication auth) {
        Long userId = currentUserId(auth);
        Auction created = auctionService.create(userId, req);
        return mapper.toSellerResponse(created, null);
    }

    @GetMapping("/auctions/{id}")
    public Object get(@PathVariable Long id, Authentication auth) {
        Auction a = auctionService.load(id);
        Long userId = auth == null ? null : currentUserIdOrNull(auth);
        boolean isSeller = userId != null && a.getSeller().getId().equals(userId);
        if (!isSeller) {
            if (isPreActive(a.getStatus())) {
                throw new AuctionNotFoundException(id);  // hide existence
            }
            return mapper.toPublicResponse(a);
        }
        return mapper.toSellerResponse(a, null);
    }

    @GetMapping("/users/me/auctions")
    public List<SellerAuctionResponse> listMine(Authentication auth) {
        Long userId = currentUserId(auth);
        return auctionService.loadOwnedBy(userId).stream()
                .map(a -> mapper.toSellerResponse(a, null))
                .toList();
    }

    @PutMapping("/auctions/{id}")
    public SellerAuctionResponse update(
            @PathVariable Long id,
            @Valid @RequestBody AuctionUpdateRequest req,
            Authentication auth) {
        Long userId = currentUserId(auth);
        Auction updated = auctionService.update(id, userId, req);
        return mapper.toSellerResponse(updated, null);
    }

    @PutMapping("/auctions/{id}/cancel")
    public SellerAuctionResponse cancel(
            @PathVariable Long id,
            @Valid @RequestBody AuctionCancelRequest req,
            Authentication auth) {
        Long userId = currentUserId(auth);
        Auction a = auctionService.loadForSeller(id, userId);
        Auction cancelled = cancellationService.cancel(a, req.reason());
        return mapper.toSellerResponse(cancelled, null);
    }

    @GetMapping("/auctions/{id}/preview")
    public SellerAuctionResponse preview(@PathVariable Long id, Authentication auth) {
        Long userId = currentUserId(auth);
        Auction a = auctionService.loadForSeller(id, userId);
        return mapper.toSellerResponse(a, null);
    }

    private boolean isPreActive(AuctionStatus s) {
        return s == AuctionStatus.DRAFT
                || s == AuctionStatus.DRAFT_PAID
                || s == AuctionStatus.VERIFICATION_PENDING
                || s == AuctionStatus.VERIFICATION_FAILED;
    }

    private Long currentUserId(Authentication auth) {
        // Inspect existing auth-extraction pattern in controller.AuthController or similar
        // and reuse. Placeholder: returns auth.getPrincipal() cast. Replace with actual JWT claim read.
        return Long.valueOf(auth.getName());
    }

    private Long currentUserIdOrNull(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        try { return Long.valueOf(auth.getName()); } catch (NumberFormatException e) { return null; }
    }
}
```

Note: `currentUserId(auth)` is a stub. Inspect the existing controllers (e.g., `UserController`) to see how they extract the JWT user ID, and use the same pattern. If the JWT subject is the user ID (string), `Long.valueOf(auth.getName())` works. If it's the email or a JWT claim, use the existing helper.

- [ ] **Step 4.10: Write AuctionServiceTest (unit, mocked repos)**

Create `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionServiceTest.java`. Parameterized transition tests for update-allowed states, validation error cases for pricing and duration/snipe combinations, tag resolution with missing codes, etc. Use `MockitoExtension` like `ParcelLookupServiceTest`. Target 15+ tests covering:

- Create with valid inputs → auction saved in DRAFT with defaults
- Create with reservePrice < startingBid → 400
- Create with buyNowPrice < max(starting, reserve) → 400
- Create with durationHours = 100 → 400 (not in allowed set)
- Create with snipeProtect=true but snipeWindowMin=null → 400
- Create with snipeProtect=false but snipeWindowMin=10 → 400
- Create with unknown tag code → 400
- Update on DRAFT → works
- Update on DRAFT_PAID → works
- Update on VERIFICATION_PENDING → InvalidAuctionStateException
- Update on ACTIVE → InvalidAuctionStateException
- Update with 11 tags → 400 (max 10 — validated by @Size)
- loadForSeller with wrong sellerId → AuctionNotFoundException

Run: `cd backend && ./mvnw test -Dtest=AuctionServiceTest -q`
Expected: PASS.

- [ ] **Step 4.11: Write CancellationServiceTest (unit)**

Create `backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceTest.java`. Matrix of (fromState × hadFeePaid × hadBids) → expected side effects. Target 12+ tests covering:

- Cancel DRAFT → CANCELLED, no refund, no counter increment, CancellationLog created
- Cancel DRAFT_PAID → CANCELLED, refund PENDING created
- Cancel VERIFICATION_PENDING with fee paid → CANCELLED, refund PENDING
- Cancel VERIFICATION_FAILED with fee paid → CANCELLED, refund PENDING
- Cancel ACTIVE with 0 bids → CANCELLED, no refund, no counter increment
- Cancel ACTIVE with 5 bids → CANCELLED, no refund, counter incremented from 0 to 1
- Cancel ENDED → InvalidAuctionStateException
- Cancel ESCROW_PENDING → InvalidAuctionStateException
- Cancel COMPLETED → InvalidAuctionStateException
- Cancel CANCELLED (already terminal) → InvalidAuctionStateException
- Cancel ACTIVE after ends_at → InvalidAuctionStateException (status check + time check)

Run: `cd backend && ./mvnw test -Dtest=CancellationServiceTest -q`
Expected: PASS.

- [ ] **Step 4.12: Write AuctionControllerIntegrationTest**

Create `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionControllerIntegrationTest.java`. Full `@SpringBootTest` + `@AutoConfigureMockMvc`. Use existing auth test helpers to obtain a JWT for a verified test user. Tests:

- Create auction (Method A) → 201, returns SellerAuctionResponse in DRAFT
- Create auction as unverified user → 403
- Create auction with valid request but non-existent parcelId → 400 (IllegalArgumentException from service → GlobalExceptionHandler maps to 400)
- GET /auctions/{id} as seller → SellerAuctionResponse
- GET /auctions/{id} as non-seller for DRAFT → 404 (hides existence)
- GET /auctions/{id} as non-seller for ACTIVE → PublicAuctionResponse with status ACTIVE
- GET /auctions/{id} as non-seller for CANCELLED → PublicAuctionResponse with status ENDED, no winner_id, no listingFee fields
- GET /users/me/auctions → returns list of all seller's auctions
- PUT /auctions/{id} on DRAFT → 200 with updated fields
- PUT /auctions/{id} on ACTIVE → 409 InvalidAuctionStateException
- PUT /auctions/{id}/cancel on DRAFT_PAID → 200, refund record exists in DB, status is CANCELLED
- PUT /auctions/{id}/cancel on COMPLETED → 409
- GET /auctions/{id}/preview as seller → 200
- GET /auctions/{id}/preview as non-seller → 404

Run: `cd backend && ./mvnw test -Dtest=AuctionControllerIntegrationTest -q`
Expected: PASS.

- [ ] **Step 4.13: Run full test suite**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS, baseline +~40 (~253 tests).

- [ ] **Step 4.14: Postman — add auction CRUD requests**

Add to `SLPA / Parcel & Listings`:
- `Auction / Create`
- `Auction / Get by ID`
- `Auction / My Auctions`
- `Auction / Update Draft`
- `Auction / Cancel`
- `Auction / Preview`

Capture `auctionId` from the Create response.

- [ ] **Step 4.15: Commit Task 4**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/
git add backend/src/main/java/com/slparcelauctions/backend/parceltag/dto/ParcelTagResponse.java
git add backend/src/main/resources/application.yml
git add backend/src/test/java/com/slparcelauctions/backend/auction/
git commit -m "feat(auction): add CRUD endpoints, state machine, two-DTO model, cancellation

- Seller/Public DTO split with type-enforced status collapse via PublicAuctionStatus enum
- 404 hides pre-ACTIVE auctions from non-sellers
- Cancellation flow writes CancellationLog + PENDING ListingFeeRefund when applicable
- cancelled_with_bids counter increments only on ACTIVE+bids cancellation
- AuctionExceptionHandler maps InvalidAuctionStateException to 409, AuctionNotFoundException to 404
- Field validation via @Valid + service-layer checks (duration enum, pricing monotonicity, snipe window enum)"
git push
```

---

## Task 5 — Dev listing fee payment stub

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/DevPayRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/DevAuctionController.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/DevAuctionControllerTest.java`

- [ ] **Step 5.1: Create DevPayRequest**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/dto/DevPayRequest.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record DevPayRequest(
        @Min(1) Long amount,
        @Size(max = 255) String txnRef) {
}
```

- [ ] **Step 5.2: Create DevAuctionController**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/DevAuctionController.java`:

```java
package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.dto.DevPayRequest;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile-only stub for listing fee payment. Stands in for the real
 * in-world terminal payment callback that arrives in Epic 05. Endpoint and
 * controller both disappear in non-dev profiles.
 */
@RestController
@RequestMapping("/api/v1/dev/auctions")
@RequiredArgsConstructor
@Profile("dev")
@Slf4j
public class DevAuctionController {

    private final AuctionService auctionService;
    private final AuctionRepository auctionRepo;
    private final AuctionDtoMapper mapper;
    private final Clock clock;

    @Value("${slpa.listing-fee.amount-lindens:100}")
    private long defaultFeeAmount;

    @PostMapping("/{id}/pay")
    @Transactional
    public SellerAuctionResponse pay(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) DevPayRequest body,
            Authentication auth) {
        Long userId = Long.valueOf(auth.getName());
        Auction a = auctionService.loadForSeller(id, userId);

        if (a.getStatus() != AuctionStatus.DRAFT) {
            throw new InvalidAuctionStateException(id, a.getStatus(), "PAY");
        }

        long amount = body != null && body.amount() != null ? body.amount() : defaultFeeAmount;
        String txnRef = body != null && body.txnRef() != null
                ? body.txnRef()
                : "dev-mock-" + UUID.randomUUID();

        a.setListingFeePaid(true);
        a.setListingFeeAmt(amount);
        a.setListingFeeTxn(txnRef);
        a.setListingFeePaidAt(OffsetDateTime.now(clock));
        a.setStatus(AuctionStatus.DRAFT_PAID);
        Auction saved = auctionRepo.save(a);
        log.info("Dev listing fee paid: auctionId={}, amount={}, txnRef={}", id, amount, txnRef);
        return mapper.toSellerResponse(saved, null);
    }
}
```

- [ ] **Step 5.3: Write DevAuctionControllerTest integration test**

Create `backend/src/test/java/com/slparcelauctions/backend/auction/DevAuctionControllerTest.java`. Use `@SpringBootTest` + `@ActiveProfiles("dev")`. Tests:

- Pay on DRAFT → 200, status flips to DRAFT_PAID, listing_fee_amt=100 (default), txn_ref prefixed with "dev-mock-"
- Pay on DRAFT_PAID → 409 InvalidAuctionStateException
- Pay on ACTIVE → 409
- Pay as non-seller → AuctionNotFoundException → 404
- Pay with custom amount and txnRef → persists both
- Non-dev profile: bean not instantiated. Verify by running a separate `@SpringBootTest` with `@ActiveProfiles("prod")` (if prod profile is configured in tests — else skip and document in README)

Run: `cd backend && ./mvnw test -Dtest=DevAuctionControllerTest -q`
Expected: PASS.

- [ ] **Step 5.4: Postman — add `Dev / Simulate listing fee payment`**

Add to the Postman collection's `SLPA / Parcel & Listings / Dev` subfolder. Body empty (defaults to config). Endpoint: `{{baseUrl}}/api/v1/dev/auctions/{{auctionId}}/pay`.

- [ ] **Step 5.5: Run full test suite**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS, +~5 tests (~258).

- [ ] **Step 5.6: Commit Task 5**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/DevAuctionController.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/dto/DevPayRequest.java
git add backend/src/test/java/com/slparcelauctions/backend/auction/DevAuctionControllerTest.java
git commit -m "feat(dev): add POST /api/v1/dev/auctions/{id}/pay stub for listing fee payment

Dev-profile-only endpoint that transitions DRAFT → DRAFT_PAID. Stand-in
for the real in-world terminal callback that arrives in Epic 05."
git push
```

---

## Task 6 — Unified /verify endpoint + Method A + parcel-locking enforcement

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/ParcelAlreadyListedException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java` (add 409 mapping)
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java` (add /verify endpoint)
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionVerificationServiceMethodATest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/ParcelLockingRaceIntegrationTest.java`

- [ ] **Step 6.1: Create ParcelAlreadyListedException + handler mapping**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/exception/ParcelAlreadyListedException.java`:

```java
package com.slparcelauctions.backend.auction.exception;

public class ParcelAlreadyListedException extends RuntimeException {

    private final Long parcelId;
    private final Long blockingAuctionId;

    public ParcelAlreadyListedException(Long parcelId, Long blockingAuctionId) {
        super("Parcel " + parcelId + " is currently in auction " + blockingAuctionId);
        this.parcelId = parcelId;
        this.blockingAuctionId = blockingAuctionId;
    }

    public Long getParcelId() {
        return parcelId;
    }

    public Long getBlockingAuctionId() {
        return blockingAuctionId;
    }
}
```

Edit `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java` — add:

```java
    @ExceptionHandler(ParcelAlreadyListedException.class)
    public ProblemDetail handleAlreadyListed(ParcelAlreadyListedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "This parcel is currently in an active auction. You can list it again after that auction ends.");
        pd.setTitle("Parcel Already Listed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PARCEL_ALREADY_LISTED");
        pd.setProperty("parcelId", e.getParcelId());
        pd.setProperty("blockingAuctionId", e.getBlockingAuctionId());
        return pd;
    }
```

- [ ] **Step 6.2: Create AuctionVerificationService skeleton (Method A only for now)**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java`:

```java
package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Unified verification dispatch.
 * <ul>
 *   <li>Method A (UUID_ENTRY) runs inline: World API call + ownership check + parcel lock check + flip to ACTIVE.</li>
 *   <li>Method B (REZZABLE) and Method C (SALE_TO_BOT) are added in later tasks.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionVerificationService {

    private static final Set<AuctionStatus> VERIFY_ALLOWED_FROM = Set.of(
            AuctionStatus.DRAFT_PAID,
            AuctionStatus.VERIFICATION_FAILED);

    private final AuctionRepository auctionRepo;
    private final AuctionService auctionService;
    private final SlWorldApiClient worldApi;
    private final Clock clock;

    @Transactional
    public Auction triggerVerification(Long auctionId, Long sellerId) {
        Auction a = auctionService.loadForSeller(auctionId, sellerId);
        if (!VERIFY_ALLOWED_FROM.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(auctionId, a.getStatus(), "VERIFY");
        }
        a.setStatus(AuctionStatus.VERIFICATION_PENDING);
        a.setVerificationNotes(null);  // clear previous retry failure
        auctionRepo.save(a);

        return switch (a.getVerificationMethod()) {
            case UUID_ENTRY -> dispatchMethodA(a);
            case REZZABLE -> throw new UnsupportedOperationException("Method B wired in Task 7");
            case SALE_TO_BOT -> throw new UnsupportedOperationException("Method C wired in Task 8");
        };
    }

    private Auction dispatchMethodA(Auction a) {
        Parcel parcel = a.getParcel();
        User seller = a.getSeller();

        ParcelMetadata fresh;
        try {
            fresh = worldApi.fetchParcel(parcel.getSlParcelUuid()).block();
        } catch (RuntimeException e) {
            a.setVerificationNotes("World API lookup failed: " + e.getMessage());
            a.setStatus(AuctionStatus.VERIFICATION_FAILED);
            return auctionRepo.save(a);
        }

        if (!"agent".equalsIgnoreCase(fresh.ownerType())) {
            a.setVerificationNotes("Method A rejects group-owned parcels. Use Method C (Sale-to-Bot).");
            a.setStatus(AuctionStatus.VERIFICATION_FAILED);
            return auctionRepo.save(a);
        }
        if (fresh.ownerUuid() == null || !fresh.ownerUuid().equals(seller.getSlAvatarUuid())) {
            a.setVerificationNotes("Ownership mismatch: World API shows owner "
                    + fresh.ownerUuid() + " but your SL avatar is " + seller.getSlAvatarUuid());
            a.setStatus(AuctionStatus.VERIFICATION_FAILED);
            return auctionRepo.save(a);
        }

        assertParcelNotLocked(a);

        parcel.setLastChecked(OffsetDateTime.now(clock));
        parcel.setOwnerUuid(fresh.ownerUuid());
        parcel.setOwnerType(fresh.ownerType());

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime endsAt = now.plusHours(a.getDurationHours());
        a.setStartsAt(now);
        a.setEndsAt(endsAt);
        a.setOriginalEndsAt(endsAt);
        a.setVerifiedAt(now);
        a.setVerificationTier(VerificationTier.SCRIPT);
        a.setStatus(AuctionStatus.ACTIVE);
        try {
            return auctionRepo.save(a);
        } catch (DataIntegrityViolationException e) {
            // Partial unique index caught a race
            throw new ParcelAlreadyListedException(parcel.getId(), -1L);
        }
    }

    /**
     * Service-layer parcel lock check. Throws {@link ParcelAlreadyListedException} if
     * another auction on the same parcel is already in a locking status. This is the
     * first of two layers; the Postgres partial unique index is the DB-level backstop.
     */
    private void assertParcelNotLocked(Auction candidate) {
        if (auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                candidate.getParcel().getId(),
                AuctionStatusConstants.LOCKING_STATUSES,
                candidate.getId())) {
            // Find the blocking auction for the error detail — cheap since we already know one exists
            Long blockingId = auctionRepo.findBySellerIdOrderByCreatedAtDesc(-1L).stream()
                    .findFirst().map(Auction::getId).orElse(-1L);
            throw new ParcelAlreadyListedException(candidate.getParcel().getId(), blockingId);
        }
    }
}
```

Note: the `blockingId` lookup via `findBySellerIdOrderByCreatedAtDesc(-1L)` is a placeholder — replace with a proper repository query `findFirstByParcelIdAndStatusIn(parcelId, LOCKING_STATUSES)`. Add that method to `AuctionRepository` during implementation. If the locking check already returned true, this lookup should always find a row.

- [ ] **Step 6.3: Add /verify endpoint to AuctionController**

Edit `AuctionController.java` — inject `AuctionVerificationService` in the constructor and add:

```java
    private final AuctionVerificationService verificationService;

    @PutMapping("/auctions/{id}/verify")
    public SellerAuctionResponse verify(@PathVariable Long id, Authentication auth) {
        Long userId = Long.valueOf(auth.getName());
        Auction a = verificationService.triggerVerification(id, userId);
        PendingVerification pending = null; // Method B/C will populate this via the service
        return mapper.toSellerResponse(a, pending);
    }
```

- [ ] **Step 6.4: Write AuctionVerificationServiceMethodATest**

Create `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionVerificationServiceMethodATest.java`. Mocked repos, mocked WorldApiClient. Tests:

- Verify from DRAFT_PAID with matching ownership → ACTIVE, startsAt/endsAt set, verification_tier=SCRIPT
- Verify from VERIFICATION_FAILED (retry) with matching ownership → ACTIVE
- Verify from DRAFT (no fee paid) → InvalidAuctionStateException
- Verify from ACTIVE → InvalidAuctionStateException
- Verify with ownertype=group → VERIFICATION_FAILED with "rejects group-owned" note
- Verify with ownerid mismatch → VERIFICATION_FAILED
- Verify when World API throws ExternalApiTimeoutException → VERIFICATION_FAILED with note
- Verify when parcel already in ACTIVE auction by same seller → ParcelAlreadyListedException
- Verify when parcel already in ACTIVE auction by different seller → ParcelAlreadyListedException

Run: PASS after implementation is correct.

- [ ] **Step 6.5: Write ParcelLockingRaceIntegrationTest**

Create `backend/src/test/java/com/slparcelauctions/backend/auction/ParcelLockingRaceIntegrationTest.java`. Full `@SpringBootTest`. Simulates the two-layer defense:

```java
package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
// ... imports for test fixtures ...

@SpringBootTest
@ActiveProfiles("dev")
class ParcelLockingRaceIntegrationTest {
    // Scenario: two auctions A1 and A2 on the same parcel, both in DRAFT_PAID.
    // Both verify simultaneously. First to complete wins ACTIVE; the other
    // gets ParcelAlreadyListedException (either from the service-layer check
    // or the DB partial index).

    @Test
    void concurrentVerify_oneWinsOneFails() throws Exception {
        // Build two draft auctions on the same parcel (use TestFixtures to create them),
        // each in DRAFT_PAID, each with verification_method = UUID_ENTRY.
        // Mock SlWorldApiClient to return matching ownership for seller A (on auction A1).
        // Auction A2 has a different seller; mock worldApi to return matching ownership for that seller too.
        // Run both /verify calls in parallel via CompletableFuture.
        // Assert exactly one ends up ACTIVE.
        // Assert the other ends up VERIFICATION_FAILED (service caught the race) or
        // threw ParcelAlreadyListedException (service-layer check) or
        // DataIntegrityViolationException was unwrapped to ParcelAlreadyListedException.

        // Full implementation: see the general-purpose test helpers in SlVerificationFlowIntegrationTest
        // for how to wire @MockBean + @Autowired + TestEntityManager.
    }

    @Test
    void sequentialVerify_secondFailsWithActiveBlocker() {
        // A1 verified → ACTIVE
        // A2.verify() → throws ParcelAlreadyListedException via assertParcelNotLocked
    }

    @Test
    void cancelledActive_unblocksParcelForRetry() {
        // A1 verified → ACTIVE
        // A1 cancelled → CANCELLED
        // A2.verify() → succeeds → ACTIVE
    }
}
```

Expand the test skeletons with real fixtures matching the existing integration-test style. Run:
```bash
cd backend && ./mvnw test -Dtest=ParcelLockingRaceIntegrationTest -q
```
Expected: PASS (3 tests).

- [ ] **Step 6.6: Postman — add `Auction / Verify` request**

Endpoint: `PUT {{baseUrl}}/api/v1/auctions/{{auctionId}}/verify`. No body.

- [ ] **Step 6.7: Run full test suite**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS, +~12 tests (~270).

- [ ] **Step 6.8: Commit Task 6**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/exception/ParcelAlreadyListedException.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java
git add backend/src/test/java/com/slparcelauctions/backend/auction/AuctionVerificationServiceMethodATest.java
git add backend/src/test/java/com/slparcelauctions/backend/auction/ParcelLockingRaceIntegrationTest.java
git commit -m "feat(auction): add PUT /verify with Method A dispatch + parcel-locking enforcement

- Unified verify endpoint; Method A runs inline (World API ownership check)
- assertParcelNotLocked service check + Postgres partial unique index backstop
- ParcelAlreadyListedException mapped to 409 with blocking auction ID
- Race-condition integration tests for concurrent verify paths"
git push
```

---

## Task 7 — Method B REZZABLE + LSL callback + code expiry job

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCodeService.java` (add `generateForParcel(userId, auctionId)` method)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCodeRepository.java` (add `findByCodeAndTypeAndAuctionIdAndUsedFalseAndExpiresAtAfter` or similar)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java` (wire Method B dispatch)
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/SlParcelVerifyRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/SlParcelVerifyService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/sl/SlParcelVerifyController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/ParcelCodeExpiryJob.java`
- Modify: `backend/src/main/resources/application.yml` (add `slpa.verification.parcel-code-expiry-check-interval`)
- Create: test files (see steps)

- [ ] **Step 7.1: Add config property**

Edit `backend/src/main/resources/application.yml`. Under `slpa:`:

```yaml
slpa:
  verification:
    parcel-code-ttl-minutes: 15
    parcel-code-expiry-check-interval: PT5M
```

- [ ] **Step 7.2: Extend VerificationCodeService for PARCEL codes**

Edit `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCodeService.java`. Add a new method that generates a PARCEL code bound to an auction (does NOT run the `AlreadyVerifiedException` check — the user IS verified when making parcel codes; that's a prerequisite). Existing method handles PLAYER codes.

Add this method inside the class:

```java
    /**
     * Generate a PARCEL-type code bound to a specific draft auction. Voids any prior
     * active PARCEL code for this auction. Unlike {@link #generate}, does NOT check
     * the user's verified flag — PARCEL codes presuppose a verified user.
     */
    @Transactional
    public GenerateCodeResponse generateForParcel(Long userId, Long auctionId) {
        voidActivePar(userId, auctionId);
        String code = String.format("%06d", random.nextInt(1_000_000));
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(CODE_TTL);
        VerificationCode row = repository.save(
                VerificationCode.builder()
                        .userId(userId)
                        .auctionId(auctionId)
                        .code(code)
                        .type(VerificationCodeType.PARCEL)
                        .expiresAt(expiresAt)
                        .used(false)
                        .build());
        log.info("Generated PARCEL verification code for user {} auction {} (id={})",
                userId, auctionId, row.getId());
        return new GenerateCodeResponse(code, expiresAt);
    }

    private void voidActivePar(Long userId, Long auctionId) {
        List<VerificationCode> active = repository
                .findByAuctionIdAndTypeAndUsedFalse(auctionId, VerificationCodeType.PARCEL);
        if (active.isEmpty()) return;
        active.forEach(c -> c.setUsed(true));
        repository.saveAll(active);
        log.info("Voided {} prior active PARCEL code(s) for auction {}", active.size(), auctionId);
    }
```

Edit `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCodeRepository.java` — add:

```java
    List<VerificationCode> findByAuctionIdAndTypeAndUsedFalse(Long auctionId, VerificationCodeType type);

    Optional<VerificationCode> findByCodeAndTypeAndAuctionIdAndUsedFalseAndExpiresAtAfter(
            String code, VerificationCodeType type, Long auctionId, OffsetDateTime now);
```

- [ ] **Step 7.3: Create SlParcelVerifyRequest DTO**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/dto/SlParcelVerifyRequest.java`:

```java
package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SlParcelVerifyRequest(
        @NotBlank String verificationCode,
        @NotNull UUID parcelUuid,
        @NotNull UUID ownerUuid,
        String parcelName,
        Integer areaSqm,
        String description,
        Integer primCapacity,
        Double regionPosX,
        Double regionPosY,
        Double regionPosZ) {
}
```

- [ ] **Step 7.4: Wire Method B dispatch in AuctionVerificationService**

Edit `AuctionVerificationService.java`. Inject `VerificationCodeService` and update `triggerVerification` + dispatch:

```java
    private final VerificationCodeService verificationCodeService;
    // add to @RequiredArgsConstructor list

    // Replace the REZZABLE switch arm:
            case REZZABLE -> dispatchMethodB(a);

    // Add new method:
    private Auction dispatchMethodB(Auction a) {
        // (status already set to VERIFICATION_PENDING by triggerVerification)
        verificationCodeService.generateForParcel(a.getSeller().getId(), a.getId());
        return a;
    }
```

`SellerAuctionResponse` for Method B needs a populated `PendingVerification`. Controller-side: after calling `triggerVerification`, query `VerificationCodeRepository.findByAuctionIdAndTypeAndUsedFalse(auctionId, PARCEL)` to fetch the freshly-generated code, and build a `PendingVerification` object. Update `AuctionController.verify` accordingly:

```java
    @PutMapping("/auctions/{id}/verify")
    public SellerAuctionResponse verify(@PathVariable Long id, Authentication auth) {
        Long userId = Long.valueOf(auth.getName());
        Auction a = verificationService.triggerVerification(id, userId);
        PendingVerification pending = verificationService.buildPendingVerification(a);
        return mapper.toSellerResponse(a, pending);
    }
```

Add `buildPendingVerification(Auction a)` to `AuctionVerificationService`:

```java
    /**
     * Builds the PendingVerification DTO for a VERIFICATION_PENDING auction.
     * Returns null for non-pending auctions. For Method A this is null even
     * during transition because Method A is synchronous — the auction has
     * already moved to ACTIVE or VERIFICATION_FAILED by the time this is called.
     */
    @Transactional(readOnly = true)
    public PendingVerification buildPendingVerification(Auction a) {
        if (a.getStatus() != AuctionStatus.VERIFICATION_PENDING) return null;
        return switch (a.getVerificationMethod()) {
            case UUID_ENTRY -> null;  // Method A never stays in VERIFICATION_PENDING
            case REZZABLE -> {
                var code = verificationCodeService
                        .findActiveForParcel(a.getSeller().getId(), a.getId());
                yield code.map(c -> new PendingVerification(
                        VerificationMethod.REZZABLE,
                        c.code(), c.expiresAt(),
                        null, null))
                        .orElse(null);
            }
            case SALE_TO_BOT -> buildBotTaskPending(a);  // Task 8
        };
    }

    private PendingVerification buildBotTaskPending(Auction a) {
        return null; // wired in Task 8
    }
```

Add `findActiveForParcel` to `VerificationCodeService`:

```java
    @Transactional(readOnly = true)
    public Optional<ActiveCodeResponse> findActiveForParcel(Long userId, Long auctionId) {
        return repository
                .findByAuctionIdAndTypeAndUsedFalse(auctionId, VerificationCodeType.PARCEL)
                .stream()
                .filter(c -> c.getExpiresAt().isAfter(OffsetDateTime.now(clock)))
                .findFirst()
                .map(c -> new ActiveCodeResponse(c.getCode(), c.getExpiresAt()));
    }
```

- [ ] **Step 7.5: Create SlParcelVerifyService**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/SlParcelVerifyService.java`:

```java
package com.slparcelauctions.backend.sl;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionStatusConstants;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.dto.SlParcelVerifyRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCode;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the LSL rezzable-object callback POST /api/v1/sl/parcel/verify.
 * Validates SL headers, code, ownership, Mainland status, and parcel lock
 * before transitioning the auction to ACTIVE.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlParcelVerifyService {

    private final SlHeaderValidator headerValidator;
    private final VerificationCodeRepository codeRepo;
    private final AuctionRepository auctionRepo;
    private final ParcelRepository parcelRepo;
    private final UserRepository userRepo;
    private final Clock clock;

    @Transactional
    public void verify(String shard, String ownerKey, SlParcelVerifyRequest body) {
        headerValidator.validate(shard, ownerKey);

        VerificationCode code = codeRepo
                .findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                        body.verificationCode(), VerificationCodeType.PARCEL, OffsetDateTime.now(clock))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification code"));

        Long auctionId = code.getAuctionId();
        if (auctionId == null) {
            throw new IllegalStateException("PARCEL code has no auction_id binding: " + code.getId());
        }

        Auction auction = auctionRepo.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (auction.getStatus() != AuctionStatus.VERIFICATION_PENDING) {
            throw new InvalidAuctionStateException(auctionId, auction.getStatus(), "SL_PARCEL_VERIFY");
        }

        Parcel parcel = auction.getParcel();
        if (!parcel.getSlParcelUuid().equals(body.parcelUuid())) {
            throw new IllegalArgumentException(
                    "Parcel UUID mismatch: code bound to " + parcel.getSlParcelUuid()
                            + " but in-world reports " + body.parcelUuid());
        }
        User seller = auction.getSeller();
        if (!body.ownerUuid().equals(seller.getSlAvatarUuid())) {
            throw new IllegalArgumentException(
                    "Owner UUID mismatch: in-world owner " + body.ownerUuid()
                            + " is not your SL avatar " + seller.getSlAvatarUuid());
        }

        // Parcel lock check
        if (auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                parcel.getId(), AuctionStatusConstants.LOCKING_STATUSES, auction.getId())) {
            throw new ParcelAlreadyListedException(parcel.getId(), -1L);
        }

        code.setUsed(true);
        codeRepo.save(code);

        // Refresh parcel metadata from in-world report
        if (body.parcelName() != null) parcel.setRegionName(parcel.getRegionName()); // name may be parcel-scope; store sellerDesc via auction.sellerDesc if needed
        if (body.areaSqm() != null) parcel.setAreaSqm(body.areaSqm());
        if (body.regionPosX() != null) parcel.setPositionX(body.regionPosX());
        if (body.regionPosY() != null) parcel.setPositionY(body.regionPosY());
        if (body.regionPosZ() != null) parcel.setPositionZ(body.regionPosZ());
        parcel.setLastChecked(OffsetDateTime.now(clock));
        parcelRepo.save(parcel);

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime endsAt = now.plusHours(auction.getDurationHours());
        auction.setStartsAt(now);
        auction.setEndsAt(endsAt);
        auction.setOriginalEndsAt(endsAt);
        auction.setVerifiedAt(now);
        auction.setVerificationTier(VerificationTier.SCRIPT);
        auction.setStatus(AuctionStatus.ACTIVE);
        try {
            auctionRepo.save(auction);
        } catch (DataIntegrityViolationException e) {
            throw new ParcelAlreadyListedException(parcel.getId(), -1L);
        }
        log.info("Method B verification complete: auctionId={}, parcelUuid={}", auctionId, body.parcelUuid());
    }
}
```

- [ ] **Step 7.6: Create SlParcelVerifyController**

Create `backend/src/main/java/com/slparcelauctions/backend/sl/SlParcelVerifyController.java`:

```java
package com.slparcelauctions.backend.sl;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.sl.dto.SlParcelVerifyRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Public endpoint (no JWT) called by in-world LSL rezzable verification
 * objects. The SL-injected headers are the trust boundary.
 */
@RestController
@RequestMapping("/api/v1/sl/parcel")
@RequiredArgsConstructor
public class SlParcelVerifyController {

    private final SlParcelVerifyService service;

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlParcelVerifyRequest body) {
        service.verify(shard, ownerKey, body);
    }
}
```

Also, ensure `SecurityConfig.java` permits `/api/v1/sl/parcel/**` the same way it permits `/api/v1/sl/verify` (`permitAll()` — the `SlHeaderValidator` is the actual security boundary).

- [ ] **Step 7.7: Create ParcelCodeExpiryJob**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/ParcelCodeExpiryJob.java`:

```java
package com.slparcelauctions.backend.auction.scheduled;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reverts stuck Method B auctions when their PARCEL verification code has
 * expired without an LSL callback. Transitions VERIFICATION_PENDING → DRAFT_PAID
 * (no refund — seller can click Verify again to get a new code immediately).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParcelCodeExpiryJob {

    private final AuctionRepository auctionRepo;
    private final VerificationCodeRepository codeRepo;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${slpa.verification.parcel-code-expiry-check-interval:PT5M}")
    @Transactional
    public void sweep() {
        List<Auction> pending = auctionRepo.findByStatusAndVerificationMethod(
                AuctionStatus.VERIFICATION_PENDING, VerificationMethod.REZZABLE);
        OffsetDateTime now = OffsetDateTime.now(clock);
        int reverted = 0;
        for (Auction a : pending) {
            boolean hasActiveCode = codeRepo
                    .findByAuctionIdAndTypeAndUsedFalse(a.getId(), VerificationCodeType.PARCEL)
                    .stream().anyMatch(c -> c.getExpiresAt().isAfter(now));
            if (!hasActiveCode) {
                a.setStatus(AuctionStatus.DRAFT_PAID);
                a.setVerificationNotes("Verification code expired without callback. Click Verify again.");
                auctionRepo.save(a);
                reverted++;
                log.info("Reverted Method B auction {} to DRAFT_PAID (code expired)", a.getId());
            }
        }
        if (reverted > 0) {
            log.info("ParcelCodeExpiryJob: reverted {} auctions", reverted);
        }
    }
}
```

Enable `@EnableScheduling` at the application level if not already. Inspect `BackendApplication.java`:

```java
// backend/src/main/java/com/slparcelauctions/backend/BackendApplication.java
// Add if not present:
@org.springframework.scheduling.annotation.EnableScheduling
```

- [ ] **Step 7.8: Write tests for Method B paths**

Create `AuctionVerificationServiceMethodBTest.java` — unit test for `dispatchMethodB` and `buildPendingVerification`. Tests:
- Verify from DRAFT_PAID with REZZABLE method → stays VERIFICATION_PENDING, PendingVerification populated with code + expiresAt
- Verify from VERIFICATION_FAILED (retry) with REZZABLE → generates new code, voids prior codes

Create `SlParcelVerifyServiceTest.java` — unit test with mocks. Tests:
- Happy path: valid code + headers + ownership match → auction → ACTIVE
- Wrong shard header → InvalidSlHeadersException
- Wrong owner-key → InvalidSlHeadersException
- Expired code → IllegalArgumentException
- Code auction-id-null (somehow PLAYER-type code passed) → IllegalStateException
- Code's auction's parcel_uuid ≠ body.parcel_uuid → IllegalArgumentException
- body.owner_uuid ≠ seller.slAvatarUuid → IllegalArgumentException
- Auction not in VERIFICATION_PENDING → InvalidAuctionStateException
- Parcel locked by another active auction → ParcelAlreadyListedException

Create `ParcelCodeExpiryJobTest.java` — unit test with clock + mocked repos. Tests:
- Stuck REZZABLE auction with expired code → reverted to DRAFT_PAID
- Stuck REZZABLE auction with still-active code → left alone
- Method A auction in VERIFICATION_PENDING → ignored (not REZZABLE)
- Method C auction in VERIFICATION_PENDING → ignored

Create `SlParcelVerifyControllerIntegrationTest.java` — `@SpringBootTest` full-stack test of the LSL endpoint with mocked SL header config.

Run: `cd backend && ./mvnw test -Dtest='*MethodB*,SlParcelVerify*,ParcelCodeExpiry*' -q`
Expected: PASS.

- [ ] **Step 7.9: Postman — add Method B flow requests**

Add to `SLPA / Parcel & Listings / Dev`:
- `Dev / Simulate SL parcel verify` — POST `/api/v1/sl/parcel/verify` with manually-set `X-SecondLife-Shard: Production` and `X-SecondLife-Owner-Key: {{slpaServiceAccountUuid}}` headers. Body captures the current `verificationCode` + parcel UUIDs from prior steps.

- [ ] **Step 7.10: Run full test suite**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS, +~15 tests (~285).

- [ ] **Step 7.11: Commit Task 7**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/verification/
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/
git add backend/src/main/java/com/slparcelauctions/backend/sl/SlParcelVerifyController.java
git add backend/src/main/java/com/slparcelauctions/backend/sl/SlParcelVerifyService.java
git add backend/src/main/java/com/slparcelauctions/backend/sl/dto/SlParcelVerifyRequest.java
git add backend/src/main/java/com/slparcelauctions/backend/BackendApplication.java
git add backend/src/main/resources/application.yml
git add backend/src/test/java/com/slparcelauctions/backend/auction/AuctionVerificationServiceMethodBTest.java
git add backend/src/test/java/com/slparcelauctions/backend/auction/scheduled/
git add backend/src/test/java/com/slparcelauctions/backend/sl/SlParcelVerifyServiceTest.java
git add backend/src/test/java/com/slparcelauctions/backend/sl/SlParcelVerifyControllerIntegrationTest.java
git commit -m "feat(verification): Method B REZZABLE flow + LSL parcel-verify callback + code expiry job

- VerificationCodeService.generateForParcel issues PARCEL codes bound to auction_id
- AuctionVerificationService Method B dispatch + buildPendingVerification
- POST /api/v1/sl/parcel/verify validates SL headers + code + ownership + parcel lock
- ParcelCodeExpiryJob reverts stuck VERIFICATION_PENDING auctions every 5 minutes"
git push
```

---

## Task 8 — Method C SALE_TO_BOT + bot task queue + 48h timeout

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskCompleteRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/bot/DevBotTaskController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java` (wire Method C)
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/BotTaskTimeoutJob.java`
- Modify: `backend/src/main/resources/application.yml` (add `slpa.bot-task.*`)
- Create: test files

- [ ] **Step 8.1: Add config properties**

Edit `backend/src/main/resources/application.yml`. Under `slpa:`:

```yaml
slpa:
  bot-task:
    sentinel-price-lindens: 999999999
    primary-escrow-uuid: 00000000-0000-0000-0000-000000000099
    timeout-hours: 48
    timeout-check-interval: PT15M
```

(The `primary-escrow-uuid` value above is a dev-only placeholder. Production will override via env var. Add to `application-dev.yml` if you want it to differ per-environment.)

- [ ] **Step 8.2: Create BotTaskResponse + BotTaskCompleteRequest DTOs**

Create `backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskResponse.java`:

```java
package com.slparcelauctions.backend.bot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;

public record BotTaskResponse(
        Long id,
        BotTaskType taskType,
        BotTaskStatus status,
        Long auctionId,
        UUID parcelUuid,
        String regionName,
        Long sentinelPrice,
        UUID assignedBotUuid,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {

    public static BotTaskResponse from(BotTask t) {
        return new BotTaskResponse(
                t.getId(), t.getTaskType(), t.getStatus(),
                t.getAuction().getId(), t.getParcelUuid(), t.getRegionName(),
                t.getSentinelPrice(), t.getAssignedBotUuid(), t.getFailureReason(),
                t.getCreatedAt(), t.getCompletedAt());
    }
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/bot/dto/BotTaskCompleteRequest.java`:

```java
package com.slparcelauctions.backend.bot.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BotTaskCompleteRequest(
        @NotNull String result,   // "SUCCESS" or "FAILURE"
        UUID authBuyerId,
        Long salePrice,
        UUID parcelOwner,
        String parcelName,
        Integer areaSqm,
        String regionName,
        Double positionX,
        Double positionY,
        Double positionZ,
        @Size(max = 500) String failureReason) {
}
```

- [ ] **Step 8.3: Create BotTaskService**

Create `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java`:

```java
package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionStatusConstants;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BotTaskService {

    private final BotTaskRepository botTaskRepo;
    private final AuctionRepository auctionRepo;
    private final ParcelRepository parcelRepo;
    private final Clock clock;

    @Value("${slpa.bot-task.sentinel-price-lindens:999999999}")
    private long sentinelPrice;

    @Value("${slpa.bot-task.primary-escrow-uuid}")
    private UUID primaryEscrowUuid;

    @Transactional
    public BotTask createForAuction(Auction auction) {
        // Cancel any prior PENDING/IN_PROGRESS task for this auction
        botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING).stream()
                .filter(t -> t.getAuction().getId().equals(auction.getId()))
                .forEach(t -> {
                    t.setStatus(BotTaskStatus.FAILED);
                    t.setFailureReason("Superseded by retry");
                    t.setCompletedAt(OffsetDateTime.now(clock));
                    botTaskRepo.save(t);
                });

        BotTask task = BotTask.builder()
                .taskType(BotTaskType.VERIFY)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(auction.getParcel().getSlParcelUuid())
                .regionName(auction.getParcel().getRegionName())
                .sentinelPrice(sentinelPrice)
                .build();
        task = botTaskRepo.save(task);
        log.info("Bot task created: id={}, auctionId={}, parcelUuid={}",
                task.getId(), auction.getId(), task.getParcelUuid());
        return task;
    }

    @Transactional
    public BotTask complete(Long taskId, BotTaskCompleteRequest body) {
        BotTask task = botTaskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Bot task not found: " + taskId));
        if (task.getStatus() != BotTaskStatus.PENDING && task.getStatus() != BotTaskStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete bot task in status " + task.getStatus());
        }
        Auction auction = task.getAuction();
        if (auction.getStatus() != AuctionStatus.VERIFICATION_PENDING) {
            throw new InvalidAuctionStateException(
                    auction.getId(), auction.getStatus(), "BOT_COMPLETE");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);

        if ("FAILURE".equalsIgnoreCase(body.result())) {
            task.setStatus(BotTaskStatus.FAILED);
            task.setFailureReason(body.failureReason() == null ? "Bot reported failure" : body.failureReason());
            task.setCompletedAt(now);
            botTaskRepo.save(task);

            auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
            auction.setVerificationNotes("Bot: " + task.getFailureReason());
            auctionRepo.save(auction);
            log.info("Bot task {} FAILED: auctionId={} reason={}", taskId, auction.getId(), task.getFailureReason());
            return task;
        }

        // SUCCESS path
        if (body.authBuyerId() == null || !body.authBuyerId().equals(primaryEscrowUuid)) {
            throw new IllegalArgumentException("authBuyerId mismatch: expected " + primaryEscrowUuid);
        }
        if (body.salePrice() == null || body.salePrice() != sentinelPrice) {
            throw new IllegalArgumentException("salePrice must be " + sentinelPrice);
        }

        // Parcel lock
        if (auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                auction.getParcel().getId(), AuctionStatusConstants.LOCKING_STATUSES, auction.getId())) {
            task.setStatus(BotTaskStatus.FAILED);
            task.setFailureReason("PARCEL_LOCKED");
            task.setCompletedAt(now);
            botTaskRepo.save(task);
            auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
            auctionRepo.save(auction);
            throw new ParcelAlreadyListedException(auction.getParcel().getId(), -1L);
        }

        Parcel parcel = auction.getParcel();
        if (body.parcelOwner() != null) parcel.setOwnerUuid(body.parcelOwner());
        if (body.areaSqm() != null) parcel.setAreaSqm(body.areaSqm());
        if (body.regionName() != null) parcel.setRegionName(body.regionName());
        if (body.positionX() != null) parcel.setPositionX(body.positionX());
        if (body.positionY() != null) parcel.setPositionY(body.positionY());
        if (body.positionZ() != null) parcel.setPositionZ(body.positionZ());
        parcel.setLastChecked(now);
        parcelRepo.save(parcel);

        task.setStatus(BotTaskStatus.COMPLETED);
        task.setCompletedAt(now);
        task.setResultData(Map.of(
                "authBuyerId", body.authBuyerId().toString(),
                "salePrice", body.salePrice(),
                "parcelOwner", body.parcelOwner() == null ? "" : body.parcelOwner().toString()));
        botTaskRepo.save(task);

        OffsetDateTime endsAt = now.plusHours(auction.getDurationHours());
        auction.setStartsAt(now);
        auction.setEndsAt(endsAt);
        auction.setOriginalEndsAt(endsAt);
        auction.setVerifiedAt(now);
        auction.setVerificationTier(VerificationTier.BOT);
        auction.setStatus(AuctionStatus.ACTIVE);
        try {
            auctionRepo.save(auction);
        } catch (DataIntegrityViolationException e) {
            throw new ParcelAlreadyListedException(parcel.getId(), -1L);
        }
        log.info("Bot task {} COMPLETED: auctionId={} → ACTIVE", taskId, auction.getId());
        return task;
    }

    @Transactional(readOnly = true)
    public List<BotTask> findPending() {
        return botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<BotTask> findPendingOlderThan(Duration threshold) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(threshold);
        return botTaskRepo.findByStatusAndCreatedAtBefore(BotTaskStatus.PENDING, cutoff);
    }

    @Transactional
    public void markTimedOut(BotTask task) {
        task.setStatus(BotTaskStatus.FAILED);
        task.setFailureReason("TIMEOUT");
        task.setCompletedAt(OffsetDateTime.now(clock));
        botTaskRepo.save(task);

        Auction auction = task.getAuction();
        if (auction.getStatus() == AuctionStatus.VERIFICATION_PENDING) {
            auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
            auction.setVerificationNotes("Bot did not complete verification within the 48-hour window.");
            auctionRepo.save(auction);
        }
    }

    public Long getPrimaryEscrowUuidAsLongMarker() {
        // Not actually used; getter exists so tests can inspect config.
        return primaryEscrowUuid.hashCode() & 0xffffffffL;
    }
}
```

- [ ] **Step 8.4: Wire Method C into AuctionVerificationService**

Edit `AuctionVerificationService.java` — inject `BotTaskService`, replace the SALE_TO_BOT switch arm:

```java
    private final BotTaskService botTaskService;

    // ...

            case SALE_TO_BOT -> dispatchMethodC(a);

    // Add:
    private Auction dispatchMethodC(Auction a) {
        botTaskService.createForAuction(a);
        return a;
    }

    // Replace buildBotTaskPending:
    private PendingVerification buildBotTaskPending(Auction a) {
        return botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING).stream()
                .filter(t -> t.getAuction().getId().equals(a.getId()))
                .findFirst()
                .map(t -> new PendingVerification(
                        VerificationMethod.SALE_TO_BOT,
                        null, null,
                        t.getId(),
                        "Set your parcel for sale to SLPAEscrow Resident (UUID: "
                                + primaryEscrowUuid + ") at L$999,999,999. "
                                + "A verification worker will confirm within 48 hours."))
                .orElse(null);
    }
```

Add `BotTaskRepository` + config value injections to the service (matching pattern for `primaryEscrowUuid`).

- [ ] **Step 8.5: Create BotTaskController + DevBotTaskController**

Create `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java`:

```java
package com.slparcelauctions.backend.bot;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Public endpoints for the SL bot service (Epic 06). Sub-spec 1 ships these
 * without auth; Epic 06 will add bot worker authentication. See DEFERRED_WORK.md.
 */
@RestController
@RequestMapping("/api/v1/bot/tasks")
@RequiredArgsConstructor
public class BotTaskController {

    private final BotTaskService service;

    @GetMapping("/pending")
    public List<BotTaskResponse> pending() {
        return service.findPending().stream().map(BotTaskResponse::from).toList();
    }

    @PutMapping("/{taskId}")
    public BotTaskResponse complete(
            @PathVariable Long taskId,
            @Valid @RequestBody BotTaskCompleteRequest body) {
        return BotTaskResponse.from(service.complete(taskId, body));
    }
}
```

Ensure `SecurityConfig` permits `/api/v1/bot/tasks/**` (`permitAll`) — note this in a FOOTGUNS entry in Task 10.

Create `backend/src/main/java/com/slparcelauctions/backend/bot/DevBotTaskController.java`:

```java
package com.slparcelauctions.backend.bot;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Dev-profile stand-in for the bot worker. Identical behavior to the real
 * PUT /api/v1/bot/tasks/{id}, but routed under /dev/ so it's clear it's a
 * local testing shortcut.
 */
@RestController
@RequestMapping("/api/v1/dev/bot/tasks")
@RequiredArgsConstructor
@Profile("dev")
public class DevBotTaskController {

    private final BotTaskService service;

    @PostMapping("/{taskId}/complete")
    public BotTaskResponse complete(
            @PathVariable Long taskId,
            @Valid @RequestBody BotTaskCompleteRequest body) {
        return BotTaskResponse.from(service.complete(taskId, body));
    }
}
```

- [ ] **Step 8.6: Create BotTaskTimeoutJob**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/BotTaskTimeoutJob.java`:

```java
package com.slparcelauctions.backend.auction.scheduled;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sweeps PENDING bot tasks older than the configured timeout (48 h by default)
 * and fails them. Transitions the associated auction to VERIFICATION_FAILED
 * (not a refund — seller retries via PUT /verify).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BotTaskTimeoutJob {

    private final BotTaskService service;

    @Value("${slpa.bot-task.timeout-hours:48}")
    private int timeoutHours;

    @Scheduled(fixedDelayString = "${slpa.bot-task.timeout-check-interval:PT15M}")
    public void sweep() {
        Duration threshold = Duration.ofHours(timeoutHours);
        List<BotTask> timedOut = service.findPendingOlderThan(threshold);
        timedOut.forEach(service::markTimedOut);
        if (!timedOut.isEmpty()) {
            log.info("BotTaskTimeoutJob: timed out {} bot tasks", timedOut.size());
        }
    }
}
```

- [ ] **Step 8.7: Write tests for Method C paths**

Create `AuctionVerificationServiceMethodCTest.java` — unit test for Method C dispatch. Tests:
- Verify from DRAFT_PAID with SALE_TO_BOT → BotTask created with correct parcel UUID, region, sentinel price
- Verify from VERIFICATION_FAILED (retry) with SALE_TO_BOT → prior task cancelled, new task created
- buildPendingVerification for SALE_TO_BOT → returns PendingVerification with botTaskId + instructions

Create `BotTaskServiceTest.java` — unit test for service. Tests:
- createForAuction → bot_task persisted with status=PENDING
- complete SUCCESS with correct authBuyerId + salePrice → task COMPLETED, auction → ACTIVE (verification_tier=BOT)
- complete SUCCESS with wrong authBuyerId → IllegalArgumentException
- complete SUCCESS with wrong salePrice → IllegalArgumentException
- complete FAILURE → task FAILED, auction → VERIFICATION_FAILED, no refund
- complete when another auction holds the parcel lock → task FAILED, auction → VERIFICATION_FAILED, throws ParcelAlreadyListedException
- complete on task already COMPLETED → IllegalStateException
- findPendingOlderThan → returns only PENDING tasks older than threshold
- markTimedOut → task FAILED with reason=TIMEOUT, auction VERIFICATION_FAILED

Create `BotTaskTimeoutJobTest.java` — unit test with fake clock. Create 3 fixture tasks: one 50h old (past threshold), one 10h old (within threshold), one 50h old but already COMPLETED. Invoke sweep. Assert only the first gets timed out.

Create `BotTaskControllerIntegrationTest.java` + `DevBotTaskControllerTest.java` — full-stack tests.

Run: `cd backend && ./mvnw test -Dtest='*MethodC*,BotTask*' -q`
Expected: PASS.

- [ ] **Step 8.8: Postman — add Method C requests**

Add:
- `Auction / Verify` already exists from Task 6 — works for all methods via method-based dispatch
- `Dev / Simulate bot completion` — POST `/api/v1/dev/bot/tasks/{{botTaskId}}/complete`
- `Bot / Get pending tasks` — GET `/api/v1/bot/tasks/pending` (no auth)
- `Bot / Report task result` — PUT `/api/v1/bot/tasks/{{botTaskId}}` (same shape as dev stub, for real bot service)

- [ ] **Step 8.9: Run full test suite**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS, +~15 tests (~300).

- [ ] **Step 8.10: Commit Task 8**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/scheduled/BotTaskTimeoutJob.java
git add backend/src/main/resources/application.yml
git add backend/src/test/java/com/slparcelauctions/backend/bot/
git add backend/src/test/java/com/slparcelauctions/backend/auction/AuctionVerificationServiceMethodCTest.java
git commit -m "feat(bot): Method C SALE_TO_BOT flow + bot task queue + 48h timeout job

- AuctionVerificationService Method C dispatch creates PENDING bot_task
- BotTaskService handles SUCCESS (verifies escrow UUID + sentinel price, auction → ACTIVE/BOT tier) and FAILURE
- Public /api/v1/bot/tasks/pending and /api/v1/bot/tasks/{id} for Epic 06 bot workers (no auth yet)
- Dev-profile /api/v1/dev/bot/tasks/{id}/complete stand-in
- BotTaskTimeoutJob sweeps PENDING tasks older than 48h every 15 minutes"
git push
```

---

## Task 9 — Parcel tags + photo pipeline refactor + photo endpoints

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/parceltag/dto/ParcelTagGroupResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/parceltag/ParcelTagService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/parceltag/ParcelTagController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/media/ImageFormat.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/media/ImageUploadValidator.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/AvatarImageProcessor.java` (delegate validation)
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/ListingPhotoProcessor.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/PhotoLimitExceededException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java` (add 413 mapping)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java` (wire photos into responses)
- Modify: `backend/src/main/resources/application.yml` (add `slpa.photos.*`)
- Create: tests

- [ ] **Step 9.1: Add config properties**

Edit `backend/src/main/resources/application.yml`. Under `slpa:`:

```yaml
slpa:
  photos:
    max-per-listing: 10
    max-bytes: 2097152
    max-dimension: 4096
```

Also add to root:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 10MB
```

(If `max-file-size` already exists from Epic 02 at 2MB, confirm it's still 2MB — applies to both avatar and photo uploads.)

- [ ] **Step 9.2: Create ParcelTagGroupResponse**

Create `backend/src/main/java/com/slparcelauctions/backend/parceltag/dto/ParcelTagGroupResponse.java`:

```java
package com.slparcelauctions.backend.parceltag.dto;

import java.util.List;

public record ParcelTagGroupResponse(
        String category,
        List<ParcelTagResponse> tags) {
}
```

- [ ] **Step 9.3: Create ParcelTagService**

Create `backend/src/main/java/com/slparcelauctions/backend/parceltag/ParcelTagService.java`:

```java
package com.slparcelauctions.backend.parceltag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.parceltag.dto.ParcelTagGroupResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ParcelTagService {

    private final ParcelTagRepository repo;

    @Transactional(readOnly = true)
    public List<ParcelTagGroupResponse> listGroupedActive() {
        Map<String, List<ParcelTagResponse>> grouped = new LinkedHashMap<>();
        for (ParcelTag t : repo.findByActiveTrueOrderByCategoryAscSortOrderAsc()) {
            grouped.computeIfAbsent(t.getCategory(), k -> new ArrayList<>())
                    .add(ParcelTagResponse.from(t));
        }
        return grouped.entrySet().stream()
                .map(e -> new ParcelTagGroupResponse(e.getKey(), e.getValue()))
                .toList();
    }
}
```

- [ ] **Step 9.4: Create ParcelTagController**

Create `backend/src/main/java/com/slparcelauctions/backend/parceltag/ParcelTagController.java`:

```java
package com.slparcelauctions.backend.parceltag;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.parceltag.dto.ParcelTagGroupResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/parcel-tags")
@RequiredArgsConstructor
public class ParcelTagController {

    private final ParcelTagService service;

    @GetMapping
    public List<ParcelTagGroupResponse> list() {
        return service.listGroupedActive();
    }
}
```

- [ ] **Step 9.5: Verify parcel_tags table has seed data**

The existing `V1__core_tables.sql` (deleted in Task 1) inserted 25 parcel tag rows. Since the migration is gone, we need to seed the table differently. Two options:

**Option A:** Seed via `@PostConstruct` in `ParcelTagService` — if no active tags exist, insert the 25 defaults.

**Option B:** Commit a `data.sql` file that Hibernate reads on boot (Spring Boot picks up `src/main/resources/data.sql` automatically with `spring.jpa.defer-datasource-initialization: true`).

Use **Option A** to keep seed logic colocated with the service:

Add to `ParcelTagService`:

```java
    @org.springframework.boot.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaultTagsIfEmpty() {
        if (repo.count() > 0) return;
        int order = 0;
        for (String[] row : SEED_DATA) {
            ParcelTag t = ParcelTag.builder()
                    .code(row[0])
                    .label(row[1])
                    .category(row[2])
                    .description(row[3])
                    .sortOrder(++order)
                    .active(true)
                    .build();
            repo.save(t);
        }
    }

    private static final String[][] SEED_DATA = {
            {"WATERFRONT", "Waterfront", "Terrain / Environment", "Ocean, lake, or river border"},
            {"SAILABLE", "Sailable", "Terrain / Environment", "Connected to navigable water (Linden waterways)"},
            {"GRASS", "Grass", "Terrain / Environment", "Grass terrain"},
            {"SNOW", "Snow", "Terrain / Environment", "Snow terrain"},
            {"SAND", "Sand", "Terrain / Environment", "Sand or beach terrain"},
            {"MOUNTAIN", "Mountain", "Terrain / Environment", "Elevated or hilly terrain"},
            {"FOREST", "Forest", "Terrain / Environment", "Wooded area"},
            {"FLAT", "Flat", "Terrain / Environment", "Level terrain, good for building"},
            {"STREETFRONT", "Streetfront", "Roads / Access", "Borders a Linden road"},
            {"ROADSIDE", "Roadside", "Roads / Access", "Near (but not directly on) a Linden road"},
            {"RAILWAY", "Railway", "Roads / Access", "Near Linden railroad / SLRR"},
            {"CORNER_LOT", "Corner Lot", "Location Features", "Parcel on a corner (two road or water sides)"},
            {"HILLTOP", "Hilltop", "Location Features", "Elevated with views"},
            {"ISLAND", "Island", "Location Features", "Surrounded by water"},
            {"PENINSULA", "Peninsula", "Location Features", "Water on three sides"},
            {"SHELTERED", "Sheltered", "Location Features", "Enclosed or private feeling, surrounded by terrain"},
            {"RESIDENTIAL", "Residential", "Neighbors / Context", "Residential neighborhood"},
            {"COMMERCIAL", "Commercial", "Neighbors / Context", "Commercial or shopping area"},
            {"INFOHUB_ADJACENT", "Infohub Adjacent", "Neighbors / Context", "Near a Linden infohub"},
            {"PROTECTED_LAND", "Protected Land", "Neighbors / Context", "Adjacent to Linden-owned protected land"},
            {"SCENIC", "Scenic", "Neighbors / Context", "Notable views or landscape"},
            {"HIGH_PRIM", "High Prim", "Parcel Features", "Higher-than-baseline land impact allowance"},
            {"MUSIC", "Music Stream", "Parcel Features", "Parcel has music stream URL set"},
            {"MEDIA", "Media Enabled", "Parcel Features", "Parcel has media URL set"},
            {"RARE", "Rare", "Miscellaneous", "Scarcity-based premium parcel type"}
    };
```

- [ ] **Step 9.6: Create ImageFormat + ImageUploadValidator**

Create `backend/src/main/java/com/slparcelauctions/backend/media/ImageFormat.java`:

```java
package com.slparcelauctions.backend.media;

import java.util.Locale;
import java.util.Optional;

public enum ImageFormat {
    JPEG,
    PNG,
    WEBP;

    public static Optional<ImageFormat> fromImageIoName(String name) {
        if (name == null) return Optional.empty();
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> Optional.of(JPEG);
            case "png" -> Optional.of(PNG);
            case "webp" -> Optional.of(WEBP);
            default -> Optional.empty();
        };
    }

    public String extension() {
        return switch (this) {
            case JPEG -> "jpg";
            case PNG -> "png";
            case WEBP -> "webp";
        };
    }

    public String contentType() {
        return switch (this) {
            case JPEG -> "image/jpeg";
            case PNG -> "image/png";
            case WEBP -> "image/webp";
        };
    }
}
```

Create `backend/src/main/java/com/slparcelauctions/backend/media/ImageUploadValidator.java`:

```java
package com.slparcelauctions.backend.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared bytes-level image validation used by both AvatarImageProcessor
 * and ListingPhotoProcessor. Sniffs format via ImageIO (trusts the bytes,
 * not the multipart Content-Type header, which is client-controlled).
 */
@Component
@Slf4j
public class ImageUploadValidator {

    public record ValidationResult(ImageFormat format, BufferedImage image, int width, int height) {}

    /**
     * @param inputBytes raw upload bytes
     * @param maxBytes 0 = no upper limit
     * @param maxDimension 0 = no dimension cap
     */
    public ValidationResult validate(byte[] inputBytes, long maxBytes, int maxDimension) {
        if (maxBytes > 0 && inputBytes.length > maxBytes) {
            throw new UnsupportedImageFormatException(
                    "File too large: " + inputBytes.length + " bytes > " + maxBytes);
        }

        BufferedImage image;
        ImageFormat format;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(inputBytes))) {
            if (iis == null) {
                throw new UnsupportedImageFormatException("Failed to open image stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new UnsupportedImageFormatException("Unrecognized image format");
            }
            ImageReader reader = readers.next();
            format = ImageFormat.fromImageIoName(reader.getFormatName())
                    .orElseThrow(() -> new UnsupportedImageFormatException(
                            "Format '" + reader.getFormatName() + "' not allowed. Use JPEG, PNG, or WebP."));
            reader.setInput(iis);
            try {
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to decode image: " + e.getMessage(), e);
        }

        int w = image.getWidth();
        int h = image.getHeight();
        if (maxDimension > 0 && (w > maxDimension || h > maxDimension)) {
            throw new UnsupportedImageFormatException(
                    "Image dimensions " + w + "x" + h + " exceed max " + maxDimension);
        }
        return new ValidationResult(format, image, w, h);
    }
}
```

- [ ] **Step 9.7: Refactor AvatarImageProcessor to delegate validation**

Edit `backend/src/main/java/com/slparcelauctions/backend/user/AvatarImageProcessor.java`. Replace the inline validation logic (ImageIO sniffing + format allowlist) with a delegated call to `ImageUploadValidator.validate()`. The avatar-specific transforms (center crop, three-size resize via Thumbnailator) stay.

New `AvatarImageProcessor` sketch:

```java
@Component
@RequiredArgsConstructor
public class AvatarImageProcessor {
    public static final int[] SIZES = {64, 128, 256};

    private final ImageUploadValidator validator;

    public Map<Integer, byte[]> process(byte[] inputBytes) {
        ImageUploadValidator.ValidationResult result = validator.validate(inputBytes, 0, 0);
        // No dimension cap for avatars — Thumbnailator resizes to 64/128/256 anyway
        // No byte cap here — spring multipart config enforces at the web layer
        BufferedImage original = result.image();

        Map<Integer, byte[]> out = new LinkedHashMap<>(3);
        for (int size : SIZES) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                Thumbnails.of(original)
                        .crop(Positions.CENTER)
                        .size(size, size)
                        .outputFormat("png")
                        .toOutputStream(baos);
                out.put(size, baos.toByteArray());
            } catch (IOException e) {
                throw new UnsupportedImageFormatException(
                        "Failed to resize image to " + size + "px: " + e.getMessage(), e);
            }
        }
        return out;
    }
}
```

Run existing avatar tests to confirm nothing broke:
```bash
cd backend && ./mvnw test -Dtest=AvatarImageProcessorTest -q
```
Expected: PASS (no behavior change).

- [ ] **Step 9.8: Create ListingPhotoProcessor**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/ListingPhotoProcessor.java`:

```java
package com.slparcelauctions.backend.auction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;

/**
 * Validates listing photos and outputs them unchanged (preserves aspect ratio
 * and dimensions). Strips metadata (EXIF, etc.) by re-encoding through ImageIO.
 */
@Component
@RequiredArgsConstructor
public class ListingPhotoProcessor {

    public record ProcessedPhoto(byte[] bytes, ImageFormat format, long sizeBytes) {}

    private final ImageUploadValidator validator;

    @Value("${slpa.photos.max-bytes:2097152}")
    private long maxBytes;

    @Value("${slpa.photos.max-dimension:4096}")
    private int maxDimension;

    public ProcessedPhoto process(byte[] inputBytes) {
        ImageUploadValidator.ValidationResult result = validator.validate(inputBytes, maxBytes, maxDimension);
        // Re-encode via ImageIO to strip metadata. Output format matches input format.
        String writerFormat = switch (result.format()) {
            case JPEG -> "jpg";
            case PNG -> "png";
            case WEBP -> "webp";
        };
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (!ImageIO.write(result.image(), writerFormat, baos)) {
                throw new UnsupportedImageFormatException(
                        "No ImageIO writer for format " + result.format());
            }
            byte[] out = baos.toByteArray();
            return new ProcessedPhoto(out, result.format(), out.length);
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to re-encode image: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 9.9: Create PhotoLimitExceededException + handler**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/exception/PhotoLimitExceededException.java`:

```java
package com.slparcelauctions.backend.auction.exception;

public class PhotoLimitExceededException extends RuntimeException {

    public PhotoLimitExceededException(int current, int max) {
        super("Photo limit exceeded: " + current + " / " + max);
    }
}
```

Edit `AuctionExceptionHandler.java`, add:

```java
    @ExceptionHandler(PhotoLimitExceededException.class)
    public ProblemDetail handlePhotoLimit(PhotoLimitExceededException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        pd.setTitle("Photo Limit Exceeded");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PHOTO_LIMIT_EXCEEDED");
        return pd;
    }
```

- [ ] **Step 9.10: Create AuctionPhotoService**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoService.java`:

```java
package com.slparcelauctions.backend.auction;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.PhotoLimitExceededException;
import com.slparcelauctions.backend.storage.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionPhotoService {

    private final AuctionService auctionService;
    private final AuctionPhotoRepository photoRepo;
    private final ListingPhotoProcessor processor;
    private final StorageService storage;

    @Value("${slpa.photos.max-per-listing:10}")
    private int maxPerListing;

    @Transactional
    public AuctionPhoto upload(Long auctionId, Long sellerId, MultipartFile file) throws java.io.IOException {
        Auction auction = auctionService.loadForSeller(auctionId, sellerId);
        if (auction.getStatus() != AuctionStatus.DRAFT && auction.getStatus() != AuctionStatus.DRAFT_PAID) {
            throw new InvalidAuctionStateException(auctionId, auction.getStatus(), "UPLOAD_PHOTO");
        }
        long currentCount = photoRepo.countByAuctionId(auctionId);
        if (currentCount >= maxPerListing) {
            throw new PhotoLimitExceededException((int) currentCount, maxPerListing);
        }

        ListingPhotoProcessor.ProcessedPhoto processed = processor.process(file.getBytes());
        String objectKey = "listings/" + auctionId + "/" + UUID.randomUUID() + "." + processed.format().extension();
        storage.put(objectKey, processed.bytes(), processed.format().contentType());

        int nextSort = (int) currentCount + 1;
        AuctionPhoto photo = AuctionPhoto.builder()
                .auction(auction)
                .objectKey(objectKey)
                .contentType(processed.format().contentType())
                .sizeBytes(processed.sizeBytes())
                .sortOrder(nextSort)
                .build();
        return photoRepo.save(photo);
    }

    @Transactional
    public void delete(Long auctionId, Long photoId, Long sellerId) {
        Auction auction = auctionService.loadForSeller(auctionId, sellerId);
        if (auction.getStatus() != AuctionStatus.DRAFT && auction.getStatus() != AuctionStatus.DRAFT_PAID) {
            throw new InvalidAuctionStateException(auctionId, auction.getStatus(), "DELETE_PHOTO");
        }
        AuctionPhoto photo = photoRepo.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found: " + photoId));
        if (!photo.getAuction().getId().equals(auctionId)) {
            throw new IllegalArgumentException("Photo " + photoId + " does not belong to auction " + auctionId);
        }
        if (!photo.getObjectKey().startsWith("listings/" + auctionId + "/")) {
            throw new IllegalStateException("Object key mismatch for photo " + photoId);
        }
        storage.delete(photo.getObjectKey());
        photoRepo.delete(photo);
    }

    @Transactional(readOnly = true)
    public byte[] fetchBytes(Long auctionId, Long photoId) {
        AuctionPhoto photo = photoRepo.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found: " + photoId));
        if (!photo.getAuction().getId().equals(auctionId)) {
            throw new IllegalArgumentException("Photo does not belong to specified auction");
        }
        return storage.get(photo.getObjectKey());
    }

    @Transactional(readOnly = true)
    public List<AuctionPhoto> list(Long auctionId) {
        return photoRepo.findByAuctionIdOrderBySortOrderAsc(auctionId);
    }
}
```

Note: `StorageService` is the existing component from Epic 02 (`backend/src/main/java/.../storage/StorageService.java`). Inspect its public API — the methods `put(key, bytes, contentType)`, `delete(key)`, `get(key)` are the conceptual calls; if the real method names differ, use those.

- [ ] **Step 9.11: Create AuctionPhotoController**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoController.java`:

```java
package com.slparcelauctions.backend.auction;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.dto.AuctionPhotoResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/photos")
@RequiredArgsConstructor
public class AuctionPhotoController {

    private final AuctionPhotoService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuctionPhotoResponse upload(
            @PathVariable Long auctionId,
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {
        Long userId = Long.valueOf(auth.getName());
        AuctionPhoto saved = service.upload(auctionId, userId, file);
        return AuctionPhotoResponse.from(saved);
    }

    @DeleteMapping("/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long auctionId,
            @PathVariable Long photoId,
            Authentication auth) {
        Long userId = Long.valueOf(auth.getName());
        service.delete(auctionId, photoId, userId);
    }

    @GetMapping("/{photoId}/bytes")
    public ResponseEntity<byte[]> bytes(
            @PathVariable Long auctionId,
            @PathVariable Long photoId) {
        byte[] bytes = service.fetchBytes(auctionId, photoId);
        AuctionPhoto photo = service.list(auctionId).stream()
                .filter(p -> p.getId().equals(photoId))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, photo.getContentType())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(bytes);
    }
}
```

- [ ] **Step 9.12: Wire photos into AuctionDtoMapper**

Edit `AuctionDtoMapper.java`. Replace `List.of()` in both `toPublicResponse` and `toSellerResponse` with a parameterized photo list. Simplest approach: add a `photoRepo` dependency to the mapper and query, OR have the controller pass the photos in as a method parameter. The latter is cleaner; update both mapper methods to accept a `List<AuctionPhoto>` argument:

```java
    public SellerAuctionResponse toSellerResponse(Auction a, PendingVerification pending, List<AuctionPhoto> photos) {
        // ... map photos to AuctionPhotoResponse via AuctionPhotoResponse.from ...
    }

    public PublicAuctionResponse toPublicResponse(Auction a, List<AuctionPhoto> photos) {
        // ...
    }
```

Update all call sites in `AuctionController` and `DevAuctionController` to query photos (inject `AuctionPhotoRepository`) and pass them in. Or introduce a thin `AuctionResponseAssembler` service that composes the full DTO including photos — cleaner separation.

Either path works; pick one and be consistent. A bare-minimum approach: add `photoRepo.findByAuctionIdOrderBySortOrderAsc(a.getId())` call inside the mapper methods. Document the N+1 implication and defer optimization to a later epic.

- [ ] **Step 9.13: Write tests**

Create:
- `ParcelTagServiceTest.java` — seed logic runs once, returns grouped tags
- `ParcelTagControllerIntegrationTest.java` — GET returns 25 tags across categories
- `ImageUploadValidatorTest.java` — JPEG/PNG/WebP pass; BMP rejected; oversized bytes rejected; over-dimension rejected
- `ListingPhotoProcessorTest.java` — input/output round-trip preserves format; WebP → WebP
- `AuctionPhotoServiceTest.java` — upload happy path, 11th upload rejected, BMP rejected, state-check on DRAFT/DRAFT_PAID only
- `AuctionPhotoControllerIntegrationTest.java` — full-stack upload + delete + GET bytes with correct Content-Type + cache header
- `AuctionServiceTest` update — tag validation via resolved codes with persisted tag rows

Also re-run `AvatarImageProcessorTest` to confirm the refactor didn't break avatar behavior.

Run: `cd backend && ./mvnw test -q`
Expected: BUILD SUCCESS, +~20 tests (~320).

- [ ] **Step 9.14: Postman — add tags + photo requests**

- `Tags / List` — GET `/api/v1/parcel-tags`
- `Photos / Upload` — POST `/api/v1/auctions/{{auctionId}}/photos` with form-data `file` field
- `Photos / Delete` — DELETE `/api/v1/auctions/{{auctionId}}/photos/{{photoId}}`

- [ ] **Step 9.15: Commit Task 9**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/parceltag/
git add backend/src/main/java/com/slparcelauctions/backend/media/
git add backend/src/main/java/com/slparcelauctions/backend/user/AvatarImageProcessor.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/ListingPhotoProcessor.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoService.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoController.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/exception/PhotoLimitExceededException.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java
git add backend/src/main/resources/application.yml
git add backend/src/test/java/com/slparcelauctions/backend/parceltag/
git add backend/src/test/java/com/slparcelauctions/backend/media/
git add backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhoto*.java
git add backend/src/test/java/com/slparcelauctions/backend/auction/ListingPhotoProcessorTest.java
git commit -m "feat(media+tags+photos): extract ImageUploadValidator, add ListingPhotoProcessor, photo and tag endpoints

- Shared ImageUploadValidator handles byte-level format sniffing + dimension checks
- AvatarImageProcessor refactored to delegate validation (behavior unchanged)
- ListingPhotoProcessor preserves aspect ratio + dimensions; 4096 px cap; EXIF stripped via re-encode
- Auction photo endpoints: upload (multipart), delete, GET bytes (proxy from MinIO)
- GET /api/v1/parcel-tags returns 25 active tags grouped by category
- Seed data applied on first boot via @EventListener in ParcelTagService"
git push
```

---

## Task 10 — Final polish + PR

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/integration/FullFlowSmokeTest.java` (integration smoke covering all three methods)
- Modify: `README.md`
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `docs/implementation/DEFERRED_WORK.md`

- [ ] **Step 10.1: Create integration smoke test**

Create `backend/src/test/java/com/slparcelauctions/backend/integration/FullFlowSmokeTest.java`. Full `@SpringBootTest` exercising the end-to-end flows for all three methods:

1. Register a new user → verify SL avatar via `DevSlSimulateController` (Epic 02) → user is verified.
2. POST /api/v1/parcels/lookup with a Mainland UUID (mock World+Map clients via `@MockBean`) → parcel row created.
3. POST /api/v1/auctions (Method A) → DRAFT.
4. POST /api/v1/dev/auctions/{id}/pay → DRAFT_PAID.
5. PUT /api/v1/auctions/{id}/verify → Method A synchronous → ACTIVE.
6. Repeat steps 3-5 for Method B: POST auction with REZZABLE → pay → verify → code returned → call /sl/parcel/verify with SL headers → ACTIVE.
7. Repeat for Method C: POST with SALE_TO_BOT → pay → verify → bot_task returned → POST /dev/bot/tasks/{id}/complete SUCCESS → ACTIVE.
8. Cancel a DRAFT_PAID auction → CANCELLED + ListingFeeRefund PENDING row exists.
9. GET /api/v1/auctions/{id} as non-seller while ACTIVE → PublicAuctionResponse with status=ACTIVE.
10. Cancel the ACTIVE → GET as non-seller returns status=ENDED.

Run: `cd backend && ./mvnw test -Dtest=FullFlowSmokeTest -q`
Expected: PASS.

- [ ] **Step 10.2: Run complete verify chain**

```bash
cd backend && ./mvnw test -q
cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev &
sleep 15
# (Postman smoke from Epic 03 spec §17 bullet list — full manual flow for each method)
```

- [ ] **Step 10.3: README sweep**

Edit `README.md`. Add/update sections:
- Endpoint catalog: list all new endpoints (parcels, auctions, verify dispatch, LSL callback, bot service, dev stubs, tags, photos)
- Mention new entities: `BotTask`, `CancellationLog`, `ListingFeeRefund`, `AuctionPhoto`
- Mention `MainlandContinents` static helper
- Bump backend test count (from ~191 to ~320+)
- Update Epic 03 status: sub-spec 1 merged to `dev`

- [ ] **Step 10.4: FOOTGUNS entries**

Edit `docs/implementation/FOOTGUNS.md`. Add entries:

- **F.XX — Continent bounding boxes are static data.** Sourced from the SL wiki's ContinentDetector as of 2026-04-16. If Linden Lab adds a new Mainland continent, the `MainlandContinents.BOXES` list needs updating. Annual review recommended.
- **F.XX — Flyway is disabled; entities are the schema.** Deleted V1/V2 migrations. Running `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` on a fresh Postgres creates the schema via Hibernate DDL-auto. On existing dev DBs, the `flyway_schema_history` table is harmless but can be dropped.
- **F.XX — `PublicAuctionStatus` enum is a privacy boundary.** Only has `ACTIVE` and `ENDED` values. `AuctionDtoMapper.toPublicStatus` is exhaustive — a compile error fires if a new `AuctionStatus` value is added without a mapping. This is intentional: the 4 terminal statuses (COMPLETED, CANCELLED, EXPIRED, DISPUTED) must never leak to non-sellers.
- **F.XX — Parcel locking has two layers.** Service-layer check (`assertParcelNotLocked`) + Postgres partial unique index. If either layer is bypassed (e.g., direct DB writes, raw SQL migrations), duplicate ACTIVE auctions on the same parcel become possible. Don't write bypass paths.
- **F.XX — Bot endpoints have no auth in sub-spec 1.** `/api/v1/bot/tasks/**` is `permitAll` because Epic 06 hasn't shipped bot authentication. Before shipping to production, Epic 06 MUST add mTLS or bearer token auth.
- **F.XX — SL `/sl/parcel/verify` trusts headers.** `X-SecondLife-Shard` and `X-SecondLife-Owner-Key` are the only trust boundary. Linden's proxy validates them. Attackers outside the grid cannot forge them — but local dev testing requires manually setting the headers in Postman.
- **F.XX — WebClient retry only covers 5xx.** `SlWorldApiClient.isTransient` treats 5xx and network errors as retryable. 4xx (especially 429 rate limit) is not retried. If SL imposes rate limits, callers get fast-failures and need to back off externally.
- **F.XX — Photo `GET bytes` proxy is unauthenticated.** Public read for listing photos — ACTIVE listings expose photos to the world, drafts to anyone with the URL. If privacy of draft photos matters, add auth on the bytes endpoint.
- **F.XX — Jackson `fail-on-unknown-properties: true` globally.** Clients sending extra fields (e.g. `{"parcelId": 42, "evilField": "x"}`) get 400. This is set in `application.yml`. Adding new optional fields is safe — clients don't send them by default.

(Use the next available F.xx numbers after the current max in FOOTGUNS.md — run `Grep` to find the max before editing.)

- [ ] **Step 10.5: DEFERRED_WORK.md updates**

Edit `docs/implementation/DEFERRED_WORK.md`. Add:

- **Bot service authentication (Epic 06)** — `/api/v1/bot/tasks/pending` and `/api/v1/bot/tasks/{taskId}` ship without auth in sub-spec 1. Must not deploy to production until Epic 06 adds mTLS or bearer-token auth.
- **Listing fee refund processor (Epic 05)** — `ListingFeeRefund` rows accumulate in PENDING status. Epic 05 escrow integration will poll and issue actual L$ refunds via the in-world escrow terminal.
- **Primary escrow UUID + SLPA service account UUID configuration (production)** — `slpa.bot-task.primary-escrow-uuid` and `slpa.sl.trusted-owner-keys` carry placeholder values in dev. Production deployment must set real UUIDs via env vars.

Remove entries that this sub-spec completed — none from the current ledger match (sub-spec 2 listing UI and sub-spec 3 ownership monitoring are still deferred).

- [ ] **Step 10.6: Commit docs + smoke test**

```bash
git add backend/src/test/java/com/slparcelauctions/backend/integration/FullFlowSmokeTest.java
git add README.md
git add docs/implementation/FOOTGUNS.md
git add docs/implementation/DEFERRED_WORK.md
git commit -m "docs: README sweep, FOOTGUNS entries, and DEFERRED_WORK updates for Epic 03 sub-spec 1"
git push
```

- [ ] **Step 10.7: Open PR into `dev`**

```bash
gh pr create --base dev --title "Epic 03 sub-spec 1: parcel verification + listing lifecycle (backend)" --body "$(cat <<'EOF'
## Summary
- Parcel lookup via World API + Map API with static Mainland continent check (no Grid Survey)
- Auction CRUD + DRAFT → DRAFT_PAID → VERIFICATION_PENDING → ACTIVE state machine
- Unified PUT /verify dispatching to Method A (sync) / B (async LSL) / C (async bot)
- Seller vs public DTO split with type-enforced status collapse (4 terminal statuses hidden as "ENDED")
- Parcel locking enforcement: service-layer check + Postgres partial unique index
- Cancellation with refund records + cancelled_with_bids counter
- Tag reference + photo upload endpoints (preserves aspect ratio via ListingPhotoProcessor)
- Dev-profile stubs for listing fee payment and bot completion
- Scheduled jobs for PARCEL code expiry (5 min) and bot task timeout (15 min / 48 h threshold)
- Jsoup added for HTML parsing; Flyway migrations V1/V2 deleted (entities are schema source of truth)

## Test plan
- [ ] ./mvnw test BUILD SUCCESS (~320 tests, from ~191 baseline)
- [ ] Full Postman smoke for all three verification methods end-to-end
- [ ] GET /auctions/{id} as seller returns full internal data; as non-seller returns PublicAuctionResponse (ACTIVE or ENDED only)
- [ ] Cancelling a DRAFT_PAID auction creates a PENDING ListingFeeRefund row
- [ ] Parcel locking: two concurrent Method A verifies on the same parcel — one wins, one gets 409

Spec: docs/superpowers/specs/2026-04-16-epic-03-sub-1-parcel-verification-listing-lifecycle.md
Plan: docs/superpowers/plans/2026-04-16-epic-03-sub-1-parcel-verification-listing-lifecycle.md
EOF
)"
```

- [ ] **Step 10.8: Return to `dev` locally**

```bash
git checkout dev
git pull origin dev
```

---

## Done definition

Sub-spec 1 is done when all of the following are true:

- [ ] All 10 tasks committed and pushed in order
- [ ] `./mvnw test` → BUILD SUCCESS, ~320 tests
- [ ] Hibernate DDL-auto created all new tables on first boot (`bot_tasks`, `cancellation_logs`, `listing_fee_refunds`, `auction_photos`) and new columns (`users.cancelled_with_bids` was pre-existing — confirm, `verification_codes.auction_id`, `parcels.continent_name`)
- [ ] Partial unique index `uq_auctions_parcel_locked_status` exists in Postgres
- [ ] Flyway migrations V1/V2 deleted, `spring.flyway.enabled: false`
- [ ] Postman collection has a `Parcel & Listings` folder with all new endpoints
- [ ] Manual Postman smoke for all three methods passes
- [ ] `README.md` swept
- [ ] `FOOTGUNS.md` updated
- [ ] `DEFERRED_WORK.md` updated
- [ ] PR into `dev` opened
- [ ] No AI/tool attribution anywhere
- [ ] Local branch is `dev` after PR opens

