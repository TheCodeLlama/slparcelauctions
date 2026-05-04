"use client";

import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { EscrowDeadlineBadge } from "@/components/escrow/EscrowDeadlineBadge";
import type { StateCardProps } from "./types";

/**
 * TRANSFER_PENDING (and FUNDED) state card. Splits on `transferConfirmedAt`
 * into two sub-phases:
 *
 *  - Pre-confirmation: seller sees a numbered SL-viewer recipe for
 *    transferring the parcel at L$0 to the winner's avatar (plus a
 *    clipboard copy button for the winner's avatar name). Winner sees a
 *    waiting banner with guidance thresholds and an inert "Message seller"
 *    placeholder (real messaging is future work).
 *  - Post-confirmation ("payout pending"): both roles see the same
 *    role-neutral acknowledgement; no dispute link because the backend is
 *    about to flip the escrow to COMPLETED. See spec §3.3.
 */
export function TransferPendingStateCard({ escrow, role }: StateCardProps) {
  const postConfirmation = escrow.transferConfirmedAt != null;

  if (postConfirmation) {
    return (
      <section
        data-testid="escrow-state-card"
        data-state="TRANSFER_PENDING"
        data-phase="payout-pending"
        data-role={role}
        className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
      >
        <h2 className="text-sm font-semibold tracking-tight text-fg">
          Ownership transferred to the winner
        </h2>
        <p className="text-sm text-fg-muted">
          Transferred at {formatTimestamp(escrow.transferConfirmedAt!)}.
          Finalizing the transaction. Payout is dispatching now.
        </p>
      </section>
    );
  }

  if (role === "seller") {
    return (
      <SellerPreConfirmation escrow={escrow} />
    );
  }

  return <WinnerPreConfirmation escrow={escrow} />;
}

function SellerPreConfirmation({
  escrow,
}: {
  escrow: StateCardProps["escrow"];
}) {
  return (
    <section
      data-testid="escrow-state-card"
      data-state="TRANSFER_PENDING"
      data-phase="pre-confirmation"
      data-role="seller"
      className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
        Seller
      </span>
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Transfer the parcel to the winner
      </h2>
      <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm text-fg-muted">
        <li>Right-click the parcel in-world and open About Land.</li>
        <li>Click Sell Land.</li>
        <li>
          Set &quot;Sell to:&quot; to the winner&apos;s avatar name.
        </li>
        <li>Set the price to L$ 0 (SLPA has already escrowed payment).</li>
        <li>Confirm the sale in the SL viewer dialog.</li>
      </ol>


      {escrow.transferDeadline ? (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-fg-muted">Transfer deadline:</span>
          <EscrowDeadlineBadge deadline={escrow.transferDeadline} />
        </div>
      ) : null}

      <Link
        href={`/auction/${escrow.auctionPublicId}/escrow/dispute`}
        className="text-sm font-medium text-brand hover:underline"
      >
        File a dispute
      </Link>
    </section>
  );
}

function WinnerPreConfirmation({
  escrow,
}: {
  escrow: StateCardProps["escrow"];
}) {
  return (
    <section
      data-testid="escrow-state-card"
      data-state="TRANSFER_PENDING"
      data-phase="pre-confirmation"
      data-role="winner"
      className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
        Winner
      </span>
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Waiting for seller to transfer the parcel
      </h2>
      <p className="text-sm text-fg-muted">
        Typical completion is under 24 hours. You&apos;ll see this flip to
        Complete automatically within 5 minutes of the transfer.
      </p>

      <div className="rounded-lg bg-bg-subtle p-3">
        <p className="mb-2 text-xs font-medium font-semibold text-fg">
          What you can do:
        </p>
        <ul className="flex list-disc flex-col gap-1 pl-5 text-xs text-fg-muted">
          <li>
            <span className="font-medium text-fg">Wait.</span> Most
            transfers complete on their own.
          </li>
          <li>
            <span className="font-medium text-fg">Message seller</span>{" "}
            if stalled &gt; 24 hours.
          </li>
          <li>
            <span className="font-medium text-fg">Dispute</span> if
            &gt; 48 hours with no progress.
          </li>
        </ul>
      </div>

      <div>
        {/* Inert placeholder — direct messaging is outside sub-spec 2 scope. */}
        <Button variant="secondary" size="md" disabled>
          Message seller
        </Button>
      </div>

      {escrow.transferDeadline ? (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-fg-muted">Transfer deadline:</span>
          <EscrowDeadlineBadge deadline={escrow.transferDeadline} />
        </div>
      ) : null}

      <Link
        href={`/auction/${escrow.auctionPublicId}/escrow/dispute`}
        className="text-sm font-medium text-brand hover:underline"
      >
        File a dispute
      </Link>
    </section>
  );
}

function formatTimestamp(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
