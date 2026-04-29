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
  SUSPENDED: "text-error font-medium",
  DISPUTED: "text-tertiary font-medium",
};

type Props = {
  rows: AdminFraudFlagSummary[];
  selectedId: number | null;
  onSelect: (id: number) => void;
};

export function FraudFlagTable({ rows, selectedId, onSelect }: Props) {
  if (rows.length === 0) {
    return (
      <div className="py-12 text-center text-body-sm text-on-surface-variant" data-testid="empty-state">
        No fraud flags match the current filter.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-default border border-outline-variant">
      <table className="w-full text-body-sm" data-testid="fraud-flag-table">
        <thead className="sticky top-0 bg-surface-container-low border-b border-outline-variant">
          <tr>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium w-[90px]">
              Detected
            </th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium w-[150px]">
              Reason
            </th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">
              Auction
            </th>
            <th className="px-3 py-2.5 text-right text-label-sm text-on-surface-variant font-medium w-[100px]">
              Status
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const statusClass =
              row.auctionStatus ? (STATUS_CLASSES[row.auctionStatus] ?? "text-on-surface") : "text-on-surface-variant";
            return (
              <tr
                key={row.id}
                role="row"
                data-testid={`flag-row-${row.id}`}
                onClick={() => onSelect(row.id)}
                className={cn(
                  "border-b border-outline-variant/50 cursor-pointer transition-colors",
                  selectedId === row.id
                    ? "bg-secondary-container/30"
                    : "hover:bg-surface-container"
                )}
              >
                <td className="px-3 py-2.5 text-on-surface-variant whitespace-nowrap">
                  {formatDate(row.detectedAt)}
                </td>
                <td className="px-3 py-2.5">
                  <ReasonBadge reason={row.reason} />
                </td>
                <td className="px-3 py-2.5 text-on-surface">
                  <div className="font-medium line-clamp-1">{row.auctionTitle ?? "(no title)"}</div>
                  {row.parcelRegionName && (
                    <div className="text-on-surface-variant text-[11px]">{row.parcelRegionName}</div>
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
