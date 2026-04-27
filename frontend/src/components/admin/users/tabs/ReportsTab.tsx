"use client";
import { useState } from "react";
import Link from "next/link";
import { useAdminUserReports } from "@/hooks/admin/useAdminUserReports";
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

export function ReportsTab({ userId }: Props) {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError } = useAdminUserReports(userId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-body-sm text-on-surface-variant">Loading reports…</div>;
  }

  if (isError) {
    return <div className="py-6 text-body-sm text-error">Could not load reports.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-body-sm text-on-surface-variant">No reports found.</div>;
  }

  return (
    <div data-testid="reports-tab">
      <div className="overflow-x-auto rounded-default border border-outline-variant">
        <table className="w-full text-body-sm">
          <thead className="bg-surface-container-low border-b border-outline-variant">
            <tr>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Auction</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Reason</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Direction</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Status</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Date</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr
                key={row.reportId}
                className="border-b border-outline-variant/50"
                data-testid={`report-row-${row.reportId}`}
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
                <td className="px-3 py-2.5 text-on-surface-variant">{row.reason}</td>
                <td className="px-3 py-2.5">
                  {row.direction === "FILED_BY" ? (
                    <span className="text-[11px] bg-surface-container-high px-2 py-0.5 rounded-full text-on-surface-variant">
                      Filed by
                    </span>
                  ) : (
                    <span className="text-[11px] bg-error-container px-2 py-0.5 rounded-full text-on-error-container">
                      Against listing
                    </span>
                  )}
                </td>
                <td className="px-3 py-2.5 text-on-surface-variant">{row.status}</td>
                <td className="px-3 py-2.5 text-on-surface-variant text-[11px]">{formatDate(row.createdAt)}</td>
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
