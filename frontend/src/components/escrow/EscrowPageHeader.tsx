import type { EscrowStatusResponse } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";
import { EscrowChip } from "@/components/escrow/EscrowChip";

export interface EscrowPageHeaderProps {
  escrow: EscrowStatusResponse;
  role: EscrowChipRole;
}

/**
 * Parcel summary + role label + state chip rendered at the top of the
 * escrow page. The chip is sized {@code md} here (vs. {@code sm} in
 * dashboard rows) so it reads as the page's primary status affordance.
 */
export function EscrowPageHeader({ escrow, role }: EscrowPageHeaderProps) {
  return (
    <header className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <div className="text-[11px] font-medium uppercase tracking-wide text-fg-muted">
          Escrow · {role}
        </div>
        <div className="text-xs text-fg-muted">
          L$ {escrow.finalBidAmount.toLocaleString()} final
        </div>
      </div>
      <EscrowChip
        state={escrow.state}
        transferConfirmedAt={escrow.transferConfirmedAt}
        role={role}
        size="md"
      />
    </header>
  );
}
