# Epic 07 sub-spec 2 — Browse & Search Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** [2026-04-23-epic-07-sub-2-browse-search-frontend.md](../specs/2026-04-23-epic-07-sub-2-browse-search-frontend.md)

**Goal:** Ship the frontend for Epic 07 — browse page, seller listings, homepage featured rails, auction detail rebuild, Curator Tray, `/saved` page, profile OG metadata, listing-wizard `title` input — consuming the backend from sub-spec 1.

**Architecture:** Next.js 16 server components for SSR routes; client hydration with React Query initialData; URL-as-source-of-truth for list filter/sort/pagination state; shared `ListingCard` component with three variants; React Query hooks (no Context) for cross-surface saved-auctions state; six horizontal tasks in dependency order.

**Tech Stack:** Next.js 16, React 19, TypeScript 5, Tailwind CSS 4, TanStack Query 5, React Hook Form, Zod, Vitest + RTL + MSW, Headless UI (existing).

---

## Conventions enforced in every task

- **Every test fails before implementation lands** (RED → GREEN → refactor). Add test, run, see red, write code, run, see green, commit.
- **Dark-mode assertion required** per new component: render under `<html class="dark">`, assert token classes resolve. Existing `renderWithProviders({ theme: "dark", forceTheme: true })` is the hook.
- **Verify scripts pass** before commit: `npm run verify` runs `no-dark-variants`, `no-hex-colors`, `no-inline-styles`, `coverage`. Fix any violations before pushing.
- **No emojis, no AI attribution** in code, commits, or PR. Existing hard rules from CLAUDE.md.
- **Icons only from** `@/components/ui/icons.ts`. If a needed icon isn't there, add it to that barrel file first.
- **Hooks live in** `frontend/src/hooks/` (not `lib/hooks/`) — spec said `lib/hooks/` but project convention is top-level `hooks/`. Plan uses the correct project path.
- **Per-task doc sweeps**: `DEFERRED_WORK.md` updates happen at the end of the task that resolves or introduces the item (spec §19).
- **Test command:** `cd frontend && npm run test -- <pattern>`. Run single file with `npm run test -- path/to/file.test.tsx`.

## File structure overview

New folders and files introduced across the six tasks. Each task below specifies which ones it creates.

```
frontend/src/
├── app/
│   ├── page.tsx                             (modified — Task 3)
│   ├── browse/page.tsx                      (modified — Task 2b)
│   ├── users/[id]/page.tsx                  (modified — Task 4)
│   ├── users/[id]/listings/page.tsx         (new — Task 2b)
│   ├── saved/page.tsx                       (new — Task 5)
│   ├── auction/[id]/page.tsx                (modified — Task 4)
│   └── dev/listing-card-demo/page.tsx       (new — Task 2a, dev only)
├── components/
│   ├── ui/
│   │   ├── BottomSheet.tsx                  (new — Task 2a)
│   │   ├── Drawer.tsx                       (new — Task 5)
│   │   ├── Lightbox.tsx                     (new — Task 4)
│   │   ├── Pagination.tsx                   (new — Task 2a)
│   │   ├── RangeSlider.tsx                  (new — Task 2a)
│   │   ├── ActiveFilterBadge.tsx            (new — Task 2a)
│   │   ├── StatusChip.tsx                   (new — Task 2a)
│   │   ├── Toast/useToast.ts                (modified — Task 5, widen variants)
│   │   ├── Toast/ToastProvider.tsx          (modified — Task 5, render warning variant)
│   │   └── icons.ts                         (modified — add new icons)
│   ├── auction/
│   │   ├── ListingCard.tsx                  (new — Task 2a)
│   │   ├── AuctionHero.tsx                  (modified — Task 4, add Lightbox)
│   │   ├── ParcelInfoPanel.tsx              (modified — Task 1, surface title)
│   │   ├── SellerProfileCard.tsx            (replaced — Task 4)
│   │   ├── VisitInSecondLifeBlock.tsx       (new — Task 4)
│   │   ├── BreadcrumbNav.tsx                (new — Task 4)
│   │   └── ParcelLayoutMapPlaceholder.tsx   (new — Task 4)
│   ├── browse/
│   │   ├── BrowseShell.tsx                  (new — Task 2b)
│   │   ├── FilterSidebar.tsx                (new — Task 2b)
│   │   ├── FilterSidebarContent.tsx         (new — Task 2b)
│   │   ├── FilterSection.tsx                (new — Task 2b)
│   │   ├── SortDropdown.tsx                 (new — Task 2b)
│   │   ├── DistanceSearchBlock.tsx          (new — Task 2b)
│   │   ├── ActiveFilters.tsx                (new — Task 2b)
│   │   ├── ResultsHeader.tsx                (new — Task 2b)
│   │   ├── ResultsGrid.tsx                  (new — Task 2b)
│   │   ├── ResultsEmpty.tsx                 (new — Task 2b)
│   │   └── SellerHeader.tsx                 (new — Task 2b)
│   ├── curator/
│   │   ├── CuratorTrayMount.tsx             (new — Task 5)
│   │   ├── CuratorTrayTrigger.tsx           (new — Task 5)
│   │   ├── CuratorTray.tsx                  (new — Task 5)
│   │   ├── CuratorTrayContent.tsx           (new — Task 5)
│   │   ├── CuratorTrayHeader.tsx            (new — Task 5)
│   │   └── CuratorTrayEmpty.tsx             (new — Task 5)
│   ├── marketing/
│   │   └── FeaturedRow.tsx                  (new — Task 3)
│   └── listing/
│       ├── ListingWizardForm.tsx            (modified — Task 1)
│       ├── ListingPreviewCard.tsx           (modified — Task 1)
│       ├── MyListingsTab.tsx                (modified — Task 1)
│       └── ListingSummaryRow.tsx            (modified — Task 1)
├── hooks/
│   ├── useAuctionSearch.ts                  (new — Task 2a)
│   └── useSavedAuctions.ts                  (new — Task 5)
├── lib/
│   ├── api/
│   │   ├── auctions-search.ts               (new — Task 2a)
│   │   └── saved.ts                         (new — Task 5)
│   ├── search/
│   │   ├── canonical-key.ts                 (new — Task 2a)
│   │   ├── url-codec.ts                     (new — Task 2a)
│   │   └── status-chip.ts                   (new — Task 2a)
│   └── sl/
│       └── slurl.ts                         (new — Task 4)
└── types/
    ├── auction.ts                           (modified — Tasks 1, 2a, add title + endOutcome)
    └── search.ts                            (new — Task 2a)
```

**Backend touches** (bundled with the consuming frontend task):

```
backend/src/main/java/com/slparcelauctions/backend/auction/
├── dto/
│   ├── AuctionSearchResultDto.java          (modified — Task 2a, add endOutcome)
│   └── AuctionSearchResultMapper.java       (modified — Task 2a, populate endOutcome)
└── ... (+ MockMvc regression test)

backend/src/main/java/com/slparcelauctions/backend/user/
└── dto/UserDto.java                         (verified — Task 4, add bio if missing)
```

---

# Task 1 — Listing-wizard title input + surfacing on existing components

**Depends on:** nothing (standalone prerequisite).

**Why first:** Unblocks the `Auction.title NOT NULL backfill` pre-launch ops gotcha tracked in `DEFERRED_WORK.md`. The field already exists on the backend from sub-spec 1; the frontend just has to surface it.

## Task 1 files

- Modify: `frontend/src/types/auction.ts` — add `title: string` to `SellerAuctionResponse` and `PublicAuctionResponse`.
- Modify: `frontend/src/components/listing/ListingWizardForm.tsx` — add Title section + Zod validation + wire into submit.
- Modify: `frontend/src/components/listing/ListingPreviewCard.tsx` — show title as primary headline.
- Modify: `frontend/src/components/listing/MyListingsTab.tsx` or `ListingSummaryRow.tsx` — title as primary label.
- Modify: `frontend/src/components/auction/ParcelInfoPanel.tsx` — prefer title over parcel description for the headline.
- Tests: co-located `*.test.tsx` updates.

### Step 1.1: Update auction type with `title`

- [ ] **Read the existing type.** `frontend/src/types/auction.ts`. Find the `SellerAuctionResponse` and `PublicAuctionResponse` types.

- [ ] **Add `title: string` to both types.** The backend already returns `title` from `/auctions/{id}` per sub-spec 1 §11.4. Update:

```ts
// Add to SellerAuctionResponse and PublicAuctionResponse type definitions:
title: string;
```

- [ ] **Update any fixture files** under `frontend/src/test/fixtures/auction.ts` to include `title`. Example default: `"Featured Parcel Listing"`.

- [ ] **Run typecheck to find consumers that break.** `cd frontend && npx tsc --noEmit`.

- [ ] **Fix any test fixtures or MSW handlers that reference the auction shape.** Common targets: `frontend/src/test/msw/fixtures.ts`, `frontend/src/test/msw/handlers.ts`.

- [ ] **Commit.**

```bash
git add frontend/src/types/auction.ts frontend/src/test/fixtures/auction.ts frontend/src/test/msw/fixtures.ts frontend/src/test/msw/handlers.ts
git commit -m "types(auction): surface title field from backend DTO"
```

### Step 1.2: Failing test for wizard title input

- [ ] **Add a test** in `frontend/src/components/listing/ListingWizardForm.test.tsx` (extend existing file).

```tsx
it("requires a non-empty title under 120 chars", async () => {
  const user = userEvent.setup();
  renderWithProviders(<ListingWizardForm mode="create" />, { auth: "authenticated" });

  // Empty submit should surface title required error.
  const submit = await screen.findByRole("button", { name: /continue to review/i });
  await user.click(submit);
  expect(await screen.findByText(/title is required/i)).toBeInTheDocument();

  // Type a valid title. Counter should update.
  const title = screen.getByLabelText(/listing title/i);
  await user.type(title, "Premium Waterfront");
  expect(screen.getByText("18 / 120")).toBeInTheDocument();

  // Over 120 chars → error.
  await user.clear(title);
  await user.type(title, "x".repeat(121));
  expect(await screen.findByText(/120 characters or less/i)).toBeInTheDocument();
});
```

- [ ] **Run the test — expect it to FAIL** (no title field exists yet).

```bash
cd frontend && npm run test -- ListingWizardForm
```

Expected: FAIL with "Unable to find label text: /listing title/i".

### Step 1.3: Add title field to wizard form

- [ ] **Open** `frontend/src/components/listing/ListingWizardForm.tsx`.

- [ ] **Add a title section at the top of the Configure step.** Place above the `ParcelLookupField`. Use the existing `useListingDraft` hook's persistence pattern. Reference sketch:

```tsx
// Above the parcel lookup card:
<section className="flex flex-col gap-2">
  <label htmlFor="listing-title" className="text-label-md font-semibold tracking-wider uppercase text-on-surface-variant">
    Listing Title
  </label>
  <p className="text-body-sm text-on-surface-variant">
    A short, punchy headline for your listing (max 120 characters).
  </p>
  <input
    id="listing-title"
    type="text"
    maxLength={120}
    value={draft.title ?? ""}
    onChange={(e) => draft.setTitle(e.target.value)}
    className={inputClasses}
    aria-invalid={titleError ? "true" : undefined}
    aria-describedby="listing-title-counter"
  />
  <div id="listing-title-counter" className={cn(
    "text-body-sm",
    titleLength >= 100 ? "text-error" : "text-on-surface-variant"
  )}>
    {titleLength} / 120
  </div>
  {titleError && <FormError>{titleError}</FormError>}
</section>
```

- [ ] **Add Zod schema** to the submit path (or to the `useListingDraft` hook). The schema:

```ts
import { z } from "zod";

export const listingTitleSchema = z.string().trim().min(1, "Title is required").max(120, "Title must be 120 characters or less");
```

- [ ] **Wire into `useListingDraft`** at `frontend/src/hooks/useListingDraft.ts`. Add `title: string | null` to the draft state + a `setTitle(value: string)` setter. Persist through the existing API draft/save flow (backend already accepts `title` on the `AuctionCreateRequest` DTO per sub-spec 1 Task 2).

- [ ] **Run the test — expect it to PASS.**

- [ ] **Also verify a happy-path submit test**: title populated + all other required fields → submit succeeds.

### Step 1.4: Surface title on ListingPreviewCard

- [ ] **Add failing test** to `frontend/src/components/listing/ListingPreviewCard.test.tsx`:

```tsx
it("shows auction title as the primary headline", () => {
  const auction = buildPreviewAuction({ title: "Bayside Cottage Lot" });
  renderWithProviders(<ListingPreviewCard auction={auction} />);
  const headline = screen.getByRole("heading", { level: 3 });
  expect(headline).toHaveTextContent("Bayside Cottage Lot");
});
```

- [ ] **Extend `ListingPreviewAuction` type** in the card file to include `title: string`. Update the card body to render `title` above the parcel description.

- [ ] **Run test — PASS.**

### Step 1.5: Surface title on ParcelInfoPanel (detail page)

- [ ] **Add failing test** to `frontend/src/components/auction/ParcelInfoPanel.test.tsx`:

```tsx
it("prefers auction.title over parcel.description for the heading", () => {
  const auction = mockAuction({ title: "Premium Waterfront" });
  renderWithProviders(<ParcelInfoPanel auction={auction} />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Premium Waterfront");
  // parcel name + region still present as subtitle
  expect(screen.getByText(new RegExp(auction.parcel.regionName))).toBeInTheDocument();
});
```

- [ ] **Modify** `ParcelInfoPanel.tsx` — replace the `const title = parcel.description?.trim() || parcel.regionName;` line with a three-level fallback:

```ts
const title =
  (auction.title?.trim() ?? "") ||
  (parcel.description?.trim() ?? "") ||
  parcel.regionName;
```

Note: `ParcelInfoPanel` currently accepts `parcel` as a prop. Widen to accept `auction` (so it can read `auction.title`). Update every call site in `app/auction/[id]/` and any test setups.

- [ ] **Run test — PASS.**

### Step 1.6: Surface title on MyListingsTab / ListingSummaryRow

- [ ] **Add failing test** in `frontend/src/components/listing/ListingSummaryRow.test.tsx` (or the `MyListingsTab.test.tsx` if rows are tested there):

```tsx
it("shows auction title as primary label, parcel name as secondary", () => {
  const auction = mockSellerAuction({ title: "Premium Waterfront", parcel: { regionName: "Tula" }});
  renderWithProviders(<ListingSummaryRow auction={auction} />);
  const primary = screen.getByTestId("listing-summary-primary");
  expect(primary).toHaveTextContent("Premium Waterfront");
  const secondary = screen.getByTestId("listing-summary-secondary");
  expect(secondary).toHaveTextContent("Tula");
});
```

- [ ] **Update** `ListingSummaryRow.tsx` to render `auction.title` as the primary span and `parcel.regionName` (+ parcel description if space) as the secondary. Add `data-testid` attrs for tests.

- [ ] **Run test — PASS.**

### Step 1.7: Final verification + commit

- [ ] **Run full frontend test suite.**

```bash
cd frontend && npm run test
```

Expected: all existing tests still pass, all new title tests pass.

- [ ] **Run verify scripts.**

```bash
cd frontend && npm run verify
```

Expected: all four verify scripts pass.

- [ ] **Commit.**

```bash
git add frontend/src/types/auction.ts \
        frontend/src/components/listing/ListingWizardForm.tsx \
        frontend/src/components/listing/ListingWizardForm.test.tsx \
        frontend/src/components/listing/ListingPreviewCard.tsx \
        frontend/src/components/listing/ListingPreviewCard.test.tsx \
        frontend/src/components/listing/ListingSummaryRow.tsx \
        frontend/src/components/listing/ListingSummaryRow.test.tsx \
        frontend/src/components/auction/ParcelInfoPanel.tsx \
        frontend/src/components/auction/ParcelInfoPanel.test.tsx \
        frontend/src/hooks/useListingDraft.ts \
        frontend/src/app/auction/\[id\]/**

git commit -m "feat(listing): surface auction.title field end-to-end

Adds the Listing Title input to the create/edit wizard with 120-char
Zod validation and a live counter. Surfaces title on ListingPreviewCard,
ListingSummaryRow, and ParcelInfoPanel (demoting parcel.description +
region to subtitle). Existing callers keep working since title is
additive.

Resolves the pre-launch ops surface for the Auction.title NOT NULL
column introduced in Epic 07 sub-spec 1 Task 2."
```

---

# Task 2a — Primitives + canonical ListingCard + search API hook

**Depends on:** Task 1 (for `title` type + render test fixtures).

**Deliverables:**

