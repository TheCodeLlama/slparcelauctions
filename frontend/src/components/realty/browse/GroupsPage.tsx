"use client";

import { ArrowDown, ArrowUp, Plus, Search } from "lucide-react";
import type {
  GroupCardLayout,
  GroupSidebarPlacement,
  GroupsSortKey,
  RealtyGroupCard,
  SortDirection,
} from "@/types/realty";
import { cn } from "@/lib/cn";
import { Btn } from "./components/Btn";
import { Checkbox } from "./components/Checkbox";
import { EmptyGroups } from "./components/EmptyGroups";
import { FilterGroup } from "./components/FilterGroup";
import { GroupCard } from "./components/GroupCard";
import { Pagination } from "./components/Pagination";
import { StarPicker } from "./components/StarPicker";

/**
 * Public realty-group directory. 1:1 visual port of the claude.ai/design
 * template at {@code docs/realty-groups/claude-design/pages/GroupsPage.tsx},
 * adapted to be props-driven (so {@link GroupsBrowseClient} can wire every
 * control to the backend instead of mock data).
 *
 * <p>The template ran filter + sort + paginate locally over a mockData
 * array. Here every control instead writes a URL param via the parent's
 * callbacks, the parent re-fetches the backend page, and the resulting
 * cards flow back in via {@link GroupsPageProps#groups}. {@code totalCount}
 * and {@code pageCount} come from the backend's {@code Page<RealtyGroupCardDto>}
 * envelope so the "{n} groups" header and pagination strip reflect the
 * full result set, not just the current page.
 *
 * <p>Sort enum diverges intentionally from the template's
 * {@code (ACTIVE_LISTINGS|AGE|NAME|RATING|SALES)}: we keep our backend's
 * {@code (RATING|NEWEST|MOST_ACTIVE_LISTINGS|MOST_SALES)} keys so the wire
 * contract stays stable. Labels in the dropdown are tuned to read like the
 * template's options.
 */
interface GroupsPageProps {
  /** Backend page's content array — parent has already paginated server-side. */
  groups: RealtyGroupCard[];

  /** Card render variant. The directory wrapper passes {@code "cover"}. */
  cardLayout?: GroupCardLayout;
  /** Sidebar placement. The directory wrapper passes {@code "left"}. */
  sidebar?: GroupSidebarPlacement;

  /** Search query — controlled by the URL-state wrapper. */
  q: string;
  onQChange: (q: string) => void;

  /** Sort key — controlled by the URL-state wrapper. */
  sort: GroupsSortKey;
  onSortChange: (sort: GroupsSortKey) => void;

  /** Sort direction toggle next to the dropdown. */
  direction: SortDirection;
  onDirectionChange: (direction: SortDirection) => void;

  /** Minimum-rating sidebar filter ({@code 0} means no filter). */
  minRating: number;
  onMinRatingChange: (value: number) => void;

  /** Minimum-reviews sidebar filter ({@code 0} means no filter). */
  minReviews: number;
  onMinReviewsChange: (value: number) => void;

  /** "Has active listing" sidebar checkbox. */
  activeOnly: boolean;
  onActiveOnlyChange: (next: boolean) => void;

  /**
   * Clear all sidebar filters + the search query in one click. Wired to
   * both the sidebar's "Clear all filters" button and the
   * {@link EmptyGroups} "Clear filters" action when a search returns
   * nothing.
   */
  onClearFilters: () => void;

  /** Current 0-based page number from the backend response. */
  page: number;
  /** Total pages from the backend response. */
  pageCount: number;
  onPageChange: (page: number) => void;

  /** Total filtered results from the backend — drives the "{n} groups" header. */
  totalCount: number;

  onOpenGroup?: (group: RealtyGroupCard) => void;
  onStartGroup?: () => void;
  onHome?: () => void;
}

