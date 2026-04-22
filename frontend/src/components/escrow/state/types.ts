import type { EscrowStatusResponse } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";

/**
 * Shared prop shape for every per-state card rendered by `EscrowStepCard`.
 * Each card consumes the full `EscrowStatusResponse` rather than picking
 * fields — some cards branch on secondary timestamps (e.g. `fundedAt` for
 * EXPIRED, `transferConfirmedAt` for TRANSFER_PENDING) that vary per state.
 */
export interface StateCardProps {
  escrow: EscrowStatusResponse;
  role: EscrowChipRole;
}
