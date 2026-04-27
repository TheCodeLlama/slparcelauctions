"use client";
import { cn } from "@/lib/cn";
import type { AdminReportListingRow, AuctionStatus } from "@/lib/admin/types";

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
  rows: AdminReportListingRow[];
  selectedAuctionId: number | null;
  onSelect: (auctionId: number) => void;
};

export function AdminReportsTable({ rows, selectedAuctionId, onSelect }: Props) {
  if (rows.length === 0) {
    return (
      <div
        className="py-12 text-center text-body-sm text-on-surface-variant"
        data-testid="empty-state"
      >
        No reports match the current filter.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-default border border-outline-variant">
      <table className="w-full text-body-sm" data-testid="reports-table">
        <thead className="sticky top-0 bg-surface-container-low border-b border-outline-variant">
          <tr>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium w-[90px]">
              Latest
            </th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">
              Listing
            </th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium w-[100px]">
              Seller
            </th>
            <th className="px-3 py-2.5 text-right text-label-sm text-on-surface-variant font-medium w-[70px]">
              Reports
            </th>
            <th className="px-3 py-2.5 text-right text-label-sm text-on-surface-variant font-medium w-[100px]">
              Status
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const statusClass =
              STATUS_CLASSES[row.auctionStatus] ?? "text-on-surface";
            return (
              <tr
                key={row.auctionId}
                role="row"
                data-testid={`report-row-${row.auctionId}`}
                onClick={() => onSelect(row.auctionId)}
                className={cn(
                  "border-b border-outline-variant/50 cursor-pointer transition-colors",
                  selectedAuctionId === row.auctionId
                    ? "bg-secondary-container/30"
                    : "hover:bg-surface-container"
                )}
              >
                <td className="px-3 py-2.5 text-on-surface-variant whitespace-nowrap">
                  {formatDate(row.latestReportAt)}
                </td>
                <td className="px-3 py-2.5 text-on-surface">
                  <div className="font-medium line-clamp-1">
                    {row.auctionTitle ?? "(no title)"}
                  </div>
                  {row.parcelRegionName && (
                    <div className="text-on-surface-variant text-[11px]">
                      {row.parcelRegionName}
                    </div>
                  )}
                </td>
                <td className="px-3 py-2.5 text-on-surface-variant">
                  {row.sellerDisplayName ?? "—"}
                </td>
                <td className="px-3 py-2.5 text-right font-medium text-on-surface">
                  {row.openReportCount}
                </td>
                <td className={cn("px-3 py-2.5 text-right", statusClass)}>
                  {row.auctionStatus}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
