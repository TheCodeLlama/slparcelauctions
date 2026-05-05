# Admin Listings Table — Implementation Plan

**Spec:** `docs/superpowers/specs/2026-05-05-admin-listings-table-design.md`
**Mode:** Autonomous (single-engineer, full deploy authority granted by user)

**Goal:** Ship the admin listings table at `/admin/listings`, the parallel drafts page at `/admin/drafts`, the four moderation action endpoints, and the Saves column wiring (incl. saved_auctions index).

**Architecture:** New backend package `admin/listings/` with controller + service + DTOs + exception handler. New frontend page + reusable `<AdminListingsTable>` component shared by both pages. Reuses existing `SuspensionService.suspendByAdmin`, `CancellationService.cancelByAdmin`, `AdminAuctionService.reinstate` for state flips and bidder-side notifications.

**Tech stack:** Spring Boot 4 / Java 26 / Hibernate / Flyway / Postgres / Spring Data JPA — Next.js 16 / React 19 / TanStack Query / Tailwind 4.

---

## Task ordering (execute top-to-bottom)

### Backend foundation

- [ ] **T1.** Flyway V16 migration `ix_saved_auctions_auction_id` index on `saved_auctions(auction_id)`. Commit.
- [ ] **T2.** Exception scaffolding under `admin/listings/exception/`:
  - `AdminListingStateException` (extends RuntimeException; `code`, `suggestedStatus()`)
  - `AdminListingExceptionHandler` `@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin.listings")` mapping the exception to ProblemDetail with `code` property.
  - Codes: `INVALID_STATUS_FOR_ACTION` (409), `ALREADY_SUSPENDED` (409), `NOT_SUSPENDED` (409), `LISTING_NOT_FOUND` (404), `INVALID_SORT_COLUMN` (400). Commit.
- [ ] **T3.** DTOs under `admin/listings/dto/`:
  - `AdminListingRowDto` (record, all 14 fields per spec §3.2)
  - `AdminListingActionRequest` (record `{ @NotBlank @Size(min=5,max=1000) String notes }`)
  - `AdminListingFilterParams` (record holding parsed query-param state for the controller). Commit.
- [ ] **T4.** Repository: add a native `@Query` to `AuctionRepository` named `searchAdmin(...)` matching spec §3.3. Returns `Page<Object[]>`. Commit.
- [ ] **T5.** Service `AdminListingService`:
  - `@Transactional(readOnly = true) Page<AdminListingRowDto> list(AdminListingFilterParams params, Pageable pageable)` — calls repo, validates sort whitelist (throws `AdminListingStateException("INVALID_SORT_COLUMN", ...)` on any non-whitelisted property), maps `Object[]` → DTO.
  - `@Transactional public void warn(String publicId, Long adminUserId, String notes)` — resolve auction, publish `notificationPublisher.listingWarned(...)`, record `WARN_SELLER_FROM_REPORT` audit row with `targetType=LISTING`, `metadata={"source":"ADMIN_LISTINGS_TABLE"}`.
  - `@Transactional public void suspend(String publicId, Long adminUserId, String notes)` — resolve auction, call `SuspensionService.suspendByAdmin(auction, adminUserId, notes)`, record `SUSPEND_LISTING_FROM_REPORT` audit row with same metadata; translate `IllegalStateException`/`AuctionNotFoundException` to `AdminListingStateException`.
  - `@Transactional public void cancel(String publicId, Long adminUserId, String notes)` — resolve, call `CancellationService.cancelByAdmin(auctionId, adminUserId, notes)`, record `CANCEL_LISTING_FROM_REPORT` audit, same translation.
  - `@Transactional public void reinstate(String publicId, Long adminUserId, String notes)` — resolve, call `AdminAuctionService.reinstate(auctionId, Optional.empty())`; the shared service already publishes the seller notification — DO NOT double-publish. Record `REINSTATE_LISTING` audit. Translate `AuctionNotSuspendedException` → `AdminListingStateException("NOT_SUSPENDED", ...)`. Commit.
- [ ] **T6.** Controller `AdminListingController` at `/api/v1/admin/listings`:
  - `@PreAuthorize("hasRole('ADMIN')")` on the class
  - `GET ""` returns `PagedResponse<AdminListingRowDto>` (use existing PagedResponse wrapper)
  - `POST "/{publicId}/warn"` returns 204
  - `POST "/{publicId}/suspend"` returns 204
  - `POST "/{publicId}/cancel"` returns 204
  - `POST "/{publicId}/reinstate"` returns 204
  - Pull admin user id from `@AuthenticationPrincipal` (existing `AuthPrincipal` carries `userId: Long`).
  - Commit.
- [ ] **T7.** Backend smoke: `./mvnw test -pl . -Dtest='*Listing*'` — fix any compile/unit-test issues. Commit if any fixups needed.

