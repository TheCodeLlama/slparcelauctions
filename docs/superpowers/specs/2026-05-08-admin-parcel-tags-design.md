# Admin parcel-tag management — design

**Date:** 2026-05-08
**Status:** approved (brainstorm complete)

## Goal

Admins can add, edit, and disable/re-enable land tags (a.k.a. parcel tags) from the admin panel. Categories are free-text values on each tag (no separate Category entity); a new category appears in browse the moment any tag references it. KISS-first scope: no hard delete, no rename of `code`, no bulk import.

## Context

`ParcelTag` already exists with full schema support for what's needed:

| Column | Constraints |
|---|---|
| `code` | unique, ≤50 chars, immutable from admin's perspective (referenced by every auction in the DB) |
| `label` | ≤100 chars, displayed to buyers |
| `category` | ≤50 chars, free-text, groups tags on the browse filter |
| `description` | nullable text, optional admin note |
| `sortOrder` | integer, drives ordering within a category |
| `active` | boolean, hides/shows in the public catalogue |

Today the only public surface is `GET /api/v1/parcel-tags` (anonymous) returning **active** tags grouped by category, sorted by `sortOrder`. 25 canonical tags are seeded on first boot via `ParcelTagService.seedDefaultTagsIfEmpty()`. There's no admin endpoint — admins can't currently edit, disable, or add tags.

The codebase has 14 admin controllers under `/api/v1/admin/...`. The natural pattern is one controller per domain (`AdminFraudFlagController`, `AdminUserController`, etc.), and `SecurityConfig` already gates `/api/v1/admin/**` to `ROLE_ADMIN`.

## Architecture

**Approach 1 from brainstorm.** New `AdminParcelTagController` + `AdminParcelTagService` live alongside the existing public read controller in the `parceltag` package. The shared `ParcelTag` entity, `ParcelTagRepository`, and DTOs are reused; the public read endpoint is unchanged.

Two services in the same package have orthogonal concerns: `ParcelTagService` is the read + seed surface; `AdminParcelTagService` is the write + admin-list surface. The package stays small (~10 files) and the boundary is clear by name.

## File structure

### Backend — new files

```
backend/src/main/java/com/slparcelauctions/backend/parceltag/
  AdminParcelTagController.java
  AdminParcelTagService.java
  dto/
    AdminParcelTagDto.java                     # all fields incl. active, for admin list/responses
    CreateParcelTagRequest.java                # { code, label, category, description?, sortOrder? }
    UpdateParcelTagRequest.java                # { label?, category?, description?, sortOrder? }
  exception/
    ParcelTagCodeConflictException.java        # 409 — POST with existing code
    ParcelTagNotFoundException.java            # 404 — PATCH/toggle on missing code
    ParcelTagExceptionHandler.java             # @RestControllerAdvice scoped to package
```

### Backend — modified files

- `ParcelTagRepository.java` — add `Optional<ParcelTag> findByCode(String)`, `boolean existsByCode(String)`, `List<ParcelTag> findAllByOrderByCategoryAscSortOrderAsc()` (returns inactive too — admin view).
- (Optional) `SecurityConfig.java` — only if the `/api/v1/admin/**` matcher doesn't already cover the new path; in practice it does.

### Backend — tests

- `AdminParcelTagServiceTest.java` — Mockito unit cases on each operation.
- `AdminParcelTagControllerIntegrationTest.java` — full slice (`@SpringBootTest`, `@AutoConfigureMockMvc`) covering 200/400/404/409/403/401 paths.

### Frontend — new files

```
frontend/src/app/admin/parcel-tags/
  page.tsx                                     # client page wrapper

frontend/src/components/admin/parcel-tags/
  AdminParcelTagsPage.tsx                      # composes header + table + add modal
  AdminParcelTagsTable.tsx                     # grouped-by-category rows w/ Edit + Disable/Enable actions
  AddParcelTagModal.tsx                        # create form
  EditParcelTagModal.tsx                       # edit label/category/description/sortOrder

frontend/src/hooks/admin/
  useAdminParcelTags.ts                        # list query + create/update/toggle mutations

frontend/src/lib/admin/
  parcelTags.ts                                # API client wrappers
```

### Frontend — modified files

- `frontend/src/components/admin/AdminShell.tsx` — add "Parcel tags" sidebar entry between Listings and Ledger.
- `frontend/src/lib/admin/queryKeys.ts` — add `parcelTags()` and `parcelTagsList()`.
- `frontend/src/lib/admin/types.ts` — add `AdminParcelTagDto`, `CreateParcelTagPayload`, `UpdateParcelTagPayload`.

## Backend endpoints

