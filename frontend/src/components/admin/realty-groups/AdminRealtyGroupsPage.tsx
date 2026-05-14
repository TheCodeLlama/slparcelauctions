"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Pagination } from "@/components/ui/Pagination";
import { useAdminRealtyGroupsList } from "@/hooks/realty/useRealtyGroups";
import type {
  AdminRealtyGroupsFilters,
  AdminRealtyGroupsStatusFilter,
} from "@/types/realty";
import { AdminRealtyGroupsFilterBar } from "./AdminRealtyGroupsFilterBar";
import { AdminRealtyGroupsTable } from "./AdminRealtyGroupsTable";

const DEFAULT_PAGE_SIZE = 25;
const BASE_PATH = "/admin/groups";
const DEFAULT_STATUS: AdminRealtyGroupsStatusFilter = "active";

function isStatus(s: string | null): s is AdminRealtyGroupsStatusFilter {
  return s === "active" || s === "dissolved" || s === "all";
}

function SkeletonRows() {
  return (
    <div className="space-y-2 py-4" aria-busy="true">
      {Array.from({ length: 5 }).map((_, i) => (
        <div
          key={i}
          className="h-12 rounded-lg bg-bg-muted animate-pulse"
        />
      ))}
    </div>
  );
}

/**
 * Admin list page for realty groups. Mirrors the shape of
 * {@code AdminListingsPage}: URL-as-source-of-truth filter state,
 * skeleton + error + empty states, paginated table with row actions.
 */
export function AdminRealtyGroupsPage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const rawStatus = searchParams?.get("status") ?? null;
  const status: AdminRealtyGroupsStatusFilter = isStatus(rawStatus)
    ? rawStatus
    : DEFAULT_STATUS;
  const urlSearch = searchParams?.get("search") ?? "";
  const urlPage = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);
  const urlSizeParsed = parseInt(
    searchParams?.get("size") ?? String(DEFAULT_PAGE_SIZE),
    10,
  );
  const urlSize =
    Number.isFinite(urlSizeParsed) && urlSizeParsed > 0
      ? urlSizeParsed
      : DEFAULT_PAGE_SIZE;

  const [searchInput, setSearchInput] = useState(urlSearch);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- mirror URL when it changes externally.
    setSearchInput(urlSearch);
  }, [urlSearch]);

  const filters: AdminRealtyGroupsFilters = {
    status,
    search: urlSearch || undefined,
    page: urlPage,
    size: urlSize,
  };

  const { data, isLoading, isError } = useAdminRealtyGroupsList(filters);

  function navigate(
    updates: {
      status?: AdminRealtyGroupsStatusFilter;
      search?: string | null;
      page?: number;
    } = {},
  ) {
    const params = new URLSearchParams();
    const nextStatus = updates.status ?? status;
    if (nextStatus !== DEFAULT_STATUS) params.set("status", nextStatus);

    const nextSearch =
      "search" in updates ? updates.search ?? null : urlSearch || null;
    if (nextSearch) params.set("search", nextSearch);

    const nextPage = updates.page ?? 0;
    if (nextPage > 0) params.set("page", String(nextPage));

    if (urlSize !== DEFAULT_PAGE_SIZE) params.set("size", String(urlSize));

    const qs = params.toString();
    router.replace(qs ? `${BASE_PATH}?${qs}` : BASE_PATH);
  }

  // Debounce search updates: write to local state on every keystroke,
  // push to the URL after a short pause.
  useEffect(() => {
    if (searchInput === urlSearch) return;
    const handle = setTimeout(() => {
      navigate({ search: searchInput || null, page: 0 });
    }, 250);
    return () => clearTimeout(handle);
    // navigate is reconstructed each render; we want the latest filter
    // shape but only re-arm the timer when the typed input changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchInput]);

  const total = data?.totalElements ?? 0;

  return (
    <div>
      <div className="flex items-baseline justify-between gap-3 mb-1">
        <h1 className="text-2xl font-semibold">Realty Groups</h1>
        <span
          className="text-xs text-fg-muted"
          data-testid="admin-realty-count"
        >
          {data
            ? `${total.toLocaleString()} group${total === 1 ? "" : "s"}`
            : ""}
        </span>
      </div>
      <p className="text-sm text-fg-muted mb-4">
        All brokerage groups. Use the actions menu to force-edit or
        force-dissolve; click a row for the detail view (members, audit,
        invitations).
      </p>

      <div className="mb-4">
        <AdminRealtyGroupsFilterBar
          status={status}
          onStatusChange={(s) => navigate({ status: s, page: 0 })}
          search={searchInput}
          onSearchChange={setSearchInput}
        />
      </div>

      {isLoading && <SkeletonRows />}

      {isError && (
        <div
          className="text-sm text-danger py-6"
          data-testid="admin-realty-error"
        >
          Could not load realty groups. Refresh to retry.
        </div>
      )}

      {data && (
        <>
          <div className="text-[11px] text-fg-muted mb-2">
            {total === 0
              ? "No groups."
              : `Showing ${urlPage * urlSize + 1}-${Math.min((urlPage + 1) * urlSize, total)} of ${total.toLocaleString()}`}
          </div>

          <AdminRealtyGroupsTable rows={data.content} />

          {data.totalPages > 1 && (
            <div className="mt-4">
              <Pagination
                page={data.number}
                totalPages={data.totalPages}
                onPageChange={(p) => navigate({ page: p })}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
