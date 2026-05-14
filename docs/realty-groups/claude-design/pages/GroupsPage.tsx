// export/realty-groups/pages/GroupsPage.tsx
"use client";

import { useMemo, useState } from "react";
import { ArrowDown, ArrowUp, ChevronRight, Plus, Search } from "lucide-react";
import type {
  GroupCardLayout,
  GroupSidebarPlacement,
  GroupsSortKey,
  RealtyGroupCard,
  SortDirection,
} from "../types";
import { cn } from "../lib/cn";
import { Btn } from "../components/Btn";
import { Checkbox } from "../components/Checkbox";
import { EmptyGroups } from "../components/EmptyGroups";
import { FilterGroup } from "../components/FilterGroup";
import { GroupCard } from "../components/GroupCard";
import { Pagination } from "../components/Pagination";
import { StarPicker } from "../components/StarPicker";

interface GroupsPageProps {
  groups: RealtyGroupCard[];
  cardLayout?: GroupCardLayout;
  sidebar?: GroupSidebarPlacement;
  perPage?: number;
  onOpenGroup?: (group: RealtyGroupCard) => void;
  onStartGroup?: () => void;
  onHome?: () => void;
}

const SORT_OPTIONS: Array<[GroupsSortKey, string]> = [
  ["ACTIVE_LISTINGS", "Active listings"],
  ["AGE", "Age"],
  ["NAME", "Name"],
  ["RATING", "Rating"],
  ["SALES", "Sales"],
];

export function GroupsPage({
  groups,
  cardLayout = "cover",
  sidebar = "left",
  perPage = 12,
  onOpenGroup,
  onStartGroup,
  onHome,
}: GroupsPageProps) {
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<GroupsSortKey>("RATING");
  const [sortDir, setSortDir] = useState<SortDirection>("desc");
  const [activeOnly, setActiveOnly] = useState(false);
  const [minRating, setMinRating] = useState(0);
  const [minReviews, setMinReviews] = useState(0);
  const [page, setPage] = useState(0);

  const filtered = useMemo(() => {
    let r = groups.filter((g) => g.hasVerifiedSlGroup);
    if (activeOnly) r = r.filter((g) => g.activeListingsCount > 0);
    if (minRating > 0)
      r = r.filter((g) => (g.rating.averageRating ?? 0) >= minRating);
    if (minReviews > 0) r = r.filter((g) => g.rating.reviewCount >= minReviews);
    if (q.trim()) {
      const needle = q.trim().toLowerCase();
      r = r.filter(
        (g) =>
          g.name.toLowerCase().includes(needle) ||
          g.tagline.toLowerCase().includes(needle),
      );
    }
    return r;
  }, [groups, activeOnly, minRating, minReviews, q]);

  const sorted = useMemo(() => {
    const list = [...filtered];
    const cmp = (a: RealtyGroupCard, b: RealtyGroupCard) => {
      if (sort === "RATING")
        return (a.rating.averageRating ?? 0) - (b.rating.averageRating ?? 0);
      if (sort === "AGE")
        return new Date(a.foundedAt).getTime() - new Date(b.foundedAt).getTime();
      if (sort === "ACTIVE_LISTINGS")
        return a.activeListingsCount - b.activeListingsCount;
      if (sort === "SALES") return a.completedSalesCount - b.completedSalesCount;
      const an = a.name.toLowerCase();
      const bn = b.name.toLowerCase();
      return an < bn ? -1 : an > bn ? 1 : 0;
    };
    list.sort((a, b) => (sortDir === "desc" ? -cmp(a, b) : cmp(a, b)));
    return list;
  }, [filtered, sort, sortDir]);

  const totalPages = Math.max(1, Math.ceil(sorted.length / perPage));
  const visible = sorted.slice(page * perPage, (page + 1) * perPage);

  const resetPage = () => setPage(0);
  const clearAll = () => {
    setQ("");
    setActiveOnly(false);
    setMinRating(0);
    setMinReviews(0);
    setPage(0);
  };

  const sidebarContent = (
    <aside className="sticky top-[calc(var(--header-h,60px)+16px)] h-fit">
      <FilterGroup title="Minimum rating">
        <StarPicker
          value={minRating}
          onChange={(v) => {
            setMinRating(v);
            resetPage();
          }}
        />
      </FilterGroup>

      <FilterGroup title="Minimum reviews">
        <input
          className="w-full px-3 py-2 rounded-md border border-border bg-surface-raised text-fg text-sm focus:outline-none focus:border-brand"
          type="number"
          min={0}
          step={1}
          value={minReviews}
          onChange={(e) => {
            setMinReviews(Math.max(0, Number(e.target.value) || 0));
            resetPage();
          }}
          placeholder="0"
        />
      </FilterGroup>

      <FilterGroup title="Listings">
        <Checkbox
          label="Has active listing"
          checked={activeOnly}
          onChange={() => {
            setActiveOnly(!activeOnly);
            resetPage();
          }}
        />
      </FilterGroup>

      <Btn variant="ghost" size="sm" block onClick={clearAll}>
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
            Verified seller collectives &mdash; find a group that specializes in the
            parcel type you&rsquo;re after.
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
                onChange={(e) => {
                  setQ(e.target.value);
                  resetPage();
                }}
                placeholder="Search groups by name or specialty\u2026"
              />
            </div>
            <select
              className="w-44 px-3 rounded-md border border-border bg-surface-raised text-fg text-sm focus:outline-none focus:border-brand h-[42px]"
              value={sort}
              onChange={(e) => {
                setSort(e.target.value as GroupsSortKey);
                resetPage();
              }}
            >
              {SORT_OPTIONS.map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={() => setSortDir(sortDir === "desc" ? "asc" : "desc")}
              title={
                sortDir === "desc"
                  ? "Descending \u2014 click to flip"
                  : "Ascending \u2014 click to flip"
              }
              className="h-[42px] px-3 border border-border bg-surface-raised text-fg text-sm font-medium inline-flex items-center gap-1.5 rounded-md cursor-pointer hover:bg-bg-muted"
            >
              {sortDir === "desc" ? (
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
                {sorted.length}
              </span>{" "}
              {sorted.length === 1 ? "group" : "groups"}
              {q.trim() && <span> matching &ldquo;{q}&rdquo;</span>}
            </div>
            <div className="flex items-center gap-2">
              {(activeOnly || minRating > 0 || minReviews > 0 || q) && (
                <Btn variant="ghost" size="sm" onClick={clearAll}>
                  Clear
                </Btn>
              )}
            </div>
          </div>

          {visible.length === 0 ? (
            <EmptyGroups query={q} onClear={clearAll} />
          ) : (
            <div className="grid gap-3.5 grid-cols-[repeat(auto-fill,minmax(280px,1fr))]">
              {visible.map((g) => (
                <GroupCard
                  key={g.publicId}
                  group={g}
                  layout={cardLayout}
                  onClick={() => onOpenGroup?.(g)}
                />
              ))}
            </div>
          )}

          <Pagination page={page} totalPages={totalPages} onChange={setPage} />
        </main>

        {sidebar === "right" && (
          <div className="hidden lg:block">{sidebarContent}</div>
        )}
      </div>
    </div>
  );
}
