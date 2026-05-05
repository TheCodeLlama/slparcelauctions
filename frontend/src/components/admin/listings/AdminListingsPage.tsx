"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, useEffect } from "react";
import { useAdminListingsList } from "@/hooks/admin/useAdminListings";
import { AdminListingsTable } from "./AdminListingsTable";
import { ListingsFilterBar } from "./ListingsFilterBar";
import { PresetChips, type Preset } from "./PresetChips";
import { Pagination } from "@/components/ui/Pagination";
import type {
  AdminListingsFilters,
  AdminListingsSort,
  AdminListingsSortColumn,
  AuctionStatus,
} from "@/lib/admin/types";

const DEFAULT_PAGE_SIZE = 25;
const SORT_COLUMNS: AdminListingsSortColumn[] = [
  "title", "seller", "createdAt", "startingBid", "currentBid",
  "bidCount", "saveCount", "endsAt", "region",
];

function isSortColumn(s: string): s is AdminListingsSortColumn {
  return (SORT_COLUMNS as string[]).includes(s);
}

function isAuctionStatus(s: string): s is AuctionStatus {
  return [
    "DRAFT", "DRAFT_PAID", "VERIFICATION_PENDING", "VERIFICATION_FAILED",
    "ACTIVE", "ENDED", "ESCROW_PENDING", "ESCROW_FUNDED",
    "TRANSFER_PENDING", "COMPLETED", "CANCELLED", "EXPIRED",
    "DISPUTED", "SUSPENDED",
  ].includes(s);
}

function SkeletonRows() {
  return (
    <div className="space-y-2 py-4" aria-busy="true">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-12 rounded-lg bg-bg-muted animate-pulse" />
      ))}
    </div>
  );
}

type Props = {
  /** Page route ("/admin/listings" or "/admin/drafts"). URL writes target this path. */
  basePath: string;
  /** When set, forces the status filter to these values and hides the Status dropdown. */
  lockedStatuses?: AuctionStatus[];
  /** Default status filter when the URL has no `status` param and no lock applies. */
  defaultStatuses: AuctionStatus[];
  /** Default sort applied when the URL has no `sort` param. */
  defaultSort: AdminListingsSort;
  /** Title shown above the filter bar. */
  heading: string;
  /** One-line subtitle / description shown under the heading. */
  subheading?: string;
  /** Preset chips to show. Empty array hides the row. */
  presets: Preset[];
};

