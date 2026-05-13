"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { Card } from "@/components/ui/Card";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { ArrowDown, ArrowUp, ArrowUpDown } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { useCommissionAnalytics } from "@/hooks/realty/useCommissionAnalytics";
import { useRealtyGroup } from "@/hooks/realty/useRealtyGroups";
import { isApiError } from "@/lib/api";
import type { MemberCommissionRow } from "@/types/realty";
import { MemberCommissionBars } from "./MemberCommissionBars";

export interface GroupCommissionAnalyticsPageProps {
  /** Group public UUID from the URL segment. */
  groupPublicId: string;
}

type SortColumn = "displayName" | "lifetime" | "last30Days";
type SortDirection = "asc" | "desc";

type SortState = { column: SortColumn; direction: SortDirection };

/** Default sort: lifetime descending — leaders care about top earners first. */
const DEFAULT_SORT: SortState = { column: "lifetime", direction: "desc" };

function compareRows(
  a: MemberCommissionRow,
  b: MemberCommissionRow,
  sort: SortState,
): number {
  let cmp = 0;
  if (sort.column === "displayName") {
    cmp = a.displayName.localeCompare(b.displayName, "en", { sensitivity: "base" });
  } else if (sort.column === "lifetime") {
    cmp = a.lifetimeLindens - b.lifetimeLindens;
  } else {
    cmp = a.last30DaysLindens - b.last30DaysLindens;
  }
  return sort.direction === "asc" ? cmp : -cmp;
}

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

const COLUMNS: Array<{
  key: SortColumn;
  label: string;
  align: "left" | "right";
}> = [
  { key: "displayName", label: "Member", align: "left" },
  { key: "lifetime", label: "Lifetime L$", align: "right" },
  { key: "last30Days", label: "Last 30 days L$", align: "right" },
];

/**
 * Realty Groups: F — leader commission analytics view (spec §15.2).
 *
 * <p>Composes:
 *
 *  - Sortable table of per-member lifetime + last-30-day commission totals.
 *  - Bar chart below the table ({@link MemberCommissionBars}) for at-a-glance
 *    "who's earning what" comparison.
 *  - Empty state when the backend returns zero rows.
 *  - Permission-denied state (HTTP 403) — the backend enforces
 *    leader-or-{@code MANAGE_MEMBERS} gating; non-eligible users see a
 *    "go back to group page" affordance rather than a redirect (the spec
 *    permits either, and an in-page error preserves the URL for sharing
 *    once permissions change).
 */
