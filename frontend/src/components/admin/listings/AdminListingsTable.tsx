"use client";
import Link from "next/link";
import { useState } from "react";
import { ArrowUp, ArrowDown, ArrowUpDown } from "@/components/ui/icons";
import { RowActionMenu } from "./RowActionMenu";
import { ListingActionModal } from "./ListingActionModal";
import { statusBadgeClass } from "./statusBadgeColor";
import type {
  AdminListingAction,
  AdminListingRow,
  AdminListingsSort,
  AdminListingsSortColumn,
} from "@/lib/admin/types";

type Props = {
  rows: AdminListingRow[];
  sort: AdminListingsSort;
  onSortChange: (sort: AdminListingsSort) => void;
};

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("en-US", {
    month: "short", day: "numeric", year: "2-digit",
  });
}

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

function formatTimeRemaining(endsAt: string | null, status: string): string {
  if (!endsAt) return "—";
  if (status !== "ACTIVE") return "—";
  const ends = new Date(endsAt).getTime();
  const now = Date.now();
  const ms = ends - now;
  if (ms <= 0) return "Ended";
  const sec = Math.floor(ms / 1000);
  const days = Math.floor(sec / 86400);
  const hours = Math.floor((sec % 86400) / 3600);
  const minutes = Math.floor((sec % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

const COLUMNS: { key: AdminListingsSortColumn | "status" | "reserve" | "actions"; label: string; sortable: boolean; align?: "right" | "left" }[] = [
  { key: "title",       label: "Title",       sortable: true },
  { key: "seller",      label: "Seller",      sortable: true },
  { key: "status",      label: "Status",      sortable: false },
  { key: "reserve",     label: "Reserve",     sortable: false },
  { key: "createdAt",   label: "Created",     sortable: true },
  { key: "startingBid", label: "Start",       sortable: true, align: "right" },
  { key: "currentBid",  label: "Current bid", sortable: true, align: "right" },
  { key: "bidCount",    label: "Bids",        sortable: true, align: "right" },
  { key: "saveCount",   label: "Saves",       sortable: true, align: "right" },
  { key: "endsAt",      label: "Ends",        sortable: true },
  { key: "region",      label: "Region",      sortable: true },
  { key: "actions",     label: "",            sortable: false },
];

export function AdminListingsTable({ rows, sort, onSortChange }: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<{
    row: AdminListingRow;
    action: AdminListingAction;
  } | null>(null);

  if (rows.length === 0) {
    return (
      <div
        className="py-12 text-center text-sm text-fg-muted"
        data-testid="empty-state"
      >
        No listings match these filters.
      </div>
    );
  }

  function handleHeaderClick(col: AdminListingsSortColumn) {
    if (sort.column === col) {
      onSortChange({ column: col, direction: sort.direction === "asc" ? "desc" : "asc" });
    } else {
      onSortChange({ column: col, direction: "asc" });
    }
  }

  return (
    <>
      <div className="overflow-x-auto rounded-lg border border-border-subtle" data-testid="listings-table">
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
                        onClick={() => handleHeaderClick(c.key as AdminListingsSortColumn)}
                        className={`inline-flex items-center gap-1 hover:text-fg ${isActive ? "text-fg" : ""}`}
                        data-testid={`listings-sort-${c.key}`}
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
              const isSelected = selectedId === row.publicId;
              return (
                <tr
                  key={row.publicId}
                  data-testid={`listing-row-${row.publicId}`}
                  onClick={() => setSelectedId(row.publicId)}
                  className={`border-b border-border-subtle/50 cursor-default ${isSelected ? "bg-bg-hover" : "hover:bg-bg-muted/50"}`}
                >
                  <td className="px-3 py-2.5">
                    <Link
                      href={`/auction/${row.publicId}`}
                      className="text-fg hover:underline truncate block max-w-[320px]"
                      title={row.title}
                      data-testid={`listing-title-${row.publicId}`}
                      onClick={(e) => e.stopPropagation()}
                    >
                      {row.title}
                    </Link>
                  </td>
                  <td className="px-3 py-2.5">
                    <Link
                      href={`/admin/users/${row.sellerPublicId}`}
                      className="text-fg hover:underline"
                      data-testid={`listing-seller-${row.publicId}`}
                      onClick={(e) => e.stopPropagation()}
                    >
                      {row.sellerUsername}
                    </Link>
                  </td>
                  <td className="px-3 py-2.5">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold ${statusBadgeClass(row.status)}`}>
                      {row.status}
                    </span>
                  </td>
                  <td className="px-3 py-2.5">
                    {row.hasReserve ? (
                      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-info-bg text-info">
                        Yes
                      </span>
                    ) : (
                      <span className="text-[11px] text-fg-muted">—</span>
                    )}
                  </td>
                  <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDate(row.createdAt)}</td>
                  <td className="px-3 py-2.5 text-right font-mono text-fg-muted text-[11px]">{formatLindens(row.startingBid)}</td>
                  <td className="px-3 py-2.5 text-right font-mono text-fg text-[11px]">{formatLindens(row.currentBid)}</td>
                  <td className="px-3 py-2.5 text-right font-mono text-fg text-[11px]">{row.bidCount}</td>
                  <td className="px-3 py-2.5 text-right font-mono text-fg text-[11px]">{row.saveCount}</td>
                  <td className="px-3 py-2.5 text-fg-muted text-[11px]" title={row.endsAt ?? undefined}>
                    {formatTimeRemaining(row.endsAt, row.status)}
                  </td>
                  <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                    {row.region ?? <span className="text-fg-muted/50">—</span>}
                  </td>
                  <td className="px-3 py-2.5 text-right" onClick={(e) => e.stopPropagation()}>
                    <RowActionMenu
                      status={row.status}
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
        <ListingActionModal
          open={true}
          row={pendingAction.row}
          action={pendingAction.action}
          onClose={() => setPendingAction(null)}
        />
      )}
    </>
  );
}
