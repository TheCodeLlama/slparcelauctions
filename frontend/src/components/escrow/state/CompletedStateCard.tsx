import type { StateCardProps } from "./types";

/**
 * COMPLETED state card. Terminal success state — the backend has both
 * confirmed ownership transfer and dispatched the payout, and the escrow
 * is closed. No CTAs, no dispute link. Seller sees the payout breakdown;
 * winner sees a parcel-ownership confirmation.
 */
export function CompletedStateCard({ escrow, role }: StateCardProps) {
  return (
    <section
      data-testid="escrow-state-card"
      data-state="COMPLETED"
      data-role={role}
      className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <span className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
        {role === "seller" ? "Seller" : "Winner"}
      </span>
      {role === "seller" ? (
        <SellerPayoutBreakdown escrow={escrow} />
      ) : (
        <>
          <h2 className="text-sm font-semibold tracking-tight text-fg">Parcel transferred</h2>
          <p className="text-sm text-fg-muted">
            The parcel has been transferred to you.
            {escrow.completedAt
              ? ` Completed ${formatTimestamp(escrow.completedAt)}.`
              : ""}
          </p>
        </>
      )}
    </section>
  );
}

/**
 * Seller-facing payout breakdown. Replaces the prior single-line "Commission
 * L$N" copy that left sellers asking why a L$100 sale only netted them L$50.
 *
 * For individual sales (`payoutAmt > 0`) the breakdown lays out:
 *   - Sale price (anchor — the seller knows what the parcel sold for)
 *   - SLParcels fee (with the 5% / L$50-minimum rule inline so the floor on
 *     small sales doesn't look arbitrary)
 *   - Net payout (now credited to the SLParcels wallet, not the avatar)
 *
 * Headline copy says "L$X added to your SLParcels wallet" to make the
 * in-wallet location unambiguous post wallet-first cutover. The seller
 * withdraws to their avatar separately via the wallet flow.
 *
 * "Fee" intentionally — "commission" is reserved for the agent-commission
 * slice on group listings per the realty-group spec; mislabelling the
 * platform's per-sale cut as "commission" confused sellers post-launch.
 *
 * For group sales (`payoutAmt === 0`, group-sale fields present) the L$ split
 * goes to the listing agent + group wallet rather than the seller's avatar.
 * The breakdown shows sale price, SLParcels fee, agent commission (seller's
 * cut as listing agent), and the group-wallet credit so the seller can
 * reconcile the full split.
 *
 * If `payoutAmt === 0` but the group-sale fields are missing (defensive
 * fallback for a wire-shape regression), the minimal "Sale completed"
 * acknowledgement is shown instead of forcing a misleading "Payout of L$0"
 * headline.
 */
function SellerPayoutBreakdown({
  escrow,
}: {
  escrow: StateCardProps["escrow"];
}) {
  const completedSuffix = escrow.completedAt
    ? ` Completed ${formatTimestamp(escrow.completedAt)}.`
    : "";

  if (escrow.payoutAmt === 0) {
    if (escrow.agentCommissionAmt == null || escrow.groupSliceAmt == null) {
      return (
        <>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Sale completed
          </h2>
          <p className="text-sm text-fg-muted">
            Sale price: L$ {escrow.finalBidAmount.toLocaleString()}. Payout was
            split to the listing agent and the group wallet.{completedSuffix}
          </p>
        </>
      );
    }
    const groupWalletLabel = escrow.groupName
      ? `${escrow.groupName} group wallet`
      : "Group wallet";
    return (
      <>
        <h2 className="text-sm font-semibold tracking-tight text-fg">
          Sale completed
        </h2>
        <dl className="grid grid-cols-[1fr_auto] gap-y-1 text-sm text-fg-muted">
          <dt>Sale price</dt>
          <dd className="text-right tabular-nums text-fg">
            L$ {escrow.finalBidAmount.toLocaleString()}
          </dd>
          <dt>SLParcels fee (5%, L$50 min)</dt>
          <dd className="text-right tabular-nums text-fg">
            &minus; L$ {escrow.commissionAmt.toLocaleString()}
          </dd>
          <dt className="border-t border-border-subtle pt-1 text-fg">
            Agent commission (your cut)
          </dt>
          <dd className="border-t border-border-subtle pt-1 text-right tabular-nums text-fg">
            L$ {escrow.agentCommissionAmt.toLocaleString()}
          </dd>
          <dt className="text-fg">{groupWalletLabel}</dt>
          <dd className="text-right tabular-nums text-fg">
            L$ {escrow.groupSliceAmt.toLocaleString()}
          </dd>
        </dl>
        {completedSuffix ? (
          <p className="text-xs text-fg-muted">{completedSuffix.trim()}</p>
        ) : null}
      </>
    );
  }

  return (
    <>
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        L$ {escrow.payoutAmt.toLocaleString()} added to your SLParcels wallet
      </h2>
      <dl className="grid grid-cols-[1fr_auto] gap-y-1 text-sm text-fg-muted">
        <dt>Sale price</dt>
        <dd className="text-right tabular-nums text-fg">
          L$ {escrow.finalBidAmount.toLocaleString()}
        </dd>
        <dt>SLParcels fee (5%, L$50 min)</dt>
        <dd className="text-right tabular-nums text-fg">
          &minus; L$ {escrow.commissionAmt.toLocaleString()}
        </dd>
        <dt className="border-t border-border-subtle pt-1 font-medium text-fg">
          Your payout
        </dt>
        <dd className="border-t border-border-subtle pt-1 text-right tabular-nums font-medium text-fg">
          L$ {escrow.payoutAmt.toLocaleString()}
        </dd>
      </dl>
      {completedSuffix ? (
        <p className="text-xs text-fg-muted">
          {completedSuffix.trim()}
        </p>
      ) : null}
    </>
  );
}

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
