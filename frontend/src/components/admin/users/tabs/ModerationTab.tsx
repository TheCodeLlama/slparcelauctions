"use client";
import { useState } from "react";
import { useAdminUserModeration } from "@/hooks/admin/useAdminUserModeration";
import { Pagination } from "@/components/ui/Pagination";

const PAGE_SIZE = 25;

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

type Props = {
  publicId: string;
};

export function ModerationTab({ publicId }: Props) {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError } = useAdminUserModeration(publicId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-sm text-fg-muted">Loading moderation history…</div>;
  }

  if (isError) {
    return <div className="py-6 text-sm text-danger">Could not load moderation history.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-sm text-fg-muted">No moderation history found.</div>;
  }

  return (
    <div data-testid="moderation-tab">
      <div className="overflow-x-auto rounded-lg border border-border-subtle">
        <table className="w-full text-sm">
          <thead className="bg-bg-subtle border-b border-border-subtle">
            <tr>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Action</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">By</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Notes</th>
              <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Date</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr
                key={row.actionId}
                className="border-b border-border-subtle/50"
                data-testid={`moderation-row-${row.actionId}`}
              >
                <td className="px-3 py-2.5">
                  <span className="text-[10px] uppercase tracking-wide text-fg-muted/70">
                    {row.actionType}
                  </span>
                </td>
                <td className="px-3 py-2.5 text-fg-muted">
                  {row.adminDisplayName ?? "System"}
                </td>
                <td className="px-3 py-2.5 text-fg-muted max-w-xs">
                  <span className="line-clamp-2">{row.notes ?? "-"}</span>
                </td>
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
