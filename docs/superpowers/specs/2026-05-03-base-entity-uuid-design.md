# BaseEntity + Public UUID Identifier — Design

**Date:** 2026-05-03
**Status:** Design approved, pending implementation plan
**Scope:** All backend entities (~33 entities), associated DTOs, frontend types, JWT auth, Postman collection. Bot DTOs unchanged. LSL scripts unchanged.

---

## 1. Goals & non-goals

### Goals

1. **Anti-scrape.** A user cannot iterate `/api/v1/auctions/1`, `/api/v1/auctions/2`, … to enumerate the entire dataset. Public-facing identifiers are random and non-sequential.
2. **Anti-order-leak.** A user cannot infer creation order or creation time from a public identifier. "Was I user #5 or user #50,000?" should be unanswerable from anything we expose.
3. **Standardize audit fields.** Every entity carries a consistent `createdAt`. Mutable entities additionally carry `updatedAt` and an optimistic-lock `version`. Today's mix of `@CreationTimestamp` / `@UpdateTimestamp` placement is inconsistent across entities.
4. **One BaseEntity hierarchy.** No per-entity decision about which audit fields to include. Mutability is the only axis.

### Non-goals

- Soft delete (`deletedAt`) standardization. Stays opt-in per entity (today only `User` uses it).
- `createdBy` / `updatedBy` audit. Forensic data already lives in the targeted `AdminAction` entity; adding "who" to every row would be 95% nulls. Out of scope.
- Schema-level enforcement that internal `id` cannot leak to the wire. Defense is `@JsonIgnore` on the field plus DTO-mediated controllers — code review is the final guard.
- Eliminating `Long` PKs. We deliberately keep `Long` as the internal PK type for B-tree health, JWT/JOIN performance, and zero impact on bot/LSL/Postman wire contracts.

---

## 2. Architecture decisions

### 2.1 Two-identifier model per entity

Every entity carries:

- **`Long id`** — internal primary key. Used for FK joins, JWT principal lookups *post-resolution*, admin/internal endpoints, bot/LSL contracts, Postman variables. **Never crosses a public wire.** `@JsonIgnore`-annotated.
- **`UUID publicId`** — public identifier. Random UUIDv4. Used in REST URLs, REST/WebSocket DTOs, frontend types, JWT subject claim. The only identifier safe to expose anywhere a user can see it.

Both are present on every entity. Decision made universal-by-default rather than opt-in per entity because:
- Adding a `NOT NULL UNIQUE` column to a hot existing table later is operationally painful (lock + backfill + concurrent index build); pre-existing it pre-launch costs ~zero.
- We can't predict today which entities will sprout public surfaces over the product's life. Cheap insurance.
- Storage cost is rounding noise (~48 bytes/row including unique index).

### 2.2 BaseEntity hierarchy

```
BaseEntity            (Long id, UUID publicId, OffsetDateTime createdAt)
└─ BaseMutableEntity  (+ OffsetDateTime updatedAt, Long version)
```

Two `@MappedSuperclass`es. Mutable entities extend `BaseMutableEntity`. Append-only logs extend `BaseEntity` directly.

Layered (parent/child) rather than sibling because:
- Matches Spring Data Commons' `AbstractPersistable` → `AbstractAuditable` convention.
- Mutability is expressed positively at the declaration site: `class User extends BaseMutableEntity` says "this row can be UPDATE'd." The naked `class Bid extends BaseEntity` is the marked exception.
- `@Version`, future `deletedAt`, and any other mutability-axis concern have a clean home — not duplicated across siblings.

### 2.3 BaseEntity Java skeleton

```java
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false,
            columnDefinition = "uuid")
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private UUID publicId = UUID.randomUUID();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity that)) return false;
        return publicId.equals(that.publicId);
    }

    @Override
    public final int hashCode() {
        return publicId.hashCode();
    }
}

@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseMutableEntity extends BaseEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
```

Key invariants enforced:

- **Lombok uses `@SuperBuilder`, not `@Builder`.** Plain `@Builder` does not compose across inheritance. Every entity that extends a base must also use `@SuperBuilder`.
- **`equals`/`hashCode` are `final` on `BaseEntity`.** Subclasses cannot override. Lombok `@EqualsAndHashCode` is forbidden on subclasses.
- **`publicId` assigned at object construction** via `@Builder.Default UUID.randomUUID()`. Always non-null from the moment `new` is called — equality works pre-persist, no Vlad-pattern null-guards needed.
- **Setters off for `id` and `publicId`.** Both are write-once: `id` via `IDENTITY` at insert, `publicId` at construction. Never overwritten afterwards.
- **`@JsonIgnore` on `id`.** Even if an entity is accidentally serialized directly (without a DTO), the internal Long cannot leak.

### 2.4 Why not UUID PKs (Option 1)?

