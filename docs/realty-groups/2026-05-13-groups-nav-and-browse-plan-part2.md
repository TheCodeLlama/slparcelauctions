# Groups Namespace Migration — Part 2: Frontend browse page

> Index: [`2026-05-13-groups-nav-and-browse-plan.md`](2026-05-13-groups-nav-and-browse-plan.md). Spec: [`2026-05-13-groups-nav-and-browse-design.md`](2026-05-13-groups-nav-and-browse-design.md). Previous: [`2026-05-13-groups-nav-and-browse-plan-part1.md`](2026-05-13-groups-nav-and-browse-plan-part1.md).

**Tasks 8-13.** Move the claude.ai/design template into `frontend/src/components/realty/browse/`, fork `GroupsPage` to externalize URL state to props, ship `useBrowseGroups`, wire the `GroupsBrowseClient` URL-state wrapper, mount `/groups/page.tsx`, and add the integration test.

**Order rule:** Task 8 must precede Task 9 (the fork edits a moved file). Task 10 is parallel-safe with Tasks 8-9. Tasks 11-12 depend on Tasks 9 + 10 chaining. Task 13 closes Part 2.

---

## Task 8: Move template files into `frontend/src/components/realty/browse/`

**Files:**
- Create directory tree: `frontend/src/components/realty/browse/components/`
- Create: `frontend/src/components/realty/browse/lib/format.ts` (homes for `formatFounded` + `initialsOf`)
- Move (via Write + delete) every component file from `docs/realty-groups/components/` to `frontend/src/components/realty/browse/components/`. Files: `Avatar.tsx`, `Badge.tsx`, `Btn.tsx`, `Checkbox.tsx`, `DetailRow.tsx`, `EmptyGroups.tsx`, `FilterGroup.tsx`, `GroupCard.tsx`, `GroupCover.tsx`, `GroupLogo.tsx`, `MemberRow.tsx`, `Pagination.tsx`, `StarPicker.tsx`, `StarRating.tsx`.
- Move: `docs/realty-groups/pages/GroupsPage.tsx` -> `frontend/src/components/realty/browse/GroupsPage.tsx`
- Move: `docs/realty-groups/pages/GroupDetailPage.tsx` -> `frontend/src/components/realty/browse/GroupDetailPage.tsx` (Part 3 consumes this for the slug profile page; lands here for proximity)
- Delete: `docs/realty-groups/lib/cn.ts`, `docs/realty-groups/types.ts`, `docs/realty-groups/index.ts`, `docs/realty-groups/mockData.ts`, every file under `docs/realty-groups/components/`, every file under `docs/realty-groups/pages/`, and (after this task) the empty parent directories `docs/realty-groups/components/`, `docs/realty-groups/pages/`, `docs/realty-groups/lib/`.
- Modify: `frontend/src/types/realty.ts` — extend the existing `RealtyGroupCard` (no collision yet — verify) so it covers every field the template reads. Per the spec's DTO contract the wire shape is `{ publicId, name, slug, tagline, logoUrl, coverUrl, foundedAt, memberCount, memberSeatLimit, activeListingsCount, completedSalesCount, rating }`. `hasVerifiedSlGroup` is **not** on the wire and is **not** on the type (verified-only is server-side per spec section 6.1).
- Modify (post-move): every moved file's import sites — re-point `../types` to `@/types/realty`, re-point `../lib/cn` to `@/lib/cn` (for `cn`) plus `./lib/format` (for `formatFounded` / `initialsOf`), drop every `mockData` import.

- [ ] **Step 1: Verify the type-name collision is empty**

```powershell
Select-String -Path "frontend/src/types/realty.ts" -Pattern "RealtyGroupCard\b"
```

Expected: zero matches. The existing file uses `RealtyGroupRowDto`, `RealtyGroupSummaryDto`, `RealtyGroupPublicDto` — `RealtyGroupCard` is unused, so the template's name lands cleanly.

If matches exist, rename the existing one in the same task (suffix `Legacy` and reroute callers) before adding the template shape — never silently coexist two `RealtyGroupCard`s.

- [ ] **Step 2: Add the template's wire types to `frontend/src/types/realty.ts`**

Append after the existing `GroupRating` block:

