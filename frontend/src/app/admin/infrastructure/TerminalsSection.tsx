"use client";
import { useState } from "react";
import {
  useTerminalsAdmin,
  useDeactivateTerminal,
} from "@/lib/admin/infrastructureHooks";
import { RotateSecretModal } from "./RotateSecretModal";

export function TerminalsSection() {
  const { data: rows = [] } = useTerminalsAdmin();
  const deactivate = useDeactivateTerminal();
  const [rotateOpen, setRotateOpen] = useState(false);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const active = rows.filter((r) => r.lastHeartbeatAt !== null).length;

  const handleConfirmDeactivate = () => {
    if (!confirmId) return;
    deactivate.mutate(confirmId, {
      onSettled: () => setConfirmId(null),
    });
  };

  return (
    <section className="bg-bg-muted rounded p-4">
      <header className="flex justify-between mb-3">
        <div>
          <h2 className="text-sm font-semibold">Terminals</h2>
          <p className="text-[10px] opacity-55">Registered LSL terminals · shared-secret authenticated</p>
        </div>
        <span className="px-2.5 py-1 rounded-full text-[10px] bg-success-bg text-success">
          ● {active}/{rows.length} active
        </span>
      </header>
      {rows.length === 0 ? (
        <p className="text-xs opacity-60 mb-3">No terminals registered yet.</p>
      ) : (
        <table className="w-full text-xs mb-3">
          <thead className="text-[10px] uppercase opacity-55 text-left">
            <tr>
              <th>Region</th>
              <th>Terminal</th>
              <th>Last cmd</th>
              <th>Balance</th>
              <th>Secret v.</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.terminalId} className="border-b border-border-subtle/40">
                <td className="py-2">{t.regionName ?? "—"}</td>
                <td className="py-2">{t.terminalId}</td>
                <td className="py-2 opacity-80">{t.lastSeenAt ? new Date(t.lastSeenAt).toLocaleString() : "—"}</td>
                <td className="py-2">{t.lastReportedBalance !== null ? `L$ ${t.lastReportedBalance}` : "—"}</td>
                <td className="py-2">v{t.currentSecretVersion ?? "—"}</td>
                <td className="py-2 text-right">
                  <button
                    type="button"
                    onClick={() => setConfirmId(t.terminalId)}
                    className="px-2 py-1 text-[10px] text-danger border border-danger/40 rounded hover:bg-danger-bg/10"
                    aria-label={`Unregister terminal ${t.terminalId}`}
                  >
                    Unregister
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <button
        type="button"
        onClick={() => setRotateOpen(true)}
        className="px-3 py-1.5 border border-border rounded text-xs text-info"
      >⟳ Rotate shared secret →</button>
      {rotateOpen && <RotateSecretModal onClose={() => setRotateOpen(false)} />}
      {confirmId && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-bg-muted max-w-md w-full rounded-lg p-5">
            <h3 className="text-sm font-semibold mb-2">Unregister terminal</h3>
            <p className="text-xs opacity-80 mb-3">
              This soft-deletes the terminal — sets <code>active=false</code> so the
              dispatcher stops routing commands to it. The row stays in the database
              for forensics. If the in-world script later re-registers, it will become
              active again.
            </p>
            <p className="text-xs mb-4">
              Terminal: <code className="font-mono">{confirmId}</code>
            </p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setConfirmId(null)}
                disabled={deactivate.isPending}
                className="px-3 py-1.5 border border-border rounded text-xs"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleConfirmDeactivate}
                disabled={deactivate.isPending}
                className="px-3 py-1.5 bg-danger text-white rounded text-xs"
              >
                {deactivate.isPending ? "Unregistering…" : "Unregister"}
              </button>
            </div>
            {deactivate.isError && (
              <p className="mt-2 text-xs text-danger">
                Failed to unregister. Try again or use the API directly.
              </p>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
