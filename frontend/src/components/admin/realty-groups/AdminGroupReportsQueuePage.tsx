"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useGroupReportsQueue } from "@/hooks/realty/useGroupReports";
import { Pagination } from "@/components/ui/Pagination";
import { cn } from "@/lib/cn";
import type {
  AdminRealtyGroupReportRow,
  AdminRealtyGroupReportsFilters,
  RealtyGroupReportReason,
  RealtyGroupReportStatus,
} from "@/types/realty";

const PAGE_SIZE = 25;
const BASE_PATH = "/admin/groups/reports";

type StatusFilter = "all" | "open" | "resolved" | "dismissed";
const DEFAULT_STATUS: StatusFilter = "open";

const STATUS_PILLS: Array<{ value: StatusFilter; label: string }> = [
  { value: "open", label: "Open" },
  { value: "resolved", label: "Resolved" },
  { value: "dismissed", label: "Dismissed" },
  { value: "all", label: "All" },
];

function parseStatus(raw: string | null): StatusFilter {
  if (raw === "resolved" || raw === "dismissed" || raw === "all" || raw === "open") {
    return raw;
  }
  return DEFAULT_STATUS;
}

function statusToWire(s: StatusFilter): RealtyGroupReportStatus | undefined {
  if (s === "all") return undefined;
  if (s === "open") return "OPEN";
  if (s === "resolved") return "RESOLVED";
  return "DISMISSED";
}

const REASON_LABELS: Record<RealtyGroupReportReason, string> = {
  FRAUDULENT_LISTINGS: "Fraudulent listings",
  MISLEADING_ATTRIBUTION: "Misleading attribution",
  HARASSMENT: "Harassment",
  IMPERSONATION: "Impersonation",
  SPAM: "Spam",
  OTHER: "Other",
};

/**
 * Compact "age" string for a report row (e.g. "3h", "2d", "5w"). Mirrors
 * the listing-reports queue rendering — exact timestamp lives on the
 * detail page.
 */