Considered: every entity has a single `UUID id` (no separate `publicId`). Rejected because:
- Random UUID PKs cause B-tree fragmentation on hot insert tables (`bids`, `user_ledger`, `notifications`). Real cost at scale, none on Long PKs.
- Bot/LSL/Postman wire contracts would all need to switch to UUID strings, expanding the blast radius materially. Audit showed this would be ~2h bot work + dispatcher batch-size adjustment, so it's not enormous, but Option 3 avoids it entirely with no downside.
- JWT cookies grow ~28 bytes per token with no benefit (subject becomes UUID either way, but service-internal lookups suffer if JWT principal must always carry UUID).

### 2.5 Why not opt-in publicId (Option 2)?

Considered: only public-facing entities (~5–8) get `publicId`; internal entities (UserLedgerEntry, AdminAction, BotTask, etc.) keep only `Long id`. Rejected because:
- "Add NOT NULL UNIQUE column to a hot existing table" is a real operational pain point we'd be deferring rather than avoiding. Pre-launch is the cheapest possible time to commit.
- Uniformity has real engineering value: one BaseEntity shape, one rule, no per-entity judgment calls during scaffolding.
- The "wasted" publicId column on internal entities costs ~48 bytes/row including unique index. Rounding noise.

---

## 3. Per-entity classification

### Extends `BaseMutableEntity` (~28)

`User`, `Auction`, `AuctionParcelSnapshot`, `AuctionPhoto`, `Region`, `Terminal`, `TerminalSecret`, `TerminalCommand`, `BotTask`, `BotWorker`, `Notification`, `ParcelTag`, `Review`, `ReviewResponse`, `Escrow`, `EscrowTransaction`, `Withdrawal`, `Ban`, `ListingReport`, `ProxyBid`, `VerificationCode`, `ListingFeeRefund`, `FraudFlag`, `ReconciliationRun`, `SavedAuction`, `SlImMessage`, `BidReservation`, `RefreshToken`.

### Extends `BaseEntity` directly (~5)

`Bid`, `UserLedgerEntry`, `AdminAction`, `ReviewFlag`, `CancellationLog`.

### Implementation-time verification

The classification was assigned by reading each entity's lifecycle (presence of `@UpdateTimestamp`, mutable status fields, `*_at` lifecycle columns). Two entities not directly read during design that should be confirmed during implementation:

- **`CancellationLog`** — assumed append-only audit. If it has any updatable lifecycle field, promote to `BaseMutableEntity`.
- **`AuctionPhoto`** — assumed mutable (display order, status). If photos are pure inserts + hard deletes only, demote to `BaseEntity`.

---

## 4. Wire format conventions

### 4.1 Field naming — explicit, no aliasing

The wire field name reveals which kind of identifier it is. This avoids "in this layer `id` means Long, in that layer it means UUID" footguns.

| Surface | Field name | Type |
|---|---|---|
| Public DTOs (REST + WebSocket envelopes) | `publicId` | `UUID` (JSON: 36-char string) |
| Bot/internal DTOs | `id` | `Long` (JSON: number) |
| Admin DTOs for user-facing entities (User, Auction, Review, etc.) | `publicId` | `UUID` |
| Admin DTOs for internal-only entities (UserLedgerEntry, AdminAction, ReconciliationRun, etc.) | `id` | `Long` |

Public DTO mappers always do `dto.publicId = entity.getPublicId()` — same name on both sides, no aliasing. Bot DTOs (`BotTaskResponse`, etc.) keep `id: Long` unchanged. Admin DTOs follow the same rule the URL paths follow (§4.2): if the admin endpoint is a user-facing-entity view, use `publicId`; if it's an internal-data view (ledger, audit, reconciliation), use `id`.

### 4.2 URL paths

| Surface | Pattern |
|---|---|
| Public web/mobile API | `/api/v1/auctions/{publicId}`, `/api/v1/users/{publicId}/profile` |
| Bot internal | `/api/v1/bot/tasks/{taskId}` (Long, unchanged) |
| Admin user-facing views | `/api/v1/admin/users/{publicId}` |
| Admin internal-data views (ledger, audit) | `/api/v1/admin/ledger/{id}` (Long is fine — admin-only, authenticated) |
| LSL HTTP-in callbacks | unchanged — most carry SL UUIDs, not backend PKs |

### 4.3 JWT subject

JWT `sub` claim carries the user's `publicId` (UUID string), not the internal `Long`. Reasons:

- The whole point of `publicId` is "the only identifier safe wherever the user can see it." JWTs live in the user's cookie jar and can leak via XSS, console logs, or pasted-in-a-forum. A `sub: 1234` claim leaks "you're user #1234" — the exact order-position leak we're avoiding.
- Cost is one secondary-index seek per authenticated request (`findByPublicId` instead of `findById`) — cacheable in the request-scoped principal cache.
- Cookie size cost ~28 bytes per JWT, meaningless.

