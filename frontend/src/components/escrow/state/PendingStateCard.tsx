import type { StateCardProps } from "./types";

/**
 * ESCROW_PENDING state card. Post wallet-only-escrow spec (2026-05-16)
 * this state is a transactional intermediate: the escrow is funded from
 * the winner's wallet inside the same transaction that creates the row,
 * so external observers never see it persist. This card only renders for
 * legacy historical rows that were created before the migration; it
 * shows a passive "funding in progress" status with no terminal /
 * dispute affordance.
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

      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Escrow funding in progress
      </h2>
      <p className="text-sm text-fg-muted">
        L$ {escrow.finalBidAmount.toLocaleString()} is being transferred
        from the winner&apos;s SLParcels wallet to escrow. This page will
        update automatically.
      </p>
    </section>
  );
}