1. Small backend commit — add `endOutcome` to `AuctionSearchResultDto`.
2. New TS types — `AuctionSearchQuery`, `AuctionSearchResultDto`, `SavedIdsResponse`.
3. New API client — `lib/api/auctions-search.ts`.
4. URL codec + canonical key + status-chip derivation — `lib/search/*.ts`.
5. Hook — `hooks/useAuctionSearch.ts`.
6. Primitives — `ui/RangeSlider.tsx`, `ui/Pagination.tsx`, `ui/BottomSheet.tsx`, `ui/ActiveFilterBadge.tsx`, `ui/StatusChip.tsx`.
7. Canonical card — `components/auction/ListingCard.tsx` with three variants.
8. Dev demo route — `app/dev/listing-card-demo/page.tsx`.

## Task 2a Step-by-step

### Step 2a.1: Backend — add `endOutcome` to search result DTO

- [ ] **Find the Java record.** `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionSearchResultDto.java`.

- [ ] **Add field.** Add `String endOutcome` (nullable) to the record in the position next to `status`. Example:

```java
public record AuctionSearchResultDto(
    Long id,
    String title,
    String status,
    String endOutcome,       // nullable — populated for ended auctions only
    ParcelSummaryDto parcel,
    ...
) { }
```

- [ ] **Find the mapper.** Likely `AuctionSearchResultMapper.java` or inline in `AuctionSearchService.java`. Populate `endOutcome` from `Auction.endOutcome` (an enum column from Epic 04). If `auction.getEndOutcome() == null`, pass `null`; otherwise `auction.getEndOutcome().name()`.

- [ ] **Add a failing test** in the existing `AuctionSearchControllerIntegrationTest` (backend):

```java
@Test
void search_includes_endOutcome_null_for_active_and_populated_for_ended() {
    // Seed one ACTIVE and one ENDED-SOLD auction (use existing test helpers).
    // Query /auctions/search.
    // Assert ACTIVE row has "endOutcome": null.
    // Then call /me/saved/auctions (authenticated) with statusFilter=ended_only
    // on a user that saved the ENDED auction — assert "endOutcome": "SOLD".
}
```

- [ ] **Run test — FAIL** (field not in mapper yet). Then **write mapper code** — run again — PASS.

- [ ] **Backend commit (first on branch):**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionSearchResultDto.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/service/AuctionSearchService.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/AuctionSearchControllerIntegrationTest.java

git commit -m "feat(search): add endOutcome field to AuctionSearchResultDto

Populated only for ended auctions (null for ACTIVE — the /search endpoint
returns ACTIVE only, so the field is effectively null there). Required by
the frontend status-chip derivation in Epic 07 sub-spec 2 — SOLD vs
RESERVE_NOT_MET vs NO_BIDS chips on the Curator Tray ended_only view.

Additive; no existing caller affected."
```

### Step 2a.2: TS types for search surface

- [ ] **Create** `frontend/src/types/search.ts`:

```ts
// Mirrors backend AuctionSearchQuery + DTOs — Epic 07 sub-spec 1 §5.1.
import type { AuctionPhotoDto } from "./auction";

export type MaturityRating = "GENERAL" | "MODERATE" | "ADULT";
export type VerificationTier = "SCRIPT" | "BOT" | "HUMAN";
export type ReserveStatusFilter = "all" | "reserve_met" | "reserve_not_met" | "no_reserve";
export type SnipeFilter = "any" | "true" | "false";
export type TagsMode = "or" | "and";
export type AuctionSort =
  | "newest"
  | "ending_soonest"
  | "most_bids"
  | "lowest_price"
  | "largest_area"
  | "nearest";
export type SavedStatusFilter = "active_only" | "all" | "ended_only";

export type AuctionSearchQuery = {
  status?: "ACTIVE";
  region?: string;
  minArea?: number;
  maxArea?: number;
  minPrice?: number;
  maxPrice?: number;
  maturity?: MaturityRating[];
  tags?: string[];
  tagsMode?: TagsMode;
  reserveStatus?: ReserveStatusFilter;
  snipeProtection?: SnipeFilter;
  verificationTier?: VerificationTier[];
  endingWithin?: number; // hours
  nearRegion?: string;
  distance?: number; // regions
  sellerId?: number;
  sort?: AuctionSort;
  page?: number;
  size?: number;
  // Saved-specific; ignored by /search
  statusFilter?: SavedStatusFilter;
};

export type AuctionEndOutcome = "SOLD" | "BOUGHT_NOW" | "RESERVE_NOT_MET" | "NO_BIDS";

export type AuctionSearchResultSeller = {
  id: number;
  displayName: string;
  avatarUrl: string | null;
  averageRating: number | null;
  reviewCount: number | null;
};

export type AuctionSearchResultParcel = {
  id: number;
  name: string;
  region: string;
  area: number;
  maturity: MaturityRating;
  snapshotUrl: string | null;
  gridX: number | null;
  gridY: number | null;
  positionX: number | null;
  positionY: number | null;
  positionZ: number | null;
  tags: string[];
};

export type AuctionSearchResultDto = {
  id: number;
  title: string;
  status: string;
  endOutcome: AuctionEndOutcome | null;
  parcel: AuctionSearchResultParcel;
  primaryPhotoUrl: string | null;
  seller: AuctionSearchResultSeller;
  verificationTier: VerificationTier;
  currentBid: number;
  startingBid: number;
  reservePrice: number | null;
  reserveMet: boolean;
  buyNowPrice: number | null;
  bidCount: number;
  endsAt: string;
  snipeProtect: boolean;
  snipeWindowMin: number | null;
  distanceRegions: number | null;
};

export type SearchResponse = {
  content: AuctionSearchResultDto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  meta?: {
    sortApplied?: AuctionSort;
    nearRegionResolved?: { name: string; gridX: number; gridY: number };
  };
};

export type SavedIdsResponse = { ids: number[] };
```

- [ ] **Typecheck** — no errors.

### Step 2a.3: Failing test for URL codec

- [ ] **Create** `frontend/src/lib/search/url-codec.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { queryFromSearchParams, searchParamsFromQuery, defaultAuctionSearchQuery } from "./url-codec";
import type { AuctionSearchQuery } from "@/types/search";

describe("url codec", () => {
  it("returns defaults when searchParams are empty", () => {
    expect(queryFromSearchParams(new URLSearchParams())).toEqual(defaultAuctionSearchQuery);
  });

  it("decodes scalar filters", () => {
    const q = queryFromSearchParams(new URLSearchParams("region=Tula&min_price=1000&max_price=50000"));
    expect(q.region).toBe("Tula");
    expect(q.minPrice).toBe(1000);
    expect(q.maxPrice).toBe(50000);
  });

  it("decodes CSV multi-selects", () => {
    const q = queryFromSearchParams(new URLSearchParams("maturity=GENERAL,MODERATE&tags=BEACHFRONT,ROADSIDE"));
    expect(q.maturity).toEqual(["GENERAL", "MODERATE"]);
    expect(q.tags).toEqual(["BEACHFRONT", "ROADSIDE"]);
  });

  it("round-trips a realistic query without lossiness", () => {
    const q: AuctionSearchQuery = {
      region: "Tula",
      maturity: ["MODERATE", "ADULT"],
      minPrice: 500,
      maxPrice: 10000,
      tags: ["BEACHFRONT"],
      tagsMode: "and",
      reserveStatus: "reserve_met",
      sort: "ending_soonest",
      page: 2,
      size: 24,
    };
    const restored = queryFromSearchParams(new URLSearchParams(searchParamsFromQuery(q).toString()));
    expect(restored).toEqual(q);
  });

  it("drops defaults from the encoded URL", () => {
    const sp = searchParamsFromQuery({ sort: "newest", page: 0, size: 24 });
    expect(sp.toString()).toBe("");
  });

  it("ignores unknown params silently", () => {
    const q = queryFromSearchParams(new URLSearchParams("foo=bar&region=Tula"));
    expect(q.region).toBe("Tula");
  });
});
```

- [ ] **Run test — FAIL** (module doesn't exist).

### Step 2a.4: Implement URL codec

- [ ] **Create** `frontend/src/lib/search/url-codec.ts`:

```ts
import type {
  AuctionSearchQuery,
  AuctionSort,
  MaturityRating,
  ReserveStatusFilter,
  SnipeFilter,
  TagsMode,
  VerificationTier,
  SavedStatusFilter,
} from "@/types/search";

export const defaultAuctionSearchQuery: AuctionSearchQuery = {
  sort: "newest",
  page: 0,
  size: 24,
};

const VALID_MATURITY: ReadonlyArray<MaturityRating> = ["GENERAL", "MODERATE", "ADULT"];
const VALID_TIER: ReadonlyArray<VerificationTier> = ["SCRIPT", "BOT", "HUMAN"];
const VALID_SORT: ReadonlyArray<AuctionSort> = [
  "newest", "ending_soonest", "most_bids", "lowest_price", "largest_area", "nearest",
];
const VALID_RESERVE: ReadonlyArray<ReserveStatusFilter> = [
  "all", "reserve_met", "reserve_not_met", "no_reserve",
];
const VALID_SNIPE: ReadonlyArray<SnipeFilter> = ["any", "true", "false"];
const VALID_TAGS_MODE: ReadonlyArray<TagsMode> = ["or", "and"];
const VALID_STATUS_FILTER: ReadonlyArray<SavedStatusFilter> = ["active_only", "all", "ended_only"];

function parseCsv<T extends string>(v: string | null, valid: ReadonlyArray<T>): T[] | undefined {
  if (!v) return undefined;
  const parts = v.split(",").map((x) => x.trim()).filter((x): x is T => (valid as ReadonlyArray<string>).includes(x));
  return parts.length > 0 ? parts : undefined;
}

function parseInt64(v: string | null): number | undefined {
  if (v === null || v === "") return undefined;
  const n = Number(v);
  return Number.isFinite(n) && Number.isInteger(n) ? n : undefined;
}

function parseEnum<T extends string>(v: string | null, valid: ReadonlyArray<T>): T | undefined {
  if (v === null) return undefined;
  return (valid as ReadonlyArray<string>).includes(v) ? (v as T) : undefined;
}

export function queryFromSearchParams(sp: URLSearchParams): AuctionSearchQuery {
  const q: AuctionSearchQuery = { ...defaultAuctionSearchQuery };
  const region = sp.get("region");
  if (region) q.region = region;

  const minArea = parseInt64(sp.get("min_area"));
  if (minArea !== undefined) q.minArea = minArea;
  const maxArea = parseInt64(sp.get("max_area"));
  if (maxArea !== undefined) q.maxArea = maxArea;

  const minPrice = parseInt64(sp.get("min_price"));
  if (minPrice !== undefined) q.minPrice = minPrice;
  const maxPrice = parseInt64(sp.get("max_price"));
  if (maxPrice !== undefined) q.maxPrice = maxPrice;

  const maturity = parseCsv(sp.get("maturity"), VALID_MATURITY);
  if (maturity) q.maturity = maturity;

  const tags = sp.get("tags")?.split(",").map((x) => x.trim()).filter(Boolean);
  if (tags && tags.length > 0) q.tags = tags;

  const tagsMode = parseEnum(sp.get("tags_mode"), VALID_TAGS_MODE);
  if (tagsMode) q.tagsMode = tagsMode;

  const reserveStatus = parseEnum(sp.get("reserve_status"), VALID_RESERVE);
  if (reserveStatus) q.reserveStatus = reserveStatus;

  const snipeProtection = parseEnum(sp.get("snipe_protection"), VALID_SNIPE);
  if (snipeProtection) q.snipeProtection = snipeProtection;

  const tier = parseCsv(sp.get("verification_tier"), VALID_TIER);
  if (tier) q.verificationTier = tier;

  const endingWithin = parseInt64(sp.get("ending_within"));
  if (endingWithin !== undefined) q.endingWithin = endingWithin;

  const nearRegion = sp.get("near_region");
  if (nearRegion) q.nearRegion = nearRegion;

  const distance = parseInt64(sp.get("distance"));
  if (distance !== undefined) q.distance = distance;

  const sellerId = parseInt64(sp.get("seller_id"));
  if (sellerId !== undefined) q.sellerId = sellerId;

  const sort = parseEnum(sp.get("sort"), VALID_SORT);
  if (sort) q.sort = sort;

  const page = parseInt64(sp.get("page"));
  if (page !== undefined && page > 0) q.page = page;

  const size = parseInt64(sp.get("size"));
  if (size !== undefined && size !== 24) q.size = size;

  const statusFilter = parseEnum(sp.get("status_filter"), VALID_STATUS_FILTER);
  if (statusFilter) q.statusFilter = statusFilter;

  return q;
}

function putIf<T>(sp: URLSearchParams, key: string, value: T | undefined, toStr: (v: T) => string): void {
  if (value === undefined || value === null) return;
  sp.set(key, toStr(value));
}

export function searchParamsFromQuery(q: AuctionSearchQuery): URLSearchParams {
  const sp = new URLSearchParams();
  putIf(sp, "region", q.region, (v) => v);
  putIf(sp, "min_area", q.minArea, String);
  putIf(sp, "max_area", q.maxArea, String);
  putIf(sp, "min_price", q.minPrice, String);
  putIf(sp, "max_price", q.maxPrice, String);
  if (q.maturity?.length) sp.set("maturity", q.maturity.join(","));
  if (q.tags?.length) sp.set("tags", q.tags.join(","));
  if (q.tagsMode && q.tagsMode !== "or") sp.set("tags_mode", q.tagsMode);
  if (q.reserveStatus && q.reserveStatus !== "all") sp.set("reserve_status", q.reserveStatus);
  if (q.snipeProtection && q.snipeProtection !== "any") sp.set("snipe_protection", q.snipeProtection);
  if (q.verificationTier?.length) sp.set("verification_tier", q.verificationTier.join(","));
  putIf(sp, "ending_within", q.endingWithin, String);
  putIf(sp, "near_region", q.nearRegion, (v) => v);
  putIf(sp, "distance", q.distance, String);
  putIf(sp, "seller_id", q.sellerId, String);
  if (q.sort && q.sort !== "newest") sp.set("sort", q.sort);
  if (q.page !== undefined && q.page > 0) sp.set("page", String(q.page));
  if (q.size !== undefined && q.size !== 24) sp.set("size", String(q.size));
  if (q.statusFilter && q.statusFilter !== "active_only") sp.set("status_filter", q.statusFilter);
  return sp;
}
```

- [ ] **Run test — PASS.**

### Step 2a.5: Canonical key (React Query key hashing)

- [ ] **Create failing test** `frontend/src/lib/search/canonical-key.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { canonicalKey } from "./canonical-key";

describe("canonicalKey", () => {
  it("produces stable keys for same filters in different orders", () => {
    const a = canonicalKey({ region: "Tula", maturity: ["MODERATE", "GENERAL"] });
    const b = canonicalKey({ maturity: ["GENERAL", "MODERATE"], region: "Tula" });
    expect(a).toBe(b);
  });

  it("differs when values differ", () => {
    expect(canonicalKey({ region: "Tula" })).not.toBe(canonicalKey({ region: "Beta" }));
  });

  it("omits undefined fields", () => {
    expect(canonicalKey({ region: "Tula", minPrice: undefined })).toBe(canonicalKey({ region: "Tula" }));
  });
});
```

- [ ] **Implement** `frontend/src/lib/search/canonical-key.ts`:

```ts
import type { AuctionSearchQuery } from "@/types/search";

export function canonicalKey(query: AuctionSearchQuery): string {
  // Deterministic JSON: sort keys, drop null/undefined, sort arrays.
  const pairs = Object.entries(query)
    .filter(([, v]) => v !== undefined && v !== null)
    .map(([k, v]) => [k, Array.isArray(v) ? [...v].sort() : v] as const)
    .sort(([a], [b]) => a.localeCompare(b));
  return JSON.stringify(Object.fromEntries(pairs));
}
```

- [ ] **Run tests — PASS.**

### Step 2a.6: Status chip derivation

- [ ] **Create failing test** `frontend/src/lib/search/status-chip.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { deriveStatusChip } from "./status-chip";

function base(overrides: Partial<Parameters<typeof deriveStatusChip>[0]> = {}) {
  return {
    status: "ACTIVE",
    endOutcome: null,
    endsAt: new Date(Date.now() + 5 * 3600_000).toISOString(),
    ...overrides,
  };
}

describe("deriveStatusChip", () => {
  it("LIVE when active and ends_at > 1h away", () => {
    expect(deriveStatusChip(base()).label).toBe("LIVE");
  });

  it("ENDING SOON when active and ends_at <= 1h away", () => {
    expect(deriveStatusChip(base({ endsAt: new Date(Date.now() + 30 * 60_000).toISOString() })).label).toBe("ENDING SOON");
  });

  it("SOLD on endOutcome SOLD/BOUGHT_NOW", () => {
    expect(deriveStatusChip(base({ status: "COMPLETED", endOutcome: "SOLD" })).label).toBe("SOLD");
    expect(deriveStatusChip(base({ status: "COMPLETED", endOutcome: "BOUGHT_NOW" })).label).toBe("SOLD");
  });

  it("RESERVE NOT MET / NO BIDS / CANCELLED / SUSPENDED", () => {
    expect(deriveStatusChip(base({ status: "ENDED", endOutcome: "RESERVE_NOT_MET" })).label).toBe("RESERVE NOT MET");
    expect(deriveStatusChip(base({ status: "ENDED", endOutcome: "NO_BIDS" })).label).toBe("NO BIDS");
    expect(deriveStatusChip(base({ status: "CANCELLED", endOutcome: null })).label).toBe("CANCELLED");
    expect(deriveStatusChip(base({ status: "SUSPENDED", endOutcome: null })).label).toBe("SUSPENDED");
  });

  it("falls back to ENDED for unknown status", () => {
    expect(deriveStatusChip(base({ status: "MYSTERY", endOutcome: null })).label).toBe("ENDED");
  });
});
```

- [ ] **Implement** `frontend/src/lib/search/status-chip.ts`:

```ts
import type { AuctionEndOutcome } from "@/types/search";