### Frontend types + API

- [ ] **T8.** `frontend/src/lib/admin/types.ts` — add `AdminListingRow`, `AdminListingsFilters`, `AdminListingActionRequest`, plus enums `AuctionStatus` and `VerificationStatus` (mirror backend literal-string union types). Commit.
- [ ] **T9.** `frontend/src/lib/admin/api.ts` — add `listings: { list, warn, suspend, cancel, reinstate }` namespace. Commit.
- [ ] **T10.** `frontend/src/lib/admin/queryKeys.ts` — `listings()`, `listingsList(filters)`. Commit.
- [ ] **T11.** `frontend/src/hooks/admin/useAdminListings.ts` — `useAdminListingsList(filters)` query hook + 4 mutation hooks (`useWarnListing`, `useSuspendListing`, `useCancelListing`, `useReinstateListing`). Each mutation invalidates `adminQueryKeys.listings()`. Add `adminListingErrorMessage(err, fallback)` helper mapping API error codes to user copy. Commit.

### Frontend components

- [ ] **T12.** `frontend/src/components/admin/listings/AdminListingsTable.tsx` — the 13-col table per spec §5.5. Props include `lockedStatuses?: AuctionStatus[]` and `presets: PresetSpec[]`. Commit.
- [ ] **T13.** `frontend/src/components/admin/listings/ListingsFilterBar.tsx` — search input (debounced), status multi-select, verification multi-select, reserve 3-state toggle, reset button. Hides Status when `lockedStatuses` is set. Commit.
- [ ] **T14.** `frontend/src/components/admin/listings/PresetChips.tsx` — chip row, props-driven. Commit.
- [ ] **T15.** Action modals — one shared `<ListingActionModal>` parameterized by action type (warn/suspend/cancel/reinstate). Files:
  - `frontend/src/components/admin/listings/ListingActionModal.tsx` (the shared modal with action-driven copy/variant)
  - Commit.
- [ ] **T16.** `frontend/src/components/admin/listings/RowActionMenu.tsx` — kebab dropdown with the 4 actions, status-based disable + tooltip. Commit.

### Frontend pages

- [ ] **T17.** `frontend/src/app/admin/listings/page.tsx` — full page composition with default filter (live statuses), the 4 presets per spec §5.4. URL-synced state via `useSearchParams`/`router.replace`. Commit.
- [ ] **T18.** `frontend/src/app/admin/drafts/page.tsx` — same composition with `lockedStatuses=[DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED]`, 1 preset (`Failed verification`). Commit.
- [ ] **T19.** `frontend/src/components/admin/AdminShell.tsx` — add Listings + Drafts entries between Bans and Users. Commit.

### Postman + README + verify

- [ ] **T20.** Postman collection: add 5 new requests under "Admin/Listings" folder with variable chaining. Commit if any collection export is checked into the repo (otherwise manual MCP call).
- [ ] **T21.** Sweep root `README.md` for staleness — add admin listings/drafts pages to the admin section once shipped. Commit.
- [ ] **T22.** Run `cd frontend && npm run verify && npm test` and `cd backend && ./mvnw test` — fix any breakage, commit fixups.

### Deploy

- [ ] **T23.** Push, open PR `dev → dev` (i.e. branch the feature against `dev`, merge into `dev`). Verify GH Actions green.
- [ ] **T24.** Open PR `dev → main`, merge autonomously per user authorization.
- [ ] **T25.** Monitor backend deploy (`gh run list --workflow 'deploy backend' --limit 3`) until SUCCEED + ECS service stable + Flyway log shows V16 applied.
- [ ] **T26.** Monitor Amplify (`aws --profile slpa-prod amplify list-jobs --app-id dil6fhehya5jf --branch-name main --max-items 3`) until SUCCEED.
- [ ] **T27.** Confirm to user with: PR URLs, deploy run IDs, what's live, and the URLs (`/admin/listings`, `/admin/drafts`).

---

## Key correctness anchors (referenced from spec self-review)

- Audit `targetType` = `AdminActionTargetType.LISTING` (not AUCTION).
- `AdminAuctionService.reinstate` already publishes seller notification — controller path must NOT double-publish.
- Column names: `starting_bid`, `reserve_price`, `current_bid`, `bid_count`, `ends_at`, `created_at`, `region_name` (on `auction_parcel_snapshots`). NOT `start_price_lindens` etc.
- Sort whitelist: `title, seller, createdAt, startPrice, currentBid, bidCount, saveCount, endsAt, region`. Reject anything else with `INVALID_SORT_COLUMN`.
- Default frontend `status` filter excludes DRAFT/DRAFT_PAID/VERIFICATION_PENDING/VERIFICATION_FAILED. Backend treats absent `status` as "all" — frontend is responsible for sending defaults.
- Page size: admin-only, no clamp.
