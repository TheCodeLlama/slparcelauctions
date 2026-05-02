"use client";
import { useWithdrawals } from "@/lib/admin/infrastructureHooks";

export function WithdrawalsHistorySection() {
  const { data } = useWithdrawals(0, 20);
  const rows = data?.content ?? [];
  if (rows.length === 0) return null;
  return (
    <section className="bg-bg-muted rounded p-4">
      <h2 className="text-sm font-semibold mb-3">Withdrawals history</h2>
      <table className="w-full text-xs">
        <thead className="text-[10px] uppercase opacity-55 text-left">
          <tr>
            <th>Requested</th><th>Admin</th><th>Amount</th>
            <th>Recipient</th><th>Status</th><th>Completed</th><th>Notes</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((w) => (
            <tr key={w.id} className="border-b border-border-subtle/40">
              <td className="py-2">{new Date(w.requestedAt).toLocaleString()}</td>
              <td className="py-2">#{w.adminUserId}</td>
              <td className="py-2">L$ {w.amount.toLocaleString()}</td>
              <td className="py-2 font-mono">{w.recipientUuid.slice(0, 8)}…</td>
              <td className={`py-2 ${
                w.status === "COMPLETED" ? "text-success" :
                w.status === "FAILED" ? "text-danger" : "text-info"
              }`}>{w.status}</td>
              <td className="py-2 opacity-70">{w.completedAt ? new Date(w.completedAt).toLocaleString() : "—"}</td>
              <td className="py-2 opacity-70">{w.notes ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
