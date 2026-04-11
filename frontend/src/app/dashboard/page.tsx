import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Dashboard" };

export default function DashboardPage() {
  return (
    <PageHeader
      title="Dashboard"
      subtitle="Your bids, listings, and sales."
    />
  );
}
