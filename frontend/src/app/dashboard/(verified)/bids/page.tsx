import { EmptyState } from "@/components/ui/EmptyState";
import { Gavel } from "@/components/ui/icons";

export default function BidsPage() {
  return (
    <EmptyState
      icon={Gavel}
      headline="No bids yet"
      description="Your active and historical bids will appear here once auctions go live."
    />
  );
}