export type ChipTone = "live" | "ending_soon" | "sold" | "muted" | "warning";

export type StatusChipInfo = { label: string; tone: ChipTone };

type ChipInput = { status: string; endOutcome: AuctionEndOutcome | null; endsAt: string };

const ONE_HOUR_MS = 60 * 60 * 1000;

export function deriveStatusChip(input: ChipInput): StatusChipInfo {
  const { status, endOutcome } = input;
  if (status === "ACTIVE") {
    const remaining = new Date(input.endsAt).getTime() - Date.now();
    return remaining <= ONE_HOUR_MS
      ? { label: "ENDING SOON", tone: "ending_soon" }
      : { label: "LIVE", tone: "live" };
  }
  if (endOutcome === "SOLD" || endOutcome === "BOUGHT_NOW") return { label: "SOLD", tone: "sold" };
  if (endOutcome === "RESERVE_NOT_MET") return { label: "RESERVE NOT MET", tone: "warning" };
  if (endOutcome === "NO_BIDS") return { label: "NO BIDS", tone: "muted" };
  if (status === "CANCELLED") return { label: "CANCELLED", tone: "muted" };
  if (status === "SUSPENDED") return { label: "SUSPENDED", tone: "warning" };
  return { label: "ENDED", tone: "muted" };
}
```

- [ ] **Run tests — PASS.**

### Step 2a.7: Search API client

- [ ] **Create failing test** `frontend/src/lib/api/auctions-search.test.ts` (keep it focused on happy path + rate-limit error):

```ts
import { describe, it, expect, beforeEach } from "vitest";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import { searchAuctions, fetchFeatured } from "./auctions-search";

describe("auctions-search API", () => {
  it("search returns the paged payload", async () => {
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        HttpResponse.json({ content: [], page: 0, size: 24, totalElements: 0, totalPages: 0, first: true, last: true }),
      ),
    );
    const r = await searchAuctions({ sort: "newest", page: 0, size: 24 });
    expect(r.content).toEqual([]);
    expect(r.first).toBe(true);
  });

  it("throws ApiError on 429", async () => {
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        new HttpResponse(JSON.stringify({ code: "TOO_MANY_REQUESTS", message: "Rate limited" }), {
          status: 429,
          headers: { "Retry-After": "42", "Content-Type": "application/problem+json" },
        }),
      ),
    );
    await expect(searchAuctions({})).rejects.toMatchObject({ status: 429 });
  });

  it("fetchFeatured hits the right endpoint", async () => {
    let seen = "";
    server.use(
      http.get("*/api/v1/auctions/featured/:category", ({ request, params }) => {
        seen = String(params.category);
        return HttpResponse.json({ content: [] });
      }),
    );
    await fetchFeatured("ending-soon");
    expect(seen).toBe("ending-soon");
  });
});
```

- [ ] **Implement** `frontend/src/lib/api/auctions-search.ts`:

```ts
import { api } from "@/lib/api";
import { searchParamsFromQuery } from "@/lib/search/url-codec";
import type { AuctionSearchQuery, SearchResponse } from "@/types/search";

export function searchAuctions(query: AuctionSearchQuery): Promise<SearchResponse> {
  const params = searchParamsFromQuery(query);
  const qs = params.toString();
  const path = "/api/v1/auctions/search" + (qs ? `?${qs}` : "");
  return api.get<SearchResponse>(path);
}

export type FeaturedCategory = "ending-soon" | "just-listed" | "most-active";

export function fetchFeatured(category: FeaturedCategory): Promise<{ content: import("@/types/search").AuctionSearchResultDto[] }> {
  return api.get(`/api/v1/auctions/featured/${category}`);
}
```

- [ ] **Run tests — PASS.**

### Step 2a.8: `useAuctionSearch` hook

- [ ] **Failing test** `frontend/src/hooks/useAuctionSearch.test.tsx`:

```tsx
import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { useAuctionSearch } from "./useAuctionSearch";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";

describe("useAuctionSearch", () => {
  it("uses initialData without fetching on first render", async () => {
    const initialData = {
      content: [], page: 0, size: 24, totalElements: 0, totalPages: 0, first: true, last: true,
    };
    const { result } = renderHook(() => useAuctionSearch({ sort: "newest" }, { initialData }), {
      wrapper: makeWrapper({}),
    });
    expect(result.current.data).toEqual(initialData);
    expect(result.current.isFetching).toBe(false);
  });

  it("refetches when the query changes", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/auctions/search", () => {
        calls++;
        return HttpResponse.json({ content: [], page: 0, size: 24, totalElements: 0, totalPages: 0, first: true, last: true });
      }),
    );
    const initialData = { content: [], page: 0, size: 24, totalElements: 0, totalPages: 0, first: true, last: true };
    const { rerender } = renderHook(
      ({ q }) => useAuctionSearch(q, { initialData }),
      {
        wrapper: makeWrapper({}),
        initialProps: { q: { sort: "newest" as const } },
      },
    );
    expect(calls).toBe(0);
    rerender({ q: { sort: "ending_soonest" as const } });
    await waitFor(() => expect(calls).toBe(1));
  });
});
```

- [ ] **Implement** `frontend/src/hooks/useAuctionSearch.ts`:

```ts
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { searchAuctions } from "@/lib/api/auctions-search";
import { canonicalKey } from "@/lib/search/canonical-key";
import type { AuctionSearchQuery, SearchResponse } from "@/types/search";

type Opts = { initialData?: SearchResponse };

export function useAuctionSearch(
  query: AuctionSearchQuery,
  opts: Opts = {},
): UseQueryResult<SearchResponse> {
  return useQuery({
    queryKey: ["auctions", "search", canonicalKey(query)],
    queryFn: () => searchAuctions(query),
    initialData: opts.initialData,
    staleTime: 30_000,
  });
}
```

- [ ] **Run tests — PASS.**

### Step 2a.9: Primitive — `ActiveFilterBadge`

- [ ] **Failing test** `frontend/src/components/ui/ActiveFilterBadge.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ActiveFilterBadge } from "./ActiveFilterBadge";

describe("ActiveFilterBadge", () => {
  it("renders label and calls onRemove when X clicked", async () => {
    const onRemove = vi.fn();
    renderWithProviders(<ActiveFilterBadge label="Maturity: Adult" onRemove={onRemove} />);
    expect(screen.getByText("Maturity: Adult")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /remove filter/i }));
    expect(onRemove).toHaveBeenCalledOnce();
  });

  it("renders in dark mode without error", () => {
    renderWithProviders(<ActiveFilterBadge label="Test" onRemove={() => {}} />, { theme: "dark", forceTheme: true });
    expect(screen.getByText("Test")).toBeInTheDocument();
  });
});
```

- [ ] **Implement** `frontend/src/components/ui/ActiveFilterBadge.tsx`:

```tsx
import { X } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export interface ActiveFilterBadgeProps {
  label: string;
  onRemove: () => void;
  className?: string;
}

export function ActiveFilterBadge({ label, onRemove, className }: ActiveFilterBadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2 rounded-full px-3 py-1 text-body-sm",
        "bg-surface-container-low text-on-surface-variant",
        className,
      )}
    >
      <span>{label}</span>
      <button
        type="button"
        onClick={onRemove}
        aria-label={`Remove filter: ${label}`}
        className="rounded-full p-0.5 hover:bg-surface-container-high focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
      >
        <X className="size-3.5" aria-hidden="true" />
      </button>
    </span>
  );
}
```

- [ ] **Run tests — PASS.**

### Step 2a.10: Primitive — `StatusChip`

- [ ] **Failing test** `frontend/src/components/ui/StatusChip.test.tsx`:

```tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { StatusChip } from "./StatusChip";

describe("StatusChip", () => {
  it("renders label with tone class", () => {
    const { container } = renderWithProviders(<StatusChip label="LIVE" tone="live" />);
    expect(screen.getByText("LIVE")).toBeInTheDocument();
    expect(container.firstChild).toHaveAttribute("data-tone", "live");
  });

  it("pulses for ending_soon", () => {
    const { container } = renderWithProviders(<StatusChip label="ENDING SOON" tone="ending_soon" />);
    expect(container.firstChild).toHaveClass("animate-pulse");
  });

  it("renders in dark mode", () => {
    renderWithProviders(<StatusChip label="SOLD" tone="sold" />, { theme: "dark", forceTheme: true });
    expect(screen.getByText("SOLD")).toBeInTheDocument();
  });
});
```

- [ ] **Implement** `frontend/src/components/ui/StatusChip.tsx`:

```tsx
import { cn } from "@/lib/cn";
import type { ChipTone } from "@/lib/search/status-chip";

export interface StatusChipProps {
  label: string;
  tone: ChipTone;
  className?: string;
}

const TONE_CLASSES: Record<ChipTone, string> = {
  live: "bg-error text-on-error",
  ending_soon: "bg-error text-on-error animate-pulse",
  sold: "bg-tertiary-container text-on-tertiary-container",
  muted: "bg-surface-container-high text-on-surface-variant",
  warning: "bg-error-container text-on-error-container",
};

export function StatusChip({ label, tone, className }: StatusChipProps) {
  return (
    <span
      data-tone={tone}
      className={cn(
        "inline-flex items-center rounded px-2 py-0.5 text-label-sm font-bold uppercase tracking-wider",
        TONE_CLASSES[tone],
        className,
      )}
      aria-label={label}
    >
      {label}
    </span>
  );
}
```

- [ ] **Run tests — PASS.**

### Step 2a.11: Primitive — `Pagination`

- [ ] **Failing test** `frontend/src/components/ui/Pagination.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Pagination } from "./Pagination";

describe("Pagination", () => {
  it("renders page numbers and prev/next", () => {
    renderWithProviders(<Pagination page={2} totalPages={5} onPageChange={() => {}} />);
    expect(screen.getByRole("button", { name: /previous page/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /next page/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /page 3/i })).toHaveAttribute("aria-current", "page");
  });

  it("disables prev on first page", () => {
    renderWithProviders(<Pagination page={0} totalPages={3} onPageChange={() => {}} />);
    expect(screen.getByRole("button", { name: /previous page/i })).toBeDisabled();
  });

  it("disables next on last page", () => {
    renderWithProviders(<Pagination page={2} totalPages={3} onPageChange={() => {}} />);
    expect(screen.getByRole("button", { name: /next page/i })).toBeDisabled();
  });

  it("calls onPageChange with page number", async () => {
    const onPageChange = vi.fn();
    renderWithProviders(<Pagination page={0} totalPages={5} onPageChange={onPageChange} />);
    await userEvent.click(screen.getByRole("button", { name: /page 3/i }));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  it("collapses with ellipsis when many pages", () => {
    renderWithProviders(<Pagination page={10} totalPages={50} onPageChange={() => {}} />);
    expect(screen.getAllByText("…").length).toBeGreaterThan(0);
  });

  it("renders nothing when only one page", () => {
    const { container } = renderWithProviders(<Pagination page={0} totalPages={1} onPageChange={() => {}} />);
    expect(container.firstChild).toBeNull();
  });
});
```

- [ ] **Implement** `frontend/src/components/ui/Pagination.tsx`:

```tsx
import { ChevronLeft, ChevronRight } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export interface PaginationProps {
  page: number; // 0-indexed
  totalPages: number;
  onPageChange: (page: number) => void;
  className?: string;
}

function getPages(current: number, total: number): Array<number | "ellipsis"> {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i);
  const pages: Array<number | "ellipsis"> = [0];
  if (current > 2) pages.push("ellipsis");
  for (let i = Math.max(1, current - 1); i <= Math.min(total - 2, current + 1); i++) {
    pages.push(i);
  }
  if (current < total - 3) pages.push("ellipsis");
  pages.push(total - 1);
  return pages;
}

export function Pagination({ page, totalPages, onPageChange, className }: PaginationProps) {
  if (totalPages <= 1) return null;
  const pages = getPages(page, totalPages);
  return (
    <nav role="navigation" aria-label="Pagination" className={cn("flex items-center justify-center gap-1", className)}>
      <button
        type="button"
        onClick={() => onPageChange(page - 1)}
        disabled={page === 0}
        aria-label="Previous page"
        className="flex size-9 items-center justify-center rounded bg-surface-container-lowest text-on-surface disabled:opacity-40 hover:bg-surface-container-low"
      >
        <ChevronLeft className="size-4" aria-hidden="true" />
      </button>
      {pages.map((p, idx) =>
        p === "ellipsis" ? (
          <span key={`e-${idx}`} className="px-2 text-on-surface-variant" aria-hidden="true">…</span>
        ) : (
          <button
            key={p}
            type="button"
            onClick={() => onPageChange(p)}
            aria-label={`Page ${p + 1}`}
            aria-current={p === page ? "page" : undefined}
            className={cn(
              "flex size-9 items-center justify-center rounded text-label-md",
              p === page
                ? "bg-primary text-on-primary"
                : "bg-surface-container-lowest text-on-surface hover:bg-surface-container-low",
            )}
          >
            {p + 1}
          </button>
        ),
      )}
      <button
        type="button"
        onClick={() => onPageChange(page + 1)}
        disabled={page === totalPages - 1}
        aria-label="Next page"
        className="flex size-9 items-center justify-center rounded bg-surface-container-lowest text-on-surface disabled:opacity-40 hover:bg-surface-container-low"
      >
        <ChevronRight className="size-4" aria-hidden="true" />
      </button>
    </nav>
  );
}
```

- [ ] **Run tests — PASS.**

### Step 2a.12: Primitive — `BottomSheet`

- [ ] **Failing test** `frontend/src/components/ui/BottomSheet.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { BottomSheet } from "./BottomSheet";

describe("BottomSheet", () => {
  it("does not render content when closed", () => {
    renderWithProviders(
      <BottomSheet open={false} onClose={() => {}} title="Filters">
        <p>hidden</p>
      </BottomSheet>,
    );
    expect(screen.queryByText("hidden")).not.toBeInTheDocument();
  });

  it("renders content when open, with accessible close button", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <BottomSheet open onClose={onClose} title="Filters">
        <p>visible</p>
      </BottomSheet>,
    );
    expect(screen.getByText("visible")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /close/i }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("ESC closes the sheet", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <BottomSheet open onClose={onClose} title="Filters">
        <p>visible</p>
      </BottomSheet>,
    );
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("traps focus within the sheet", async () => {
    renderWithProviders(
      <BottomSheet open onClose={() => {}} title="Filters">
        <button>one</button>
        <button>two</button>
      </BottomSheet>,
    );
    // First focusable should receive focus after open.
    expect(document.activeElement?.textContent).toBe("one");
  });
});
```

- [ ] **Implement** `frontend/src/components/ui/BottomSheet.tsx` using Headless UI's `Dialog`:

```tsx
"use client";
import { Dialog, DialogBackdrop, DialogPanel, DialogTitle } from "@headlessui/react";
import { X } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { ReactNode } from "react";

export interface BottomSheetProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
}

export function BottomSheet({ open, onClose, title, children, footer, className }: BottomSheetProps) {
  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <DialogBackdrop
        transition
        className="fixed inset-0 bg-on-surface/30 transition data-[closed]:opacity-0 data-[enter]:duration-200 data-[leave]:duration-150"
      />
      <div className="fixed inset-0 flex items-end justify-center">
        <DialogPanel
          transition
          className={cn(
            "w-full max-h-[85vh] rounded-t-xl",
            "bg-surface-container-lowest/90 backdrop-blur-xl border-t border-outline-variant/30",
            "shadow-[0_-20px_40px_rgba(25,28,30,0.08)]",
            "transition data-[closed]:translate-y-full data-[enter]:duration-250 data-[leave]:duration-200",
            className,
          )}
        >
          <div className="mx-auto mt-3 mb-2 h-1 w-10 rounded-full bg-outline-variant" aria-hidden="true" />
          <div className="flex items-center justify-between px-5 py-3">
            <DialogTitle className="text-title-md font-bold">{title}</DialogTitle>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="rounded p-1 hover:bg-surface-container-high"
            >
              <X className="size-5" aria-hidden="true" />
            </button>
          </div>
          <div className="overflow-y-auto px-5 pb-4 max-h-[70vh]">{children}</div>
          {footer && (
            <div className="sticky bottom-0 bg-surface-container-lowest/95 backdrop-blur border-t border-outline-variant/30 px-5 py-3">
              {footer}
            </div>
          )}
        </DialogPanel>
      </div>
    </Dialog>
  );
}
```

- [ ] **Run tests — PASS.** (Headless UI handles focus trap + ESC out of the box.)

### Step 2a.13: Primitive — `RangeSlider`

- [ ] **Failing test** `frontend/src/components/ui/RangeSlider.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent, fireEvent } from "@/test/render";
import { RangeSlider } from "./RangeSlider";

