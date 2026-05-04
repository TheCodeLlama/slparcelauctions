import type { EscrowLedgerEntry } from "@/lib/admin/disputes";

export function EscrowLedgerPanel({ entries }: { entries: EscrowLedgerEntry[] }) {
  return (
    <section>
      <div className="text-[10px] uppercase opacity-60 mb-2">Escrow ledger</div>
      <table className="w-full text-xs bg-bg-subtle rounded overflow-hidden">
        <tbody>
          {entries.length === 0 ? (
            <tr>
              <td className="py-2 px-3 opacity-60" colSpan={4}>No ledger entries.</td>
            </tr>
          ) : (
            entries.map((e, i) => (
              <tr key={i} className="border-b border-border-subtle/40">
                <td className="py-2 px-3 opacity-60">{new Date(e.at).toLocaleString()}</td>
                <td className="py-2 px-3 text-brand">{e.type}</td>
                <td className="py-2 px-3">{e.amount === null ? "—" : `L$ ${e.amount}`}</td>
                <td className="py-2 px-3 opacity-70">{e.detail}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  );
}
