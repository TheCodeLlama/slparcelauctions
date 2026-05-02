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
  SUSPENDED: "text-danger font-medium",
  DISPUTED: "text-info font-medium",
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
        className="py-12 text-center text-sm text-fg-muted"
        data-testid="empty-state"
      >
        No reports match the current filter.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-border-subtle">
      <table className="w-full text-sm" data-testid="reports-table">
        <thead className="sticky top-0 bg-bg-subtle border-b border-border-subtle">
          <tr>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted w-[90px]">
              Latest
            </th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
              Listing
            </th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted w-[100px]">
              Seller
            </th>
            <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted w-[70px]">
              Reports
            </th>
            <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted w-[100px]">
              Status
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const statusClass =
              STATUS_CLASSES[row.auctionStatus] ?? "text-fg";
            return (
              <tr
                key={row.auctionId}
                role="row"
                data-testid={`report-row-${row.auctionId}`}
                onClick={() => onSelect(row.auctionId)}
                className={cn(
                  "border-b border-border-subtle/50 cursor-pointer transition-colors",
                  selectedAuctionId === row.auctionId
                    ? "bg-info-bg/30"
                    : "hover:bg-bg-muted"
                )}
              >
                <td className="px-3 py-2.5 text-fg-muted whitespace-nowrap">
                  {formatDate(row.latestReportAt)}
                </td>
                <td className="px-3 py-2.5 text-fg">
                  <div className="font-medium line-clamp-1">
                    {row.auctionTitle ?? "(no title)"}
                  </div>
                  {row.parcelRegionName && (
                    <div className="text-fg-muted text-[11px]">
                      {row.parcelRegionName}
                    </div>
                  )}
                </td>
                <td className="px-3 py-2.5 text-fg-muted">
                  {row.sellerDisplayName ?? "—"}
                </td>
                <td className="px-3 py-2.5 text-right font-medium text-fg">
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
