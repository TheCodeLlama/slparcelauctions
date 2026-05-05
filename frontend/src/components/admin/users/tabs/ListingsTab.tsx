"use client";
import { useState } from "react";
import Link from "next/link";
import { useAdminUserListings } from "@/hooks/admin/useAdminUserListings";
import { ReinstateListingModal } from "../ReinstateListingModal";
import { Pagination } from "@/components/ui/Pagination";
import { Button } from "@/components/ui/Button";
import type { AdminUserListingRow, AuctionStatus } from "@/lib/admin/types";

const PAGE_SIZE = 25;

function statusLabel(status: AuctionStatus): { label: string; className: string } {
  const map: Partial<Record<AuctionStatus, { label: string; className: string }>> = {
    ACTIVE: { label: "Active", className: "text-brand" },
    ENDED: { label: "Ended", className: "text-fg-muted" },
    COMPLETED: { label: "Completed", className: "text-success" },
    CANCELLED: { label: "Cancelled", className: "text-danger" },
    SUSPENDED: { label: "Suspended", className: "text-danger font-semibold" },
    EXPIRED: { label: "Expired", className: "text-fg-muted" },
    DRAFT: { label: "Draft", className: "text-fg-muted" },
  };
  return map[status] ?? { label: status, className: "text-fg-muted" };
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

type Props = {
  publicId: string;
};

export function ListingsTab({ publicId }: Props) {
  const [page, setPage] = useState(0);
  const [reinstateAuction, setReinstateAuction] = useState<AdminUserListingRow | null>(null);
  const { data, isLoading, isError } = useAdminUserListings(publicId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-sm text-fg-muted">Loading listings…</div>;
  }

  if (isError) {
    return <div className="py-6 text-sm text-danger">Could not load listings.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-sm text-fg-muted">No listings found.</div>;
  }

  return (
    <div data-testid="listings-tab">
      <div className="overflow-x-auto rounded-lg border border-border-subtle">
        <table className="w-full text-sm">
          <thead className="bg-bg-subtle border-b border-border-subtle">
            <tr>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Title</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Region</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Status</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Ends</th>
              <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted">Final bid</th>
              <th className="px-3 py-2.5 w-[90px]" />
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => {
              const { label, className } = statusLabel(row.status);
              return (
                <tr
                  key={row.auctionId}
                  className="border-b border-border-subtle/50"
                  data-testid={`listing-row-${row.auctionId}`}
                >
                  <td className="px-3 py-2.5">
                    <Link
                      href={`/auction/${row.auctionPublicId}`}
                      className="text-brand hover:underline underline-offset-2 line-clamp-1"
                      target="_blank"
                    >
                      {row.title}
                    </Link>
                  </td>
                  <td className="px-3 py-2.5 text-fg-muted">{row.regionName ?? "-"}</td>
                  <td className={`px-3 py-2.5 ${className}`}>{label}</td>
                  <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDate(row.endsAt)}</td>
                  <td className="px-3 py-2.5 text-right text-fg">
                    {row.finalBidAmount !== null ? `L$ ${row.finalBidAmount.toLocaleString()}` : "-"}
                  </td>
                  <td className="px-3 py-2.5 text-right">
                    {row.status === "SUSPENDED" && (
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => setReinstateAuction(row)}
                        data-testid={`reinstate-btn-${row.auctionId}`}
                      >
                        Reinstate
                      </Button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {data.totalPages > 1 && (
        <div className="mt-4">
          <Pagination page={data.number} totalPages={data.totalPages} onPageChange={setPage} />
        </div>
      )}

      {reinstateAuction && (
        <ReinstateListingModal
          auction={reinstateAuction}
          userPublicId={publicId}
          onClose={() => setReinstateAuction(null)}
        />
      )}
    </div>
  );
}
