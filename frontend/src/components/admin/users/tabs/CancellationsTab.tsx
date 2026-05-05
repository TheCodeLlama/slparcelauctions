"use client";
import { useState } from "react";
import Link from "next/link";
import { useAdminUserCancellations } from "@/hooks/admin/useAdminUserCancellations";
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
  publicId: string;
};

export function CancellationsTab({ publicId }: Props) {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError } = useAdminUserCancellations(publicId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-sm text-fg-muted">Loading cancellations…</div>;
  }

  if (isError) {
    return <div className="py-6 text-sm text-danger">Could not load cancellations.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-sm text-fg-muted">No cancellations found.</div>;
  }

  return (
    <div data-testid="cancellations-tab">
      <div className="overflow-x-auto rounded-lg border border-border-subtle">
        <table className="w-full text-sm">
          <thead className="bg-bg-subtle border-b border-border-subtle">
            <tr>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Auction</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">From status</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Had bids</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Penalty</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Date</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr
                key={row.logId}
                className="border-b border-border-subtle/50"
                data-testid={`cancellation-row-${row.logId}`}
              >
                <td className="px-3 py-2.5">
                  <Link
                    href={`/auction/${row.auctionPublicId}`}
                    className="text-brand hover:underline underline-offset-2 line-clamp-1"
                    target="_blank"
                  >
                    {row.auctionTitle}
                  </Link>
                </td>
                <td className="px-3 py-2.5 text-fg-muted">{row.cancelledFromStatus}</td>
                <td className="px-3 py-2.5">
                  {row.hadBids ? (
                    <span className="text-danger font-medium">Yes</span>
                  ) : (
                    <span className="text-fg-muted">No</span>
                  )}
                </td>
                <td className="px-3 py-2.5 text-fg-muted">
                  {row.penaltyKind ? (
                    <span>
                      {row.penaltyKind}
                      {row.penaltyAmountL !== null && ` (L$ ${row.penaltyAmountL.toLocaleString()})`}
                    </span>
                  ) : (
                    "-"
                  )}
                </td>
                <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDate(row.cancelledAt)}</td>
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
