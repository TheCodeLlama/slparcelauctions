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
  /**
   * The backend page's content array — the parent already paginated server-side.
   * Order is preserved verbatim; no client-side filter or sort runs.
   */
  groups: RealtyGroupCard[];

  /** Card render variant. Defaults to {@code "standard"}; the production URL wrapper passes {@code "cover"}. */
  cardLayout?: GroupCardLayout;
  /** Sidebar placement. Defaults to {@code "left"}; sidebar content is currently empty so this is a layout hint only. */
  sidebar?: GroupSidebarPlacement;
  /**
   * Legacy prop from the pre-fork template — ignored after the fork. The
   * backend response's page size is the source of truth.
   * @deprecated Backend pagination is authoritative.
   */
  perPage?: number;

  /** Search query — controlled by the URL-state wrapper (Task 11). */
  q: string;
  onQChange: (q: string) => void;

  /** Sort key — controlled by the URL-state wrapper. Every key is DESC server-side per spec section 6.1. */
  sort: GroupsSortKey;
  onSortChange: (sort: GroupsSortKey) => void;

  /** Current 0-based page number from the backend response. */
  page: number;
  /** Total pages from the backend response (so the {@code Pagination} strip is honest). */
  pageCount: number;
  onPageChange: (page: number) => void;

  /** Total filtered results from the backend — drives the "{n} groups" header (not {@code groups.length}). */
  totalCount: number;

  onOpenGroup?: (group: RealtyGroupCard) => void;
  onStartGroup?: () => void;
  onHome?: () => void;

  /** When true, render a skeleton grid in place of the cards / empty state. */
  isLoading?: boolean;
}

const SORT_OPTIONS: Array<[GroupsSortKey, string]> = [
  ["RATING", "Rating"],
  ["NEWEST", "Newest"],
  ["MOST_ACTIVE_LISTINGS", "Most active listings"],
  ["MOST_SALES", "Most sales"],
];

const SKELETON_COUNT = 8;

export function GroupsPage({
  groups,
  cardLayout = "standard",
  sidebar = "left",
  q,
  sort,
  page,
  pageCount,
  totalCount,
  onQChange,
  onSortChange,
  onPageChange,
  onOpenGroup,
  onStartGroup,
  onHome,
  isLoading = false,
}: GroupsPageProps) {
  // The sidebar block (filters) was removed: every client-side filter the
  // template carried (activeOnly / minRating / minReviews) is out of scope per
  // spec section 6.1. With no remaining sidebar controls, render the layout as
  // a single column whenever the parent did not explicitly ask for a sidebar
  // slot to render around.
  const gridCols =
    sidebar === "hidden"
      ? "grid-cols-1"
      : sidebar === "right"
        ? "grid-cols-1 lg:[grid-template-columns:1fr_264px]"
        : "grid-cols-1 lg:[grid-template-columns:264px_1fr]";

  const headerLabel =
    totalCount === 0 ? "No groups" : totalCount === 1 ? "1 group" : `${totalCount} groups`;

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
              {totalCount > 0 ? (
                <>
                  <span className="font-semibold tabular-nums text-fg">
                    {totalCount}
                  </span>{" "}
                  {totalCount === 1 ? "group" : "groups"}
                </>
              ) : (
                <span className="font-semibold text-fg">{headerLabel}</span>
              )}
              {q.trim() && <span> matching &ldquo;{q}&rdquo;</span>}
            </div>
          </div>

          {isLoading ? (
            <div
              aria-busy="true"
              aria-label="Loading groups"
              className="grid gap-3.5 grid-cols-[repeat(auto-fill,minmax(280px,1fr))]"
            >
              {Array.from({ length: SKELETON_COUNT }).map((_, i) => (
                <div
                  key={i}
                  className="h-56 rounded-md border border-border bg-surface-raised animate-pulse"
                />
              ))}
            </div>
          ) : groups.length === 0 ? (
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
            totalPages={pageCount}
            onChange={onPageChange}
          />
        </main>
      </div>
    </div>
  );
}