```ts
/**
 * Wire shape for one card on the public groups directory. Matches the backend
 * {@code RealtyGroupCardDto} record (spec section 6.1). {@code logoUrl} /
 * {@code coverUrl} are relative paths — render via {@code apiUrl(...)}.
 *
 * {@code tagline} is the backend-truncated description (120 chars + ellipsis);
 * the frontend renders it as-is.
 *
 * No {@code hasVerifiedSlGroup} field: the browse endpoint filters unverified
 * groups server-side, so the flag would always be true on the wire.
 */
export interface RealtyGroupCard {
  publicId: string;
  name: string;
  slug: string;
  tagline: string;
  logoUrl: string | null;
  coverUrl: string | null;
  foundedAt: string;
  memberCount: number;
  memberSeatLimit: number;
  activeListingsCount: number;
  completedSalesCount: number;
  rating: GroupRating;
}

/** Card render variant for the directory grid. */
export type GroupCardLayout = "standard" | "compact" | "cover";

/** Sidebar placement variant for the directory grid. */
export type GroupSidebarPlacement = "left" | "right" | "hidden";

/**
 * Sort key the directory page emits in the {@code ?sort=...} query param. The
 * union must stay in lockstep with the backend {@code GroupsSortKey} enum at
 * {@code backend/.../realty/browse/GroupsSortKey.java}.
 */
export type GroupsSortKey =
  | "RATING"
  | "NEWEST"
  | "MOST_ACTIVE_LISTINGS"
  | "MOST_SALES";

/**
 * Compact view types consumed by {@code GroupDetailPage} (the claude.ai/design
 * profile template). The public-profile page in Part 3 maps the backend
 * {@code RealtyGroupPublicDto} -> these shapes.
 */
export interface GroupMember {
  id: string;
  name: string;
  rating: number;
  sales: number;
  memberSince: string;
}

export interface GroupReview {
  id: string;
  author: string;
  stars: number;
  when: string;
  text: string;
}
```

- [ ] **Step 3: Create `frontend/src/components/realty/browse/lib/format.ts`**

```ts
/**
 * Display-only helpers used by the {@code /groups} directory template.
 * Kept here (not in {@code @/lib/cn}) so the shared {@code cn} utility stays
 * narrow.
 */
export function formatFounded(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString("en-US", { month: "short", year: "numeric" });
}

export function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0] ?? "")
    .join("")
    .toUpperCase();
}
```

- [ ] **Step 4: Move each component file with rewritten imports**

For each of the 14 component files under `docs/realty-groups/components/`, copy the file body to `frontend/src/components/realty/browse/components/<Name>.tsx`, then update the imports:

- `../types` -> `@/types/realty`
- `../lib/cn` -> `@/lib/cn` (for `cn` only)
- Any `../lib/cn` import that pulls `formatFounded` or `initialsOf` -> `../lib/format`
- Strip any `mockData` import lines (none of the components should depend on `mockData`, but verify per-file).

Example (`GroupCard.tsx`) — the before:

```ts
import type { GroupCardLayout, RealtyGroupCard } from "../types";
import { cn, formatFounded, initialsOf } from "../lib/cn";
```

becomes:

```ts
import type { GroupCardLayout, RealtyGroupCard } from "@/types/realty";
import { cn } from "@/lib/cn";
import { formatFounded, initialsOf } from "../lib/format";
```

The relative depth changes: components/X imports `../lib/format` (one level up from `components/`). The `GroupsPage.tsx` and `GroupDetailPage.tsx` (sibling of `components/` and `lib/`) imports `./lib/format` and `./components/<Name>`.

Repeat for every file. Inline the new import block — never split the move from the rewrite into two commits.

- [ ] **Step 5: Move `GroupsPage.tsx` and `GroupDetailPage.tsx`**

Copy `docs/realty-groups/pages/GroupsPage.tsx` to `frontend/src/components/realty/browse/GroupsPage.tsx` verbatim for now (Task 9 forks it). Same for `GroupDetailPage.tsx`. Rewrite their import paths the same way (`../types` -> `@/types/realty`; `../lib/cn` -> `@/lib/cn` + `./lib/format`; `../components/<Name>` -> `./components/<Name>`).

- [ ] **Step 6: Delete the source files**

```powershell
Remove-Item -Recurse -Force docs/realty-groups/components
Remove-Item -Recurse -Force docs/realty-groups/pages
Remove-Item -Recurse -Force docs/realty-groups/lib
Remove-Item -Force docs/realty-groups/types.ts
Remove-Item -Force docs/realty-groups/index.ts
Remove-Item -Force docs/realty-groups/mockData.ts
```

What stays under `docs/realty-groups/`: the design spec, the plan index, the four `-plan-part*.md` files, and `README.md` (if present — leave it alone in this task).

- [ ] **Step 7: Run lint + typecheck on the new tree**

```powershell
cd frontend; npm run lint -- --max-warnings=0
cd frontend; npx tsc --noEmit
```

Expected: clean. Any unresolved import that survives the rewrite means a path was missed — fix in this step, do not defer.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/realty/browse/ \
        frontend/src/types/realty.ts
