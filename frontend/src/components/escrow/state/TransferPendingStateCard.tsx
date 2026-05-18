"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { EscrowDeadlineBadge } from "@/components/escrow/EscrowDeadlineBadge";
import { Copy, ExternalLink, AlertTriangle } from "@/components/ui/icons";
import {
  useVerifySellTo,
  useVerifyTransfer,
  useRequestManualReview,
} from "@/hooks/useEscrowManualActions";
import type { StateCardProps } from "./types";
import type { EscrowStatusResponse } from "@/types/escrow";

/**
 * TRANSFER_PENDING (and FUNDED) state card. Split into three sub-phases
 * (spec 2026-05-17-escrow-transfer-split-verification §9):
 *
 *  - **Set Sell To** (`sellToConfirmedAt == null`): seller sees a numbered
 *    SL-viewer recipe for setting the parcel's "Sell to:" field to the
 *    winner at L$0, the parcel SLURL, a manual "Verify Sell To" button
 *    (capped at 3 manual attempts, after which the bot auto-checks every
 *    30 min), the last-result inline error, the transfer-deadline badge, and
 *    a "Request manual review" escalation. Winner sees a waiting banner +
 *    SLURL.
 *  - **Buy Parcel** (`sellToConfirmedAt` set, `transferConfirmedAt == null`):
 *    winner buys the now-L$0 parcel (SLURL + only-if-L$0 guidance + "Verify
 *    purchase" + review). Seller sees a waiting banner with the same verify +
 *    review affordances.
 *  - **Post-confirmation** (`transferConfirmedAt` set): unchanged
 *    role-neutral payout-pending acknowledgement; no dispute link because the
 *    backend is about to flip the escrow to COMPLETED.
 *
 * Every pre-confirmation sub-phase card surfaces both the no-fault "Request
 * manual review" escalation and the separate "File a dispute" link — they are
 * deliberately coexisting mechanisms (spec §2 decision 2, §12), not
 * substitutes.
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

  const sellToConfirmed = escrow.sellToConfirmedAt != null;

  if (!sellToConfirmed) {
    return role === "seller" ? (
      <SellerSetSellToCard escrow={escrow} />
    ) : (
      <WinnerWaitingSellToCard escrow={escrow} />
    );
  }

  return role === "winner" ? (
    <WinnerBuyParcelCard escrow={escrow} />
  ) : (
    <SellerWaitingBuyCard escrow={escrow} />
  );
}

/* ─────────────────────────── shared bits ─────────────────────────── */

function ParcelSlurlLink({ escrow }: { escrow: EscrowStatusResponse }) {
  // parcelMapUrl is an absolute external SL maps URL (not a backend-relative
  // path), so it is rendered directly — no apiUrl() wrapping.
  if (!escrow.parcelMapUrl) return null;
  return (
    <a
      href={escrow.parcelMapUrl}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex w-fit items-center gap-1.5 text-sm font-medium text-brand hover:underline"
    >
      <ExternalLink className="size-4" aria-hidden="true" />
      Open parcel in the SL map
    </a>
  );
}

function DeadlineRow({ escrow }: { escrow: EscrowStatusResponse }) {
  if (!escrow.transferDeadline) return null;
  return (
    <div className="flex items-center gap-2 text-sm">
      <span className="text-fg-muted">Transfer deadline:</span>
      <EscrowDeadlineBadge deadline={escrow.transferDeadline} />
    </div>
  );
}

/**
 * "File a dispute" link. The no-fault "request manual review" escalation and
 * the dispute flow are deliberately separate, coexisting mechanisms (spec
 * 2026-05-17 §2 decision 2, §12). TRANSFER_PENDING is the canonical phase for
 * filing disputes (e.g. SELLER_NOT_RESPONSIVE), so every pre-confirmation
 * sub-phase card surfaces it alongside the manual-review control. The
 * post-confirmation payout-pending card intentionally omits it.
 */
function DisputeLink({ escrow }: { escrow: EscrowStatusResponse }) {
  return (
    <Link
      href={`/auction/${escrow.auctionPublicId}/escrow/dispute`}
      className="text-sm font-medium text-brand hover:underline"
    >
      File a dispute
    </Link>
  );
}

