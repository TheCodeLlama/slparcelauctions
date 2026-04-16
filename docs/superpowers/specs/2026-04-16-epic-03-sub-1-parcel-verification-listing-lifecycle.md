# Epic 03 — Sub-spec 1: Parcel Verification + Listing Lifecycle (Backend)

**Date:** 2026-04-16
**Epic:** 03 — Parcel Management
**Sub-spec scope:** Tasks 03-01, 03-02, 03-03, 03-04 (all backend)
**Status:** design
**Author:** brainstorming session 2026-04-16

---

## 1. Summary

Ship the backend half of Epic 03: the full parcel verification and auction listing lifecycle as a testable REST API. A verified user can look up a Second Life parcel by UUID (backend fetches World API metadata, resolves grid coordinates via Map API, and enforces Mainland-only scope via static continent bounding boxes), create an auction draft referencing that parcel, pay the listing fee (dev-profile stub for now), and trigger parcel verification via one of three methods:

- **Method A (UUID_ENTRY):** Synchronous World API ownership check against the user's verified SL avatar UUID.
- **Method B (REZZABLE):** Asynchronous verification via an in-world LSL object callback. Backend issues a PARCEL-type verification code bound to the specific draft auction; the in-world object reports back with code + parcel data; backend transitions the auction to ACTIVE.
- **Method C (SALE_TO_BOT):** Asynchronous verification via a bot worker that checks the seller's land-for-sale status to the primary escrow account. The bot service is Epic 06, so a dev-profile stub endpoint stands in until then.

All three methods converge on the same state machine: `DRAFT → DRAFT_PAID → VERIFICATION_PENDING → ACTIVE` (or `VERIFICATION_FAILED` on failure, with retry support).

Auctions have seller and public DTOs with distinct shapes — the public representation collapses the four terminal statuses (COMPLETED, CANCELLED, EXPIRED, DISPUTED) into a single `ENDED` value so viewers cannot infer why an auction ended.

Cancellation, listing fee refund records, parcel photo uploads, and parcel tag selection round out the seller workflow.

After this sub-spec merges, all the backend endpoints needed for the listing creation UI (Epic 03 sub-spec 2, task 03-05) exist and are exercised by curl/Postman. The frontend sub-spec 2 then layers a multi-step wizard on top.

---

## 2. Scope

### In scope

1. **World API client.** Spring WebFlux `WebClient` service that fetches `https://world.secondlife.com/place/{parcel_uuid}`, parses HTML meta tags (Jsoup), and returns a structured DTO. Handles 404 (parcel deleted), timeouts, transient failures with retry+backoff.
2. **Map API client.** WebFlux `WebClient` service that queries `https://cap.secondlife.com/cap/0/b713fe80-283b-4585-af4d-a3b7d9a32492` to resolve a region name to `(grid_x, grid_y)` coordinates.
3. **Static Mainland continent check.** In-memory `MainlandContinents` helper with the 17 continent bounding boxes from the SL wiki's [ContinentDetector](https://wiki.secondlife.com/wiki/ContinentDetector). No external dependency, no Grid Survey API. Deterministic point-in-box check.
4. **Parcel entity + lookup endpoint.** `Parcel` JPA entity mapping to the existing `parcels` table (schema from Epic 01). `POST /api/v1/parcels/lookup` fetches World API + Map API data, runs the Mainland check, and creates a shared parcel row. Parcels are shared across auctions (many-to-one from auctions), never 1:1 with drafts.
5. **Auction entity + CRUD endpoints.** `Auction` JPA entity. Endpoints: `POST /auctions` (create draft), `PUT /auctions/{id}` (edit draft), `GET /auctions/{id}` (seller or public view), `GET /users/me/auctions` (seller's own), `PUT /auctions/{id}/cancel` (cancel), `GET /auctions/{id}/preview` (seller preview).
6. **Auction state machine.** DRAFT → DRAFT_PAID → VERIFICATION_PENDING → ACTIVE with cancellation branches to CANCELLED and retry path through VERIFICATION_FAILED. Service-layer validation, throws `InvalidAuctionStateException` on invalid transitions (HTTP 409).
7. **Unified verification dispatch.** `PUT /auctions/{id}/verify` reads `auction.verification_method` and dispatches to Method A (sync), B (async LSL), or C (async bot). One codepath, three behaviors.
8. **Method A — sync World API verification.** Inline call during `PUT /verify`: re-fetch World API, verify `ownertype == "agent"` and `ownerid` matches user's SL UUID. On success, transition to ACTIVE; on failure, VERIFICATION_FAILED.
9. **Method B — LSL callback endpoint.** `POST /api/v1/sl/parcel/verify` is public (no JWT), validates `X-SecondLife-Shard == "Production"` and `X-SecondLife-Owner-Key == SLPA service account UUID`, accepts payload `{ verification_code, parcel_uuid, owner_uuid, parcel_name, area_sqm, description, prim_capacity, region_pos_x, region_pos_y, region_pos_z }`, resolves code → auction, validates ownership, transitions to ACTIVE.
10. **Method B — PARCEL verification codes.** Extends existing `VerificationCode` entity (Epic 02) with a nullable `auction_id` FK column. PLAYER codes leave it null; PARCEL codes populate it. When the seller triggers verify for a REZZABLE auction, backend generates a PARCEL code bound to that auction_id.
11. **Method C — bot_task queue + dev stub.** New `BotTask` JPA entity (new table, created via Hibernate DDL-auto per CONVENTIONS.md). When Method C verification is triggered, backend creates a `bot_tasks` row with the region/parcel details and sentinel price. Real bot polling endpoints (`GET /api/v1/bot/tasks/pending`, `PUT /api/v1/bot/tasks/{taskId}`) exist on the production surface (bot auth is Epic 06 concern). A dev-profile-only `POST /api/v1/dev/bot/tasks/{taskId}/complete` stands in for the bot until Epic 06.
12. **Listing fee payment stub (dev-only).** `POST /api/v1/dev/auctions/{id}/pay` transitions `DRAFT → DRAFT_PAID` with a mock payment payload. Real production endpoint shape is Epic 05 (in-world terminal callback).
13. **Listing fee refund records.** New `ListingFeeRefund` entity (new `listing_fee_refunds` table) with status `PENDING`/`PROCESSED`, amount, timestamp, txn reference (null until Epic 05 processes). Cancellations from DRAFT_PAID/VERIFICATION_PENDING/VERIFICATION_FAILED create PENDING records.
14. **Cancellation log.** New `CancellationLog` entity (new `cancellation_logs` table) capturing `auction_id`, `seller_id`, `cancelled_at`, `cancelled_from_status`, `had_bids`, `reason` (optional free-text). Written on every cancellation.
15. **`cancelled_with_bids` counter on User.** New column on the existing `users` table (Hibernate DDL-auto adds it). Incremented on cancellation from ACTIVE when `bid_count > 0`.
16. **Scheduled timeout jobs.** Two Spring `@Scheduled` jobs:
    - Method B PARCEL code expiry (every 5 min): transitions stuck VERIFICATION_PENDING auctions back to DRAFT_PAID when the associated code has expired without a callback.
    - Method C bot task timeout (every 15 min): fails any `bot_task` in PENDING older than 48 hours; transitions associated auction to VERIFICATION_FAILED; no refund (seller retries via `PUT /verify`).
17. **Parcel photo uploads.** New `AuctionPhoto` entity (new `auction_photos` table). Endpoints: `POST /api/v1/auctions/{id}/photos` (multipart, DRAFT/DRAFT_PAID only), `DELETE /api/v1/auctions/{id}/photos/{photoId}`. Max 10 photos per listing, 2 MB per photo, JPEG/PNG/WebP. Reuses the `AvatarImageProcessor` pipeline from Epic 02 sub-spec 2a (bytes-level format validation). MinIO storage path `listings/{auctionId}/{uuid}.{ext}`.
18. **Parcel tag endpoint.** `GET /api/v1/parcel-tags` returns all active tags grouped by category. Consumed by sub-spec 2 UI's tag selector.
19. **Parcel tag assignment.** Auction create/edit requests accept `tags: string[]` (array of tag codes, not IDs). Backend validates against `parcel_tags` table, writes to `auction_tags` join table.
20. **Two-DTO auction response model.** `SellerAuctionResponse` (full internal state) for seller-facing endpoints; `PublicAuctionResponse` (reduced status enum `ACTIVE | ENDED`, stripped of seller-only fields like `winner_id`, `listing_fee_*`, `verification_notes`, `commission_*`, `pendingVerification`) for non-sellers. Enforced at the Jackson serialization layer — the 4 terminal statuses cannot leak through a bug.
21. **Reserve auction behavior in public DTO.** Public response exposes `hasReserve: boolean` + `reserveMet: boolean`, never the raw `reserve_price`. Enforcement of reserve during bidding is Epic 04.
22. **Field validation (JSR-380).** Bean Validation annotations on all create/update DTOs (starting_bid min 1, duration_hours enum, snipe_window_min enum if snipe_protect, seller_desc max 5000 chars, tags max 10, etc.).
23. **Postman collection updates.** New folder: `SLPA/Parcel & Listings` with requests for each new endpoint. Capture scripts for `parcelId`, `auctionId`, `verificationCode`, `botTaskId` into collection variables.

### Out of scope (deferred)

- **Task 03-05 listing creation UI** — Epic 03 sub-spec 2.
- **Task 03-06 ownership monitoring** — Epic 03 sub-spec 3.
- **Auction engine / bidding logic** — Epic 04. Sub-spec 1 sets `starts_at` and `ends_at` correctly on ACTIVE transition; Epic 04 handles what happens during ACTIVE.
- **Escrow / in-world payment** — Epic 05. Sub-spec 1 uses a dev-profile stub for listing fee payment; production shape is Epic 05's territory.
- **Bot service implementation** — Epic 06. Sub-spec 1 ships the `bot_tasks` queue and public endpoints (`GET /bot/tasks/pending`, `PUT /bot/tasks/{taskId}`) with placeholder auth; Epic 06 wires real bot authentication and worker logic.
- **LSL scripts** — Epic 11. Sub-spec 1 ships the `POST /api/v1/sl/parcel/verify` endpoint testable via Postman with manually-set SL headers.
- **Fraud signal tracking for PARCEL code abuse** — Epic 10 (Admin & Moderation). Entry in `DEFERRED_WORK.md`.
- **Admin completion endpoint for bot tasks (outside dev profile)** — Epic 10. Sub-spec 1 has a dev-only stub only.
- **Ratings / reviews linked to completed auctions** — Epic 08.

### Non-goals

- **No new Flyway migrations.** Per `CONVENTIONS.md`, all schema additions (new tables: `bot_tasks`, `cancellation_logs`, `listing_fee_refunds`, `auction_photos`; new columns: `users.cancelled_with_bids`, `verification_codes.auction_id`, new columns on `parcels` for continent name and layout map cache) happen via JPA entity definitions with `spring.jpa.hibernate.ddl-auto: update`.
- **No Grid Survey API integration.** Replaced by static `MainlandContinents` bounding-box check.
- **No changes to Epic 02's `VerificationCode` service behavior for PLAYER codes.** We add one nullable column (`auction_id`) and one type constant (`PARCEL`); PLAYER verification flow is unaffected.
- **No UI work.** This is backend-only, consumed by curl/Postman and by sub-spec 2 frontend later.

---

## 3. Background and references

- `docs/implementation/epic-03/03-parcel-management.md` — Epic 03 overview.
- `docs/implementation/epic-03/task-01-world-api-client.md` — World API, Map API, Grid Survey (Grid Survey dropped in this sub-spec; see §7).
- `docs/implementation/epic-03/task-02-parcel-verification-methods-ab.md` — Methods A and B.
- `docs/implementation/epic-03/task-03-parcel-verification-method-c.md` — Method C sale-to-bot.
- `docs/implementation/epic-03/task-04-listing-lifecycle.md` — Auction lifecycle state machine.
- `docs/initial-design/DESIGN.md` §4.2 (verification methods), §4.5 (listing lifecycle), §6.2–6.4 (SL external APIs), §7 (parcel tags).
- `docs/implementation/CONVENTIONS.md` — project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).
- `docs/implementation/DEFERRED_WORK.md` — check before starting this epic; one new entry added by this sub-spec (PARCEL code fraud signal → Epic 10).
- `backend/src/main/resources/db/migration/V1__core_tables.sql` — existing `parcels`, `auctions`, `parcel_tags`, `auction_tags` schema.
- `backend/src/main/java/com/slparcelauctions/backend/user/` — Epic 02's package structure; we follow the same feature-based layout for `parcel/`, `auction/`, `sl/`, `bot/`, `verification/`.
- `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCode.java` (and service) — existing code generation logic extended with PARCEL type.
- `backend/src/main/java/com/slparcelauctions/backend/user/AvatarImageProcessor.java` — Epic 02 sub-spec 2a's image pipeline; reused for parcel photos.
- SL wiki ContinentDetector: `https://wiki.secondlife.com/wiki/ContinentDetector` — source of the 17 continent bounding boxes.

