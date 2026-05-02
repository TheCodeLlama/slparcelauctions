"use client";

import { useState, Fragment } from "react";
import Link from "next/link";
import type { AdminAuditLogRow, AdminActionTargetType } from "@/lib/admin/auditLog";
import { AuditLogRowDetails } from "./AuditLogRowDetails";

function targetUrlFor(
  type: AdminActionTargetType | null,
  id: number | null
): string | null {
  if (!type || id === null) return null;
  switch (type) {
    case "AUCTION": return `/auction/${id}`;
    case "USER": return `/admin/users/${id}`;
    case "DISPUTE": return `/admin/disputes/${id}`;
    case "BAN": return `/admin/bans`;
    case "WITHDRAWAL": return `/admin/infrastructure`;
    case "FRAUD_FLAG": return `/admin/fraud-flags?flagId=${id}`;
    case "REPORT": return `/admin/reports?reportId=${id}`;
    case "TERMINAL_SECRET": return `/admin/infrastructure`;
    default: return null;
  }
}

type Props = { rows: AdminAuditLogRow[] };

export function AdminAuditLogTable({ rows }: Props) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  if (rows.length === 0) {
    return (
      <p className="text-sm text-fg-muted">
        No audit log entries match.
      </p>
    );
  }

  const toggle = (id: number) => {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setExpanded(next);
  };

  return (
    <table className="w-full text-xs">
      <thead className="text-[10px] uppercase opacity-55 text-left">
        <tr className="border-b border-border-subtle">
          <th className="py-2 px-2 w-32">When</th>
          <th className="py-2 px-2">Action</th>
          <th className="py-2 px-2">Admin</th>
          <th className="py-2 px-2">Target</th>
          <th className="py-2 px-2">Notes</th>
          <th className="py-2 px-2 w-8"></th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const url = targetUrlFor(row.targetType, row.targetId);
          const isOpen = expanded.has(row.id);
          return (
            <Fragment key={row.id}>
              <tr
                className="border-b border-border-subtle/40 hover:bg-bg-subtle cursor-pointer"
                onClick={() => toggle(row.id)}
              >
                <td className="py-2 px-2 opacity-70">
                  {new Date(row.occurredAt).toLocaleString()}
                </td>
                <td className="py-2 px-2 text-brand">{row.actionType}</td>
                <td className="py-2 px-2">
                  {row.adminEmail ?? `#${row.adminUserId}`}
                </td>
                <td className="py-2 px-2">
                  {row.targetType ? (
                    <>
                      <span className="opacity-70">{row.targetType}</span>{" "}
                      {url ? (
                        <Link
                          href={url}
                          onClick={(e) => e.stopPropagation()}
                          className="text-brand"
                        >
                          #{row.targetId}
                        </Link>
                      ) : (
                        <span>#{row.targetId}</span>
                      )}
                    </>
                  ) : (
                    "—"
                  )}
                </td>
                <td className="py-2 px-2 opacity-85">{row.notes ?? "—"}</td>
                <td className="py-2 px-2 text-brand">
                  {isOpen ? "▾" : "▸"}
                </td>
              </tr>
              {isOpen && (
                <tr className="border-b border-border-subtle/40">
                  <td colSpan={6} className="px-4">
                    <AuditLogRowDetails row={row} />
                  </td>
                </tr>
              )}
            </Fragment>
          );
        })}
      </tbody>
    </table>
  );
}
