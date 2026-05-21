"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Pagination } from "@/components/ui/Pagination";
import { useMySupportTickets } from "@/hooks/useMySupportTickets";
import { formatRelativeTime } from "@/lib/time/relativeTime";
import type {
  SupportTicketCategory,
  SupportTicketStatus,
  SupportTicketSummaryDto,
} from "@/types/support";

const PAGE_SIZE = 20;

type StatusFilter = "all" | SupportTicketStatus;

function parseStatus(raw: string | null): StatusFilter {
  if (raw === "OPEN" || raw === "RESOLVED") return raw;
  return "all";
}

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  ACCOUNT: "Account",
  BIDDING: "Bidding",
  LISTING: "Listing",
  ESCROW: "Escrow",
  WALLET: "Wallet",
  OTHER: "Other",
};

function statusPillClass(status: SupportTicketStatus): string {
  if (status === "OPEN") return "bg-warning-bg text-warning";
  return "bg-success-bg text-success";
}

function statusLabel(status: SupportTicketStatus): string {
  return status === "OPEN" ? "Open" : "Resolved";
}

function EmptyState() {
  return (
    <div
      className="rounded-lg border border-border-subtle bg-surface-raised py-12 px-6 text-center flex flex-col items-center gap-3"
      data-testid="support-tickets-empty"
    >
      <p className="text-sm text-fg-muted">
        No tickets yet. Need help? Click &quot;New ticket&quot; to get started.
      </p>
      <Link href="/support/new">
        <Button variant="primary" size="sm" data-testid="empty-new-ticket-btn">
          New ticket
        </Button>
      </Link>
    </div>
  );
}

export function SupportTicketList() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const status = parseStatus(searchParams?.get("status") ?? null);
  const page = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);

  // Send `status` to the backend only when a concrete filter is selected;
  // the "all" sentinel stays out of the URL and out of the query so we share
  // the cache entry across no-filter navigations.
  const params = {
    status: status === "all" ? undefined : status,
    page,
    size: PAGE_SIZE,
  };

  const { data, isLoading, isError } = useMySupportTickets(params);

  function buildUrl(overrides: {
    status?: StatusFilter;
    page?: number;
  }): string {
    const sp = new URLSearchParams();
    const nextStatus = overrides.status ?? status;
    const nextPage = overrides.page ?? 0;
    if (nextStatus !== "all") sp.set("status", nextStatus);
    if (nextPage > 0) sp.set("page", String(nextPage));
    const qs = sp.toString();
    return qs ? `/support?${qs}` : "/support";
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight font-display">
            Support
          </h1>
          <p className="text-sm text-fg-muted mt-1">
            Open a ticket and our team will reply here.
          </p>
        </div>
        <Link href="/support/new">
          <Button
            variant="primary"
            size="sm"
            data-testid="new-ticket-btn"
          >
            New ticket
          </Button>
        </Link>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-2 text-xs text-fg-muted">
          Status
          <select
            value={status}
            data-testid="status-select"
            aria-label="Filter by status"
            onChange={(e) =>
              router.replace(
                buildUrl({
                  status: e.target.value as StatusFilter,
                  page: 0,
                }),
                { scroll: false },
              )
            }
            className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
          >
            <option value="all">All</option>
            <option value="OPEN">Open</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </label>
      </div>

      {isLoading && <LoadingSpinner label="Loading tickets..." />}

      {isError && (
        <div className="text-sm text-danger py-8" role="alert">
          Could not load tickets. Refresh to retry.
        </div>
      )}

      {data && data.content.length === 0 && <EmptyState />}

      {data && data.content.length > 0 && (
        <>
          <div
            className="overflow-x-auto rounded-lg border border-border-subtle"
            data-testid="support-tickets-table"
          >
            <table className="w-full text-sm">
              <thead className="bg-bg-subtle border-b border-border-subtle">
                <tr>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Subject
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Category
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Status
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Last updated
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((row: SupportTicketSummaryDto) => {
                  const adminReplied =
                    row.lastMessageAuthor === "ADMIN" && row.status === "OPEN";
                  return (
                    <tr
                      key={row.publicId}
                      data-testid={`support-ticket-row-${row.publicId}`}
                      className="border-b border-border-subtle/50 hover:bg-bg-muted/50"
                    >
                      <td className="px-3 py-2.5">
                        <Link
                          href={`/support/${row.publicId}`}
                          className="text-fg font-medium hover:underline"
                          data-testid={`support-ticket-subject-${row.publicId}`}
                        >
                          {row.subject}
                        </Link>
                      </td>
                      <td className="px-3 py-2.5">
                        <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-medium bg-info-bg text-info">
                          {CATEGORY_LABELS[row.category]}
                        </span>
                      </td>
                      <td className="px-3 py-2.5">
                        <div className="flex flex-col gap-0.5">
                          <span
                            className={`inline-flex w-fit items-center px-2 py-0.5 rounded-full text-[10px] font-semibold ${statusPillClass(row.status)}`}
                          >
                            {statusLabel(row.status)}
                          </span>
                          {adminReplied && (
                            <span className="text-[10px] text-fg-muted">
                              admin replied
                            </span>
                          )}
                        </div>
                      </td>
                      <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                        {formatRelativeTime(row.lastMessageAt)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {data.totalPages > 1 && (
            <div>
              <Pagination
                page={data.number}
                totalPages={data.totalPages}
                onPageChange={(p) =>
                  router.replace(buildUrl({ page: p }), { scroll: false })
                }
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
