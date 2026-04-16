import { StatusBadge } from "@/components/ui/StatusBadge";

export function NewSellerBadge({ completedSales }: { completedSales: number }) {
  if (completedSales >= 3) return null;
  return <StatusBadge tone="warning">New Seller</StatusBadge>;
}