git rm -r docs/realty-groups/components docs/realty-groups/pages docs/realty-groups/lib
git rm docs/realty-groups/types.ts docs/realty-groups/index.ts docs/realty-groups/mockData.ts
git commit -m "refactor(groups): move claude.ai/design browse template into frontend/src/components/realty/browse"
```

---

## Task 9: Fork `GroupsPage` — externalize search/sort/page to props

**Files:**
- Modify: `frontend/src/components/realty/browse/GroupsPage.tsx`

Goal: the moved-but-unforked file owns `useState` for `q` / `sort` / `sortDir` / `activeOnly` / `minRating` / `minReviews` / `page`. The fork:

1. Accepts `q`, `sort`, `page` as props plus `onQChange`, `onSortChange`, `onPageChange` callbacks (the URL-state wrapper from Task 11 owns the source of truth).
2. Drops `hasVerifiedSlGroup` filtering (always true server-side).
3. Drops client-side filtering by `activeOnly` / `minRating` / `minReviews` — out of scope for the spec's first cut; the sidebar still renders these controls as no-ops or hidden, but the wire ignores them. Pick **hidden**: less surface to maintain.
4. Drops the `sortDir` toggle button (the backend sort keys are always DESC tie-broken by name ASC; the toggle would lie to the user).
5. Drops client pagination — `groups` is already one backend page; the `Pagination` component still renders but its `totalPages` comes from props.

The fork keeps the original render shell, the grid, the search input, and the sort `<select>` — only the state plumbing changes.

- [ ] **Step 1: Write a failing component test**

Path: `frontend/src/components/realty/browse/GroupsPage.test.tsx`.

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { GroupsPage } from "./GroupsPage";
import type { RealtyGroupCard } from "@/types/realty";

function makeCard(overrides: Partial<RealtyGroupCard> = {}): RealtyGroupCard {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    tagline: "Premium Mainland brokerage.",
    logoUrl: null,
    coverUrl: null,
    foundedAt: "2026-01-01T00:00:00Z",
    memberCount: 1,
    memberSeatLimit: 8,
    activeListingsCount: 0,
    completedSalesCount: 0,
    rating: { averageRating: null, reviewCount: 0 },
    ...overrides,
  };
}

describe("GroupsPage (forked, props-driven)", () => {
  it("renders the passed-in groups verbatim with no client-side filtering", () => {
    render(
      <GroupsPage
        groups={[makeCard({ name: "Alpha" }), makeCard({ name: "Beta", publicId: "00000000-0000-0000-0000-000000000002", slug: "beta" })]}
        cardLayout="cover"
        sidebar="left"
        q=""
        sort="RATING"
        page={0}
        totalPages={1}
        onQChange={vi.fn()}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
      />,
    );
    expect(screen.getByText("Alpha")).toBeInTheDocument();
    expect(screen.getByText("Beta")).toBeInTheDocument();
  });

  it("calls onQChange when the user types in the search input", () => {
    const onQChange = vi.fn();
    render(
      <GroupsPage
        groups={[]}
        q=""
        sort="RATING"
        page={0}
        totalPages={1}
        onQChange={onQChange}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByPlaceholderText(/search groups/i), {
      target: { value: "mainland" },
    });
    expect(onQChange).toHaveBeenCalledWith("mainland");
  });

  it("calls onSortChange when the sort select changes", () => {
    const onSortChange = vi.fn();
    render(
      <GroupsPage
        groups={[]}
        q=""
        sort="RATING"
        page={0}
        totalPages={1}
        onQChange={vi.fn()}
        onSortChange={onSortChange}
        onPageChange={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByRole("combobox"), {
      target: { value: "NEWEST" },
    });
    expect(onSortChange).toHaveBeenCalledWith("NEWEST");
  });

  it("does not filter or sort client-side: the order of `groups` is preserved", () => {
    const cards = [
      makeCard({ name: "Zulu", publicId: "00000000-0000-0000-0000-000000000001", slug: "zulu" }),
      makeCard({ name: "Alpha", publicId: "00000000-0000-0000-0000-000000000002", slug: "alpha" }),
    ];
    render(
      <GroupsPage
        groups={cards}
        q=""
        sort="RATING"
        page={0}
        totalPages={1}
        onQChange={vi.fn()}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
      />,
    );
    const headings = screen.getAllByRole("heading", { level: 3 });
    // The first heading rendered should be "Zulu" because the parent's order is
    // preserved (no client-side sort).
    expect(headings[0]).toHaveTextContent("Zulu");
  });
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```powershell
cd frontend; npm test -- GroupsPage.test
```

Expected: FAIL — the current component owns `useState`, so the prop signature does not exist; `q`/`sort`/`page`/`totalPages`/`onQChange`/`onSortChange`/`onPageChange` will trip TypeScript.

- [ ] **Step 3: Rewrite `GroupsPage.tsx` with the props-driven shape**

Replace the moved file's body wholesale (single Edit pass; no re-Write since the file already exists).

```tsx
"use client";

import { ChevronRight, Plus, Search } from "lucide-react";
import type {
  GroupCardLayout,
  GroupSidebarPlacement,
  GroupsSortKey,
  RealtyGroupCard,
} from "@/types/realty";
import { cn } from "@/lib/cn";
import { Btn } from "./components/Btn";
import { EmptyGroups } from "./components/EmptyGroups";
import { GroupCard } from "./components/GroupCard";
import { Pagination } from "./components/Pagination";

interface GroupsPageProps {
  /** The backend page's content array. The parent already paginated. */
  groups: RealtyGroupCard[];
  cardLayout?: GroupCardLayout;
  sidebar?: GroupSidebarPlacement;

  /** Search query — controlled by the URL-state wrapper. */
  q: string;
  /** Sort key — controlled by the URL-state wrapper. */
  sort: GroupsSortKey;
  /** Current 0-based page number from the backend response. */
  page: number;
  /** Total pages from the backend response (so the Pagination strip is honest). */
  totalPages: number;

