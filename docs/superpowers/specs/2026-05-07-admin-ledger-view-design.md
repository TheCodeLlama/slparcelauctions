# Admin Global Ledger View — Design

**Date:** 2026-05-07
**Status:** Approved
**Owner:** Claude (autonomous implementation per user direction)

## 1. Goal

Give admins a single page where they can search and audit every L$-touching event across every user, drawn from all five money-event tables in the system: `user_ledger`, `escrow_transactions`, `terminal_commands`, `withdrawals`, and `bid_reservations`. Today admins only have a per-user ledger (the Wallet tab on `/admin/users/<publicId>`) — there is no cross-user view, and several money-event sources don't appear in the per-user view at all (escrow internals, terminal command queue, admin withdrawals).

The goal is operational reconciliation: pick a user + date range and see the full chain of money events in one timeline, with one-click drill-down to the source's native detail page.

## 2. Architecture

**Route:** `/admin/ledger` (new). Sidebar entry "Ledger" between **Bans** and **Listings** in `AdminShell`.

**Backend package** (new): `backend/src/main/java/com/slparcelauctions/backend/admin/ledger/`

```
AdminLedgerController.java            # GET /api/v1/admin/ledger
AdminLedgerService.java               # filter validation, native-query orchestration, drill resolution
AdminLedgerQueryRepository.java       # native UNION ALL across the 5 tables
AdminLedgerExceptionHandler.java      # 400/404 ProblemDetail mapping
exception/AdminLedgerStateException.java
dto/AdminLedgerRowDto.java
dto/AdminLedgerFilterParams.java
dto/AdminLedgerKind.java              # USER_LEDGER, ESCROW_TXN, TERMINAL_CMD, WITHDRAWAL, BID_RESERVATION
```

No new Hibernate entities — the 5 source entities are read via native SQL projection.

**Frontend package** (new): `frontend/src/components/admin/ledger/`

```
AdminLedgerPage.tsx                   # page shell, URL state, filter bar
AdminLedgerTable.tsx                  # 10-col unified table
AdminLedgerFilterBar.tsx              # filter row (search / date / amount / kind / user / entryType / refType+refId)
UserTypeahead.tsx                     # the user-search input + dropdown
AdminLedgerKindBadge.tsx
AdminLedgerStatusBadge.tsx
adminLedgerLinks.ts                   # row → drill-down URL resolver
```

Plus types/api/queryKeys/hooks added to existing `lib/admin/api.ts`, `lib/admin/types.ts`, `lib/admin/queryKeys.ts`, and a new `frontend/src/hooks/admin/useAdminLedger.ts`.

**Data flow:**

```
/admin/ledger
  → useAdminLedger(filters)
  → GET /api/v1/admin/ledger?<filters>
  → AdminLedgerService.list(filterParams, pageable)
  → AdminLedgerQueryRepository.search(...)        // native UNION ALL across selected kinds
  → AdminLedgerRowDto[]                            // normalized
```

Authz: `@PreAuthorize("hasRole('ADMIN')")` on the controller class.

## 3. The unified row shape

```java
public record AdminLedgerRowDto(
    AdminLedgerKind kind,             // USER_LEDGER / ESCROW_TXN / TERMINAL_CMD / WITHDRAWAL / BID_RESERVATION
    String eventId,                   // composite "{kind}-{nativeId}" — globally unique row key
    Long nativeId,                    // the source table's PK
    OffsetDateTime createdAt,         // sort axis
    UUID userPublicId,                // primary user (resolved); null when no resolvable user
    String username,                  // denormalized snapshot for display
    UUID counterpartyPublicId,        // null for kinds with one user
    String counterpartyUsername,
    Long amountLindens,               // signed from primary user's perspective
    String entryType,                 // kind-native subtype string
    String status,                    // kind-native status string; null for USER_LEDGER (always settled)
    String refType,                   // USER_LEDGER native; synthesized for other kinds
    Long refId,
    String description                // pre-formatted summary line built server-side
) {}
```

### 3.1 Resolution rules per kind