export function GroupCommissionAnalyticsPage({
  groupPublicId,
}: GroupCommissionAnalyticsPageProps) {
  const [sort, setSort] = useState<SortState>(DEFAULT_SORT);

  const groupQuery = useRealtyGroup(groupPublicId);
  const analyticsQuery = useCommissionAnalytics(groupPublicId);

  const sortedRows = useMemo(() => {
    const data = analyticsQuery.data ?? [];
    return [...data].sort((a, b) => compareRows(a, b, sort));
  }, [analyticsQuery.data, sort]);

  function handleHeaderClick(col: SortColumn) {
    setSort((prev) => {
      if (prev.column === col) {
        return { column: col, direction: prev.direction === "asc" ? "desc" : "asc" };
      }
      // Switching column: lifetime / last30Days start desc (large first);
      // member starts asc (A→Z is the natural directory order).
      return { column: col, direction: col === "displayName" ? "asc" : "desc" };
    });
  }

  // Permission-denied: backend returned 403. Render a non-fatal notice with
  // a link back to the group page; do not redirect because the URL is
  // shareable and the caller's permissions may change later.
  if (analyticsQuery.error && isApiError(analyticsQuery.error) && analyticsQuery.error.status === 403) {
    return (
      <div className="flex flex-col gap-3" data-testid="commission-analytics-forbidden">
        <h1 className="text-xl font-semibold tracking-tight text-fg">
          Commission analytics
        </h1>
        <Card>
          <Card.Body>
            <h2 className="text-sm font-semibold text-fg mb-1">
              You do not have permission to view these analytics.
            </h2>
            <p className="text-sm text-fg-muted">
              Only the group leader and members with the{" "}
              <strong>Manage Members</strong> permission can see per-member
              commission totals.
            </p>
            <Link
              href={
                groupQuery.data?.slug
                  ? `/groups/${encodeURIComponent(groupQuery.data.slug)}`
                  : "/groups"
              }
              className="mt-3 inline-block text-sm text-brand hover:underline"
              data-testid="commission-analytics-back-link"
            >
              Back to group page
            </Link>
          </Card.Body>
        </Card>
      </div>
    );
  }

  if (analyticsQuery.isPending) {
    return <LoadingSpinner label="Loading analytics..." />;
  }

  if (analyticsQuery.error) {
    return (
      <div className="flex flex-col gap-3" data-testid="commission-analytics-error">
        <h1 className="text-xl font-semibold tracking-tight text-fg">
          Commission analytics
        </h1>
        <Card>
          <Card.Body>
            <p className="text-sm text-fg">
              {analyticsQuery.error instanceof Error
                ? analyticsQuery.error.message
                : "Failed to load commission analytics."}
            </p>
          </Card.Body>
        </Card>
      </div>
    );
  }

  const rows = sortedRows;
  const isEmpty = rows.length === 0 || rows.every((r) => r.lifetimeLindens === 0);

  return (
    <div className="flex flex-col gap-4" data-testid="commission-analytics-page">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight text-fg">
          Commission analytics
        </h1>
        <p className="text-sm text-fg-muted" data-testid="commission-analytics-group-name">
          {groupQuery.data?.name ?? " "}
        </p>
      </div>

      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Per-member commissions
          </h2>
        </Card.Header>
        <Card.Body>
          {isEmpty ? (
            <div
              className="py-8 text-center text-sm text-fg-muted"
              data-testid="commission-analytics-empty"
            >
              No commissions paid out yet.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table
                className="w-full text-sm"
                data-testid="commission-analytics-table"
              >
                <thead className="border-b border-border-subtle">
                  <tr>
                    {COLUMNS.map((c) => {
                      const isActive = sort.column === c.key;
                      const Icon = isActive
                        ? sort.direction === "asc"
                          ? ArrowUp
                          : ArrowDown
                        : ArrowUpDown;
                      const alignClass =
                        c.align === "right" ? "text-right" : "text-left";
                      return (
                        <th
                          key={c.key}
                          className={cn(
                            "px-3 py-2 text-[11px] font-medium text-fg-muted uppercase tracking-wider",
                            alignClass,
                          )}
                          aria-sort={
                            isActive
                              ? sort.direction === "asc"
                                ? "ascending"
                                : "descending"
                              : "none"
                          }
                        >
                          <button
                            type="button"
                            onClick={() => handleHeaderClick(c.key)}
                            className={cn(
                              "inline-flex items-center gap-1 hover:text-fg",
                              isActive && "text-fg",
                              c.align === "right" && "ml-auto",
                            )}
                            data-testid={`commission-analytics-sort-${c.key}`}
                          >
                            <span>{c.label}</span>
                            <Icon className="size-3" aria-hidden="true" />
                          </button>
                        </th>
                      );
                    })}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr
                      key={r.memberPublicId}
                      className="border-b border-border-subtle/50"
                      data-testid={`commission-analytics-row-${r.memberPublicId}`}
                    >
                      <td className="px-3 py-2 text-fg">{r.displayName}</td>
                      <td
                        className="px-3 py-2 text-right font-mono tabular-nums text-fg"
                        data-testid={`commission-analytics-lifetime-${r.memberPublicId}`}
                      >
                        {formatLindens(r.lifetimeLindens)}
                      </td>
                      <td
                        className="px-3 py-2 text-right font-mono tabular-nums text-fg-muted"
                        data-testid={`commission-analytics-recent-${r.memberPublicId}`}
                      >
                        {formatLindens(r.last30DaysLindens)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card.Body>
      </Card>

      {!isEmpty && (
        <Card>
          <Card.Header>
            <h2 className="text-sm font-semibold tracking-tight text-fg">
              Commission breakdown
            </h2>
            <p className="text-xs text-fg-muted">
              Light bars show lifetime; darker overlay shows the last 30 days.
            </p>
          </Card.Header>
          <Card.Body>
            <MemberCommissionBars rows={rows} />
          </Card.Body>
        </Card>
      )}
    </div>
  );
}
