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
        className="py-12 text-center text-body-sm text-on-surface-variant"
        data-testid="empty-state"
      >
        No users found.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-default border border-outline-variant" data-testid="users-table">
      <table className="w-full text-body-sm">
        <thead className="bg-surface-container-low border-b border-outline-variant">
          <tr>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Name</th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Email</th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">SL UUID</th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Role</th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Verified</th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Status</th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Member since</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.id}
              data-testid={`user-row-${row.id}`}
              className="border-b border-outline-variant/50 hover:bg-surface-container/50 cursor-pointer"
            >
              <td className="px-3 py-2.5">
                <Link
                  href={`/admin/users/${row.id}`}
                  className="block"
                  data-testid={`user-link-${row.id}`}
                >
                  <div className="text-on-surface font-medium">
                    {row.displayName ?? row.email}
                  </div>
                  <div className="text-[11px] text-on-surface-variant mt-0.5">
                    {row.completedSales} sold
                    {row.cancelledWithBids > 0 && (
                      <span className="text-error"> · {row.cancelledWithBids} cancelled</span>
                    )}
                  </div>
                </Link>
              </td>
              <td className="px-3 py-2.5 text-on-surface-variant">{row.email}</td>
              <td className="px-3 py-2.5">
                {row.slAvatarUuid ? (
                  <span className="font-mono text-[11px] text-on-surface-variant" title={row.slAvatarUuid}>
                    {truncateUuid(row.slAvatarUuid)}
                  </span>
                ) : (
                  <span className="text-on-surface-variant/50">—</span>
                )}
              </td>
              <td className="px-3 py-2.5">
                {row.role === "ADMIN" ? (
                  <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-tertiary-container text-on-tertiary-container">
                    ADMIN
                  </span>
                ) : (
                  <span className="text-[11px] text-on-surface-variant">User</span>
                )}
              </td>
              <td className="px-3 py-2.5">
                {row.verified ? (
                  <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-secondary-container text-on-secondary-container">
                    Yes
                  </span>
                ) : (
                  <span className="text-[11px] text-on-surface-variant">No</span>
                )}
              </td>
              <td className="px-3 py-2.5">
                {row.hasActiveBan ? (
                  <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-error text-on-error">
                    BANNED
                  </span>
                ) : (
                  <span className="text-[11px] text-on-surface-variant">—</span>
                )}
              </td>
              <td className="px-3 py-2.5 text-on-surface-variant text-[11px]">{formatDate(row.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
