import type { StateCardProps } from "./types";

/**
 * EXPIRED state card. Terminal state — the backend emits a single EXPIRED
 * state for both deadline-timeout branches. We disambiguate on `fundedAt`:
 *
 *  - `fundedAt == null` ⇒ pre-fund payment timeout (winner didn't pay).
 *    No refund is queued because no funds were captured.
 *  - `fundedAt != null` ⇒ post-fund transfer timeout (seller didn't
 *    transfer). Refund of `finalBidAmount` has been queued to the winner.
 *
 * See spec §3.3 EXPIRED branching note.
 */
export function ExpiredStateCard({ escrow, role }: StateCardProps) {
  const postFund = escrow.fundedAt != null;

  return (
    <section
      data-testid="escrow-state-card"
      data-state="EXPIRED"
      data-phase={postFund ? "transfer-timeout" : "payment-timeout"}
      data-role={role}
      className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        {postFund ? "Transfer deadline expired" : "Payment deadline expired"}
      </h2>
      <p className="text-sm text-fg-muted">
        {renderBody({ escrow, role, postFund })}
      </p>
    </section>
  );
}

function renderBody({
  escrow,
  role,
  postFund,
}: {
  escrow: StateCardProps["escrow"];
  role: StateCardProps["role"];
  postFund: boolean;
}): string {
  if (!postFund) {
    if (role === "seller") {
      return "Escrow expired because the winner didn't pay by the deadline.";
    }
    return "You didn't pay by the deadline. The auction has expired.";
  }

  const amount = escrow.finalBidAmount.toLocaleString();
  if (role === "seller") {
    return `Escrow expired because the transfer wasn't completed by the deadline. Refund of L$ ${amount} has been queued to the winner.`;
  }
  return `Seller didn't complete the transfer. Your L$ ${amount} refund has been queued and should land in your SL wallet shortly.`;
}