function LastResultError({ result }: { result: string | null }) {
  if (!result) return null;
  return (
    <p
      data-testid="sell-to-last-result"
      className="flex items-start gap-2 rounded-md border border-danger bg-danger-bg p-3 text-sm text-danger"
    >
      <AlertTriangle
        className="mt-0.5 size-4 shrink-0"
        aria-hidden="true"
      />
      <span>
        Last check failed: <span className="font-mono">{result}</span>. Fix the
        parcel&apos;s sale settings and verify again.
      </span>
    </p>
  );
}

/**
 * Attempts counter + "Request manual review" escalation. The review link is
 * always available but is rendered prominently (button styling) when no
 * manual verify attempts remain so a stuck party has an obvious next step.
 */
function VerifyControls({
  escrow,
  attemptsRemaining,
  verifyLabel,
  onVerify,
  verifyPending,
  reviewPending,
  onRequestReview,
}: {
  escrow: EscrowStatusResponse;
  attemptsRemaining: number;
  verifyLabel: string;
  onVerify: () => void;
  verifyPending: boolean;
  reviewPending: boolean;
  onRequestReview: () => void;
}) {
  const exhausted = attemptsRemaining <= 0;
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-3">
        <Button
          variant="primary"
          size="md"
          disabled={exhausted}
          loading={verifyPending}
          onClick={onVerify}
        >
          {verifyLabel}
        </Button>
        <span
          data-testid="verify-attempts"
          className="text-xs font-medium text-fg-muted"
        >
          {attemptsRemaining} of 3 manual attempts left
        </span>
      </div>
      <p className="text-xs text-fg-muted">
        After your manual attempts run out, SLParcels automatically re-checks
        every 30 min.
      </p>
      {escrow.manualReviewStatus === "OPEN" ? (
        <p className="text-xs font-medium text-fg-muted">
          A manual review is open. SLParcels staff will follow up.
        </p>
      ) : (
        <Button
          variant={exhausted ? "secondary" : "tertiary"}
          size="sm"
          loading={reviewPending}
          onClick={onRequestReview}
          className="w-fit"
        >
          Request manual review
        </Button>
      )}
    </div>
  );
}

/* ───────────────────────── Set Sell To ───────────────────────── */

function SellerSetSellToCard({
  escrow,
}: {
  escrow: EscrowStatusResponse;
}) {
  const verifySellTo = useVerifySellTo(escrow.auctionPublicId);
  const requestReview = useRequestManualReview(escrow.auctionPublicId);
  const attempts = escrow.sellToVerifyAttemptsRemaining ?? 0;

  return (
    <section
      data-testid="escrow-state-card"
      data-state="TRANSFER_PENDING"
      data-phase="set-sell-to"
      data-role="seller"
      className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
        Seller
      </span>
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Set the parcel for sale to the winner
      </h2>
      <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm text-fg-muted">
        <li>Right-click the parcel in-world and open About Land.</li>
        <li>Click Sell Land.</li>
        <li>
          {escrow.winnerSlAvatarName ? (
            <div className="flex flex-wrap items-center gap-2">
              <span>Set &quot;Sell to:&quot; to:</span>
              <code
                data-testid="winner-sl-avatar-name"
                className="rounded bg-bg-subtle px-1.5 py-0.5 font-mono text-xs text-fg"
              >
                {escrow.winnerSlAvatarName}
              </code>
              <CopyAvatarNameButton name={escrow.winnerSlAvatarName} />
            </div>
          ) : (
            <>Set &quot;Sell to:&quot; to the winner&apos;s avatar name.</>
          )}
        </li>
        <li>Set the price to L$ 0 (SLParcels has already escrowed payment).</li>
        <li>Confirm the sale in the SL viewer dialog.</li>
      </ol>

      <ParcelSlurlLink escrow={escrow} />

      <LastResultError result={escrow.sellToLastResult} />

      <VerifyControls
        escrow={escrow}
        attemptsRemaining={attempts}
        verifyLabel="Verify Sell To"
        onVerify={() => verifySellTo.mutate()}
        verifyPending={verifySellTo.isPending}
        reviewPending={requestReview.isPending}
        onRequestReview={() => requestReview.mutate(undefined)}
      />

      <DeadlineRow escrow={escrow} />
      <DisputeLink escrow={escrow} />
    </section>
  );
}