---

## 4. API surface

All authenticated endpoints require a verified user (`user.verified == true`) unless noted. Public endpoints have no auth requirement. Dev endpoints exist only when `spring.profiles.active` contains `dev`.

### 4.1 Parcel endpoints

```
POST   /api/v1/parcels/lookup                 auth, verified  → ParcelResponse
  body: { slParcelUuid: string }
  Fetches World API + Map API + MainlandContinents check.
  Creates parcels row if UUID is new, else returns existing row.
  Errors:
    400 - UUID format invalid
    404 - World API returned 404 (parcel does not exist in SL)
    422 - Parcel is not on Mainland (continent check failed)
    504 - World API or Map API timed out
```

### 4.2 Auction CRUD endpoints

```
POST   /api/v1/auctions                        auth, verified  → SellerAuctionResponse (201)
  body: AuctionCreateRequest
  {
    parcelId: number,
    verificationMethod: "UUID_ENTRY" | "REZZABLE" | "SALE_TO_BOT",
    startingBid: number,          // L$ as BIGINT, min 1
    reservePrice?: number | null, // optional, >= startingBid
    buyNowPrice?: number | null,  // optional, >= max(startingBid, reservePrice)
    durationHours: 24 | 48 | 72 | 168 | 336,
    snipeProtect: boolean,
    snipeWindowMin?: 5 | 10 | 15 | 30 | 60 | null, // required iff snipeProtect=true
    sellerDesc?: string | null,    // max 5000 chars
    tags: string[]                 // array of parcel_tag codes, max 10
  }
  Creates auction in DRAFT. Commission rate from config.

GET    /api/v1/auctions/{id}                   auth                        → SellerAuctionResponse | PublicAuctionResponse
  Seller sees full SellerAuctionResponse (all statuses).
  Non-seller: 404 if status ∈ {DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED}.
  Non-seller: PublicAuctionResponse if status ∈ {ACTIVE, ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING, COMPLETED, CANCELLED, EXPIRED, DISPUTED}.
    → public status maps to ACTIVE for internal ACTIVE, else ENDED.

GET    /api/v1/users/me/auctions               auth, verified              → SellerAuctionResponse[]
  Returns all auctions where seller_id = current user. All statuses. Ordered by created_at DESC.

PUT    /api/v1/auctions/{id}                   auth, seller                → SellerAuctionResponse
  body: AuctionUpdateRequest (same fields as create, all optional)
  Allowed only from DRAFT or DRAFT_PAID. 409 otherwise.
  Cannot change parcelId (that would invalidate the lookup).
  Can change verificationMethod.

PUT    /api/v1/auctions/{id}/cancel             auth, seller                → SellerAuctionResponse
  body: { reason?: string }
  Allowed from DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED, ACTIVE.
  Not allowed from ENDED/ESCROW_*/TRANSFER_PENDING/COMPLETED/CANCELLED/EXPIRED/DISPUTED.
  Creates CancellationLog entry.
  If DRAFT_PAID/VERIFICATION_PENDING/VERIFICATION_FAILED: creates ListingFeeRefund (PENDING).
  If ACTIVE with bid_count > 0: increments seller.cancelled_with_bids.

GET    /api/v1/auctions/{id}/preview             auth, seller                → SellerAuctionResponse
  Seller-only. 404 to others.
  Shape identical to GET /auctions/{id} for the seller.
```

### 4.3 Auction lifecycle endpoints

```
PUT    /api/v1/auctions/{id}/verify             auth, seller                → SellerAuctionResponse
  Unified dispatch. Transitions DRAFT_PAID → VERIFICATION_PENDING.
  Also allowed from VERIFICATION_FAILED (retry path).
  Behavior depends on auction.verification_method:

  UUID_ENTRY (synchronous):
    Calls World API, verifies ownertype == "agent" and ownerid == user.slAvatarUuid.
    Success: VERIFICATION_PENDING → ACTIVE (sets starts_at = now, ends_at = now + durationHours, original_ends_at = ends_at, verification_tier = SCRIPT).
    Failure: VERIFICATION_PENDING → VERIFICATION_FAILED (sets verification_notes).
    Returns SellerAuctionResponse reflecting the final state.

  REZZABLE (async):
    Generates a PARCEL-type VerificationCode bound to auction_id, 15-min TTL.
    Stays VERIFICATION_PENDING.
    Returns SellerAuctionResponse with pendingVerification.code + codeExpiresAt populated.

  SALE_TO_BOT (async):
    Creates BotTask (status=PENDING, type=VERIFY, region_name, parcel_uuid, sentinel_price).
    Stays VERIFICATION_PENDING.
    Returns SellerAuctionResponse with pendingVerification.botTaskId + instructions populated.
```

### 4.4 In-world LSL callback (Method B completion)

```
POST   /api/v1/sl/parcel/verify                 public (SL headers)         → 204 No Content
  headers:
    X-SecondLife-Shard: "Production"
    X-SecondLife-Owner-Key: <SLPA service account UUID>
    X-SecondLife-Object-Key: <object UUID>  (logged, not required for validation)
    X-SecondLife-Region: <region name>
  body (application/x-www-form-urlencoded, LSL convention):
    verification_code: string (6 digits)
    parcel_uuid: UUID
    owner_uuid: UUID
    parcel_name: string
    area_sqm: integer
    description: string
    prim_capacity: integer
    region_pos_x: float
    region_pos_y: float
    region_pos_z: float
  Validates:
    X-SecondLife-Shard matches "Production" (rejects beta grid)
    X-SecondLife-Owner-Key matches configured SLPA service account UUID
    verification_code exists, type=PARCEL, not expired, not used
    verification_code.auction.parcel.slParcelUuid matches body parcel_uuid
    body owner_uuid matches code holder's user.slAvatarUuid
    body owner_uuid ownertype is "agent" (re-checked against World API)
  On success:
    Marks code as used.
    Refreshes parcels row metadata from body (parcel_name, area_sqm, description, position).
    Transitions auction: VERIFICATION_PENDING → ACTIVE.
    Sets starts_at = now, ends_at = now + durationHours, original_ends_at = ends_at, verification_tier = SCRIPT.
  On validation failure: 400 Bad Request with problem detail. LSL script typically logs and retries on the next touch.
```

### 4.5 Bot service endpoints

```
GET    /api/v1/bot/tasks/pending                public (bot auth TBD Epic 06) → BotTaskResponse[]
  Returns all bot_tasks where status = PENDING. FIFO order (created_at ASC).
  Bot service (Epic 06) will add authentication (bearer token or mTLS). Sub-spec 1 ships
  it on the production surface with no auth — this is explicitly noted in the spec and
  would not be deployed to production until Epic 06 adds auth. Dev and integration test
  profiles only.

PUT    /api/v1/bot/tasks/{taskId}               public (bot auth TBD Epic 06) → BotTaskResponse
  body: {
    result: "SUCCESS" | "FAILURE",
    authBuyerId?: UUID,          // on SUCCESS
    salePrice?: number,           // on SUCCESS
    parcelOwner?: UUID,           // on SUCCESS
    parcelName?: string,
    areaSqm?: integer,
    regionName?: string,
    positionX?: float, positionY?: float, positionZ?: float,
    failureReason?: string        // on FAILURE
  }
  On SUCCESS with valid payload:
    Validates authBuyerId == config primary escrow UUID.
    Validates salePrice == config sentinel price (L$999,999,999).
    Refreshes parcels row metadata from body.
    Marks bot_task as COMPLETED.
    Transitions associated auction: VERIFICATION_PENDING → ACTIVE, verification_tier = BOT.
  On FAILURE:
    Marks bot_task as FAILED with failure_reason.
    Transitions auction: VERIFICATION_PENDING → VERIFICATION_FAILED.
    No refund. Seller retries via PUT /auctions/{id}/verify.
```

### 4.6 Dev profile endpoints (dev profile only)

