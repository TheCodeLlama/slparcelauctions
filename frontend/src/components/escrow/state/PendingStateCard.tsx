import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { EscrowDeadlineBadge } from "@/components/escrow/EscrowDeadlineBadge";
import type { StateCardProps } from "./types";

/**
 * ESCROW_PENDING state card. Renders role-aware copy for the pre-payment
 * window — the seller is waiting on the winner to fund escrow via an
 * in-world terminal. Both views carry a payment-deadline badge and a link
 * into the dispute flow; the winner additionally sees a placeholder
 * "Find a terminal" button (see DEFERRED_WORK §11 opener — terminal
 * locator feature ships with Epic 11 LSL terminals).
 */
export function PendingStateCard({ escrow, role }: StateCardProps) {
  return (
    <section
      data-testid="escrow-state-card"
      data-state="ESCROW_PENDING"
      data-role={role}
      className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
        {role === "seller" ? "Seller" : "Winner"}
      </span>

      {role === "seller" ? (
        <>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Awaiting payment from the winner
          </h2>
          <p className="text-sm text-fg-muted">
            If they don&apos;t pay you&apos;ll be able to re-list once the
            escrow expires.
          </p>
        </>
      ) : (
        <>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Pay L$ {escrow.finalBidAmount.toLocaleString()}
          </h2>
          <p className="text-sm text-fg-muted">
            Pay at an SLPA terminal in-world. Your winning bid needs to land
            before the payment deadline.
          </p>
          <div>
            {/* Inert placeholder — terminal locator is DEFERRED_WORK §11
                (ships with Epic 11 LSL terminals). */}
            <Button variant="primary" size="md" disabled>
              Find a terminal
            </Button>
          </div>
        </>
      )}

      <div className="flex items-center gap-2 text-sm">
        <span className="text-fg-muted">Payment deadline:</span>
        <EscrowDeadlineBadge deadline={escrow.paymentDeadline} />
      </div>

      <Link
        href={`/auction/${escrow.auctionPublicId}/escrow/dispute`}
        className="text-sm font-medium text-brand hover:underline"
      >
        File a dispute
      </Link>
    </section>
  );
}
