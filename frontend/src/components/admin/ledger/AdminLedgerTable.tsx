"use client";
import Link from "next/link";
import { ArrowUp, ArrowDown, ArrowUpDown } from "@/components/ui/icons";
import { AdminLedgerKindBadge } from "./AdminLedgerKindBadge";
import { AdminLedgerStatusBadge } from "./AdminLedgerStatusBadge";
import { rowDrillLink } from "./adminLedgerLinks";
import type { AdminLedgerRow, AdminLedgerSort, AdminLedgerSortColumn } from "@/lib/admin/types";

type Props = {
  rows: AdminLedgerRow[];
  sort: AdminLedgerSort;
  onSortChange: (sort: AdminLedgerSort) => void;
};

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString("en-US", {
    month: "short", day: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}

function formatLindens(amount: number): string {
  const sign = amount < 0 ? "-" : amount > 0 ? "+" : "";
  return `${sign}L$ ${Math.abs(amount).toLocaleString()}`;
}

const COLUMNS: { key: AdminLedgerSortColumn | "kind" | "user" | "entry" | "status" | "counterparty" | "ref" | "description" | "actions"; label: string; sortable: boolean; align?: "right" | "left" }[] = [
  { key: "createdAt",     label: "Time",          sortable: true },
  { key: "kind",          label: "Kind",          sortable: false },
  { key: "user",          label: "User",          sortable: false },
  { key: "amountLindens", label: "Amount",        sortable: true,  align: "right" },
  { key: "entry",         label: "Entry",         sortable: false },
  { key: "status",        label: "Status",        sortable: false },
  { key: "counterparty",  label: "Counterparty",  sortable: false },
  { key: "ref",           label: "Ref",           sortable: false },
  { key: "description",   label: "Description",   sortable: false },
  { key: "actions",       label: "",              sortable: false },
];

export function AdminLedgerTable({ rows, sort, onSortChange }: Props) {
  if (rows.length === 0) {
    return (
      <div className="py-12 text-center text-sm text-fg-muted" data-testid="ledger-empty">
        No ledger events match these filters.
      </div>
    );
  }

  function handleHeaderClick(col: AdminLedgerSortColumn) {
    if (sort.column === col) {
      onSortChange({ column: col, direction: sort.direction === "asc" ? "desc" : "asc" });
    } else {
      onSortChange({ column: col, direction: "desc" });
    }
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-border-subtle" data-testid="ledger-table">
      <table className="w-full text-sm">
        <thead className="bg-bg-subtle border-b border-border-subtle">
          <tr>
            {COLUMNS.map((c) => {
              const isActive = c.sortable && sort.column === c.key;
              const Icon = isActive
                ? sort.direction === "asc" ? ArrowUp : ArrowDown
                : ArrowUpDown;
              return (
                <th
                  key={c.key}
                  className={`px-3 py-2.5 text-[11px] font-medium text-fg-muted ${c.align === "right" ? "text-right" : "text-left"}`}
                >
                  {c.sortable ? (
                    <button
                      type="button"
                      onClick={() => handleHeaderClick(c.key as AdminLedgerSortColumn)}
                      className={`inline-flex items-center gap-1 hover:text-fg ${isActive ? "text-fg" : ""}`}
                      data-testid={`ledger-sort-${c.key}`}
                    >
                      <span>{c.label}</span>
                      <Icon className="size-3" aria-hidden="true" />
                    </button>
                  ) : c.label}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const drill = rowDrillLink(row);
            return (
              <tr
                key={row.eventId}
                data-testid={`ledger-row-${row.eventId}`}
                className="border-b border-border-subtle/50 hover:bg-bg-muted/30"
              >
                <td className="px-3 py-2.5 text-fg-muted text-[11px]" title={row.createdAt}>
                  {formatDateTime(row.createdAt)}
                </td>
                <td className="px-3 py-2.5">
                  <AdminLedgerKindBadge kind={row.kind} />
                </td>
                <td className="px-3 py-2.5">
                  {row.userPublicId ? (
                    <Link
                      href={`/admin/users/${row.userPublicId}`}
                      className="text-fg hover:underline"
                    >
                      {row.username ?? row.userPublicId.slice(0, 8) + "…"}
                    </Link>
                  ) : (
                    <span className="text-fg-muted/50">(none)</span>
                  )}
                </td>
                <td className={`px-3 py-2.5 text-right font-mono text-[11px] ${row.amountLindens < 0 ? "text-danger" : "text-fg"}`}>
                  {formatLindens(row.amountLindens)}
                </td>
                <td className="px-3 py-2.5 text-[10px] uppercase tracking-wide text-fg-muted/70">
                  {row.entryType}
                </td>
                <td className="px-3 py-2.5">
                  <AdminLedgerStatusBadge status={row.status} />
                </td>
                <td className="px-3 py-2.5">
                  {row.counterpartyPublicId ? (
                    <Link
                      href={`/admin/users/${row.counterpartyPublicId}`}
                      className="text-fg hover:underline"
                    >
                      {row.counterpartyUsername ?? row.counterpartyPublicId.slice(0, 8) + "…"}
                    </Link>
                  ) : (
                    <span className="text-fg-muted/50">(none)</span>
                  )}
                </td>
                <td className="px-3 py-2.5 text-[11px] text-fg-muted">
                  {row.refType ? (
                    <span>
                      {row.refType}{row.refId != null ? ` · ${row.refId}` : ""}
                    </span>
                  ) : <span className="text-fg-muted/50">(none)</span>}
                </td>
                <td className="px-3 py-2.5 text-[11px] text-fg-muted max-w-[420px] truncate" title={row.description ?? ""}>
                  {row.description ?? <span className="text-fg-muted/50">(none)</span>}
                </td>
                <td className="px-3 py-2.5 text-right">
                  {drill ? (
                    <Link
                      href={drill}
                      className="text-[11px] text-fg-muted hover:text-fg underline"
                      data-testid={`ledger-drill-${row.eventId}`}
                    >
                      →
                    </Link>
                  ) : (
                    <span className="text-fg-muted/30">(none)</span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
