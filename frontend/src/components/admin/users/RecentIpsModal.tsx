"use client";
import { useEffect, useState } from "react";
import { useAdminUserIps } from "@/hooks/admin/useAdminUserIps";
import { CreateBanModal } from "@/components/admin/bans/CreateBanModal";

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

type Props = {
  userId: number;
  onClose: () => void;
};

export function RecentIpsModal({ userId, onClose }: Props) {
  const { data, isLoading, isError } = useAdminUserIps(userId);
  const [banIp, setBanIp] = useState<string | null>(null);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-inverse-surface/20"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Recent IPs"
        data-testid="recent-ips-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-lg rounded-default bg-surface-container-low border border-outline-variant shadow-elevated p-6 flex flex-col gap-4 max-h-[80vh] overflow-y-auto"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between">
            <h2 className="text-title-md font-semibold text-on-surface">Recent IPs</h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="p-1.5 rounded-default text-on-surface-variant hover:bg-surface-container"
            >
              ✕
            </button>
          </div>

          {isLoading && (
            <div className="text-body-sm text-on-surface-variant py-4">Loading IPs…</div>
          )}

          {isError && (
            <div className="text-body-sm text-error py-4">Could not load IPs.</div>
          )}

          {data && data.length === 0 && (
            <div className="text-body-sm text-on-surface-variant py-4">No IP history found.</div>
          )}

          {data && data.length > 0 && (
            <div className="overflow-x-auto rounded-default border border-outline-variant">
              <table className="w-full text-body-sm">
                <thead className="bg-surface-container-low border-b border-outline-variant">
                  <tr>
                    <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">IP Address</th>
                    <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Sessions</th>
                    <th className="px-3 py-2.5 text-left text-label-sm text-on-surface-variant font-medium">Last seen</th>
                    <th className="px-3 py-2.5 w-[80px]" />
                  </tr>
                </thead>
                <tbody>
                  {data.map((row) => (
                    <tr
                      key={row.ipAddress}
                      className="border-b border-outline-variant/50"
                      data-testid={`ip-row-${row.ipAddress}`}
                    >
                      <td className="px-3 py-2.5 font-mono text-[11px] text-on-surface">{row.ipAddress}</td>
                      <td className="px-3 py-2.5 text-on-surface-variant">{row.sessionCount}</td>
                      <td className="px-3 py-2.5 text-on-surface-variant text-[11px]">{formatDate(row.lastSeenAt)}</td>
                      <td className="px-3 py-2.5 text-right">
                        <button
                          type="button"
                          onClick={() => setBanIp(row.ipAddress)}
                          data-testid={`ban-ip-btn-${row.ipAddress}`}
                          className="text-[11px] text-error hover:underline underline-offset-2"
                        >
                          Ban IP
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      <CreateBanModal
        open={banIp !== null}
        onClose={() => setBanIp(null)}
        initialIpAddress={banIp ?? undefined}
      />
    </>
  );
}
