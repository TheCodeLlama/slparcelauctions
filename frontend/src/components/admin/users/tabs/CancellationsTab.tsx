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
  userId: number;
};

export function CancellationsTab({ userId }: Props) {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError } = useAdminUserCancellations(userId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-body-sm text-on-surface-variant">Loading cancellations…</div>;
  }

  if (isError) {
    return <div className="py-6 text-body-sm text-error">Could not load cancellations.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-body-sm text-on-surface-variant">No cancellations found.</div>;
  }

  return (
    <div data-testid="cancellations-tab">
      <div className="overflow-x-auto rounded-default border border-outline-variant">
        <table className="w-full text-body-sm">
          <thead className="bg-surface-container-low border-b border-outline-variant">
            <tr>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Auction</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">From status</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Had bids</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Penalty</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Date</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr
                key={row.logId}
                className="border-b border-outline-variant/50"
                data-testid={`cancellation-row-${row.logId}`}
              >
                <td className="px-3 py-2.5">
                  <Link
                    href={`/auction/${row.auctionId}`}
                    className="text-primary hover:underline underline-offset-2 line-clamp-1"
                    target="_blank"
                  >
                    {row.auctionTitle}
                  </Link>
                </td>
                <td className="px-3 py-2.5 text-on-surface-variant">{row.cancelledFromStatus}</td>
                <td className="px-3 py-2.5">
                  {row.hadBids ? (
                    <span className="text-error font-medium">Yes</span>
                  ) : (
                    <span className="text-on-surface-variant">No</span>
                  )}
                </td>
                <td className="px-3 py-2.5 text-on-surface-variant">
                  {row.penaltyKind ? (
                    <span>
                      {row.penaltyKind}
                      {row.penaltyAmountL !== null && ` (L$ ${row.penaltyAmountL.toLocaleString()})`}
                    </span>
                  ) : (
                    "—"
                  )}
                </td>
                <td className="px-3 py-2.5 text-on-surface-variant text-[11px]">{formatDate(row.cancelledAt)}</td>
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
