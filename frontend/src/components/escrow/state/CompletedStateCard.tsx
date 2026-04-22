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
      className="flex flex-col gap-3 rounded-lg border border-outline-variant bg-surface-container-lowest p-5 shadow-soft"
    >
      <span className="text-label-sm uppercase tracking-wide text-on-surface-variant">
        {role === "seller" ? "Seller" : "Winner"}
      </span>
      {role === "seller" ? (
        <>
          <h2 className="text-title-md text-on-surface">
            Payout of L$ {escrow.payoutAmt.toLocaleString()} sent
          </h2>
          <p className="text-body-md text-on-surface-variant">
            Commission L$ {escrow.commissionAmt.toLocaleString()}.
            {escrow.completedAt
              ? ` Completed ${formatTimestamp(escrow.completedAt)}.`
              : ""}
          </p>
        </>
      ) : (
        <>
          <h2 className="text-title-md text-on-surface">Parcel transferred</h2>
          <p className="text-body-md text-on-surface-variant">
            You&apos;re the owner of{" "}
            <span className="font-medium text-on-surface">
              {escrow.parcelName}
            </span>{" "}
            in{" "}
            <span className="font-medium text-on-surface">{escrow.region}</span>
            .
            {escrow.completedAt
              ? ` Completed ${formatTimestamp(escrow.completedAt)}.`
              : ""}
          </p>
        </>
      )}
    </section>
  );
}

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
