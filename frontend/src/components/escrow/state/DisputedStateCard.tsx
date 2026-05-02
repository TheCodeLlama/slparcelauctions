import type { EscrowDisputeReasonCategory } from "@/types/escrow";
import type { StateCardProps } from "./types";

const CATEGORY_LABEL: Record<EscrowDisputeReasonCategory, string> = {
  SELLER_NOT_RESPONSIVE: "Seller not responsive",
  WRONG_PARCEL_TRANSFERRED: "Wrong parcel transferred",
  PAYMENT_NOT_CREDITED: "Payment not credited",
  FRAUD_SUSPECTED: "Fraud suspected",
  OTHER: "Other",
};

/**
 * DISPUTED state card. Terminal state — one of the two parties filed a
 * dispute and the escrow is awaiting manual SLPA review (admin tooling
 * lands with Epic 10). Copy is deliberately role-neutral: both parties
 * see the same facts about the filing so the UX doesn't bias the
 * viewer toward either side during review.
 */
export function DisputedStateCard({ escrow, role }: StateCardProps) {
  const category = escrow.disputeReasonCategory
    ? CATEGORY_LABEL[escrow.disputeReasonCategory]
    : "Unspecified";

  return (
    <section
      data-testid="escrow-state-card"
      data-state="DISPUTED"
      data-role={role}
      className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Dispute filed
        {escrow.disputedAt ? ` ${formatTimestamp(escrow.disputedAt)}` : ""}
      </h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">Category:</dt>
        <dd className="text-fg">{category}</dd>
        {escrow.disputeDescription ? (
          <>
            <dt className="text-fg-muted">Reason:</dt>
            <dd className="text-fg">{escrow.disputeDescription}</dd>
          </>
        ) : null}
      </dl>
      <p className="text-sm text-fg-muted">
        SLPA is reviewing this transaction. Expect a response within 48 hours.
      </p>
    </section>
  );
}

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
