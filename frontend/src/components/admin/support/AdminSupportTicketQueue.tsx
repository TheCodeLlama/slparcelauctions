"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useAdminSupportTickets } from "@/hooks/admin/useAdminSupportTickets";
import { Pagination } from "@/components/ui/Pagination";
import { formatRelativeTime } from "@/lib/time/relativeTime";
import type {
  AdminSupportTicketQueueRow,
  SupportTicketCategory,
  SupportTicketStatus,
} from "@/types/support";

const PAGE_SIZE = 25;

type StatusFilter = "all" | SupportTicketStatus;
type CategoryFilter = "ALL" | SupportTicketCategory;
type AssigneeFilter = "all" | "mine" | "unassigned";
type LastAuthorFilter = "all" | "USER" | "ADMIN";

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  ACCOUNT: "Account",
  BIDDING: "Bidding",
  LISTING: "Listing",
  ESCROW: "Escrow",
  WALLET: "Wallet",
  OTHER: "Other",
};

function parseStatus(raw: string | null): StatusFilter {
  if (raw === "OPEN" || raw === "RESOLVED") return raw;
  return "all";
}

function parseCategory(raw: string | null): CategoryFilter {
  if (
    raw === "ACCOUNT" ||
    raw === "BIDDING" ||
    raw === "LISTING" ||
    raw === "ESCROW" ||
    raw === "WALLET" ||
    raw === "OTHER"
  ) {
    return raw;
  }
  return "ALL";
}

function parseAssignee(raw: string | null): AssigneeFilter {
  if (raw === "mine" || raw === "unassigned") return raw;
  return "all";
}

function parseLastAuthor(raw: string | null): LastAuthorFilter {
  if (raw === "USER" || raw === "ADMIN") return raw;
  return "all";
}

function statusPillClass(status: SupportTicketStatus): string {
  if (status === "OPEN") return "bg-warning-bg text-warning";
  return "bg-success-bg text-success";
}