  onQChange: (q: string) => void;
  onSortChange: (sort: GroupsSortKey) => void;
  onPageChange: (page: number) => void;

  onOpenGroup?: (group: RealtyGroupCard) => void;
  onStartGroup?: () => void;
  onHome?: () => void;
}

const SORT_OPTIONS: Array<[GroupsSortKey, string]> = [
  ["RATING", "Rating"],
  ["NEWEST", "Newest"],
  ["MOST_ACTIVE_LISTINGS", "Active listings"],
  ["MOST_SALES", "Sales"],
];

export function GroupsPage({
  groups,
  cardLayout = "cover",
  sidebar = "left",
  q,
  sort,
  page,
  totalPages,
  onQChange,
  onSortChange,
  onPageChange,
  onOpenGroup,
  onStartGroup,
  onHome,
}: GroupsPageProps) {
  const gridCols =
    sidebar === "hidden"
      ? "grid-cols-1"
      : sidebar === "right"
        ? "grid-cols-1 lg:[grid-template-columns:1fr_264px]"
        : "grid-cols-1 lg:[grid-template-columns:264px_1fr]";

  return (
    <div className="w-full max-w-[1280px] mx-auto px-6 py-7 pb-16">
      <div className="text-xs text-fg-subtle mb-1.5">
        <button
          type="button"
          onClick={onHome}
          className="text-fg-muted hover:text-fg cursor-pointer bg-transparent border-none p-0"
        >
          Home
        </button>{" "}
        <ChevronRight className="inline w-3 h-3 align-[-1px]" /> Groups
      </div>

      <div className="flex justify-between items-end gap-4 mb-5 flex-wrap">
        <div>
          <h1 className="text-3xl font-bold tracking-tight m-0 text-fg">
            Realty groups
          </h1>
          <p className="text-sm text-fg-muted mt-1 mb-0">
            Verified seller collectives. Find a group that specializes in the
            parcel type you want.
          </p>
        </div>
        <div className="flex gap-2">
          <Btn variant="secondary" onClick={onStartGroup}>
            <Plus className="w-3.5 h-3.5" /> Start a group
          </Btn>
        </div>
      </div>

      <div className={cn("grid gap-7 items-start", gridCols)}>
        <main className="min-w-0">
          <div className="flex gap-2.5 mb-4 flex-wrap">
            <div className="relative flex-1 min-w-[240px]">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-fg-subtle pointer-events-none" />
              <input
                className="w-full pl-9 pr-3 py-3 rounded-md border border-border bg-surface-raised text-fg text-sm focus:outline-none focus:border-brand"
                value={q}
                onChange={(e) => onQChange(e.target.value)}
                placeholder="Search groups by name or specialty"
                aria-label="Search groups"
              />
            </div>
            <select
              className="w-44 px-3 rounded-md border border-border bg-surface-raised text-fg text-sm focus:outline-none focus:border-brand h-[42px]"
              value={sort}
              onChange={(e) => onSortChange(e.target.value as GroupsSortKey)}
              aria-label="Sort groups"
            >
              {SORT_OPTIONS.map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
            </select>
          </div>

          <div className="flex items-center justify-between mb-3.5 flex-wrap gap-2">
            <div className="text-sm text-fg-muted">
              <span className="font-semibold tabular-nums text-fg">
                {groups.length}
              </span>{" "}
              {groups.length === 1 ? "group" : "groups"}
              {q.trim() && <span> matching &ldquo;{q}&rdquo;</span>}
            </div>
          </div>

          {groups.length === 0 ? (
            <EmptyGroups query={q} onClear={() => onQChange("")} />
          ) : (
            <div className="grid gap-3.5 grid-cols-[repeat(auto-fill,minmax(280px,1fr))]">
              {groups.map((g) => (
                <GroupCard
                  key={g.publicId}
                  group={g}
                  layout={cardLayout}
                  onClick={() => onOpenGroup?.(g)}
                />
              ))}
            </div>
          )}

          <Pagination
            page={page}
            totalPages={totalPages}
            onChange={onPageChange}
          />
        </main>
      </div>
    </div>
  );
}
```

The fork deletes: every `useState` import, every `useMemo` import, the `FilterGroup` / `Checkbox` / `StarPicker` sidebar block, the `sortDir` button, and the `clearAll` / `resetPage` helpers. The aside is removed because Task 9's "sidebar controls are hidden" decision (see §spec section 7.1 — directory page constraints) keeps the surface minimal.

- [ ] **Step 4: Run the test to confirm it passes**

```powershell
cd frontend; npm test -- GroupsPage.test
```

Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/realty/browse/GroupsPage.tsx \
        frontend/src/components/realty/browse/GroupsPage.test.tsx
git commit -m "refactor(groups): GroupsPage props-driven q/sort/page (no client filter or sort)"
```

---

## Task 10: `useBrowseGroups` TanStack Query hook [parallel-safe]

**Files:**
- Create: `frontend/src/lib/api/realtyGroupsBrowse.ts` — the typed API client wrapper for `GET /api/v1/realty-groups`.
- Create: `frontend/src/hooks/realty/useBrowseGroups.ts` — the TanStack Query hook.
- Create: `frontend/src/hooks/realty/useBrowseGroups.test.tsx` — unit test confirming the right URL is hit with the right query params.

- [ ] **Step 1: Write a failing test**

```tsx
import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/msw/server";
import { useBrowseGroups } from "./useBrowseGroups";
import type { Page } from "@/types/page";
import type { RealtyGroupCard } from "@/types/realty";

function emptyPage(): Page<RealtyGroupCard> {
  return { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
}

function wrap() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  // eslint-disable-next-line react/display-name
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe("useBrowseGroups", () => {
  it("hits /api/v1/realty-groups with the supplied query params", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    const { result } = renderHook(
      () =>
        useBrowseGroups({
          q: "mainland",
          page: 2,
          size: 30,
          sort: "MOST_ACTIVE_LISTINGS",
        }),
      { wrapper: wrap() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const url = new URL(capturedUrl);
    expect(url.pathname).toBe("/api/v1/realty-groups");
    expect(url.searchParams.get("q")).toBe("mainland");
    expect(url.searchParams.get("page")).toBe("2");
    expect(url.searchParams.get("size")).toBe("30");
    expect(url.searchParams.get("sort")).toBe("MOST_ACTIVE_LISTINGS");
  });

  it("omits the q param when q is empty / undefined", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    const { result } = renderHook(
      () => useBrowseGroups({ page: 0, size: 20, sort: "RATING" }),
      { wrapper: wrap() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const url = new URL(capturedUrl);
    expect(url.searchParams.has("q")).toBe(false);
    expect(url.searchParams.get("sort")).toBe("RATING");
  });
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```powershell
cd frontend; npm test -- useBrowseGroups.test
```

Expected: FAIL — module-not-found for `./useBrowseGroups` and `@/lib/api/realtyGroupsBrowse`.

- [ ] **Step 3: Create `frontend/src/lib/api/realtyGroupsBrowse.ts`**

```ts
import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type { GroupsSortKey, RealtyGroupCard } from "@/types/realty";

/**
 * Query inputs for {@link getBrowseGroups}. {@code q} is optional; absent /
 * blank means "no search filter applied". {@code sort} defaults to RATING
 * server-side, but we always send it explicitly so the URL params and the
 * wire are 1:1.
 */
export interface BrowseGroupsParams {
  q?: string;
  page?: number;
  size?: number;
  sort?: GroupsSortKey;
}

/**
 * Calls {@code GET /api/v1/realty-groups} — the public browse endpoint
 * (spec section 6.1). Anonymous-accessible; the shared {@code api.get}
 * helper omits the Authorization header when no JWT is present, so this
 * works pre-login.
 */
export function getBrowseGroups(
  params: BrowseGroupsParams,
): Promise<Page<RealtyGroupCard>> {
  const search = new URLSearchParams();
  if (params.q && params.q.trim().length > 0) {
    search.set("q", params.q.trim());
  }
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 20));
  search.set("sort", params.sort ?? "RATING");
  return api.get(`/api/v1/realty-groups?${search.toString()}`);
}
```

- [ ] **Step 4: Create `frontend/src/hooks/realty/useBrowseGroups.ts`**

```ts
"use client";
import { useQuery } from "@tanstack/react-query";
import {
  getBrowseGroups,
  type BrowseGroupsParams,
} from "@/lib/api/realtyGroupsBrowse";