```
POST   /api/v1/dev/auctions/{id}/pay            dev profile, auth, seller   → SellerAuctionResponse
  body: { amount?: number, txnRef?: string }  (both optional, defaults from config)
  Transitions auction: DRAFT → DRAFT_PAID.
  Populates listing_fee_amt = body.amount ?? config.default, listing_fee_txn = body.txnRef ?? "dev-mock-<uuid>", listing_fee_paid_at = now, listing_fee_paid = true.
  Stand-in until Epic 05 escrow terminal.

POST   /api/v1/dev/bot/tasks/{taskId}/complete  dev profile, auth            → BotTaskResponse
  body: same shape as PUT /api/v1/bot/tasks/{taskId}
  Stand-in for the bot worker until Epic 06.
  Identical behavior to the real bot endpoint, but routed under /dev/ and accessible with normal JWT auth.
```

### 4.7 Tag reference endpoint

```
GET    /api/v1/parcel-tags                       auth                         → ParcelTagGroupResponse[]
  Returns active tags grouped by category.
  Response:
    [
      {
        category: "Terrain / Environment",
        tags: [
          { code: "WATERFRONT", label: "Waterfront", description: "Ocean, lake, or river border", sortOrder: 1 },
          ...
        ]
      },
      ...
    ]
  Groups ordered by the minimum sortOrder in each group; tags within a group ordered by sortOrder.
```

### 4.8 Auction photo endpoints

```
POST   /api/v1/auctions/{id}/photos              auth, seller                → AuctionPhotoResponse (201)
  multipart/form-data
  field: file (image/jpeg, image/png, or image/webp, max 2 MB)
  Allowed only from DRAFT or DRAFT_PAID.
  Rejects if current photo count >= 10.
  Stores in MinIO at listings/{auctionId}/{uuid}.{ext}.
  Creates AuctionPhoto row.

GET    /api/v1/auctions/{id}/photos/{photoId}/bytes  public                  → image bytes
  Serves the photo from MinIO. No auth required (photos on ACTIVE+ auctions are public;
  photos on pre-ACTIVE drafts are still accessible since the seller can share them).
  Same pattern as Epic 02 sub-spec 2a's avatar serving endpoint.
  Content-Type matches the stored content type.
  Cache-Control: public, max-age=86400 (24h).

DELETE /api/v1/auctions/{id}/photos/{photoId}   auth, seller                → 204
  Allowed only from DRAFT or DRAFT_PAID.
  Deletes MinIO object and AuctionPhoto row.
```

---

## 5. Data model

### 5.1 New entities

**`Parcel` (maps to existing `parcels` table).** Hibernate entity over the Epic 01 schema. Adds no new columns initially — the table already has `sl_parcel_uuid`, `owner_uuid`, `region_name`, `grid_x`, `grid_y`, `area_sqm`, `description`, `snapshot_url`, `layout_map_url`, `layout_map_data`, `layout_map_at`, `location`, `slurl`, `verified`, `verified_at`, `last_checked`, `created_at`. We add one new column via JPA: `continent_name VARCHAR(50)` (the Mainland continent the parcel belongs to, cached at lookup time).

Repurposed semantics:
- `verified` → "metadata successfully fetched from World API at least once" (not ownership claim).
- `verified_at` → timestamp of that first successful World API fetch.
- `last_checked` → timestamp of the most recent World API refresh (Task 03-06 will use this for ownership monitoring; sub-spec 1 just sets it at lookup time).

**`Auction` (maps to existing `auctions` table).** Maps directly onto the Epic 01 schema. No new columns on the table. Hibernate maps every column to a JPA field.

**`BotTask` (new entity → new `bot_tasks` table).**

```java
@Entity
@Table(name = "bot_tasks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BotTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private BotTaskType taskType;          // VERIFY (only value in sub-spec 1)

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private BotTaskStatus status;          // PENDING, IN_PROGRESS, COMPLETED, FAILED

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(name = "parcel_uuid", nullable = false)
    private UUID parcelUuid;

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(name = "sentinel_price", nullable = false)
    private Long sentinelPrice;            // L$999999999 from config

    @Column(name = "assigned_bot_uuid")
    private UUID assignedBotUuid;          // populated by Epic 06 bot worker

    @Column(name = "result_data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> resultData; // raw ParcelProperties payload from bot

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @CreationTimestamp @Column(name = "last_updated_at", nullable = false)
    private OffsetDateTime lastUpdatedAt;
}
```

**`CancellationLog` (new entity → new `cancellation_logs` table).**

```java
@Entity
@Table(name = "cancellation_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CancellationLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @Column(name = "cancelled_from_status", nullable = false, length = 30)
    private String cancelledFromStatus;    // stores the internal status at cancellation time

    @Column(name = "had_bids", nullable = false)
    private boolean hadBids;

    @Column(length = 500)
    private String reason;                  // optional free-text from body.reason

    @CreationTimestamp @Column(name = "cancelled_at", nullable = false)
    private OffsetDateTime cancelledAt;
}
```

**`ListingFeeRefund` (new entity → new `listing_fee_refunds` table).**

```java
@Entity
@Table(name = "listing_fee_refunds")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ListingFeeRefund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(nullable = false)
    private Long amount;                    // in L$, matches listing_fee_amt at time of cancellation

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private RefundStatus status;            // PENDING, PROCESSED, FAILED

    @Column(name = "txn_ref", length = 255)
    private String txnRef;                  // populated by Epic 05 when refund processed

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @CreationTimestamp @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
```

**`AuctionPhoto` (new entity → new `auction_photos` table).**

```java
@Entity
@Table(name = "auction_photos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuctionPhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;               // MinIO key, e.g., "listings/42/a1b2c3.jpg"

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;             // "image/jpeg" etc.

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;              // position in listing display; 0-indexed

    @CreationTimestamp @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;
}
```

**`ParcelTag` (maps to existing `parcel_tags` table).** Already has `id`, `code`, `label`, `category`, `description`, `sort_order`, `active`, `created_at`, `updated_at`. JPA entity reads the table; seed data is in place.

**`AuctionTag` (maps to existing `auction_tags` join table).** Composite key `(auction_id, tag_id)`. Could be modeled as `@ManyToMany` on `Auction` with `@JoinTable` annotation, or as an explicit join entity. We use `@ManyToMany` for simplicity — the join table has no extra columns.

### 5.2 Modified entities

**`User` (existing).** Add column: `cancelled_with_bids INTEGER NOT NULL DEFAULT 0`. Incremented only when an ACTIVE auction is cancelled with `bid_count > 0`.

**`VerificationCode` (existing, Epic 02).** Add column: `auction_id BIGINT` (nullable) `REFERENCES auctions(id)`. Populated only for PARCEL-type codes; PLAYER codes leave it null.

### 5.3 Enum types

Java enums for type-safe state management:

```java
public enum AuctionStatus {
    DRAFT, DRAFT_PAID,
    VERIFICATION_PENDING, VERIFICATION_FAILED,
    ACTIVE, ENDED,
    ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING,
    COMPLETED, CANCELLED, EXPIRED, DISPUTED
}

public enum VerificationTier { SCRIPT, BOT, HUMAN }

public enum VerificationMethod { UUID_ENTRY, REZZABLE, SALE_TO_BOT }

public enum PublicAuctionStatus { ACTIVE, ENDED }

public enum BotTaskType { VERIFY }

public enum BotTaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

public enum RefundStatus { PENDING, PROCESSED, FAILED }

public enum VerificationCodeType { PLAYER, PARCEL }  // extends Epic 02's enum
```

Storage: `@Enumerated(EnumType.STRING)` so the database column holds the string name (already the schema convention per `auctions.status VARCHAR(30)`).

---

## 6. DTO definitions

### 6.1 Request DTOs

```typescript
// POST /api/v1/parcels/lookup
type ParcelLookupRequest = {
  slParcelUuid: UUID;  // must be a valid UUID format
};

// POST /api/v1/auctions
type AuctionCreateRequest = {
  parcelId: number;                                    // required; must exist in parcels table
  verificationMethod: "UUID_ENTRY" | "REZZABLE" | "SALE_TO_BOT";
  startingBid: number;                                 // L$ BIGINT, min 1
  reservePrice?: number | null;                        // >= startingBid if set
  buyNowPrice?: number | null;                         // >= max(startingBid, reservePrice ?? 0) if set
  durationHours: 24 | 48 | 72 | 168 | 336;
  snipeProtect: boolean;
  snipeWindowMin?: 5 | 10 | 15 | 30 | 60 | null;       // required iff snipeProtect === true
  sellerDesc?: string | null;                          // max 5000 chars
  tags: string[];                                      // parcel_tag codes; max 10 distinct; all must be active
};

// PUT /api/v1/auctions/{id}
type AuctionUpdateRequest = Partial<Omit<AuctionCreateRequest, "parcelId">>;
// parcelId cannot be changed on update. All other fields optional.

// PUT /api/v1/auctions/{id}/cancel
type AuctionCancelRequest = {
  reason?: string;  // max 500 chars
};

// POST /api/v1/dev/auctions/{id}/pay
type DevPayRequest = {
  amount?: number;  // defaults to config slpa.listing-fee.amount-lindens (100)
  txnRef?: string;  // defaults to "dev-mock-<random uuid>"
};

// PUT /api/v1/bot/tasks/{taskId} and POST /api/v1/dev/bot/tasks/{taskId}/complete
type BotTaskCompleteRequest = {
  result: "SUCCESS" | "FAILURE";
  authBuyerId?: UUID;         // on SUCCESS
  salePrice?: number;          // on SUCCESS
  parcelOwner?: UUID;          // on SUCCESS
  parcelName?: string;
  areaSqm?: number;
  regionName?: string;
  positionX?: number; positionY?: number; positionZ?: number;
  failureReason?: string;      // on FAILURE
};

// POST /api/v1/sl/parcel/verify (LSL callback, x-www-form-urlencoded)
type SlParcelVerifyRequest = {
  verification_code: string;
  parcel_uuid: UUID;
  owner_uuid: UUID;
  parcel_name: string;
  area_sqm: number;
  description: string;
  prim_capacity: number;
  region_pos_x: number;
  region_pos_y: number;
  region_pos_z: number;
};
```

### 6.2 Response DTOs