All under `/api/v1/admin/parcel-tags`, gated by `ROLE_ADMIN` via the existing `SecurityConfig` matcher. Each write action records an `AdminActionLog` entry.

### `GET /api/v1/admin/parcel-tags`

Lists all tags (active + inactive) ordered by `category ASC, sortOrder ASC`. Returns `AdminParcelTagDto[]` (flat list — the frontend groups by category at render time so the admin can sort/filter freely).

```json
[
  { "code": "WATERFRONT", "label": "Waterfront", "category": "Terrain / Environment",
    "description": "Ocean, lake, or river border", "sortOrder": 1, "active": true,
    "createdAt": "...", "updatedAt": "..." },
  ...
]
```

### `POST /api/v1/admin/parcel-tags`

Body: `CreateParcelTagRequest`.

```json
{ "code": "BEACHFRONT", "label": "Beachfront", "category": "Terrain / Environment",
  "description": "On the immediate beach", "sortOrder": 9 }
```

- `code` — required, 1-50 chars, uppercase letters/digits/underscore only (regex `^[A-Z0-9_]+$`), unique.
- `label` — required, 1-100 chars.
- `category` — required, 1-50 chars.
- `description` — optional, ≤2000 chars.
- `sortOrder` — optional, defaults to `max(sortOrder in same category) + 1` server-side.

Returns 201 `AdminParcelTagDto`. Duplicate `code` → 409 with `code=PARCEL_TAG_CODE_CONFLICT`. Validation errors → 400 with the standard `ProblemDetail.errors` map.

### `PATCH /api/v1/admin/parcel-tags/{code}`

Body: `UpdateParcelTagRequest`. All fields optional; only the supplied fields are written.

```json
{ "label": "Waterfront (premium)", "sortOrder": 0 }
```

- `code` cannot be changed (path is the lookup key, body has no `code` field).
- Same validation rules as create for the fields that are present.

Returns 200 `AdminParcelTagDto`. Missing tag → 404 with `code=PARCEL_TAG_NOT_FOUND`.

### `POST /api/v1/admin/parcel-tags/{code}/toggle-active`

Empty body. Flips `active`. Returns 200 `AdminParcelTagDto`. Missing tag → 404.

This is a single endpoint (not separate enable/disable) because the action's intent — "flip the visibility of this tag" — is clearer at the call site than "set active=false". Saves a body field and a branch.

## Frontend UI

### Route

`/admin/parcel-tags` — client component, server wrapper just renders it. Sidebar entry "Parcel tags" sits between Listings and Ledger.

### Page composition

```
┌──────────────────────────────────────────────────────────┐
│ Parcel tags                              [+ Add tag]     │
├──────────────────────────────────────────────────────────┤
│ Terrain / Environment                                    │
│   WATERFRONT  Waterfront           Active   [Edit] [⋯]   │
│   GRASS       Grass                Active   [Edit] [⋯]   │
│   SNOW        Snow (retired)       Inactive [Edit] [⋯]   │
├──────────────────────────────────────────────────────────┤
│ Roads / Access                                           │
│   STREETFRONT Streetfront          Active   [Edit] [⋯]   │
│   ...                                                    │
└──────────────────────────────────────────────────────────┘
```

- Each row shows code (mono font), label, category (in the header), active state, sortOrder.
- "Edit" opens `EditParcelTagModal` pre-filled with current values. Code is rendered as read-only at the top of the modal.
- "⋯" kebab on each row exposes "Disable" or "Re-enable" (depending on current state).
- Inactive rows are visually muted (`opacity-60`) but still listed.
- "+ Add tag" opens `AddParcelTagModal` with all fields blank. Category field is an autocomplete pulled from the existing distinct categories.

### Modals

Both modals follow the existing admin-modal pattern (`Dialog` from `@headlessui/react`, `Button` primary/secondary, `FormError` for errors):

- **Add tag** — code input (uppercase auto-coerce), label, category autocomplete, description (optional), sortOrder (optional, placeholder shows the auto-default).
- **Edit tag** — code rendered read-only, label / category / description / sortOrder editable.

Validation errors from the backend (400 `ProblemDetail.errors`) render inline next to the matching field; other errors render in the form-level `FormError`.

### Optimistic updates

On create/update/toggle: optimistic write to the React Query cache, then invalidate-on-settle. Standard pattern used by other admin pages.

## Audit logging

Each write action records an `AdminActionLog` entry via the existing `AdminAuditService`. New action types added to the enum if they don't already exist:

