import type { AdminDisputeDetail } from "@/lib/admin/disputes";
import { EvidenceImageLightbox } from "./EvidenceImageLightbox";

export function EvidenceSideBySidePanel({ d }: { d: AdminDisputeDetail }) {
  return (
    <section>
      <div className="text-[10px] uppercase opacity-60 mb-2">Evidence</div>
      <div className="grid grid-cols-2 gap-3">
        <div className="bg-bg-subtle rounded p-3">
          <div className="text-[10px] uppercase opacity-55 mb-2">Winner&apos;s evidence ({d.winnerEmail})</div>
          <p className="text-xs whitespace-pre-wrap mb-2">{d.winnerDescription}</p>
          {d.slTransactionKey && (
            <div className="text-[11px] opacity-75 mb-2">
              SL tx: <span className="font-mono text-brand">{d.slTransactionKey}</span>
            </div>
          )}
          <EvidenceImageLightbox images={d.winnerEvidence} />
        </div>
        <div className="bg-bg-subtle rounded p-3">
          <div className="text-[10px] uppercase opacity-55 mb-2">Seller&apos;s evidence ({d.sellerEmail})</div>
          {d.sellerEvidenceSubmittedAt ? (
            <>
              <p className="text-xs whitespace-pre-wrap mb-2">{d.sellerEvidenceText}</p>
              <EvidenceImageLightbox images={d.sellerEvidence} />
            </>
          ) : (
            <p className="text-xs italic opacity-50">No evidence submitted yet.</p>
          )}
        </div>
      </div>
    </section>
  );
}
