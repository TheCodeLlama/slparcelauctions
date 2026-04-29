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
  userId: number;
};

export function ModerationTab({ userId }: Props) {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError } = useAdminUserModeration(userId, page, PAGE_SIZE);

  if (isLoading) {
    return <div className="py-6 text-body-sm text-on-surface-variant">Loading moderation history…</div>;
  }

  if (isError) {
    return <div className="py-6 text-body-sm text-error">Could not load moderation history.</div>;
  }

  if (!data || data.content.length === 0) {
    return <div className="py-6 text-body-sm text-on-surface-variant">No moderation history found.</div>;
  }

  return (
    <div data-testid="moderation-tab">
      <div className="overflow-x-auto rounded-default border border-outline-variant">
        <table className="w-full text-body-sm">
          <thead className="bg-surface-container-low border-b border-outline-variant">
            <tr>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Action</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">By</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Notes</th>
              <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Date</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr
                key={row.actionId}
                className="border-b border-outline-variant/50"
                data-testid={`moderation-row-${row.actionId}`}
              >
                <td className="px-3 py-2.5">
                  <span className="text-[10px] uppercase tracking-wide text-on-surface-variant/70">
                    {row.actionType}
                  </span>
                </td>
                <td className="px-3 py-2.5 text-on-surface-variant">
                  {row.adminDisplayName ?? "System"}
                </td>
                <td className="px-3 py-2.5 text-on-surface-variant max-w-xs">
                  <span className="line-clamp-2">{row.notes ?? "—"}</span>
                </td>
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
