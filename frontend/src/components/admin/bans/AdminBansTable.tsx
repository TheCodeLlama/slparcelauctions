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
        className="py-12 text-center text-sm text-fg-muted"
        data-testid="empty-state"
      >
        No bans match the current filter.
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-border-subtle">
      <table className="w-full text-sm" data-testid="bans-table">
        <thead className="sticky top-0 bg-bg-subtle border-b border-border-subtle">
          <tr>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted w-[70px]">
              Type
            </th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
              Identifier
            </th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
              Reason
            </th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted w-[110px]">
              Banned By
            </th>
            <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted w-[160px]">
              Expires
            </th>
            {showLift && (
              <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted w-[80px]">
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
              className="border-b border-border-subtle/50"
            >
              <td className="px-3 py-2.5">
                <BanTypeBadge banType={row.banType} />
              </td>
              <td className="px-3 py-2.5 text-fg">
                {(row.banType === "AVATAR" || row.banType === "BOTH") && row.slAvatarUuid && (
                  <div className="flex items-center gap-1 font-mono text-[11px]">
                    {row.avatarLinkedUserId ? (
                      <Link
                        href={`/admin/users/${row.avatarLinkedUserId}`}
                        className="text-brand underline underline-offset-2 hover:opacity-80"
                        data-testid={`avatar-link-${row.id}`}
                      >
                        {row.avatarLinkedDisplayName ?? truncate(row.slAvatarUuid, 16)}
                      </Link>
                    ) : (
                      <span className="text-fg-muted">{truncate(row.slAvatarUuid, 24)}</span>
                    )}
                  </div>
                )}
                {(row.banType === "IP" || row.banType === "BOTH") && row.ipAddress && (
                  <div className="font-mono text-[11px] text-fg-muted">
                    {row.ipAddress}
                  </div>
                )}
                {row.banType === "AVATAR" && row.firstSeenIp && (
                  <div className="font-mono text-[10px] text-fg-muted/70 mt-0.5">
                    first seen: {row.firstSeenIp}
                  </div>
                )}
              </td>
              <td className="px-3 py-2.5 text-fg-muted max-w-[200px]">
                <div className="text-[10px] text-fg-muted/70 uppercase tracking-wide">
                  {row.reasonCategory}
                </div>
                <div className="line-clamp-2">{row.reasonText}</div>
              </td>
              <td className="px-3 py-2.5 text-fg-muted">
                {row.bannedByDisplayName ?? `#${row.bannedByUserId}`}
              </td>
              <td className="px-3 py-2.5 text-fg-muted text-[11px]">
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
