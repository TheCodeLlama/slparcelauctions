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
 * For individual sales (case-1, `payoutAmt > 0`) the breakdown lays out:
 *   - Sale price (anchor — the seller knows what the parcel sold for)
 *   - SLParcels fee (with the 5% / L$50-minimum rule inline so the floor on
 *     small sales doesn't look arbitrary)
 *   - Net payout
 *
 * "Fee" intentionally — "commission" is reserved for the agent-commission
 * slice on group listings (case-3) per the realty-group spec; mislabelling
 * the platform's per-sale cut as "commission" confused sellers post-launch.
 *
 * For group listings (case-3, `payoutAmt === 0`) the L$ split goes to the
 * listing agent + group wallet, not to the seller's avatar. The escrow DTO
 * doesn't surface the agent / group-wallet split here today, so we show a
 * minimal acknowledgement instead of forcing a "Payout of L$0" headline that
 * would only confuse the lister. Full case-3 breakdown is a follow-up that
 * needs the agent-commission + group-slice fields surfaced on the DTO.
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

  return (
    <>
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Payout of L$ {escrow.payoutAmt.toLocaleString()} sent
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
