"use client";
import { useState } from "react";
import Link from "next/link";
import { useAdminUserFraudFlags } from "@/hooks/admin/useAdminUserFraudFlags";
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

export function FraudFlagsTab({ publicId }: Props) {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError } = useAdminUserFraudFlags(publicId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-sm text-fg-muted">Loading fraud flags…</div>;
  }

  if (isError) {
    return <div className="py-6 text-sm text-danger">Could not load fraud flags.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-sm text-fg-muted">No fraud flags found.</div>;
  }

  return (
    <div data-testid="fraud-flags-tab">
      <div className="overflow-x-auto rounded-lg border border-border-subtle">
        <table className="w-full text-sm">
          <thead className="bg-bg-subtle border-b border-border-subtle">
            <tr>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Auction</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Reason</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Status</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Detected</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr
                key={row.flagId}
                className="border-b border-border-subtle/50"
                data-testid={`fraud-flag-row-${row.flagId}`}
              >
                <td className="px-3 py-2.5">
                  {row.auctionPublicId ? (
                    <Link
                      href={`/auction/${row.auctionPublicId}`}
                      className="text-brand hover:underline underline-offset-2 line-clamp-1"
                      target="_blank"
                    >
                      {row.auctionTitle ?? "(deleted)"}
                    </Link>
                  ) : (
                    <span className="text-fg-muted">(deleted)</span>
                  )}
                </td>
                <td className="px-3 py-2.5 text-fg-muted">{row.reason}</td>
                <td className="px-3 py-2.5">
                  {row.resolved ? (
                    <span className="text-[11px] bg-bg-hover px-2 py-0.5 rounded-full text-fg-muted">
                      Resolved
                    </span>
                  ) : (
                    <span className="text-[11px] bg-danger-bg px-2 py-0.5 rounded-full text-danger">
                      Open
                    </span>
                  )}
                </td>
                <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDate(row.detectedAt)}</td>
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
