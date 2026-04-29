"use client";

import { useRouter } from "next/navigation";
import { useDispute } from "@/lib/admin/disputeHooks";
import { EscrowLedgerPanel } from "./EscrowLedgerPanel";
import { EvidenceSideBySidePanel } from "./EvidenceSideBySidePanel";
import { ResolutionPanel } from "./ResolutionPanel";

export function AdminDisputeDetailPage({ escrowId }: { escrowId: number }) {
  const router = useRouter();
  const { data, isLoading, error } = useDispute(escrowId);
  if (isLoading) return <p>Loading…</p>;
  if (error || !data) return <p className="text-error">Failed to load dispute</p>;

  return (
    <div className="space-y-4">
      <nav className="text-xs">
        <a href="/admin/disputes" className="text-primary">← Disputes</a>
        <span className="opacity-40 mx-2">/</span>
        <span className="opacity-85">{data.auctionTitle}</span>
      </nav>

      <header className="bg-surface-container rounded p-4 flex gap-4 items-center">
        <span className={`px-2.5 py-1 rounded text-[11px] ${
          data.status === "DISPUTED" ? "bg-error-container text-on-error-container" :
          "bg-tertiary-container text-on-tertiary-container"
        }`}>
          {data.status === "DISPUTED" ? "⚐ DISPUTED" : "❄ FROZEN"}
        </span>
        <div className="flex-1">
          <h1 className="text-base font-semibold">{data.auctionTitle}</h1>
          <p className="text-[11px] opacity-65">
            {data.sellerEmail} → {data.winnerEmail} · L$ {data.salePriceL.toLocaleString()} ·
            Auction #{data.auctionId} · Opened {new Date(data.openedAt).toLocaleString()}
          </p>
        </div>
        {data.reasonCategory && (
          <div className="text-[11px] opacity-55">Reason: <strong>{data.reasonCategory}</strong></div>
        )}
      </header>

      <div className="grid grid-cols-[1fr_360px] gap-4">
        <div className="space-y-4">
          <EscrowLedgerPanel entries={data.ledger} />
          <EvidenceSideBySidePanel d={data} />
        </div>
        <ResolutionPanel
          dispute={data}
          onResolved={() => router.push("/admin/disputes")}
        />
      </div>
    </div>
  );
}