function statusLabel(status: SupportTicketStatus): string {
  return status === "OPEN" ? "Open" : "Resolved";
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

export function AdminSupportTicketQueue() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const q = searchParams?.get("q") ?? "";
  const status = parseStatus(searchParams?.get("status") ?? null);
  const category = parseCategory(searchParams?.get("category") ?? null);
  const assignee = parseAssignee(searchParams?.get("assignee") ?? null);
  const lastAuthor = parseLastAuthor(searchParams?.get("last_author") ?? null);
  const page = Math.max(
    0,
    parseInt(searchParams?.get("page") ?? "0", 10) || 0,
  );

  // Wire encoding matches the backend service's `listAdmin` switch:
  //   - "mine" -> server resolves to the caller's adminId via AuthPrincipal
  //   - "unassigned" -> tickets with no assigned admin
  //   - a UUID string -> tickets SUBMITTED BY that user (not used by the UI today)
  const assigneeParam =
    assignee === "all" ? undefined : assignee;

  const params = {
    q: q || undefined,
    status: status === "all" ? undefined : status,
    category: category === "ALL" ? undefined : category,
    assignee: assigneeParam,
    last_author: lastAuthor === "all" ? undefined : lastAuthor,
    page,
    size: PAGE_SIZE,
  };

  const { data, isLoading, isError } = useAdminSupportTickets(params);

  function buildUrl(overrides: {
    q?: string;
    status?: StatusFilter;
    category?: CategoryFilter;
    assignee?: AssigneeFilter;
    lastAuthor?: LastAuthorFilter;
    page?: number;
  }): string {
    const sp = new URLSearchParams();
    const nextQ = overrides.q !== undefined ? overrides.q : q;
    const nextStatus = overrides.status ?? status;
    const nextCategory = overrides.category ?? category;
    const nextAssignee = overrides.assignee ?? assignee;
    const nextLastAuthor = overrides.lastAuthor ?? lastAuthor;
    const nextPage = overrides.page ?? 0;
    if (nextQ) sp.set("q", nextQ);
    if (nextStatus !== "all") sp.set("status", nextStatus);
    if (nextCategory !== "ALL") sp.set("category", nextCategory);
    if (nextAssignee !== "all") sp.set("assignee", nextAssignee);
    if (nextLastAuthor !== "all") sp.set("last_author", nextLastAuthor);
    if (nextPage > 0) sp.set("page", String(nextPage));
    const qs = sp.toString();
    return qs ? `/admin/support?${qs}` : "/admin/support";
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Support</h1>
      </div>

      <div className="flex flex-wrap items-center gap-3 mb-4">
        <input
          key={q}
          defaultValue={q}
          placeholder="Search by subject"
          data-testid="support-search-input"
          aria-label="Search tickets by subject"
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              const v = (e.target as HTMLInputElement).value.trim();
              router.replace(buildUrl({ q: v || "", page: 0 }), {
                scroll: false,
              });
            }
          }}
          className="flex-1 min-w-[240px] rounded-lg bg-bg-muted px-4 py-2 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
        />

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

        <label className="flex items-center gap-2 text-xs text-fg-muted">
          Category
          <select
            value={category}
            data-testid="category-select"
            aria-label="Filter by category"
            onChange={(e) =>
              router.replace(
                buildUrl({
                  category: e.target.value as CategoryFilter,
                  page: 0,
                }),
                { scroll: false },
              )
            }
            className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
          >
            <option value="ALL">Any</option>
            <option value="ACCOUNT">Account</option>
            <option value="BIDDING">Bidding</option>
            <option value="LISTING">Listing</option>
            <option value="ESCROW">Escrow</option>
            <option value="WALLET">Wallet</option>
            <option value="OTHER">Other</option>
          </select>
        </label>

        <label className="flex items-center gap-2 text-xs text-fg-muted">
          Assignee
          <select
            value={assignee}
            data-testid="assignee-select"
            aria-label="Filter by assignee"
            onChange={(e) =>
              router.replace(
                buildUrl({
                  assignee: e.target.value as AssigneeFilter,
                  page: 0,
                }),
                { scroll: false },
              )
            }
            className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
          >
            <option value="all">All</option>
            <option value="mine">Mine</option>
            <option value="unassigned">Unassigned</option>
          </select>
        </label>

        <label className="flex items-center gap-2 text-xs text-fg-muted">
          Last reply
          <select
            value={lastAuthor}
            data-testid="last-author-select"
            aria-label="Filter by last author"
            onChange={(e) =>
              router.replace(
                buildUrl({
                  lastAuthor: e.target.value as LastAuthorFilter,
                  page: 0,
                }),
                { scroll: false },
              )
            }
            className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
          >
            <option value="all">All</option>
            <option value="USER">Needs admin reply</option>
            <option value="ADMIN">Waiting on user</option>
          </select>
        </label>
      </div>

      {isLoading && <SkeletonRows />}

      {isError && (
        <div className="text-sm text-danger py-8" role="alert">
          Could not load tickets. Refresh to retry.
        </div>
      )}

      {data && data.content.length === 0 && (
        <div
          className="py-12 text-center text-sm text-fg-muted"
          data-testid="support-tickets-empty"
        >
          No tickets match these filters.
        </div>
      )}

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
                    Submitter
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Category
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Status
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Last activity
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Assigned to
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((row: AdminSupportTicketQueueRow) => {
                  const needsReply =
                    row.lastMessageAuthor === "USER" && row.status === "OPEN";
                  return (
                    <tr
                      key={row.publicId}
                      data-testid={`support-ticket-row-${row.publicId}`}
                      className="border-b border-border-subtle/50 hover:bg-bg-muted/50"
                    >
                      <td className="px-3 py-2.5">
                        <Link
                          href={`/admin/support/${row.publicId}`}
                          className="text-fg font-medium hover:underline"
                          data-testid={`support-ticket-subject-${row.publicId}`}
                        >
                          {row.subject}
                        </Link>
                      </td>
                      <td className="px-3 py-2.5 text-fg text-[12px]">
                        {row.submitterDisplayName}
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
                          {needsReply && (
                            <span className="text-[10px] text-warning">
                              needs admin reply
                            </span>
                          )}
                        </div>
                      </td>
                      <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                        {formatRelativeTime(row.lastMessageAt)}
                      </td>
                      <td className="px-3 py-2.5 text-fg-muted text-[12px]">
                        {row.assignedAdminDisplayName ?? (
                          <span className="text-fg-muted/60">Unassigned</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {data.totalPages > 1 && (
            <div className="mt-4">
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