/**
 * TanStack Query hook over the public {@code GET /api/v1/realty-groups}
 * directory endpoint (spec section 6.1). Cache key includes every input so a
 * change to {@code q} / {@code page} / {@code sort} triggers a fresh fetch.
 *
 * {@code staleTime} is short (5s) — the cards' rating / active-listings
 * counts move fast enough that the prior cached page becomes misleading
 * within a refresh interval. Refetch-on-focus is left at the global default.
 */
export function useBrowseGroups(params: BrowseGroupsParams) {
  return useQuery({
    queryKey: [
      "realty-groups",
      "browse",
      {
        q: params.q ?? "",
        page: params.page ?? 0,
        size: params.size ?? 20,
        sort: params.sort ?? "RATING",
      },
    ] as const,
    queryFn: () => getBrowseGroups(params),
    staleTime: 5_000,
  });
}
```

- [ ] **Step 5: Run the test to confirm it passes**

```powershell
cd frontend; npm test -- useBrowseGroups.test
```

Expected: 2 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/api/realtyGroupsBrowse.ts \
        frontend/src/hooks/realty/useBrowseGroups.ts \
        frontend/src/hooks/realty/useBrowseGroups.test.tsx
git commit -m "feat(groups): useBrowseGroups hook + realtyGroupsBrowse api client"
```

---

## Task 11: `GroupsBrowseClient` URL-state wrapper

**Files:**
- Create: `frontend/src/components/realty/browse/GroupsBrowseClient.tsx`

Reads `q` / `sort` / `page` from `useSearchParams`, calls `useBrowseGroups`, renders `<GroupsPage>` with the locked layout props (`cardLayout="cover"`, `sidebar="left"`) per spec section 7.1. URL-state mutations use `router.replace` (no history pollution for keystrokes) for the search input and `router.push` for sort + pagination + card-click navigation.

