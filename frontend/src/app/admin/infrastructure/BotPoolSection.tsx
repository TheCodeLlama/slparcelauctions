"use client";
import { useBotPoolHealth } from "@/lib/admin/infrastructureHooks";

export function BotPoolSection() {
  const { data: rows = [] } = useBotPoolHealth();
  const alive = rows.filter((r) => r.isAlive).length;
  const total = rows.length;

  return (
    <section className="bg-bg-muted rounded p-4">
      <header className="flex justify-between mb-3">
        <div>
          <h2 className="text-sm font-semibold">Bot pool</h2>
          <p className="text-[10px] opacity-55">Heartbeat 60s · Redis · TTL 180s</p>
        </div>
        <span className={`px-2.5 py-1 rounded-full text-[10px] ${
          total === 0 ? "bg-bg-subtle" :
          alive === total ? "bg-success-bg text-success-flat"
                          : "bg-danger-bg text-danger-flat"
        }`}>● {alive}/{total} healthy</span>
      </header>
      {rows.length === 0 ? (
        <p className="text-xs opacity-60">No bots registered yet.</p>
      ) : (
        <table className="w-full text-xs">
          <thead className="text-[10px] uppercase opacity-55 text-left">
            <tr>
              <th>Worker</th><th>SL UUID</th><th>State</th>
              <th>Region</th><th>Current task</th><th>Last beat</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.workerId} className="border-b border-border-subtle/40">
                <td className="py-2">{r.name}</td>
                <td className="py-2 font-mono opacity-70">{r.slUuid.slice(0, 8)}…</td>
                <td className={`py-2 ${r.isAlive ? "text-success-flat" : "text-danger-flat"}`}>
                  ● {r.sessionState ?? "MISSING"}
                </td>
                <td className="py-2">{r.currentRegion ?? "—"}</td>
                <td className="py-2 opacity-80">
                  {r.currentTaskType ? `${r.currentTaskType} ${r.currentTaskKey ?? ""}` : "—"}
                </td>
                <td className="py-2 opacity-70">{r.isAlive ? `${secondsAgo(r.lastSeenAt)}s ago` : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function secondsAgo(iso: string) {
  return Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
}
