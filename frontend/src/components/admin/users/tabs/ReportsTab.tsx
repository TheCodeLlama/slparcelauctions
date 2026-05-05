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
  publicId: string;
};

export function ReportsTab({ publicId }: Props) {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError } = useAdminUserReports(publicId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-sm text-fg-muted">Loading reports…</div>;
  }

  if (isError) {
    return <div className="py-6 text-sm text-danger">Could not load reports.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-sm text-fg-muted">No reports found.</div>;
  }

  return (
    <div data-testid="reports-tab">
      <div className="overflow-x-auto rounded-lg border border-border-subtle">
        <table className="w-full text-sm">
          <thead className="bg-bg-subtle border-b border-border-subtle">
            <tr>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Auction</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Reason</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Direction</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Status</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Date</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr
                key={row.reportId}
                className="border-b border-border-subtle/50"
                data-testid={`report-row-${row.reportId}`}
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
                <td className="px-3 py-2.5 text-fg-muted">{row.reason}</td>
                <td className="px-3 py-2.5">
                  {row.direction === "FILED_BY" ? (
                    <span className="text-[11px] bg-bg-hover px-2 py-0.5 rounded-full text-fg-muted">
                      Filed by
                    </span>
                  ) : (
                    <span className="text-[11px] bg-danger-bg px-2 py-0.5 rounded-full text-danger">
                      Against listing
                    </span>
                  )}
                </td>
                <td className="px-3 py-2.5 text-fg-muted">{row.status}</td>
                <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDate(row.createdAt)}</td>
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