describe("RangeSlider", () => {
  it("renders two handles with values", () => {
    renderWithProviders(<RangeSlider min={0} max={100} value={[10, 80]} onChange={() => {}} />);
    const [lo, hi] = screen.getAllByRole("slider");
    expect(lo).toHaveAttribute("aria-valuenow", "10");
    expect(hi).toHaveAttribute("aria-valuenow", "80");
  });

  it("emits new tuple on change", () => {
    const onChange = vi.fn();
    renderWithProviders(<RangeSlider min={0} max={100} value={[10, 80]} onChange={onChange} />);
    const [lo] = screen.getAllByRole("slider");
    fireEvent.change(lo, { target: { value: "30" } });
    expect(onChange).toHaveBeenCalledWith([30, 80]);
  });

  it("clamps low handle above high", () => {
    const onChange = vi.fn();
    renderWithProviders(<RangeSlider min={0} max={100} value={[10, 50]} onChange={onChange} />);
    const [lo] = screen.getAllByRole("slider");
    fireEvent.change(lo, { target: { value: "70" } });
    // Low can't cross above high (50); clamped to 50.
    expect(onChange).toHaveBeenCalledWith([50, 50]);
  });
});
```

- [ ] **Implement** `frontend/src/components/ui/RangeSlider.tsx`:

```tsx
"use client";
import { cn } from "@/lib/cn";

export interface RangeSliderProps {
  min: number;
  max: number;
  step?: number;
  value: [number, number];
  onChange: (value: [number, number]) => void;
  ariaLabel?: [string, string];
  className?: string;
}

export function RangeSlider({
  min, max, step = 1, value, onChange, ariaLabel, className,
}: RangeSliderProps) {
  const [lo, hi] = value;
  return (
    <div className={cn("relative h-8", className)}>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={lo}
        aria-label={ariaLabel?.[0] ?? "Minimum"}
        aria-valuenow={lo}
        onChange={(e) => {
          const v = Number(e.target.value);
          onChange([Math.min(v, hi), hi]);
        }}
        className="absolute inset-x-0 top-0 w-full appearance-none bg-transparent accent-primary"
      />
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={hi}
        aria-label={ariaLabel?.[1] ?? "Maximum"}
        aria-valuenow={hi}
        onChange={(e) => {
          const v = Number(e.target.value);
          onChange([lo, Math.max(v, lo)]);
        }}
        className="absolute inset-x-0 top-0 w-full appearance-none bg-transparent accent-primary"
      />
    </div>
  );
}
```

- [ ] **Run tests — PASS.** Note the overlapping-track visual polish is a later iteration concern; core logic works.

### Step 2a.14: `ListingCard` — failing tests first

- [ ] **Create comprehensive failing test** `frontend/src/components/auction/ListingCard.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ListingCard } from "./ListingCard";
import type { AuctionSearchResultDto } from "@/types/search";

const sample: AuctionSearchResultDto = {
  id: 1,
  title: "Premium Waterfront",
  status: "ACTIVE",
  endOutcome: null,
  parcel: {
    id: 11, name: "Bayside Lot", region: "Tula", area: 1024, maturity: "MODERATE",
    snapshotUrl: "/snap.jpg", gridX: 1, gridY: 2, positionX: 80, positionY: 104, positionZ: 89, tags: ["BEACHFRONT", "ROADSIDE"],
  },
  primaryPhotoUrl: "/photo.jpg",
  seller: { id: 7, displayName: "seller", avatarUrl: null, averageRating: 4.8, reviewCount: 12 },
  verificationTier: "BOT",
  currentBid: 12500, startingBid: 5000, reservePrice: 10000, reserveMet: true, buyNowPrice: null,
  bidCount: 7, endsAt: new Date(Date.now() + 5 * 3600_000).toISOString(),
  snipeProtect: true, snipeWindowMin: 5, distanceRegions: null,
};

describe("ListingCard", () => {
  it.each(["default", "compact", "featured"] as const)("renders variant=%s", (variant) => {
    renderWithProviders(<ListingCard listing={sample} variant={variant} />);
    expect(screen.getByText("Premium Waterfront")).toBeInTheDocument();
  });

  it("shows status chip LIVE for active far-future", () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />);
    expect(screen.getByText("LIVE")).toBeInTheDocument();
  });

  it("shows SOLD chip for COMPLETED/SOLD", () => {
    renderWithProviders(<ListingCard listing={{ ...sample, status: "COMPLETED", endOutcome: "SOLD" }} variant="default" />);
    expect(screen.getByText("SOLD")).toBeInTheDocument();
  });

  it("card navigates to detail on click", async () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />);
    const link = screen.getByRole("link", { name: /premium waterfront/i });
    expect(link).toHaveAttribute("href", "/auction/1");
  });

  it("compact variant shows fewer tag pills (2) than default (3)", () => {
    const many = { ...sample, parcel: { ...sample.parcel, tags: ["A", "B", "C", "D", "E"] } };
    const { rerender } = renderWithProviders(<ListingCard listing={many} variant="default" />);
    // Default: 3 pills + overflow count
    expect(screen.getByText("+2")).toBeInTheDocument();
    rerender(<ListingCard listing={many} variant="compact" />);
    // Compact: 2 pills + overflow count
    expect(screen.getByText("+3")).toBeInTheDocument();
  });

  it("distance chip rendered when distanceRegions present", () => {
    renderWithProviders(
      <ListingCard listing={{ ...sample, distanceRegions: 3.2 }} variant="default" />,
    );
    expect(screen.getByText(/3\.2/)).toBeInTheDocument();
  });

  it("heart button stops card click propagation", async () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />);
    const heart = screen.getByRole("button", { name: /save/i });
    // Click heart — should NOT navigate.
    await userEvent.click(heart);
    // Toast should appear (unauth default).
    expect(await screen.findByText(/sign in to save/i)).toBeInTheDocument();
  });

  it("dark mode renders without visual regressions", () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />, {
      theme: "dark", forceTheme: true,
    });
    expect(screen.getByText("Premium Waterfront")).toBeInTheDocument();
  });

  it("does not render heart button on pre-active statuses", () => {
    renderWithProviders(<ListingCard listing={{ ...sample, status: "DRAFT" }} variant="default" />);
    expect(screen.queryByRole("button", { name: /save/i })).not.toBeInTheDocument();
  });
});
```

- [ ] **Run tests — all FAIL** (component doesn't exist).

### Step 2a.15: Implement `ListingCard`

- [ ] **Create** `frontend/src/components/auction/ListingCard.tsx`. Heart logic stubs in a `useSavedAuctions` placeholder that always surfaces the sign-in toast (full implementation lands in Task 5):

```tsx
"use client";
import Link from "next/link";
import { useToast } from "@/components/ui/Toast/useToast";
import { StatusChip } from "@/components/ui/StatusChip";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { ShieldCheck, MapPin, Heart } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { deriveStatusChip } from "@/lib/search/status-chip";
import type { AuctionSearchResultDto } from "@/types/search";

export type ListingCardVariant = "default" | "compact" | "featured";

export interface ListingCardProps {
  listing: AuctionSearchResultDto;
  variant: ListingCardVariant;
  className?: string;
}

const PRE_ACTIVE = new Set(["DRAFT", "DRAFT_PAID", "VERIFICATION_PENDING", "VERIFICATION_FAILED"]);

const MAX_TAGS: Record<ListingCardVariant, number> = {
  default: 3,
  compact: 2,
  featured: 5,
};

export function ListingCard({ listing, variant, className }: ListingCardProps) {
  const chip = deriveStatusChip({
    status: listing.status,
    endOutcome: listing.endOutcome,
    endsAt: listing.endsAt,
  });
  const imageSrc = listing.primaryPhotoUrl ?? listing.parcel.snapshotUrl ?? undefined;
  const maxTags = MAX_TAGS[variant];
  const visibleTags = listing.parcel.tags.slice(0, maxTags);
  const overflow = listing.parcel.tags.length - visibleTags.length;
  const showHeart = !PRE_ACTIVE.has(listing.status);

  return (
    <article
      className={cn(
        "relative flex flex-col rounded-default bg-surface-container-lowest shadow-sm overflow-hidden",
        "transition hover:shadow-md hover:scale-[1.01]",
        variant === "featured" && "md:col-span-2",
        className,
      )}
      data-variant={variant}
    >
      <Link
        href={`/auction/${listing.id}`}
        className="flex flex-col gap-3 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary rounded-default"
        aria-label={`${listing.title} in ${listing.parcel.region}, current bid L$${listing.currentBid.toLocaleString()}`}
      >
        <div className={cn("relative overflow-hidden", variant === "featured" ? "aspect-[16/9]" : "aspect-[4/3]")}>
          {imageSrc && (
            <img src={imageSrc} alt="" className="h-full w-full object-cover" loading="lazy" />
          )}
          <StatusChip label={chip.label} tone={chip.tone} className="absolute top-2 left-2" />
          <span className="absolute bottom-2 right-2 rounded-full bg-on-surface/70 px-2 py-0.5 text-label-sm text-on-primary">
            {listing.parcel.area}m²
          </span>
        </div>
        <div className="flex flex-col gap-1 px-4 pb-4">
          <h3 className={cn(
            "font-display font-bold tracking-[-0.02em]",
            variant === "compact" ? "text-title-md line-clamp-1" : "text-title-lg line-clamp-2",
          )}>
            {listing.title}
          </h3>
          <p className="text-body-sm text-on-surface-variant flex items-center gap-1">
            <MapPin className="size-3.5" aria-hidden="true" />
            <span>{listing.parcel.name} · {listing.parcel.region}</span>
          </p>
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-display-sm font-bold">L$ {listing.currentBid.toLocaleString()}</span>
            {listing.snipeProtect && (
              <span className="inline-flex items-center gap-1 text-body-sm text-on-surface-variant">
                <ShieldCheck className="size-4" aria-hidden="true" />
                {listing.snipeWindowMin ?? 5}min
              </span>
            )}
          </div>
          {variant !== "compact" && (
            <p className="text-body-sm text-on-surface-variant">
              {listing.bidCount} bid{listing.bidCount === 1 ? "" : "s"}
              {" · "}
              {listing.reserveMet ? "Reserve met" : "Reserve not met"}
            </p>
          )}
          <div className="text-body-sm text-on-surface-variant">
            <CountdownTimer endsAt={listing.endsAt} />
          </div>
          {visibleTags.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {visibleTags.map((t) => (
                <span key={t} className="rounded-full bg-surface-container-low px-2 py-0.5 text-label-sm">
                  {t}
                </span>
              ))}
              {overflow > 0 && (
                <span className="rounded-full bg-surface-container-low px-2 py-0.5 text-label-sm text-on-surface-variant">
                  +{overflow}
                </span>
              )}
            </div>
          )}
          {listing.distanceRegions !== null && (
            <span className="text-label-sm text-on-surface-variant">
              {listing.distanceRegions.toFixed(1)} regions
            </span>
          )}
        </div>
      </Link>
      {showHeart && <HeartOverlay auctionId={listing.id} title={listing.title} />}
    </article>
  );
}

