"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Card } from "@/components/ui/Card";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { useGroupReports } from "@/hooks/realty/useGroupReports";
import type {
  AdminRealtyGroupReportRow,
  RealtyGroupReportReason,
  RealtyGroupReportStatus,
} from "@/types/realty";

export interface AdminGroupReportsTabProps {
  groupPublicId: string;
}

const REASON_LABEL: Record<RealtyGroupReportReason, string> = {
  FRAUDULENT_LISTINGS: "Fraudulent listings",
  MISLEADING_ATTRIBUTION: "Misleading attribution",
  HARASSMENT: "Harassment",
  IMPERSONATION: "Impersonation",
  SPAM: "Spam",
  OTHER: "Other",
};

function reasonLabel(value: RealtyGroupReportReason | string | null | undefined): string {
  if (!value) return "Unknown";
  if (value in REASON_LABEL) return REASON_LABEL[value as RealtyGroupReportReason];
  return String(value);
}

function statusTone(status: RealtyGroupReportStatus | string): "warning" | "success" | "default" {
  if (status === "OPEN") return "warning";
  if (status === "RESOLVED") return "success";
  return "default";
}

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return "(none)";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "(none)";
  return d.toLocaleString();
}

/**
 * Realty Groups: F — admin "Reports" tab on the group detail page. Lists
 * reports filed against THIS group only (not the global queue).
 *
 * <p>Each row links to the standalone report detail page
 * ({@code /admin/groups/reports/[publicId]}) where the admin
 * resolves or dismisses. Status filter is a simple dropdown so the admin
 * can quickly narrow to OPEN reports without bouncing to the global queue.
 */
export function AdminGroupReportsTab({
  groupPublicId,
}: AdminGroupReportsTabProps) {
  const [status, setStatus] = useState<RealtyGroupReportStatus | "">("");
  const { data, isLoading, isError } = useGroupReports(groupPublicId, {
    status: status || undefined,
    page: 0,
    size: 50,
  });

  const rows = useMemo<AdminRealtyGroupReportRow[]>(
    () => (Array.isArray(data?.content) ? data!.content : []),
    [data],
  );

  return (
    <Card data-testid="admin-group-reports-tab">
      <Card.Header>
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-sm font-semibold tracking-tight">Reports</h2>
          <label className="flex items-center gap-2 text-xs text-fg-muted">
            Status
            <select
              value={status}
              onChange={(e) =>
                setStatus(e.target.value as RealtyGroupReportStatus | "")
              }
              className="rounded-lg bg-bg-subtle px-3 py-1.5 text-xs text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
              data-testid="admin-group-reports-status-filter"
            >
              <option value="">All</option>
              <option value="OPEN">Open</option>
              <option value="RESOLVED">Resolved</option>
              <option value="DISMISSED">Dismissed</option>
            </select>
          </label>
        </div>
      </Card.Header>
      <Card.Body>
        {isLoading && (
          <div data-testid="admin-group-reports-loading">
            <LoadingSpinner label="Loading reports…" />
          </div>
        )}
        {isError && (
          <p
            className="text-xs text-danger"
            data-testid="admin-group-reports-error"
          >
            Could not load reports. Refresh to retry.
          </p>
        )}
        {!isLoading && !isError && rows.length === 0 && (
          <p
            className="text-xs text-fg-muted"
            data-testid="admin-group-reports-empty"
          >
            No reports filed against this group yet.
          </p>
        )}
        {!isLoading && !isError && rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-fg-muted">
                  <th className="py-2 pr-3 font-medium">Status</th>
                  <th className="py-2 pr-3 font-medium">Reason</th>
                  <th className="py-2 pr-3 font-medium">Reporter</th>
                  <th className="py-2 pr-3 font-medium">Submitted</th>
                  <th className="py-2 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody data-testid="admin-group-reports-table">
                {rows.map((row) => (
                  <tr
                    key={row.publicId}
                    className="border-t border-border-subtle align-top"
                    data-testid={`admin-group-report-row-${row.publicId}`}
                  >
                    <td className="py-2 pr-3">
                      <StatusBadge tone={statusTone(row.status)}>
                        {row.status}
                      </StatusBadge>
                    </td>
                    <td className="py-2 pr-3 text-xs text-fg">
                      {reasonLabel(row.reason)}
                    </td>
                    <td className="py-2 pr-3 text-xs text-fg-muted">
                      {row.reporter?.displayName ?? "Unknown"}
                    </td>
                    <td className="py-2 pr-3 text-xs text-fg-muted">
                      {formatTimestamp(row.createdAt)}
                    </td>
                    <td className="py-2 text-right">
                      <Link
                        href={`/admin/groups/reports/${row.publicId}`}
                        className="text-xs text-brand hover:underline"
                        data-testid={`admin-group-report-detail-link-${row.publicId}`}
                      >
                        Open
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
