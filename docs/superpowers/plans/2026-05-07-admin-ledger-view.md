# Admin Global Ledger View — Implementation Plan

**Spec:** `docs/superpowers/specs/2026-05-07-admin-ledger-view-design.md`
**Mode:** Autonomous (single-engineer, full deploy authority granted by user)

**Goal:** Ship `/admin/ledger` — a unified, searchable, filterable cross-user money-event audit page reading from `user_ledger` + `escrow_transactions` + `terminal_commands` + `withdrawals` + `bid_reservations` via native UNION ALL.

**Architecture:** New backend package `admin/ledger/` with controller + service + native-SQL query repository + DTOs + exception handler. New frontend page reuses the listings-table interaction pattern. Native query unions up to 5 source tables and projects to a single `AdminLedgerRowDto` shape; the controller compiles only selected `kinds` arms into the SQL at request time.

**Tech stack:** Spring Boot 4 / Java 26 / native SQL (no Hibernate entities) / Flyway V17 — Next.js 16 / React 19 / TanStack Query / Tailwind 4.

---

## Task ordering (execute top-to-bottom)

### Foundation

- [ ] **T1.** Verify whether an admin user-typeahead endpoint already exists. Search for `typeahead`, `searchAdmin`, and any `GET /api/v1/admin/users` query that supports text. Document what's found:
  - If `searchAdmin` (the full users-list endpoint) supports a text query that returns small results, REUSE it as the typeahead source — frontend hits `?search=q&size=10`.
  - If not, add a dedicated `GET /api/v1/admin/users/typeahead?q=&limit=10` controller method on the existing `AdminUserController`.
  - This task informs frontend T11. Commit if any backend code is added.
- [ ] **T2.** Flyway V17 migration `V17__ledger_view_indexes.sql`:
  ```sql
  CREATE INDEX IF NOT EXISTS ix_escrow_transactions_created_at ON escrow_transactions (created_at DESC);
  CREATE INDEX IF NOT EXISTS ix_terminal_commands_created_at   ON terminal_commands (created_at DESC);
  CREATE INDEX IF NOT EXISTS ix_withdrawals_created_at         ON withdrawals (created_at DESC);
  ```
  Commit.

### Backend foundation

- [ ] **T3.** Exception scaffolding under `admin/ledger/exception/`:
  - `AdminLedgerStateException` (extends RuntimeException; `code`, `suggestedStatus()` for INVALID_SORT_COLUMN/ENTRY_TYPE_REQUIRES_SINGLE_KIND/REF_ID_REQUIRES_REF_TYPE/INVALID_KIND/INVALID_DATE_RANGE all → 400; USER_NOT_FOUND → 404).
  - `AdminLedgerExceptionHandler` `@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin.ledger")` mapping the exception to ProblemDetail with `code` property.
  - Commit.
- [ ] **T4.** DTOs under `admin/ledger/dto/`:
  - `AdminLedgerKind` enum: `USER_LEDGER`, `ESCROW_TXN`, `TERMINAL_CMD`, `WITHDRAWAL`, `BID_RESERVATION`.
  - `AdminLedgerRowDto` (record, all 14 fields per spec §3): `kind`, `eventId`, `nativeId`, `createdAt`, `userPublicId`, `username`, `counterpartyPublicId`, `counterpartyUsername`, `amountLindens`, `entryType`, `status`, `refType`, `refId`, `description`.
  - `AdminLedgerFilterParams` (record holding parsed controller state — `kinds`, `userPublicId`, `userInternalId` resolved, `entryType`, `refType`, `refId`, `dateFrom`, `dateTo`, `amountMin`, `amountMax`, `search`).
  - Commit.