- [ ] **Step 1: Write a failing test**

The full integration coverage lives in Task 13 — this task ships the component with a single rendering smoke test so the file compiles before the page imports it.

Path: `frontend/src/components/realty/browse/GroupsBrowseClient.smoke.test.tsx`.

```tsx
import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { GroupsBrowseClient } from "./GroupsBrowseClient";
import type { Page } from "@/types/page";
import type { RealtyGroupCard } from "@/types/realty";

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/groups",
}));

function page(content: RealtyGroupCard[]): Page<RealtyGroupCard> {
  return {
    content,
    totalElements: content.length,
    totalPages: Math.max(1, Math.ceil(content.length / 20)),
    number: 0,
    size: 20,
  };
}

describe("GroupsBrowseClient (smoke)", () => {
  it("renders the directory heading once data resolves", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(page([])),
      ),
    );
    renderWithProviders(<GroupsBrowseClient />);
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { level: 1, name: /realty groups/i }),
      ).toBeInTheDocument(),
    );
  });
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```powershell
cd frontend; npm test -- GroupsBrowseClient.smoke.test
```

Expected: FAIL — module-not-found for `./GroupsBrowseClient`.

- [ ] **Step 3: Create `GroupsBrowseClient.tsx`**

```tsx
"use client";

import { useCallback } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useBrowseGroups } from "@/hooks/realty/useBrowseGroups";
import { GroupsPage } from "./GroupsPage";
import type { GroupsSortKey, RealtyGroupCard } from "@/types/realty";

const VALID_SORTS: ReadonlySet<GroupsSortKey> = new Set<GroupsSortKey>([
  "RATING",
  "NEWEST",
  "MOST_ACTIVE_LISTINGS",
  "MOST_SALES",
]);

function parseSort(raw: string | null): GroupsSortKey {
  if (raw && (VALID_SORTS as Set<string>).has(raw)) {
    return raw as GroupsSortKey;
  }
  return "RATING";
}