- `PARCEL_TAG_CREATED` — target = tag's `code`, metadata = full DTO snapshot.
- `PARCEL_TAG_UPDATED` — target = `code`, metadata = `{ before, after }` deltas (only changed fields).
- `PARCEL_TAG_TOGGLED_ACTIVE` — target = `code`, metadata = `{ from: bool, to: bool }`.

## Error handling & edge cases

- **Duplicate code at create** — 409 `PARCEL_TAG_CODE_CONFLICT`. Frontend shows "A tag with this code already exists" inline next to the code field.
- **Validation failures** — 400 `ProblemDetail.errors`. Each field's message renders inline.
- **Missing tag at update/toggle** — 404 `PARCEL_TAG_NOT_FOUND`. Frontend toasts and refetches the list.
- **Non-admin access** — 403 from existing `SecurityConfig`; admin pages already redirect non-admins, no extra handling needed.
- **Auction references a tag that gets disabled** — auction's existing `tags` collection still references the inactive `ParcelTag` row (ID-based join). Public browse hides it from the filter UI. Existing listings keep their tag chip rendering (shows the label until the seller next saves the listing). No data migration; this is the intended soft-disable semantics.
- **Disabling all tags in a category** — the category disappears from public browse. The category remains visible to admins (admin list shows inactive too) so a future re-enable surfaces it again.
- **Concurrent edits** — last-write-wins. The schema has a `version` column on `BaseMutableEntity` so a stale-update would throw `ObjectOptimisticLockingFailureException` → existing global handler turns into 409 → admin's modal shows "Tag was modified in another window — please refresh".

## Testing

### Backend

`AdminParcelTagServiceTest` (Mockito unit):
- `create_happyPath_persistsRow` — validates assigned defaults
- `create_duplicateCode_throwsConflict`
- `create_blankFields_validationErrors` (covered at controller level via bean-validation)
- `update_happyPath_writesPartial` — only supplied fields touched
- `update_missingCode_throwsNotFound`
- `toggleActive_flipsBooleanAndAudits`

`AdminParcelTagControllerIntegrationTest` (`@SpringBootTest` slice):
- `list_returns200WithAllTagsIncludingInactive`
- `create_returns201WithDto`
- `create_duplicateCode_returns409WithCode`
- `create_invalidCodeRegex_returns400`
- `create_blankLabel_returns400`
- `update_returns200WithUpdatedDto`
- `update_missingCode_returns404`
- `toggleActive_flipsActive_returns200`
- `nonAdmin_returns403`
- `anonymous_returns401`

### Frontend

- `useAdminParcelTags.test.tsx` — list query, create mutation, update mutation, toggle mutation. Optimistic + invalidation.
- `AddParcelTagModal.test.tsx` — opens, validates required fields, calls mutation on submit, closes on success, surfaces 409 inline.
- `EditParcelTagModal.test.tsx` — opens pre-filled, code is read-only, partial update submits only changed fields.
- `AdminParcelTagsTable.test.tsx` — groups by category, kebab toggles active, "Edit" opens modal, inactive rows visually muted (`data-active="false"` testid attribute).
- `AdminParcelTagsPage.test.tsx` — integration: page renders header + add button + table; clicking add opens modal; new tag appears after submit.

### Postman

Mirror all 4 endpoints into the SLPA collection in a new `Admin / Parcel Tags` folder, threading `{{adminAccessToken}}` and a fresh `{{tagCode}}` variable.

## Out of scope

- **Hard delete.** Soft-disable via `active=false` is the durable contract. A row that's been referenced by an auction can't be safely removed.
- **Rename `code`.** Auctions reference tags by `code` semantically (tag chips render the label, but the link is via the row). A rename would orphan every existing reference. If a code is wrong, admins create a new tag with the right code and disable the old one.
- **Bulk import / CSV upload.** YAGNI — 25 seed tags + occasional one-offs.
- **Drag-and-drop reordering.** `sortOrder` is editable as a number; that's enough.
- **Category management as a separate admin surface.** Categories are free-text on each tag — adding a new category is just typing a new value. Mitigation against typo divergence: the category field on Add/Edit modals autocompletes from existing distinct categories.

## Acceptance

- Admins can navigate to `/admin/parcel-tags`, see all tags grouped by category, and add a new tag that appears immediately on `GET /api/v1/parcel-tags` (the public catalogue).
- Admins can edit a tag's label / category / description / sortOrder. `code` is locked.
- Admins can disable a tag (hidden from public catalogue) and re-enable it (back in the catalogue).
- Non-admins get 403 / are redirected; anonymous gets 401.
- Every admin write produces an `AdminActionLog` row.
- All existing public-side tag tests still pass.
- New backend integration tests cover the 200/400/404/409/403/401 paths.