The auth filter resolves the UUID subject to the user's identity at request entry. The Spring `@AuthenticationPrincipal` carries both `id` (Long) and `publicId` (UUID) — exact shape (a thin synthetic principal record vs. the full `User` entity) is decided during implementation; either way, service code reads `principal.id()` for FK joins and `principal.publicId()` for outbound JSON without extra DB hits.

### 4.4 Frontend type renames

Every TypeScript type backing a public DTO renames `id: number` → `publicId: string`:

- Find every `id: number` on a public-facing type → `publicId: string`.
- Find every `Number(params.id)` parse → drop, use `params.publicId` directly.
- Rename Next.js dynamic route segments: `[id]/page.tsx` → `[publicId]/page.tsx`.
- TanStack Query cache keys: replace numeric IDs with publicId strings.
- WebSocket subscription topics: `/topic/auction/{auctionPublicId}/bids`.

### 4.5 Postman

`SLPA Dev` environment variable splits:

- `userPublicId`, `auctionPublicId`, `reviewPublicId`, etc. — extracted from `publicId` field of public DTOs, threaded into URL paths and bodies.
- `botTaskId` — unchanged, still Long, used by bot endpoint requests.

Login chain: `/auth/login` response now carries `publicId` instead of `id`; test scripts that today call `pm.environment.set('userId', json.id)` change to `pm.environment.set('userPublicId', json.publicId)`.

### 4.6 What's *not* changing on the wire

- **Bot DTOs:** 3 fields (`BotTaskResponse.Id`, `.AuctionId`, `.EscrowId`) stay `long`. Backend bot endpoints continue to use Long internal ids in URLs and bodies.
- **LSL scripts:** entirely untouched. None of `parcel-verifier.lsl`, `slpa-terminal.lsl`, `slpa-verifier-giver.lsl`, `verification-terminal.lsl`, or `dispatcher.lsl` carry backend PKs in payloads that change shape under Option 3. The earlier dispatcher batch-size concern was Option-1-specific.

---

## 5. Migration strategy

### 5.1 Single Flyway migration

`backend/src/main/resources/db/migration/V12__base_entity_uuid_migration.sql`

Shape: `DROP TABLE` every existing application table in dependency order, then `CREATE TABLE` everything in the new shape. Wraps in a single transaction.

The migration covers per table:

1. `public_id uuid not null unique` column.
2. `version bigint not null default 0` on mutable tables only.
3. `updated_at timestamptz not null default now()` on mutable tables (standardized — most exist today via `@UpdateTimestamp` but the SQL-level default is currently inconsistent).
4. Re-create existing indexes.
5. Re-create FK constraints (still Long-typed; FK columns referencing other tables stay `bigint`).
6. New unique index per table on `public_id`.

Drop-and-recreate over incremental ALTER because:
- ~33 tables × ~6 changes each ≈ 200 ALTER statements with ordering hazards. Error-prone.
- Pre-launch + Fargate wipe means we don't lose data we care about.
- Clean V12 makes the design intent obvious to anyone reading the migration history.

### 5.2 Prod rollout sequence

1. **Merge to `dev`, then `main`.** Backend image builds via `.github/workflows/deploy-backend.yml`. Frontend rebuilds via Amplify on its own webhook (independent timing). The wire-format change requires manual coordination — both must roll over together.
2. **Run the documented Fargate DB-wipe task** (CLAUDE.md "DB wipe procedure"). Drops the `slpa` schema including `flyway_schema_history`.
3. **`aws ecs update-service --force-new-deployment`** on `slpa-prod-backend`. New container boots, Flyway sees an empty DB, applies V1 → V12 in order. Final state: schema in the new shape.
4. **Smoke test** via Postman: register a user, place a bid, watch CloudWatch. Confirm `publicId` appears in responses, internal `id` does not.

### 5.3 Rollback

If V12 fails partway through on prod, the transaction rolls back. Backend won't start (Hibernate validate fails against the partial schema). Recovery: another Fargate wipe + redeploy with the fixed migration.

If the deploy succeeds but a regression is found post-launch: rolling back means redeploying the prior backend image AND wiping the DB AND redeploying the prior frontend. Plan around this by:
- Keeping the prior backend image tag handy.
- Deploying inside a window where ~1h of remediation is acceptable.

### 5.4 Dev environment

`docker compose up --build` starts a fresh Postgres each time. Flyway runs on every backend boot. V12 just runs once per fresh DB. No special steps.

---

## 6. Testing strategy

### 6.1 Unit tests

Existing tests continue to pass with one mechanical change: test fixtures that construct entities via `@SuperBuilder` get `publicId` defaulted automatically. No test needs to set publicId explicitly unless asserting on it.

