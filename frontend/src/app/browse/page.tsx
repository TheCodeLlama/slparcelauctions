import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Browse Auctions" };

export default function BrowsePage() {
  return (
    <PageHeader
      title="Browse Auctions"
      subtitle="Active land listings across the grid."
    />
  );
}