- [ ] **T5.** Native-SQL query repository `AdminLedgerQueryRepository`:
  - Build the 5 sub-selects per spec §4.1, each projecting the column list (kind, native_id, created_at, user_public_id, username, counterparty_public_id, counterparty_username, amount_lindens, entry_type, status, ref_type, ref_id, description, searchable_text, user_id, counterparty_user_id).
  - User-resolution joins: USER_LEDGER joins `users` on `user_id`. ESCROW_TXN does `LEFT JOIN users payer_u ON payer_u.sl_avatar_uuid = et.payer` and `LEFT JOIN users payee_u ON payee_u.sl_avatar_uuid = et.payee`, then a `CASE` on `et.type` decides which side becomes user vs counterparty. TERMINAL_CMD `LEFT JOIN users ON users.sl_avatar_uuid = tc.recipient_uuid`. WITHDRAWAL `LEFT JOIN users recip ON recip.sl_avatar_uuid = w.recipient_uuid` + `LEFT JOIN users admin_u ON admin_u.id = w.admin_user_id`. BID_RESERVATION joins `users` on `user_id` and joins `auctions` on `auction_id` (for the description's auction id) — and UNION-ALLs internally to materialize RESERVED + RELEASED events.
  - For the ESCROW_TXN sign convention, use `CASE WHEN type IN ('AUCTION_ESCROW_PAYMENT', 'LISTING_FEE_PAYMENT', 'LISTING_PENALTY_PAYMENT') THEN -et.amount WHEN type IN ('AUCTION_ESCROW_PAYOUT', 'AUCTION_ESCROW_REFUND', 'LISTING_FEE_REFUND') THEN et.amount ELSE et.amount END AS amount_lindens` (commission is positive, but the user_id resolves to null typically).
  - `BID_RESERVATION` RESERVED = -br.amount, RELEASED = +br.amount.
  - `TERMINAL_CMD` and `WITHDRAWAL` arms use `-tc.amount`/`-w.amount` (always negative from user's POV).
  - The `description` column is built per arm via SQL string concatenation, e.g. `('AUCTION_ESCROW_PAYMENT escrow #' || et.escrow_id || ' (auction #' || es.auction_id || ')')`.
  - Outer query applies all WHERE filters per spec §4.2 against the projected aliases.
  - `kinds` filter is applied at SQL-build time: `AdminLedgerQueryRepository.search(...)` accepts the `Set<AdminLedgerKind>` and only concatenates the selected arms into the UNION ALL.
  - Sort: `ORDER BY <whitelisted-col> <dir>, kind ASC, native_id ASC`. Whitelist `createdAt → created_at`, `amountLindens → amount_lindens`. Anything else throws `AdminLedgerStateException("INVALID_SORT_COLUMN", ...)`.
  - Two queries: page + count.
  - Commit.
- [ ] **T6.** Service `AdminLedgerService`:
  - `@Transactional(readOnly = true) Page<AdminLedgerRowDto> list(AdminLedgerFilterParams params, Pageable pageable)`.
  - Validate `params`: empty `kinds` → treat as all-5; `entryType` set with `kinds.size() != 1` → throw `ENTRY_TYPE_REQUIRES_SINGLE_KIND`; `refId` set with `refType` null → `REF_ID_REQUIRES_REF_TYPE`; `dateFrom > dateTo` → `INVALID_DATE_RANGE`.
  - Resolve `userPublicId` to internal `userInternalId` via `UserRepository.findByPublicId().map(User::getId).orElseThrow(USER_NOT_FOUND)` if `userPublicId` is set.
  - Delegate to `AdminLedgerQueryRepository.search(...)`.
  - Commit.
- [ ] **T7.** Controller `AdminLedgerController` at `/api/v1/admin/ledger`:
  - `@PreAuthorize("hasRole('ADMIN')")` on the class.
  - `GET ""` returns `PagedResponse<AdminLedgerRowDto>`. Parses `kinds`, `userPublicId`, `entryType`, `refType`, `refId`, `dateFrom`, `dateTo`, `amountMin`, `amountMax`, `search`, `page`, `size`, `sort` per spec §5.
  - Sort parsing reuses the `parseSort` helper pattern from `AdminListingController` (whitelist enforced in the query repo, not the controller).
  - Commit.
- [ ] **T8.** Backend smoke: `cd backend && ./mvnw test`. Fix any compile/test issues. Commit fixups.

### Frontend foundation

- [ ] **T9.** Frontend types + api + queryKeys + hooks:
  - `frontend/src/lib/admin/types.ts` — add `AdminLedgerKind` (literal-string union mirroring backend enum), `AdminLedgerRow`, `AdminLedgerFilters`, `AdminLedgerSort`. The `AdminLedgerFilters` record has all the filter fields per spec §5 plus `page`, `size`, `sort`.
  - `frontend/src/lib/admin/api.ts` — add `ledger.list(filters)` namespace method that builds the URLSearchParams (repeat `kinds=` for each).
  - `frontend/src/lib/admin/queryKeys.ts` — `ledger()` and `ledgerList(filters)`.
  - `frontend/src/hooks/admin/useAdminLedger.ts` — `useAdminLedgerList(filters)` query hook.
  - Also add a `useAdminUserTypeahead(q: string)` hook (whatever endpoint T1 settled on).
  - Commit.

### Frontend components

- [ ] **T10.** Badges + drill-link resolver:
  - `frontend/src/components/admin/ledger/AdminLedgerKindBadge.tsx` — colored chip per kind (USER_LEDGER blue/info-bg, ESCROW_TXN purple, TERMINAL_CMD amber/warning-bg, WITHDRAWAL slate/bg-hover, BID_RESERVATION green/success-bg).
  - `frontend/src/components/admin/ledger/AdminLedgerStatusBadge.tsx` — chip with the COMPLETED/IN_FLIGHT/FAILED/RESERVED/RELEASED color mapping per spec §6.3. Renders `null` for null status.
  - `frontend/src/components/admin/ledger/adminLedgerLinks.ts` — `function rowDrillLink(row: AdminLedgerRow): string` per spec §6.3 mapping. Each kind branches; for `TERMINAL_CMD` it inspects `row.entryType` to detect `WALLET_WITHDRAWAL` vs other purposes.
  - Commit.
- [ ] **T11.** Filter bar + user typeahead:
  - `frontend/src/components/admin/ledger/UserTypeahead.tsx` — controlled input with 200ms-debounced query against `useAdminUserTypeahead`, dropdown of up to 10 results, `onSelect(publicId, label)`. Renders a chip when a user is selected.
  - `frontend/src/components/admin/ledger/AdminLedgerFilterBar.tsx` — the wide filter row per spec §6.2. Search (debounced 300ms), Kinds multi-select dropdown, UserTypeahead, EntryType dropdown (disabled unless 1 kind selected), DateFrom + DateTo `<input type="datetime-local">` with inline error on invalid range, AmtMin + AmtMax numeric inputs, RefType dropdown, RefId numeric input (disabled unless RefType set), Reset button (visible only when dirty).
  - Commit.
- [ ] **T12.** Table:
  - `frontend/src/components/admin/ledger/AdminLedgerTable.tsx` — 10 columns per spec §6.3. Sortable headers on Time + Amount only. RefId column renders as a link to the drill destination of that ref (uses `rowDrillLink` indirectly). Status column uses the badge. Loading/empty states handled here.
  - Commit.

### Frontend pages

- [ ] **T13.** Page composition:
  - `frontend/src/app/admin/ledger/page.tsx` — Suspense + `<AdminLedgerPage>` (similar shape to `/admin/listings/page.tsx`).
  - `frontend/src/components/admin/ledger/AdminLedgerPage.tsx` — URL-state via `useSearchParams`/`router.replace`, results-summary line, table render, pagination, page-size text input bottom-right (admin-only — no clamp, matches listings convention).
  - Commit.
- [ ] **T14.** Sidebar entry:
  - `frontend/src/components/admin/AdminShell.tsx` — add `{ label: "Ledger", href: "/admin/ledger" }` between Bans and Listings.
  - Commit.

### Drill-down detail-page enhancements

- [ ] **T15.** Row scroll-to-and-highlight utility + applications:
  - `frontend/src/hooks/useScrollToRowFromQueryParam.ts` — generic hook: takes a `queryParam` name + a `getRowEl(id) => HTMLElement | null` lookup. On mount, reads the query param, calls `getRowEl`, applies `scrollIntoView({ block: "center" })` and a temporary `bg-bg-hover` class for 1.5s.
  - Apply in `frontend/src/components/admin/users/wallet/WalletTab.tsx`: wire `?ledgerEntryId=N` (highlights the ledger row) and `?terminalCommandId=N` (highlights the pending-withdrawal row). Each row already has `data-testid="ledger-row-${entryId}"` / `data-testid="pending-withdrawal-${terminalCommandId}"`, so `getRowEl` does `document.querySelector('[data-testid="..."]')`.
  - Apply in the infrastructure-page tabs that render terminal commands and admin withdrawals. Identify the components first (search for the existing data-testid patterns or row rendering); if no row-id-able rendering exists, add `data-testid` to the row in the same change. Wire `?withdrawalId=N` and `?commandId=N`.
  - Commit.

### Manual surfaces + verify + deploy

- [ ] **T16.** Postman + README + verify:
  - Postman: create new top-level folder `Admin / Ledger` via MCP; add `GET admin/ledger` with example query params (kinds + dateFrom + dateTo). If T1 produced a new typeahead endpoint, also add `GET admin/users/typeahead`.
  - `README.md`: add `/admin/ledger` to the admin frontend routes list. Add `Admin / Ledger` to the Postman folder list.
  - Run `cd frontend && npm run verify && npm test -- --run` — fix any breakage.
  - Run `cd backend && ./mvnw test` — fix any breakage.
  - Commit fixups.
- [ ] **T17.** PRs + deploy:
  - Push the feature branch, open PR `feat/admin-ledger-view → dev`. Wait for CI green. Squash-merge.
  - Open PR `dev → main`. Squash-merge autonomously per user authorization.
  - Watch backend deploy (`gh run view ...`) until SUCCESS + ECS rolled + Flyway log shows V17 applied.
  - Watch Amplify (`aws amplify get-job ... --app-id dil6fhehya5jf --branch-name main`) until SUCCEED.
  - Confirm to user with PR URLs, deploy run IDs, what's live, and the URL `/admin/ledger`.

---

## Key correctness anchors

- **Sign convention**: all `amount_lindens` projections are signed from the resolved primary user's wallet POV. Money leaving them = negative.
- **Kind compile-out**: when fewer than 5 kinds are selected, only those arms get concatenated into the UNION ALL. Don't unconditionally include all 5 arms behind a `WHERE kind IN (:kinds)` filter — that would force 5x the query work for a single-kind admin filter.
- **User filter**: matches BOTH `user_id` and `counterparty_user_id` projections. The frontend sends the `userPublicId` UUID, the service resolves to internal Long once via `UserRepository.findByPublicId`.
- **BID_RESERVATION 2-row materialization**: each reservation row produces RESERVED at `created_at` (always) and RELEASED at `released_at` (only when `released_at IS NOT NULL`). Achieved via a UNION ALL inside the BID_RESERVATION arm.
- **No double-publish from existing wallet ledger**: this view is read-only over existing tables. No new write paths. No notifications fire.
- **ESCROW commission rows**: `payer` and `payee` may both be platform avatars (no user resolution). Renders with empty User column. That's correct — admins see commission flowed but with no specific user attached.
- **Status mapping**: USER_LEDGER never has a status (always settled at write time — append-only ledger). Renders as `—` in the table.