function ageString(iso: string): string {
  const created = Date.parse(iso);
  if (!Number.isFinite(created)) return "";
  const seconds = Math.max(0, Math.floor((Date.now() - created) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d`;
  const weeks = Math.floor(days / 7);
  return `${weeks}w`;
}

function truncate(s: string, max: number): string {
  if (s.length <= max) return s;
  return `${s.slice(0, max - 1)}…`;
}

function statusPillClasses(status: RealtyGroupReportStatus): string {
  switch (status) {
    case "OPEN":
      return "bg-danger-bg text-danger";
    case "RESOLVED":
      return "bg-info-bg text-info";
    case "DISMISSED":
      return "bg-bg-muted text-fg-muted";
  }
}

function SkeletonRows() {
  return (
    <div className="space-y-2 py-4" aria-busy="true">
      {Array.from({ length: 5 }).map((_, i) => (
        <div
          key={i}
          className="h-14 rounded-lg bg-bg-muted animate-pulse"
        />
      ))}
    </div>
  );
}

/**
 * Global admin queue across every realty-group report.
 *
 * <p>Status defaults to {@code OPEN}; pagination + status filter live in the
 * URL so refreshes and shareable links are first-class. Clicking a row
 * navigates to the detail page (separate route, not a slide-over — see
 * Task 36 plan rationale on why this differs from listing-reports).
 */
export function AdminGroupReportsQueuePage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const status = parseStatus(searchParams?.get("status") ?? null);
  const page = Math.max(
    0,
    parseInt(searchParams?.get("page") ?? "0", 10) || 0,
  );

  const filters: AdminRealtyGroupReportsFilters = {
    status: statusToWire(status),
    page,
    size: PAGE_SIZE,
  };

  const { data, isLoading, isError } = useGroupReportsQueue(filters);

  function buildUrl(overrides: { status?: StatusFilter; page?: number }): string {
    const params = new URLSearchParams();
    const nextStatus = overrides.status ?? status;
    const nextPage = overrides.page ?? page;
    if (nextStatus !== DEFAULT_STATUS) params.set("status", nextStatus);
    if (nextPage > 0) params.set("page", String(nextPage));
    const qs = params.toString();
    return qs ? `${BASE_PATH}?${qs}` : BASE_PATH;
  }

  const handleStatusChange = (s: StatusFilter) => {
    router.replace(buildUrl({ status: s, page: 0 }), { scroll: false });
  };

  const handlePage = (p: number) => {
    router.replace(buildUrl({ page: p }), { scroll: false });
  };

  return (
    <div>
      <div className="flex items-baseline justify-between mb-1">
        <h1 className="text-2xl font-semibold">Group Reports</h1>
        <span
          className="text-xs text-fg-muted"
          data-testid="admin-group-reports-count"
        >
          {data
            ? `${data.totalElements.toLocaleString()} report${
                data.totalElements === 1 ? "" : "s"
              }`
            : ""}
        </span>
      </div>
      <p className="text-sm text-fg-muted mb-4">
        User-submitted reports filed against realty groups. Click a row to
        review the full report and resolve or dismiss it.
      </p>

      <div
        className="mb-4 flex items-center gap-2"
        role="group"
        aria-label="Report status filter"
      >
        {STATUS_PILLS.map((pill) => (
          <button
            key={pill.value}
            type="button"
            onClick={() => handleStatusChange(pill.value)}
            data-testid={`group-reports-status-${pill.value}`}
            className={cn(
              "px-3 py-1.5 rounded-full text-[11px] font-medium transition-colors",
              status === pill.value
                ? "bg-info-bg text-info"
                : "bg-bg-muted text-fg-muted hover:bg-bg-hover",
            )}
          >
            {pill.label}
          </button>
        ))}
      </div>

      {isLoading && <SkeletonRows />}

      {isError && (
        <div
          className="text-sm text-danger py-6"
          data-testid="admin-group-reports-error"
        >
          Could not load reports. Refresh to retry.
        </div>
      )}

      {data && data.content.length === 0 && (
        <div
          data-testid="admin-group-reports-empty"
          className="text-sm text-fg-muted py-8 text-center"
        >
          No reports match the current filter.
        </div>
      )}

      {data && data.content.length > 0 && (
        <>
          <ul
            className="flex flex-col gap-2"
            data-testid="admin-group-reports-table"
          >
            {data.content.map((row) => (
              <ReportRow key={row.publicId} row={row} />
            ))}
          </ul>
          {data.totalPages > 1 && (
            <div className="mt-4">
              <Pagination
                page={data.number}
                totalPages={data.totalPages}
                onPageChange={handlePage}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}

function ReportRow({ row }: { row: AdminRealtyGroupReportRow }) {
  const href = `/admin/groups/reports/${row.publicId}`;
  return (
    <li>
      <Link
        href={href}
        data-testid={`admin-group-report-row-${row.publicId}`}
        className="block rounded-lg border border-border-subtle bg-surface-raised px-4 py-3 transition-colors hover:bg-bg-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      >
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 flex-wrap mb-1">
              <span
                className={cn(
                  "text-[10px] font-medium px-2 py-0.5 rounded-full uppercase tracking-wider",
                  statusPillClasses(row.status),
                )}
              >
                {row.status}
              </span>
              <span className="text-sm font-semibold text-fg truncate">
                {row.groupName}
              </span>
            </div>
            <div className="text-xs text-fg-muted">
              <span>{REASON_LABELS[row.reason] ?? row.reason}</span>
              <span className="mx-1">·</span>
              <span>Reported by {row.reporter.displayName}</span>
            </div>
          </div>
          <div className="text-[11px] text-fg-muted shrink-0 pt-0.5">
            {ageString(row.createdAt)}
          </div>
        </div>
        {/* details preview slot — backend's row DTO is currently compact and
            omits the details body; we leave this hook in for forward-compat
            with detailsPreview if the wire shape grows. */}
        {hasDetailsPreview(row) && (
          <div className="mt-1 text-xs text-fg-muted truncate">
            {truncate((row as { detailsPreview: string }).detailsPreview, 80)}
          </div>
        )}
      </Link>
    </li>
  );
}

function hasDetailsPreview(
  row: AdminRealtyGroupReportRow,
): row is AdminRealtyGroupReportRow & { detailsPreview: string } {
  const maybe = (row as { detailsPreview?: unknown }).detailsPreview;
  return typeof maybe === "string" && maybe.length > 0;
}
