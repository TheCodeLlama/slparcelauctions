"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { adminApi } from "@/lib/admin/api";
import type { AdminUserModerationRow } from "@/lib/admin/types";

export interface AdminGroupAuditTabProps {
  groupPublicId: string;
}

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString();
}

/**
 * Realty Groups: F — admin "Audit" tab on the group detail page.
 *
 * <p>Calls {@code GET /api/v1/admin/audit} pre-filtered to
 * {@code entityType=REALTY_GROUP&entityId={groupPublicId}} so the table
 * shows every {@code REALTY_GROUP_*} action against this specific group.
 * Renders raw enum names (no localization layer for the new F action
 * types — see DEFERRED_WORK.md for the deferred localization item).
 */
export function AdminGroupAuditTab({ groupPublicId }: AdminGroupAuditTabProps) {
  const [page, setPage] = useState(0);
  const size = 25;

  const { data, isLoading, isError } = useQuery({
    queryKey: ["admin", "audit", "realty-group", groupPublicId, page, size],
    queryFn: () =>
      adminApi.audit.list({
        entityType: "REALTY_GROUP",
        entityId: groupPublicId,
        page,
        size,
      }),
    enabled: !!groupPublicId,
    staleTime: 5_000,
  });

  const rows = useMemo<AdminUserModerationRow[]>(
    () => (Array.isArray(data?.content) ? data!.content : []),
    [data],
  );
  const totalElements = data?.totalElements ?? 0;
  const totalPages = data?.totalPages ?? 0;
  const showingFrom = rows.length === 0 ? 0 : page * size + 1;
  const showingTo = page * size + rows.length;

  return (
    <Card data-testid="admin-group-audit-tab">
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Audit log</h2>
      </Card.Header>
      <Card.Body>
        {isLoading && (
          <div data-testid="admin-group-audit-loading">
            <LoadingSpinner label="Loading audit log…" />
          </div>
        )}
        {isError && (
          <p
            className="text-xs text-danger"
            data-testid="admin-group-audit-error"
          >
            Could not load audit log. Refresh to retry.
          </p>
        )}
        {!isLoading && !isError && rows.length === 0 && (
          <p
            className="text-xs text-fg-muted"
            data-testid="admin-group-audit-empty"
          >
            No admin actions recorded for this group yet.
          </p>
        )}
        {!isLoading && !isError && rows.length > 0 && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs text-fg-muted">
                    <th className="py-2 pr-3 font-medium">When</th>
                    <th className="py-2 pr-3 font-medium">Admin</th>
                    <th className="py-2 pr-3 font-medium">Action</th>
                    <th className="py-2 font-medium">Notes</th>
                  </tr>
                </thead>
                <tbody data-testid="admin-group-audit-table">
                  {rows.map((row) => (
                    <tr
                      key={row.actionId}
                      className="border-t border-border-subtle align-top"
                      data-testid={`admin-group-audit-row-${row.actionId}`}
                    >
                      <td className="py-2 pr-3 text-xs text-fg-muted whitespace-nowrap">
                        {formatTimestamp(row.createdAt)}
                      </td>
                      <td className="py-2 pr-3 text-xs text-fg">
                        {row.adminDisplayName ?? "—"}
                      </td>
                      <td className="py-2 pr-3 text-xs text-fg font-mono">
                        {row.actionType}
                      </td>
                      <td className="py-2 text-xs text-fg-muted">
                        {row.notes ?? ""}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex items-center justify-between border-t border-border-subtle pt-3 text-xs text-fg-muted">
              <span>
                Showing {showingFrom}–{showingTo} of {totalElements}
              </span>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                  data-testid="admin-group-audit-prev"
                >
                  Previous
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setPage((p) => p + 1)}
                  disabled={page + 1 >= totalPages}
                  data-testid="admin-group-audit-next"
                >
                  Next
                </Button>
              </div>
            </div>
          </>
        )}
      </Card.Body>
    </Card>
  );
}