const SORT_OPTIONS: Array<[GroupsSortKey, string]> = [
  ["RATING", "Rating"],
  ["NEWEST", "Age"],
  ["MOST_ACTIVE_LISTINGS", "Active listings"],
  ["MOST_SALES", "Sales"],
];

export function GroupsPage({
  groups,
  cardLayout = "cover",
  sidebar = "left",
  q,
  onQChange,
  sort,
  onSortChange,
  direction,
  onDirectionChange,
  minRating,
  onMinRatingChange,
  minReviews,
  onMinReviewsChange,
  activeOnly,
  onActiveOnlyChange,
  onClearFilters,
  page,
  pageCount,
  totalCount,
  onPageChange,
  onOpenGroup,
  onStartGroup,
  onHome,
}: GroupsPageProps) {
  const hasActiveFilter =
    q.trim().length > 0 || minRating > 0 || minReviews > 0 || activeOnly;

  const sidebarContent = (
    <aside className="sticky top-[calc(var(--header-h,60px)+16px)] h-fit">
      <FilterGroup title="Minimum rating">
        <StarPicker
          value={minRating}
          onChange={(v) => onMinRatingChange(v)}
        />
      </FilterGroup>

      <FilterGroup title="Minimum reviews">
        <input
          className="w-full px-3 py-2 rounded-md border border-border bg-surface-raised text-fg text-sm focus:outline-none focus:border-brand"
          type="number"
          min={0}
          step={1}
          value={minReviews}
          onChange={(e) =>
            onMinReviewsChange(Math.max(0, Number(e.target.value) || 0))
          }
          placeholder="0"
          aria-label="Minimum reviews"
        />
      </FilterGroup>

      <FilterGroup title="Listings">
        <Checkbox
          label="Has active listing"
          checked={activeOnly}
          onChange={() => onActiveOnlyChange(!activeOnly)}
        />
      </FilterGroup>

      <Btn variant="ghost" size="sm" block onClick={onClearFilters}>
        Clear all filters
      </Btn>
    </aside>
  );

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
        / Groups
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
        {sidebar === "left" && (
          <div className="hidden lg:block">{sidebarContent}</div>
        )}

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
            <button
              type="button"
              onClick={() =>
                onDirectionChange(direction === "DESC" ? "ASC" : "DESC")
              }
              title={
                direction === "DESC"
                  ? "Descending - click to flip"
                  : "Ascending - click to flip"
              }
              aria-label={
                direction === "DESC"
                  ? "Sort descending; click to flip"
                  : "Sort ascending; click to flip"
              }
              className="h-[42px] px-3 border border-border bg-surface-raised text-fg text-sm font-medium inline-flex items-center gap-1.5 rounded-md cursor-pointer hover:bg-bg-muted"
            >
              {direction === "DESC" ? (
                <>
                  <ArrowDown className="w-3.5 h-3.5" /> Desc
                </>
              ) : (
                <>
                  <ArrowUp className="w-3.5 h-3.5" /> Asc
                </>
              )}
            </button>
          </div>

          <div className="flex items-center justify-between mb-3.5 flex-wrap gap-2">
            <div className="text-sm text-fg-muted">
              <span className="font-semibold tabular-nums text-fg">
                {totalCount}
              </span>{" "}
              {totalCount === 1 ? "group" : "groups"}
              {q.trim() && <span> matching &ldquo;{q}&rdquo;</span>}
            </div>
            <div className="flex items-center gap-2">
              {hasActiveFilter && (
                <Btn variant="ghost" size="sm" onClick={onClearFilters}>
                  Clear
                </Btn>
              )}
            </div>
          </div>

          {groups.length === 0 ? (
            <EmptyGroups query={q} onClear={onClearFilters} />
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
            totalPages={pageCount}
            onChange={onPageChange}
          />
        </main>

        {sidebar === "right" && (
          <div className="hidden lg:block">{sidebarContent}</div>
        )}
      </div>
    </div>
  );
}
