import Link from "next/link";
import { FileX } from "@/components/ui/icons";
import { EmptyState } from "@/components/ui/EmptyState";

export interface EscrowPageEmptyProps {
  auctionId: number;
}

/**
 * 404 / "no escrow yet" state for the escrow page. Surfaced when the
 * auction exists but the escrow endpoint returns 404 (e.g. the auction
 * ended without a winner, or escrow creation is still pending).
 */
export function EscrowPageEmpty({ auctionId }: EscrowPageEmptyProps) {
  return (
    <EmptyState
      icon={FileX}
      headline="No escrow for this auction"
      description="Either this auction hasn't ended with a winner yet, or no escrow was created."
    >
      <Link href={`/auction/${auctionId}`} className="text-primary hover:underline">
        Back to auction
      </Link>
    </EmptyState>
  );
}