function parsePage(raw: string | null): number {
  const n = raw ? Number.parseInt(raw, 10) : 0;
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

/**
 * URL-state wrapper for the {@code /groups} directory. Owns the
 * {@code ?q=...&sort=...&page=...} contract and threads those values into
 * {@link GroupsPage} via props, calling {@link useBrowseGroups} for data.
 *
 * Search keystrokes use {@code router.replace} (no history entry per
 * character); sort + pagination + card-click use {@code router.push}
 * (user-meaningful navigation).
 */
export function GroupsBrowseClient() {
  const router = useRouter();
  const params = useSearchParams();

  const q = params.get("q") ?? "";
  const sort = parseSort(params.get("sort"));
  const page = parsePage(params.get("page"));

  const query = useBrowseGroups({ q, page, size: 20, sort });

  const writeParams = useCallback(
    (mutator: (sp: URLSearchParams) => void, mode: "push" | "replace") => {
      const sp = new URLSearchParams(params.toString());
      mutator(sp);
      const qs = sp.toString();
      const target = qs ? `/groups?${qs}` : "/groups";
      if (mode === "push") router.push(target);
      else router.replace(target);
    },
    [params, router],
  );

  const onQChange = useCallback(
    (next: string) => {
      writeParams((sp) => {
        if (next.trim().length === 0) sp.delete("q");
        else sp.set("q", next);
        sp.delete("page");
      }, "replace");
    },
    [writeParams],
  );

  const onSortChange = useCallback(
    (next: GroupsSortKey) => {
      writeParams((sp) => {
        sp.set("sort", next);
        sp.delete("page");
      }, "push");
    },
    [writeParams],
  );

  const onPageChange = useCallback(
    (next: number) => {
      writeParams((sp) => {
        if (next === 0) sp.delete("page");
        else sp.set("page", String(next));
      }, "push");
    },
    [writeParams],
  );

  const onOpenGroup = useCallback(
    (g: RealtyGroupCard) => router.push(`/groups/${g.slug}`),
    [router],
  );

  const onStartGroup = useCallback(
    () => router.push("/groups/new"),
    [router],
  );

  const onHome = useCallback(() => router.push("/"), [router]);

  if (query.isError) {
    return (
      <div
        role="alert"
        className="mx-auto max-w-2xl my-12 rounded-md border border-border bg-surface-raised p-6 text-sm text-fg"
      >
        <p className="font-semibold mb-1">Couldn't load groups.</p>
        <p className="text-fg-muted">
          Try refreshing the page. If the problem keeps happening, check back
          in a few minutes.
        </p>
      </div>
    );
  }

  if (query.isPending) {
    return (
      <div
        aria-busy="true"
        aria-label="Loading groups"
        className="w-full max-w-[1280px] mx-auto px-6 py-7 pb-16"
      >
        <div className="h-8 w-48 bg-bg-muted rounded animate-pulse mb-6" />
        <div className="grid gap-3.5 grid-cols-[repeat(auto-fill,minmax(280px,1fr))]">
          {Array.from({ length: 6 }).map((_, i) => (
            <div
              key={i}
              className="h-56 rounded-md border border-border bg-surface-raised animate-pulse"
            />
          ))}
        </div>
      </div>
    );
  }

  const data = query.data;
  return (
    <GroupsPage
      cardLayout="cover"
      sidebar="left"
      groups={data.content}
      q={q}
      sort={sort}
      page={data.number}
      totalPages={Math.max(1, data.totalPages)}
      onQChange={onQChange}
      onSortChange={onSortChange}
      onPageChange={onPageChange}
      onOpenGroup={onOpenGroup}
      onStartGroup={onStartGroup}
      onHome={onHome}
    />
  );
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```powershell
cd frontend; npm test -- GroupsBrowseClient.smoke.test
```

Expected: 1 test, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/realty/browse/GroupsBrowseClient.tsx \
        frontend/src/components/realty/browse/GroupsBrowseClient.smoke.test.tsx
git commit -m "feat(groups): GroupsBrowseClient URL-state wrapper for the directory page"
```

---

## Task 12: `/groups/page.tsx` server component

**Files:**
- Create: `frontend/src/app/groups/page.tsx`

- [ ] **Step 1: Write a failing test asserting the export**

Path: `frontend/src/app/groups/page.test.tsx`.

```tsx
import { describe, expect, it } from "vitest";
import GroupsPage, { dynamic, metadata } from "./page";

describe("/groups page export", () => {
  it("opts out of static prerendering", () => {
    expect(dynamic).toBe("force-dynamic");
  });

  it("ships a static title for SEO", () => {
    expect(metadata?.title).toBe("Realty groups");
  });

  it("default-exports a callable component", () => {
    expect(typeof GroupsPage).toBe("function");
  });
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```powershell
cd frontend; npm test -- "app/groups/page.test"
```

Expected: FAIL — module-not-found.

- [ ] **Step 3: Create `frontend/src/app/groups/page.tsx`**

```tsx
import type { Metadata } from "next";
import { GroupsBrowseClient } from "@/components/realty/browse/GroupsBrowseClient";

/**
 * Public realty-group directory. Spec section 7.1.
 *
 * {@code force-dynamic} per the SSR caveat in CLAUDE.md — search, sort, and
 * pagination state changes per visit, so static prerendering would cache a
 * single page-zero snapshot and lie to every other visitor. Mirrors the
 * posture of {@code /group/[slug]} and the home page.
 */
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Realty groups",
  description:
    "Browse verified realty groups on SLParcels. Search, sort by rating or activity, and find the right seller collective for your parcel.",
};

export default function GroupsDirectoryPage() {
  return <GroupsBrowseClient />;
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```powershell
cd frontend; npm test -- "app/groups/page.test"
```

Expected: 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/groups/page.tsx \
        frontend/src/app/groups/page.test.tsx
git commit -m "feat(groups): /groups directory page (force-dynamic, mounts GroupsBrowseClient)"
```

---

## Task 13: Integration test for the browse flow

**Files:**
- Create: `frontend/src/components/realty/browse/GroupsBrowseClient.test.tsx`

Covers: MSW handler hit with the right query params, card count from the mocked response, search input triggers a fresh request, card click navigates to `/groups/{slug}`, start-a-group CTA navigates to `/groups/new`, loading skeleton renders before data resolves.

- [ ] **Step 1: Write the failing integration test**

```tsx
import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { fireEvent } from "@testing-library/react";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { GroupsBrowseClient } from "./GroupsBrowseClient";
import type { Page } from "@/types/page";
import type { RealtyGroupCard } from "@/types/realty";

const pushSpy = vi.fn();
const replaceSpy = vi.fn();

const searchParamsState: { value: URLSearchParams } = {
  value: new URLSearchParams(),
};

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: pushSpy,
    replace: replaceSpy,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: () => searchParamsState.value,
  usePathname: () => "/groups",
}));

function setSearchParams(qs: string) {
  searchParamsState.value = new URLSearchParams(qs);
}

function card(overrides: Partial<RealtyGroupCard> = {}): RealtyGroupCard {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    tagline: "Premium Mainland brokerage.",
    logoUrl: null,
    coverUrl: null,
    foundedAt: "2026-01-01T00:00:00Z",
    memberCount: 1,
    memberSeatLimit: 8,
    activeListingsCount: 0,
    completedSalesCount: 0,
    rating: { averageRating: null, reviewCount: 0 },
    ...overrides,
  };
}

function asPage(content: RealtyGroupCard[]): Page<RealtyGroupCard> {
  return {
    content,
    totalElements: content.length,
    totalPages: Math.max(1, Math.ceil(content.length / 20)),
    number: 0,
    size: 20,
  };
}

describe("GroupsBrowseClient (integration)", () => {
  beforeEach(() => {
    pushSpy.mockReset();
    replaceSpy.mockReset();
    setSearchParams("");
  });

  it("hits /api/v1/realty-groups with the default sort=RATING and page=0", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(asPage([]));
      }),
    );
    renderWithProviders(<GroupsBrowseClient />);
    await waitFor(() => expect(capturedUrl).not.toBe(""));
    const url = new URL(capturedUrl);
    expect(url.pathname).toBe("/api/v1/realty-groups");
    expect(url.searchParams.get("sort")).toBe("RATING");
    expect(url.searchParams.get("page")).toBe("0");
    expect(url.searchParams.has("q")).toBe(false);
  });

  it("renders one card per row in the mocked response", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(
          asPage([
            card({ name: "Alpha", slug: "alpha", publicId: "00000000-0000-0000-0000-00000000aaaa" }),
            card({ name: "Beta", slug: "beta", publicId: "00000000-0000-0000-0000-00000000bbbb" }),
            card({ name: "Gamma", slug: "gamma", publicId: "00000000-0000-0000-0000-00000000cccc" }),
          ]),
        ),
      ),
    );
    renderWithProviders(<GroupsBrowseClient />);
    await waitFor(() => {
      expect(screen.getByText("Alpha")).toBeInTheDocument();
      expect(screen.getByText("Beta")).toBeInTheDocument();
      expect(screen.getByText("Gamma")).toBeInTheDocument();
    });
  });

  it("renders a loading skeleton before data resolves", async () => {
    let resolve!: (value: unknown) => void;
    const pending = new Promise((r) => {
      resolve = r;
    });
    server.use(
      http.get("*/api/v1/realty-groups", async () => {
        await pending;
        return HttpResponse.json(asPage([]));
      }),
    );
    renderWithProviders(<GroupsBrowseClient />);
    expect(screen.getByLabelText(/loading groups/i)).toBeInTheDocument();
    resolve(undefined);
    await waitFor(() =>
      expect(screen.queryByLabelText(/loading groups/i)).not.toBeInTheDocument(),
    );
  });

  it("typing in the search input replaces the URL with ?q=...", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(asPage([])),
      ),
    );
    renderWithProviders(<GroupsBrowseClient />);
    await waitFor(() =>
      expect(screen.getByPlaceholderText(/search groups/i)).toBeInTheDocument(),
    );
    fireEvent.change(screen.getByPlaceholderText(/search groups/i), {
      target: { value: "mainland" },
    });
    expect(replaceSpy).toHaveBeenCalledWith("/groups?q=mainland");
  });

  it("clicking a card pushes /groups/{slug}", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(
          asPage([card({ name: "Alpha", slug: "alpha-realty" })]),
        ),
      ),
    );
    renderWithProviders(<GroupsBrowseClient />);
    await waitFor(() => expect(screen.getByText("Alpha")).toBeInTheDocument());
    fireEvent.click(screen.getByText("Alpha"));
    expect(pushSpy).toHaveBeenCalledWith("/groups/alpha-realty");
  });

  it("clicking Start a group pushes /groups/new", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(asPage([])),
      ),
    );
    renderWithProviders(<GroupsBrowseClient />);
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /start a group/i }),
      ).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /start a group/i }));
    expect(pushSpy).toHaveBeenCalledWith("/groups/new");
  });

  it("changing sort pushes ?sort=NEWEST", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(asPage([])),
      ),
    );
    renderWithProviders(<GroupsBrowseClient />);
    await waitFor(() =>
      expect(
        screen.getByRole("combobox", { name: /sort groups/i }),
      ).toBeInTheDocument(),
    );
    fireEvent.change(screen.getByRole("combobox", { name: /sort groups/i }), {
      target: { value: "NEWEST" },
    });
    expect(pushSpy).toHaveBeenCalledWith("/groups?sort=NEWEST");
  });
});
```

`beforeEach` is imported implicitly via Vitest's globals — confirm `vitest.setup.ts` exposes it (the existing test files use the same pattern). If your installed config requires explicit import, add `import { beforeEach } from "vitest";` at the top.

- [ ] **Step 2: Run the test to confirm it fails initially**

```powershell
cd frontend; npm test -- GroupsBrowseClient.test
```

Expected on first run after Tasks 8-12 are committed: the test file is new; if previous tasks shipped correctly, every assertion should pass on the first green run. If any fails:

- Card click not firing: the `GroupCard` component might consume `onClick` via a child element. Inspect `frontend/src/components/realty/browse/components/GroupCard.tsx` — the click target may be the card root or an inner button. Re-target the test's `fireEvent.click` to the actual handler element.
- Search-replace URL doesn't strip an empty `q`: confirm the wrapper deletes `q` when the input is blank (the wrapper code in Task 11 does — leave the test asserting `/groups?q=mainland`).
- Loading skeleton not detected: confirm the `aria-busy` / `aria-label` strings in Task 11's wrapper match the test's `getByLabelText(/loading groups/i)` regex.

- [ ] **Step 3: Run the test to confirm it passes**

```powershell
cd frontend; npm test -- GroupsBrowseClient.test
```

Expected: 7 tests, 0 failures.

- [ ] **Step 4: Run the full frontend test suite to catch regressions**

```powershell
cd frontend; npm test
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/realty/browse/GroupsBrowseClient.test.tsx
git commit -m "test(groups): integration coverage for the /groups browse flow"
```

---

## Push Part 2 commits

```bash
git push
```

Frontend browse page complete; Part 3 begins (slug-keyed route tree under `/groups/[slug]/*`).
