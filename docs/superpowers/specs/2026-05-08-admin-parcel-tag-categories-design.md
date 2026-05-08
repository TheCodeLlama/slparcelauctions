# Admin parcel-tag categories — design

**Date:** 2026-05-08
**Status:** approved (brainstorm complete)

## Goal

Promote parcel-tag categories from free-text strings to a first-class entity. Admins get a dedicated `/admin/parcel-tag-categories` page mirroring the `/admin/parcel-tags` UX (add, edit, disable/re-enable). Each `ParcelTag` references a category by FK; the admin tag form's category field becomes a dropdown of active categories. Disabling a category cascades (via a `WHERE` filter, not a write) to hiding all its tags from the public catalogue.

## Why

Today categories exist as `String` columns on each `ParcelTag` row. Adding a tag invents a category by typing; the autocomplete mitigates typos but doesn't prevent them. There's no way to rename a category in one operation, no concept of disabling, and the seed data implicitly creates 6 categories that admins can't manage.

Promoting the category to its own row fixes all three pain points and matches the treatment we already gave tags.

## Architecture

Approach 1 from the brainstorm: keep all parcel-tag-domain code in the existing `parceltag/` package. New `ParcelTagCategory` entity sits next to `ParcelTag`; new admin controller + service mirror the tag patterns. No new package.

`ParcelTag.category` becomes a `@ManyToOne` relationship to `ParcelTagCategory`. The public read DTO (`ParcelTagResponse`) projects the category's `label` as a flat string so the wire shape stays unchanged for sellers and buyers.

## File structure

### Backend — new files

```
backend/src/main/java/com/slparcelauctions/backend/parceltag/
  ParcelTagCategory.java
  ParcelTagCategoryRepository.java
  AdminParcelTagCategoryController.java        # /api/v1/admin/parcel-tag-categories
  AdminParcelTagCategoryService.java

  dto/
    AdminParcelTagCategoryDto.java
    CreateParcelTagCategoryRequest.java
    UpdateParcelTagCategoryRequest.java

  exception/
    ParcelTagCategoryCodeConflictException.java
    ParcelTagCategoryNotFoundException.java
    InactiveParcelTagCategoryException.java    # 400 — tag references inactive category
    # ParcelTagExceptionHandler already covers the package — extend with three new handlers.

backend/src/main/resources/db/migration/
  V19__parcel_tag_categories.sql
```

### Backend — modified files

- `ParcelTag.java` — `String category` → `@ManyToOne(fetch=LAZY) ParcelTagCategory category`.
- `ParcelTagRepository.java` — `findByActiveTrueOrderByCategoryAscLabelAsc()` becomes `findByActiveTrueAndCategoryActiveTrueOrderByCategoryLabelAscLabelAsc()`. Admin variant `findAllByOrderByCategoryAscLabelAsc()` becomes `findAllByOrderByCategoryLabelAscLabelAsc()` (admin sees all, including those whose category is inactive).
- `ParcelTagService.java` — seed loop now ensures each tag's category exists first (insert-if-missing on a `ParcelTagCategoryRepository.findByCode`), then links the tag.
- `ParcelTagResponse.java` (public DTO) — `category` stays a `String` (the category label).
- `AdminParcelTagDto.java` — `category` becomes a nested object `{ code, label }` so the admin UI can show the label while referencing rows by code.
- `CreateParcelTagRequest.java` / `UpdateParcelTagRequest.java` — `category: String` becomes `categoryCode: String`.
- `AdminParcelTagService.java` — resolve `categoryCode` → `ParcelTagCategory` row on create/update; reject 400 with `code=INACTIVE_PARCEL_TAG_CATEGORY` if the referenced category is inactive, 400 with `code=PARCEL_TAG_CATEGORY_NOT_FOUND` if missing.
- `AuctionDtoMapper.java` — `t.getCategory()` was a String; becomes `t.getCategory().getLabel()`.
- `AdminActionType.java` — add `PARCEL_TAG_CATEGORY_CREATED`, `PARCEL_TAG_CATEGORY_UPDATED`, `PARCEL_TAG_CATEGORY_TOGGLED_ACTIVE`.
- `AdminActionTargetType.java` — add `PARCEL_TAG_CATEGORY`.

### Frontend — new files

```
frontend/src/app/admin/parcel-tag-categories/
  page.tsx

frontend/src/components/admin/parcel-tag-categories/
  AdminParcelTagCategoriesPage.tsx
  AdminParcelTagCategoriesTable.tsx
  AddParcelTagCategoryModal.tsx
  EditParcelTagCategoryModal.tsx

frontend/src/hooks/admin/useAdminParcelTagCategories.ts
frontend/src/lib/admin/parcelTagCategories.ts
```