function WinnerWaitingSellToCard({
  escrow,
}: {
  escrow: EscrowStatusResponse;
}) {
  return (
    <section
      data-testid="escrow-state-card"
      data-state="TRANSFER_PENDING"
      data-phase="set-sell-to"
      data-role="winner"
      className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
        Winner
      </span>
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Waiting for the seller to set the parcel for sale
      </h2>
      <p className="text-sm text-fg-muted">
        The seller is configuring the parcel&apos;s &quot;Sell to:&quot; field
        in Second Life. Once SLParcels verifies it, you&apos;ll be able to buy
        the parcel for L$0.
      </p>
      <ParcelSlurlLink escrow={escrow} />
      <DeadlineRow escrow={escrow} />
      <DisputeLink escrow={escrow} />
    </section>
  );
}

/* ───────────────────────── Buy Parcel ───────────────────────── */

function WinnerBuyParcelCard({ escrow }: { escrow: EscrowStatusResponse }) {
  const verifyTransfer = useVerifyTransfer(escrow.auctionPublicId);
  const requestReview = useRequestManualReview(escrow.auctionPublicId);
  const attempts = escrow.buyVerifyBuyerAttemptsRemaining ?? 0;

  return (
    <section
      data-testid="escrow-state-card"
      data-state="TRANSFER_PENDING"
      data-phase="buy-parcel"
      data-role="winner"
      className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
        Winner
      </span>
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Buy the parcel
      </h2>
      <p className="text-sm text-fg-muted">
        The seller has listed the parcel for sale to you. Open it in Second
        Life and buy it now,{" "}
        <span className="font-medium text-fg">
          only if the price shows L$ 0
        </span>
        . SLParcels has already escrowed your payment; if the viewer shows any
        other price, do not buy and request a manual review.
      </p>

      <ParcelSlurlLink escrow={escrow} />

      <VerifyControls
        escrow={escrow}
        attemptsRemaining={attempts}
        verifyLabel="Verify purchase"
        onVerify={() => verifyTransfer.mutate()}
        verifyPending={verifyTransfer.isPending}
        reviewPending={requestReview.isPending}
        onRequestReview={() => requestReview.mutate(undefined)}
      />

      <DeadlineRow escrow={escrow} />
      <DisputeLink escrow={escrow} />
    </section>
  );
}

function SellerWaitingBuyCard({ escrow }: { escrow: EscrowStatusResponse }) {
  const verifyTransfer = useVerifyTransfer(escrow.auctionPublicId);
  const requestReview = useRequestManualReview(escrow.auctionPublicId);
  const attempts = escrow.buyVerifySellerAttemptsRemaining ?? 0;

  return (
    <section
      data-testid="escrow-state-card"
      data-state="TRANSFER_PENDING"
      data-phase="buy-parcel"
      data-role="seller"
      className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
        Seller
      </span>
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Waiting for the winner to buy the parcel
      </h2>
      <p className="text-sm text-fg-muted">
        You&apos;ve set the parcel for sale at L$0 and SLParcels verified it.
        The winner is completing the purchase in Second Life. This flips to
        Complete automatically once the buy is detected.
      </p>

      <VerifyControls
        escrow={escrow}
        attemptsRemaining={attempts}
        verifyLabel="Verify purchase"
        onVerify={() => verifyTransfer.mutate()}
        verifyPending={verifyTransfer.isPending}
        reviewPending={requestReview.isPending}
        onRequestReview={() => requestReview.mutate(undefined)}
      />

      <DeadlineRow escrow={escrow} />
      <DisputeLink escrow={escrow} />
    </section>
  );
}

/* ───────────────────────── helpers ───────────────────────── */

function formatTimestamp(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

/**
 * Small inline button that copies the winner's SL avatar name to the
 * clipboard. Shows a brief "Copied" confirmation for ~1.5s after a
 * successful copy; clipboard rejection is non-fatal because the name is
 * also visible on screen.
 */
function CopyAvatarNameButton({ name }: { name: string }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(name);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard rejection is non-fatal; the name is also visible on screen.
    }
  };
  return (
    <button
      type="button"
      onClick={handleCopy}
      className="inline-flex items-center gap-1 text-xs font-medium text-brand hover:underline"
      data-testid="copy-winner-sl-avatar-name-btn"
    >
      <Copy className="size-3" aria-hidden="true" />
      {copied ? "Copied" : "Copy"}
    </button>
  );
}
