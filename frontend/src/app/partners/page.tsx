import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Partners" };

export default function PartnersPage() {
  return <PageHeader title="Partners" subtitle="Our verification and bot service partners." />;
}