### Frontend — modified files

- `AdminShell.tsx` — sidebar entry "Parcel categories" between "Parcel tags" and "Users".
- `AddParcelTagModal.tsx` / `EditParcelTagModal.tsx` — category field becomes a `<select>` populated from `useAdminParcelTagCategories()` (active only). Inline "Manage categories →" link below the field.
- `AdminParcelTagsTable.tsx` — groups by `tag.category.label` (now nested object).
- `lib/admin/parcelTags.ts` — `category: string` → `category: { code, label }`; create/update payloads use `categoryCode`.
- `lib/admin/queryKeys.ts` — add `parcelTagCategories()` + `parcelTagCategoriesList()`.

## V19 migration

```sql
-- 1. New table
CREATE TABLE parcel_tag_categories (
    id          BIGSERIAL PRIMARY KEY,
    public_id   UUID NOT NULL UNIQUE,
    code        VARCHAR(50) NOT NULL UNIQUE,
    label       VARCHAR(100) NOT NULL,
    description TEXT,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT NOT NULL DEFAULT 0
);

-- 2. Seed from distinct values currently on parcel_tags.
--    Code = uppercase, non-alphanumeric → underscore, then trim runs of underscores.
INSERT INTO parcel_tag_categories (public_id, code, label, active)
SELECT
    gen_random_uuid(),
    regexp_replace(regexp_replace(upper(category), '[^A-Z0-9]+', '_', 'g'),
                   '^_+|_+$', '', 'g'),
    category,
    TRUE
FROM (SELECT DISTINCT category FROM parcel_tags) c;

-- 3. Add the FK column, backfill, then enforce NOT NULL.
ALTER TABLE parcel_tags ADD COLUMN category_id BIGINT;

UPDATE parcel_tags SET category_id = (
    SELECT id FROM parcel_tag_categories
    WHERE parcel_tag_categories.label = parcel_tags.category
);

ALTER TABLE parcel_tags
    ALTER COLUMN category_id SET NOT NULL,
    ADD CONSTRAINT fk_parcel_tags_category
        FOREIGN KEY (category_id) REFERENCES parcel_tag_categories(id);

-- 4. Drop the old free-text column.
ALTER TABLE parcel_tags DROP COLUMN category;

CREATE INDEX ix_parcel_tags_category_id ON parcel_tags(category_id);
```

## Backend endpoints

All under `/api/v1/admin/parcel-tag-categories`, gated by the existing `/api/v1/admin/**` `ROLE_ADMIN` matcher in `SecurityConfig`. Each write records an `AdminAction`.

### `GET /api/v1/admin/parcel-tag-categories`

Returns `AdminParcelTagCategoryDto[]` ordered by `label ASC` (active and inactive both included).

### `POST /api/v1/admin/parcel-tag-categories`

Body: `CreateParcelTagCategoryRequest`.

```json
{ "code": "TERRAIN", "label": "Terrain", "description": "Land surface kind." }
```

- `code` — required, 1-50 chars, regex `^[A-Z0-9_]+$`, unique.
- `label` — required, 1-100 chars.
- `description` — optional, ≤2000 chars.

Returns 201 `AdminParcelTagCategoryDto`. Duplicate code → 409 `PARCEL_TAG_CATEGORY_CODE_CONFLICT`.

### `PATCH /api/v1/admin/parcel-tag-categories/{code}`

Body: `UpdateParcelTagCategoryRequest`. Partial; same validations.

```json
{ "label": "Terrain (premium)" }
```

Returns 200 `AdminParcelTagCategoryDto`. Missing → 404 `PARCEL_TAG_CATEGORY_NOT_FOUND`.

### `POST /api/v1/admin/parcel-tag-categories/{code}/toggle-active`

Empty body. Flips `active`. Returns 200 `AdminParcelTagCategoryDto`. Missing → 404.

## Frontend UI

### Route

`/admin/parcel-tag-categories` — client page wrapper, sidebar entry between "Parcel tags" and "Users".

### Page composition

Mirrors the parcel-tags page: list grouped not by anything (categories are flat), `+ Add category` button at top, per-row Edit + Disable/Re-enable. Inactive rows visually muted; the table shows code (mono), label, status, action buttons.

### Modals

- **Add category** — same shape as Add tag: code field with uppercase auto-coerce, label, optional description.
- **Edit category** — code is read-only; label and description editable.

### Tag form integration

