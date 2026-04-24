"use client";
import { useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { BottomSheet } from "@/components/ui/BottomSheet";
import { Pagination } from "@/components/ui/Pagination";
import { useAuctionSearch } from "@/hooks/useAuctionSearch";
import { useMediaQuery } from "@/hooks/useMediaQuery";
import {
  defaultAuctionSearchQuery,
  queryFromSearchParams,
  searchParamsFromQuery,
} from "@/lib/search/url-codec";
import { isApiError } from "@/lib/api";
import type { AuctionSearchQuery, SearchResponse } from "@/types/search";
import { ActiveFilters } from "./ActiveFilters";
import { FilterSidebar } from "./FilterSidebar";
import { FilterSidebarContent } from "./FilterSidebarContent";
import { ResultsGrid } from "./ResultsGrid";
import { ResultsHeader } from "./ResultsHeader";

export interface BrowseShellProps {
  initialQuery: AuctionSearchQuery;
  initialData: SearchResponse;
  /** Filters "pinned" by the surrounding page (e.g. {@code sellerId}). */
  fixedFilters?: Partial<AuctionSearchQuery>;
  /** Filter groups to hide (e.g. {@code "distance"} on the seller page). */
  hiddenFilterGroups?: Array<"distance" | "seller">;
  title?: string;
}

const MOBILE_QUERY = "(max-width: 767px)";
const SESSION_KEY = "last-browse-url";

/**
 * Orchestrator for the /browse surface. Owns the single source of truth
 * pattern: the URL. Every filter change → new query → router.replace →
 * useSearchParams update → state re-sync → React Query refetch. The
 * ceremony keeps back/forward navigation, deep links, and shareable URLs
 * all honouring the same state as an in-session filter tweak.
 *
 * Desktop (md+): {@link FilterSidebarContent} renders inside
 * {@link FilterSidebar} in "immediate" mode — every change pushes a new
 * URL.
 *
 * Mobile (< md): the sidebar is hidden. A trigger button in
 * {@link ResultsHeader} opens a {@link BottomSheet} hosting the same
 * component in "staged" mode — changes accumulate locally; Apply commits
 * them in a single URL update. The sheet's content is keyed on
 * {@code sheetOpen} so closing-without-applying remounts the content on
 * next open, discarding any staged-but-unapplied changes.
 *
 * {@code sessionStorage["last-browse-url"]} is written on mount and on
 * every URL change so a detail-page breadcrumb (Task 4) can restore the
 * exact prior browse state on back-navigation.
 */
export function BrowseShell({
  initialQuery,
  initialData,
  fixedFilters,
  hiddenFilterGroups,
  title,
}: BrowseShellProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const isMobile = useMediaQuery(MOBILE_QUERY);
  const [sheetOpen, setSheetOpen] = useState(false);

  // Merge the fixed filters into the initial query so the first render
  // already reflects the pinned constraints (e.g. sellerId on the seller
  // page). fixedFilters ALWAYS wins — it's the page invariant. Lazy
  // initializer so the merge runs once on mount.
  const [query, setQuery] = useState<AuctionSearchQuery>(() => ({
    ...initialQuery,
    ...(fixedFilters ?? {}),
  }));

  // Sync from URL changes (back/forward, programmatic replace). The URL
  // is authoritative — the local state mirrors it. This is the legitimate
  // "external state into React state" pattern: useSearchParams is an
  // external source (the URL bar) and setQuery is how we reflect it.
  useEffect(() => {
    const urlQuery = queryFromSearchParams(
      new URLSearchParams(searchParams.toString()),
    );
    const merged = { ...urlQuery, ...(fixedFilters ?? {}) };
    // eslint-disable-next-line react-hooks/set-state-in-effect -- URL is the external source of truth; mirroring it into local state on change is the whole point of this effect.
    setQuery(merged);
  }, [searchParams, fixedFilters]);

  // Persist the current browse URL so the detail-page breadcrumb can
  // restore the previous browse state on back-navigation.
  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      const qs = searchParams.toString();
      const url = qs ? `${pathname}?${qs}` : pathname;
      window.sessionStorage.setItem(SESSION_KEY, url);
    } catch {
      // sessionStorage can throw in private-mode on some browsers — a
      // missing breadcrumb is better than crashing the page.
    }
  }, [pathname, searchParams]);

  const result = useAuctionSearch(query, { initialData });
  const errorCode =
    result.error && isApiError(result.error)
      ? (result.error.problem?.code as string | undefined)
      : undefined;

  const applyQuery = (next: AuctionSearchQuery) => {
    const merged = { ...next, ...(fixedFilters ?? {}) };
    setQuery(merged);
    const qs = searchParamsFromQuery(merged).toString();
    const url = qs ? `${pathname}?${qs}` : pathname;
    router.replace(url, { scroll: false });
    setSheetOpen(false);
  };

  const handleSortChange = (sort: AuctionSearchQuery["sort"]) =>
    applyQuery({ ...query, sort, page: 0 });

  const handlePageChange = (page: number) =>
    applyQuery({ ...query, page });

  const handleClearFilters = () =>
    applyQuery({ ...defaultAuctionSearchQuery, ...(fixedFilters ?? {}) });

  return (
    <div className="flex min-h-screen">
      <FilterSidebar className="hidden md:flex md:w-72">
        <FilterSidebarContent
          mode="immediate"
          query={query}
          onChange={applyQuery}
          hiddenGroups={hiddenFilterGroups}
          errorCode={errorCode}
        />
      </FilterSidebar>
      <main className="flex-1 flex flex-col gap-4 p-4 md:p-8">
        <ResultsHeader
          title={title}
          total={result.data?.totalElements ?? 0}
          sort={query.sort ?? "newest"}
          onSortChange={handleSortChange}
          onOpenMobile={() => setSheetOpen(true)}
          nearestEnabled={Boolean(query.nearRegion)}
        />
        <ActiveFilters
          query={query}
          onChange={applyQuery}
          fixedFilters={fixedFilters}
        />
        <ResultsGrid
          listings={result.data?.content ?? []}
          isLoading={result.isLoading}
          isError={result.isError}
          errorCode={errorCode}
          query={query}
          onClearFilters={handleClearFilters}
          onRetry={() => result.refetch()}
          fixedFilters={fixedFilters}
        />
        {result.data && result.data.totalPages > 1 && (
          <Pagination
            page={result.data.page}
            totalPages={result.data.totalPages}
            onPageChange={handlePageChange}
            className="mt-4"
          />
        )}
      </main>
      <BottomSheet
        open={sheetOpen && isMobile}
        onClose={() => setSheetOpen(false)}
        title="Filters"
      >
        <FilterSidebarContent
          key={sheetOpen ? "open" : "closed"}
          mode="staged"
          query={query}
          onCommit={applyQuery}
          hiddenGroups={hiddenFilterGroups}
          errorCode={errorCode}
        />
      </BottomSheet>
    </div>
  );
}
