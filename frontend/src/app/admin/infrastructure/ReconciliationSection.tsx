"use client";
import { useReconciliationRuns } from "@/lib/admin/infrastructureHooks";

export function ReconciliationSection() {
  const { data: runs = [] } = useReconciliationRuns(7);
  const latest = runs[0];

  return (
    <section className="bg-bg-muted rounded p-4">
      <header className="flex justify-between mb-3">
        <div>
          <h2 className="text-sm font-semibold">Daily balance reconciliation</h2>
          <p className="text-[10px] opacity-55">Sum of pending escrow vs SLPA SL account · runs 03:00 UTC daily</p>
        </div>
        {latest && (
          <span className={`px-2.5 py-1 rounded-full text-[10px] ${badgeFor(latest.status)}`}>
            ● {labelFor(latest.status)}
          </span>
        )}
      </header>

      {!latest && <p className="text-xs opacity-60">No reconciliation runs yet.</p>}

      {latest && (
        <div className="bg-bg-subtle rounded p-3 mb-3 text-xs space-y-1">
          <Row label="Last run" value={new Date(latest.ranAt).toLocaleString()} />
          <Row label="Expected (locked sum)" value={`L$ ${latest.expected}`} />
          <Row label="Observed (grid balance)" value={latest.observed !== null ? `L$ ${latest.observed}` : "—"} />
          <Row label="Drift" value={latest.drift !== null ? `L$ ${latest.drift}` : "—"} />
          {latest.errorMessage && <p className="text-danger-flat text-[11px] mt-2">{latest.errorMessage}</p>}
        </div>
      )}

      {runs.length > 0 && (
        <>
          <div className="text-[10px] uppercase opacity-55 mb-2">History</div>
          <div className="flex gap-1.5 flex-wrap">
            {runs.map((r) => (
              <span key={r.id} className={`px-2 py-1 rounded text-[10.5px] ${badgeFor(r.status)}`}>
                {new Date(r.ranAt).toLocaleDateString()}{" "}
                {r.status === "BALANCED" ? "✓" : r.status === "MISMATCH" ? `⚠ L$ ${r.drift}` : "—"}
              </span>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <span className="opacity-65">{label}</span>
      <span>{value}</span>
    </div>
  );
}

function badgeFor(s: string) {
  return s === "BALANCED" ? "bg-success-bg text-success-flat"
       : s === "MISMATCH" ? "bg-info-bg text-info-flat"
       : "bg-bg-subtle";
}

function labelFor(s: string) {
  return s === "BALANCED" ? "Last run balanced"
       : s === "MISMATCH" ? "Last run mismatch"
       : "Last run errored";
}