The category field on Add/Edit tag modals changes from a free-text input + datalist to a `<select>`:

```tsx
<select value={categoryCode} onChange={...}>
  <option value="">Pick a category…</option>
  {activeCategories.map(c => <option key={c.code} value={c.code}>{c.label}</option>)}
</select>
```

Below the select, an inline `Manage categories →` link routes to `/admin/parcel-tag-categories`. If a category was disabled while a tag was being created, the tag form rejects on save with the 400 from the backend, and surfaces "This category is no longer active — pick another."

## Audit logging

- `PARCEL_TAG_CATEGORY_CREATED` — target = category id, metadata = `{ code, label }`.
- `PARCEL_TAG_CATEGORY_UPDATED` — target = category id, metadata = `{ changes: { ... } }`.
- `PARCEL_TAG_CATEGORY_TOGGLED_ACTIVE` — target = category id, metadata = `{ from, to }`.

## Error handling & edge cases

- **Duplicate code at create** — 409 `PARCEL_TAG_CATEGORY_CODE_CONFLICT`.
- **Missing category at update/toggle** — 404 `PARCEL_TAG_CATEGORY_NOT_FOUND`.
- **Tag references inactive/missing category at save** — 400 `INACTIVE_PARCEL_TAG_CATEGORY` or `PARCEL_TAG_CATEGORY_NOT_FOUND`. Frontend shows inline error on the category field.
- **Disabling a category** — does NOT cascade-write to tags. The public read filters `WHERE tag.active AND category.active`. Re-enabling restores visibility for all its tags.
- **Disabling the last active category** — admin can do it. Tag creation will fail with 400 because the dropdown will be empty; admins notice and re-enable. No special guard.
- **Concurrent edits / stale category** — `BaseMutableEntity` carries a `version` column; stale update raises `ObjectOptimisticLockingFailureException` → 409 from the global handler.

## Testing

### Backend

`AdminParcelTagCategoryServiceTest` (Mockito unit):
- `create_happyPath_persistsAndAudits`
- `create_duplicateCode_throwsConflict`
- `update_writesOnlySuppliedFields_andAudits`
- `update_missingCode_throwsNotFound`
- `toggleActive_flipsAndAudits`
- `toggleActive_missingCode_throwsNotFound`

`AdminParcelTagCategoryControllerSliceTest` (`@SpringBootTest`):
- `list_returns200WithAllCategories`
- `create_returns201WithDto`
- `create_invalidCode_returns400`
- `create_duplicateCode_returns409`
- `update_returns200WithUpdatedDto`
- `update_missingCode_returns404`
- `toggleActive_returns200`
- `nonAdmin_returns403`
- `anonymous_returns401`

`AdminParcelTagServiceTest` extensions:
- `create_inactiveCategoryCode_throwsInactive`
- `create_missingCategoryCode_throwsNotFound`

`ParcelTagServiceTest` updates: existing seed test still verifies 25 tags; new assertion that 6 categories were created with the expected codes.

### Frontend

- `useAdminParcelTagCategories.test.tsx` — list / create / update / toggle mutations.
- `AdminParcelTagCategoriesTable.test.tsx` — groups inactive rows muted, Edit + Disable buttons fire callbacks, empty state.
- `AddParcelTagCategoryModal.test.tsx` — validates required fields, calls mutation, surfaces 409 inline.
- `EditParcelTagCategoryModal.test.tsx` — pre-fills, code read-only, partial update.
- Existing parcel-tag tests update — `category: string` becomes `category: { code, label }` in fixtures.

## Out of scope

- **Hard delete** — soft-disable via `active=false` is the durable contract.
- **Renaming `code`** — code is the FK target conceptually (we look up by code on tag create/update), so renaming would silently invalidate every tag's reference if not done atomically. To rename a category's display value, edit `label` — `code` stays put.
- **Reorder categories** — labels sort alphabetically. No manual sort.
- **Public read endpoint** for categories — admin endpoint suffices; public catalogue groups tags by category label as it does today.

## Acceptance

- Admins see `/admin/parcel-tag-categories` with the 6 categories that were inferred from the existing tag set.
- Admins can add a new category, edit a label/description, and toggle active.
- Tag form's category field is a select (not free-text) that filters to active categories.
- Disabling a category hides all its tags from the public `GET /api/v1/parcel-tags`.
- Re-enabling restores them.
- Backend tests + integration tests pass.
- Frontend `npm run verify` clean, all tests green.
- Migration runs cleanly on prod (V19) — `parcel_tag_categories` table exists, `parcel_tags.category_id` is populated, `parcel_tags.category` column is gone.