```typescript
type ParcelResponse = {
  id: number;
  slParcelUuid: UUID;
  ownerUuid: UUID;
  ownerType: "agent" | "group";
  regionName: string;
  gridX: number;
  gridY: number;
  continentName: string;         // populated by MainlandContinents check
  areaSqm: number;
  description: string | null;
  snapshotUrl: string | null;
  slurl: string;
  maturityRating: "PG" | "MATURE" | "ADULT";
  verified: boolean;             // "metadata fetched" semantic
  verifiedAt: string | null;
  lastChecked: string | null;
  createdAt: string;
};

type ParcelTagResponse = {
  code: string;
  label: string;
  description: string | null;
  sortOrder: number;
};

type ParcelTagGroupResponse = {
  category: string;
  tags: ParcelTagResponse[];
};

type AuctionPhotoResponse = {
  id: number;
  url: string;                   // GET /api/v1/auctions/{id}/photos/{photoId}/bytes (public read?)
  contentType: string;
  sizeBytes: number;
  sortOrder: number;
  uploadedAt: string;
};

type BotTaskResponse = {
  id: number;
  taskType: "VERIFY";
  status: "PENDING" | "IN_PROGRESS" | "COMPLETED" | "FAILED";
  auctionId: number;
  parcelUuid: UUID;
  regionName: string | null;
  sentinelPrice: number;
  assignedBotUuid: UUID | null;
  failureReason: string | null;
  createdAt: string;
  completedAt: string | null;
};

type PendingVerification = {
  // populated when auction.status === VERIFICATION_PENDING
  method: "UUID_ENTRY" | "REZZABLE" | "SALE_TO_BOT";
  code: string | null;            // REZZABLE only
  codeExpiresAt: string | null;   // REZZABLE only
  botTaskId: number | null;        // SALE_TO_BOT only
  instructions: string | null;     // SALE_TO_BOT only (human-readable)
};

type SellerAuctionResponse = {
  id: number;
  seller: UserPublicProfile;      // from Epic 02
  parcel: ParcelResponse;
  status: AuctionStatus;           // full internal enum
  verificationMethod: VerificationMethod;
  verificationTier: VerificationTier | null;
  pendingVerification: PendingVerification | null;
  verificationNotes: string | null;
  startingBid: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  currentBid: number;
  bidCount: number;
  winnerId: number | null;
  durationHours: number;
  snipeProtect: boolean;
  snipeWindowMin: number | null;
  startsAt: string | null;
  endsAt: string | null;
  originalEndsAt: string | null;
  sellerDesc: string | null;
  tags: ParcelTagResponse[];
  photos: AuctionPhotoResponse[];
  listingFeePaid: boolean;
  listingFeeAmt: number | null;
  listingFeeTxn: string | null;
  listingFeePaidAt: string | null;
  commissionRate: number;          // e.g., 0.05
  commissionAmt: number | null;    // null until settlement (Epic 04/05)
  createdAt: string;
  updatedAt: string;
};

type PublicAuctionResponse = {
  id: number;
  seller: UserPublicProfile;       // parcel's seller
  parcel: ParcelResponse;           // full parcel data is public
  status: PublicAuctionStatus;      // ACTIVE | ENDED only (strictly typed, not the full enum)
  verificationTier: VerificationTier | null;
  startingBid: number;
  hasReserve: boolean;              // derived: reservePrice != null
  reserveMet: boolean;              // derived: currentBid >= reservePrice
  buyNowPrice: number | null;       // publicly useful for Buy-it-now auctions
  currentBid: number;
  bidCount: number;
  durationHours: number;
  snipeProtect: boolean;
  snipeWindowMin: number | null;
  startsAt: string | null;
  endsAt: string | null;
  originalEndsAt: string | null;
  sellerDesc: string | null;
  tags: ParcelTagResponse[];
  photos: AuctionPhotoResponse[];
  // Excluded: winnerId, reservePrice (raw), listingFee*, verificationMethod,
  //           pendingVerification, verificationNotes, commission*, agentFee*,
  //           assignedBotUuid, saleSentinel*, lastBotCheck*, botCheckFailures
};
```

**Jackson serialization enforcement.** `PublicAuctionResponse.status` is typed as `PublicAuctionStatus` (a Java enum with two values: `ACTIVE`, `ENDED`). The response builder's mapping function cannot produce any other value — if it tried, it would be a compile error. This enforces the "4 terminal statuses never leak" rule at the type level.

```java
// AuctionDtoMapper.java
public PublicAuctionStatus toPublicStatus(AuctionStatus internal) {
    return switch (internal) {
        case ACTIVE -> PublicAuctionStatus.ACTIVE;
        case ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING,
             COMPLETED, CANCELLED, EXPIRED, DISPUTED -> PublicAuctionStatus.ENDED;
        default -> throw new IllegalStateException("Non-public status leaked to PublicAuctionResponse: " + internal);
    };
}
```

The default branch covers `DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED` which should never reach this mapper because the controller returns 404 for those to non-sellers before mapping happens. The `IllegalStateException` is a belt-and-suspenders runtime guard.

---

## 7. External API orchestration

### 7.1 World API client

**Endpoint:** `GET https://world.secondlife.com/place/{parcel_uuid}`

**Response:** HTML page with meta tags. Parse with Jsoup:
- `<meta property="og:title" content="Parcel Name">` — parcel name
- `<meta property="og:description" content="...">` — description
- `<meta property="og:image" content="..snapshot URL..">` — snapshot URL
- `<meta name="secondlife:region" content="RegionName">` — region name
- `<meta name="secondlife:parcelid" content="...">` — parcel UUID (sanity check)
- Hidden meta tags: `ownerid`, `ownertype` (`agent` | `group`), `area`, `maturityrating`, `owner`

Example service:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SlWorldApiClient {
    private final WebClient worldApiWebClient;  // configured with base URL and timeouts

    @Value("${slpa.world-api.retry-attempts:3}")
    private int retryAttempts;

    public Mono<ParcelMetadata> fetchParcel(UUID parcelUuid) {
        return worldApiWebClient.get()
            .uri("/place/{uuid}", parcelUuid)
            .retrieve()
            .onStatus(HttpStatus.NOT_FOUND::equals, r -> Mono.error(new ParcelNotFoundException(parcelUuid)))
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(10))
            .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(500))
                .filter(this::isTransient))
            .map(this::parseHtml);
    }

    private ParcelMetadata parseHtml(String html) {
        Document doc = Jsoup.parse(html);
        // extract meta tags, build ParcelMetadata
    }

    private boolean isTransient(Throwable t) {
        return t instanceof WebClientResponseException
            && ((WebClientResponseException) t).getStatusCode().is5xxServerError();
    }
}
```

**Timeouts and retries (config properties):**
- `slpa.world-api.base-url: https://world.secondlife.com`
- `slpa.world-api.timeout-ms: 10000`
- `slpa.world-api.retry-attempts: 3`
- `slpa.world-api.retry-backoff-ms: 500`

### 7.2 Map API client

**Endpoint:** `GET https://cap.secondlife.com/cap/0/b713fe80-283b-4585-af4d-a3b7d9a32492`

Query-parameter-based region name lookup. Response is a simple XML-ish body with `var coords = [x,y];`.

Service similar shape:

```java
@Service
@RequiredArgsConstructor
public class SlMapApiClient {
    private final WebClient mapApiWebClient;

    public Mono<GridCoordinates> resolveRegion(String regionName) {
        return mapApiWebClient.get()
            .uri(uriBuilder -> uriBuilder.queryParam("var", regionName).build())
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(5))
            .map(this::parseCoords);  // regex out the "var coords = [x,y];" values
    }
}
```

**Timeouts and retries:** similar config properties.

### 7.3 MainlandContinents (static check, no HTTP)

```java
// backend/src/main/java/com/slparcelauctions/backend/sl/MainlandContinents.java
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
        new Continent(461824.0, 464384.0, 307456.0, 310016.0, "Horizons")
    );

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

Unit-tested with a fixture set of (x, y, expected continent or null) tuples, including edge coordinates.

### 7.4 Orchestration at lookup time

```
POST /api/v1/parcels/lookup body { slParcelUuid }
  |
  v
  If parcels row with slParcelUuid already exists:
      return existing ParcelResponse (no external calls)
  Else:
      1. call WorldApi.fetchParcel(slParcelUuid)  [REQUIRED, may throw 404/504]
      2. call MapApi.resolveRegion(worldApiResult.regionName)  [REQUIRED, may throw 504]
      3. MainlandContinents.continentAt(gridX, gridY)
         - if empty → throw NotMainlandException (HTTP 422)
         - if present → use continent name
      4. build SLURL from region name + parcel position
      5. persist Parcel row
      6. return ParcelResponse
```

The "parcel already exists" short-circuit matters for the "multiple drafts share a parcel row" design — the second draft of parcel X reuses the row without re-fetching.

**Optional refresh:** the spec does NOT include a "refresh metadata" endpoint in sub-spec 1. Task 03-06 (ownership monitoring, later sub-spec) will add scheduled refreshes. For sub-spec 1, the parcel row is immutable after first creation except for `last_checked` bumps when we re-fetch at verify time.

### 7.5 Orchestration at verification time

**Method A (UUID_ENTRY) — inside `PUT /auctions/{id}/verify`:**
```
1. Transition DRAFT_PAID → VERIFICATION_PENDING
2. WorldApi.fetchParcel(auction.parcel.slParcelUuid) [inline]
3. Validate:
   - ownertype == "agent" (Method A rejects group-owned)
   - ownerid == current user's user.slAvatarUuid
