import type { EscrowStatusResponse } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";
import { PendingStateCard } from "./state/PendingStateCard";
import { TransferPendingStateCard } from "./state/TransferPendingStateCard";
import { CompletedStateCard } from "./state/CompletedStateCard";
import { DisputedStateCard } from "./state/DisputedStateCard";
import { FrozenStateCard } from "./state/FrozenStateCard";
import { ExpiredStateCard } from "./state/ExpiredStateCard";

export interface EscrowStepCardProps {
  escrow: EscrowStatusResponse;
  role: EscrowChipRole;
}

/**
 * Dispatcher that routes to the correct per-state card based on
 * `escrow.state`. `FUNDED` is collapsed into `TRANSFER_PENDING` because
 * sub-spec 1 advances `FUNDED → TRANSFER_PENDING` atomically within the
 * same transaction — external observers rarely see the transient state,
 * but we handle it defensively so the UI never falls through.
 *
 * The exhaustiveness check (`_exhaustive: never`) forces a type error
 * if the `EscrowState` union ever gains a new variant without a case
 * here, making this the forcing function for new-state UI work.
 */
export function EscrowStepCard({ escrow, role }: EscrowStepCardProps) {
  switch (escrow.state) {
    case "ESCROW_PENDING":
      return <PendingStateCard escrow={escrow} role={role} />;
    case "FUNDED":
    case "TRANSFER_PENDING":
      return <TransferPendingStateCard escrow={escrow} role={role} />;
    case "COMPLETED":
      return <CompletedStateCard escrow={escrow} role={role} />;
    case "DISPUTED":
      return <DisputedStateCard escrow={escrow} role={role} />;
    case "FROZEN":
      return <FrozenStateCard escrow={escrow} role={role} />;
    case "EXPIRED":
      return <ExpiredStateCard escrow={escrow} role={role} />;
    default: {
      const _exhaustive: never = escrow.state;
      throw new Error(`Unhandled escrow state: ${_exhaustive}`);
    }
  }
}
