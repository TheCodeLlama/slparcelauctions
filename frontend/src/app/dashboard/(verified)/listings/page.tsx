import { EmptyState } from "@/components/ui/EmptyState";
import { ListChecks } from "@/components/ui/icons";

export default function ListingsPage() {
  return (
    <EmptyState
      icon={ListChecks}
      headline="No listings yet"
      description="Parcels you put up for auction will appear here."
    />
  );
}