4. On success:
   - Update parcels.last_checked, owner_uuid if changed (the parcel might have changed hands;
     we trust the user's ownership now since we just verified it)
   - Transition VERIFICATION_PENDING → ACTIVE
5. On failure:
   - Transition VERIFICATION_PENDING → VERIFICATION_FAILED
   - verification_notes = "Ownership mismatch: World API shows owner <uuid>"
6. Return SellerAuctionResponse reflecting new state
```

**Method B (REZZABLE) — inside `PUT /auctions/{id}/verify`:**
```
1. Transition DRAFT_PAID → VERIFICATION_PENDING
2. Invalidate any existing active PARCEL code for this auction (edge: retry case)
3. Generate VerificationCode(type=PARCEL, user_id=seller, auction_id=auction.id, expires_at=now+15m)
4. Return SellerAuctionResponse with pendingVerification.code + codeExpiresAt
```

**Method B completion (POST /api/v1/sl/parcel/verify):**
```
1. Validate SL headers (shard = Production, owner-key = configured SLPA service account)
2. Look up VerificationCode by value:
   - must exist, type=PARCEL, not expired, not used
3. Validate code.auction.parcel.slParcelUuid matches body.parcel_uuid
4. Validate body.owner_uuid matches code.user.slAvatarUuid
5. (Cross-check) WorldApi.fetchParcel(body.parcel_uuid) - verify ownertype == "agent" and ownerid == body.owner_uuid
6. Mark code as used
7. Refresh parcels row metadata from body + World API cross-check
8. Transition auction: VERIFICATION_PENDING → ACTIVE, verification_tier=SCRIPT
9. Return 204 No Content (LSL does not need the response body)
```

**Method C (SALE_TO_BOT) — inside `PUT /auctions/{id}/verify`:**
```
1. Transition DRAFT_PAID → VERIFICATION_PENDING
2. Cancel any existing PENDING/IN_PROGRESS bot_task for this auction (edge: retry case)
3. Create BotTask:
   - task_type = VERIFY
   - status = PENDING
   - auction_id = auction.id
   - parcel_uuid = auction.parcel.slParcelUuid
   - region_name = auction.parcel.regionName
   - sentinel_price = from config (L$999999999)
4. Build human-readable instructions: "Set your parcel for sale to <SLPAEscrow Resident> (UUID: <uuid>)
   at L$999,999,999. A verification worker will confirm within 48 hours."
5. Return SellerAuctionResponse with pendingVerification.botTaskId + instructions
```

**Method C completion (PUT /api/v1/bot/tasks/{taskId} from Epic 06 bot, OR POST /api/v1/dev/bot/tasks/{taskId}/complete from dev stub):**
```
If result == SUCCESS:
  1. Validate body.authBuyerId == config primary escrow UUID
  2. Validate body.salePrice == config sentinel price (L$999999999)
  3. Refresh parcels row metadata from body
  4. Mark bot_task status=COMPLETED, result_data=body, completed_at=now
  5. Transition auction: VERIFICATION_PENDING → ACTIVE, verification_tier=BOT
If result == FAILURE:
  1. Mark bot_task status=FAILED, failure_reason=body.failureReason, completed_at=now
  2. Transition auction: VERIFICATION_PENDING → VERIFICATION_FAILED
  3. verification_notes = "Bot reports: " + failureReason
```

---

## 8. State machine

### 8.1 Transition table

| From | Via | To | Conditions | Side effects |
|---|---|---|---|---|
| — | `POST /auctions` | DRAFT | user is verified, parcel exists | commission_rate from config |
| DRAFT | `POST /dev/auctions/{id}/pay` | DRAFT_PAID | dev profile only | sets listing_fee_* fields |
| DRAFT_PAID | `PUT /auctions/{id}/verify` | VERIFICATION_PENDING | — | method-dependent dispatch (A sync, B/C async) |
| VERIFICATION_FAILED | `PUT /auctions/{id}/verify` | VERIFICATION_PENDING | — | retry path, same dispatch logic |
| VERIFICATION_PENDING | (inline, Method A) | ACTIVE | World API confirms ownership (ownertype=agent, ownerid match) | starts_at=now, ends_at=now+duration, original_ends_at=ends_at, verification_tier=SCRIPT |
| VERIFICATION_PENDING | (inline, Method A) | VERIFICATION_FAILED | ownership mismatch or ownertype=group | verification_notes set |
| VERIFICATION_PENDING | `POST /sl/parcel/verify` (Method B) | ACTIVE | valid code, SL headers, ownership match | starts/ends set, verification_tier=SCRIPT |
| VERIFICATION_PENDING | `PUT /bot/tasks/{id}` or `POST /dev/bot/tasks/{id}/complete` (SUCCESS) | ACTIVE | authBuyerId + salePrice validated | starts/ends set, verification_tier=BOT |
| VERIFICATION_PENDING | `PUT /bot/tasks/{id}` or `POST /dev/bot/tasks/{id}/complete` (FAILURE) | VERIFICATION_FAILED | — | bot_task.status=FAILED |
| VERIFICATION_PENDING | PARCEL code expiry (scheduled, 5-min interval) | DRAFT_PAID | Method B, 15-min TTL elapsed, no callback | code invalidated; no refund |
| VERIFICATION_PENDING | bot_task timeout (scheduled, 15-min interval) | VERIFICATION_FAILED | Method C, 48h elapsed | bot_task.status=FAILED, failure_reason="TIMEOUT"; no refund |
| DRAFT | `PUT /auctions/{id}/cancel` | CANCELLED | — | no refund (never paid); CancellationLog |
| DRAFT_PAID | `PUT /auctions/{id}/cancel` | CANCELLED | — | ListingFeeRefund(PENDING); CancellationLog |
| VERIFICATION_PENDING | `PUT /auctions/{id}/cancel` | CANCELLED | — | ListingFeeRefund if fee paid; bot_task cancelled if Method C; CancellationLog |
| VERIFICATION_FAILED | `PUT /auctions/{id}/cancel` | CANCELLED | — | ListingFeeRefund(PENDING); CancellationLog |
| ACTIVE | `PUT /auctions/{id}/cancel` | CANCELLED | ends_at > now | no refund; CancellationLog; if bid_count>0, increment user.cancelled_with_bids |

### 8.2 Transitions NOT in sub-spec 1 (Epic 04/05 territory)

| From | To | Epic |
|---|---|---|
| ACTIVE | ENDED | Epic 04 (auction clock) |
| ACTIVE | EXPIRED | Epic 04 (no bids at end) |
| ENDED | ESCROW_PENDING | Epic 05 (escrow initiation) |
| ESCROW_PENDING | ESCROW_FUNDED | Epic 05 |
| ESCROW_FUNDED | TRANSFER_PENDING | Epic 05 |
| TRANSFER_PENDING | COMPLETED | Epic 05 |
| ACTIVE / ESCROW_* / TRANSFER_PENDING | DISPUTED | Epic 10 (admin / moderation) |

The entity and column definitions support these future transitions — sub-spec 1 just doesn't implement the code paths.

### 8.3 Invalid transition handling

`InvalidAuctionStateException` thrown at the service layer with payload `{ auctionId, currentState, attemptedAction }`. `GlobalExceptionHandler` maps to HTTP 409 Conflict with `ProblemDetail`:

```json
{
  "status": 409,
  "title": "Invalid Auction State",
  "detail": "Cannot trigger verification on an auction in DRAFT state. Pay the listing fee first.",
  "auctionId": 42,
  "currentState": "DRAFT",
  "attemptedAction": "VERIFY"
}
```

---

## 9. Cancellation and refunds

### 9.1 Cancellation rules

`PUT /api/v1/auctions/{id}/cancel` is allowed from these internal states:

| From | Refund? | Counter increment? | Other side effects |
|---|---|---|---|
| DRAFT | no (never paid) | never | CancellationLog |
| DRAFT_PAID | yes (ListingFeeRefund PENDING) | never | CancellationLog |
| VERIFICATION_PENDING (Method A has no pending state to cancel — it's synchronous) | yes if fee paid | never | CancellationLog; if Method C: cancel bot_task |
| VERIFICATION_FAILED | yes | never | CancellationLog |
| ACTIVE | no | yes if bid_count > 0 | CancellationLog |

Cancellation is disallowed (409 Conflict) from:
- ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING (transfer in progress — Epic 05 concern)
- COMPLETED, CANCELLED, EXPIRED, DISPUTED (already terminal)

### 9.2 Refund record flow

1. Cancellation from a refund-eligible state creates a `ListingFeeRefund` row with `status=PENDING, amount=auction.listingFeeAmt`.
2. Epic 05 will implement the refund processor: poll PENDING refunds, issue the in-world L$ refund via the escrow account, mark `status=PROCESSED` and set `txn_ref` + `processed_at`.
3. Sub-spec 1 does not process refunds — it only creates the PENDING records. This is noted in `DEFERRED_WORK.md` as an Epic 05 integration point.

### 9.3 `cancelled_with_bids` counter

Added as a new column on `users`: `cancelled_with_bids INTEGER NOT NULL DEFAULT 0`. Incremented only on cancellation from ACTIVE when `auction.bid_count > 0`. Used later (Epic 08 Ratings or Epic 10 Moderation) as a seller-reputation signal. Sub-spec 1 just maintains the counter; no consumer of the value ships here.

---

## 10. Scheduled jobs

### 10.1 PARCEL code expiry (Method B)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ParcelCodeExpiryJob {
    private final AuctionVerificationService verificationService;
    private final VerificationCodeRepository codeRepo;

    @Scheduled(fixedDelayString = "${slpa.verification.parcel-code-expiry-check-interval:PT5M}")
    public void sweep() {
        List<Auction> stuck = verificationService.findStuckRezzableAuctions();
        // auctions in VERIFICATION_PENDING, Method B, with no active (not-expired, not-used) code
        stuck.forEach(verificationService::revertExpiredRezzableVerification);
        // transitions each VERIFICATION_PENDING → DRAFT_PAID; no refund
    }
}
```

### 10.2 Bot task 48h timeout (Method C)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class BotTaskTimeoutJob {
    private final BotTaskService botTaskService;

    @Scheduled(fixedDelayString = "${slpa.bot-task.timeout-check-interval:PT15M}")
    public void sweep() {
        List<BotTask> timedOut = botTaskService.findPendingOlderThan(Duration.ofHours(48));
        timedOut.forEach(botTaskService::markTimedOut);
        // for each:
        //   bot_task.status = FAILED, failure_reason = "TIMEOUT"
        //   auction: VERIFICATION_PENDING → VERIFICATION_FAILED
        //   no refund
    }
}
```

Both jobs disabled in test profile via `slpa.verification.parcel-code-expiry-check-interval: PT1H` (or similar "effectively never"). Unit tests invoke the job methods directly with controlled time.

---

## 11. Photo upload pipeline

### 11.1 Flow

1. Seller POSTs multipart to `/api/v1/auctions/{id}/photos` with field `file`.
2. Controller validates:
   - Seller owns this auction (401/403 otherwise)
   - Auction status is DRAFT or DRAFT_PAID (409 otherwise)
   - Current photo count < 10 (413 Payload Too Large if at max)
3. `AvatarImageProcessor` (renamed or refactored into a more general `ImageUploadValidator` if scope allows) is reused:
   - Content-Type from bytes (ImageIO sniffing) — reject non-JPEG/PNG/WebP
   - Size < 2 MB (spring.servlet.multipart.max-file-size enforces at web layer too)
   - Optional: strip EXIF metadata
4. Upload to MinIO at `listings/{auctionId}/{uuid}.{ext}`.
5. Create `AuctionPhoto` row. `sortOrder` = max existing order + 1.
6. Return `AuctionPhotoResponse` (201 Created).

### 11.2 Delete

`DELETE /api/v1/auctions/{id}/photos/{photoId}`:
1. Validate seller + status as above.
2. Delete MinIO object.
3. Delete `AuctionPhoto` row.
4. 204 No Content.

### 11.3 Serving bytes

Photo bytes need to be fetchable. Two options:
- **A)** Public MinIO URL (MinIO's signed URLs or a public bucket).
- **B)** `GET /api/v1/auctions/{id}/photos/{photoId}/bytes` endpoint that proxies from MinIO.

Sub-spec 1 ships **option B** for consistency with Epic 02 sub-spec 2a's avatar serving pattern. The `AuctionPhotoResponse.url` field contains the proxy URL. Public read — no auth needed to view photos on a public auction (or on seller-only views where the auction is accessible to the caller).

---

## 12. Security considerations

### 12.1 Cross-auction data leakage

`GET /api/v1/auctions/{id}` must correctly identify caller identity and return the appropriate DTO variant. The controller:
- If caller is anonymous or not the seller → `PublicAuctionResponse` (or 404 for pre-ACTIVE states).
- If caller is the seller → `SellerAuctionResponse`.

**Key invariant:** `SellerAuctionResponse` is NEVER returned to a non-seller, even if the caller happens to be an admin. Admin-specific endpoints are not in scope for this sub-spec; if an admin wants a full view, that's Epic 10's concern.

### 12.2 SL header spoofing

`POST /api/v1/sl/parcel/verify` is a public endpoint with no JWT. The only authentication is the SL-injected headers:
- `X-SecondLife-Shard: Production` (reject beta grid)
- `X-SecondLife-Owner-Key: <UUID>` — must match the configured SLPA service account UUID (the UUID of the LSL-object-owning account)

The LSL script that calls this endpoint will be deployed as a rezzable verification object owned by the SLPA service account. Any `Owner-Key` other than that UUID is rejected. An attacker cannot forge SL headers from outside the SL grid — Linden's proxy validates them — but Postman/curl testing in dev requires manually setting them.

`Dev/Simulate parcel verify` Postman helper sets the headers correctly. Production requests without them get 403 Forbidden.

### 12.3 PARCEL code uniqueness

`VerificationCode` has implicit per-user-per-type uniqueness for active codes (existing Epic 02 behavior: generating a new PLAYER code invalidates the previous one). PARCEL codes are further scoped by `auction_id`: the "active code for a given auction" is unique. A single user can have multiple active PARCEL codes across multiple drafts, which is correct.

### 12.4 Bot endpoint auth (deferred)

`GET /api/v1/bot/tasks/pending` and `PUT /api/v1/bot/tasks/{taskId}` ship without auth in sub-spec 1. **This is a deliberate interim state.** They are on the production URL surface but Epic 06 will add bot worker authentication (bearer token, mTLS, or similar) before the bot service is deployed. `DEFERRED_WORK.md` gets an entry tracking this.

### 12.5 Parcel UUID validation

`POST /api/v1/parcels/lookup` accepts `slParcelUuid` and must validate format (UUID regex) before calling World API. World API does not rate-limit aggressively but we should not forward malformed requests. Reject 400 on parse failure.

### 12.6 MinIO object key injection

Photo object keys are `listings/{auctionId}/{uuid}.{ext}` where `uuid` is generated server-side (not user-controlled) and `ext` is mapped from the sniffed content type (jpg, png, webp only). No user input flows into the key. Deletion validates that the `AuctionPhoto` row's `objectKey` starts with `listings/{auctionId}/` before calling MinIO — prevents a compromised controller from deleting arbitrary keys.

### 12.7 State machine bypass

Every state transition goes through the service layer, which validates the source state. Direct database writes (e.g., via admin tooling or migration scripts in future) are not protected — `DEFERRED_WORK.md` already has a trail of "admin model" concerns; a future audit-trail entity could help here.

---

## 13. Testing strategy

### 13.1 Unit tests (service layer)

**Per-service tests, mocked repositories + mocked external clients:**

- `ParcelLookupServiceTest` — mocks `SlWorldApiClient` and `SlMapApiClient`. Verifies:
  - New UUID → creates row, returns DTO
  - Existing UUID → short-circuits, no external calls
  - 404 from World API → throws `ParcelNotFoundException` → 404
  - Timeout from World API → throws `ExternalApiTimeoutException` → 504
  - Non-Mainland coordinates → throws `NotMainlandException` → 422

- `AuctionStateMachineTest` — verifies every allowed transition and rejects every disallowed one. Parameterized test with `(fromState, action, expectedTo)` tuples. ~30 cases.

- `AuctionVerificationServiceTest` — three sub-suites for Method A, B, C:
  - Method A: mock World API success/failure, verify correct state transition
  - Method B: verify PARCEL code generation with correct TTL + auction_id binding
  - Method C: verify bot_task creation with correct sentinel price

- `SlParcelVerifyServiceTest` (LSL callback) — verifies header validation, code validation, ownership match, state transition. Includes cases for expired code, non-Production shard, wrong owner-key, code-auction-parcel mismatch.

- `BotTaskServiceTest` — SUCCESS/FAILURE callback paths, timeout sweep, validation of authBuyerId and salePrice.

- `ParcelCodeExpiryJobTest` — fake clock + fixture auctions, verify only stuck REZZABLE auctions are reverted.

- `BotTaskTimeoutJobTest` — fake clock + fixture bot_tasks, verify 48h threshold.

- `CancellationServiceTest` — matrix of (fromState × hadFeePaid × hadBids) → expected side effects.

- `MainlandContinentsTest` — fixture coordinates per continent (center + edge) + a few non-Mainland samples.

- `AuctionDtoMapperTest` — verify:
  - SellerAuctionResponse includes all internal fields
  - PublicAuctionResponse excludes all seller-only fields
  - Public status collapse: ENDED/ESCROW_*/TRANSFER_PENDING/COMPLETED/CANCELLED/EXPIRED/DISPUTED → ENDED
  - `IllegalStateException` on DRAFT/DRAFT_PAID/VERIFICATION_* (should never reach mapper)

### 13.2 Integration tests (`@SpringBootTest` with testcontainers)

Full end-to-end flows through the HTTP layer with a real Postgres, Redis, and MinIO. Mock external APIs via `@MockBean` on the WebClient wrappers.

**Coverage:**

- **Parcel lookup flow:** POST with valid UUID → 201/200 with ParcelResponse. Second POST same UUID → short-circuit, same row. POST non-Mainland UUID → 422.
- **Method A end-to-end:** register user → verify player → lookup parcel → create draft (Method A) → /dev/pay → /verify → ACTIVE. Verify JSON response shapes at each step.
- **Method B end-to-end:** ...up to /verify → pendingVerification.code populated → POST /sl/parcel/verify with matching headers → ACTIVE.
- **Method C end-to-end:** ...up to /verify → bot_task created → POST /dev/bot/tasks/{id}/complete with SUCCESS → ACTIVE.
- **State machine errors:** try to /verify from DRAFT (skip pay) → 409. Try to edit from VERIFICATION_PENDING → 409. Try to cancel from COMPLETED → 409.
- **Public DTO shape:** create active auction as User A → GET as anonymous → PublicAuctionResponse with `status: "ACTIVE"` and no seller-only fields. Cancel the auction → GET as anonymous → `status: "ENDED"`, no `winner_id` in response.
- **Photo upload:** upload JPEG/PNG/WebP each → 201. Upload BMP → 400. Upload 3 MB file → 413. Upload 11th photo → 413.
- **Scheduled job smoke:** direct service call to `parcelCodeExpiryJob.sweep()` with a fixture of expired codes → verify auctions bumped back.

### 13.3 External API mocking

`WireMock` for unit-level HTTP-client tests of `SlWorldApiClient` and `SlMapApiClient`. Fixture HTML pages stored under `src/test/resources/fixtures/world-api/<scenario>.html`.

Integration tests use `@MockBean` on the clients to avoid WireMock setup cost.

### 13.4 Test counts

- Backend baseline (end of Epic 02): ~191 tests.
- Expected delta: +60 to +80 tests.
- Target: 250-270 tests passing.

### 13.5 Postman collection

New folder: `SLPA / Parcel & Listings`. Requests:
- `Parcel / Lookup` (POST /parcels/lookup)
- `Auction / Create` (POST /auctions)
- `Auction / Get by ID` (GET /auctions/{id})
- `Auction / My Auctions` (GET /users/me/auctions)
- `Auction / Update Draft` (PUT /auctions/{id})
- `Auction / Cancel` (PUT /auctions/{id}/cancel)
- `Auction / Preview` (GET /auctions/{id}/preview)
- `Auction / Verify` (PUT /auctions/{id}/verify)
- `Tags / List` (GET /parcel-tags)
- `Photos / Upload` (POST /auctions/{id}/photos, multipart)
- `Photos / Delete` (DELETE /auctions/{id}/photos/{photoId})
- `Dev / Simulate listing fee payment` (POST /dev/auctions/{id}/pay)
- `Dev / Simulate bot completion` (POST /dev/bot/tasks/{taskId}/complete)
- `Dev / Simulate SL parcel verify` (POST /sl/parcel/verify with SL headers mocked)
- `Bot / Get pending tasks` (GET /bot/tasks/pending)
- `Bot / Report task result` (PUT /bot/tasks/{taskId})

Collection capture scripts populate `parcelId`, `auctionId`, `verificationCode`, `botTaskId`, `photoId` into collection variables so subsequent requests chain naturally.

---

## 14. Package structure

Follow Epic 02's feature-based layout:

```
backend/src/main/java/com/slparcelauctions/backend/
├── parcel/
│   ├── Parcel.java (entity)
│   ├── ParcelRepository.java
│   ├── ParcelLookupService.java
│   ├── ParcelController.java
│   └── dto/
│       ├── ParcelLookupRequest.java
│       └── ParcelResponse.java
├── auction/
│   ├── Auction.java
│   ├── AuctionStatus.java (enum)
│   ├── VerificationMethod.java (enum)
│   ├── VerificationTier.java (enum)
│   ├── AuctionRepository.java
│   ├── AuctionService.java          (CRUD + state transitions)
│   ├── AuctionVerificationService.java  (unified dispatch)
│   ├── AuctionDtoMapper.java
│   ├── AuctionController.java
│   ├── CancellationService.java
│   ├── CancellationLog.java
│   ├── CancellationLogRepository.java
│   ├── ListingFeeRefund.java
│   ├── ListingFeeRefundRepository.java
│   ├── AuctionPhoto.java
│   ├── AuctionPhotoRepository.java
│   ├── AuctionPhotoService.java
│   ├── AuctionPhotoController.java
│   ├── DevAuctionController.java     (dev profile only, @Profile("dev"))
│   ├── scheduled/
│   │   ├── ParcelCodeExpiryJob.java
│   │   └── BotTaskTimeoutJob.java
│   ├── exception/
│   │   ├── InvalidAuctionStateException.java
│   │   └── AuctionNotFoundException.java
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
├── parceltag/
│   ├── ParcelTag.java
│   ├── ParcelTagRepository.java
│   ├── ParcelTagService.java
│   ├── ParcelTagController.java
│   └── dto/
│       ├── ParcelTagResponse.java
│       └── ParcelTagGroupResponse.java
├── bot/
│   ├── BotTask.java
│   ├── BotTaskType.java
│   ├── BotTaskStatus.java
│   ├── BotTaskRepository.java
│   ├── BotTaskService.java
│   ├── BotTaskController.java         (production bot endpoints)
│   ├── DevBotTaskController.java      (@Profile("dev"))
│   └── dto/
│       ├── BotTaskResponse.java
│       └── BotTaskCompleteRequest.java
├── sl/
│   ├── MainlandContinents.java
│   ├── SlWorldApiClient.java
│   ├── SlMapApiClient.java
│   ├── SlParcelVerifyController.java  (LSL callback endpoint)
│   ├── SlParcelVerifyService.java
│   ├── config/
│   │   └── SlClientConfig.java         (WebClient beans, timeouts)
│   ├── exception/
│   │   ├── ParcelNotFoundException.java
│   │   ├── NotMainlandException.java
│   │   └── ExternalApiTimeoutException.java
│   └── dto/
│       ├── ParcelMetadata.java         (internal World API result)
│       ├── GridCoordinates.java
│       └── SlParcelVerifyRequest.java
└── verification/
    ├── VerificationCode.java           (existing from Epic 02, modified: +auction_id, +PARCEL type)
    ├── VerificationCodeType.java       (existing enum, +PARCEL)
    ├── VerificationCodeService.java    (existing; extended for PARCEL codes)
    └── ... (existing Epic 02 files)
