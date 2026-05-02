"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { useAdminReportsList } from "@/hooks/admin/useAdminReportsList";
import { AdminReportsFilters, type ReportListStatus } from "./AdminReportsFilters";
import { AdminReportsTable } from "./AdminReportsTable";
import { AdminReportSlideOver } from "./AdminReportSlideOver";
import { Pagination } from "@/components/ui/Pagination";

const PAGE_SIZE = 25;

function parseAuctionId(raw: string | null): number | null {
  if (!raw) return null;
  const n = parseInt(raw, 10);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function parseStatus(raw: string | null): ReportListStatus {
  if (raw === "reviewed" || raw === "all") return raw;
  return "open";
}

export function AdminReportsListPage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const status = parseStatus(searchParams?.get("status") ?? null);
  const page = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);
  const auctionId = parseAuctionId(searchParams?.get("auctionId") ?? null);

  const { data: list, isLoading, isError } = useAdminReportsList({
    status,
    page,
    size: PAGE_SIZE,
  });

  const ids = list?.content.map((r) => r.auctionId) ?? [];
  const idx = auctionId !== null ? ids.indexOf(auctionId) : -1;
  const listingRow = auctionId !== null
    ? (list?.content.find((r) => r.auctionId === auctionId) ?? null)
    : null;

  function buildUrl(overrides: {
    status?: ReportListStatus;
    page?: number;
    auctionId?: number | null;
  }): string {
    const params = new URLSearchParams();
    const nextStatus = overrides.status ?? status;
    const nextPage = overrides.page ?? page;
    const nextAuctionId = "auctionId" in overrides ? overrides.auctionId : auctionId;
    if (nextStatus !== "open") params.set("status", nextStatus);
    if (nextPage > 0) params.set("page", String(nextPage));
    if (nextAuctionId !== null && nextAuctionId !== undefined)
      params.set("auctionId", String(nextAuctionId));
    const qs = params.toString();
    return qs ? `/admin/reports?${qs}` : "/admin/reports";
  }

  const handleStatusChange = (s: ReportListStatus) => {
    router.replace(buildUrl({ status: s, page: 0, auctionId: null }), { scroll: false });
  };

  const handleSelect = (id: number) => {
    router.replace(buildUrl({ auctionId: id }), { scroll: false });
  };

  const handleClose = () => {
    router.replace(buildUrl({ auctionId: null }), { scroll: false });
  };

  const handlePrev = () => {
    if (idx > 0)
      router.replace(buildUrl({ auctionId: ids[idx - 1] }), { scroll: false });
  };

  const handleNext = () => {
    if (idx < ids.length - 1)
      router.replace(buildUrl({ auctionId: ids[idx + 1] }), { scroll: false });
  };

  const handlePage = (p: number) => {
    router.replace(buildUrl({ page: p, auctionId: null }), { scroll: false });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Reports</h1>
      </div>

      <div className="mb-4">
        <AdminReportsFilters status={status} onStatusChange={handleStatusChange} />
      </div>

      {isLoading && (
        <div className="text-sm text-fg-muted py-8">Loading…</div>
      )}

      {isError && (
        <div className="text-sm text-danger-flat py-8">
          Could not load reports. Refresh to retry.
        </div>
      )}

      {list && (
        <>
          <AdminReportsTable
            rows={list.content}
            selectedAuctionId={auctionId}
            onSelect={handleSelect}
          />
          {list.totalPages > 1 && (
            <div className="mt-4">
              <Pagination
                page={list.number}
                totalPages={list.totalPages}
                onPageChange={handlePage}
              />
            </div>
          )}
        </>
      )}

      <AdminReportSlideOver
        auctionId={auctionId}
        listingRow={listingRow}
        hasPrev={idx > 0}
        hasNext={idx !== -1 && idx < ids.length - 1}
        onPrev={handlePrev}
        onNext={handleNext}
        onClose={handleClose}
      />
    </div>
  );
}
