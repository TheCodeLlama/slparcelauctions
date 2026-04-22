import { LoadingSpinner } from "@/components/ui/LoadingSpinner";

/**
 * Loading state for the escrow page. Surfaced while the initial
 * {@code getEscrowStatus} query is in flight.
 */
export function EscrowPageSkeleton() {
  return <LoadingSpinner label="Loading escrow status..." />;
}