```

---

## 15. Providers configuration

New Spring config properties in `application.yml`:

```yaml
slpa:
  world-api:
    base-url: https://world.secondlife.com
    timeout-ms: 10000
    retry-attempts: 3
    retry-backoff-ms: 500
  map-api:
    base-url: https://cap.secondlife.com
    cap-uuid: b713fe80-283b-4585-af4d-a3b7d9a32492
    timeout-ms: 5000
  listing-fee:
    amount-lindens: 100
  commission:
    default-rate: 0.05
  verification:
    parcel-code-ttl-minutes: 15
    parcel-code-expiry-check-interval: PT5M
  bot-task:
    sentinel-price-lindens: 999999999
    primary-escrow-uuid: <TBD-configure-per-environment>
    timeout-hours: 48
    timeout-check-interval: PT15M
  photos:
    max-per-listing: 10
    max-bytes: 2097152  # 2 MB
    allowed-content-types:
      - image/jpeg
      - image/png
      - image/webp
```

Dev profile overrides for faster test iteration:

```yaml
# application-dev.yml
slpa:
  verification:
    parcel-code-ttl-minutes: 15  # same as prod; unless testing expiry
  bot-task:
    timeout-hours: 48             # same; override in tests
```

---

## 16. Transition trace (end-to-end example — Method A)

1. User Alice (verified SL identity: `TesterBot Resident`) logs in. JWT issued.
2. Alice calls `POST /api/v1/parcels/lookup` with `{ slParcelUuid: "abc..." }`.
   - Backend fetches World API → returns HTML with `ownertype=agent, ownerid=<Alice's SL UUID>, region=Bellisseria`, `area=1024`, etc.
   - Backend fetches Map API → `gridX=265000, gridY=245000`.
   - MainlandContinents check → matches "Bellisseria" box → passes.
   - `parcels` row created with full metadata + `continentName=Bellisseria`.
   - Returns `ParcelResponse` with `id: 101`.
