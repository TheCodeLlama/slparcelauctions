"use client";
import { cn } from "@/lib/cn";
import { ReasonBadge } from "./ReasonBadge";
import type { AdminFraudFlagSummary, AuctionStatus } from "@/lib/admin/types";

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

const STATUS_CLASSES: Partial<Record<AuctionStatus, string>> = {
  SUSPENDED: "text-danger font-medium",
  DISPUTED: "text-info font-medium",
};

type Props = {
  rows: AdminFraudFlagSummary[];
  selectedId: number | null;
  onSelect: (id: number) => void;
};

export function FraudFlagTable({ rows, selectedId, onSelect }: Props) {
  if (rows.length === 0) {
    return (
      <div className="py-12 text-center text-sm text-fg-muted" data-testid="empty-state">
        No fraud flags match the current filter.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-border-subtle">
      <table className="w-full text-sm" data-testid="fraud-flag-table">
        <thead className="sticky top-0 bg-bg-subtle border-b border-border-subtle">
          <tr>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted w-[90px]">
              Detected
            </th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted w-[150px]">
              Reason
            </th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
              Auction
            </th>
            <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted w-[100px]">
              Status
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const statusClass =
              row.auctionStatus ? (STATUS_CLASSES[row.auctionStatus] ?? "text-fg") : "text-fg-muted";
            return (
              <tr
                key={row.id}
                role="row"
                data-testid={`flag-row-${row.id}`}
                onClick={() => onSelect(row.id)}
                className={cn(
                  "border-b border-border-subtle/50 cursor-pointer transition-colors",
                  selectedId === row.id
                    ? "bg-info-bg/30"
                    : "hover:bg-bg-muted"
                )}
              >
                <td className="px-3 py-2.5 text-fg-muted whitespace-nowrap">
                  {formatDate(row.detectedAt)}
                </td>
                <td className="px-3 py-2.5">
                  <ReasonBadge reason={row.reason} />
                </td>
                <td className="px-3 py-2.5 text-fg">
                  <div className="font-medium line-clamp-1">{row.auctionTitle ?? "(no title)"}</div>
                  {row.parcelRegionName && (
                    <div className="text-fg-muted text-[11px]">{row.parcelRegionName}</div>
                  )}
                </td>
                <td className={cn("px-3 py-2.5 text-right", statusClass)}>
                  {row.auctionStatus ?? "—"}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
