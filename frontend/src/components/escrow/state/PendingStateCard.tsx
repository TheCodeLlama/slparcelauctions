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
      className="flex flex-col gap-4 rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft"
    >
      <span className="text-label-sm uppercase tracking-wide text-on-surface-variant">
        {role === "seller" ? "Seller" : "Winner"}
      </span>

      {role === "seller" ? (
        <>
          <h2 className="text-title-md text-on-surface">
            Awaiting payment from {escrow.counterparty.displayName}
          </h2>
          <p className="text-body-md text-on-surface-variant">
            If they don&apos;t pay you&apos;ll be able to re-list once the
            escrow expires.
          </p>
        </>
      ) : (
        <>
          <h2 className="text-title-md text-on-surface">
            Pay L$ {escrow.finalBidAmount.toLocaleString()}
          </h2>
          <p className="text-body-md text-on-surface-variant">
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

      <div className="flex items-center gap-2 text-body-md">
        <span className="text-on-surface-variant">Payment deadline:</span>
        <EscrowDeadlineBadge deadline={escrow.paymentDeadline} />
      </div>

      <Link
        href={`/auction/${escrow.auctionId}/escrow/dispute`}
        className="text-label-lg text-primary hover:underline"
      >
        File a dispute
      </Link>
    </section>
  );
}
