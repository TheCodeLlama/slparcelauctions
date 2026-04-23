"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { EscrowDeadlineBadge } from "@/components/escrow/EscrowDeadlineBadge";
import type { StateCardProps } from "./types";

const COPIED_DURATION_MS = 2_000;

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
        className="flex flex-col gap-4 rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft"
      >
        <h2 className="text-title-md text-on-surface">
          Ownership transferred to the winner
        </h2>
        <p className="text-body-md text-on-surface-variant">
          Transferred at {formatTimestamp(escrow.transferConfirmedAt!)}.
          Finalizing the transaction — payout is dispatching now.
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
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(escrow.counterparty.slAvatarName);
      setCopied(true);
      window.setTimeout(() => setCopied(false), COPIED_DURATION_MS);
    } catch {
      // Clipboard API can fail in locked-down browsers; silently degrade.
    }
  }

  return (
    <section
      data-testid="escrow-state-card"
      data-state="TRANSFER_PENDING"
      data-phase="pre-confirmation"
      data-role="seller"
      className="flex flex-col gap-4 rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft"
    >
      <span className="text-label-sm uppercase tracking-wide text-on-surface-variant">
        Seller
      </span>
      <h2 className="text-title-md text-on-surface">
        Transfer the parcel to {escrow.counterparty.displayName}
      </h2>
      <ol className="flex list-decimal flex-col gap-2 pl-5 text-body-md text-on-surface-variant">
        <li>Right-click the parcel in-world and open About Land.</li>
        <li>Click Sell Land.</li>
        <li>
          Set &quot;Sell to:&quot; to{" "}
          <span className="font-medium text-on-surface">
            {escrow.counterparty.slAvatarName}
          </span>
          .
        </li>
        <li>Set the price to L$ 0 (SLPA has already escrowed payment).</li>
        <li>Confirm the sale in the SL viewer dialog.</li>
      </ol>

      <div className="flex items-center gap-3">
        <Button variant="secondary" size="sm" onClick={handleCopy}>
          {copied ? "Copied!" : "Copy winner name"}
        </Button>
      </div>

      {escrow.transferDeadline ? (
        <div className="flex items-center gap-2 text-body-md">
          <span className="text-on-surface-variant">Transfer deadline:</span>
          <EscrowDeadlineBadge deadline={escrow.transferDeadline} />
        </div>
      ) : null}

      <Link
        href={`/auction/${escrow.auctionId}/escrow/dispute`}
        className="text-label-lg text-primary hover:underline"
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
      className="flex flex-col gap-4 rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft"
    >
      <span className="text-label-sm uppercase tracking-wide text-on-surface-variant">
        Winner
      </span>
      <h2 className="text-title-md text-on-surface">
        Waiting for seller to transfer the parcel
      </h2>
      <p className="text-body-md text-on-surface-variant">
        Typical completion is under 24 hours. You&apos;ll see this flip to
        Complete automatically within 5 minutes of the transfer.
      </p>

      <div className="rounded-default bg-surface-container-low p-3">
        <p className="mb-2 text-label-md font-semibold text-on-surface">
          What you can do:
        </p>
        <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm text-on-surface-variant">
          <li>
            <span className="font-medium text-on-surface">Wait</span> — most
            transfers complete on their own.
          </li>
          <li>
            <span className="font-medium text-on-surface">Message seller</span>{" "}
            if stalled &gt; 24 hours.
          </li>
          <li>
            <span className="font-medium text-on-surface">Dispute</span> if
            &gt; 48 hours with no progress.
          </li>
        </ul>
      </div>

      <div>
        {/* Inert placeholder — direct messaging is outside sub-spec 2 scope. */}
        <Button variant="secondary" size="md" disabled>
          Message {escrow.counterparty.displayName}
        </Button>
      </div>

      {escrow.transferDeadline ? (
        <div className="flex items-center gap-2 text-body-md">
          <span className="text-on-surface-variant">Transfer deadline:</span>
          <EscrowDeadlineBadge deadline={escrow.transferDeadline} />
        </div>
      ) : null}

      <Link
        href={`/auction/${escrow.auctionId}/escrow/dispute`}
        className="text-label-lg text-primary hover:underline"
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