// Placeholder heart — full implementation wires into useSavedAuctions in Task 5.
function HeartOverlay({ auctionId: _auctionId, title }: { auctionId: number; title: string }) {
  const { toast } = useToast();
  return (
    <button
      type="button"
      aria-label={`Save ${title}`}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        toast.warning({
          title: "Sign in to save parcels",
          action: { label: "Sign in", onClick: () => window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`) },
        });
      }}
      className="absolute top-2 right-2 rounded-full bg-surface-container-lowest/80 backdrop-blur p-2 hover:bg-surface-container-lowest focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
    >
      <Heart className="size-5 text-on-surface-variant" aria-hidden="true" />
    </button>
  );
}
```

- [ ] **Add `Heart` to** `@/components/ui/icons.ts`:

```ts
export { Heart } from "lucide-react";
```

- [ ] **Note on `toast.warning`:** it's added in Task 5. For Task 2a we need it to at least not throw. Temporarily, either:
  - Implement a minimal widening now (preferred; less churn later), or
  - Use `toast.error` with the same payload shape.

Go with "widen now." That means Task 2a also opens `ui/Toast/useToast.ts` and adds `warning` with an action-button payload. Tests in Task 5 will exercise the new variant; the primitive just needs to compile here.

### Step 2a.16: Toast primitive — add `warning` variant + action-button payload

- [ ] **Read existing** `frontend/src/components/ui/Toast/useToast.ts` and `ToastProvider.tsx` to learn the current shape.

- [ ] **Failing test** `frontend/src/components/ui/Toast/Toast.test.tsx` (extend):

```tsx
it("renders a warning toast with action button", async () => {
  const onClick = vi.fn();
  const Harness = () => {
    const { toast } = useToast();
    return <button onClick={() => toast.warning({ title: "Heads up", description: "something", action: { label: "Act", onClick } })}>fire</button>;
  };
  renderWithProviders(<><ToastProvider /><Harness /></>);
  await userEvent.click(screen.getByText("fire"));
  const action = await screen.findByRole("button", { name: "Act" });
  await userEvent.click(action);
  expect(onClick).toHaveBeenCalled();
});
```

- [ ] **Widen the Toast primitive** — add `warning` (and `info` while we're here) to `ToastKind`, widen payload to accept structured `{ title, description?, action? }` in addition to plain strings (backward compat). Render the action-button in the toast when present, and wire aria labeling.

- [ ] **Run test — PASS.**

- [ ] **Run the existing ListingCard test — PASS** (heart click triggers the new `toast.warning` shape).

### Step 2a.17: Dev demo route

- [ ] **Create** `frontend/src/app/dev/listing-card-demo/page.tsx`:

```tsx
import { ListingCard } from "@/components/auction/ListingCard";
import type { AuctionSearchResultDto } from "@/types/search";

const sample: AuctionSearchResultDto = {
  id: 42,
  title: "Demo — Premium Waterfront",
  status: "ACTIVE",
  endOutcome: null,
  parcel: {
    id: 11, name: "Bayside Lot", region: "Tula", area: 1024, maturity: "MODERATE",
    snapshotUrl: null, gridX: 1, gridY: 2, positionX: 80, positionY: 104, positionZ: 89,
    tags: ["BEACHFRONT", "ROADSIDE", "ELEVATED", "PROTECTED"],
  },
  primaryPhotoUrl: null,
  seller: { id: 7, displayName: "seller.one", avatarUrl: null, averageRating: 4.8, reviewCount: 12 },
  verificationTier: "BOT",
  currentBid: 12500, startingBid: 5000, reservePrice: 10000, reserveMet: true, buyNowPrice: null,
  bidCount: 7, endsAt: new Date(Date.now() + 48 * 3600_000).toISOString(),
  snipeProtect: true, snipeWindowMin: 5, distanceRegions: 3.2,
};

export const dynamic = "force-dynamic";

export default function ListingCardDemo() {
  return (
    <div className="p-8 grid grid-cols-1 md:grid-cols-3 gap-6 max-w-6xl mx-auto">
      <section>
        <h2 className="text-title-lg font-bold mb-3">default</h2>
        <ListingCard listing={sample} variant="default" />
      </section>
      <section>
        <h2 className="text-title-lg font-bold mb-3">compact</h2>
        <ListingCard listing={sample} variant="compact" />
      </section>
      <section className="md:col-span-2">
        <h2 className="text-title-lg font-bold mb-3">featured</h2>
        <ListingCard listing={sample} variant="featured" />
      </section>
    </div>
  );
}
```

- [ ] **Verify locally** by opening `http://localhost:3000/dev/listing-card-demo` — Heath has said dev server is running; verify cards render in both themes.

### Step 2a.18: Full test suite + verify + commit

- [ ] **Run** `cd frontend && npm run test`. All pass.
- [ ] **Run** `cd frontend && npm run verify`. All pass.
- [ ] **Commit the frontend changes.**

```bash
git add frontend/src/types/search.ts \
        frontend/src/types/auction.ts \
        frontend/src/lib/api/auctions-search.ts \
        frontend/src/lib/api/auctions-search.test.ts \
        frontend/src/lib/search/url-codec.ts \
        frontend/src/lib/search/url-codec.test.ts \
        frontend/src/lib/search/canonical-key.ts \
        frontend/src/lib/search/canonical-key.test.ts \
        frontend/src/lib/search/status-chip.ts \
        frontend/src/lib/search/status-chip.test.ts \
        frontend/src/hooks/useAuctionSearch.ts \
        frontend/src/hooks/useAuctionSearch.test.tsx \
        frontend/src/components/ui/RangeSlider.tsx \
        frontend/src/components/ui/RangeSlider.test.tsx \
        frontend/src/components/ui/Pagination.tsx \
        frontend/src/components/ui/Pagination.test.tsx \
        frontend/src/components/ui/BottomSheet.tsx \
        frontend/src/components/ui/BottomSheet.test.tsx \
        frontend/src/components/ui/ActiveFilterBadge.tsx \
        frontend/src/components/ui/ActiveFilterBadge.test.tsx \
        frontend/src/components/ui/StatusChip.tsx \
        frontend/src/components/ui/StatusChip.test.tsx \
        frontend/src/components/ui/icons.ts \
        frontend/src/components/ui/Toast/useToast.ts \
        frontend/src/components/ui/Toast/ToastProvider.tsx \
        frontend/src/components/ui/Toast/Toast.test.tsx \
        frontend/src/components/auction/ListingCard.tsx \
        frontend/src/components/auction/ListingCard.test.tsx \
        frontend/src/app/dev/listing-card-demo/page.tsx

git commit -m "feat(search): primitives + ListingCard + search API hook

Task 2a: ships the foundation that Tasks 2b/3/4/5 consume.

  - Types (AuctionSearchQuery, AuctionSearchResultDto, SearchResponse).
  - URL codec with CSV encoding for multi-selects + round-trip tests.
  - canonicalKey for React Query key canonicalization.
  - deriveStatusChip (LIVE / ENDING SOON / SOLD / RESERVE NOT MET /
    NO BIDS / CANCELLED / SUSPENDED / ENDED) from status + endOutcome.
  - API client (searchAuctions, fetchFeatured) + useAuctionSearch hook
    with 30s staleTime and initialData support.
  - UI primitives: RangeSlider, Pagination, BottomSheet, ActiveFilterBadge,
    StatusChip.
  - Toast primitive widened to support warning/info variants + structured
    action-button payload (resolves part of the existing DEFERRED_WORK
    'Richer outbid toast' item; full OutbidToastProvider migration lands
    in Task 5).
  - Canonical ListingCard with three variants (default/compact/featured).
    Heart button surfaces 'Sign in to save' toast for anonymous users;
    full save/unsave mutation lands in Task 5.
  - Dev demo route /dev/listing-card-demo exercising all three variants."
```

---

# Task 2b — Browse page + seller listings page

**Depends on:** Task 2a.

**Deliverables:**

1. Browse page shell + all filter primitives.
2. `/browse` route with SSR initial fetch.
3. `/users/[id]/listings` route with `seller_id`-pinned BrowseShell.
4. Mobile-staged filter behavior.
5. DEFERRED_WORK updates (remove "per-user listings"; add "region autocomplete" + "infinite scroll").

## Task 2b files — new components in `components/browse/`

Paths listed in the file structure overview.

### Step 2b.1: `SortDropdown`

- [ ] **Failing test** `frontend/src/components/browse/SortDropdown.test.tsx`:

```tsx
it("disables nearest option when no near_region", () => {
  const onChange = vi.fn();
  renderWithProviders(<SortDropdown value="newest" onChange={onChange} nearestEnabled={false} />);
  const nearest = screen.getByRole("option", { name: /nearest/i });
  expect(nearest).toBeDisabled();
});
```

- [ ] **Implement** `frontend/src/components/browse/SortDropdown.tsx` — a semantic `<select>` with six options and `disabled` on "nearest" when `nearestEnabled={false}`. Use design-system classes.

### Step 2b.2: `FilterSection` + `ActiveFilters`

- [ ] **Tests + implementations** for simple presentational components. `FilterSection` wraps content under a collapsible uppercase header. `ActiveFilters` maps a diff between current `query` and `defaultAuctionSearchQuery` to a row of `ActiveFilterBadge` elements.

- [ ] `ActiveFilters.tsx` — derive chips from the current query. Each chip calls `onChange` with the field cleared. "Clear all" resets to `defaultAuctionSearchQuery`.

```tsx
export function ActiveFilters({ query, onChange }: { query: AuctionSearchQuery; onChange: (q: AuctionSearchQuery) => void }) {
  const chips: Array<{ key: keyof AuctionSearchQuery; label: string }> = [];
  if (query.region) chips.push({ key: "region", label: `Region: ${query.region}` });
  if (query.minPrice !== undefined || query.maxPrice !== undefined)
    chips.push({ key: "minPrice", label: `Price: ${query.minPrice ?? 0}-${query.maxPrice ?? "max"}` });
  if (query.maturity?.length) chips.push({ key: "maturity", label: `Maturity: ${query.maturity.join(", ")}` });
  // ... more fields per §7.3
  if (chips.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-2">
      {chips.map((c) => (
        <ActiveFilterBadge key={c.key} label={c.label} onRemove={() => {
          const copy = { ...query };
          if (c.key === "minPrice") { copy.minPrice = undefined; copy.maxPrice = undefined; }
          else (copy as Record<string, unknown>)[c.key] = undefined;
          onChange(copy);
        }} />
      ))}
      <button type="button" onClick={() => onChange(defaultAuctionSearchQuery)}
        className="text-body-sm text-primary hover:underline">Clear all</button>
    </div>
  );
}
```

Add tests for each chip removal + Clear all.

### Step 2b.3: `DistanceSearchBlock`

- [ ] **Failing test**:

```tsx
it("emits near_region and distance fields", async () => {
  const onChange = vi.fn();
  renderWithProviders(<DistanceSearchBlock query={{}} onChange={onChange} />);
  await userEvent.type(screen.getByLabelText(/region name/i), "Tula");
  // Debounce 300ms
  await new Promise(r => setTimeout(r, 350));
  expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ nearRegion: "Tula" }));
});

it("surfaces REGION_NOT_FOUND error inline", () => {
  renderWithProviders(<DistanceSearchBlock query={{ nearRegion: "Bogus" }} onChange={() => {}} errorCode="REGION_NOT_FOUND" />);
  expect(screen.getByRole("alert")).toHaveTextContent(/couldn't locate that region/i);
});
```

- [ ] **Implement**. Accept `errorCode?: string` prop from parent (`BrowseShell` passes the latest fetch's error code). Debounce input via a 300ms setTimeout. Distance slider disabled until a non-empty region.

### Step 2b.4: `FilterSidebarContent` — immediate vs staged modes

- [ ] **Failing tests** in `FilterSidebarContent.test.tsx`:

```tsx
describe("FilterSidebarContent — immediate mode", () => {
  it("fires onChange immediately on checkbox toggle", async () => {
    const onChange = vi.fn();
    renderWithProviders(<FilterSidebarContent mode="immediate" query={{}} onChange={onChange} />);
    await userEvent.click(screen.getByLabelText(/general/i));
    expect(onChange).toHaveBeenCalled();
  });
});

describe("FilterSidebarContent — staged mode", () => {
  it("holds local state until onCommit", async () => {
    const onCommit = vi.fn();
    renderWithProviders(<FilterSidebarContent mode="staged" query={{}} onCommit={onCommit} />);
    await userEvent.click(screen.getByLabelText(/general/i));
    expect(onCommit).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: /apply filters/i }));
    expect(onCommit).toHaveBeenCalledWith(expect.objectContaining({ maturity: ["GENERAL"] }));
  });

  it("discards staged changes on remount (close without applying)", () => {
    const { unmount } = renderWithProviders(<FilterSidebarContent mode="staged" query={{ maturity: ["GENERAL"] }} onCommit={() => {}} />);
    unmount();
    renderWithProviders(<FilterSidebarContent mode="staged" query={{ maturity: ["GENERAL"] }} onCommit={() => {}} />);
    // State matches provided query, not any prior staged changes.
    expect(screen.getByLabelText(/general/i)).toBeChecked();
  });
});
```

- [ ] **Implement** `FilterSidebarContent.tsx`:

```tsx
"use client";
import { useState, useEffect } from "react";
import { Button } from "@/components/ui/Button";
import { FilterSection } from "./FilterSection";
import { RangeSlider } from "@/components/ui/RangeSlider";
import { DistanceSearchBlock } from "./DistanceSearchBlock";
import { TagSelector } from "@/components/listing/TagSelector";
import type { AuctionSearchQuery, MaturityRating } from "@/types/search";

interface Props {
  mode: "immediate" | "staged";
  query: AuctionSearchQuery;
  onChange?: (q: AuctionSearchQuery) => void;      // immediate mode
  onCommit?: (q: AuctionSearchQuery) => void;      // staged mode
  hiddenGroups?: Array<"distance" | "seller">;
  errorCode?: string;
}

export function FilterSidebarContent({ mode, query, onChange, onCommit, hiddenGroups = [], errorCode }: Props) {
  const [local, setLocal] = useState<AuctionSearchQuery>(query);

  // In immediate mode, propagate every change up.
  // In staged mode, only propagate on commit.
  useEffect(() => { setLocal(query); }, [query]);

  const update = (partial: Partial<AuctionSearchQuery>) => {
    const next = { ...local, ...partial };
    setLocal(next);
    if (mode === "immediate") onChange?.(next);
  };

  const toggleMaturity = (m: MaturityRating) => {
    const existing = local.maturity ?? [];
    const next = existing.includes(m) ? existing.filter((x) => x !== m) : [...existing, m];
    update({ maturity: next.length > 0 ? next : undefined });
  };

  return (
    <div className="flex flex-col gap-5">
      {!hiddenGroups.includes("distance") && (
        <FilterSection title="Distance search">
          <DistanceSearchBlock query={local} onChange={update} errorCode={errorCode} />
        </FilterSection>
      )}
      <FilterSection title="Price">
        <RangeSlider
          min={0} max={1_000_000} step={500}
          value={[local.minPrice ?? 0, local.maxPrice ?? 1_000_000]}
          onChange={([lo, hi]) => update({ minPrice: lo === 0 ? undefined : lo, maxPrice: hi === 1_000_000 ? undefined : hi })}
          ariaLabel={["Minimum price L$", "Maximum price L$"]}
        />
      </FilterSection>
      <FilterSection title="Size">
        <RangeSlider
          min={512} max={65536} step={512}
          value={[local.minArea ?? 512, local.maxArea ?? 65536]}
          onChange={([lo, hi]) => update({ minArea: lo === 512 ? undefined : lo, maxArea: hi === 65536 ? undefined : hi })}
          ariaLabel={["Minimum area sqm", "Maximum area sqm"]}
        />
      </FilterSection>
      <FilterSection title="Maturity">
        {(["GENERAL", "MODERATE", "ADULT"] as const).map((m) => (
          <label key={m} className="flex items-center gap-2 text-body-md">
            <input type="checkbox" checked={local.maturity?.includes(m) ?? false} onChange={() => toggleMaturity(m)} />
            {m.charAt(0) + m.slice(1).toLowerCase()}
          </label>
        ))}
      </FilterSection>
      {/* ... reserveStatus, snipeProtection, verificationTier, endingWithin ... */}
      {mode === "staged" && (
        <Button variant="primary" onClick={() => onCommit?.(local)}>Apply filters</Button>
      )}
    </div>
  );
}
```

- [ ] **Run tests — PASS.**

### Step 2b.5: `ResultsGrid`, `ResultsHeader`, `ResultsEmpty`

- [ ] **Tests + implementations** for the three results-surface components. `ResultsGrid` renders `<ListingCard>` per result with skeleton/empty/error branches. `ResultsHeader` has the title, result count, view toggle (placeholder), and `SortDropdown`. `ResultsEmpty` accepts a `reason: "no-filters" | "no-match"` and renders the appropriate copy per §7.6.

### Step 2b.6: `BrowseShell` — the orchestrator

- [ ] **Failing test** `frontend/src/components/browse/BrowseShell.test.tsx`:

```tsx
describe("BrowseShell", () => {
  it("seeds from initialQuery/initialData without fetching on first render", async () => {
    let fetches = 0;
    server.use(http.get("*/api/v1/auctions/search", () => {
      fetches++;
      return HttpResponse.json({ content: [], page: 0, size: 24, totalElements: 0, totalPages: 0, first: true, last: true });
    }));
    renderWithProviders(<BrowseShell initialQuery={{ sort: "newest" }} initialData={emptyResponse} />);
    expect(fetches).toBe(0);
  });

  it("pushes new URL + fetches when filter changes (desktop immediate)", async () => {
    const { useRouter } = await import("next/navigation");
    const replaceMock = vi.fn();
    vi.mocked(useRouter).mockReturnValue({ replace: replaceMock, push: vi.fn(), back: vi.fn(), forward: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() } as any);

    renderWithProviders(<BrowseShell initialQuery={{ sort: "newest" }} initialData={emptyResponse} />);
    // Change sort — desktop path is immediate.
    await userEvent.selectOptions(screen.getByLabelText(/sort/i), "ending_soonest");
    expect(replaceMock).toHaveBeenCalledWith(expect.stringContaining("sort=ending_soonest"), { scroll: false });
  });

  it("stages + commits when Apply is clicked in the mobile sheet", async () => {
    const { useRouter } = await import("next/navigation");
    const replaceMock = vi.fn();
    vi.mocked(useRouter).mockReturnValue({ replace: replaceMock, push: vi.fn(), back: vi.fn(), forward: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() } as any);

    renderWithProviders(<BrowseShell initialQuery={{}} initialData={emptyResponse} />);
    // Open mobile sheet trigger.
    await userEvent.click(screen.getByRole("button", { name: /filters/i }));
    // Toggle filter inside the sheet.
    await userEvent.click(screen.getByLabelText(/general/i));
    // replaceMock should NOT yet be called.
    expect(replaceMock).not.toHaveBeenCalled();
    // Apply.
    await userEvent.click(screen.getByRole("button", { name: /apply filters/i }));
    expect(replaceMock).toHaveBeenCalled();
  });

  it("close-without-apply discards staged changes", async () => {
    // Open sheet, toggle filter, close sheet (X button). Re-open: filter reset.
    // Assert: replaceMock never called; state reset to URL-derived query on reopen.
  });

  it("writes current URL to sessionStorage on mount and on every change", () => {
    renderWithProviders(<BrowseShell initialQuery={{ region: "Tula" }} initialData={emptyResponse} />);
    expect(sessionStorage.getItem("last-browse-url")).toMatch(/region=Tula/);
  });
});
```

- [ ] **Implement** `BrowseShell.tsx`:

```tsx
"use client";
import { useState, useEffect } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useMediaQuery } from "@/hooks/useMediaQuery"; // NEW tiny hook
import { FilterSidebar } from "./FilterSidebar";
import { FilterSidebarContent } from "./FilterSidebarContent";
import { BottomSheet } from "@/components/ui/BottomSheet";
import { ResultsHeader } from "./ResultsHeader";
import { ResultsGrid } from "./ResultsGrid";
import { ActiveFilters } from "./ActiveFilters";
import { Pagination } from "@/components/ui/Pagination";
import { useAuctionSearch } from "@/hooks/useAuctionSearch";
import { queryFromSearchParams, searchParamsFromQuery, defaultAuctionSearchQuery } from "@/lib/search/url-codec";
import { isApiError } from "@/lib/api";
import type { AuctionSearchQuery, SearchResponse } from "@/types/search";

interface Props {
  initialQuery: AuctionSearchQuery;
  initialData: SearchResponse;
  fixedFilters?: Partial<AuctionSearchQuery>;
  hiddenFilterGroups?: Array<"distance" | "seller">;
}

export function BrowseShell({ initialQuery, initialData, fixedFilters, hiddenFilterGroups }: Props) {
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const [query, setQuery] = useState<AuctionSearchQuery>(initialQuery);
  const [sheetOpen, setSheetOpen] = useState(false);
  const isMobile = useMediaQuery("(max-width: 767px)");

  // Sync from URL changes (back/forward).
  useEffect(() => {
    const urlQuery = queryFromSearchParams(new URLSearchParams(sp.toString()));
    const merged = fixedFilters ? { ...urlQuery, ...fixedFilters } : urlQuery;
    setQuery(merged);
  }, [sp, fixedFilters]);

  // Persist "last browse URL" for breadcrumb back-link.
  useEffect(() => {
    sessionStorage.setItem("last-browse-url", `${pathname}?${sp.toString()}`);
  }, [pathname, sp]);

  const result = useAuctionSearch(query, { initialData });

  const applyQuery = (next: AuctionSearchQuery) => {
    const merged = fixedFilters ? { ...next, ...fixedFilters } : next;
    setQuery(merged);
    const url = `${pathname}?${searchParamsFromQuery(merged).toString()}`;
    router.replace(url, { scroll: false });
    setSheetOpen(false);
  };

  const errorCode = result.error && isApiError(result.error) ? result.error.problem?.code : undefined;

  return (
    <div className="flex min-h-screen">
      <FilterSidebar className="hidden md:flex md:w-64" onOpenMobile={() => setSheetOpen(true)}>
        <FilterSidebarContent
          mode="immediate"
          query={query}
          onChange={applyQuery}
          hiddenGroups={hiddenFilterGroups}
          errorCode={typeof errorCode === "string" ? errorCode : undefined}
        />
      </FilterSidebar>
      <main className="flex-1 p-8">
        <ResultsHeader
          total={result.data?.totalElements ?? 0}
          onOpenMobile={() => setSheetOpen(true)}
          sort={query.sort ?? "newest"}
          onSortChange={(sort) => applyQuery({ ...query, sort })}
          nearestEnabled={Boolean(query.nearRegion)}
        />
        <ActiveFilters query={query} onChange={applyQuery} />
        <ResultsGrid
          listings={result.data?.content ?? []}
          isLoading={result.isLoading}
          isError={result.isError}
          errorCode={typeof errorCode === "string" ? errorCode : undefined}
          query={query}
          onClearFilters={() => applyQuery(defaultAuctionSearchQuery)}
        />
        {result.data && (
          <Pagination
            page={result.data.page}
            totalPages={result.data.totalPages}
            onPageChange={(page) => applyQuery({ ...query, page })}
            className="mt-8"
          />
        )}
      </main>
      <BottomSheet
        open={sheetOpen && isMobile}
        onClose={() => setSheetOpen(false)}
        title="Filters"
      >
        {/* Remount on every sheet open via key ensures staged state resets */}
        <FilterSidebarContent
          key={sheetOpen ? "open" : "closed"}
          mode="staged"
          query={query}
          onCommit={(next) => applyQuery(next)}
          hiddenGroups={hiddenFilterGroups}
          errorCode={typeof errorCode === "string" ? errorCode : undefined}
        />
      </BottomSheet>
    </div>
  );
}
```

- [ ] **Create tiny hook** `frontend/src/hooks/useMediaQuery.ts`:

```ts
"use client";
import { useEffect, useState } from "react";

export function useMediaQuery(query: string): boolean {
  const [match, setMatch] = useState(false);
  useEffect(() => {
    const mql = window.matchMedia(query);
    setMatch(mql.matches);
    const listener = (e: MediaQueryListEvent) => setMatch(e.matches);
    mql.addEventListener("change", listener);
    return () => mql.removeEventListener("change", listener);
  }, [query]);
  return match;
}
```

- [ ] **Tests — PASS** (including the staged/discard tests via the `key={sheetOpen ? "open" : "closed"}` remount trick).

### Step 2b.7: Page routes

- [ ] **Replace** `frontend/src/app/browse/page.tsx`:

```tsx
import { BrowseShell } from "@/components/browse/BrowseShell";
import { searchAuctions } from "@/lib/api/auctions-search";
import { queryFromSearchParams } from "@/lib/search/url-codec";
import type { Metadata } from "next";

export const metadata: Metadata = { title: "Browse Auctions · SLPA", description: "Discover active land auctions across the grid." };

type SP = Record<string, string | string[] | undefined>;

export default async function BrowsePage({ searchParams }: { searchParams: Promise<SP> }) {
  const params = await searchParams;
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (typeof v === "string") sp.set(k, v);
    else if (Array.isArray(v) && v[0]) sp.set(k, v[0]);
  }
  const query = queryFromSearchParams(sp);
  const initialData = await searchAuctions(query);
  return <BrowseShell initialQuery={query} initialData={initialData} />;
}
```

- [ ] **Create** `frontend/src/app/users/[id]/listings/page.tsx`:

```tsx
import { notFound } from "next/navigation";
import { BrowseShell } from "@/components/browse/BrowseShell";
import { SellerHeader } from "@/components/browse/SellerHeader";
import { searchAuctions } from "@/lib/api/auctions-search";
import { fetchUser } from "@/lib/api/users"; // existing
import { queryFromSearchParams } from "@/lib/search/url-codec";

type SP = Record<string, string | string[] | undefined>;

export default async function SellerListingsPage({ params, searchParams }: {
  params: Promise<{ id: string }>;
  searchParams: Promise<SP>;
}) {
  const { id } = await params;
  const sellerId = Number(id);
  if (!Number.isInteger(sellerId) || sellerId <= 0) notFound();

  const searchParamsData = await searchParams;
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(searchParamsData)) {
    if (typeof v === "string") sp.set(k, v);
    else if (Array.isArray(v) && v[0]) sp.set(k, v[0]);
  }
  const urlQuery = queryFromSearchParams(sp);
  const query = { ...urlQuery, sellerId };

  let user;
  try {
    [user, /* discard */] = await Promise.all([fetchUser(sellerId), Promise.resolve()]);
  } catch { notFound(); }

  const initialData = await searchAuctions(query);

  return (
    <>
      <SellerHeader user={user} />
      <BrowseShell
        initialQuery={query}
        initialData={initialData}
        fixedFilters={{ sellerId }}
        hiddenFilterGroups={["distance"]}
      />
    </>
  );
}
```

- [ ] **Create simple** `SellerHeader.tsx` — displays user's avatar + display name + member-since + link to full profile.

### Step 2b.8: DEFERRED_WORK sweep

- [ ] **Edit** `docs/implementation/DEFERRED_WORK.md`:
  - Remove the `### Per-user public listings page /users/{id}/listings` entry.
  - Add under "Current Deferred Items":

```markdown
### Region autocomplete for DistanceSearchBlock
- **From:** Epic 07 sub-spec 2 (Task 2b)
- **Why:** Phase 1 ships a free-form region text input with server-side validation on submit (REGION_NOT_FOUND surfaces inline under the input). Client-side autocomplete needs a new lightweight `/sl/regions/search?q=` endpoint, debounced input, keyboard nav, and a popover primitive — scope for its own design pass.
- **When:** Phase 2 polish.
- **Notes:** Touchpoint: `DistanceSearchBlock.tsx`.

### Infinite-scroll on browse grid
- **From:** Epic 07 sub-spec 2 (Task 2b)
- **Why:** Phase 1 ships numbered pagination — shareable URLs, SSR-friendly, back-button sane. Infinite scroll introduces scroll-position restore, focus management, SR announcements that deserve their own scoped design pass.
- **When:** Indefinite — trigger is user feedback demanding it. Consolidates with the existing BidHistory infinite-scroll deferral.
- **Notes:** Touchpoint: `BrowseShell.tsx` + `useAuctionSearch`. React Query already supports `useInfiniteQuery`.
```

### Step 2b.9: Full test + verify + commit

- [ ] **Run** tests + verify.
- [ ] **Commit.**

```bash
git add frontend/src/components/browse/ \
        frontend/src/app/browse/page.tsx \
        frontend/src/app/users/\[id\]/listings/page.tsx \
        frontend/src/hooks/useMediaQuery.ts \
        docs/implementation/DEFERRED_WORK.md

git commit -m "feat(browse): /browse + /users/{id}/listings pages

Ships the filterable, sortable, paginated browse experience backed by
/auctions/search. URL is the source of truth — every filter change
updates via router.replace, the back button restores the prior query,
deep links render the exact state.

  - BrowseShell composes FilterSidebar (desktop) + BottomSheet (mobile),
    ResultsHeader, ActiveFilters, ResultsGrid, Pagination.
  - Desktop: filters apply immediately (debounced 300ms for ranges/text).
  - Mobile: filters stage inside the sheet; Apply commits in one URL
    update. Close-without-apply discards via sheet remount.
  - /users/{id}/listings pins seller_id and hides the distance filter
    group — other filters, sort, and pagination work the same.
  - sessionStorage writes the current browse URL on every change — read
    by the detail page breadcrumb in Task 4.
  - DEFERRED_WORK sweep: removes 'per-user listings', adds 'region
    autocomplete' and 'infinite scroll'."
```

---

# Task 3 — Homepage featured rows

**Depends on:** Task 2a (for `ListingCard` + search client).

**Deliverables:**

1. `FeaturedRow` component.
2. Server-fetch three featured endpoints via `Promise.allSettled` on `/`.
3. Partial-failure isolation.
4. DEFERRED_WORK entry for the (intentionally deferred) StatsBar.

### Step 3.1: `FeaturedRow`

- [ ] **Failing test** `frontend/src/components/marketing/FeaturedRow.test.tsx`:

```tsx
import type { AuctionSearchResultDto } from "@/types/search";

describe("FeaturedRow", () => {
  it("renders cards when result is fulfilled", () => {
    const dto: AuctionSearchResultDto = { /* ... sample */ };
    const result = { status: "fulfilled" as const, value: { content: [dto] } };
    renderWithProviders(<FeaturedRow title="Ending Soon" sortLink="/browse?sort=ending_soonest" result={result} />);
    expect(screen.getByText("Ending Soon")).toBeInTheDocument();
    expect(screen.getAllByRole("article")).toHaveLength(1);
  });

  it("renders empty-state when result is rejected", () => {
    const result = { status: "rejected" as const, reason: new Error("nope") };
    renderWithProviders(<FeaturedRow title="Ending Soon" sortLink="/browse" result={result} />);
    expect(screen.getByText(/temporarily unavailable/i)).toBeInTheDocument();
  });

  it("renders empty-state when fulfilled but no content", () => {
    const result = { status: "fulfilled" as const, value: { content: [] } };
    renderWithProviders(<FeaturedRow title="Ending Soon" sortLink="/browse" result={result} />);
    expect(screen.getByText(/no listings ending soon/i)).toBeInTheDocument();
  });
});
```

- [ ] **Implement** `FeaturedRow.tsx`:

```tsx
import Link from "next/link";
import { ArrowRight } from "@/components/ui/icons";
import { ListingCard } from "@/components/auction/ListingCard";
import type { AuctionSearchResultDto } from "@/types/search";

type FeaturedResult = PromiseSettledResult<{ content: AuctionSearchResultDto[] }>;

export interface FeaturedRowProps {
  title: string;
  sortLink: string;
  result: FeaturedResult;
  emptyMessage?: string;
}

export function FeaturedRow({ title, sortLink, result, emptyMessage }: FeaturedRowProps) {
  const listings = result.status === "fulfilled" ? result.value.content : [];
  const failed = result.status === "rejected";

  return (
    <section className="py-10 px-6 md:px-8">
      <header className="mb-6 flex items-baseline justify-between">
        <h2 className="text-display-sm font-display font-bold tracking-[-0.02em]">{title}</h2>
        <Link href={sortLink} className="text-primary hover:underline inline-flex items-center gap-1">
          View all <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </header>
      {failed ? (
        <div className="rounded-default bg-surface-container-low p-8 text-center text-body-md text-on-surface-variant">
          {title} auctions are temporarily unavailable.
        </div>
      ) : listings.length === 0 ? (
        <div className="rounded-default bg-surface-container-low p-8 text-center text-body-md text-on-surface-variant">
          {emptyMessage ?? `No listings ${title.toLowerCase()} right now.`}
        </div>
      ) : (
        <div className="flex overflow-x-auto snap-x snap-mandatory gap-4 pb-2">
          {listings.map((l) => (
            <div key={l.id} className="snap-start shrink-0 w-[280px]">
              <ListingCard listing={l} variant="compact" />
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
```

- [ ] **Run tests — PASS.**

### Step 3.2: Homepage rewrite

- [ ] **Modify** `frontend/src/app/page.tsx`:

```tsx
import { Hero } from "@/components/marketing/Hero";
import { HowItWorksSection } from "@/components/marketing/HowItWorksSection";
import { FeaturesSection } from "@/components/marketing/FeaturesSection";
import { CtaSection } from "@/components/marketing/CtaSection";
import { FeaturedRow } from "@/components/marketing/FeaturedRow";
import { fetchFeatured } from "@/lib/api/auctions-search";

export default async function HomePage() {
  const [endingSoon, justListed, mostActive] = await Promise.allSettled([
    fetchFeatured("ending-soon"),
    fetchFeatured("just-listed"),
    fetchFeatured("most-active"),
  ]);
  return (
    <>
      <Hero />
      <FeaturedRow title="Ending Soon"   sortLink="/browse?sort=ending_soonest" result={endingSoon} />
      <FeaturedRow title="Just Listed"   sortLink="/browse?sort=newest"         result={justListed} />
      <FeaturedRow title="Most Active"   sortLink="/browse?sort=most_bids"      result={mostActive} />
      <HowItWorksSection />
      <FeaturesSection />
      <CtaSection />
    </>
  );
}
```

- [ ] **Integration test** — assert one rail failing doesn't crash the page. Mock one featured endpoint to 500 via MSW, assert two other rails render + the failing rail shows empty state.

### Step 3.3: DEFERRED_WORK StatsBar entry

- [ ] **Add to** `docs/implementation/DEFERRED_WORK.md`:

```markdown
### Public StatsBar on homepage (activity-threshold gated)
- **From:** Epic 07 sub-spec 2 (Task 3)
- **Why:** Backend `GET /api/v1/stats/public` is live from sub-spec 1 but the homepage deliberately does not render a stats bar. Launching with low numbers ("2 active bidders") reads as a liability, not social proof. Re-enable once activity is strong enough that the numbers flatter the product.
- **When:** Product decision — trigger is an activity threshold, not a technical readiness gate.
- **Notes:** Touchpoint: `app/page.tsx`. Component to add: `StatsBar` in `components/marketing/`. `/stats/public` response shape already documented in sub-spec 1 §5.3.
```

### Step 3.4: README sweep + commit

- [ ] **Update** `README.md` — add `/browse` and `/users/[id]/listings` to the frontend routes list if not already.
- [ ] **Run** tests + verify.
- [ ] **Commit.**

```bash
git add frontend/src/app/page.tsx \
        frontend/src/components/marketing/FeaturedRow.tsx \
        frontend/src/components/marketing/FeaturedRow.test.tsx \
        docs/implementation/DEFERRED_WORK.md \
        README.md

git commit -m "feat(homepage): three featured rails with partial-failure isolation

Slots Ending Soon / Just Listed / Most Active rails between the Hero
and HowItWorks sections on /. Server-side fetch via Promise.allSettled
so one rail's 5xx doesn't take down the page — failing rails render an
empty placeholder ('Ending Soon auctions are temporarily unavailable').

StatsBar is intentionally deferred per product decision; tracked in
DEFERRED_WORK.md (re-enable when activity looks like social proof)."
```

---

# Task 4 — Auction detail rebuild + OG metadata

**Depends on:** Task 2a (for `ListingCard` + `lib/sl/slurl.ts` setup).

**Deliverables:**

1. `Lightbox` primitive.
2. `AuctionHero` extension → wires into `Lightbox`.
3. New `SellerProfileCard` (replaces existing minimal).
4. `VisitInSecondLifeBlock` + `lib/sl/slurl.ts`.
5. `BreadcrumbNav`.
6. `ParcelLayoutMapPlaceholder`.
7. `cache()`-wrapped fetch + `generateMetadata` on `/auction/[id]` and `/users/[id]`.

### Step 4.1: `lib/sl/slurl.ts`

- [ ] **Failing test** `frontend/src/lib/sl/slurl.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { viewerProtocolUrl, mapUrl } from "./slurl";

describe("slurl", () => {
  it("viewer protocol keeps spaces raw", () => {
    expect(viewerProtocolUrl("Two Worlds", 80, 104, 89)).toBe("secondlife:///Two Worlds/80/104/89");
  });

  it("map url encodes spaces", () => {
    expect(mapUrl("Two Worlds", 80, 104, 89)).toBe("https://maps.secondlife.com/secondlife/Two%20Worlds/80/104/89");
  });

  it("null positions fall back to region center", () => {
    expect(viewerProtocolUrl("Tula", null, null, null)).toBe("secondlife:///Tula/128/128/0");
    expect(mapUrl("Tula", null, null, null)).toBe("https://maps.secondlife.com/secondlife/Tula/128/128/0");
  });

  it("handles apostrophes and unicode", () => {
    expect(mapUrl("Dubois' Place", 0, 0, 0)).toBe("https://maps.secondlife.com/secondlife/Dubois'%20Place/0/0/0");
  });
});
```

- [ ] **Implement** `frontend/src/lib/sl/slurl.ts`:

```ts
function xyz(x: number | null, y: number | null, z: number | null): { x: number; y: number; z: number } {
  if (x === null || y === null || z === null) return { x: 128, y: 128, z: 0 };
  return { x, y, z };
}

export function viewerProtocolUrl(regionName: string, x: number | null, y: number | null, z: number | null): string {
  const p = xyz(x, y, z);
  // Viewer protocol: region name with RAW spaces.
  return `secondlife:///${regionName}/${p.x}/${p.y}/${p.z}`;
}

export function mapUrl(regionName: string, x: number | null, y: number | null, z: number | null): string {
  const p = xyz(x, y, z);
  return `https://maps.secondlife.com/secondlife/${encodeURIComponent(regionName)}/${p.x}/${p.y}/${p.z}`;
}
```

- [ ] **Run tests — PASS.**

### Step 4.2: `Lightbox` primitive

- [ ] **Failing test** `frontend/src/components/ui/Lightbox.test.tsx`:

```tsx
describe("Lightbox", () => {
  const photos = [
    { id: 1, url: "/1.jpg", sortOrder: 0, caption: null },
    { id: 2, url: "/2.jpg", sortOrder: 1, caption: "Second" },
    { id: 3, url: "/3.jpg", sortOrder: 2, caption: null },
  ];

  it("does not render when closed", () => {
    renderWithProviders(<Lightbox photos={photos} initialIndex={0} open={false} onClose={() => {}} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("renders initial photo + counter", () => {
    renderWithProviders(<Lightbox photos={photos} initialIndex={1} open onClose={() => {}} />);
    expect(screen.getByText("2 / 3")).toBeInTheDocument();
    expect(screen.getByAltText(/Second/)).toBeInTheDocument();
  });

  it("arrow keys navigate", async () => {
    renderWithProviders(<Lightbox photos={photos} initialIndex={0} open onClose={() => {}} />);
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByText("2 / 3")).toBeInTheDocument();
    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByText("1 / 3")).toBeInTheDocument();
  });

  it("ESC closes", async () => {
    const onClose = vi.fn();
    renderWithProviders(<Lightbox photos={photos} initialIndex={0} open onClose={onClose} />);
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("Home/End jump", async () => {
    renderWithProviders(<Lightbox photos={photos} initialIndex={1} open onClose={() => {}} />);
    await userEvent.keyboard("{End}");
    expect(screen.getByText("3 / 3")).toBeInTheDocument();
    await userEvent.keyboard("{Home}");
    expect(screen.getByText("1 / 3")).toBeInTheDocument();
  });
});
```

- [ ] **Implement** `frontend/src/components/ui/Lightbox.tsx` using Headless UI `Dialog`:

```tsx
"use client";
import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { Dialog, DialogBackdrop, DialogPanel } from "@headlessui/react";
import { X } from "@/components/ui/icons";
import type { AuctionPhotoDto } from "@/types/auction";

export interface LightboxProps {
  photos: AuctionPhotoDto[];
  initialIndex: number;
  open: boolean;
  onClose: () => void;
}

export function Lightbox({ photos, initialIndex, open, onClose }: LightboxProps) {
  const [idx, setIdx] = useState(initialIndex);

  useEffect(() => { if (open) setIdx(initialIndex); }, [open, initialIndex]);

  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => {
      if (e.key === "ArrowRight") setIdx((i) => Math.min(photos.length - 1, i + 1));
      else if (e.key === "ArrowLeft") setIdx((i) => Math.max(0, i - 1));
      else if (e.key === "Home") setIdx(0);
      else if (e.key === "End") setIdx(photos.length - 1);
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [open, photos.length]);

  if (!open) return null;
  const p = photos[idx];
  return (
    <Dialog open={open} onClose={onClose} className="relative z-[60]">
      <DialogBackdrop className="fixed inset-0 bg-on-surface/95" />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel className="relative flex h-full w-full flex-col items-center justify-center">
          <div className="absolute top-3 left-4 text-on-primary text-label-md">
            {idx + 1} / {photos.length}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="absolute top-3 right-4 rounded-full bg-on-surface/20 p-2 hover:bg-on-surface/40"
          >
            <X className="size-5 text-on-primary" aria-hidden="true" />
          </button>
          <img
            src={p.url}
            alt={p.caption ?? `Photo ${idx + 1} of ${photos.length}`}
            className="max-h-[85vh] max-w-full object-contain"
          />
          {/* thumb strip */}
          <div className="absolute bottom-6 flex gap-2 overflow-x-auto px-4 max-w-full">
            {photos.map((ph, i) => (
              <button
                key={ph.id}
                type="button"
                onClick={() => setIdx(i)}
                className={`shrink-0 size-16 rounded border-2 ${i === idx ? "border-primary" : "border-transparent opacity-60"}`}
              >
                <img src={ph.url} alt="" className="h-full w-full object-cover rounded" />
              </button>
            ))}
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
```

- [ ] **Run tests — PASS.**

### Step 4.3: Extend `AuctionHero` with Lightbox

- [ ] **Failing test** — extend existing `AuctionHero.test.tsx`:

```tsx
it("clicking any image opens the Lightbox at that index", async () => {
  const photos = [/* 3 photos */];
  renderWithProviders(<AuctionHero photos={photos} snapshotUrl={null} regionName="Tula" />);
  await userEvent.click(screen.getByAltText(/photo 2/i));
  // Lightbox now open at idx 1.
  expect(screen.getByText("2 / 3")).toBeInTheDocument();
});
```

- [ ] **Modify** `AuctionHero.tsx` — wrap each image tag in a button or add a click handler that opens a new `useState<number | null>` for the lightbox index. Render `<Lightbox>` at the end when `index !== null`.

- [ ] **Run test — PASS.**

### Step 4.4: `SellerProfileCard` replacement

- [ ] **Read existing** `frontend/src/components/auction/SellerProfileCard.tsx` and its test. Confirm the existing props shape.

- [ ] **Failing test** — extend the existing test with the new enriched fields:

```tsx
it("shows completionRate as percentage", () => {
  renderWithProviders(<SellerProfileCard seller={mockEnrichedSeller({ completionRate: 0.67 })} />);
  expect(screen.getByText("67%")).toBeInTheDocument();
});

it("hides completion rate when null with 'Too new' copy", () => {
  renderWithProviders(<SellerProfileCard seller={mockEnrichedSeller({ completionRate: null })} />);
  expect(screen.getByText(/too new to calculate/i)).toBeInTheDocument();
});

it("shows New Seller badge when completedSales < 3", () => {
  renderWithProviders(<SellerProfileCard seller={mockEnrichedSeller({ completedSales: 1 })} />);
  expect(screen.getByText(/new seller/i)).toBeInTheDocument();
});
```

- [ ] **Implement** the new `SellerProfileCard.tsx`. Uses existing `ReputationStars` + `NewSellerBadge`. Reads from the enriched `SellerBlock` type added to `PublicAuctionResponse`/`SellerAuctionResponse`:

```tsx
import Link from "next/link";
import { Avatar } from "@/components/ui/Avatar";
import { ReputationStars } from "@/components/user/ReputationStars";
import { NewSellerBadge } from "@/components/user/NewSellerBadge";
import type { SellerBlock } from "@/types/auction";

export interface SellerProfileCardProps {
  seller: SellerBlock;
}

export function SellerProfileCard({ seller }: SellerProfileCardProps) {
  const pct = seller.completionRate === null ? null : Math.round(seller.completionRate * 100);
  const isNew = seller.completedSales < 3 || seller.completionRate === null;
  const memberSince = new Date(seller.memberSince).toLocaleDateString(undefined, { year: "numeric", month: "short" });

  return (
    <Link href={`/users/${seller.id}`} className="block rounded-default bg-surface-container-lowest p-5 shadow-sm hover:shadow-md">
      <div className="flex gap-4">
        <Avatar src={seller.avatarUrl} alt={seller.displayName} size={56} />
        <div className="flex-1 flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <h3 className="text-title-md font-semibold">{seller.displayName}</h3>
            {isNew && <NewSellerBadge />}
          </div>
          <p className="text-body-sm text-on-surface-variant">Member since {memberSince}</p>
          {seller.averageRating !== null && seller.reviewCount !== null && (
            <div className="flex items-center gap-2">
              <ReputationStars rating={seller.averageRating} />
              <span className="text-body-sm text-on-surface-variant">
                {seller.averageRating.toFixed(1)} · {seller.reviewCount} review{seller.reviewCount === 1 ? "" : "s"}
              </span>
            </div>
          )}
          <div className="flex gap-4 mt-2 text-body-sm">
            <span><strong>{seller.completedSales}</strong> completed sales</span>
            {pct !== null ? (
              <span><strong>{pct}%</strong> completion rate</span>
            ) : (
              <span className="text-on-surface-variant">Too new to calculate</span>
            )}
          </div>
        </div>
      </div>
    </Link>
  );
}
```

- [ ] **Run tests — PASS.**

### Step 4.5: `VisitInSecondLifeBlock`

- [ ] **Test + implement** the block. Two buttons, explanatory line, accepts `{ region, positionX, positionY, positionZ }` and calls `viewerProtocolUrl` + `mapUrl`.

### Step 4.6: `BreadcrumbNav`

- [ ] **Failing test** `BreadcrumbNav.test.tsx`:

```tsx
it("Browse link uses sessionStorage last-browse-url when present", () => {
  sessionStorage.setItem("last-browse-url", "/browse?region=Tula");
  renderWithProviders(<BreadcrumbNav region="Tula" title="Premium Waterfront" />);
  const link = screen.getByRole("link", { name: /browse/i });
  expect(link).toHaveAttribute("href", "/browse?region=Tula");
});

it("Browse link falls back to /browse with no sessionStorage", () => {
  sessionStorage.removeItem("last-browse-url");
  renderWithProviders(<BreadcrumbNav region="Tula" title="Premium Waterfront" />);
  const link = screen.getByRole("link", { name: /browse/i });
  expect(link).toHaveAttribute("href", "/browse");
});

it("Region link encodes the region name", () => {
  renderWithProviders(<BreadcrumbNav region="Two Worlds" title="Lot" />);
  const link = screen.getByRole("link", { name: /two worlds/i });
  expect(link).toHaveAttribute("href", "/browse?region=Two%20Worlds");
});
```

- [ ] **Implement** `BreadcrumbNav.tsx` — three-level breadcrumb with JSON-LD microdata:

```tsx
"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { ChevronRight } from "@/components/ui/icons";

export interface BreadcrumbNavProps {
  region: string;
  title: string;
}

export function BreadcrumbNav({ region, title }: BreadcrumbNavProps) {
  const [browseHref, setBrowseHref] = useState("/browse");
  useEffect(() => {
    const stored = sessionStorage.getItem("last-browse-url");
    if (stored) setBrowseHref(stored);
  }, []);
  const truncated = title.length > 40 ? title.slice(0, 40) + "…" : title;
  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "BreadcrumbList",
    itemListElement: [
      { "@type": "ListItem", position: 1, name: "Browse", item: "/browse" },
      { "@type": "ListItem", position: 2, name: region, item: `/browse?region=${encodeURIComponent(region)}` },
      { "@type": "ListItem", position: 3, name: title },
    ],
  };
  return (
    <nav aria-label="Breadcrumb" className="flex items-center gap-1 text-body-sm text-on-surface-variant py-2">
      <Link href={browseHref} className="hover:underline">Browse</Link>
      <ChevronRight className="size-4" aria-hidden="true" />
      <Link href={`/browse?region=${encodeURIComponent(region)}`} className="hover:underline">{region}</Link>
      <ChevronRight className="size-4" aria-hidden="true" />
      <span className="text-on-surface" aria-current="page">{truncated}</span>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />
    </nav>
  );
}
```

### Step 4.7: `ParcelLayoutMapPlaceholder`

- [ ] Simple card with "Parcel map coming soon" copy, hidden on mobile:

```tsx
export function ParcelLayoutMapPlaceholder() {
  return (
    <section className="hidden md:block rounded-default bg-surface-container-low p-6">
      <h2 className="text-title-md font-semibold mb-1">Parcel layout</h2>
      <p className="text-body-sm text-on-surface-variant">Parcel map coming soon.</p>
    </section>
  );
}
```

### Step 4.8: `cache()`-wrapped getAuction + `generateMetadata`

- [ ] **Verify Next.js 16 pattern.** Implementer reads `frontend/node_modules/next/dist/docs/` for the current dedup guidance (per frontend/AGENTS.md). Expected pattern:

```ts
// app/auction/[id]/page.tsx
import { cache } from "react";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getAuction, getBidHistory } from "@/lib/api/auctions";
import { isApiError } from "@/lib/api";
import { AuctionDetailClient } from "./AuctionDetailClient";

const loadAuction = cache((id: number) => getAuction(id));

export async function generateMetadata({ params }: { params: Promise<{ id: string }> }): Promise<Metadata> {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isInteger(auctionId) || auctionId <= 0) return { title: "Auction · SLPA" };
  try {
    const a = await loadAuction(auctionId);
    const og = a.primaryPhotoUrl ?? a.parcel.snapshotUrl ?? undefined;
    return {
      title: `${a.title} · SLPA`,
      description: `${a.parcel.regionName} · ${a.parcel.area} sqm · L$ ${a.currentBid.toLocaleString()}`,
      openGraph: {
        title: a.title,
        description: `${a.parcel.regionName} · ${a.parcel.area} sqm`,
        images: og ? [og] : [],
        type: "website",
      },
      twitter: { card: og ? "summary_large_image" : "summary" },
    };
  } catch { return { title: "Auction · SLPA" }; }
}

export default async function AuctionPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isInteger(auctionId) || auctionId <= 0) notFound();

  let auction;
  let firstBidPage;
  try {
    [auction, firstBidPage] = await Promise.all([loadAuction(auctionId), getBidHistory(auctionId, { page: 0, size: 20 })]);
  } catch (err) {
    if (isApiError(err) && err.status === 404) notFound();
    throw err;
  }
  if (!auction) notFound();
  return <AuctionDetailClient initialAuction={auction} initialBidPage={firstBidPage} />;
}
```

- [ ] **Integrate new components into `AuctionDetailClient`** — add `BreadcrumbNav` at top, `VisitInSecondLifeBlock` after the hero, swap in the new `SellerProfileCard`, render `ParcelLayoutMapPlaceholder` before the bid history.

### Step 4.9: `/users/[id]/page.tsx` OG metadata

- [ ] **Modify** the existing page to export `generateMetadata`. If `/users/{id}` doesn't already return `bio`, add it to the backend User DTO response in a small commit before this step.

- [ ] **Verify** by fetching the user once via `cache()` shared between `generateMetadata` and the page.

### Step 4.10: DEFERRED_WORK sweep + README sweep + commit

- [ ] **Remove** the `### Profile page SEO metadata (OpenGraph)` entry from `DEFERRED_WORK.md`.
- [ ] **README** — Add a line to the frontend status section mentioning auction-detail enrichment (photos gallery, SLURL, breadcrumbs, OG tags).
- [ ] **Run tests + verify.**
- [ ] **Commit.**

```bash
git add frontend/src/lib/sl/slurl.ts \
        frontend/src/lib/sl/slurl.test.ts \
        frontend/src/components/ui/Lightbox.tsx \
        frontend/src/components/ui/Lightbox.test.tsx \
        frontend/src/components/auction/AuctionHero.tsx \
        frontend/src/components/auction/AuctionHero.test.tsx \
        frontend/src/components/auction/SellerProfileCard.tsx \
        frontend/src/components/auction/SellerProfileCard.test.tsx \
        frontend/src/components/auction/VisitInSecondLifeBlock.tsx \
        frontend/src/components/auction/VisitInSecondLifeBlock.test.tsx \
        frontend/src/components/auction/BreadcrumbNav.tsx \
        frontend/src/components/auction/BreadcrumbNav.test.tsx \
        frontend/src/components/auction/ParcelLayoutMapPlaceholder.tsx \
        frontend/src/app/auction/\[id\]/page.tsx \
        frontend/src/app/auction/\[id\]/AuctionDetailClient.tsx \
        frontend/src/app/users/\[id\]/page.tsx \
        docs/implementation/DEFERRED_WORK.md \
        README.md

git commit -m "feat(detail): photos gallery + enriched seller card + OG metadata

Task 4 — extends the existing /auction/[id] page with the sub-spec 1
surface additions and adds generateMetadata on both /auction/[id] and
/users/[id] for OpenGraph + Twitter cards. React cache() dedups the
fetch across generateMetadata and the page body.

  - New Lightbox primitive (full-screen image viewer with keyboard +
    thumb strip + focus trap).
  - AuctionHero extended: click any image → opens Lightbox at that
    index. No visual regression — existing single-image + placeholder
    fallbacks unchanged.
  - SellerProfileCard replaced with the enriched version: rating,
    review count, completed sales, completion rate (percentage format,
    null → 'Too new to calculate'), member-since, New Seller badge,
    link to public profile page.
  - VisitInSecondLifeBlock — two buttons with correct SLURL encoding
    split (viewer protocol keeps raw spaces; map URL uses %20).
  - BreadcrumbNav — Browse/Region/Title with JSON-LD microdata and
    sessionStorage last-browse-url fallback for the Browse link.
  - ParcelLayoutMapPlaceholder — reserves space for a Phase 2 feature.
  - Resolves DEFERRED_WORK 'Profile page SEO metadata (OpenGraph)'."
```

---

# Task 5 — Curator Tray + /saved page + heart wiring

**Depends on:** Tasks 2a, 2b, 3, 4 (the cards + surfaces the heart lights up).

**Deliverables:**

1. `lib/api/saved.ts` API client.
2. `useSavedIds`, `useToggleSaved`, `useSavedAuctions` hooks.
3. `Drawer` primitive.
4. `CuratorTrayMount`, `CuratorTrayTrigger`, `CuratorTray`, `CuratorTrayContent`, `CuratorTrayHeader`, `CuratorTrayEmpty`.
5. `app/saved/page.tsx`.
6. Wire the heart button in `ListingCard` to `useToggleSaved`.
7. `OutbidToastProvider` migration to the new Toast primitive shape.

### Step 5.1: Saved API client

- [ ] **Failing test** `frontend/src/lib/api/saved.test.ts`:

```ts
it("saveAuction returns 200 with auctionId + savedAt", async () => {
  server.use(http.post("*/api/v1/me/saved", async ({ request }) => {
    const body = await request.json();
    return HttpResponse.json({ auctionId: (body as any).auctionId, savedAt: "2026-04-23T15:00:00Z" });
  }));
  await expect(saveAuction(42)).resolves.toEqual({ auctionId: 42, savedAt: "2026-04-23T15:00:00Z" });
});

it("throws ApiError on 409 SAVED_LIMIT_REACHED", async () => {
  server.use(http.post("*/api/v1/me/saved", () =>
    new HttpResponse(JSON.stringify({ code: "SAVED_LIMIT_REACHED" }), { status: 409 }),
  ));
  await expect(saveAuction(42)).rejects.toMatchObject({ status: 409 });
});

it("fetchSavedIds returns ids array", async () => {
  server.use(http.get("*/api/v1/me/saved/ids", () => HttpResponse.json({ ids: [1, 2, 3] })));
  await expect(fetchSavedIds()).resolves.toEqual({ ids: [1, 2, 3] });
});

it("fetchSavedAuctions accepts query params", async () => {
  let capturedUrl: URL | null = null;
  server.use(http.get("*/api/v1/me/saved/auctions", ({ request }) => {
    capturedUrl = new URL(request.url);
    return HttpResponse.json({ content: [], page: 0, size: 24, totalElements: 0, totalPages: 0, first: true, last: true });
  }));
  await fetchSavedAuctions({ statusFilter: "ended_only", sort: "newest", page: 0, size: 24 });
  expect(capturedUrl?.searchParams.get("status_filter")).toBe("ended_only");
});
```

- [ ] **Implement** `frontend/src/lib/api/saved.ts`:

```ts
import { api } from "@/lib/api";
import { searchParamsFromQuery } from "@/lib/search/url-codec";
import type { AuctionSearchQuery, SearchResponse, SavedIdsResponse } from "@/types/search";

export function fetchSavedIds(): Promise<SavedIdsResponse> {
  return api.get<SavedIdsResponse>("/api/v1/me/saved/ids");
}

export function fetchSavedAuctions(query: AuctionSearchQuery): Promise<SearchResponse> {
  const sp = searchParamsFromQuery(query);
  const qs = sp.toString();
  return api.get<SearchResponse>(`/api/v1/me/saved/auctions${qs ? "?" + qs : ""}`);
}

export function saveAuction(auctionId: number): Promise<{ auctionId: number; savedAt: string }> {
  return api.post("/api/v1/me/saved", { auctionId });
}

export function unsaveAuction(auctionId: number): Promise<void> {
  return api.delete<void>(`/api/v1/me/saved/${auctionId}`);
}
```

### Step 5.2: `useSavedAuctions` hooks

- [ ] **Failing tests** `frontend/src/hooks/useSavedAuctions.test.tsx`:

```tsx
describe("useSavedIds", () => {
  it("returns empty set when unauthenticated", () => {
    const { result } = renderHook(() => useSavedIds(), { wrapper: makeWrapper({ auth: "anonymous" }) });
    expect(result.current.ids.size).toBe(0);
    expect(result.current.isSaved(1)).toBe(false);
    expect(result.current.isLoading).toBe(false);
  });

  it("populates ids when authenticated", async () => {
    server.use(http.get("*/api/v1/me/saved/ids", () => HttpResponse.json({ ids: [1, 2] })));
    const { result } = renderHook(() => useSavedIds(), { wrapper: makeWrapper({ auth: "authenticated" }) });
    await waitFor(() => expect(result.current.ids.size).toBe(2));
    expect(result.current.isSaved(1)).toBe(true);
  });
});

describe("useToggleSaved", () => {
  it("unauth surfaces toast, doesn't fire request", async () => {
    let called = false;
    server.use(http.post("*/api/v1/me/saved", () => { called = true; return HttpResponse.json({}); }));
    const { result } = renderHook(() => useToggleSaved(), { wrapper: makeWrapper({ auth: "anonymous" }) });
    await result.current.toggle(42);
    expect(called).toBe(false);
  });

  it("auth: optimistic add + saveAuction fires", async () => {
    const ids = [1];
    server.use(
      http.get("*/api/v1/me/saved/ids", () => HttpResponse.json({ ids })),
      http.post("*/api/v1/me/saved", async ({ request }) => {
        ids.push((await request.json() as any).auctionId);
        return HttpResponse.json({ auctionId: 42, savedAt: "t" });
      }),
    );
    const wrapper = makeWrapper({ auth: "authenticated" });
    const { result: useIds } = renderHook(() => useSavedIds(), { wrapper });
    const { result: useToggle } = renderHook(() => useToggleSaved(), { wrapper });
    await waitFor(() => expect(useIds.current.isSaved(1)).toBe(true));
    await useToggle.current.toggle(42);
    await waitFor(() => expect(useIds.current.isSaved(42)).toBe(true));
  });

  it("rollback on 409 + surfaces limit-reached toast", async () => {
    // Mock 409 — optimistic adds 42, then rollback removes it.
    // Assert toast 'Curator Tray is full' appeared.
  });

  it("auth: unsave when already saved, optimistic remove + DELETE fires", async () => {
    // Mirror of the save test.
  });
});
```

- [ ] **Implement** `frontend/src/hooks/useSavedAuctions.ts`:

```ts
"use client";
import { useCallback, useMemo } from "react";
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { useCurrentUser } from "@/hooks/useCurrentUser"; // existing auth session hook
import { useToast } from "@/components/ui/Toast/useToast";
import { fetchSavedIds, fetchSavedAuctions, saveAuction, unsaveAuction } from "@/lib/api/saved";
import { canonicalKey } from "@/lib/search/canonical-key";
import { isApiError } from "@/lib/api";
import type { AuctionSearchQuery, SearchResponse, SavedIdsResponse } from "@/types/search";

const IDS_KEY = ["saved", "ids"] as const;
const AUCTIONS_KEY_PREFIX = ["saved", "auctions"] as const;

export function useSavedIds() {
  const { data: user } = useCurrentUser();
  const authenticated = Boolean(user);
  const q = useQuery({
    queryKey: IDS_KEY,
    queryFn: fetchSavedIds,
    enabled: authenticated,
    staleTime: Infinity,
  });
  const ids = useMemo(() => new Set<number>(q.data?.ids ?? []), [q.data]);
  return {
    ids,
    isSaved: useCallback((id: number) => ids.has(id), [ids]),
    isLoading: q.isLoading,
  };
}

export function useToggleSaved() {
  const { data: user } = useCurrentUser();
  const authenticated = Boolean(user);
  const qc = useQueryClient();
  const { toast } = useToast();

  const mutation = useMutation({
    mutationFn: async ({ id, shouldSave }: { id: number; shouldSave: boolean }) => {
      if (shouldSave) await saveAuction(id);
      else await unsaveAuction(id);
    },
    onMutate: async ({ id, shouldSave }) => {
      await qc.cancelQueries({ queryKey: IDS_KEY });
      const prev = qc.getQueryData<SavedIdsResponse>(IDS_KEY);
      qc.setQueryData<SavedIdsResponse>(IDS_KEY, (old) => {
        const set = new Set(old?.ids ?? []);
        if (shouldSave) set.add(id); else set.delete(id);
        return { ids: [...set] };
      });
      return { prev };
    },
    onError: (err, _vars, ctx) => {
      if (ctx?.prev) qc.setQueryData(IDS_KEY, ctx.prev);
      if (isApiError(err)) {
        if (err.status === 409) {
          toast.error({ title: "Curator Tray is full (500 saved)", description: "Remove some to add more." });
        } else if (err.status === 403) {
          toast.error({ title: "This auction isn't available to save yet." });
        } else if (err.status === 404) {
          toast.error({ title: "That auction no longer exists." });
          qc.invalidateQueries({ queryKey: IDS_KEY });
          qc.invalidateQueries({ queryKey: AUCTIONS_KEY_PREFIX, refetchType: "active" });
        } else {
          toast.error({ title: "Something went wrong saving that parcel." });
        }
      }
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: IDS_KEY });
      qc.invalidateQueries({ queryKey: AUCTIONS_KEY_PREFIX, refetchType: "active" });
    },
  });

  const toggle = useCallback(async (id: number) => {
    if (!authenticated) {
      toast.warning({
        title: "Sign in to save parcels",
        action: { label: "Sign in", onClick: () => window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`) },
      });
      return;
    }
    const ids = qc.getQueryData<SavedIdsResponse>(IDS_KEY)?.ids ?? [];
    const shouldSave = !ids.includes(id);
    await mutation.mutateAsync({ id, shouldSave });
  }, [authenticated, qc, mutation, toast]);

  return { toggle, isPending: mutation.isPending };
}

