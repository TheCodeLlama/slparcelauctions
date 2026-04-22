import { AlertCircle } from "@/components/ui/icons";
import { EmptyState } from "@/components/ui/EmptyState";

export interface EscrowPageErrorProps {
  error: Error;
}

/**
 * Non-404 error state for the escrow page. Covers 403 (forbidden), 5xx,
 * and network-level failures. 404 is handled separately via
 * {@link EscrowPageEmpty}.
 */
export function EscrowPageError({ error }: EscrowPageErrorProps) {
  return (
    <EmptyState
      icon={AlertCircle}
      headline="Could not load escrow status"
      description={error.message}
    />
  );
}