export function AdminListingsPage({
  basePath,
  lockedStatuses,
  defaultStatuses,
  defaultSort,
  heading,
  subheading,
  presets,
}: Props) {
  const router = useRouter();
  const searchParams = useSearchParams();

  // Read URL state
  const urlSearch = searchParams?.get("search") ?? "";
  const urlPage = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);
  const urlSizeParsed = parseInt(searchParams?.get("size") ?? String(DEFAULT_PAGE_SIZE), 10);
  const urlSize = Number.isFinite(urlSizeParsed) && urlSizeParsed > 0 ? urlSizeParsed : DEFAULT_PAGE_SIZE;

  const urlSort: AdminListingsSort = (() => {
    const raw = searchParams?.get("sort") ?? "";
    const [col, dir] = raw.split(",");
    if (col && isSortColumn(col)) {
      return { column: col, direction: dir === "desc" ? "desc" : "asc" };
    }
    return defaultSort;
  })();

  const urlStatuses: AuctionStatus[] = (() => {
    if (lockedStatuses) return lockedStatuses;
    const all = searchParams?.getAll("status") ?? [];
    const valid = all.filter(isAuctionStatus) as AuctionStatus[];
    return valid.length > 0 ? valid : defaultStatuses;
  })();

  const urlHasReserve: boolean | null = (() => {
    const raw = searchParams?.get("hasReserve");
    if (raw === "true") return true;
    if (raw === "false") return false;
    return null;
  })();

  // Page-size text input local state
  const [sizeInput, setSizeInput] = useState<string>(String(urlSize));
  useEffect(() => { setSizeInput(String(urlSize)); }, [urlSize]);

  const filters: AdminListingsFilters = {
    search: urlSearch || undefined,
    statuses: urlStatuses,
    hasReserve: urlHasReserve,
    page: urlPage,
    size: urlSize,
    sort: urlSort,
  };

  const { data, isLoading, isError } = useAdminListingsList(filters);

  function navigate(updates: {
    search?: string | null;
    statuses?: AuctionStatus[];
    hasReserve?: boolean | null | undefined;
    page?: number;
    size?: number;
    sort?: AdminListingsSort;
  }) {
    const params = new URLSearchParams();
    const nextSearch = "search" in updates ? updates.search : urlSearch;
    if (nextSearch) params.set("search", nextSearch);

    const nextStatuses = updates.statuses ?? (lockedStatuses ? undefined : urlStatuses);
    if (!lockedStatuses && nextStatuses) {
      for (const s of nextStatuses) params.append("status", s);
    }

    const nextHasReserve = "hasReserve" in updates ? updates.hasReserve : urlHasReserve;
    if (nextHasReserve === true) params.set("hasReserve", "true");
    if (nextHasReserve === false) params.set("hasReserve", "false");

    const nextPage = updates.page ?? 0;
    if (nextPage > 0) params.set("page", String(nextPage));

    const nextSize = updates.size ?? urlSize;
    if (nextSize !== DEFAULT_PAGE_SIZE) params.set("size", String(nextSize));

    const nextSort = updates.sort ?? urlSort;
    if (nextSort.column !== defaultSort.column || nextSort.direction !== defaultSort.direction) {
      params.set("sort", `${nextSort.column},${nextSort.direction}`);
    }

    const qs = params.toString();
    router.replace(qs ? `${basePath}?${qs}` : basePath);
  }

  function handleSizeBlur() {
    const parsed = parseInt(sizeInput, 10);
    if (Number.isFinite(parsed) && parsed > 0) {
      navigate({ size: parsed, page: 0 });
    } else {
      setSizeInput(String(urlSize));
    }
  }

  function handlePresetPick(preset: Preset | null) {
    if (preset) {
      navigate({
        statuses: lockedStatuses ?? preset.statuses,
        sort: preset.sort,
        page: 0,
      });
    } else {
      navigate({ statuses: lockedStatuses ?? defaultStatuses, sort: defaultSort, page: 0 });
    }
  }

  const isDirty =
    !!urlSearch ||
    urlHasReserve !== null ||
    (!lockedStatuses && JSON.stringify([...urlStatuses].sort()) !== JSON.stringify([...defaultStatuses].sort())) ||
    urlSort.column !== defaultSort.column ||
    urlSort.direction !== defaultSort.direction;

  return (
    <div>
      <h1 className="text-2xl font-semibold mb-1">{heading}</h1>
      {subheading && <p className="text-sm text-fg-muted mb-4">{subheading}</p>}

      <div className="flex flex-col gap-3 mb-4">
        <ListingsFilterBar
          search={urlSearch}
          onSearchChange={(s) => navigate({ search: s, page: 0 })}
          statuses={urlStatuses}
          onStatusesChange={(s) => navigate({ statuses: s, page: 0 })}
          hasReserve={urlHasReserve}
          onHasReserveChange={(v) => navigate({ hasReserve: v, page: 0 })}
          showStatusFilter={!lockedStatuses}
          onReset={() =>
            navigate({
              search: null,
              statuses: lockedStatuses ?? defaultStatuses,
              hasReserve: null,
              sort: defaultSort,
              page: 0,
            })
          }
          isDirty={isDirty}
        />
        {presets.length > 0 && (
          <PresetChips
            presets={presets}
            current={filters}
            onPick={handlePresetPick}
          />
        )}
      </div>

      {isLoading && <SkeletonRows />}

      {isError && (
        <div className="text-sm text-danger py-6" data-testid="listings-error">
          Could not load listings. Refresh to retry.
        </div>
      )}

      {data && (
        <>
          <div className="text-[11px] text-fg-muted mb-2">
            {data.totalElements === 0
              ? "No listings."
              : `Showing ${urlPage * urlSize + 1}–${Math.min((urlPage + 1) * urlSize, data.totalElements)} of ${data.totalElements.toLocaleString()} listings`}
          </div>

          <AdminListingsTable
            rows={data.content}
            sort={urlSort}
            onSortChange={(s) => navigate({ sort: s, page: 0 })}
          />

          <div className="mt-4 flex items-center justify-between gap-4 flex-wrap">
            <div className="flex-1">
              {data.totalPages > 1 && (
                <Pagination
                  page={data.number}
                  totalPages={data.totalPages}
                  onPageChange={(p) => navigate({ page: p })}
                />
              )}
            </div>
            <label className="flex items-center gap-2 text-[11px] text-fg-muted">
              Per page:
              <input
                type="text"
                inputMode="numeric"
                value={sizeInput}
                onChange={(e) => setSizeInput(e.target.value)}
                onBlur={handleSizeBlur}
                onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }}
                data-testid="listings-page-size-input"
                className="w-16 rounded bg-bg-muted px-2 py-1 text-fg ring-1 ring-border-subtle text-center"
              />
            </label>
          </div>
        </>
      )}
    </div>
  );
}
