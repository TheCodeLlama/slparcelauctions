import Link from "next/link";
import type { AdminUserSummary } from "@/lib/admin/types";

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

function truncateUuid(uuid: string): string {
  return uuid.length > 20 ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : uuid;
}

type Props = {
  rows: AdminUserSummary[];
};

export function AdminUsersTable({ rows }: Props) {
  if (rows.length === 0) {
    return (
      <div
        className="py-12 text-center text-sm text-fg-muted"
        data-testid="empty-state"
      >
        No users found.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-border-subtle" data-testid="users-table">
      <table className="w-full text-sm">
        <thead className="bg-bg-subtle border-b border-border-subtle">
          <tr>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Name</th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Email</th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">SL UUID</th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Role</th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Verified</th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Status</th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Member since</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.id}
              data-testid={`user-row-${row.id}`}
              className="border-b border-border-subtle/50 hover:bg-bg-muted/50 cursor-pointer"
            >
              <td className="px-3 py-2.5">
                <Link
                  href={`/admin/users/${row.id}`}
                  className="block"
                  data-testid={`user-link-${row.id}`}
                >
                  <div className="text-fg font-medium">
                    {row.displayName ?? row.email}
                  </div>
                  <div className="text-[11px] text-fg-muted mt-0.5">
                    {row.completedSales} sold
                    {row.cancelledWithBids > 0 && (
                      <span className="text-danger-flat"> · {row.cancelledWithBids} cancelled</span>
                    )}
                  </div>
                </Link>
              </td>
              <td className="px-3 py-2.5 text-fg-muted">{row.email}</td>
              <td className="px-3 py-2.5">
                {row.slAvatarUuid ? (
                  <span className="font-mono text-[11px] text-fg-muted" title={row.slAvatarUuid}>
                    {truncateUuid(row.slAvatarUuid)}
                  </span>
                ) : (
                  <span className="text-fg-muted/50">—</span>
                )}
              </td>
              <td className="px-3 py-2.5">
                {row.role === "ADMIN" ? (
                  <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-info-bg text-info-flat">
                    ADMIN
                  </span>
                ) : (
                  <span className="text-[11px] text-fg-muted">User</span>
                )}
              </td>
              <td className="px-3 py-2.5">
                {row.verified ? (
                  <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-info-bg text-info-flat">
                    Yes
                  </span>
                ) : (
                  <span className="text-[11px] text-fg-muted">No</span>
                )}
              </td>
              <td className="px-3 py-2.5">
                {row.hasActiveBan ? (
                  <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-danger-flat text-white">
                    BANNED
                  </span>
                ) : (
                  <span className="text-[11px] text-fg-muted">—</span>
                )}
              </td>
              <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDate(row.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
