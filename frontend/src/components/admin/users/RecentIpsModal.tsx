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
  publicId: string;
  onClose: () => void;
};

export function RecentIpsModal({ publicId, onClose }: Props) {
  const { data, isLoading, isError } = useAdminUserIps(publicId);
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
          className="w-full max-w-lg rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4 max-h-[80vh] overflow-y-auto"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-fg">Recent IPs</h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
            >
              ✕
            </button>
          </div>

          {isLoading && (
            <div className="text-sm text-fg-muted py-4">Loading IPs…</div>
          )}

          {isError && (
            <div className="text-sm text-danger py-4">Could not load IPs.</div>
          )}

          {data && data.length === 0 && (
            <div className="text-sm text-fg-muted py-4">No IP history found.</div>
          )}

          {data && data.length > 0 && (
            <div className="overflow-x-auto rounded-lg border border-border-subtle">
              <table className="w-full text-sm">
                <thead className="bg-bg-subtle border-b border-border-subtle">
                  <tr>
                    <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">IP Address</th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Sessions</th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Last seen</th>
                    <th className="px-3 py-2.5 w-[80px]" />
                  </tr>
                </thead>
                <tbody>
                  {data.map((row) => (
                    <tr
                      key={row.ipAddress}
                      className="border-b border-border-subtle/50"
                      data-testid={`ip-row-${row.ipAddress}`}
                    >
                      <td className="px-3 py-2.5 font-mono text-[11px] text-fg">{row.ipAddress}</td>
                      <td className="px-3 py-2.5 text-fg-muted">{row.sessionCount}</td>
                      <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDate(row.lastSeenAt)}</td>
                      <td className="px-3 py-2.5 text-right">
                        <button
                          type="button"
                          onClick={() => setBanIp(row.ipAddress)}
                          data-testid={`ban-ip-btn-${row.ipAddress}`}
                          className="text-[11px] text-danger hover:underline underline-offset-2"
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