3. Alice calls `POST /api/v1/auctions`:
   ```json
   { "parcelId": 101, "verificationMethod": "UUID_ENTRY",
     "startingBid": 5000, "durationHours": 168, "snipeProtect": true,
     "snipeWindowMin": 10, "sellerDesc": "Beautiful waterfront parcel",
     "tags": ["WATERFRONT", "FLAT", "RESIDENTIAL"] }
   ```
   - Auction created with `id: 42`, status `DRAFT`, `seller_id = Alice.id`, `commission_rate = 0.05`.
   - Returns `SellerAuctionResponse`.
4. Alice calls `POST /api/v1/dev/auctions/42/pay` (dev profile).
   - Sets `listing_fee_amt = 100, listing_fee_paid = true, listing_fee_paid_at = now, listing_fee_txn = "dev-mock-<uuid>"`.
   - Status transitions DRAFT → DRAFT_PAID.
5. Alice calls `PUT /api/v1/auctions/42/verify`.
   - Backend transitions to VERIFICATION_PENDING.
   - Method A dispatch: fetch World API again.
   - World API returns `ownertype=agent, ownerid=<Alice's SL UUID>` — matches.
   - `parcels.last_checked` updated.
   - Status transitions VERIFICATION_PENDING → ACTIVE.
   - `starts_at = now`, `ends_at = now + 168 hours`, `original_ends_at = ends_at`, `verification_tier = SCRIPT`.
   - Returns `SellerAuctionResponse` with status=ACTIVE.
6. Anonymous user Bob navigates to `/users/<alice-id>` and the Active Listings section calls `GET /api/v1/auctions/42`.
   - Backend identifies Bob as non-seller.
   - Returns `PublicAuctionResponse` with `status: "ACTIVE"`, full parcel info, tags, photos, seller profile, `verificationTier: "SCRIPT"`, but no `winnerId`, `listingFee*`, `commission*`.

**Total external API calls for this trace:** 3 (2 World API + 1 Map API). All at defined moments; no hidden polling. Lookup short-circuits on subsequent calls for the same UUID.

---

## 17. Done definition

Sub-spec 1 is done when all of the following are true on `task/03-sub-1-parcel-verification-listing-lifecycle` off `dev`:

- [ ] All tasks in §18 committed in order
- [ ] `./mvnw test` → BUILD SUCCESS, ~250-270 tests (from ~191 baseline)
- [ ] Integration tests pass against Postgres + Redis + MinIO via testcontainers
- [ ] Hibernate DDL-auto creates the 4 new tables (`bot_tasks`, `cancellation_logs`, `listing_fee_refunds`, `auction_photos`) and the 2 new columns (`users.cancelled_with_bids`, `verification_codes.auction_id`, plus `parcels.continent_name`) on first dev-profile boot
- [ ] Postman collection updated with new `Parcel & Listings` folder and capture scripts
- [ ] Manual Postman smoke end-to-end for all three verification methods:
  1. Register + verify user → `Dev/Simulate SL verify`
  2. `Parcel/Lookup` with a known Mainland UUID → 200 with metadata
  3. `Auction/Create` (Method A) → `Dev/Simulate listing fee payment` → `Auction/Verify` → ACTIVE
  4. `Auction/Create` (Method B) → pay → verify → `Dev/Simulate SL parcel verify` → ACTIVE
  5. `Auction/Create` (Method C) → pay → verify → `Dev/Simulate bot completion` (SUCCESS) → ACTIVE
  6. `Auction/Cancel` a DRAFT_PAID auction → listing_fee_refunds row created
