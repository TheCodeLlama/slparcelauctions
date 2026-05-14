"use client";

import Link from "next/link";
import { useState } from "react";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { RealtyGroupRowDto } from "@/types/realty";
import {
  AdminRealtyGroupActionModal,
  type AdminRealtyGroupAction,
} from "./AdminRealtyGroupActionModal";
import { AdminRealtyGroupRowActionMenu } from "./AdminRealtyGroupRowActionMenu";

type Props = {
  rows: RealtyGroupRowDto[];
};

function formatDate(iso: string | null): string {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "-";
  return d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "2-digit",
  });
}

const COLUMNS: { key: string; label: string; align?: "right" }[] = [
  { key: "name", label: "Group" },
  { key: "slug", label: "Slug" },
  { key: "leader", label: "Leader" },
  { key: "members", label: "Members", align: "right" },
  { key: "status", label: "Status" },
  { key: "createdAt", label: "Created" },
  { key: "actions", label: "" },
];

/**
 * Table body for `/admin/realty-groups`. Rows are clickable (whole-row link
 * to the admin detail page); the kebab menu opens a row-action modal for
 * force-edit / force-dissolve.
 */
export function AdminRealtyGroupsTable({ rows }: Props) {
  const [pendingAction, setPendingAction] = useState<{
    row: RealtyGroupRowDto;
    action: AdminRealtyGroupAction;
  } | null>(null);

  if (rows.length === 0) {
    return (
      <div
        className="py-12 text-center text-sm text-fg-muted"
        data-testid="admin-realty-empty"
      >
        No realty groups match these filters.
      </div>
    );
  }

  return (
    <>
      <div
        className="overflow-x-auto rounded-lg border border-border-subtle"
        data-testid="admin-realty-table"
      >
        <table className="w-full text-sm">
          <thead className="bg-bg-subtle border-b border-border-subtle">
            <tr>
              {COLUMNS.map((c) => (
                <th
                  key={c.key}
                  className={`px-3 py-2.5 text-[11px] font-medium text-fg-muted ${
                    c.align === "right" ? "text-right" : "text-left"
                  }`}
                >
                  {c.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              return (
                <tr
                  key={row.publicId}
                  data-testid={`admin-realty-row-${row.publicId}`}
                  className="border-b border-border-subtle/50 hover:bg-bg-muted/50"
                >
                  <td className="px-3 py-2.5">
                    <Link
                      href={`/admin/groups/${encodeURIComponent(row.slug)}`}
                      className="text-fg hover:underline truncate block max-w-[280px]"
                      data-testid={`admin-realty-row-link-${row.publicId}`}
                    >
                      {row.name}
                    </Link>
                  </td>
                  <td className="px-3 py-2.5 text-fg-muted text-[11px] font-mono">
                    {row.slug}
                  </td>
                  <td className="px-3 py-2.5">
                    <Link
                      href={`/admin/users/${row.leaderPublicId}`}
                      className="text-fg hover:underline"
                      data-testid={`admin-realty-row-leader-${row.publicId}`}
                      onClick={(e) => e.stopPropagation()}
                    >
                      {row.leaderDisplayName}
                    </Link>
                  </td>
                  <td className="px-3 py-2.5 text-right font-mono text-fg text-[11px]">
                    {row.memberCount}
                  </td>
                  <td className="px-3 py-2.5">
                    <StatusBadge tone={row.dissolved ? "default" : "success"}>
                      {row.dissolved ? "Dissolved" : "Active"}
                    </StatusBadge>
                  </td>
                  <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                    {formatDate(row.createdAt)}
                  </td>
                  <td
                    className="px-3 py-2.5 text-right"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <AdminRealtyGroupRowActionMenu
                      isDissolved={row.dissolved}
                      onPick={(action) => setPendingAction({ row, action })}
                    />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {pendingAction && (
        <AdminRealtyGroupActionModal
          open={true}
          row={pendingAction.row}
          action={pendingAction.action}
          onClose={() => setPendingAction(null)}
        />
      )}
    </>
  );
}