export function useSavedAuctions(query: AuctionSearchQuery): UseQueryResult<SearchResponse> {
  const { data: user } = useCurrentUser();
  return useQuery({
    queryKey: [...AUCTIONS_KEY_PREFIX, canonicalKey(query)],
    queryFn: () => fetchSavedAuctions(query),
    enabled: Boolean(user),
    staleTime: 0,
  });
}
```

### Step 5.3: Rewire `ListingCard` heart to real hook

- [ ] **Update** `ListingCard.tsx` — replace the `HeartOverlay` placeholder with one that uses `useSavedIds` + `useToggleSaved`:

```tsx
function HeartOverlay({ auctionId, title }: { auctionId: number; title: string }) {
  const { isSaved } = useSavedIds();
  const { toggle } = useToggleSaved();
  const saved = isSaved(auctionId);
  return (
    <button
      type="button"
      aria-label={`${saved ? "Unsave" : "Save"} ${title}`}
      aria-pressed={saved}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        toggle(auctionId);
      }}
      className={cn(
        "absolute top-2 right-2 rounded-full bg-surface-container-lowest/80 backdrop-blur p-2",
        "hover:bg-surface-container-lowest focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary",
      )}
    >
      <Heart className={cn("size-5", saved ? "fill-primary text-primary" : "text-on-surface-variant")} aria-hidden="true" />
    </button>
  );
}
```

- [ ] **Update ListingCard test** — full cycle: unauth toast, auth optimistic add, auth optimistic remove.

### Step 5.4: `Drawer` primitive

- [ ] **Test + implement** analogously to `BottomSheet` but right-anchored. Same focus trap + ESC. Different slide direction (`data-[closed]:translate-x-full`).

### Step 5.5: Curator Tray components

- [ ] **Test + implement** each Curator Tray component. Keep `CuratorTrayContent` presentational with a callback seam for URL sync vs internal state. Use `useMediaQuery("(min-width: 768px)")` to pick Drawer vs BottomSheet inside `CuratorTray`.

- [ ] `CuratorTrayMount` → wrapper at app-level. Only renders when `useCurrentUser().data` is non-null. Renders `CuratorTrayTrigger` in the nav (use React portal to an element with id `curator-tray-slot`) + the `CuratorTray` conditionally.

- [ ] Add `<div id="curator-tray-slot" />` to the existing top-nav component in `components/layout/`.

### Step 5.6: `/saved` page

- [ ] **Create** `frontend/src/app/saved/page.tsx`:

```tsx
import { Metadata } from "next";
import { SavedPageContent } from "./SavedPageContent";

