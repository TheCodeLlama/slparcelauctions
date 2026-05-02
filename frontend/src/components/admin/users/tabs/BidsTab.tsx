"use client";
import { useState } from "react";
import Link from "next/link";
import { useAdminUserBids } from "@/hooks/admin/useAdminUserBids";
import { Pagination } from "@/components/ui/Pagination";

const PAGE_SIZE = 25;

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

type Props = {
  userId: number;
};

export function BidsTab({ userId }: Props) {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError } = useAdminUserBids(userId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-sm text-fg-muted">Loading bids…</div>;
  }

  if (isError) {
    return <div className="py-6 text-sm text-danger">Could not load bids.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-sm text-fg-muted">No bids found.</div>;
  }

  return (
    <div data-testid="bids-tab">
      <div className="overflow-x-auto rounded-lg border border-border-subtle">
        <table className="w-full text-sm">
          <thead className="bg-bg-subtle border-b border-border-subtle">
            <tr>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Auction</th>
              <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted">Amount</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Placed</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Status</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr
                key={row.bidId}
                className="border-b border-border-subtle/50"
                data-testid={`bid-row-${row.bidId}`}
              >
                <td className="px-3 py-2.5">
                  <Link
                    href={`/auction/${row.auctionId}`}
                    className="text-brand hover:underline underline-offset-2 line-clamp-1"
                    target="_blank"
                  >
                    {row.auctionTitle}
                  </Link>
                </td>
                <td className="px-3 py-2.5 text-right text-fg font-medium">
                  L$ {row.amount.toLocaleString()}
                </td>
                <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDate(row.placedAt)}</td>
                <td className="px-3 py-2.5 text-fg-muted">{row.auctionStatus}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data.totalPages > 1 && (
        <div className="mt-4">
          <Pagination page={data.number} totalPages={data.totalPages} onPageChange={setPage} />
        </div>
      )}
    </div>
  );
}