A subtle improvement: tests asserting on entity equality (`expected.equals(actual)`) now work pre-persist because `publicId` is non-null from construction. Today's "id is null until persist" pain in unit tests goes away.

### 6.2 Integration tests (`@SpringBootTest`)

Per-test-class DB wipe via Testcontainers / shared dev container works as today. Flyway runs V1 → V12 on each fresh schema — slightly slower test boot, ms-scale.

### 6.3 New tests to add

1. **`BaseEntityEqualityTest`** — two transient entities with different `publicId` are not equal; same `publicId` are equal; equality survives the `persist` boundary.
2. **`BaseMutableEntityVersionTest`** — concurrent updates on a representative entity (e.g., `Auction`) raise `OptimisticLockException`. Demonstrates `@Version` is wired.
3. **`PublicIdSerializationTest` (`@WebMvcTest`)** — a representative entity → DTO → JSON round trip emits `publicId` and never `id`. Covers `@JsonIgnore` invariant + DTO mapper convention.
4. **`JwtSubjectIsPublicIdTest`** — login → decode JWT → `sub` claim is the user's UUID `publicId`, not their `Long id`.

### 6.4 Frontend tests

Vitest tests on components that consume IDs: rename test fixtures to use UUID-style strings. Mechanical.

### 6.5 Manual / out-of-band verification

- **Postman** chained-variable scripts. Failures are loud — request expects `userPublicId` and prior response set `userId` → 404. Human-tested post-deploy.
- **Bot vs backend wire compatibility.** Bot's `dotnet test` exercises HTTP via WireMock stubs, not a real backend. Verified manually post-deploy by watching bot logs for end-to-end task lifecycle.

---

## 7. External-system impact summary

| System | Impact | Effort |
|---|---|---|
| Backend Java | ~33 entities + ~50 DTOs + auth filter + JWT principal + DTO mappers | days |
| Backend SQL | Single V12 migration | hours |
| Frontend types | `id: number → publicId: string` across all public types + dynamic route renames + WebSocket subscription topics | day |
| Bot (.NET) | 3 DTO fields stay `long`; **no change required** | none |
| LSL scripts | **No change** | none |
| Postman | Variable rename in test scripts; new env vars | hours |

---

## 8. Open implementation-time items

Captured in the design but verified during implementation:

1. **`CancellationLog` and `AuctionPhoto`** mutability — confirm their classification before assignment to base.
2. **Spring Security principal shape** — exact `@AuthenticationPrincipal` payload carrying both `id` and `publicId`. Likely a thin synthetic principal record rather than the full `User` entity, to keep the security context cheap.
3. **`PagedResponse<T>` of public DTOs** — verify the existing `PagedResponse.from(page.map(dtoMapper::toDto))` pattern composes cleanly with `publicId`-bearing DTOs (no expected issues; flagged for completeness).
4. **WebSocket subscription topic migration** — every existing topic that interpolates an `auctionId` or `userId` switches to `auctionPublicId`/`userPublicId`. Inventory and rename.
5. **Photo URL helpers** (`apiUrl(photo.url)`) — confirm the backend-emitted photo paths are publicId-keyed, not id-keyed.
6. **JWT token-version (`tv`) claim** — unchanged. Domain-level token freshness counter stays as-is on `User.tokenVersion`.

---

## 9. Out of scope (deferred)

Captured here so they don't get added to this work scope, and so they're easy to reach for later:

- **Soft delete (`deletedAt`) standardization** — opt-in per entity stays. Today only `User` uses it; if more entities need tombstoning, add per-entity.
- **`createdBy` / `updatedBy` audit columns** — too many nulls for too little value. Targeted forensics already in `AdminAction`.
- **`@SoftDelete` / `@SQLRestriction` query filters** — only relevant if soft-delete becomes universal, which it isn't.
- **Switching JWT token-version (`tv`) to publicId-keyed semantics** — orthogonal change, current behavior unchanged.

---

## 10. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| `@SuperBuilder` breaks existing test fixtures that use `@Builder` | High (mechanical) | Find/replace `@Builder` → `@SuperBuilder` on every entity; tests recompile and reveal callers. |
| Internal `Long id` accidentally leaks via direct entity serialization | Medium | `@JsonIgnore` on `BaseEntity.id`; existing convention is DTO-mediated controllers. |
| Auth filter becomes the new performance hot-path due to `findByPublicId` per request | Low | Spring Security request-scoped principal cache. Can promote to a Redis-backed cache if traces show contention. |
| Frontend rollover lags backend rollover (Amplify timing) | Medium | Manual coordination during deploy window. Worst case: brief frontend errors as old types hit new responses. |
| Lombok + JPA edge cases with two `@MappedSuperclass`es | Low | Spring Data Commons does this exact pattern (`AbstractPersistable` → `AbstractAuditable`). Well-trodden path. |