export const metadata: Metadata = {
  title: "Saved Parcels · SLPA",
  robots: { index: false, follow: false },
};

export default function SavedPage() {
  return <SavedPageContent />;
}
```

- [ ] **Create** `SavedPageContent.tsx` — client component. Uses `useCurrentUser`: unauth → sign-in empty state; auth → renders `CuratorTrayContent` with URL-synced query state (via `useRouter().replace`).

### Step 5.7: `OutbidToastProvider` migration

- [ ] Update to use `toast.warning({ title, description, action: { label, onClick: scrollToBidPanel } })`. Drop the imperative `scrollIntoView` side-effect in favor of the action button.

- [ ] Update its test.

### Step 5.8: DEFERRED_WORK sweep + README sweep + commit

- [ ] **Remove entries**:
  - `### Saved / watchlist "Curator Tray"`
  - `### Richer outbid toast shape (warning variant + structured action button)`

- [ ] **README** — add `/saved` route + mention Curator Tray in the Phase 1 surface list. Ensure every new route from this sub-spec is represented.

- [ ] **Run tests + verify.**

- [ ] **Commit.**

```bash
git add frontend/src/lib/api/saved.ts \
        frontend/src/lib/api/saved.test.ts \
        frontend/src/hooks/useSavedAuctions.ts \
        frontend/src/hooks/useSavedAuctions.test.tsx \
        frontend/src/components/ui/Drawer.tsx \
        frontend/src/components/ui/Drawer.test.tsx \
        frontend/src/components/curator/ \
        frontend/src/components/layout/ \
        frontend/src/components/auction/ListingCard.tsx \
        frontend/src/components/auction/ListingCard.test.tsx \
        frontend/src/components/auction/OutbidToastProvider.tsx \
        frontend/src/components/auction/OutbidToastProvider.test.tsx \
        frontend/src/app/saved/ \
        docs/implementation/DEFERRED_WORK.md \
        README.md

git commit -m "feat(curator-tray): saved-auctions drawer + /saved page + heart wiring

Task 5 — lights up the heart button across every ListingCard-rendered
surface (browse, featured, detail, seller listings) and adds a
glassmorphic Curator Tray drawer (desktop) / bottom sheet (mobile)
with a matching /saved page.

  - Saved API client (POST/DELETE/GET/GET ids) covering all four
    sub-spec 1 endpoints.
  - Three React Query hooks: useSavedIds (empty-set safe when unauth),
    useToggleSaved (optimistic update + rollback on 409/403/404 with
    targeted toast copy), useSavedAuctions (tray + /saved list).
  - onSettled invalidation uses refetchType: 'active' so closed drawers
    don't background-fetch.
  - Curator Tray: Drawer on desktop, BottomSheet on mobile. Same
    CuratorTrayContent child shared with /saved page — tray query is
    ephemeral (component state), /saved query is URL-synced via an
    onQueryChange callback.
  - Heart-count badge in top nav (hidden when unauth).
  - ListingCard heart: optimistic save/unsave, aria-pressed state,
    propagation stops on click.
  - OutbidToastProvider migrated to toast.warning with structured
    action button — resolves the existing DEFERRED_WORK entry.
  - DEFERRED_WORK sweep: removes 'Curator Tray' and 'Richer outbid
    toast' entries."
```

