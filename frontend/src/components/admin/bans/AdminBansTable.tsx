"use client";
import Link from "next/link";
import { BanTypeBadge } from "./BanTypeBadge";
import { Button } from "@/components/ui/Button";
import type { AdminBanRow } from "@/lib/admin/types";

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatExpiry(expiresAt: string | null): string {
  if (!expiresAt) return "Permanent";
  const d = new Date(expiresAt);
  const now = Date.now();
  const diffMs = d.getTime() - now;
  const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));
  const abs = formatDate(expiresAt);
  if (diffDays <= 0) return `${abs} (expired)`;
  return `${abs} (in ${diffDays}d)`;
}

function truncate(str: string, max: number): string {
  return str.length > max ? `${str.slice(0, max)}…` : str;
}

type Props = {
  rows: AdminBanRow[];
  onLift: (ban: AdminBanRow) => void;
  showLift: boolean;
};

export function AdminBansTable({ rows, onLift, showLift }: Props) {
  if (rows.length === 0) {
    return (
      <div
        className="py-12 text-center text-body-sm text-on-surface-variant"
        data-testid="empty-state"
      >
        No bans match the current filter.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-default border border-outline-variant">
      <table className="w-full text-body-sm" data-testid="bans-table">
        <thead className="sticky top-0 bg-surface-container-low border-b border-outline-variant">
          <tr>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium w-[70px]">
              Type
            </th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">
              Identifier
            </th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">
              Reason
            </th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium w-[110px]">
              Banned By
            </th>
            <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium w-[160px]">
              Expires
            </th>
            {showLift && (
              <th className="px-3 py-2.5 text-right text-label-sm text-on-surface-variant font-medium w-[80px]">
                Action
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.id}
              data-testid={`ban-row-${row.id}`}
              className="border-b border-outline-variant/50"
            >
              <td className="px-3 py-2.5">
                <BanTypeBadge banType={row.banType} />
              </td>
              <td className="px-3 py-2.5 text-on-surface">
                {(row.banType === "AVATAR" || row.banType === "BOTH") && row.slAvatarUuid && (
                  <div className="flex items-center gap-1 font-mono text-[11px]">
                    {row.avatarLinkedUserId ? (
                      <Link
                        href={`/admin/users/${row.avatarLinkedUserId}`}
                        className="text-primary underline underline-offset-2 hover:opacity-80"
                        data-testid={`avatar-link-${row.id}`}
                      >
                        {row.avatarLinkedDisplayName ?? truncate(row.slAvatarUuid, 16)}
                      </Link>
                    ) : (
                      <span className="text-on-surface-variant">{truncate(row.slAvatarUuid, 24)}</span>
                    )}
                  </div>
                )}
                {(row.banType === "IP" || row.banType === "BOTH") && row.ipAddress && (
                  <div className="font-mono text-[11px] text-on-surface-variant">
                    {row.ipAddress}
                  </div>
                )}
                {row.banType === "AVATAR" && row.firstSeenIp && (
                  <div className="font-mono text-[10px] text-on-surface-variant/70 mt-0.5">
                    first seen: {row.firstSeenIp}
                  </div>
                )}
              </td>
              <td className="px-3 py-2.5 text-on-surface-variant max-w-[200px]">
                <div className="text-[10px] text-on-surface-variant/70 uppercase tracking-wide">
                  {row.reasonCategory}
                </div>
                <div className="line-clamp-2">{row.reasonText}</div>
              </td>
              <td className="px-3 py-2.5 text-on-surface-variant">
                {row.bannedByDisplayName ?? `#${row.bannedByUserId}`}
              </td>
              <td className="px-3 py-2.5 text-on-surface-variant text-[11px]">
                {formatExpiry(row.expiresAt)}
              </td>
              {showLift && (
                <td className="px-3 py-2.5 text-right">
                  <Button
                    variant="destructive"
                    size="sm"
                    onClick={() => onLift(row)}
                    data-testid={`lift-btn-${row.id}`}
                  >
                    Lift
                  </Button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
