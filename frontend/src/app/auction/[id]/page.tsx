import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Auction" };

export default async function AuctionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return (
    <PageHeader
      title={`Auction #${id}`}
      breadcrumbs={[
        { label: "Browse", href: "/browse" },
        { label: `Auction #${id}` },
      ]}
    />
  );
}