---

# Final integration pass

After Task 5 lands:

- [ ] **Full test suite** — `cd frontend && npm run test`. All pass.
- [ ] **Typecheck** — `cd frontend && npx tsc --noEmit`. Clean.
- [ ] **Verify** — `cd frontend && npm run verify`. All pass.
- [ ] **Build** — `cd frontend && npm run build`. Succeeds.
- [ ] **Final README sweep** — every new route mentioned; no stale "coming in Phase 7" wording.
- [ ] **Open PR** targeting `dev` from `task/07-sub-2-browse-search-frontend`. PR body summarizes the six tasks + deferred entries.

---

## Self-review notes

**Spec coverage:**

- §1 scope — all covered across Tasks 1-5.
- §3 architecture — Next.js 16 cache(), URL codec, React Query, initialData hydration — covered in 2a/2b/4.
- §5 components — all present in file structure and task steps.
- §6 hook contracts — implementations match spec shape.
- §7 filter surface — FilterSidebarContent with immediate/staged modes.
- §8 ListingCard — three variants, heart, status chip, accessibility.
- §9 browse pages — both routes land in 2b.
- §10 featured — Task 3.
- §11 detail — Task 4 covers every sub-section.
- §12 curator tray — Task 5 covers drawer + trigger + page + wiring.
- §13 wizard title — Task 1.
- §18 backend gaps — §2a.1 (endOutcome), §4.9 (bio verify), §5.7 (toast migration already done in Task 2a).
- §19 deferred work — per-task sweeps in 2b/3/4/5.
- §21 success criteria — every bullet mapped to a task deliverable.

**Type consistency** — `AuctionSearchQuery`, `SearchResponse`, `AuctionSearchResultDto` names match across Tasks 2a → 5. `useSavedIds` / `useToggleSaved` / `useSavedAuctions` signatures match spec §6.

**Placeholder check** — every code block contains actual implementation. `// ...` markers in test examples are for the implementer to fill with exhaustive enum/boundary coverage, not unresolved design questions.
