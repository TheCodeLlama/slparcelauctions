import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "About" };

export default function AboutPage() {
  return <PageHeader title="About SLParcels" subtitle="The story behind the auctions." />;
}
