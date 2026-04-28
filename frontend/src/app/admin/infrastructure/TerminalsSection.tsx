"use client";
import { useState } from "react";
import { useTerminalsAdmin } from "@/lib/admin/infrastructureHooks";
import { RotateSecretModal } from "./RotateSecretModal";

export function TerminalsSection() {
  const { data: rows = [] } = useTerminalsAdmin();
  const [rotateOpen, setRotateOpen] = useState(false);
  const active = rows.filter((r) => r.lastHeartbeatAt !== null).length;

  return (
    <section className="bg-surface-container rounded p-4">
      <header className="flex justify-between mb-3">
        <div>
          <h2 className="text-sm font-semibold">Terminals</h2>
          <p className="text-[10px] opacity-55">Registered LSL terminals · shared-secret authenticated</p>
        </div>
        <span className="px-2.5 py-1 rounded-full text-[10px] bg-success-container text-on-success-container">
          ● {active}/{rows.length} active
        </span>
      </header>
      {rows.length === 0 ? (
        <p className="text-xs opacity-60 mb-3">No terminals registered yet.</p>
      ) : (
        <table className="w-full text-xs mb-3">
          <thead className="text-[10px] uppercase opacity-55 text-left">
            <tr><th>Region</th><th>Terminal</th><th>Last cmd</th><th>Balance</th><th>Secret v.</th></tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.terminalId} className="border-b border-outline-variant/40">
                <td className="py-2">{t.regionName ?? "—"}</td>
                <td className="py-2">{t.terminalId}</td>
                <td className="py-2 opacity-80">{t.lastSeenAt ? new Date(t.lastSeenAt).toLocaleString() : "—"}</td>
                <td className="py-2">{t.lastReportedBalance !== null ? `L$ ${t.lastReportedBalance}` : "—"}</td>
                <td className="py-2">v{t.currentSecretVersion ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <button
        type="button"
        onClick={() => setRotateOpen(true)}
        className="px-3 py-1.5 border border-outline rounded text-xs text-tertiary"
      >⟳ Rotate shared secret →</button>
      {rotateOpen && <RotateSecretModal onClose={() => setRotateOpen(false)} />}
    </section>
  );
}