**`userPublicId` / `username`** (the row's primary user):
- `USER_LEDGER` → join `users` on `user_id`.
- `ESCROW_TXN` → resolve the SL avatar UUID side that "matches" the row's direction:
  - PAYMENT / LISTING_FEE_PAYMENT → user is `payer`.
  - PAYOUT / REFUND / COMMISSION → user is `payee`.
  - Resolved via `LEFT JOIN users payer_u ON payer_u.sl_avatar_uuid = et.payer` (and same for `payee`).
- `TERMINAL_CMD` → `LEFT JOIN users ON users.sl_avatar_uuid = tc.recipient_uuid`.
- `WITHDRAWAL` → same as TERMINAL_CMD on `recipient_uuid`.
- `BID_RESERVATION` → join `users` on `user_id`.

When the resolved user is null (e.g. SL primary escrow avatar on a payment), the column renders empty.

**`counterpartyPublicId` / `counterpartyUsername`**: filled only for `ESCROW_TXN` (the other side of the payer/payee pair) and `WITHDRAWAL` (the `admin_user_id` who initiated). Other kinds set null.

**Projected `user_id` and `counterparty_user_id` columns** (internal Long; not in the DTO — used only by the outer query's user filter): each arm's SELECT projects these as the resolved internal-id JOINs (`u.id`/`payer_u.id`/`payee_u.id`/`recipient_u.id`/`admin_u.id` etc.). These are what the WHERE clause compares against for "user matches either side". The DTO carries the public UUID; the filter uses the internal Long.

**`amountLindens`** — single rule: **signed from the resolved primary user's wallet perspective.** Money leaving the user's wallet → negative; money arriving in the user's wallet → positive. When no user is resolved (e.g. commission with platform-only payer/payee), sign is positive by convention but the row renders with no User and the sign carries little meaning.

| Kind | Sign rule |
|---|---|
| `USER_LEDGER` | Already signed in the column — pass through. |
| `ESCROW_TXN` PAYMENT / LISTING_FEE_PAYMENT / LISTING_PENALTY_PAYMENT | User is the payer; money leaves them → **negative**. |
| `ESCROW_TXN` PAYOUT / REFUND | User is the payee; money arrives → **positive**. |
| `ESCROW_TXN` COMMISSION | No user typically resolves (platform). Amount projected as positive; user column empty. |
| `TERMINAL_CMD` | Money leaves the user's wallet to the in-world terminal → **negative**. |
| `WITHDRAWAL` | Admin moves L$ off the platform on the user's behalf → **negative** from the user's POV. |
| `BID_RESERVATION` (RESERVED) | Hold (money becomes unavailable) → **negative**. |
| `BID_RESERVATION` (RELEASED) | Hold released → **positive**. |

**`description`** (built server-side per kind):
- `USER_LEDGER` → echoes the existing `description` column.
- `ESCROW_TXN` → `"<TYPE> escrow #<escrowId> (auction #<auctionId>)"`.
- `TERMINAL_CMD` → `"<purpose> → <recipient_username_or_uuid> (status=<status>, attempt <n>/<max>)"`.
- `WITHDRAWAL` → `"Admin withdrawal → <recipient_username> (status=<status>)"`.
- `BID_RESERVATION` (RESERVED) → `"Bid reserved on auction #<auctionId>"`.
- `BID_RESERVATION` (RELEASED) → `"Bid released (<release_reason>)"`.

### 3.2 Why a flat record over a sealed union

Every row renders the same 10 columns. A sealed/per-kind union would force discriminator-based rendering in the frontend, doubling the rendering code. The flat DTO with `kind` makes the table component simple (`row.amountLindens`, `row.description`, etc.) and pushes kind-aware logic into the SQL projection where it belongs.

## 4. Backend — query

A single native `SELECT` whose body is a `UNION ALL` of up to 5 sub-selects (one per selected kind). Each sub-select projects the same column list as `AdminLedgerRowDto`. The outer query applies WHERE filters and `ORDER BY created_at DESC, kind ASC, native_id ASC` (composite tiebreaker for stable pagination), then `LIMIT`/`OFFSET`. A separate `COUNT(*)` over the same UNION-ALL body produces `totalElements`.

### 4.1 Sub-select shape (USER_LEDGER arm)

```sql
SELECT
    'USER_LEDGER'                                AS kind,
    ule.id                                       AS native_id,
    ule.created_at                               AS created_at,
    u.public_id                                  AS user_public_id,
    u.username                                   AS username,
    NULL::uuid                                   AS counterparty_public_id,
    NULL::text                                   AS counterparty_username,
    ule.amount                                   AS amount_lindens,
    ule.entry_type                               AS entry_type,
    NULL::text                                   AS status,
    ule.ref_type                                 AS ref_type,
    ule.ref_id                                   AS ref_id,
    ule.description                              AS description,
    -- search fan-out projected as one column for the outer WHERE LOWER(searchable) LIKE
    LOWER(COALESCE(ule.description, '')
          || ' ' || COALESCE(ule.sl_transaction_id, '')
          || ' ' || COALESCE(ule.idempotency_key, '')) AS searchable_text,
    -- counterparty resolved id for the outer "user matches either side" predicate
    NULL::bigint                                 AS counterparty_user_id,
    ule.user_id                                  AS user_id
FROM user_ledger ule
JOIN users u ON u.id = ule.user_id
```

Other arms follow the same shape with kind-specific JOINs and CASE-built signed amount. The `BID_RESERVATION` arm UNION-ALLs internally to produce two rows per reservation row (one for RESERVED at `created_at`, one for RELEASED at `released_at` when not null).

### 4.2 Filter application

All filters apply at the **outer** query, against the projected alias columns:

```sql
WHERE 1=1
  AND (:dateFrom IS NULL OR created_at >= :dateFrom)
  AND (:dateTo   IS NULL OR created_at <  :dateTo)
  AND (:userId IS NULL OR user_id = :userId OR counterparty_user_id = :userId)
  AND (:amountMin IS NULL OR ABS(amount_lindens) >= :amountMin)
  AND (:amountMax IS NULL OR ABS(amount_lindens) <= :amountMax)
  AND (:refType IS NULL OR ref_type = :refType)
  AND (:refId   IS NULL OR ref_id   = :refId)
  AND (:entryType IS NULL OR entry_type = :entryType)
  AND (:search  IS NULL OR searchable_text LIKE :search)
ORDER BY created_at DESC, kind ASC, native_id ASC
LIMIT :limit OFFSET :offset
```

The `kinds` filter is applied at SQL-build time — only selected arms are compiled into the UNION, unselected arms drop out entirely.

### 4.3 New indexes (V17 Flyway migration)

```sql
CREATE INDEX IF NOT EXISTS ix_escrow_transactions_created_at ON escrow_transactions (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_terminal_commands_created_at   ON terminal_commands (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_withdrawals_created_at         ON withdrawals (created_at DESC);
```

`user_ledger` already has the composite `(user_id, created_at DESC)` from V3. `bid_reservations` is left un-indexed for now; see §6.

## 5. Backend — endpoint

`GET /api/v1/admin/ledger`

| Param | Type | Default | Notes |
|---|---|---|---|
| `kinds` | `AdminLedgerKind[]` | `[]` (= all 5) | Repeatable. |
| `userPublicId` | UUID? | null | Service resolves to internal Long once. |
| `entryType` | string? | null | Kind-native subtype. Requires exactly one `kinds` value. |
| `refType` | string? | null | |
| `refId` | Long? | null | Requires `refType`. |
| `dateFrom` | ISO datetime? | null | |
| `dateTo` | ISO datetime? | null | Half-open `< dateTo`. |
| `amountMin` | Long? | null | `ABS(amount_lindens) >=`. |
| `amountMax` | Long? | null | `ABS(amount_lindens) <=`. |
| `search` | string? | null | Matches `searchable_text` (description + sl_transaction_id + idempotency_key + terminal_id, per arm). |
| `page` | int | 0 | |
| `size` | int | 50 | Admin-only — no clamp. |
| `sort` | string | `createdAt,desc` | Whitelist: `createdAt`, `amountLindens`. |

**Response:** `PagedResponse<AdminLedgerRowDto>`.

**Error codes** (`AdminLedgerStateException` → ProblemDetail):

| Code | HTTP | Cause |
|---|---|---|
| `INVALID_SORT_COLUMN` | 400 | sort property not in whitelist |
| `ENTRY_TYPE_REQUIRES_SINGLE_KIND` | 400 | `entryType` set without exactly one `kinds` value |
| `REF_ID_REQUIRES_REF_TYPE` | 400 | `refId` set without `refType` |
| `INVALID_KIND` | 400 | unparseable `kinds` value |
| `INVALID_DATE_RANGE` | 400 | `dateFrom > dateTo` |
| `USER_NOT_FOUND` | 404 | `userPublicId` doesn't resolve |

**User typeahead support endpoint:** `GET /api/v1/admin/users/typeahead?q=<text>&limit=10`. The implementation plan's first task verifies whether a similar admin-typeahead endpoint already exists; if so, reuse it instead of adding a new one. Returns `[{ publicId, username, displayName }]`. Matches `LOWER(username) LIKE '%q%' OR (q is uuid AND publicId = q) OR (q is uuid AND slAvatarUuid = q)`.

## 6. Frontend — `/admin/ledger` page

### 6.1 URL state

Source of truth = URL search params, mirrored via `useSearchParams` + `router.replace`. Bookmarkable.

```
/admin/ledger?kinds=USER_LEDGER&kinds=ESCROW_TXN&userPublicId=<uuid>
             &dateFrom=2026-05-01T00:00&dateTo=2026-05-08T00:00
             &amountMin=10000&refType=ESCROW&refId=42
             &search=foo&page=0&size=50&sort=createdAt,desc
```

### 6.2 Filter bar layout

```
[Search…] [Kinds ▾] [User ▾] [Entry type ▾] [Date from] [Date to] [Amt min] [Amt max] [RefType ▾] [RefId] [Reset]
```

- **Search** — debounced 300ms, fires on Enter or pause.
- **Kinds** — multi-select dropdown (5 options); shows count or "All".
- **User typeahead** — `<UserTypeahead>` component. Empty input shows nothing; typing 2+ chars triggers a debounced 200ms call to `/api/v1/admin/users/typeahead`. Dropdown lists up to 10 matches with `displayName / username / first 8 chars of UUID`. Selecting one stamps `userPublicId` and shows a chip ("Alice (12345…) ×") that clears on the ×.
- **Entry type** — disabled (greyed with tooltip) unless exactly one kind is selected. Renders that kind's allowed values.
- **Date from / to** — `<input type="datetime-local">`. Local-tz input → ISO string on submit. `dateFrom > dateTo` shows inline red error and disables submit.
- **Amt min / max** — numeric inputs.
- **RefType** — dropdown of canonical values (`ESCROW`, `AUCTION`, `WITHDRAWAL`, `BID`, `LISTING_FEE_REFUND`, `TERMINAL_COMMAND`, `PENALTY`, `ADJUSTMENT`, `DORMANCY`).
- **RefId** — numeric, enabled only when `RefType` is set.
- **Reset** — visible only when any filter is non-default.

Above the bar: `Showing 1–50 of 14,382 events`.
Below the table: pagination + page-size text input bottom-right.

### 6.3 Table — 10 columns

| Time | Kind | User | Amount | Entry | Status | Counterparty | RefType / RefId | Description | → |
|---|---|---|---|---|---|---|---|---|---|

- **Time** — short format (`MMM D, h:mm a`); full ISO in `title=`.
- **Kind** — `<AdminLedgerKindBadge>` colored chip (USER_LEDGER blue, ESCROW_TXN purple, TERMINAL_CMD amber, WITHDRAWAL slate, BID_RESERVATION green).
- **User** — link to `/admin/users/{userPublicId}`. `—` when null.
- **Amount** — right-aligned monospace, signed `L$ 1,234` / `-L$ 5,000`. Negative `text-danger`, positive `text-fg`.
- **Entry** — small uppercase label of the kind-native subtype.
- **Status** — `<AdminLedgerStatusBadge>`; `—` for USER_LEDGER. Mapping: COMPLETED/SETTLED green, IN_FLIGHT/QUEUED amber, FAILED/REVERSED red, RESERVED/ACTIVE blue, RELEASED neutral.
- **Counterparty** — link to admin user when populated, else `—`.
- **RefType / RefId** — `ESCROW · 42`. RefId is itself a link to that ref's drill destination so admins can click directly without going through the row's `→` link.
- **Description** — truncates to one line; full text in `title=`.
- **→** — kind-aware drill link (per `adminLedgerLinks.ts`):

| Kind | Link target |
|---|---|
| USER_LEDGER | `/admin/users/{userPublicId}/wallet?ledgerEntryId={nativeId}` |
| ESCROW_TXN | `/auction/{auctionPublicId}/escrow` |
| TERMINAL_CMD (purpose=WALLET_WITHDRAWAL) | `/admin/users/{userPublicId}/wallet?terminalCommandId={nativeId}` |
| TERMINAL_CMD (other) | `/admin/infrastructure?tab=terminals&commandId={nativeId}` |
| WITHDRAWAL | `/admin/infrastructure?tab=withdrawals&withdrawalId={nativeId}` |
| BID_RESERVATION | `/auction/{auctionPublicId}` |

### 6.4 Detail-page enhancements (in scope)

The drill-down links to `/admin/users/{publicId}/wallet?ledgerEntryId=N`, `?terminalCommandId=N`, `/admin/infrastructure?tab=...&{xId}=N` need a small UX assist: when those landing pages receive the row-id query param, scroll to the matching row + briefly highlight it with `bg-bg-hover` for 1.5s.

A new utility hook `useScrollToRowFromQueryParam(queryKey, getRowEl, deps)` lives at `frontend/src/hooks/useScrollToRowFromQueryParam.ts` and is wired into:
- `WalletTab.tsx` (ledger row + pending-withdrawal row).
- The infrastructure-page tabs that show terminal commands and admin withdrawals (whatever the existing component names are; the implementation plan's first task identifies them).

The existing escrow page (`/auction/{publicId}/escrow`) and auction page (`/auction/{publicId}`) are NOT enhanced — they're useful as broader-context landings without row highlighting. Future polish.

### 6.5 Loading / empty / error

- Loading: 5×10-cell skeleton rows.
- Empty: `No ledger events match these filters. [Reset filters]`.
- Error: `Could not load ledger. Refresh to retry.`

## 7. Out of scope (deferred)

1. **CSV export.** Streaming export structure precedent exists (`LedgerCollapsedRepositoryImpl.streamCollapsedForUser`); add when a real reporting need shows up.
2. **Materialized `ledger_events` table populated by triggers / write-side hooks.** Defer until UNION ALL slows down. Today: <50k rows total across all 5 sources.
3. **Reconciliation drift detection.** Sum-mismatch flagging across user_ledger / escrow_transactions / terminal_commands. The natural next feature once the global view exists.
4. **`bid_reservations.created_at` index.** Premature on a small table; revisit if the page slow-queries here.
5. **Saved filter views.** URL bookmarks suffice.
6. **Per-row admin notes / annotations.** Separate feature.
7. **Detail-page scroll-to-and-highlight for `/auction/{publicId}/escrow` and `/auction/{publicId}`.** Future polish; broader-context landings are useful without row highlighting.
8. **Live updates (WebSocket).** Refresh on filter change or manual reload only.

## 8. Postman

Add to the `SLPA` Postman collection in the same task — new top-level folder `Admin / Ledger`:

- `GET admin/ledger` (with example query params for the user-X-last-week audit case).
- `GET admin/users/typeahead` (the typeahead endpoint backing the filter; if a pre-existing equivalent is reused, register that one's collection slot under this folder).

Variable chaining: typeahead request stamps `userPublicId` for the ledger query.

## 9. README

Update root `README.md` to mention the new `/admin/ledger` page once shipped, and the "Admin / Ledger" Postman folder.