- [ ] Manual non-Mainland rejection: `Parcel/Lookup` with a known Linden estate UUID → 422
- [ ] `README.md` swept with new endpoint catalog, `bot_tasks` / `cancellation_logs` / `listing_fee_refunds` / `auction_photos` mention, backend test count bump
- [ ] `docs/implementation/FOOTGUNS.md` updated with any new gotchas (continent box stale-list risk, WebClient retry semantics, etc.)
- [ ] `docs/implementation/DEFERRED_WORK.md` updated: entry removed for "Real data for My Listings tab" (still deferred — that's sub-spec 2); new entries added for "Bot service auth (Epic 06)" and "Listing fee refund processor (Epic 05)"
- [ ] PR into `dev` opened (not `main`)
- [ ] No AI/tool attribution anywhere
- [ ] Local branch is `dev` after PR opens

---

## 18. Task breakdown

Ten tasks in strict execution order. Each task ends with a commit + push.

### Task 1 — Schema foundation + entity skeletons (~2 hours)

1. Add `continent_name VARCHAR(50)` field to `Parcel` entity (JPA auto-creates column).
2. Add `cancelled_with_bids INTEGER NOT NULL DEFAULT 0` to `User` entity.
3. Add nullable `auction_id BIGINT REFERENCES auctions(id)` to `VerificationCode` entity.
4. Add PARCEL enum value to `VerificationCodeType`.
5. Create `BotTask`, `CancellationLog`, `ListingFeeRefund`, `AuctionPhoto` entities + repositories (no service/controller yet).
6. Create `Auction`, `Parcel`, `ParcelTag`, `AuctionTag` JPA entities over existing tables.
7. Verify schema changes at dev-profile boot (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` and check Postgres `\d` for new tables/columns).
8. Commit.

### Task 2 — World API + Map API + MainlandContinents (~2 hours)

1. Create `SlWorldApiClient` with Jsoup-based HTML parsing. Config properties for base URL, timeout, retry.
2. Create `SlMapApiClient` with regex-based response parsing.
3. Create `MainlandContinents` static helper with the 17 bounding boxes.
4. Unit tests with WireMock: World API success (fixture HTML), 404, 500 retried then error. Map API success, error.
5. Unit tests for MainlandContinents with fixture coordinates per continent + non-Mainland samples.
6. Commit.

### Task 3 — Parcel lookup endpoint (~1.5 hours)

1. Create `ParcelLookupService` wiring World API + Map API + MainlandContinents + `ParcelRepository`.
2. Create `ParcelController` with `POST /api/v1/parcels/lookup`.
3. Global exception handler mappings for `ParcelNotFoundException` (404), `NotMainlandException` (422), `ExternalApiTimeoutException` (504).
4. Integration test: new UUID → 200 with metadata; same UUID → short-circuit; non-Mainland → 422; invalid UUID format → 400.
5. Postman request: `Parcel/Lookup` with capture script for `parcelId`.
6. Commit.

### Task 4 — Auction CRUD + state machine (~3 hours)

1. Create `AuctionService` with methods for create, update (DRAFT/DRAFT_PAID only), cancel.
2. Create `InvalidAuctionStateException` + global mapping to 409.
3. Create `AuctionDtoMapper` with `toSellerResponse` and `toPublicResponse` + `toPublicStatus` switch.
4. Create `AuctionController`: POST /auctions, GET /auctions/{id} (seller vs public dispatch), GET /users/me/auctions, PUT /auctions/{id}, GET /auctions/{id}/preview.
5. Create `CancellationService` handling refund record creation + cancelled_with_bids increment.
6. Create `PUT /auctions/{id}/cancel` endpoint.
7. Unit tests: AuctionStateMachineTest (parameterized transitions), AuctionDtoMapperTest (public/seller shape + status collapse).
8. Integration test: full create/edit/preview/cancel flow for each from-state.
9. Postman requests added to `Parcel & Listings` folder.
10. Commit.

### Task 5 — Dev-profile listing fee payment stub (~1 hour)

1. Create `DevAuctionController` annotated `@Profile("dev")`.
2. `POST /api/v1/dev/auctions/{id}/pay` transitions DRAFT → DRAFT_PAID.
3. Config: `slpa.listing-fee.amount-lindens: 100`.
4. Integration test: pay on DRAFT → 200 with DRAFT_PAID. Pay on DRAFT_PAID → 409. Pay as non-seller → 403.
5. Postman request: `Dev/Simulate listing fee payment`.
6. Commit.

### Task 6 — Unified /verify endpoint + Method A (~2 hours)

1. Create `AuctionVerificationService` with `triggerVerification(auctionId, user)`.
2. Method A dispatch (inline): fetch World API, validate ownership, transition to ACTIVE or VERIFICATION_FAILED.
3. Endpoint: `PUT /api/v1/auctions/{id}/verify`.
4. Integration test: Method A happy path (DRAFT → paid → verify → ACTIVE). Method A failure (World API ownerid mismatch → VERIFICATION_FAILED). Retry from VERIFICATION_FAILED → VERIFICATION_PENDING → ACTIVE.
5. Verify `SellerAuctionResponse.pendingVerification` is null for Method A in both success and failure responses (Method A is synchronous, no pending state persists).
6. Postman request: `Auction/Verify` (capture updated status).
7. Commit.

### Task 7 — Method B (REZZABLE) with PARCEL codes + LSL callback (~2.5 hours)

1. Extend `VerificationCodeService` to generate PARCEL-type codes bound to `auction_id`.
2. Method B dispatch in `AuctionVerificationService`: generate code, populate `pendingVerification` in response.
3. Create `SlParcelVerifyController` with `POST /api/v1/sl/parcel/verify` — public (no JWT), validates SL headers.
4. Create `SlParcelVerifyService` handling code lookup, validation, auction transition.
5. Integration test: generate code via verify → call /sl/parcel/verify with correct SL headers → ACTIVE. Wrong shard → 403. Wrong owner-key → 403. Expired code → 400. Code/auction/parcel mismatch → 400.
6. Create `ParcelCodeExpiryJob` with `@Scheduled` — unit test: stuck Method B auctions → reverted to DRAFT_PAID.
7. Postman requests: `Auction/Verify` (Method B flow), `Dev/Simulate SL parcel verify` (with SL headers populated).
8. Commit.

### Task 8 — Method C (SALE_TO_BOT) + bot_task queue + dev stub (~2.5 hours)

1. Create `BotTaskService` with methods: create, markCompleted, markFailed, findPending, findPendingOlderThan, markTimedOut.
2. Create `BotTaskController` with `GET /api/v1/bot/tasks/pending` and `PUT /api/v1/bot/tasks/{taskId}` (no auth for now, deferred to Epic 06).
3. Create `DevBotTaskController` with `POST /api/v1/dev/bot/tasks/{taskId}/complete`.
4. Method C dispatch in `AuctionVerificationService`: create bot_task, populate `pendingVerification.botTaskId` + instructions.
5. Integration test: create Method C auction → pay → verify → bot_task created (pending) → `Dev/Simulate bot completion` SUCCESS → ACTIVE. FAILURE → VERIFICATION_FAILED.
6. Create `BotTaskTimeoutJob` with `@Scheduled` — unit test: 48h-old PENDING task → auction to VERIFICATION_FAILED, task to FAILED with reason=TIMEOUT.
7. Postman requests: `Auction/Verify` (Method C), `Dev/Simulate bot completion`, `Bot/Get pending tasks`, `Bot/Report task result`.
8. Commit.

### Task 9 — Parcel tags + photo upload pipeline (~2 hours)

1. Create `ParcelTagService` + `ParcelTagController` with `GET /api/v1/parcel-tags` (grouped by category).
2. Validate tag codes in `AuctionService.create`/`update` (resolve codes to IDs via repository lookup).
3. Create `AuctionPhotoService` reusing `AvatarImageProcessor` pattern for image validation.
4. Create `AuctionPhotoController` with `POST /api/v1/auctions/{id}/photos` (multipart), `DELETE /api/v1/auctions/{id}/photos/{photoId}`, `GET /api/v1/auctions/{id}/photos/{photoId}/bytes`.
5. Configure MinIO bucket `listings/` path prefix.
6. Integration test: upload JPEG → 201. Upload BMP → 400. Oversized file → 413. Upload 11th photo → 413. Delete photo → 204, MinIO object gone.
7. Integration test: tag code validation — invalid tag code → 400. Duplicate tag codes in request → deduplicated or rejected.
8. Postman requests: `Tags/List`, `Photos/Upload`, `Photos/Delete`.
9. Commit.

### Task 10 — Scheduled jobs, docs, and PR (~1.5 hours)

1. Verify both scheduled jobs run at configured intervals in dev (log output or metrics).
2. Sweep `README.md`: add Parcel & Listings endpoint catalog, bump backend test count, mention new tables.
3. Add FOOTGUNS entries:
   - Continent bounding boxes are static; review annually for Linden additions
   - WebClient retry semantics: only retries 5xx, not 4xx
   - Hibernate DDL-auto creates tables on first boot; prod migrations deferred to production-prep sub-spec
   - Jackson's `PublicAuctionStatus` type-level enforcement prevents terminal-status leaks
   - LSL endpoint requires SL-injected headers; dev tests set them manually
4. Add `DEFERRED_WORK.md` entries:
   - Bot service auth (Epic 06): `/api/v1/bot/tasks/*` endpoints ship without auth in sub-spec 1
   - Listing fee refund processor (Epic 05): `listing_fee_refunds.status=PENDING` rows await Epic 05
5. Run the full verify chain: `./mvnw test`, manual Postman smoke.
6. Open PR into `dev`: `gh pr create --base dev --title "Epic 03 sub-spec 1: parcel verification + listing lifecycle (backend)" ...`
7. Return to `dev` locally.
8. Commit: `docs: README sweep, FOOTGUNS entries, and DEFERRED_WORK updates for Epic 03 sub-spec 1`.

**Total estimate:** ~20 hours across 10 tasks.

---

## 19. Deferred questions (for future sub-specs / epics)

Captured for context; not blocking sub-spec 1.

1. **Admin endpoints for bot task management.** No admin model exists yet. When admin roles land (Epic 10), we'll add real admin endpoints for manually completing, reassigning, or cancelling bot tasks. Dev stub is the interim.
2. **Bot worker authentication.** Production `/api/v1/bot/tasks/*` endpoints ship without auth. Epic 06 will add bearer token or mTLS.
3. **Listing fee refund processor.** `ListingFeeRefund` rows accumulate in PENDING state. Epic 05 escrow will poll and process them.
4. **Continent bounding box refresh.** Static list sourced from SL wiki. If Linden adds a new Mainland continent, the list goes stale. Annual review process or automated check needed.
5. **PARCEL code rate-limiting for fraud signals.** Already in `DEFERRED_WORK.md` → Epic 10.
6. **SLPA service account UUID.** Production value for `X-SecondLife-Owner-Key` validation. Dev uses a fixture UUID.
7. **Primary escrow UUID.** Production value for Method C's `authBuyerId` check. Dev uses a fixture UUID.
8. **Region position in SLURL.** Currently stored at lookup time from World API meta tags. If World API doesn't include position meta, we fall back to region-center coordinates. Edge case worth testing.
9. **Auction archival.** COMPLETED/CANCELLED/EXPIRED auctions pile up indefinitely. Future epic (Epic 10?) will define retention policy.
10. **Rate limiting on `/parcels/lookup`.** Each lookup hits World API. Malicious callers could hammer the endpoint. Future epic adds per-user rate limiting.

---

## 20. Decisions log (from brainstorm)

Locked via Q&A on 2026-04-16:

- **Q1 scope** → C: combined tasks 01+02+03+04 in one backend sub-spec (Option 2 pattern: backend-first, UI in sub-spec 2).
- **Q2 parcel visibility at draft time** → B with corrections: metadata lookup creates shared parcel row; ownership check deferred to verification time; parcels 1:many with auctions (not 1:1) to prevent malicious blocking.
- **Q3 PARCEL code binding** → A: code bound to specific auction_id via nullable FK on verification_codes.
- **Q4 verification dispatch** → unified `PUT /auctions/{id}/verify` endpoint with method-based dispatch, replacing task-02/03's three separate endpoints.
- **Q5 DTO response shape for /verify** → single `SellerAuctionResponse` with computed `pendingVerification` nested field populated during VERIFICATION_PENDING.
- **Q6 stubs** → dev-profile only for listing fee payment (`POST /dev/auctions/{id}/pay`) and bot completion (`POST /dev/bot/tasks/{id}/complete`). No admin endpoints until admin model exists.
- **Q7 endpoint surface** → photos endpoints + tag reference endpoint both included in sub-spec 1 backend.
- **Q8 external API orchestration** → continent bounding box check replaces Grid Survey entirely; no re-verification of Mainland status after lookup.
- **Q9 tag API shape** → tag codes (VARCHAR), not IDs. Public API stable regardless of internal PK type.
- **Q10 public status collapse** → `PublicAuctionStatus { ACTIVE, ENDED }` type-enforced at Jackson serialization layer; terminal statuses (COMPLETED, CANCELLED, EXPIRED, DISPUTED) never reach public responses.
- **Q11 cancellation mechanics** → `ListingFeeRefund` records (PENDING until Epic 05) + `CancellationLog` entity + `cancelled_with_bids` counter on User.
- **Q12 state machine** → confirmed transition table including Method B code expiry returning to DRAFT_PAID (no refund) and Method C timeout returning to VERIFICATION_FAILED (no refund, retry via re-verify).
- **Locking set for parcel re-listing** → ACTIVE, ENDED, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING, DISPUTED (VERIFICATION_PENDING does NOT lock; DISPUTED does).
- **`parcels.verified` semantic** → "metadata successfully fetched from World API" (user-agnostic), not an ownership claim. Ownership state lives per-auction on `auctions.verification_tier` + `auctions.verified_at`.
- **Reserve auction public representation** → `hasReserve: boolean` + `reserveMet: boolean`, never the raw `reserve_price`.
- **Winner ID privacy** → `winner_id` excluded from `PublicAuctionResponse` entirely; final bid amount (`current_bid`) remains public.
